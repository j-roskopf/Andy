package app.andy.desktop.service.ios

import app.andy.desktop.service.mirror.NativeMirrorHostRegistry
import app.andy.desktop.service.mirror.NativeMirrorJni
import app.andy.model.IosTargetKind
import app.andy.model.IosTargetState
import app.andy.model.iosNormalizedTouchCoordinates
import app.andy.service.CommandResult
import app.andy.service.IosTargetRegistry
import app.andy.service.MirrorBackend
import app.andy.service.MirrorBackendKind
import app.andy.service.MirrorEngine
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.service.MirrorRendererMode
import app.andy.service.MirrorSession
import app.andy.service.MirrorStats
import app.andy.service.MirrorTouchAction
import app.andy.service.MirrorVideoConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val NATIVE_HOST_WAIT_NANOS = 8_000_000_000L
private const val NATIVE_HOST_WAIT_STEP_MILLIS = 16L

class DesktopIosMirrorEngine(
    private val iosDevices: app.andy.service.IosDeviceService? = null,
) : MirrorEngine {
    override val session = MutableStateFlow<MirrorSession?>(null)
    override val frames = MutableStateFlow(MirrorFrame(1, 1, intArrayOf(0xff000000.toInt())))
    override val status = MutableStateFlow("Ready for iOS mirror")
    private var connectedUdid: String? = null
    private var nextFrameNumber = 1L
    private var statsJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun connect(serial: String, config: MirrorVideoConfig): CommandResult = withContext(Dispatchers.IO) {
        if (connectedUdid == serial && session.value != null) {
            rebindPresentation()
            return@withContext CommandResult.success("iOS mirror already connected for $serial")
        }
        disconnect(immediate = true)
        connectedUdid = serial
        val target = IosTargetRegistry.target(serial)
        if (target?.kind == IosTargetKind.Simulator && target.state == IosTargetState.Shutdown) {
            iosDevices?.boot(serial)?.takeIf { !it.isSuccess }?.let { return@withContext it }
            var booted = false
            for (attempt in 0 until 120) {
                delay(500)
                if (iosDevices?.listTargets()
                        ?.firstOrNull { it.udid == serial }
                        ?.state == IosTargetState.Booted
                ) {
                    IosTargetRegistry.update(
                        IosTargetRegistry.targets.value.map { entry ->
                            if (entry.udid == serial) entry.copy(state = IosTargetState.Booted) else entry
                        },
                    )
                    booted = true
                    break
                }
            }
            if (!booted) {
                val reason = "Simulator is still booting — try again in a few seconds"
                session.value = MirrorSession(
                    serial = serial,
                    requestedMode = config.rendererMode,
                    backend = MirrorBackend(MirrorBackendKind.Unavailable, fallbackReason = reason),
                    failureReason = reason,
                )
                return@withContext CommandResult.failure(reason)
            }
        }
        if (!NativeMirrorJni.isEmbeddedPresentationSupported()) {
            val reason = NativeMirrorJni.embeddedPresentationFailureReason()
            session.value = MirrorSession(
                serial = serial,
                requestedMode = config.rendererMode,
                backend = MirrorBackend(MirrorBackendKind.Unavailable, fallbackReason = reason),
                failureReason = reason,
            )
            return@withContext CommandResult.failure(reason)
        }
        val host = awaitNativeHost()
        if (host == null) {
            val reason = "Live surface is not ready for Metal presentation"
            session.value = MirrorSession(
                serial = serial,
                requestedMode = config.rendererMode,
                backend = MirrorBackend(MirrorBackendKind.Unavailable, fallbackReason = reason),
                failureReason = reason,
            )
            return@withContext CommandResult.failure(reason)
        }
        status.value = "Starting iOS screen capture…"
        val size = when (target?.kind) {
            IosTargetKind.Physical -> {
                val captureSize = connectPhysicalDevice(
                    serial,
                    target.displayName.ifBlank { IosTargetRegistry.target(serial)?.displayName.orEmpty() },
                    target.coreDeviceIdentifier ?: IosTargetRegistry.target(serial)?.coreDeviceIdentifier,
                )
                if (captureSize == null || captureSize[0] <= 0 || captureSize[1] <= 0) {
                    null
                } else if (!openMetalPresentation(host)) {
                    disconnectIosCapture(serial)
                    null
                } else {
                    captureSize
                }
            }
            else -> {
                if (!NativeIosSimJni.isAvailable()) {
                    val reason = NativeIosSimJni.diagnostic().ifBlank { "iOS simulator mirroring is unavailable on this Mac" }
                    session.value = MirrorSession(serial, config.rendererMode, MirrorBackend(MirrorBackendKind.Unavailable, fallbackReason = reason), failureReason = reason)
                    return@withContext CommandResult.failure(reason)
                }
                if (!openMetalPresentation(host)) {
                    null
                } else {
                    NativeIosSimJni.connect(serial)
                }
            }
        }
        if (size == null || size[0] <= 0 || size[1] <= 0) {
            val reason = when (target?.kind) {
                IosTargetKind.Physical -> NativeIosDeviceJni.diagnostic().ifBlank { "Physical device mirror failed" }
                else -> when {
                    !NativeMirrorJni.isMetalInlineOverlayOpen() -> "Metal inline overlay failed to open"
                    else -> NativeIosSimJni.diagnostic().ifBlank { "Simulator mirror failed" }
                }
            }
            disconnectIosCapture(serial)
            session.value = MirrorSession(serial, config.rendererMode, MirrorBackend(MirrorBackendKind.Unavailable, fallbackReason = reason), failureReason = reason)
            return@withContext CommandResult.failure(reason)
        }
        NativeMirrorJni.setPresentationContentSize(size[0], size[1])
        NativeMirrorJni.repaintLatestFrame()
        frames.value = MirrorFrame(size[0], size[1], intArrayOf(), frameNumber = nextFrameNumber++)
        session.value = MirrorSession(
            serial = serial,
            requestedMode = config.rendererMode,
            backend = MirrorBackend(
                kind = MirrorBackendKind.NativeHardware,
                decoder = if (target?.kind == IosTargetKind.Physical) "CMIO" else "SimulatorKit",
                renderer = "Metal",
            ),
            width = size[0],
            height = size[1],
        )
        status.value = "Connected to iOS target $serial (${size[0]}x${size[1]})"
        startStats()
        CommandResult.success(status.value)
    }

    private suspend fun connectPhysicalDevice(
        udid: String,
        displayName: String,
        coreDeviceIdentifier: String?,
    ): IntArray? {
        status.value = "Waiting for iPhone screen device…"
        NativeIosDeviceJni.prepareForCapture()
        val started = NativeIosDeviceJni.connect(udid, displayName, coreDeviceIdentifier) ?: return null
        if (started[0] > 0 && started[1] > 0) return started
        repeat(50) {
            delay(100)
            val size = NativeIosDeviceJni.contentSize()
            if (size[0] > 0 && size[1] > 0) return size
        }
        return null
    }

    private suspend fun awaitNativeHost(): java.awt.Canvas? {
        NativeMirrorHostRegistry.current()?.let { return it }
        val deadline = System.nanoTime() + NATIVE_HOST_WAIT_NANOS
        while (System.nanoTime() < deadline) {
            delay(NATIVE_HOST_WAIT_STEP_MILLIS)
            NativeMirrorHostRegistry.current()?.let { return it }
        }
        return null
    }

    private fun disconnectIosCapture(serial: String) {
        when (IosTargetRegistry.target(serial)?.kind) {
            IosTargetKind.Physical -> NativeIosDeviceJni.disconnect()
            else -> NativeIosSimJni.disconnect()
        }
        NativeMirrorJni.destroyPresentation()
    }

    private fun openMetalPresentation(host: java.awt.Component): Boolean {
        if (!NativeMirrorJni.openMetalInlineOverlay(host)) return false
        NativeMirrorJni.repaintLatestFrame()
        return true
    }

    private fun rebindPresentation() {
        val host = NativeMirrorHostRegistry.current() ?: return
        if (!NativeMirrorJni.isMetalInlineOverlayOpen()) {
            openMetalPresentation(host)
        } else {
            NativeMirrorJni.setInlineOverlayVisible(true)
            NativeMirrorJni.updateMetalLayerGeometry(host)
            NativeMirrorJni.repaintLatestFrame()
        }
    }

    private fun startStats() {
        statsJob?.cancel()
        statsJob = scope.launch {
            var lastPresented = NativeMirrorJni.framesPresented()
            var lastTick = System.nanoTime()
            while (isActive && connectedUdid != null) {
                delay(1_000)
                val presented = NativeMirrorJni.framesPresented()
                val now = System.nanoTime()
                val fps = ((presented - lastPresented).toFloat() * 1_000_000_000f) / (now - lastTick).coerceAtLeast(1)
                lastPresented = presented
                lastTick = now
                val current = session.value ?: continue
                session.value = current.copy(
                    stats = MirrorStats(
                        displayedFps = fps,
                        framesPresented = presented,
                        p95InputToPresentMillis = NativeMirrorJni.p95InputToPresentMillis(),
                    ),
                )
            }
        }
    }

    override suspend fun disconnect(immediate: Boolean) {
        statsJob?.cancel()
        statsJob = null
        val udid = connectedUdid
        val hadSession = udid != null && session.value != null
        connectedUdid = null
        if (udid != null) {
            when (IosTargetRegistry.target(udid)?.kind) {
                IosTargetKind.Physical -> NativeIosDeviceJni.disconnect()
                else -> NativeIosSimJni.disconnect()
            }
        }
        if (hadSession) {
            if (immediate) {
                NativeMirrorJni.destroyPresentation()
            } else {
                delay(500)
                if (connectedUdid == null) NativeMirrorJni.destroyPresentation()
            }
        }
        session.value = null
        frames.value = MirrorFrame(1, 1, intArrayOf(0xff000000.toInt()), frameNumber = nextFrameNumber++)
        status.value = "Disconnected"
    }

    override suspend fun sendInput(input: MirrorInput): CommandResult {
        val udid = connectedUdid ?: return CommandResult.failure("No iOS mirror connected")
        val target = IosTargetRegistry.target(udid) ?: return CommandResult.failure("Unknown iOS target")
        if (target.kind != IosTargetKind.Simulator) {
            return CommandResult.failure("Input is not supported for physical iOS devices")
        }
        val mirrorWidth = session.value?.width?.takeIf { it > 0 } ?: NativeIosSimJni.contentSizePoints()[0]
        val mirrorHeight = session.value?.height?.takeIf { it > 0 } ?: NativeIosSimJni.contentSizePoints()[1]
        when (input) {
            is MirrorInput.Touch -> {
                val action = when (input.action) {
                    MirrorTouchAction.Down -> 0
                    MirrorTouchAction.Move -> 1
                    MirrorTouchAction.Up -> 2
                }
                val (nx, ny) = iosNormalizedTouchCoordinates(input.x, input.y, mirrorWidth, mirrorHeight)
                NativeIosSimJni.sendTouch(action, nx, ny)
            }
            is MirrorInput.Tap -> {
                val (nx, ny) = iosNormalizedTouchCoordinates(input.x, input.y, mirrorWidth, mirrorHeight)
                NativeIosSimJni.sendTouch(0, nx, ny)
                delay(170)
                NativeIosSimJni.sendTouch(2, nx, ny)
            }
            is MirrorInput.Swipe -> {
                val w = mirrorWidth.coerceAtLeast(1).toFloat()
                val h = mirrorHeight.coerceAtLeast(1).toFloat()
                NativeIosSimJni.sendSwipe(
                    input.startX / w, input.startY / h,
                    input.endX / w, input.endY / h,
                    (input.durationMillis / 16).coerceAtLeast(2),
                )
            }
            is MirrorInput.Text -> NativeIosSimJni.sendText(input.value)
            MirrorInput.Home -> NativeIosSimJni.sendButton(0)
            MirrorInput.Power -> NativeIosSimJni.sendButton(1)
            MirrorInput.Back, MirrorInput.Recents -> return CommandResult.failure("No iOS equivalent")
            is MirrorInput.Key -> return CommandResult.failure("Use text input for iOS keyboard")
        }
        return CommandResult.success("Sent")
    }

    override suspend fun screenshot(serial: String): ByteArray? {
        return NativeMirrorJni.copyLatestFrameArgb()?.let { frame ->
            // Bug capture uses MirrorFrame path elsewhere; returning null keeps parity with native metadata frames.
            null
        }
    }
}
