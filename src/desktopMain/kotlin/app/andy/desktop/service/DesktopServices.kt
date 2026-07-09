package app.andy.desktop.service

import app.andy.desktop.updates.DesktopAppUpdateService
import app.andy.model.*
import app.andy.service.*
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ClientInterceptors
import io.grpc.ForwardingClientCall
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.stub.ClientCalls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVCodecParserContext
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

fun createDesktopServices(): AndyServices {
    val runner = CommandRunner()
    val locator = SdkLocator()
    val store = DesktopWorkspaceStore()
    val devices = DesktopDeviceService(runner, locator, store)
    val mirror = DesktopMirrorEngine(runner, devices)
    val logcat = DesktopLogcatService(runner, devices)
    val updatesScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val updates = DesktopAppUpdateService(updatesScope)
    val actionConfig = DesktopActionConfigStore()
    val actionRuns = DesktopActionRunService(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    val avd = DesktopAvdService(runner, locator) { store.load().selectedSdkPath }
    val intents = DesktopIntentService(runner, devices)
    val apps = DesktopAppService(runner, devices)
    val files = DesktopFileService(runner, devices)
    val hostFiles = DesktopHostFileService(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
    val proxy = DesktopProxyService(runner, devices)
    val accessibility = DesktopAccessibilityService(runner, devices)

    val mcp = DesktopMcpServerService(
        devices = devices,
        avd = avd,
        mirror = mirror,
        logcat = logcat,
        intents = intents,
        apps = apps,
        files = files,
        proxy = proxy,
        accessibility = accessibility,
        workspaceStore = store
    )

    return AndyServices(
        devices = devices,
        avd = avd,
        mirror = mirror,
        logcat = logcat,
        intents = intents,
        apps = apps,
        files = files,
        hostFiles = hostFiles,
        proxy = proxy,
        metrics = DesktopMetricsService(runner, devices),
        accessibility = accessibility,
        bugs = DesktopBugService(mirror, logcat, devices = devices, accessibility = accessibility),
        artifacts = DesktopArtifactService(runner, devices, mirror),
        workspaceStore = store,
        updates = updates,
        mcp = mcp,
        actionConfig = actionConfig,
        actionRuns = actionRuns,
    )
}

private const val EMULATOR_IMAGE_BYTES_PER_PIXEL = 3
private const val EMULATOR_DISPLAY_SIZE_SETTLE_NANOS = 60_000_000_000L
private const val EMULATOR_DISPLAY_SIZE_REFRESH_MIN_NANOS = 1_000_000_000L
private val EMULATOR_WM_SIZE_REGEX = Regex("""(?:Physical|Override) size:\s*(\d+)x(\d+)""")

internal data class EmulatorDisplaySize(val width: Int, val height: Int)
internal data class EmulatorTouchPoint(val x: Int, val y: Int)

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
    val row = ByteArray(pixels.size * 3)
    bytes.get(row)
    var source = 0
    for (index in pixels.indices) {
        val red = row[source++].toInt() and 0xff
        val green = row[source++].toInt() and 0xff
        val blue = row[source++].toInt() and 0xff
        pixels[index] = 0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
    }
    return pixels
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
    override val frames = MutableStateFlow(MirrorFrame(1, 1, intArrayOf(0xff000000.toInt())))
    override val status = MutableStateFlow("Ready for embedded mirror")
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
    private val controlLock = Any()

    override suspend fun connect(serial: String, config: MirrorVideoConfig): CommandResult {
        if (connectedSerial == serial && connectedConfig == config && videoJob?.isActive == true) {
            return CommandResult.success("Embedded mirror already connected for $serial")
        }
        disconnect()
        connectedSerial = serial
        connectedConfig = config
        connectedAtNanos = System.nanoTime()
        lastEmulatorDisplaySizeRefreshNanos = 0L
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        frames.value = MirrorFrame(1, 1, intArrayOf(0xff000000.toInt()))
        if (serial.isEmulatorSerial()) {
            val grpcPort = serial.emulatorConsolePort()?.plus(3000)
                ?: return CommandResult.failure("Unable to resolve emulator gRPC port for $serial")
            val token = readEmulatorGrpcToken(grpcPort)
            val client = EmulatorGrpcClient("127.0.0.1", grpcPort, token, readEmulatorDisplaySize(adb, serial))
            emulatorGrpcClient = client
            status.value = "Starting emulator gRPC mirror for $serial on port $grpcPort"
            videoJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                runEmulatorGrpcVideoLoop(adb, serial, client, config)
            }
            return CommandResult.success("Emulator gRPC mirror starting for $serial")
        }
        val scrcpyServer = ScrcpyServerLocator.find()
            ?: return CommandResult.failure("scrcpy-server not found. Andy bundles it for packaged builds; for development, install scrcpy with `brew install scrcpy` or set SCRCPY_SERVER_PATH.")
        status.value = "Starting scrcpy-server raw H.264 mirror for $serial (${config.maxSize}px, ${config.bitRate / 1_000_000.0} Mbps)"
        videoJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runNativeVideoLoop(adb, serial, scrcpyServer, config)
        }
        return CommandResult.success("Embedded mirror starting for $serial")
    }

    override suspend fun disconnect() {
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
        videoProcess?.destroyForcibly()
        videoProcess = null
        videoForwardPort?.let { port ->
            val adb = devices.adbPath()
            if (adb != null && connectedSerial != null) {
                runner.run(listOf(adb, "-s", connectedSerial!!, "forward", "--remove", "tcp:$port"), 3)
            }
        }
        videoForwardPort = null
        connectedConfig = null
        connectedAtNanos = 0L
        lastEmulatorDisplaySizeRefreshNanos = 0L
        // Wait for the video loop to fully stop before clearing the frame so a late
        // in-flight frame can't win the race and leave a frozen image on screen.
        job?.let { runCatching { it.join() } }
        frames.value = MirrorFrame(1, 1, intArrayOf(0xff000000.toInt()))
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

    override suspend fun screenshot(serial: String): ByteArray? {
        val adb = devices.adbPath() ?: return null
        val result = runner.run(listOf(adb, "-s", serial, "exec-out", "screencap", "-p"), 8)
        return result.stdout.encodeToByteArray().takeIf { result.isSuccess }
    }

    private suspend fun readEmulatorDisplaySize(adb: String, serial: String): EmulatorDisplaySize? {
        val result = runner.run(listOf(adb, "-s", serial, "shell", "wm", "size"), 4)
        if (!result.isSuccess) return null
        return result.stdout
            .lineSequence()
            .mapNotNull { line -> EMULATOR_WM_SIZE_REGEX.find(line)?.destructured }
            .mapNotNull { (width, height) ->
                val parsedWidth = width.toIntOrNull()
                val parsedHeight = height.toIntOrNull()
                if (parsedWidth != null && parsedHeight != null && parsedWidth > 0 && parsedHeight > 0) {
                    EmulatorDisplaySize(parsedWidth, parsedHeight)
                } else {
                    null
                }
            }
            .firstOrNull()
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
                frames.value = MirrorFrame(
                    width = image.width,
                    height = image.height,
                    argb = emulatorRgb888ToArgb(image.width, image.height, rgb),
                    frameNumber = ++frameNumber,
                    decodedFps = decodedFps.takeIf { it > 0f },
                )
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
            "raw_stream=true",
            "max_size=${config.maxSize}",
            "video_bit_rate=${config.bitRate}",
            "max_fps=${config.maxFps}",
            "log_level=info",
        )
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
        delay(500)
        var socket: Socket? = null
        var parser: AVCodecParserContext? = null
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
            val codec = avcodec.avcodec_find_decoder(avcodec.AV_CODEC_ID_H264)
                ?: error("H.264 decoder not available")
            parser = avcodec.av_parser_init(avcodec.AV_CODEC_ID_H264)
                ?: error("H.264 parser not available")
            codecContext = avcodec.avcodec_alloc_context3(codec)
                ?: error("Unable to allocate H.264 decoder")
            codecContext.width(captureSize.width)
            codecContext.height(captureSize.height)
            if (avcodec.avcodec_open2(codecContext, codec, null as PointerPointer<*>?) < 0) {
                error("Unable to open H.264 decoder")
            }
            packet = avcodec.av_packet_alloc()
                ?: error("Unable to allocate H.264 packet")
            frame = avutil.av_frame_alloc()
                ?: error("Unable to allocate H.264 frame")

            val input = BufferedInputStream(socket.getInputStream(), 1 shl 20)
            val readBuffer = ByteArray(64 * 1024)
            while (isActive) {
                val read = input.read(readBuffer)
                if (read < 0) break
                val inputPointer: BytePointer = BytePointer((read + 64).toLong())
                inputPointer.put(readBuffer, 0, read)
                repeat(64) { inputPointer.put(read + it.toLong(), 0.toByte()) }
                inputPointer.position(0)
                var offset = 0
                while (offset < read && isActive) {
                    val output = PointerPointer<BytePointer>(1)
                    val outputSize = IntPointer(1)
                    val consumed = avcodec.av_parser_parse2(
                        parser,
                        codecContext,
                        output,
                        outputSize,
                        inputPointer.position(offset.toLong()),
                        read - offset,
                        avutil.AV_NOPTS_VALUE,
                        avutil.AV_NOPTS_VALUE,
                        0,
                    )
                    if (consumed < 0) error("Unable to parse H.264 stream")
                    offset += consumed
                    if (outputSize.get() > 0) {
                        packet.data(output.get(BytePointer::class.java))
                        packet.size(outputSize.get())
                        val sendResult = avcodec.avcodec_send_packet(codecContext, packet)
                        avcodec.av_packet_unref(packet)
                        if (sendResult >= 0) {
                            val beforeFrameNumber = frameNumber
                            frameNumber = receiveDecodedFrames(codecContext, frame, swsContext, frameNumber, decodedFps)
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
                    output.close()
                    outputSize.close()
                }
                inputPointer.close()
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
            parser?.let { avcodec.av_parser_close(it) }
            // Avoid avcodec_free_context() here for now. JavaCPP/FFmpeg may crash if
            // the parser-owned packet pointers are still referenced during teardown.
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
            frames.value = MirrorFrame(width, height, bgraToArgb(output.bgra, width * height), ++nextFrameNumber, decodedFps.takeIf { it > 0f })
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
        val output = runner.run(listOf(adb, "-s", serial, "shell", "wm", "size"), 3).stdout
        val match = Regex("""Physical size:\s*(\d+)x(\d+)""").find(output)
        val sourceWidth = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 720
        val sourceHeight = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 1280
        val longestSide = maxOf(sourceWidth, sourceHeight)
        val scale = if (longestSide > maxSize) maxSize.toDouble() / longestSide else 1.0
        return CaptureSize(
            width = evenAtLeast((sourceWidth * scale).toInt(), 2),
            height = evenAtLeast((sourceHeight * scale).toInt(), 2),
        )
    }

    private fun evenAtLeast(value: Int, minimum: Int): Int {
        return maxOf(minimum, value and -2)
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

internal object ScrcpyServerLocator {
    fun find(): File? {
        val envPath = System.getenv("SCRCPY_SERVER_PATH")?.takeIf { it.isNotBlank() }?.let(::File)
        if (envPath != null && envPath.isFile) return envPath

        bundledServer()?.let { return it }

        val candidates = listOf(
            "/opt/homebrew/Cellar/scrcpy/4.0/share/scrcpy/scrcpy-server",
            "/opt/homebrew/share/scrcpy/scrcpy-server",
            "/usr/local/share/scrcpy/scrcpy-server",
            "/Applications/scrcpy/scrcpy-server",
        ).map(::File)
        return candidates.firstOrNull { it.isFile }
    }

    private fun bundledServer(): File? {
        val target = File(System.getProperty("user.home"), ".andy/scrcpy/scrcpy-server")
        val resource = javaClass.classLoader.getResourceAsStream("scrcpy/scrcpy-server") ?: return null
        target.parentFile.mkdirs()
        try {
            resource.use { input ->
                Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            target.setReadable(true, false)
        } catch (_: Exception) {
            if (!target.isFile || target.length() == 0L) return null
        }
        return target.takeIf { it.isFile && it.length() > 0 }
    }
}

private class EmulatorMappedFramebuffer private constructor(
    private val file: File,
    private val channel: FileChannel,
    private val buffer: MappedByteBuffer,
) : AutoCloseable {
    val handle: String = file.toPath().toUri().toASCIIString()

    fun frameBytes(byteCount: Int): ByteBuffer? {
        if (byteCount <= 0 || byteCount > buffer.capacity()) return null
        val frame = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        frame.position(0)
        frame.limit(byteCount)
        return frame.slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { file.delete() }
    }

    companion object {
        fun create(maxSize: Int): EmulatorMappedFramebuffer {
            val boundedMaxSize = maxSize.coerceIn(1, 4096)
            val byteCount = boundedMaxSize.toLong() * boundedMaxSize * EMULATOR_IMAGE_BYTES_PER_PIXEL + 4096L
            val file = File.createTempFile("andy-emulator-framebuffer-", ".rgb")
            file.deleteOnExit()
            val channel = FileChannel.open(
                file.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            try {
                channel.truncate(byteCount)
                val buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, byteCount)
                return EmulatorMappedFramebuffer(file, channel, buffer)
            } catch (error: Throwable) {
                runCatching { channel.close() }
                runCatching { file.delete() }
                throw error
            }
        }
    }
}

private class EmulatorGrpcClient(
    host: String,
    port: Int,
    token: String?,
    initialDisplaySize: EmulatorDisplaySize?,
) {
    var displaySize: EmulatorDisplaySize? = initialDisplaySize
        private set
    private val managedChannel: ManagedChannel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext()
        .build()
    private val channel: Channel = token
        ?.let { ClientInterceptors.intercept(managedChannel, EmulatorGrpcAuthInterceptor(it)) }
        ?: managedChannel

    fun streamScreenshots(maxSize: Int, mmapHandle: String? = null): Iterator<EmulatorImage> {
        return ClientCalls.blockingServerStreamingCall(
            channel,
            STREAM_SCREENSHOT_METHOD,
            CallOptions.DEFAULT,
            EmulatorImageFormat(maxSize, mmapHandle),
        )
    }

    fun sendInput(input: MirrorInput, frame: MirrorFrame): CommandResult? {
        return when (input) {
            is MirrorInput.Touch -> sendTouch(
                touch = EmulatorTouch(
                    x = scaleX(input.x, frame),
                    y = scaleY(input.y, frame),
                    pressure = if (input.action == MirrorTouchAction.Up) 0 else 1,
                ),
            )
            is MirrorInput.Tap -> {
                val x = scaleX(input.x, frame)
                val y = scaleY(input.y, frame)
                sendTouch(EmulatorTouch(x, y, pressure = 1))
                sendTouch(EmulatorTouch(x, y, pressure = 0))
            }
            is MirrorInput.Swipe -> {
                val startX = scaleX(input.startX, frame)
                val startY = scaleY(input.startY, frame)
                val endX = scaleX(input.endX, frame)
                val endY = scaleY(input.endY, frame)
                sendTouch(EmulatorTouch(startX, startY, pressure = 1))
                sendTouch(EmulatorTouch(endX, endY, pressure = 1))
                sendTouch(EmulatorTouch(endX, endY, pressure = 0))
            }
            is MirrorInput.Text -> sendText(input.value)
            // Named keys go over gRPC for snappy typing; anything unmapped (volume,
            // etc.) returns null so the caller falls back to adb keyevent.
            is MirrorInput.Key -> domKeyName(input.keyCode)?.let { sendNamedKey(it) }
            MirrorInput.Back,
            MirrorInput.Home,
            MirrorInput.Recents,
            MirrorInput.Power -> null
        }
    }

    // Returns null when the gRPC keyboard call cannot be delivered so the caller
    // falls back to adb `input text`/`keyevent` instead of surfacing an error.
    private fun sendText(text: String): CommandResult? {
        if (text.isEmpty()) return null
        return sendKey(EmulatorKeyEvent(eventType = KEY_EVENT_KEYPRESS, text = text))
    }

    private fun sendNamedKey(key: String): CommandResult? {
        val down = sendKey(EmulatorKeyEvent(eventType = KEY_EVENT_KEYDOWN, key = key)) ?: return null
        return sendKey(EmulatorKeyEvent(eventType = KEY_EVENT_KEYUP, key = key)) ?: down
    }

    private fun sendKey(event: EmulatorKeyEvent): CommandResult? {
        return runCatching {
            ClientCalls.blockingUnaryCall(channel, SEND_KEY_METHOD, CallOptions.DEFAULT, event)
            CommandResult.success("Input sent")
        }.getOrNull()
    }

    // Android key codes (KeyEvent.KEYCODE_*) → DOM-style key names the emulator understands.
    private fun domKeyName(androidKeyCode: Int): String? = when (androidKeyCode) {
        66 -> "Enter"
        67 -> "Backspace"
        112 -> "Delete"
        61 -> "Tab"
        111 -> "Escape"
        19 -> "ArrowUp"
        20 -> "ArrowDown"
        21 -> "ArrowLeft"
        22 -> "ArrowRight"
        122 -> "Home"
        123 -> "End"
        92 -> "PageUp"
        93 -> "PageDown"
        else -> null
    }

    private fun scaleX(x: Int, frame: MirrorFrame): Int {
        return scaledEmulatorTouchPoint(x, 0, frame, displaySize).x
    }

    private fun scaleY(y: Int, frame: MirrorFrame): Int {
        return scaledEmulatorTouchPoint(0, y, frame, displaySize).y
    }

    fun close() {
        runCatching {
            managedChannel.shutdownNow()
            managedChannel.awaitTermination(500, TimeUnit.MILLISECONDS)
        }
    }

    private fun sendTouch(touch: EmulatorTouch): CommandResult {
        return runCatching {
            ClientCalls.blockingUnaryCall(
                channel,
                SEND_TOUCH_METHOD,
                CallOptions.DEFAULT,
                EmulatorTouchEvent(listOf(touch)),
            )
            CommandResult.success("Input sent")
        }.getOrElse { error ->
            CommandResult.failure("Emulator gRPC touch failed: ${error.message ?: error::class.simpleName}")
        }
    }

    fun updateDisplaySize(next: EmulatorDisplaySize) {
        displaySize = next
    }

    private class EmulatorGrpcAuthInterceptor(private val token: String) : ClientInterceptor {
        override fun <ReqT : Any?, RespT : Any?> interceptCall(
            method: MethodDescriptor<ReqT, RespT>,
            callOptions: CallOptions,
            next: Channel,
        ): ClientCall<ReqT, RespT> {
            return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
                override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                    headers.put(AUTHORIZATION, "Bearer $token")
                    super.start(responseListener, headers)
                }
            }
        }
    }

    private companion object {
        private val AUTHORIZATION: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
        private val STREAM_SCREENSHOT_METHOD: MethodDescriptor<EmulatorImageFormat, EmulatorImage> =
            MethodDescriptor.newBuilder<EmulatorImageFormat, EmulatorImage>()
                .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
                .setFullMethodName(
                    MethodDescriptor.generateFullMethodName(
                        "android.emulation.control.EmulatorController",
                        "streamScreenshot",
                    ),
                )
                .setRequestMarshaller(EmulatorImageFormatMarshaller)
                .setResponseMarshaller(EmulatorImageMarshaller)
                .build()
        private val SEND_TOUCH_METHOD: MethodDescriptor<EmulatorTouchEvent, Unit> =
            MethodDescriptor.newBuilder<EmulatorTouchEvent, Unit>()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(
                    MethodDescriptor.generateFullMethodName(
                        "android.emulation.control.EmulatorController",
                        "sendTouch",
                    ),
                )
                .setRequestMarshaller(EmulatorTouchEventMarshaller)
                .setResponseMarshaller(EmulatorEmptyMarshaller)
                .build()
        private val SEND_KEY_METHOD: MethodDescriptor<EmulatorKeyEvent, Unit> =
            MethodDescriptor.newBuilder<EmulatorKeyEvent, Unit>()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(
                    MethodDescriptor.generateFullMethodName(
                        "android.emulation.control.EmulatorController",
                        "sendKey",
                    ),
                )
                .setRequestMarshaller(EmulatorKeyEventMarshaller)
                .setResponseMarshaller(EmulatorEmptyMarshaller)
                .build()
    }
}

private const val KEY_EVENT_KEYDOWN = 0
private const val KEY_EVENT_KEYUP = 1
private const val KEY_EVENT_KEYPRESS = 2

internal data class EmulatorImageFormat(val maxSize: Int, val mmapHandle: String? = null)
internal data class EmulatorImage(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
    val seq: Long = 0,
    val timestampUs: Long = 0,
)
internal data class EmulatorTouch(val x: Int, val y: Int, val pressure: Int)
internal data class EmulatorTouchEvent(val touches: List<EmulatorTouch>, val display: Int = 0)
internal data class EmulatorKeyEvent(val eventType: Int = KEY_EVENT_KEYDOWN, val key: String = "", val text: String = "")

private object EmulatorImageFormatMarshaller : MethodDescriptor.Marshaller<EmulatorImageFormat> {
    override fun stream(value: EmulatorImageFormat): InputStream {
        return ByteArrayInputStream(EmulatorGrpcProto.imageFormat(value.maxSize.coerceAtLeast(1), value.mmapHandle))
    }

    override fun parse(stream: InputStream): EmulatorImageFormat {
        stream.readAllBytes()
        return EmulatorImageFormat(0)
    }
}

private object EmulatorImageMarshaller : MethodDescriptor.Marshaller<EmulatorImage> {
    override fun stream(value: EmulatorImage): InputStream {
        return ByteArrayInputStream(ByteArray(0))
    }

    override fun parse(stream: InputStream): EmulatorImage {
        return EmulatorGrpcProto.parseImage(stream.readAllBytes())
    }
}

private object EmulatorTouchEventMarshaller : MethodDescriptor.Marshaller<EmulatorTouchEvent> {
    override fun stream(value: EmulatorTouchEvent): InputStream {
        return ByteArrayInputStream(EmulatorGrpcProto.touchEvent(value))
    }

    override fun parse(stream: InputStream): EmulatorTouchEvent {
        stream.readAllBytes()
        return EmulatorTouchEvent(emptyList())
    }
}

private object EmulatorKeyEventMarshaller : MethodDescriptor.Marshaller<EmulatorKeyEvent> {
    override fun stream(value: EmulatorKeyEvent): InputStream {
        return ByteArrayInputStream(EmulatorGrpcProto.keyEvent(value))
    }

    override fun parse(stream: InputStream): EmulatorKeyEvent {
        stream.readAllBytes()
        return EmulatorKeyEvent()
    }
}

private object EmulatorEmptyMarshaller : MethodDescriptor.Marshaller<Unit> {
    override fun stream(value: Unit): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun parse(stream: InputStream) {
        stream.readAllBytes()
    }
}

internal object EmulatorGrpcProto {
    fun imageFormat(maxSize: Int, mmapHandle: String? = null): ByteArray {
        val writer = ProtoWriter()
        writer.varint(1, 2) // ImageFormat.RGB888
        writer.varint(3, maxSize.toLong())
        writer.varint(4, maxSize.toLong())
        if (!mmapHandle.isNullOrBlank()) {
            val transport = ProtoWriter()
            transport.varint(1, 1) // ImageTransport.MMAP
            transport.string(2, mmapHandle)
            writer.bytes(6, transport.toByteArray())
        }
        return writer.toByteArray()
    }

    fun touchEvent(event: EmulatorTouchEvent): ByteArray {
        val writer = ProtoWriter()
        event.touches.forEach { touch ->
            val touchWriter = ProtoWriter()
            touchWriter.varint(1, touch.x.coerceAtLeast(0).toLong())
            touchWriter.varint(2, touch.y.coerceAtLeast(0).toLong())
            touchWriter.varint(3, 0)
            touchWriter.varint(4, touch.pressure.coerceAtLeast(0).toLong())
            touchWriter.varint(7, 1) // NEVER_EXPIRE; Andy sends explicit pressure=0 on release.
            writer.bytes(1, touchWriter.toByteArray())
        }
        if (event.display != 0) writer.varint(2, event.display.toLong())
        return writer.toByteArray()
    }

    // KeyboardEvent { codeType=1(Usb, default), eventType=2, keyCode=3, key=4, text=5 }
    fun keyEvent(event: EmulatorKeyEvent): ByteArray {
        val writer = ProtoWriter()
        if (event.eventType != 0) writer.varint(2, event.eventType.toLong())
        if (event.key.isNotEmpty()) writer.string(4, event.key)
        if (event.text.isNotEmpty()) writer.string(5, event.text)
        return writer.toByteArray()
    }

    fun parseImage(bytes: ByteArray): EmulatorImage {
        val reader = ProtoReader(bytes)
        var width = 0
        var height = 0
        var deprecatedWidth = 0
        var deprecatedHeight = 0
        var image = ByteArray(0)
        var seq = 0L
        var timestampUs = 0L
        while (!reader.isAtEnd()) {
            when (val tag = reader.readTag()) {
                10 -> {
                    val format = parseImageFormat(reader.readBytes())
                    width = format.first
                    height = format.second
                }
                16 -> deprecatedWidth = reader.readVarint().toInt()
                24 -> deprecatedHeight = reader.readVarint().toInt()
                34 -> image = reader.readBytes()
                40 -> seq = reader.readVarint()
                48 -> timestampUs = reader.readVarint()
                else -> reader.skip(tag)
            }
        }
        return EmulatorImage(
            width = width.takeIf { it > 0 } ?: deprecatedWidth,
            height = height.takeIf { it > 0 } ?: deprecatedHeight,
            pixels = image,
            seq = seq,
            timestampUs = timestampUs,
        )
    }

    private fun parseImageFormat(bytes: ByteArray): Pair<Int, Int> {
        val reader = ProtoReader(bytes)
        var width = 0
        var height = 0
        while (!reader.isAtEnd()) {
            when (val tag = reader.readTag()) {
                8 -> reader.readVarint()
                24 -> width = reader.readVarint().toInt()
                32 -> height = reader.readVarint().toInt()
                else -> reader.skip(tag)
            }
        }
        return width to height
    }

    class ProtoWriter {
        private val output = ByteArrayOutputStream()

        fun varint(field: Int, value: Long) {
            tag(field, 0)
            writeVarint(value)
        }

        fun bytes(field: Int, bytes: ByteArray) {
            tag(field, 2)
            writeVarint(bytes.size.toLong())
            output.write(bytes)
        }

        fun string(field: Int, value: String) {
            bytes(field, value.encodeToByteArray())
        }

        fun toByteArray(): ByteArray = output.toByteArray()

        private fun tag(field: Int, wireType: Int) {
            writeVarint(((field shl 3) or wireType).toLong())
        }

        private fun writeVarint(value: Long) {
            var remaining = value
            while ((remaining and 0x7f.inv().toLong()) != 0L) {
                output.write(((remaining and 0x7f) or 0x80).toInt())
                remaining = remaining ushr 7
            }
            output.write(remaining.toInt())
        }
    }

    private class ProtoReader(private val bytes: ByteArray) {
        private var offset = 0

        fun isAtEnd(): Boolean = offset >= bytes.size

        fun readTag(): Int = readVarint().toInt()

        fun readVarint(): Long {
            var shift = 0
            var result = 0L
            while (shift < 64 && offset < bytes.size) {
                val b = bytes[offset++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            return result
        }

        fun readBytes(): ByteArray {
            val size = readVarint().toInt().coerceAtLeast(0)
            val end = (offset + size).coerceAtMost(bytes.size)
            return bytes.copyOfRange(offset, end).also { offset = end }
        }

        fun skip(tag: Int) {
            when (tag and 0x7) {
                0 -> readVarint()
                1 -> offset = (offset + 8).coerceAtMost(bytes.size)
                2 -> {
                    val size = readVarint().toInt().coerceAtLeast(0)
                    offset = (offset + size).coerceAtMost(bytes.size)
                }
                5 -> offset = (offset + 4).coerceAtMost(bytes.size)
                else -> offset = bytes.size
            }
        }
    }
}

private fun String.isEmulatorSerial(): Boolean = startsWith("emulator-")

private fun String.emulatorConsolePort(): Int? = removePrefix("emulator-").toIntOrNull()

private fun readEmulatorGrpcToken(grpcPort: Int): String? {
    return emulatorGrpcDiscoveryFiles()
        .asSequence()
        .mapNotNull { file -> loadEmulatorGrpcDiscovery(file) }
        .firstOrNull { discovery -> discovery.port == grpcPort }
        ?.token
}

private data class EmulatorGrpcDiscovery(val port: Int?, val token: String?)

private fun emulatorGrpcDiscoveryFiles(): List<File> {
    val home = File(System.getProperty("user.home"))
    val tmpDir = System.getenv("TMPDIR")?.takeIf { it.isNotBlank() }?.let(::File)
    val xdgRuntime = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
    val roots = listOfNotNull(
        File(home, "Library/Caches/TemporaryItems/avd/running"),
        tmpDir?.resolve("avd/running"),
        xdgRuntime?.resolve("avd/running"),
        File(System.getProperty("java.io.tmpdir"), "avd/running"),
        File("/tmp/android-${System.getProperty("user.name")}/avd/running"),
    ).distinctBy { it.absolutePath }
    return roots.flatMap { root ->
        root.listFiles { file -> file.isFile && file.name.startsWith("pid_") && file.name.endsWith(".ini") }
            ?.toList()
            .orEmpty()
    }
}

private fun loadEmulatorGrpcDiscovery(file: File): EmulatorGrpcDiscovery? {
    val entries = runCatching {
        file.readLines()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank() || trimmed.startsWith("#") || "=" !in trimmed) {
                    null
                } else {
                    trimmed.substringBefore("=").trim() to trimmed.substringAfter("=").trim()
                }
            }
            .toMap()
    }.getOrNull() ?: return null
    val port = entries["grpc.port"]?.toIntOrNull()
    val token = entries["grpc.token"]?.takeIf { it.isNotBlank() }
    return EmulatorGrpcDiscovery(port = port, token = token)
}

private object ScrcpyControlMessage {
    private const val TYPE_INJECT_KEYCODE = 0
    private const val TYPE_INJECT_TEXT = 1
    private const val TYPE_INJECT_TOUCH_EVENT = 2
    private const val ACTION_DOWN = 0
    private const val ACTION_UP = 1
    private const val ACTION_MOVE = 2
    private const val POINTER_ID_GENERIC_FINGER = -2L

    fun serialize(input: MirrorInput, frame: MirrorFrame): List<ByteArray> {
        return when (input) {
            is MirrorInput.Touch -> listOf(
                touch(
                    action = when (input.action) {
                        MirrorTouchAction.Down -> ACTION_DOWN
                        MirrorTouchAction.Move -> ACTION_MOVE
                        MirrorTouchAction.Up -> ACTION_UP
                    },
                    x = input.x,
                    y = input.y,
                    frame = frame,
                    pressure = if (input.action == MirrorTouchAction.Up) 0f else 1f,
                ),
            )
            is MirrorInput.Tap -> listOf(
                touch(ACTION_DOWN, input.x, input.y, frame, pressure = 1f),
                touch(ACTION_UP, input.x, input.y, frame, pressure = 0f),
            )
            is MirrorInput.Swipe -> buildList {
                add(touch(ACTION_DOWN, input.startX, input.startY, frame, pressure = 1f))
                add(touch(ACTION_MOVE, input.endX, input.endY, frame, pressure = 1f))
                add(touch(ACTION_UP, input.endX, input.endY, frame, pressure = 0f))
            }
            is MirrorInput.Key -> keyPress(input.keyCode)
            is MirrorInput.Text -> listOf(text(input.value))
            MirrorInput.Back -> keyPress(4)
            MirrorInput.Home -> keyPress(3)
            MirrorInput.Recents -> keyPress(187)
            MirrorInput.Power -> keyPress(26)
        }
    }

    private fun keyPress(keyCode: Int): List<ByteArray> = listOf(
        key(ACTION_DOWN, keyCode),
        key(ACTION_UP, keyCode),
    )

    private fun key(action: Int, keyCode: Int): ByteArray {
        val bytes = ByteArray(14)
        bytes[0] = TYPE_INJECT_KEYCODE.toByte()
        bytes[1] = action.toByte()
        bytes.writeInt(2, keyCode)
        bytes.writeInt(6, 0)
        bytes.writeInt(10, 0)
        return bytes
    }

    private fun text(value: String): ByteArray {
        val payload = value.encodeToByteArray().take(300).toByteArray()
        val bytes = ByteArray(1 + 4 + payload.size)
        bytes[0] = TYPE_INJECT_TEXT.toByte()
        bytes.writeInt(1, payload.size)
        payload.copyInto(bytes, destinationOffset = 5)
        return bytes
    }

    private fun touch(action: Int, x: Int, y: Int, frame: MirrorFrame, pressure: Float): ByteArray {
        val width = frame.width.coerceAtLeast(1)
        val height = frame.height.coerceAtLeast(1)
        val bytes = ByteArray(32)
        bytes[0] = TYPE_INJECT_TOUCH_EVENT.toByte()
        bytes[1] = action.toByte()
        bytes.writeLong(2, POINTER_ID_GENERIC_FINGER)
        bytes.writeInt(10, x.coerceIn(0, width - 1))
        bytes.writeInt(14, y.coerceIn(0, height - 1))
        bytes.writeShort(18, width)
        bytes.writeShort(20, height)
        bytes.writeShort(22, (pressure.coerceIn(0f, 1f) * 0xffff).toInt())
        bytes.writeInt(24, 0)
        bytes.writeInt(28, 0)
        return bytes
    }

    private fun ByteArray.writeShort(offset: Int, value: Int) {
        this[offset] = ((value ushr 8) and 0xff).toByte()
        this[offset + 1] = (value and 0xff).toByte()
    }

    private fun ByteArray.writeInt(offset: Int, value: Int) {
        this[offset] = ((value ushr 24) and 0xff).toByte()
        this[offset + 1] = ((value ushr 16) and 0xff).toByte()
        this[offset + 2] = ((value ushr 8) and 0xff).toByte()
        this[offset + 3] = (value and 0xff).toByte()
    }

    private fun ByteArray.writeLong(offset: Int, value: Long) {
        this[offset] = ((value ushr 56) and 0xff).toByte()
        this[offset + 1] = ((value ushr 48) and 0xff).toByte()
        this[offset + 2] = ((value ushr 40) and 0xff).toByte()
        this[offset + 3] = ((value ushr 32) and 0xff).toByte()
        this[offset + 4] = ((value ushr 24) and 0xff).toByte()
        this[offset + 5] = ((value ushr 16) and 0xff).toByte()
        this[offset + 6] = ((value ushr 8) and 0xff).toByte()
        this[offset + 7] = (value and 0xff).toByte()
    }
}

class DesktopProxyService(
    private val runner: CommandRunner,
    private val devices: DesktopDeviceService,
    private val mitmdumpExecutable: () -> String? = { findMitmdumpExecutable() },
    private val processStarter: (List<String>, File, Map<String, String>) -> ProxyProcess = { command, directory, environment ->
        RealProxyProcess(command, directory, environment)
    },
    private val certificateSubjectHash: suspend (File) -> String? = { certificate -> defaultCertificateSubjectHash(runner, certificate) },
    private val certificateSpkiFingerprint: suspend (File) -> String? = { certificate -> defaultCertificateSpkiFingerprint(runner, certificate) },
    private val hostOsName: () -> String = { System.getProperty("os.name") },
) : ProxyService {
    override val exchanges = MutableStateFlow<List<NetworkExchange>>(emptyList())
    override val status = MutableStateFlow("Proxy stopped")
    private val proxyDir = File(System.getProperty("user.home"), ".andy/proxy")
    private val rulesFile = File(proxyDir, "rules.json")
    private val addonFile = File(proxyDir, "andy_mitm_addon.py")
    private var process: ProxyProcess? = null
    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null

    override suspend fun detectMitmproxy(): CommandResult = withContext(Dispatchers.IO) {
        val executable = mitmdumpExecutable()
        if (executable == null) {
            CommandResult.failure("mitmdump not found. Install mitmproxy with `brew install mitmproxy`.")
        } else {
            CommandResult.success(executable)
        }
    }

    override suspend fun ensureCertificateAuthority(): CommandResult = withContext(Dispatchers.IO) {
        proxyDir.mkdirs()
        writeAddon()
        CommandResult.success("mitmproxy CA will be generated at ${certificateAuthorityPath()}")
    }

    override suspend fun certificateAuthorityPath(): String = withContext(Dispatchers.IO) {
        File(proxyDir, "mitmproxy-ca-cert.cer").absolutePath
    }

    override suspend fun start(port: Int, rules: List<ProxyRule>): CommandResult = withContext(Dispatchers.IO) {
        val executable = mitmdumpExecutable() ?: return@withContext CommandResult.failure("mitmdump not found. Install mitmproxy with `brew install mitmproxy`.")
        stopCurrentProcess(updateStatus = false)
        killOrphanedProxies()
        proxyDir.mkdirs()
        writeAddon()
        writeRules(rules)
        val hostProxy = detectHostProxyState()
        val command = buildList {
            add(executable)
            hostProxy.upstreamProxy?.let { upstream ->
                add("--mode")
                add("upstream:$upstream")
            }
            addAll(
                listOf(
                    "--listen-host", "0.0.0.0",
                    "--listen-port", port.toString(),
                    "--set", "confdir=${proxyDir.absolutePath}",
                    "-s", addonFile.absolutePath,
                    "--set", "termlog_verbosity=warn",
                ),
            )
        }
        runCatching {
            process = processStarter(command, proxyDir, mapOf("ANDY_RULES_PATH" to rulesFile.absolutePath))
            status.value = "mitmdump listening on 0.0.0.0:$port" + hostProxy.upstreamProxy?.let { " via Mac proxy $it" }.orEmpty()
            pumpProcess(process!!)
            CommandResult.success(status.value)
        }.getOrElse { error ->
            status.value = "Proxy failed: ${error.message ?: error::class.simpleName}"
            CommandResult.failure(status.value)
        }
    }

    override suspend fun updateRules(rules: List<ProxyRule>): CommandResult = withContext(Dispatchers.IO) {
        proxyDir.mkdirs()
        writeRules(rules)
        CommandResult.success("Updated ${rules.size} proxy rules")
    }

    override suspend fun clearTraffic(): CommandResult = withContext(Dispatchers.IO) {
        exchanges.value = emptyList()
        CommandResult.success("Cleared network traffic")
    }

    override suspend fun stop(): CommandResult = withContext(Dispatchers.IO) {
        stopCurrentProcess(updateStatus = true)
        CommandResult.success("Proxy stopped")
    }

    private fun stopCurrentProcess(updateStatus: Boolean) {
        stdoutJob?.cancel()
        stderrJob?.cancel()
        stdoutJob = null
        stderrJob = null
        process?.destroy()
        process = null
        if (updateStatus) status.value = "Proxy stopped"
    }

    override suspend fun resolveDeviceProxyHost(serial: String): String {
        val online = devices.listDevices().firstOrNull { it.serial == serial }
        return if (online?.kind == DeviceKind.Emulator || serial.startsWith("emulator-")) "10.0.2.2" else resolveLanIp()
    }

    override suspend fun configureDeviceProxy(serial: String, host: String, port: Int): CommandResult {
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        activatePersistedCertificateAuthority(adb, serial)
        val commands = listOf(
            listOf("settings", "put", "global", "http_proxy", "$host:$port"),
            listOf("settings", "put", "global", "global_http_proxy_host", host),
            listOf("settings", "put", "global", "global_http_proxy_port", port.toString()),
            listOf("settings", "delete", "global", "global_http_proxy_exclusion_list"),
            listOf("settings", "delete", "global", "global_proxy_pac_url"),
        )
        val proxyConfigured = runAdbShellSequence(adb, serial, commands, "Device proxy configured at $host:$port")
        if (!proxyConfigured.isSuccess) return proxyConfigured
        val restart = restartDeviceInternet(adb, serial)
        return CommandResult.success(
            listOf(proxyConfigured.stdout, restart.stdout.ifBlank { restart.stderr })
                .filter { it.isNotBlank() }
                .joinToString(". "),
        )
    }

    override suspend fun clearDeviceProxy(serial: String): CommandResult {
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        val commands = listOf(
            listOf("settings", "put", "global", "http_proxy", ":0"),
            listOf("settings", "delete", "global", "global_http_proxy_host"),
            listOf("settings", "delete", "global", "global_http_proxy_port"),
            listOf("settings", "delete", "global", "global_http_proxy_exclusion_list"),
            listOf("settings", "delete", "global", "global_proxy_pac_url"),
        )
        val proxyCleared = runAdbShellSequence(adb, serial, commands, "Device proxy cleared")
        if (!proxyCleared.isSuccess) return proxyCleared
        val restart = restartDeviceInternet(adb, serial)
        return CommandResult.success(
            listOf(proxyCleared.stdout, restart.stdout.ifBlank { restart.stderr })
                .filter { it.isNotBlank() }
                .joinToString(". "),
        )
    }

    override suspend fun diagnoseDeviceProxyRoute(serial: String, host: String, port: Int): NetworkRouteDiagnostics = withContext(Dispatchers.IO) {
        val adb = devices.adbPath()
            ?: return@withContext NetworkRouteDiagnostics(
                expectedProxy = "$host:$port",
                configuredProxy = null,
                proxyConfigured = false,
                vpnActive = false,
                issues = listOf("ADB not found"),
            )
        val expectedProxy = "$host:$port"
        val configuredProxy = readConfiguredProxy(adb, serial)
        val proxyConfigured = configuredProxy == expectedProxy
        val connectivity = runner.run(listOf(adb, "-s", serial, "shell", "dumpsys", "connectivity"), 10)
        val vpn = parseVpnRouteState(connectivity.stdout)
        val route = runner.run(listOf(adb, "-s", serial, "shell", "ip", "route", "get", host), 10)
        val routeSummary = route.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        val routeUsesVpn = routeSummary?.contains(Regex("""\b(tun|ppp|wg|vpn)\w*""", RegexOption.IGNORE_CASE)) == true
        val hostProxy = detectHostProxyState()
        val hostVpn = detectHostVpnState()
        val issues = buildList {
            if (!proxyConfigured) add("Android global proxy is ${configuredProxy ?: "not set"}; expected $expectedProxy.")
            if (vpn.active) add("A VPN is active${vpn.name?.let { " ($it)" }.orEmpty()}; it can bypass Android's global HTTP proxy or route Andy's proxy endpoint into the tunnel.")
            if (routeUsesVpn) add("The route to Andy's proxy host appears to use a VPN interface: $routeSummary")
            if (hostProxy.active && !hostProxy.bypassLooksSafe) add("Mac proxy bypass rules do not clearly include localhost/127.0.0.1/10.0.2.2; add them if emulator traffic still disappears.")
            if (hostVpn.active) add("Mac VPN-like interfaces are active (${hostVpn.summary}); emulator traffic may be routed by the host tunnel.")
        }
        NetworkRouteDiagnostics(
            expectedProxy = expectedProxy,
            configuredProxy = configuredProxy,
            proxyConfigured = proxyConfigured,
            vpnActive = vpn.active,
            vpnName = vpn.name,
            routeUsesVpn = routeUsesVpn,
            routeSummary = routeSummary,
            hostProxyActive = hostProxy.active,
            hostProxySummary = hostProxy.summary,
            hostUpstreamProxy = hostProxy.upstreamProxy,
            hostProxyBypassLooksSafe = hostProxy.bypassLooksSafe,
            hostVpnActive = hostVpn.active,
            hostVpnSummary = hostVpn.summary,
            issues = issues,
        )
    }

    override suspend fun openVpnSettings(serial: String): CommandResult {
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        return runner.run(listOf(adb, "-s", serial, "shell", "am", "start", "-a", "android.settings.VPN_SETTINGS"), 10)
    }

    override suspend fun prepareUserCertificateInstall(serial: String): CommandResult = withContext(Dispatchers.IO) {
        val adb = devices.adbPath() ?: return@withContext CommandResult.failure("ADB not found")
        val certificate = usableCertificateAuthority()
            ?: return@withContext CommandResult.failure(
                "Could not find a valid mitmproxy CA. Start the proxy once so mitmproxy can generate ${File(proxyDir, "mitmproxy-ca-cert.cer").absolutePath}.",
            )
        val remotePath = "/sdcard/Download/andy-mitmproxy-ca-cert.cer"
        val push = runner.run(listOf(adb, "-s", serial, "push", certificate.absolutePath, remotePath), 60)
        if (!push.isSuccess) return@withContext push

        val settings = runner.run(listOf(adb, "-s", serial, "shell", "am", "start", "-a", "android.settings.SECURITY_SETTINGS"), 10)
        val settingsNote = if (settings.isSuccess) {
            "Opened Security settings."
        } else {
            "Could not open Security settings automatically. ${settings.combinedOutput()}".trim()
        }
        CommandResult.success(
            "Copied Andy CA to $remotePath. $settingsNote On the device, install it from Settings > Security > Encryption & credentials > Install a certificate > CA certificate.",
        )
    }

    override suspend fun installSystemCertificateAuthority(serial: String): CommandResult = withContext(Dispatchers.IO) {
        val adb = devices.adbPath() ?: return@withContext CommandResult.failure("ADB not found")
        val certificate = usableCertificateAuthority()
        if (certificate == null) {
            return@withContext CommandResult.failure(
                "Could not find a valid mitmproxy CA. Start the proxy once so mitmproxy can generate ${File(proxyDir, "mitmproxy-ca-cert.cer").absolutePath}.",
            )
        }

        val root = runner.run(listOf(adb, "-s", serial, "root"), 30)
        val rootOutput = "${root.stdout}\n${root.stderr}".trim()
        if (!root.isSuccess || rootOutput.contains("cannot run as root", ignoreCase = true)) {
            return@withContext CommandResult.failure(
                "ADB root is not available for this device. Physical devices can only use Android's manual user-credential install unless they are rooted. For emulator system trust, use a non-Google-Play emulator image. $rootOutput".trim(),
            )
        }
        runner.run(listOf(adb, "-s", serial, "wait-for-device"), 60)

        val hash = certificateSubjectHash(certificate)
            ?: return@withContext CommandResult.failure("Could not compute Android CA subject hash for ${certificate.absolutePath}. Install OpenSSL or verify the mitmproxy CA file.")
        val androidCert = File(proxyDir, "$hash.0")
        certificate.copyTo(androidCert, overwrite = true)

        val persistentInstall = installPersistentCertificateAuthority(adb, serial, androidCert, certificate)
        if (persistentInstall.isSuccess) return@withContext persistentInstall

        val runtimeInjection = installRuntimeCertificateAuthority(adb, serial, androidCert, certificate, persistent = false)
        if (runtimeInjection.isSuccess) return@withContext runtimeInjection

        CommandResult.failure(
            "Could not install Andy CA persistently or inject it into the runtime trust store. " +
                "${persistentInstall.combinedOutput()} ${runtimeInjection.combinedOutput()}".trim(),
        )
    }

    override suspend fun activatePersistedCertificateAuthority(serial: String): CommandResult = withContext(Dispatchers.IO) {
        val adb = devices.adbPath() ?: return@withContext CommandResult.failure("ADB not found")
        activatePersistedCertificateAuthority(adb, serial)
    }

    override suspend fun isCertificateInstalled(serial: String): Boolean = withContext(Dispatchers.IO) {
        val adb = devices.adbPath() ?: return@withContext false
        val certificate = usableCertificateAuthority() ?: return@withContext false
        val hash = certificateSubjectHash(certificate) ?: return@withContext false
        val result = runner.run(
            listOf(adb, "-s", serial, "shell", "test -f /system/etc/security/cacerts/$hash.0 || test -f /apex/com.android.conscrypt/cacerts/$hash.0"),
            10
        )
        result.isSuccess
    }

    override suspend fun isDeviceProxyConfigured(serial: String, host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        val adb = devices.adbPath() ?: return@withContext false
        readConfiguredProxy(adb, serial) == "$host:$port"
    }

    private suspend fun readConfiguredProxy(adb: String, serial: String): String? {
        val result = runner.run(listOf(adb, "-s", serial, "shell", "settings", "get", "global", "http_proxy"), 10)
        if (!result.isSuccess) return null
        return result.stdout.trim().takeIf { it.isNotBlank() && it != "null" && it != ":0" }
    }

    private suspend fun detectHostProxyState(): HostProxyState {
        if (!isMacHost()) return HostProxyState(active = false)
        val result = runner.run(listOf("/usr/sbin/scutil", "--proxy"), 5)
        if (!result.isSuccess) return HostProxyState(active = false)
        return parseMacProxyState(result.stdout)
    }

    private suspend fun detectHostVpnState(): HostVpnState {
        if (!isMacHost()) return HostVpnState(active = false)
        val result = runner.run(listOf("/sbin/ifconfig"), 5)
        if (!result.isSuccess) return HostVpnState(active = false)
        val activeInterfaces = Regex("""^([a-z]+[0-9]+): flags=.*\bUP\b""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
            .findAll(result.stdout)
            .mapNotNull { match ->
                val name = match.groupValues.getOrNull(1).orEmpty()
                name.takeIf { it.startsWith("utun") || it.startsWith("ppp") || it.startsWith("ipsec") || it.startsWith("wg") }
            }
            .toList()
        return HostVpnState(
            active = activeInterfaces.isNotEmpty(),
            summary = activeInterfaces.distinct().joinToString(", ").takeIf { it.isNotBlank() },
        )
    }

    private fun isMacHost(): Boolean = hostOsName().contains("Mac", ignoreCase = true)

    private suspend fun installPersistentCertificateAuthority(adb: String, serial: String, androidCert: File, originalCertificate: File): CommandResult {
        val remount = remountWritableSystem(adb, serial)
        if (!remount.isSuccess) return remount

        val persistentDir = "/system/etc/andy/cacerts"
        val persistentCert = "$persistentDir/${androidCert.name}"
        val persistentScript = "/system/etc/andy/andy-ca-injector.sh"
        val initScript = "/system/etc/init/andy-ca.rc"
        val systemCert = "/system/etc/security/cacerts/${androidCert.name}"
        val localPersistentScript = File(proxyDir, "andy-persistent-ca-injector.sh")
        val localInitScript = File(proxyDir, "andy-ca.rc")

        localPersistentScript.writeText(androidCaInjectionScript(persistentCert))
        localInitScript.writeText(androidCaInitScript(persistentScript))

        val setupDir = runner.run(listOf(adb, "-s", serial, "shell", "mkdir", "-p", persistentDir), 30)
        if (!setupDir.isSuccess) return setupDir
        val pushPersistentCert = runner.run(listOf(adb, "-s", serial, "push", androidCert.absolutePath, persistentCert), 60)
        if (!pushPersistentCert.isSuccess) return pushPersistentCert
        val pushSystemCert = runner.run(listOf(adb, "-s", serial, "push", androidCert.absolutePath, systemCert), 60)
        if (!pushSystemCert.isSuccess) return pushSystemCert
        val pushScript = runner.run(listOf(adb, "-s", serial, "push", localPersistentScript.absolutePath, persistentScript), 60)
        if (!pushScript.isSuccess) return pushScript
        val pushInit = runner.run(listOf(adb, "-s", serial, "push", localInitScript.absolutePath, initScript), 60)
        if (!pushInit.isSuccess) return pushInit

        val permissions = runAdbShellSequence(
            adb,
            serial,
            listOf(
                listOf("chmod", "644", persistentCert),
                listOf("chmod", "644", systemCert),
                listOf("chmod", "755", persistentScript),
                listOf("chmod", "644", initScript),
                listOf("chown", "root:root", persistentCert, systemCert, persistentScript, initScript),
                listOf("chcon", "u:object_r:system_file:s0", persistentCert, systemCert, persistentScript, initScript),
            ),
            "Persistent CA files installed",
        )
        if (!permissions.isSuccess) return permissions

        val runtimeInjection = installRuntimeCertificateAuthority(adb, serial, androidCert, originalCertificate, persistent = true)
        if (!runtimeInjection.isSuccess) return runtimeInjection

        return CommandResult.success(
            "Installed Andy CA persistently on the writable emulator system image and activated it for this boot. " +
                "After emulator restart, Configure device will reactivate the persisted CA for Android's runtime trust namespace.",
        )
    }

    private suspend fun installRuntimeCertificateAuthority(adb: String, serial: String, androidCert: File, originalCertificate: File, persistent: Boolean): CommandResult {
        val remoteCert = if (persistent) "/system/etc/andy/cacerts/${androidCert.name}" else "/data/local/tmp/${androidCert.name}"
        val remoteInjectionScript = "/data/local/tmp/andy-inject-ca.sh"
        val remoteChromeScript = "/data/local/tmp/andy-chrome-proxy-flags.sh"
        val injectionScript = File(proxyDir, "andy-inject-ca.sh")
        val chromeScript = File(proxyDir, "andy-chrome-proxy-flags.sh")
        injectionScript.writeText(androidCaInjectionScript(remoteCert))
        val spkiFingerprint = certificateSpkiFingerprint(originalCertificate)
        chromeScript.writeText(chromeProxyFlagsScript(spkiFingerprint))

        if (!persistent) {
            val pushCert = runner.run(listOf(adb, "-s", serial, "push", androidCert.absolutePath, remoteCert), 60)
            if (!pushCert.isSuccess) return pushCert
        }
        val pushInjectionScript = runner.run(listOf(adb, "-s", serial, "push", injectionScript.absolutePath, remoteInjectionScript), 60)
        if (!pushInjectionScript.isSuccess) return pushInjectionScript
        val runInjection = runner.run(listOf(adb, "-s", serial, "shell", "sh", remoteInjectionScript), 60)
        if (!runInjection.isSuccess) return CommandResult.failure("Runtime CA injection failed. ${runInjection.combinedOutput()}")

        if (spkiFingerprint != null) {
            val pushChromeScript = runner.run(listOf(adb, "-s", serial, "push", chromeScript.absolutePath, remoteChromeScript), 60)
            if (!pushChromeScript.isSuccess) return pushChromeScript
            val runChromeScript = runner.run(listOf(adb, "-s", serial, "shell", "sh", remoteChromeScript), 30)
            if (!runChromeScript.isSuccess) return CommandResult.failure("Installed runtime CA, but Chrome/WebView proxy flags failed. ${runChromeScript.combinedOutput()}")
            runner.run(listOf(adb, "-s", serial, "shell", "am", "force-stop", "com.android.chrome"), 10)
        }

        return CommandResult.success(
            "Injected Andy CA into the emulator runtime trust store. This avoids a writable-system remount and lasts until emulator reboot. " +
                if (spkiFingerprint != null) {
                    "Chrome/WebView SPKI flags were applied; reopen Chrome and restart the debug app to pick up the CA."
                } else {
                    "Could not compute Chrome/WebView SPKI flags, so Chrome may still need app-side CA trust or a restart."
                },
        )
    }

    private suspend fun activatePersistedCertificateAuthority(adb: String, serial: String): CommandResult {
        val marker = runner.run(listOf(adb, "-s", serial, "shell", "ls", "/system/etc/andy/cacerts/*.0"), 10)
        if (!marker.isSuccess) return CommandResult.success()
        val root = runner.run(listOf(adb, "-s", serial, "root"), 30)
        if (!root.isSuccess || root.combinedOutput().contains("cannot run as root", ignoreCase = true)) return CommandResult.failure(root.combinedOutput())
        runner.run(listOf(adb, "-s", serial, "wait-for-device"), 60)
        val script = "/system/etc/andy/andy-ca-injector.sh"
        val scriptExists = runner.run(listOf(adb, "-s", serial, "shell", "test", "-f", script), 10)
        if (!scriptExists.isSuccess) return CommandResult.failure("Persisted Andy CA marker exists, but $script is missing.")
        val activation = runner.run(listOf(adb, "-s", serial, "shell", "sh", script), 60)
        return if (activation.isSuccess) CommandResult.success("Activated persisted Andy CA for this emulator boot.") else activation
    }

    private fun androidCaInjectionScript(remoteCert: String): String = """
        |#!/system/bin/sh
        |set -u
        |
        |CERT_FILE="$remoteCert"
        |TMP_COPY="/data/local/tmp/andy-ca-copy"
        |TARGET="/system/etc/security/cacerts"
        |
        |if [ -d "/apex/com.android.conscrypt/cacerts" ]; then
        |    CERT_SOURCE="/apex/com.android.conscrypt/cacerts"
        |elif [ -d "/system/etc/security/cacerts" ]; then
        |    CERT_SOURCE="/system/etc/security/cacerts"
        |elif [ -d "/system/etc/certificates" ]; then
        |    CERT_SOURCE="/system/etc/certificates"
        |elif [ -d "/etc/security/cacerts" ]; then
        |    CERT_SOURCE="/etc/security/cacerts"
        |elif [ -d "/data/misc/keychain/cacerts-added" ]; then
        |    CERT_SOURCE="/data/misc/keychain/cacerts-added"
        |elif [ -d "/system/ca-certificates/files" ]; then
        |    CERT_SOURCE="/system/ca-certificates/files"
        |else
        |    echo "Could not find Android certificate directory"
        |    exit 1
        |fi
        |
        |if [ ! -f "${'$'}CERT_FILE" ]; then
        |    echo "Missing certificate at ${'$'}CERT_FILE"
        |    exit 1
        |fi
        |
        |rm -rf "${'$'}TMP_COPY"
        |mkdir -p -m 700 "${'$'}TMP_COPY"
        |cp "${'$'}CERT_SOURCE"/* "${'$'}TMP_COPY"/ 2>/dev/null || true
        |mkdir -p "${'$'}TARGET"
        |mount -t tmpfs tmpfs "${'$'}TARGET" 2>/dev/null || true
        |cp "${'$'}TMP_COPY"/* "${'$'}TARGET"/ 2>/dev/null || true
        |cp "${'$'}CERT_FILE" "${'$'}TARGET"/
        |chown root:root "${'$'}TARGET"/* 2>/dev/null || true
        |chmod 644 "${'$'}TARGET"/* 2>/dev/null || true
        |chcon u:object_r:system_file:s0 "${'$'}TARGET"/* 2>/dev/null || true
        |
        |ZYGOTE_PIDS="$(pidof zygote zygote64 2>/dev/null || true)"
        |for Z_PID in ${'$'}ZYGOTE_PIDS; do
        |    nsenter --mount=/proc/${'$'}Z_PID/ns/mnt -- /bin/mount --bind "${'$'}TARGET" "${'$'}CERT_SOURCE" 2>/dev/null || true
        |done
        |
        |APP_PIDS="$(
        |    for Z_PID in ${'$'}ZYGOTE_PIDS; do
        |        ps -o PID -P "${'$'}Z_PID" 2>/dev/null | grep -v PID || true
        |    done
        |)"
        |for PID in ${'$'}APP_PIDS; do
        |    nsenter --mount=/proc/${'$'}PID/ns/mnt -- /bin/mount --bind "${'$'}TARGET" "${'$'}CERT_SOURCE" 2>/dev/null &
        |done
        |wait
        |
        |echo "Injected CA into ${'$'}CERT_SOURCE"
        |""".trimMargin()

    private fun androidCaInitScript(persistentScript: String): String = """
        |service andy_ca_injector /system/bin/sh $persistentScript
        |    class late_start
        |    user root
        |    group root
        |    oneshot
        |    disabled
        |
        |on property:sys.boot_completed=1
        |    start andy_ca_injector
        |""".trimMargin()

    private fun chromeProxyFlagsScript(spkiFingerprint: String?): String {
        val flags = if (spkiFingerprint == null) {
            "chrome"
        } else {
            "chrome --ignore-certificate-errors-spki-list=$spkiFingerprint"
        }
        return """
            |#!/system/bin/sh
            |set -u
            |FLAGS=${shellQuote(flags)}
            |
            |for variant in chrome android-webview webview content-shell; do
            |    for base_path in /data/local /data/local/tmp; do
            |        FLAGS_PATH="${'$'}base_path/${'$'}variant-command-line"
            |        echo "${'$'}FLAGS" > "${'$'}FLAGS_PATH"
            |        chmod 744 "${'$'}FLAGS_PATH" 2>/dev/null || true
            |        chcon "u:object_r:shell_data_file:s0" "${'$'}FLAGS_PATH" 2>/dev/null || true
            |    done
            |done
            |""".trimMargin()
    }

    private suspend fun remountWritableSystem(adb: String, serial: String): CommandResult {
        val first = runner.run(listOf(adb, "-s", serial, "remount"), 60)
        if (first.isSuccess) return first
        val firstOutput = first.combinedOutput()
        if (firstOutput.contains("bootloader", ignoreCase = true) && firstOutput.contains("unlock", ignoreCase = true)) {
            return CommandResult.failure(
                "Could not remount emulator system partition because the emulator bootloader is locked. " +
                    "Use a non-Google-Play emulator with an unlocked bootloader and writable system image. " +
                    "Andy will not auto-unlock because unlocking can wipe emulator data. A manual path is: " +
                    "`adb -s $serial reboot bootloader`, `fastboot flashing unlock`, then restart the AVD with `-writable-system` and retry. " +
                    firstOutput,
            )
        }
        if (!firstOutput.contains("verity", ignoreCase = true)) {
            return CommandResult.failure("Could not remount emulator system partition. Use a writable non-Google-Play emulator image. $firstOutput")
        }

        val disableVerity = runner.run(listOf(adb, "-s", serial, "disable-verity"), 60)
        if (!disableVerity.isSuccess) {
            return CommandResult.failure("Could not disable Android verified boot for remount. ${disableVerity.combinedOutput()}")
        }
        runner.run(listOf(adb, "-s", serial, "reboot"), 15)
        runner.run(listOf(adb, "-s", serial, "wait-for-device"), 120)
        val root = runner.run(listOf(adb, "-s", serial, "root"), 30)
        if (!root.isSuccess) {
            return CommandResult.failure("ADB root was unavailable after disabling verity. ${root.combinedOutput()}")
        }
        runner.run(listOf(adb, "-s", serial, "wait-for-device"), 60)
        val second = runner.run(listOf(adb, "-s", serial, "remount"), 60)
        return if (second.isSuccess) {
            second
        } else {
            CommandResult.failure("Could not remount emulator system partition after disabling verity. ${second.combinedOutput()}")
        }
    }

    private fun CommandResult.combinedOutput(): String =
        listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n").trim()

    private suspend fun usableCertificateAuthority(): File? =
        listOf(
            File(proxyDir, "mitmproxy-ca-cert.cer"),
            File(proxyDir, "mitmproxy-ca-cert.pem"),
        ).firstOrNull { file ->
            file.exists() && certificateSubjectHash(file) != null
        }

    private suspend fun runAdbShellSequence(adb: String, serial: String, shellCommands: List<List<String>>, successMessage: String): CommandResult {
        shellCommands.forEach { shellCommand ->
            val result = runner.run(listOf(adb, "-s", serial, "shell") + shellCommand)
            if (!result.isSuccess) return result
        }
        return CommandResult.success(successMessage)
    }

    private suspend fun restartDeviceInternet(adb: String, serial: String): CommandResult {
        val wifiWasEnabled = readWifiEnabled(adb, serial) != false
        val disableWifi = runner.run(listOf(adb, "-s", serial, "shell", "cmd", "wifi", "set-wifi-enabled", "disabled"), 10)
            .takeIf { it.isSuccess }
            ?: runner.run(listOf(adb, "-s", serial, "shell", "svc", "wifi", "disable"), 10)
        val wifiDisabled = waitForWifiEnabled(adb, serial, enabled = false, attempts = 8)

        val enableWifi = runner.run(listOf(adb, "-s", serial, "shell", "cmd", "wifi", "set-wifi-enabled", "enabled"), 10)
            .takeIf { it.isSuccess }
            ?: runner.run(listOf(adb, "-s", serial, "shell", "svc", "wifi", "enable"), 10)
        val wifiEnabled = waitForWifiEnabled(adb, serial, enabled = true, attempts = 12)
        runner.run(listOf(adb, "-s", serial, "shell", "cmd", "wifi", "reconnect"), 10)
        runner.run(listOf(adb, "-s", serial, "shell", "cmd", "wifi", "start-scan"), 10)

        runner.run(listOf(adb, "-s", serial, "shell", "svc", "data", "disable"), 10)
        delay(1000)
        runner.run(listOf(adb, "-s", serial, "shell", "svc", "data", "enable"), 10)

        val wifiMessage = when {
            !disableWifi.isSuccess -> "Wi-Fi restart was requested, but Android rejected the disable command: ${disableWifi.combinedOutput()}"
            !enableWifi.isSuccess -> "Wi-Fi restart was requested, but Android rejected the enable command: ${enableWifi.combinedOutput()}"
            wifiDisabled && wifiEnabled -> "Device Wi-Fi restarted and mobile data bounced"
            !wifiWasEnabled && wifiEnabled -> "Device Wi-Fi was off; Andy enabled it and bounced mobile data"
            else -> "Device mobile data bounced, but Android did not report a Wi-Fi off/on transition"
        }
        return CommandResult.success(wifiMessage)
    }

    private suspend fun waitForWifiEnabled(adb: String, serial: String, enabled: Boolean, attempts: Int): Boolean {
        repeat(attempts) {
            if (readWifiEnabled(adb, serial) == enabled) return true
            delay(500)
        }
        return false
    }

    private suspend fun readWifiEnabled(adb: String, serial: String): Boolean? {
        val status = runner.run(listOf(adb, "-s", serial, "shell", "cmd", "wifi", "status"), 10)
        val output = if (status.isSuccess) {
            status.stdout.lowercase()
        } else {
            val fallback = runner.run(listOf(adb, "-s", serial, "shell", "settings", "get", "global", "wifi_on"), 10)
            if (!fallback.isSuccess) return null
            fallback.stdout.trim()
        }
        return when {
            output.contains("wifi is enabled") || output.contains("wi-fi is enabled") || output == "1" -> true
            output.contains("wifi is disabled") || output.contains("wi-fi is disabled") || output == "0" -> false
            else -> null
        }
    }

    private fun pumpProcess(proxyProcess: ProxyProcess) {
        stdoutJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            proxyProcess.stdout.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    parseMitmproxyFlowLine(line)?.let { exchange ->
                        val current = exchanges.value
                        val index = current.indexOfFirst { it.id == exchange.id }
                        if (index >= 0) {
                            val mutable = current.toMutableList()
                            mutable[index] = exchange
                            exchanges.value = mutable
                        } else {
                            exchanges.value = (current + exchange).takeLast(MaxNetworkExchanges)
                        }
                    }
                }
            }
            if (process === proxyProcess && !proxyProcess.isAlive()) status.value = "mitmdump exited"
        }
        stderrJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            proxyProcess.stderr.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    System.err.println("[Proxy stderr] $line")
                    if (process === proxyProcess && line.isNotBlank() && !proxyProcess.isAlive()) status.value = line.take(220)
                }
            }
        }
    }

    private fun writeRules(rules: List<ProxyRule>) {
        rulesFile.writeText(ProxyRuleJson.writeRules(rules))
    }

    private suspend fun killOrphanedProxies() {
        val os = System.getProperty("os.name").lowercase()
        if (os.contains("windows")) {
            runner.run(listOf("taskkill", "/F", "/IM", "mitmdump.exe"), 5)
        } else {
            val psResult = runner.run(listOf("ps", "ax", "-o", "pid,command"), 10)
            if (psResult.isSuccess) {
                psResult.stdout.lineSequence()
                    .map { it.trim() }
                    .filter { it.contains("mitmdump") && it.contains("andy_mitm_addon.py") }
                    .forEach { line ->
                        val pid = line.substringBefore(' ').trim().toIntOrNull()
                        if (pid != null) {
                            runner.run(listOf("kill", "-9", pid.toString()), 5)
                        }
                    }
            }
        }
    }

    private fun writeAddon() {
        val bundled = javaClass.classLoader.getResourceAsStream("proxy/andy_mitm_addon.py")
        if (bundled != null) {
            bundled.use { input -> addonFile.outputStream().use { output -> input.copyTo(output) } }
        } else if (!addonFile.exists()) {
            addonFile.writeText(AndyMitmAddonSource)
        }
    }
}

interface ProxyProcess {
    val stdout: InputStream
    val stderr: InputStream
    fun isAlive(): Boolean
    fun destroy()
}

class RealProxyProcess(command: List<String>, directory: File, environment: Map<String, String>) : ProxyProcess {
    private val delegate = ProcessBuilder(command)
        .directory(directory)
        .redirectErrorStream(false)
        .also { builder -> builder.environment().putAll(environment) }
        .start()

    private val shutdownHook = Thread {
        try {
            if (delegate.isAlive) {
                delegate.destroy()
                if (!delegate.waitFor(500, TimeUnit.MILLISECONDS)) {
                    delegate.destroyForcibly()
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    override val stdout: InputStream get() = delegate.inputStream
    override val stderr: InputStream get() = delegate.errorStream
    override fun isAlive(): Boolean = delegate.isAlive
    override fun destroy() {
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        delegate.destroy()
        if (!delegate.waitFor(800, TimeUnit.MILLISECONDS)) delegate.destroyForcibly()
    }
}

private const val MaxNetworkExchanges = 20_000

internal fun findMitmdumpExecutable(): String? {
    val pathCandidates = System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { File(it, "mitmdump") }
    return (pathCandidates + listOf(File("/opt/homebrew/bin/mitmdump"), File("/usr/local/bin/mitmdump")))
        .firstOrNull { it.exists() && it.canExecute() }
        ?.absolutePath
}

private suspend fun defaultCertificateSubjectHash(runner: CommandRunner, certificate: File): String? {
    return listOf("PEM", "DER").firstNotNullOfOrNull { format ->
        val result = runner.run(
            listOf("openssl", "x509", "-inform", format, "-subject_hash_old", "-in", certificate.absolutePath, "-noout"),
            10,
        )
        if (!result.isSuccess) {
            null
        } else {
            result.stdout.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.matches(Regex("[0-9a-fA-F]{8}")) }
                ?.lowercase()
        }
    }
}

private suspend fun defaultCertificateSpkiFingerprint(runner: CommandRunner, certificate: File): String? {
    val command = "openssl x509 -in ${shellQuote(certificate.absolutePath)} -pubkey -noout | " +
        "openssl pkey -pubin -outform der | " +
        "openssl dgst -sha256 -binary | " +
        "base64"
    val result = runner.run(listOf("/bin/sh", "-c", command), 10)
    return if (result.isSuccess) result.stdout.trim().takeIf { it.isNotBlank() } else null
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

internal fun resolveLanIp(): String {
    return NetworkInterface.getNetworkInterfaces().toList().asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.toList().asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && !it.hostAddress.startsWith("169.254.") }
        ?.hostAddress
        ?: "127.0.0.1"
}

internal object ProxyRuleJson {
    fun writeRules(rules: List<ProxyRule>): String {
        return rules.joinToString(prefix = "{\"rules\":[", postfix = "]}\n") { rule ->
            buildString {
                append("{")
                appendJsonField("id", rule.id)
                append(",")
                appendJsonField("name", rule.name)
                append(",\"enabled\":${rule.enabled}")
                append(",")
                appendJsonField("urlPattern", rule.urlPattern)
                append(",\"method\":")
                append(rule.method?.let(::quoteJson) ?: "null")
                append(",\"statusCode\":")
                append(rule.statusCode?.toString() ?: "null")
                append(",\"setHeaders\":{")
                append(rule.setHeaders.entries.joinToString(",") { "${quoteJson(it.key)}:${quoteJson(it.value)}" })
                append("},\"removeHeaders\":[")
                append(rule.removeHeaders.joinToString(",") { quoteJson(it) })
                append("],\"responseBody\":")
                append(rule.responseBody?.let(::quoteJson) ?: "null")
                append("}")
            }
        }
    }

    private fun StringBuilder.appendJsonField(name: String, value: String) {
        append(quoteJson(name))
        append(":")
        append(quoteJson(value))
    }
}

internal fun parseMitmproxyFlowLine(line: String): NetworkExchange? {
    if (!line.trimStart().startsWith("{") || jsonString(line, "type") != "flow") return null
    val id = jsonString(line, "id") ?: return null
    val requestHeaders = jsonObject(line, "requestHeaders")
    val responseHeaders = jsonObject(line, "responseHeaders")
    val started = jsonLong(line, "startedAtMillis") ?: System.currentTimeMillis()
    val completed = jsonLong(line, "completedAtMillis")
    return NetworkExchange(
        id = id,
        flowId = id,
        startedAtMillis = started,
        completedAtMillis = completed,
        method = jsonString(line, "method") ?: "-",
        url = jsonString(line, "url") ?: "-",
        statusCode = jsonInt(line, "statusCode"),
        contentType = jsonString(line, "contentType"),
        sizeBytes = jsonLong(line, "sizeBytes"),
        durationMillis = jsonLong(line, "durationMillis"),
        requestHeaders = requestHeaders,
        responseHeaders = responseHeaders,
        requestBodyPreview = jsonNullableString(line, "requestBodyPreview"),
        responseBodyPreview = jsonNullableString(line, "responseBodyPreview"),
        error = jsonNullableString(line, "error"),
        tlsStatus = jsonNullableString(line, "tlsStatus"),
        matchedRuleId = jsonNullableString(line, "matchedRuleId"),
    )
}

internal data class VpnRouteState(val active: Boolean, val name: String?)

internal fun parseVpnRouteState(dumpsysConnectivity: String): VpnRouteState {
    val lines = dumpsysConnectivity.lines()
    val vpnLine = lines.firstOrNull { line ->
        line.contains("TRANSPORT_VPN") ||
            line.contains("type: VPN", ignoreCase = true) ||
            (line.contains("VPN") && line.contains("CONNECTED", ignoreCase = true))
    } ?: return VpnRouteState(active = false, name = null)
    val ownerLine = lines.firstOrNull { line ->
        line.contains("mOwnerName=", ignoreCase = true) ||
            line.contains("owner=", ignoreCase = true) ||
            (line.contains("vpn", ignoreCase = true) && line.contains("package", ignoreCase = true))
    }
    val name = ownerLine
        ?.let { Regex("""(?:mOwnerName|owner|packageName|package)=([A-Za-z0-9_.-]+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1) }
        ?: Regex("""\b([a-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_-]+){2,})\b""").find(vpnLine)?.groupValues?.getOrNull(1)
    return VpnRouteState(active = true, name = name)
}

internal data class HostProxyState(
    val active: Boolean,
    val summary: String? = null,
    val upstreamProxy: String? = null,
    val bypassLooksSafe: Boolean = true,
)

private data class HostVpnState(val active: Boolean, val summary: String? = null)

internal fun parseMacProxyState(scutilProxy: String): HostProxyState {
    val entries = scutilProxy.lineSequence()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (":" !in trimmed) return@mapNotNull null
            trimmed.substringBefore(":").trim() to trimmed.substringAfter(":").trim()
        }
        .toMap()
    val http = proxyEndpoint(entries, "HTTP")
    val https = proxyEndpoint(entries, "HTTPS")
    val socks = proxyEndpoint(entries, "SOCKS")
    val upstream = https ?: http ?: socks
    val activeParts = listOfNotNull(
        http?.let { "HTTP $it" },
        https?.let { "HTTPS $it" },
        socks?.let { "SOCKS $it" },
        entries["ProxyAutoConfigEnable"]?.takeIf { it == "1" }?.let { entries["ProxyAutoConfigURLString"]?.let { url -> "PAC $url" } ?: "PAC" },
    )
    val active = activeParts.isNotEmpty()
    val exceptions = scutilProxy.lineSequence()
        .map { it.trim() }
        .filter { Regex("""^\d+\s*:""").containsMatchIn(it) }
        .map { it.substringAfter(":").trim() }
        .toList()
    val simpleHostsExcluded = entries["ExcludeSimpleHostnames"] == "1"
    val bypassLooksSafe = !active || simpleHostsExcluded || listOf("localhost", "127.0.0.1", "10.0.2.2").all { expected ->
        exceptions.any { exception -> proxyExceptionCovers(exception, expected) }
    }
    return HostProxyState(
        active = active,
        summary = activeParts.joinToString(", ").takeIf { it.isNotBlank() },
        upstreamProxy = upstream,
        bypassLooksSafe = bypassLooksSafe,
    )
}

private fun proxyEndpoint(entries: Map<String, String>, prefix: String): String? {
    if (entries["${prefix}Enable"] != "1") return null
    val host = entries["${prefix}Proxy"]?.takeIf { it.isNotBlank() } ?: return null
    val port = entries["${prefix}Port"]?.takeIf { it.isNotBlank() } ?: return null
    val scheme = if (prefix == "SOCKS") "socks5" else "http"
    return "$scheme://$host:$port"
}

private fun proxyExceptionCovers(exception: String, expected: String): Boolean {
    val normalized = exception.trim().lowercase()
    val target = expected.lowercase()
    return normalized == target ||
        normalized == "<local>" && target == "localhost" ||
        normalized == "*.local" && target.endsWith(".local") ||
        normalized.endsWith(".*") && target.startsWith(normalized.removeSuffix(".*")) ||
        normalized.endsWith("/8") && normalized.substringBefore("/") == "10.0.0.0" && target.startsWith("10.") ||
        normalized.endsWith("/16") && target.startsWith(normalized.substringBeforeLast('.').removeSuffix(".0")) ||
        normalized.endsWith("/24") && target.startsWith(normalized.substringBeforeLast('.'))
}

private fun quoteJson(value: String): String {
    return buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }
}

private fun jsonString(source: String, key: String): String? = jsonNullableString(source, key)

private fun jsonNullableString(source: String, key: String): String? {
    val start = Regex(""""${Regex.escape(key)}"\s*:\s*""").find(source)?.range?.last?.plus(1) ?: return null
    val trimmed = source.substring(start).trimStart()
    if (trimmed.startsWith("null")) return null
    if (!trimmed.startsWith("\"")) return null
    val builder = StringBuilder()
    var escape = false
    for (char in trimmed.drop(1)) {
        if (escape) {
            builder.append(
                when (char) {
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    else -> char
                },
            )
            escape = false
        } else if (char == '\\') {
            escape = true
        } else if (char == '"') {
            return builder.toString()
        } else {
            builder.append(char)
        }
    }
    return null
}

private fun jsonInt(source: String, key: String): Int? = Regex(""""${Regex.escape(key)}"\s*:\s*(-?\d+)""")
    .find(source)
    ?.groupValues
    ?.getOrNull(1)
    ?.toIntOrNull()

private fun jsonLong(source: String, key: String): Long? = Regex(""""${Regex.escape(key)}"\s*:\s*(-?\d+)""")
    .find(source)
    ?.groupValues
    ?.getOrNull(1)
    ?.toLongOrNull()

private fun jsonObject(source: String, key: String): Map<String, String> {
    val start = Regex(""""${Regex.escape(key)}"\s*:\s*\{""").find(source)?.range?.last ?: return emptyMap()
    var depth = 0
    var end = -1
    for (index in start until source.length) {
        when (source[index]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) {
                    end = index
                    break
                }
            }
        }
    }
    if (end < 0) return emptyMap()
    val body = source.substring(start + 1, end)
    return Regex(""""((?:\\.|[^"\\])*)"\s*:\s*"((?:\\.|[^"\\])*)"""")
        .findAll(body)
        .associate { match -> unescapeJson(match.groupValues[1]) to unescapeJson(match.groupValues[2]) }
}

private fun unescapeJson(value: String): String {
    val builder = StringBuilder()
    var escape = false
    for (char in value) {
        if (escape) {
            builder.append(
                when (char) {
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    else -> char
                },
            )
            escape = false
        } else if (char == '\\') {
            escape = true
        } else {
            builder.append(char)
        }
    }
    return builder.toString()
}

/** Fallback when the classpath resource is missing. Phase 0 golden-tests the evaluated string. */
internal val AndyMitmAddonSource = """
import json
import os
import time
from mitmproxy import http

RULES_PATH = os.environ.get("ANDY_RULES_PATH")
PREVIEW_LIMIT = 4096

def _load_rules():
    if not RULES_PATH or not os.path.exists(RULES_PATH):
        return []
    try:
        with open(RULES_PATH, "r", encoding="utf-8") as handle:
            return json.load(handle).get("rules", [])
    except Exception:
        return []

def _preview(content):
    if not content:
        return None
    data = content[:PREVIEW_LIMIT]
    try:
        return data.decode("utf-8", errors="replace")
    except Exception:
        return repr(data)

def _message_preview(message):
    if not message or not message.raw_content:
        return None
    try:
        text = message.get_text(strict=False)
        if text is not None:
            return text[:PREVIEW_LIMIT]
    except Exception:
        pass
    return _preview(message.raw_content)

def _headers(headers):
    return {key: value for key, value in headers.items()}

def _remove_header(headers, target):
    target_lower = target.lower()
    for name in list(headers.keys()):
        if name.lower() == target_lower:
            headers.pop(name, None)

def _match(rule, flow):
    if not rule.get("enabled", True):
        return False
    pattern = (rule.get("urlPattern") or "").lower()
    if pattern and pattern not in flow.request.pretty_url.lower():
        return False
    method = rule.get("method")
    if method and method.upper() != flow.request.method.upper():
        return False
    return True

def request(flow: http.HTTPFlow):
    flow.metadata["andy_started_at"] = int(time.time() * 1000)
    _emit(flow, is_request=True)

def response(flow: http.HTTPFlow):
    matched_rule_id = None
    for rule in _load_rules():
        if not _match(rule, flow):
            continue
        matched_rule_id = rule.get("id")
        if rule.get("statusCode") is not None:
            flow.response.status_code = int(rule["statusCode"])
        for header, value in (rule.get("setHeaders") or {}).items():
            flow.response.headers[header] = value
        for header in rule.get("removeHeaders") or []:
            _remove_header(flow.response.headers, header)
        if rule.get("responseBody") is not None:
            flow.response.headers.pop("content-encoding", None)
            flow.response.headers.pop("content-length", None)
            flow.response.text = rule["responseBody"]
        break
    _emit(flow, matched_rule_id, None)

def error(flow: http.HTTPFlow):
    _emit(flow, None, str(flow.error) if flow.error else "proxy error")

def _emit(flow, matched_rule_id=None, error=None, is_request=False):
    response = flow.response if (hasattr(flow, 'response') and flow.response) else None
    started = flow.metadata.get("andy_started_at")
    if started is None:
        started = int(time.time() * 1000)
        flow.metadata["andy_started_at"] = started

    if is_request:
        completed = None
        duration = None
    else:
        completed = int(time.time() * 1000)
        duration = max(0, completed - started)

    payload = {
        "type": "flow",
        "id": flow.id,
        "startedAtMillis": started,
        "completedAtMillis": completed,
        "durationMillis": duration,
        "method": flow.request.method,
        "url": flow.request.pretty_url,
        "statusCode": response.status_code if response else None,
        "contentType": response.headers.get("content-type") if response else None,
        "sizeBytes": len(response.raw_content or b"") if response else None,
        "requestHeaders": _headers(flow.request.headers),
        "responseHeaders": _headers(response.headers) if response else {},
        "requestBodyPreview": _message_preview(flow.request),
        "responseBodyPreview": _message_preview(response) if response else None,
        "error": error,
        "tlsStatus": "tls" if flow.request.scheme == "https" else "plain",
        "matchedRuleId": matched_rule_id,
    }
    print(json.dumps(payload, separators=(",", ":")), flush=True)
""".trimIndent()

