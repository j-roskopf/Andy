package app.andy.desktop.service.mirror

import app.andy.desktop.service.CommandRunner
import app.andy.desktop.service.DesktopDeviceService
import app.andy.desktop.service.emulator.EMULATOR_IMAGE_BYTES_PER_PIXEL
import app.andy.desktop.service.emulator.EmulatorGrpcClient
import app.andy.desktop.service.emulator.EmulatorMappedFramebuffer
import app.andy.desktop.service.emulator.emulatorConsolePort
import app.andy.desktop.service.emulator.isEmulatorSerial
import app.andy.desktop.service.emulator.readEmulatorGrpcToken
import app.andy.model.DeviceConnectionState
import app.andy.service.CommandResult
import app.andy.service.MirrorEngine
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.service.MirrorTouchAction
import app.andy.service.MirrorVideoConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

