package app.andy.desktop.service.ios

import app.andy.desktop.service.mirror.GpuMirrorHostRegistry
import app.andy.desktop.service.mirror.GpuMirrorJni
import app.andy.desktop.service.mirror.GpuMirrorPipeline
import app.andy.desktop.service.mirror.GpuMirrorSessions
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

    /** Serial this engine currently holds a GPU pipeline reference for; null when unheld. */
    private var gpuPipelineKey: String? = null

    /** Idempotent: drops this engine's single pipeline reference. */
    private fun releaseGpuPipeline() {
        gpuPipelineKey?.let(GpuMirrorSessions::release)
        gpuPipelineKey = null
    }

    override suspend fun connect(serial: String, config: MirrorVideoConfig): CommandResult = withContext(Dispatchers.IO) {
        if (connectedUdid == serial && session.value != null) {
            activeGpuPipeline()?.repaintAll()
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
        if (!GpuMirrorJni.isAvailable() && !NativeMirrorJni.isEmbeddedPresentationSupported()) {
            val reason = NativeMirrorJni.embeddedPresentationFailureReason()
            session.value = MirrorSession(
                serial = serial,
                requestedMode = config.rendererMode,
                backend = MirrorBackend(MirrorBackendKind.Unavailable, fallbackReason = reason),
                failureReason = reason,
            )
            return@withContext CommandResult.failure(reason)
        }
        val useGpuHub = GpuMirrorJni.isAvailable() &&
            config.rendererMode != MirrorRendererMode.Legacy &&
            GpuMirrorSessions.acquire(serial) != null
        if (useGpuHub) gpuPipelineKey = serial
        val host = if (!useGpuHub) awaitNativeHost() else null
        if (!useGpuHub && host == null) {
            val reason = "Live surface is not ready for Metal presentation"
            session.value = MirrorSession(
                serial = serial,
                requestedMode = config.rendererMode,
                backend = MirrorBackend(MirrorBackendKind.Unavailable, fallbackReason = reason),
                failureReason = reason,
            )
            return@withContext CommandResult.failure(reason)
        }
        if (useGpuHub) {
            // Nudge Compose to attach a presenter against this pipeline before SimulatorKit
            // starts producing IOSurfaces (otherwise frames fan out to zero presenters).
            frames.value = MirrorFrame(2, 2, intArrayOf(), frameNumber = nextFrameNumber++)
            awaitGpuPresenter(serial)
            GpuMirrorSessions.get(serial)?.bindIosCapture()
            NativeMirrorJni.setInlineOverlayVisible(false)
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
                } else if (!useGpuHub && !openMetalPresentation(host!!)) {
                    disconnectIosCapture(serial)
                    null
                } else {
                    captureSize
                }
            }
            else -> {
                if (!NativeIosSimJni.isAvailable()) {
                    val reason = NativeIosSimJni.diagnostic().ifBlank { "iOS simulator mirroring is unavailable on this Mac" }
                    releaseGpuPipeline()
                    session.value = MirrorSession(serial, config.rendererMode, MirrorBackend(MirrorBackendKind.Unavailable, fallbackReason = reason), failureReason = reason)
                    return@withContext CommandResult.failure(reason)
                }
                // Headless simctl boot can mirror without Simulator.app, but HID injection cannot.
                // Bring Simulator.app up *before* SimDeviceIO attach — launching it afterwards
                // races the display pipeline and leaves Live black.
                iosDevices?.prepareEmbeddedMirror(serial)
                if (!useGpuHub && !openMetalPresentation(host!!)) {
                    null
                } else {
                    // Never block connect on HID warm-up: LegacyHID handshakes used to stall
                    // "Starting iOS screen capture…" and starve presenter attach.
                    NativeIosSimJni.connect(serial)
                }
            }
        }
        if (size == null || size[0] <= 0 || size[1] <= 0) {
            val reason = when (target?.kind) {
                IosTargetKind.Physical -> NativeIosDeviceJni.diagnostic().ifBlank { "Physical device mirror failed" }
                else -> when {
                    useGpuHub && NativeIosSimJni.diagnostic().isNotBlank() -> NativeIosSimJni.diagnostic()
                    !useGpuHub && !NativeMirrorJni.isMetalInlineOverlayOpen() -> "Metal inline overlay failed to open"
                    else -> NativeIosSimJni.diagnostic().ifBlank { "Simulator mirror failed" }
                }
            }
            disconnectIosCapture(serial)
            session.value = MirrorSession(serial, config.rendererMode, MirrorBackend(MirrorBackendKind.Unavailable, fallbackReason = reason), failureReason = reason)
            return@withContext CommandResult.failure(reason)
        }
        val presentedBeforeSession = if (useGpuHub) {
            val pipeline = GpuMirrorSessions.get(serial)
            pipeline?.setContentSize(size[0], size[1])
            // Publish size before the second wait so Compose maps touch into the real frame.
            frames.value = MirrorFrame(size[0], size[1], intArrayOf(), frameNumber = nextFrameNumber++)
            awaitGpuPresenter(serial)
            pipeline?.repaintAll()
            // Wait briefly for at least one presented frame so Live isn't left on a black host.
            awaitPresentedFrame(serial)
            // A device switch (e.g. Android -> iOS) recreates the Live surface while the previous
            // device's overlay window is still closing. Re-resolve parenting/geometry now so the
            // iOS overlay re-attaches to the real host window instead of staying black.
            delay(120)
            pipeline?.refreshAllGeometry()
            pipeline?.repaintAll()
            // Keep a fresh metadata tick so surfaces that attached mid-wait pick up content size.
            frames.value = MirrorFrame(size[0], size[1], intArrayOf(), frameNumber = nextFrameNumber++)
            pipeline?.framesPresented() ?: 0L
        } else {
            NativeMirrorJni.setPresentationContentSize(size[0], size[1])
            NativeMirrorJni.repaintLatestFrame()
            frames.value = MirrorFrame(size[0], size[1], intArrayOf(), frameNumber = nextFrameNumber++)
            NativeMirrorJni.framesPresented()
        }
        session.value = MirrorSession(
            serial = serial,
            requestedMode = config.rendererMode,
            backend = MirrorBackend(
                kind = MirrorBackendKind.NativeHardware,
                decoder = if (target?.kind == IosTargetKind.Physical) "CMIO" else "SimulatorKit",
                renderer = "Metal",
            ),
            // Seed presentation stats so Live's loading overlay clears immediately; startStats
            // only refreshes once per second and left Metal looking black under a dimmer.
            stats = MirrorStats(
                displayedFps = if (presentedBeforeSession > 0L) 1f else 0f,
                framesPresented = presentedBeforeSession,
            ),
            width = size[0],
            height = size[1],
        )
        status.value = "Connected to iOS target $serial (${size[0]}x${size[1]})"
        startStats()
        if (target?.kind != IosTargetKind.Physical) {
            // HID warm-up after pixels are live — never on the connect critical path.
            scope.launch {
                NativeIosSimJni.ensureInputReady()
            }
        }
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

    private suspend fun awaitGpuPresenter(serial: String): Boolean {
        val pipeline = GpuMirrorSessions.get(serial) ?: return false
        if (GpuMirrorHostRegistry.presentersForDecoder(pipeline.decoderId).isNotEmpty()) return true
        val deadline = System.nanoTime() + NATIVE_HOST_WAIT_NANOS
        while (System.nanoTime() < deadline) {
            delay(NATIVE_HOST_WAIT_STEP_MILLIS)
            if (GpuMirrorHostRegistry.presentersForDecoder(pipeline.decoderId).isNotEmpty()) return true
        }
        return GpuMirrorHostRegistry.presentersForDecoder(pipeline.decoderId).isNotEmpty()
    }

    private suspend fun awaitPresentedFrame(serial: String): Boolean {
        val pipeline = GpuMirrorSessions.get(serial) ?: return false
        if (pipeline.framesPresented() > 0L) return true
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            pipeline.repaintAll()
            delay(50)
            if (pipeline.framesPresented() > 0L) return true
        }
        return pipeline.framesPresented() > 0L
    }

    private fun disconnectIosCapture(serial: String) {
        when (IosTargetRegistry.target(serial)?.kind) {
            IosTargetKind.Physical -> NativeIosDeviceJni.disconnect()
            else -> NativeIosSimJni.disconnect()
        }
        // On the GPU hub path the legacy singleton overlay was never opened for this device;
        // only tear it down on the legacy path so a coexisting non-GPU surface is left intact.
        val wasGpu = gpuPipelineKey != null
        releaseGpuPipeline()
        if (!wasGpu) NativeMirrorJni.destroyPresentation()
    }

    private fun activeGpuPipeline(): GpuMirrorPipeline? =
        connectedUdid?.let(GpuMirrorSessions::get)

    private fun openMetalPresentation(host: java.awt.Component): Boolean {
        if (!NativeMirrorJni.openMetalInlineOverlay(host)) return false
        NativeMirrorJni.repaintLatestFrame()
        return true
    }

    private fun rebindPresentation() {
        activeGpuPipeline()?.let { pipeline ->
            pipeline.repaintAll()
            return
        }
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
            var lastPresented = activeGpuPipeline()?.framesPresented() ?: NativeMirrorJni.framesPresented()
            var lastTick = System.nanoTime()
            while (isActive && connectedUdid != null) {
                delay(1_000)
                val presented = activeGpuPipeline()?.framesPresented() ?: NativeMirrorJni.framesPresented()
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
                val wasGpu = gpuPipelineKey != null
                releaseGpuPipeline()
                if (!wasGpu) NativeMirrorJni.destroyPresentation()
            } else {
                delay(500)
                if (connectedUdid == null) {
                    val wasGpu = gpuPipelineKey != null
                    releaseGpuPipeline()
                    if (!wasGpu) NativeMirrorJni.destroyPresentation()
                }
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
        // Touch mapping must use the same space as MirrorPanel.mapPoint (MirrorFrame pixels).
        // Prefer the live session frame size; fall back to the IOSurface pixel size, not points.
        val mirrorWidth = session.value?.width?.takeIf { it > 1 }
            ?: frames.value.width.takeIf { it > 1 }
            ?: NativeIosSimJni.contentSizePoints()[0]
        val mirrorHeight = session.value?.height?.takeIf { it > 1 }
            ?: frames.value.height.takeIf { it > 1 }
            ?: NativeIosSimJni.contentSizePoints()[1]
        // SimulatorKit HID injection blocks on a dispatch semaphore (up to ~2s). Run it off the
        // Compose UI dispatcher: the input channel is shared, so a stalled iOS HID call on the UI
        // thread would freeze input for BOTH iOS and Android and stall connect's run_on_main calls.
        return withContext(Dispatchers.IO) {
            when (input) {
                is MirrorInput.Touch -> {
                    val action = when (input.action) {
                        MirrorTouchAction.Down -> 0
                        MirrorTouchAction.Move -> 1
                        MirrorTouchAction.Up -> 2
                    }
                    val (nx, ny) = iosNormalizedTouchCoordinates(input.x, input.y, mirrorWidth, mirrorHeight)
                    val delivered = NativeIosSimJni.sendTouch(action, nx, ny)
                    activeGpuPipeline()?.recordInput()
                    if (delivered) {
                        CommandResult.success("Sent")
                    } else {
                        CommandResult.failure("Simulator HID did not accept touch ${input.action.name.lowercase()}")
                    }
                }
                is MirrorInput.Tap -> {
                    val (nx, ny) = iosNormalizedTouchCoordinates(input.x, input.y, mirrorWidth, mirrorHeight)
                    val downDelivered = NativeIosSimJni.sendTouch(0, nx, ny)
                    activeGpuPipeline()?.recordInput()
                    delay(170)
                    val upDelivered = NativeIosSimJni.sendTouch(2, nx, ny)
                    if (downDelivered && upDelivered) {
                        CommandResult.success("Sent")
                    } else {
                        CommandResult.failure("Simulator HID did not accept tap")
                    }
                }
                is MirrorInput.Swipe -> {
                    val w = mirrorWidth.coerceAtLeast(1).toFloat()
                    val h = mirrorHeight.coerceAtLeast(1).toFloat()
                    NativeIosSimJni.sendSwipe(
                        input.startX / w, input.startY / h,
                        input.endX / w, input.endY / h,
                        (input.durationMillis / 16).coerceAtLeast(2),
                    )
                    CommandResult.success("Sent")
                }
                is MirrorInput.Text -> {
                    NativeIosSimJni.sendText(input.value)
                    CommandResult.success("Sent")
                }
                MirrorInput.Home -> {
                    NativeIosSimJni.sendButton(0)
                    CommandResult.success("Sent")
                }
                MirrorInput.Power -> {
                    NativeIosSimJni.sendButton(1)
                    CommandResult.success("Sent")
                }
                MirrorInput.Back, MirrorInput.Recents -> CommandResult.failure("No iOS equivalent")
                is MirrorInput.Key -> CommandResult.failure("Use text input for iOS keyboard")
            }
        }
    }

    override suspend fun screenshot(serial: String): ByteArray? {
        return NativeMirrorJni.copyLatestFrameArgb()?.let { frame ->
            // Bug capture uses MirrorFrame path elsewhere; returning null keeps parity with native metadata frames.
            null
        }
    }
}
