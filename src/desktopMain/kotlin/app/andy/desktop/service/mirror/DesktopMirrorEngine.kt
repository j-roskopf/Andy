package app.andy.desktop.service.mirror

import app.andy.desktop.service.CommandRunner
import app.andy.desktop.service.DesktopDeviceService
import app.andy.desktop.parser.AndroidParsers
import app.andy.desktop.service.emulator.EMULATOR_IMAGE_BYTES_PER_PIXEL
import app.andy.desktop.service.emulator.EmulatorGrpcClient
import app.andy.desktop.service.emulator.EmulatorMappedFramebuffer
import app.andy.desktop.service.emulator.emulatorConsolePort
import app.andy.desktop.service.emulator.isEmulatorSerial
import app.andy.desktop.service.emulator.readEmulatorGrpcToken
import app.andy.model.DeviceConnectionState
import app.andy.model.isWirelessAdbSerial
import app.andy.service.CommandResult
import app.andy.service.MirrorEngine
import app.andy.service.MirrorBackend
import app.andy.service.MirrorBackendKind
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.service.MirrorRendererMode
import app.andy.service.MirrorSession
import app.andy.service.MirrorTouchAction
import app.andy.service.MirrorVideoConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.andy.service.EncodedVideoAccessUnit
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

private const val EMULATOR_DISPLAY_SIZE_SETTLE_NANOS = 60_000_000_000L
private const val EMULATOR_DISPLAY_SIZE_REFRESH_MIN_NANOS = 1_000_000_000L
private const val EMULATOR_BOOT_WAIT_NANOS = 120_000_000_000L
private const val EMULATOR_BOOT_POLL_MILLIS = 500L
private const val MIRROR_START_MAX_ATTEMPTS = 4
private const val MIRROR_START_RETRY_DELAY_MILLIS = 2_000L
private const val MIRROR_SOCKET_SETTLE_MILLIS = 1_500L
private const val MIRROR_WIRELESS_SOCKET_SETTLE_MILLIS = 2_500L
private const val NATIVE_HOST_WAIT_NANOS = 750_000_000L
private const val NATIVE_HOST_WAIT_STEP_MILLIS = 16L
private const val SCRCPY_FRAME_HEADER_BYTES = 12
private const val MAX_SCRCPY_FRAME_BYTES = 16 * 1024 * 1024
/** Keep scrcpy warm across AndyShell destination switches (Live ↔ Design ↔ Accessibility). */
private const val MIRROR_RELEASE_GRACE_MILLIS = 500L

internal data class EmulatorDisplaySize(val width: Int, val height: Int)
internal data class EmulatorTouchPoint(val x: Int, val y: Int)
internal data class ScrcpySessionFrame(val width: Int, val height: Int, val clientResized: Boolean)

/** Extracts the big-endian payload length from scrcpy's 8-byte PTS/flags + 4-byte-size header. */
internal fun scrcpyFramePayloadSize(header: ByteArray): Int {
    require(header.size == SCRCPY_FRAME_HEADER_BYTES) { "Expected a $SCRCPY_FRAME_HEADER_BYTES-byte scrcpy frame header" }
    require(!scrcpyFrameIsSession(header)) { "A scrcpy session header has no payload" }
    val size = ((header[8].toInt() and 0xff) shl 24) or
        ((header[9].toInt() and 0xff) shl 16) or
        ((header[10].toInt() and 0xff) shl 8) or
        (header[11].toInt() and 0xff)
    require(size in 0..MAX_SCRCPY_FRAME_BYTES) { "Invalid scrcpy frame size: $size" }
    return size
}

/** A metadata-only packet that announces the video size; it is never an H.264 access unit. */
internal fun scrcpyFrameIsSession(header: ByteArray): Boolean {
    require(header.size == SCRCPY_FRAME_HEADER_BYTES) { "Expected a $SCRCPY_FRAME_HEADER_BYTES-byte scrcpy frame header" }
    return header[0].toInt() and 0x80 != 0
}

internal fun scrcpySessionFrame(header: ByteArray): ScrcpySessionFrame {
    require(scrcpyFrameIsSession(header)) { "Expected a scrcpy session header" }
    fun readUint32(offset: Int): Int =
        ((header[offset].toInt() and 0xff) shl 24) or
            ((header[offset + 1].toInt() and 0xff) shl 16) or
            ((header[offset + 2].toInt() and 0xff) shl 8) or
            (header[offset + 3].toInt() and 0xff)
    val width = readUint32(4)
    val height = readUint32(8)
    require(width > 0 && height > 0) { "Invalid scrcpy session size: ${width}x${height}" }
    return ScrcpySessionFrame(width, height, header[3].toInt() and 1 != 0)
}

/**
 * Scales a physical display size to the scrcpy `max_size` edge. `maxSize <= 0` means native
 * (unlimited), matching scrcpy's `max_size=0` contract. Treating 0 as a real limit previously
 * collapsed the metadata frame to 2x2 and made GPU touch mapping a no-op.
 */
internal fun scaledCaptureSize(sourceWidth: Int, sourceHeight: Int, maxSize: Int): Pair<Int, Int> {
    val longestSide = maxOf(sourceWidth, sourceHeight).coerceAtLeast(1)
    val scale = when {
        maxSize <= 0 -> 1.0
        longestSide > maxSize -> maxSize.toDouble() / longestSide
        else -> 1.0
    }
    fun evenAtLeast(value: Int, minimum: Int): Int = maxOf(minimum, value and -2)
    return evenAtLeast((sourceWidth * scale).toInt(), 2) to evenAtLeast((sourceHeight * scale).toInt(), 2)
}

private fun MirrorInput.usesTouchCoordinates(): Boolean = when (this) {
    is MirrorInput.Touch,
    is MirrorInput.Tap,
    is MirrorInput.Swipe -> true
    is MirrorInput.Key,
    is MirrorInput.Text,
    MirrorInput.Back,
    MirrorInput.Home,
    MirrorInput.Recents,
    MirrorInput.Power -> false
}

private fun emulatorDisplayAspectDrifted(frame: MirrorFrame, displaySize: EmulatorDisplaySize): Boolean {
    if (frame.width <= 1 || frame.height <= 1 || displaySize.width <= 0 || displaySize.height <= 0) return false
    val frameAspect = frame.width.toDouble() / frame.height
    val displayAspect = displaySize.width.toDouble() / displaySize.height
    return abs(frameAspect - displayAspect) > 0.03
}

internal fun emulatorRgb888ToArgb(width: Int, height: Int, rgb: ByteBuffer): IntArray {
    val pixels = IntArray(width * height)
    val bytes = rgb.duplicate()
    bytes.position(0)
    for (index in pixels.indices) {
        val red = bytes.get().toInt() and 0xff
        val green = bytes.get().toInt() and 0xff
        val blue = bytes.get().toInt() and 0xff
        pixels[index] = 0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
    }
    return pixels
}

/**
 * Apply the one low-latency encoder key that the Qualcomm Codec2 stack exposes explicitly.
 *
 * The server accepts arbitrary MediaFormat keys, so this must be limited to Qualcomm devices.
 * An explicit developer override remains authoritative, including an explicit `=0` opt-out.
 */
internal fun videoCodecOptionsForDevice(
    socManufacturer: String?,
    override: String?,
): String? {
    val options = override
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.toMutableList()
        ?: mutableListOf()
    val isQualcomm = socManufacturer
        ?.trim()
        ?.lowercase()
        ?.let { it == "qti" || it == "qualcomm" }
        ?: false
    val lowLatencyKey = "vendor.qti-ext-enc-low-latency.enable"
    if (isQualcomm && options.none { it.substringBefore('=').trim() == lowLatencyKey }) {
        options += "$lowLatencyKey=1"
    }
    return options.takeIf { it.isNotEmpty() }?.joinToString(",")
}

internal fun scaledEmulatorTouchPoint(
    x: Int,
    y: Int,
    frame: MirrorFrame,
    displaySize: EmulatorDisplaySize?,
): EmulatorTouchPoint {
    val frameWidth = frame.width.coerceAtLeast(1)
    val frameHeight = frame.height.coerceAtLeast(1)
    val targetWidth = displaySize?.width ?: frameWidth
    val targetHeight = displaySize?.height ?: frameHeight
    return EmulatorTouchPoint(
        x = (x.coerceIn(0, frameWidth - 1).toLong() * targetWidth / frameWidth)
            .toInt()
            .coerceIn(0, targetWidth - 1),
        y = (y.coerceIn(0, frameHeight - 1).toLong() * targetHeight / frameHeight)
            .toInt()
            .coerceIn(0, targetHeight - 1),
    )
}

class DesktopMirrorEngine(
    private val runner: CommandRunner,
    private val devices: DesktopDeviceService,
) : MirrorEngine {
    override val session = MutableStateFlow<MirrorSession?>(null)
    override val frames = MutableStateFlow(MirrorFrame(1, 1, intArrayOf(0xff000000.toInt())))
    override val status = MutableStateFlow("Ready for embedded mirror")
    private val encodedVideoFlow = MutableSharedFlow<EncodedVideoAccessUnit>(
        // Absorb brief collector stalls / wireless jitter. DesktopBugService owns the
        // rolling window; capacity 8 (~130ms at 60fps) dropped AUs and truncated recordings.
        extraBufferCapacity = 512,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val encodedVideo: SharedFlow<EncodedVideoAccessUnit> = encodedVideoFlow

    /** Best-effort emit for bug capture; never blocks the live decode path. */
    private fun publishEncodedVideo(unit: EncodedVideoAccessUnit) {
        encodedVideoFlow.tryEmit(unit)
    }
    private var videoJob: Job? = null
    private var videoProcess: Process? = null
    private var controlSocket: Socket? = null
    private var controlOutput: BufferedOutputStream? = null
    private var emulatorGrpcClient: EmulatorGrpcClient? = null
    private var videoForwardPort: Int? = null
    private var connectedSerial: String? = null
    private var connectedConfig: MirrorVideoConfig? = null
    private var connectedAtNanos: Long = 0L
    private var lastEmulatorDisplaySizeRefreshNanos: Long = 0L
    private var nativeHost: java.awt.Canvas? = null
    private val controlLock = Any()
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingRelease: Job? = null

    override suspend fun connect(serial: String, config: MirrorVideoConfig): CommandResult {
        val handingOff = pendingRelease?.isActive == true
        pendingRelease?.cancel()
        pendingRelease = null

        if (connectedSerial == serial && videoJob?.isActive == true) {
            // Same config, or warm handoff from another live destination — keep scrcpy running.
            if (connectedConfig == config || handingOff) {
                rebindPresentationHost(config)
                return CommandResult.success("Embedded mirror already connected for $serial")
            }
            // Explicit quality/preset change while the session is held — restart.
            tearDownSession()
        } else if (videoJob?.isActive == true || connectedSerial != null) {
            tearDownSession()
        }

        connectedSerial = serial
        connectedConfig = config
        connectedAtNanos = System.nanoTime()
        lastEmulatorDisplaySizeRefreshNanos = 0L
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        frames.value = MirrorFrame(1, 1, intArrayOf(0xff000000.toInt()))
        // SwingPanel realizes its AWT child just after the Compose tree commits. Connection is
        // also started from a LaunchedEffect, so probe briefly instead of racing that lifecycle
        // and permanently selecting the CPU path for an otherwise supported Live surface.
        val host = if (config.rendererMode != MirrorRendererMode.Legacy && NativeMirrorJni.isEmbeddedPresentationSupported()) {
            awaitNativeHost()
        } else {
            null
        }
        val nativeAvailable = host != null
        val unavailableReason by lazy { acceleratedUnavailableReason() }
        if (config.rendererMode == MirrorRendererMode.Accelerated && !nativeAvailable) {
            val reason = unavailableReason
            session.value = MirrorSession(
                serial = serial,
                requestedMode = config.rendererMode,
                backend = MirrorBackend(MirrorBackendKind.Unavailable, fallbackReason = reason),
                failureReason = reason,
            )
            status.value = "Accelerated mirror unavailable · $reason"
            return CommandResult.failure(status.value)
        }
        var useNativeRenderer = config.rendererMode != MirrorRendererMode.Legacy && nativeAvailable
        if (useNativeRenderer && host != null) {
            // Product GPU path: borderless Metal surface letterboxed over the Live Canvas.
            if (!NativeMirrorJni.openMetalInlineOverlay(host)) {
                val reason = "VideoToolbox/Metal inline overlay initialization failed"
                if (config.rendererMode == MirrorRendererMode.Accelerated) {
                    session.value = MirrorSession(
                        serial = serial,
                        requestedMode = config.rendererMode,
                        backend = MirrorBackend(MirrorBackendKind.Unavailable, fallbackReason = reason),
                        failureReason = reason,
                    )
                    status.value = "Accelerated mirror unavailable · $reason"
                    return CommandResult.failure(status.value)
                }
                useNativeRenderer = false
            } else {
                nativeHost = host
            }
        }
        val fallbackReason = if (!useNativeRenderer && config.rendererMode == MirrorRendererMode.Auto) {
            "$unavailableReason; using legacy CPU presentation"
        } else {
            null
        }
        if (!useNativeRenderer) publishLegacySession(serial, config, fallbackReason = fallbackReason)
        val scrcpyServer = ScrcpyServerLocator.find()
            ?: run {
                NativeMirrorJni.destroyPresentation()
                nativeHost = null
                return CommandResult.failure("Andy’s bundled scrcpy server is missing. Reinstall Andy or set SCRCPY_SERVER_PATH for local protocol development.")
            }
        status.value = legacyStatus("Starting scrcpy-server raw H.264 mirror for $serial (${config.maxSize}px, ${config.bitRate / 1_000_000.0} Mbps)")
        videoJob = engineScope.launch {
            val emulator = serial.isEmulatorSerial()
            val wireless = isWirelessAdbSerial(serial)
            if (emulator) {
                awaitEmulatorReady(adb, serial)
            }
            var coldStartAttempt = 0
            while (isActive && connectedSerial == serial) {
                val framesPresentedBefore = if (useNativeRenderer) NativeMirrorJni.framesPresented() else frames.value.frameNumber
                runNativeVideoLoop(adb, serial, scrcpyServer, config)
                if (!isActive || connectedSerial != serial) break
                val framesPresentedAfter = if (useNativeRenderer) NativeMirrorJni.framesPresented() else frames.value.frameNumber
                val presentedDuringLoop = framesPresentedAfter - framesPresentedBefore
                if (presentedDuringLoop <= 0L) {
                    coldStartAttempt++
                    // Black Live pane: scrcpy often exits before the first decoded frame on cold
                    // boot or flaky wireless ADB tunnels. Cap retries before giving up.
                    if (coldStartAttempt >= MIRROR_START_MAX_ATTEMPTS) break
                    val kind = when {
                        emulator -> "Emulator"
                        wireless -> "Wireless"
                        else -> "Device"
                    }
                    status.value = "$kind mirror not ready yet; retrying ($coldStartAttempt/$MIRROR_START_MAX_ATTEMPTS)…"
                } else {
                    // Healthy stream that later ended or stalled — keep Live alive.
                    coldStartAttempt = 0
                    status.value = "Video stream interrupted; reconnecting…"
                }
                delay(MIRROR_START_RETRY_DELAY_MILLIS)
                if (useNativeRenderer) {
                    rebindPresentationHost(config)
                }
            }
        }
        return CommandResult.success("Embedded mirror starting for $serial")
    }

    override suspend fun disconnect(immediate: Boolean) {
        pendingRelease?.cancel()
        pendingRelease = null
        // Compose Desktop may retain a SwingPanel's native peer briefly after its screen leaves
        // composition. The Metal presenter is an independent AppKit surface, so hide it at the
        // session boundary rather than waiting for that peer's removeNotify callback.
        NativeMirrorJni.setInlineOverlayVisible(false)
        if (connectedSerial == null && videoJob == null && session.value == null) {
            return
        }
        if (immediate) {
            tearDownSession()
            return
        }
        // Destination switches dispose the old screen before composing the next. Delay teardown
        // so Live/Design/Accessibility can reclaim the same scrcpy session within the grace window.
        pendingRelease = engineScope.launch {
            delay(MIRROR_RELEASE_GRACE_MILLIS)
            tearDownSession()
        }
    }

    private suspend fun rebindPresentationHost(config: MirrorVideoConfig) {
        if (config.rendererMode == MirrorRendererMode.Legacy) return
        if (!NativeMirrorJni.isEmbeddedPresentationSupported()) return
        val host = awaitNativeHost() ?: return
        nativeHost = host
        if (NativeMirrorJni.isMetalInlineOverlayOpen()) {
            // The departing Live host hides the retained presenter immediately so it cannot
            // float above the next destination. Make it visible only after this new host exists.
            NativeMirrorJni.setInlineOverlayVisible(true)
            NativeMirrorJni.updateMetalLayerGeometry(host)
        } else if (!NativeMirrorJni.openMetalInlineOverlay(host) && config.rendererMode == MirrorRendererMode.Accelerated) {
            status.value = "Accelerated mirror unavailable · VideoToolbox/Metal inline overlay initialization failed"
        }
    }

    private suspend fun tearDownSession() {
        val job = videoJob
        videoJob = null
        job?.cancel()
        synchronized(controlLock) {
            runCatching { controlOutput?.close() }
            controlOutput = null
            runCatching { controlSocket?.close() }
            controlSocket = null
        }
        emulatorGrpcClient?.close()
        emulatorGrpcClient = null
        NativeMirrorJni.destroyPresentation()
        nativeHost = null
        videoProcess?.destroyForcibly()
        videoProcess = null
        videoForwardPort?.let { port ->
            val adb = devices.adbPath()
            if (adb != null && connectedSerial != null) {
                runner.run(listOf(adb, "-s", connectedSerial!!, "forward", "--remove", "tcp:$port"), 3)
            }
        }
        videoForwardPort = null
        connectedSerial = null
        connectedConfig = null
        connectedAtNanos = 0L
        lastEmulatorDisplaySizeRefreshNanos = 0L
        // Wait for the video loop to fully stop before clearing the frame so a late
        // in-flight frame can't win the race and leave a frozen image on screen.
        job?.let { runCatching { it.join() } }
        frames.value = MirrorFrame(1, 1, intArrayOf(0xff000000.toInt()))
        session.value = null
        status.value = "Disconnected"
    }

    override suspend fun sendInput(input: MirrorInput): CommandResult {
        emulatorGrpcClient?.let { client ->
            // Run the blocking gRPC touch RPC off the Compose UI dispatcher so dragging
            // stays as smooth as Android Studio instead of stalling the event thread.
            withContext(Dispatchers.IO) {
                val frame = frames.value
                val serial = connectedSerial
                val adb = devices.adbPath()
                val touchInput = input.usesTouchCoordinates()
                if (serial != null && adb != null && touchInput) {
                    refreshEmulatorDisplaySizeIfNeeded(client, adb, serial, frame)
                }
                if (touchInput && client.displaySize == null) {
                    return@withContext CommandResult.failure("Waiting for emulator display size before sending touch input")
                }
                client.sendInput(input, frame)
            }?.let { return it }
        }
        sendScrcpyControl(input)?.let { return it }
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        val selectedSerial = connectedSerial ?: devices.listDevices().firstOrNull { it.state == DeviceConnectionState.Online }?.serial
            ?: return CommandResult.failure("No online device")
        val command = when (input) {
            is MirrorInput.Touch -> when (input.action) {
                MirrorTouchAction.Up -> listOf(adb, "-s", selectedSerial, "shell", "input", "tap", input.x.toString(), input.y.toString())
                MirrorTouchAction.Down, MirrorTouchAction.Move -> return CommandResult.success("Touch ${input.action.name} ignored without scrcpy control")
            }
            is MirrorInput.Tap -> listOf(adb, "-s", selectedSerial, "shell", "input", "tap", input.x.toString(), input.y.toString())
            is MirrorInput.Swipe -> listOf(adb, "-s", selectedSerial, "shell", "input", "swipe", input.startX.toString(), input.startY.toString(), input.endX.toString(), input.endY.toString(), input.durationMillis.toString())
            is MirrorInput.Key -> listOf(adb, "-s", selectedSerial, "shell", "input", "keyevent", input.keyCode.toString())
            is MirrorInput.Text -> listOf(adb, "-s", selectedSerial, "shell", "input", "text", input.value.replace(" ", "%s"))
            MirrorInput.Back -> listOf(adb, "-s", selectedSerial, "shell", "input", "keyevent", "4")
            MirrorInput.Home -> listOf(adb, "-s", selectedSerial, "shell", "input", "keyevent", "3")
            MirrorInput.Recents -> listOf(adb, "-s", selectedSerial, "shell", "input", "keyevent", "187")
            MirrorInput.Power -> listOf(adb, "-s", selectedSerial, "shell", "input", "keyevent", "26")
        }
        return runner.run(command)
    }

    private suspend fun refreshEmulatorDisplaySizeIfNeeded(
        client: EmulatorGrpcClient,
        adb: String,
        serial: String,
        frame: MirrorFrame,
    ) {
        val now = System.nanoTime()
        val withinBootSettleWindow = connectedAtNanos > 0L && now - connectedAtNanos < EMULATOR_DISPLAY_SIZE_SETTLE_NANOS
        val shouldRefresh = client.displaySize == null ||
            withinBootSettleWindow ||
            client.displaySize?.let { displaySize -> emulatorDisplayAspectDrifted(frame, displaySize) } == true
        if (!shouldRefresh) return
        if (now - lastEmulatorDisplaySizeRefreshNanos < EMULATOR_DISPLAY_SIZE_REFRESH_MIN_NANOS) return
        lastEmulatorDisplaySizeRefreshNanos = now

        val next = readEmulatorDisplaySize(adb, serial) ?: return
        client.updateDisplaySize(next)
    }

    private fun sendScrcpyControl(input: MirrorInput): CommandResult? {
        val output = synchronized(controlLock) { controlOutput } ?: return null
        return runCatching {
            val messages = ScrcpyControlMessage.serialize(input, frames.value)
            synchronized(controlLock) {
                messages.forEach(output::write)
                output.flush()
                // Start the host-input measurement at the point the command has actually
                // entered the scrcpy control socket. Recording it before serialization or a
                // contended control lock would inflate the end-to-end result with host-side
                // queuing that has not yet injected anything into Android.
                if (session.value?.backend?.kind == MirrorBackendKind.NativeHardware) {
                    NativeMirrorJni.recordInput()
                }
            }
            CommandResult.success("Input sent")
        }.getOrElse { error ->
            synchronized(controlLock) {
                runCatching { controlOutput?.close() }
                controlOutput = null
                runCatching { controlSocket?.close() }
                controlSocket = null
            }
            CommandResult.failure("scrcpy control failed: ${error.message ?: error::class.simpleName}")
        }
    }

    private fun publishLegacySession(serial: String, config: MirrorVideoConfig, fallbackReason: String?) {
        session.value = MirrorSession(
            serial = serial,
            requestedMode = config.rendererMode,
            backend = MirrorBackend(
                kind = MirrorBackendKind.LegacyCpu,
                decoder = "FFmpeg software decode",
                renderer = "Swing BufferedImage",
                fallbackReason = fallbackReason,
            ),
            failureReason = fallbackReason,
        )
    }

    private fun publishNativeSession(serial: String, config: MirrorVideoConfig) {
        session.value = MirrorSession(
            serial = serial,
            requestedMode = config.rendererMode,
            backend = MirrorBackend(
                kind = MirrorBackendKind.NativeHardware,
                decoder = "VideoToolbox H.264",
                renderer = "Metal",
            ),
        )
    }

    private fun publishCpuFrame(frame: MirrorFrame) {
        frames.value = frame
        val active = session.value ?: return
        // LiveScreen collects session into Compose state. Emitting a new session on every
        // decoded frame recomposes the Swing host and flickers the CPU painter. Only push
        // telemetry when size or fps actually changes (fps is sampled ~1 Hz above).
        val displayedFps = frame.displayedFps ?: frame.decodedFps ?: active.stats.displayedFps
        val decodedFps = frame.decodedFps ?: active.stats.decodedFps
        val sizeChanged = active.width != frame.width || active.height != frame.height
        val fpsChanged =
            displayedFps != active.stats.displayedFps || decodedFps != active.stats.decodedFps
        if (!sizeChanged && !fpsChanged) return
        session.value = active.copy(
            stats = active.stats.copy(
                displayedFps = displayedFps,
                decodedFps = decodedFps,
                framesPresented = frame.frameNumber.coerceAtLeast(active.stats.framesPresented),
            ),
            width = frame.width,
            height = frame.height,
        )
    }

    private fun legacyStatus(message: String): String = buildString {
        append(message)
        session.value?.backend?.fallbackReason?.let { append(" · $it") }
    }

    private fun acceleratedUnavailableReason(): String = when {
        !NativeMirrorJni.isEmbeddedPresentationSupported() -> NativeMirrorJni.embeddedPresentationFailureReason()
        !NativeMirrorJni.isAvailable() -> "This desktop platform has no packaged VideoToolbox/Metal native mirror bridge"
        NativeMirrorHostRegistry.current() == null -> "No realized native mirror host is available"
        else -> "The native mirror backend could not initialize"
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

    /**
     * adb reports emulators Online before SurfaceFlinger / MediaCodec are ready. Connecting
     * scrcpy in that window yields a black Live pane and a wrong capture aspect. Wait for
     * boot_completed and a readable display size first.
     */
    private suspend fun awaitEmulatorReady(adb: String, serial: String) {
        status.value = "Waiting for emulator boot…"
        val deadline = System.nanoTime() + EMULATOR_BOOT_WAIT_NANOS
        while (System.nanoTime() < deadline && connectedSerial == serial) {
            val boot = runner.run(listOf(adb, "-s", serial, "shell", "getprop", "sys.boot_completed"), 3)
            if (boot.stdout.trim() == "1" && readEmulatorDisplaySize(adb, serial) != null) {
                status.value = "Emulator ready — starting mirror"
                return
            }
            delay(EMULATOR_BOOT_POLL_MILLIS)
        }
        status.value = "Emulator boot wait timed out — starting mirror anyway"
    }

    override suspend fun screenshot(serial: String): ByteArray? {
        val adb = devices.adbPath() ?: return null
        val result = runner.run(listOf(adb, "-s", serial, "exec-out", "screencap", "-p"), 8)
        return result.stdout.encodeToByteArray().takeIf { result.isSuccess }
    }

    private suspend fun readEmulatorDisplaySize(adb: String, serial: String): EmulatorDisplaySize? {
        val result = runner.run(listOf(adb, "-s", serial, "shell", "wm", "size"), 4)
        if (!result.isSuccess) return null
        val parsed = AndroidParsers.parseWmSize(result.stdout) ?: return null
        val width = parsed.substringBefore('x').toIntOrNull() ?: return null
        val height = parsed.substringAfter('x').toIntOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        return EmulatorDisplaySize(width, height)
    }

    private suspend fun runEmulatorGrpcVideoLoop(adb: String, serial: String, client: EmulatorGrpcClient, config: MirrorVideoConfig) = withContext(Dispatchers.IO) {
        var frameNumber = 0L
        var fpsWindowStartedAt = System.nanoTime()
        var fpsWindowFrame = 0L
        var decodedFps = 0f
        var mappedFramebuffer: EmulatorMappedFramebuffer? = null
        try {
            mappedFramebuffer = if (System.getenv("ANDY_EMULATOR_GRPC_MMAP") != "0") {
                runCatching { EmulatorMappedFramebuffer.create(config.maxSize) }.getOrNull()
            } else {
                null
            }
            val transport = if (mappedFramebuffer != null) "MMAP" else "raw"
            status.value = "Emulator gRPC video connected (${config.maxSize}px $transport RGB stream)"
            val screenshots = client.streamScreenshots(config.maxSize, mappedFramebuffer?.handle)
            while (isActive && screenshots.hasNext()) {
                val image = screenshots.next()
                if (image.width <= 0 || image.height <= 0) {
                    continue
                }
                val expectedBytes = image.width * image.height * EMULATOR_IMAGE_BYTES_PER_PIXEL
                val rgb = if (image.pixels.isNotEmpty()) {
                    ByteBuffer.wrap(image.pixels)
                } else {
                    mappedFramebuffer?.frameBytes(expectedBytes)
                }
                if (rgb == null || rgb.remaining() < expectedBytes) {
                    status.value = "Skipping short emulator frame (${rgb?.remaining() ?: 0}/$expectedBytes bytes)"
                    continue
                }
                fpsWindowFrame++
                val now = System.nanoTime()
                val elapsedNanos = now - fpsWindowStartedAt
                if (elapsedNanos >= 1_000_000_000L) {
                    decodedFps = fpsWindowFrame * 1_000_000_000f / elapsedNanos
                    fpsWindowFrame = 0
                    fpsWindowStartedAt = now
                }
                publishCpuFrame(MirrorFrame(
                    width = image.width,
                    height = image.height,
                    argb = emulatorRgb888ToArgb(image.width, image.height, rgb),
                    frameNumber = ++frameNumber,
                    decodedFps = decodedFps.takeIf { it > 0f },
                ))
            }
            status.value = "Emulator gRPC video stream ended"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (!isActive) return@withContext
            val reason = error.message ?: error::class.simpleName.orEmpty()
            status.value = "Emulator gRPC video failed; falling back to scrcpy: $reason"
            if (emulatorGrpcClient === client) emulatorGrpcClient = null
            runCatching { client.close() }
            val scrcpyServer = ScrcpyServerLocator.find()
            if (scrcpyServer == null) {
                status.value = "Emulator gRPC video failed and scrcpy-server was not found: $reason"
                return@withContext
            }
            runNativeVideoLoop(adb, serial, scrcpyServer, config)
        } finally {
            mappedFramebuffer?.close()
        }
    }

    private suspend fun runNativeVideoLoop(adb: String, serial: String, scrcpyServer: File, config: MirrorVideoConfig) = withContext(Dispatchers.IO) {
        val captureSize = captureSize(adb, serial, config.maxSize)
        val forwardPort = allocateLocalPort()
        val scid = Random.nextInt(1, Int.MAX_VALUE).toString(16).padStart(8, '0')
        videoForwardPort = forwardPort
        val remoteServer = "/data/local/tmp/scrcpy-server-andy.jar"
        val push = runner.run(listOf(adb, "-s", serial, "push", scrcpyServer.absolutePath, remoteServer), 10)
        if (!push.isSuccess) {
            status.value = "Failed to push scrcpy-server: ${push.stderr.ifBlank { push.stdout }.take(180)}"
            return@withContext
        }
        val forward = runner.run(listOf(adb, "-s", serial, "forward", "tcp:$forwardPort", "localabstract:scrcpy_$scid"), 5)
        if (!forward.isSuccess) {
            status.value = "Failed to create adb video tunnel: ${forward.stderr.ifBlank { forward.stdout }.take(180)}"
            return@withContext
        }
        val socManufacturer = runner.run(
            listOf(adb, "-s", serial, "shell", "getprop", "ro.soc.manufacturer"),
            timeoutSeconds = 3,
        ).stdout.trim()
        val codecOptions = videoCodecOptionsForDevice(
            socManufacturer = socManufacturer,
            override = System.getenv("ANDY_MIRROR_VIDEO_CODEC_OPTIONS"),
        )?.let { listOf("video_codec_options=$it") }.orEmpty()
        val command = listOf(
            adb,
            "-s", serial,
            "shell",
            "CLASSPATH=$remoteServer",
            "app_process",
            "/",
            "com.genymobile.scrcpy.Server",
            "4.0",
            "scid=$scid",
            "tunnel_forward=true",
            "audio=false",
            "cleanup=false",
            // Preserve each MediaCodec output-buffer boundary. Raw Annex-B requires an extra
            // parser to rediscover access units, which can hold real-time frames in a queue.
            "send_device_meta=false",
            "send_stream_meta=false",
            "send_frame_meta=true",
            "send_dummy_byte=false",
            "max_size=${config.maxSize}",
            "video_bit_rate=${config.bitRate}",
            "max_fps=${config.maxFps}",
            "log_level=info",
        ) + codecOptions
        var frameNumber = 0L
        var fpsWindowStartedAt = System.nanoTime()
        var fpsWindowFrame = 0L
        var decodedFps = 0f
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        videoProcess = process
        val stderr = StringBuilder()
        val serverStderrPump = launch {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (stderr.length < 4_000) stderr.appendLine(line)
                    if (line.isNotBlank()) status.value = line.take(180)
                }
            }
        }
        val serverStdoutPump = launch {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) status.value = line.take(180)
                }
            }
        }
        // On physical devices the server may take longer than an emulator to initialize its
        // localabstract socket. ADB accepts the local TCP connection before that socket exists,
        // then immediately closes it; waiting here avoids treating that empty stream as a
        // decoder failure. Wireless tunnels need a bit more slack.
        delay(
            if (isWirelessAdbSerial(serial)) MIRROR_WIRELESS_SOCKET_SETTLE_MILLIS
            else MIRROR_SOCKET_SETTLE_MILLIS,
        )
        var socket: Socket? = null
        var codecContext: AVCodecContext? = null
        var packet: AVPacket? = null
        var frame: AVFrame? = null
        var swsContext: SwsContext? = null
        try {
            socket = connectScrcpySocket(forwardPort)
            synchronized(controlLock) {
                controlSocket = connectScrcpySocket(forwardPort).also { it.tcpNoDelay = true }
                controlOutput = BufferedOutputStream(controlSocket!!.getOutputStream(), 1024)
            }
            status.value = "scrcpy-server video connected (${captureSize.width}x${captureSize.height})"
            var usingNativeRenderer = nativeHost != null && config.rendererMode != MirrorRendererMode.Legacy
            var nativeHardwareVerified = false
            var nativeStatsWindowStartedAt = System.nanoTime()
            var nativeStatsWindowFrames = NativeMirrorJni.framesPresented()
            if (usingNativeRenderer) {
                // Metadata only: the Canvas needs source dimensions for coordinate mapping while
                // VideoToolbox/Metal owns the actual pixels.
                frames.value = MirrorFrame(captureSize.width, captureSize.height, IntArray(0), frameNumber = 1)
                NativeMirrorJni.setPresentationContentSize(captureSize.width, captureSize.height)
                status.value = "Native VideoToolbox/Metal mirror connected (${captureSize.width}x${captureSize.height})"
            }

            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 1 shl 20))
            val header = ByteArray(SCRCPY_FRAME_HEADER_BYTES)
            while (isActive) {
                try {
                    input.readFully(header)
                } catch (_: EOFException) {
                    break
                }
                if (scrcpyFrameIsSession(header)) {
                    val sessionFrame = scrcpySessionFrame(header)
                    status.value = "scrcpy session ${sessionFrame.width}x${sessionFrame.height}" +
                        if (sessionFrame.clientResized) " (client resized)" else ""
                    // Keep touch-mapping metadata aligned with the live stream size. This matters
                    // most for max_size=0 (native), where the initial estimate must match the
                    // encoder output or scrcpy control injects into a tiny coordinate space.
                    if (usingNativeRenderer) {
                        frames.value = MirrorFrame(
                            sessionFrame.width,
                            sessionFrame.height,
                            IntArray(0),
                            frameNumber = frames.value.frameNumber.coerceAtLeast(1),
                        )
                        NativeMirrorJni.setPresentationContentSize(sessionFrame.width, sessionFrame.height)
                    }
                    // Session headers are exactly 12 bytes. Treating their height field as a
                    // payload size consumes the next H.264 access unit and desynchronizes the
                    // stream, which is especially visible on Android Emulator startup.
                    continue
                }
                val payloadSize = scrcpyFramePayloadSize(header)
                val payload = ByteArray(payloadSize)
                try {
                    input.readFully(payload)
                } catch (_: EOFException) {
                    break
                }
                val streamWidth = frames.value.width.coerceAtLeast(captureSize.width)
                val streamHeight = frames.value.height.coerceAtLeast(captureSize.height)
                publishEncodedVideo(
                    EncodedVideoAccessUnit(
                        timestampMillis = System.currentTimeMillis(),
                        // Fresh per-AU buffer; collectors that retain must copy.
                        bytes = payload,
                        width = streamWidth,
                        height = streamHeight,
                    ),
                )
                if (usingNativeRenderer) {
                    NativeMirrorJni.recordTransportIngress()
                    if (!NativeMirrorJni.consumeH264(payload)) {
                        val reason = "VideoToolbox rejected the H.264 access unit"
                        if (config.rendererMode == MirrorRendererMode.Accelerated) error(reason)
                        usingNativeRenderer = false
                        NativeMirrorJni.destroyPresentation()
                        nativeHost = null
                        publishLegacySession(
                            serial,
                            config,
                            fallbackReason = "$reason; using legacy CPU presentation",
                        )
                        status.value = "Native mirror failed; falling back to legacy CPU presentation"
                    } else {
                        if (!nativeHardwareVerified && NativeMirrorJni.isHardwareReady()) {
                            nativeHardwareVerified = true
                            publishNativeSession(serial, config)
                            status.value = "Verified native VideoToolbox/Metal mirror connected (${captureSize.width}x${captureSize.height})"
                        }
                        val now = System.nanoTime()
                        val framesPresented = NativeMirrorJni.framesPresented()
                        val elapsedNanos = now - nativeStatsWindowStartedAt
                        if (elapsedNanos >= 1_000_000_000L) {
                            val displayedFps = if (elapsedNanos > 0L) {
                                (framesPresented - nativeStatsWindowFrames).coerceAtLeast(0) * 1_000_000_000f / elapsedNanos
                            } else {
                                0f
                            }
                            nativeStatsWindowStartedAt = now
                            nativeStatsWindowFrames = framesPresented
                            session.value?.let { active ->
                                session.value = active.copy(
                                    stats = active.stats.copy(
                                        displayedFps = displayedFps,
                                        decodedFps = displayedFps,
                                        framesPresented = framesPresented,
                                        p95InputToPresentMillis = NativeMirrorJni.p95InputToPresentMillis(),
                                    ),
                                )
                            }
                        }
                    }
                }
                if (!usingNativeRenderer) {
                    // The JavaCV path is strictly a fallback. Do not even create FFmpeg's
                    // software codec/parser during a native VideoToolbox/Metal session: that
                    // avoids competing decoder state and proves the normal path has no JVM
                    // frame-presentation dependency.
                    if (codecContext == null || packet == null || frame == null) {
                        val codec = avcodec.avcodec_find_decoder(avcodec.AV_CODEC_ID_H264)
                            ?: error("H.264 fallback decoder not available")
                        codecContext = avcodec.avcodec_alloc_context3(codec)
                            ?: error("Unable to allocate H.264 fallback decoder")
                        codecContext.width(captureSize.width)
                        codecContext.height(captureSize.height)
                        if (avcodec.avcodec_open2(codecContext, codec, null as PointerPointer<*>?) < 0) {
                            error("Unable to open H.264 fallback decoder")
                        }
                        packet = avcodec.av_packet_alloc()
                            ?: error("Unable to allocate H.264 fallback packet")
                        frame = avutil.av_frame_alloc()
                            ?: error("Unable to allocate H.264 fallback frame")
                    }
                    val cpuCodecContext = checkNotNull(codecContext)
                    val cpuPacket = checkNotNull(packet)
                    val cpuFrame = checkNotNull(frame)
                    // `send_frame_meta=true` preserves MediaCodec output-buffer boundaries,
                    // so every non-session payload is already a complete Annex-B access unit.
                    // Feeding it directly avoids a second parser queue and accepts a standalone
                    // SPS/PPS config packet, which FFmpeg's parser may legally report as zero
                    // consumed while waiting for a later picture.
                    if (avcodec.av_new_packet(cpuPacket, payload.size) < 0) {
                        error("Unable to allocate H.264 fallback packet payload")
                    }
                    cpuPacket.data().position(0).put(payload, 0, payload.size)
                    val sendResult = avcodec.avcodec_send_packet(cpuCodecContext, cpuPacket)
                    avcodec.av_packet_unref(cpuPacket)
                    if (sendResult >= 0) {
                        val beforeFrameNumber = frameNumber
                        frameNumber = receiveDecodedFrames(cpuCodecContext, cpuFrame, swsContext, frameNumber, decodedFps)
                        swsContext = currentSwsContext
                        if (frameNumber > beforeFrameNumber) {
                            fpsWindowFrame += frameNumber - beforeFrameNumber
                            val now = System.nanoTime()
                            val elapsedNanos = now - fpsWindowStartedAt
                            if (elapsedNanos >= 1_000_000_000L) {
                                decodedFps = fpsWindowFrame * 1_000_000_000f / elapsedNanos
                                fpsWindowFrame = 0
                                fpsWindowStartedAt = now
                            }
                        }
                    }
                }
            }
            val exitCode = runCatching { process.exitValue() }.getOrNull()
            status.value = "Video stream ended${exitCode?.let { " ($it)" }.orEmpty()}"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            status.value = "Video decode failed: ${error.message ?: error::class.simpleName}"
        } finally {
            serverStderrPump.cancel()
            serverStdoutPump.cancel()
            runCatching { socket?.close() }
            synchronized(controlLock) {
                runCatching { controlOutput?.close() }
                controlOutput = null
                runCatching { controlSocket?.close() }
                controlSocket = null
            }
            swsContext?.let { swscale.sws_freeContext(it) }
            frame?.let { avutil.av_frame_free(it) }
            packet?.let { avcodec.av_packet_free(it) }
            // Avoid avcodec_free_context() here for now. JavaCPP/FFmpeg may crash if
            // packet pointers are still referenced during teardown.
            currentDecodedFrameBuffer?.close()
            currentDecodedFrameBuffer = null
            process.destroy()
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
            }
            runner.run(listOf(adb, "-s", serial, "forward", "--remove", "tcp:$forwardPort"), 3)
            if (videoForwardPort == forwardPort) videoForwardPort = null
            videoProcess = null
        }
    }

    private var currentSwsContext: SwsContext? = null
    private var currentDecodedFrameBuffer: DecodedFrameBuffer? = null

    private fun receiveDecodedFrames(
        codecContext: AVCodecContext,
        frame: AVFrame,
        swsContext: SwsContext?,
        frameNumber: Long,
        decodedFps: Float,
    ): Long {
        var nextFrameNumber = frameNumber
        var context = swsContext
        while (true) {
            val receiveResult = avcodec.avcodec_receive_frame(codecContext, frame)
            if (receiveResult < 0) break
            val width = frame.width()
            val height = frame.height()
            if (context == null || width != frames.value.width || height != frames.value.height) {
                context?.let { swscale.sws_freeContext(it) }
                context = swscale.sws_getContext(
                    width,
                    height,
                    frame.format(),
                    width,
                    height,
                    avutil.AV_PIX_FMT_BGRA,
                    swscale.SWS_FAST_BILINEAR,
                    null,
                    null,
                    null as DoubleArray?,
                )
            }
            val output = decodedFrameBuffer(width, height)
            val scaledRows = swscale.sws_scale(context, frame.data(), frame.linesize(), 0, height, output.dstData, output.dstLinesize)
            if (scaledRows <= 0) {
                avutil.av_frame_unref(frame)
                continue
            }
            publishCpuFrame(MirrorFrame(width, height, bgraToArgb(output.bgra, width * height), ++nextFrameNumber, decodedFps.takeIf { it > 0f }))
            avutil.av_frame_unref(frame)
        }
        currentSwsContext = context
        return nextFrameNumber
    }

    private fun decodedFrameBuffer(width: Int, height: Int): DecodedFrameBuffer {
        currentDecodedFrameBuffer?.takeIf { it.width == width && it.height == height }?.let { return it }
        currentDecodedFrameBuffer?.close()
        val bgra = BytePointer(width.toLong() * height * 4L)
        val dstData = PointerPointer<BytePointer>(4)
        val dstLinesize = IntPointer(4)
        avutil.av_image_fill_arrays(dstData, dstLinesize, bgra, avutil.AV_PIX_FMT_BGRA, width, height, 1)
        return DecodedFrameBuffer(width, height, bgra, dstData, dstLinesize).also {
            currentDecodedFrameBuffer = it
        }
    }

    private fun bgraToArgb(bytes: BytePointer, pixelCount: Int): IntArray {
        val pixels = IntArray(pixelCount)
        bytes.position(0)
            .limit(pixelCount.toLong() * 4L)
            .asByteBuffer()
            .order(ByteOrder.LITTLE_ENDIAN)
            .asIntBuffer()
            .get(pixels)
        return pixels
    }

    private data class DecodedFrameBuffer(
        val width: Int,
        val height: Int,
        val bgra: BytePointer,
        val dstData: PointerPointer<BytePointer>,
        val dstLinesize: IntPointer,
    ) {
        fun close() {
            bgra.close()
            dstData.close()
            dstLinesize.close()
        }
    }

    private suspend fun captureSize(adb: String, serial: String, maxSize: Int): CaptureSize {
        // Retry briefly: on first emulator boot wm size often fails or returns before the
        // display mode is final, which previously fell back to 720x1280 and made Live too wide.
        var display = readEmulatorDisplaySize(adb, serial)
        if (display == null && serial.isEmulatorSerial()) {
            repeat(10) {
                delay(400)
                display = readEmulatorDisplaySize(adb, serial)
                if (display != null) return@repeat
            }
        }
        val sourceWidth = display?.width ?: 1080
        val sourceHeight = display?.height ?: 2400
        val (width, height) = scaledCaptureSize(sourceWidth, sourceHeight, maxSize)
        return CaptureSize(width = width, height = height)
    }

    private fun allocateLocalPort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private fun connectScrcpySocket(port: Int): Socket {
        var lastError: Throwable? = null
        repeat(100) {
            try {
                val socket = Socket("127.0.0.1", port)
                socket.tcpNoDelay = true
                return socket
            } catch (error: Throwable) {
                lastError = error
                Thread.sleep(100)
            }
        }
        throw IllegalStateException("Unable to connect scrcpy socket", lastError)
    }

    private data class CaptureSize(val width: Int, val height: Int)
}
