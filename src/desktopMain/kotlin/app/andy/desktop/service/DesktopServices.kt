package app.andy.desktop.service

import app.andy.desktop.parser.AndroidParsers
import app.andy.model.*
import app.andy.service.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
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
import java.io.InputStream
import java.net.Socket
import java.net.ServerSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.random.Random

fun createDesktopServices(): AndyServices {
    val runner = CommandRunner()
    val locator = SdkLocator()
    val store = DesktopWorkspaceStore()
    val devices = DesktopDeviceService(runner, locator, store)
    return AndyServices(
        devices = devices,
        avd = DesktopAvdService(runner, locator) { store.load().selectedSdkPath },
        mirror = DesktopMirrorEngine(runner, devices),
        logcat = DesktopLogcatService(runner, devices),
        intents = DesktopIntentService(runner, devices),
        apps = DesktopAppService(runner, devices),
        files = DesktopFileService(runner, devices),
        proxy = DesktopProxyService(runner, devices),
        metrics = DesktopMetricsService(runner, devices),
        accessibility = DesktopAccessibilityService(runner, devices),
        workspaceStore = store,
    )
}

class DesktopDeviceService(
    private val runner: CommandRunner,
    private val locator: SdkLocator,
    private val store: WorkspaceStore,
) : DeviceService {
    override suspend fun discoverSdk(): SdkDiscovery {
        return locator.discover(store.load().selectedSdkPath)
    }

    override suspend fun listDevices(): List<AndroidDevice> {
        val sdk = discoverSdk()
        val adb = sdk.adbPath ?: return emptyList()
        val result = runner.run(listOf(adb, "devices", "-l"))
        if (!result.isSuccess) return emptyList()
        return AndroidParsers.parseAdbDevices(result.stdout).map { base ->
            if (base.state != DeviceConnectionState.Online) return@map base
            val props = getProps(adb, base.serial)
            val avdName = props["ro.boot.qemu.avd_name"] ?: props["ro.kernel.qemu.avd_name"]
            base.copy(
                displayName = if (base.kind == DeviceKind.Emulator) avdName ?: props["ro.product.model"] ?: base.displayName else props["ro.product.model"] ?: base.displayName,
                apiLevel = props["ro.build.version.sdk"],
                abi = props["ro.product.cpu.abi"],
                model = props["ro.product.model"] ?: base.model,
                product = props["ro.product.name"] ?: base.product,
                batteryPercent = AndroidParsers.parseBatteryPercent(runner.run(listOf(adb, "-s", base.serial, "shell", "dumpsys", "battery"), 6).stdout),
                screenSize = AndroidParsers.parseWmSize(runner.run(listOf(adb, "-s", base.serial, "shell", "wm", "size"), 6).stdout),
                storageSummary = AndroidParsers.parseStorage(runner.run(listOf(adb, "-s", base.serial, "shell", "df", "-h", "/data"), 6).stdout),
            )
        }
    }

    override suspend fun shell(serial: String, command: List<String>): CommandResult {
        val adb = discoverSdk().adbPath ?: return CommandResult.failure("ADB not found")
        return runner.run(listOf(adb, "-s", serial, "shell") + command)
    }

    suspend fun adbPath(): String? = discoverSdk().adbPath

    private suspend fun getProps(adb: String, serial: String): Map<String, String> {
        val result = runner.run(listOf(adb, "-s", serial, "shell", "getprop"), 8)
        return result.stdout.lineSequence().mapNotNull { line ->
            val match = Regex("""\[(.+)]\:\s+\[(.*)]""").find(line) ?: return@mapNotNull null
            match.groupValues[1] to match.groupValues[2]
        }.toMap()
    }
}

class DesktopAvdService(
    private val runner: CommandRunner,
    private val locator: SdkLocator,
    private val preferredSdkPath: suspend () -> String? = { null },
) : AvdService {
    override suspend fun listSystemImages(): List<SystemImage> {
        val sdk = locator.discover(preferredSdkPath())
        val sdkManager = sdk.sdkManagerPath ?: return emptyList()
        val installed = runner.run(listOf(sdkManager, "--list_installed"), 30).stdout
        val available = runner.run(listOf(sdkManager, "--list"), 45).stdout
        return (AndroidParsers.parseSystemImages(installed).map { it.copy(installed = true) } + AndroidParsers.parseSystemImages(available))
            .distinctBy { it.packageId }
    }

    override suspend fun listProfiles(): List<AvdProfile> {
        val avdManager = locator.discover(preferredSdkPath()).avdManagerPath ?: return emptyList()
        return AndroidParsers.parseProfiles(runner.run(listOf(avdManager, "list", "device"), 20).stdout)
    }

    override suspend fun listVirtualDevices(): List<VirtualDevice> {
        val avdManager = locator.discover(preferredSdkPath()).avdManagerPath ?: return emptyList()
        val avds = AndroidParsers.parseAvdList(runner.run(listOf(avdManager, "list", "avd"), 20).stdout)
        val running = runningEmulatorNames()
        return avds.map { it.copy(running = it.name in running) }
    }

    override suspend fun createVirtualDevice(name: String, profileId: String, systemImagePackage: String): CommandResult {
        val avdManager = locator.discover(preferredSdkPath()).avdManagerPath ?: return CommandResult.failure("avdmanager not found")
        return runner.run(listOf(avdManager, "create", "avd", "-n", name, "-k", systemImagePackage, "-d", profileId), 120)
    }

    override suspend fun startVirtualDevice(name: String): CommandResult {
        val emulator = locator.discover(preferredSdkPath()).emulatorPath ?: return CommandResult.failure("emulator not found")
        return withContext(Dispatchers.IO) {
            ProcessBuilder(
                listOf(
                    emulator,
                    "-avd", name,
                    "-no-window",
                    "-no-snapshot-load",
                    "-no-snapshot-save",
                    "-no-boot-anim",
                    "-gpu", "swiftshader_indirect",
                    "-writable-system",
                ),
            ).start()
            CommandResult.success("Starting $name headless with writable system")
        }
    }

    override suspend fun stopVirtualDevice(name: String): CommandResult {
        val sdk = locator.discover(preferredSdkPath())
        val adb = sdk.adbPath ?: return CommandResult.failure("ADB not found")
        val devices = AndroidParsers.parseAdbDevices(runner.run(listOf(adb, "devices", "-l"), 8).stdout)
        val emulator = devices.firstOrNull { device ->
            device.kind == DeviceKind.Emulator &&
                device.state == DeviceConnectionState.Online &&
                namesMatch(resolveAvdName(adb, device.serial) ?: device.displayName, name)
        } ?: return CommandResult.failure("No running emulator found for $name")
        val result = runner.run(listOf(adb, "-s", emulator.serial, "emu", "kill"), 8)
        return if (result.isSuccess) {
            CommandResult.success("Stopped $name (${emulator.serial})")
        } else {
            result
        }
    }

    private suspend fun runningEmulatorNames(): Set<String> = withContext(Dispatchers.IO) {
        File(System.getProperty("user.home"), ".android/avd").listFiles()
            ?.filter { it.name.endsWith(".avd") && File(it, "hardware-qemu.ini.lock").exists() }
            ?.map { it.name.removeSuffix(".avd") }
            ?.toSet()
            ?: emptySet()
    }

    private suspend fun resolveAvdName(adb: String, serial: String): String? {
        val result = runner.run(listOf(adb, "-s", serial, "shell", "getprop"), 8)
        if (!result.isSuccess) return null
        return result.stdout.lineSequence()
            .mapNotNull { line ->
                val match = Regex("""\[(.+)]\:\s+\[(.*)]""").find(line) ?: return@mapNotNull null
                match.groupValues[1] to match.groupValues[2]
            }
            .toMap()
            .let { props -> props["ro.boot.qemu.avd_name"] ?: props["ro.kernel.qemu.avd_name"] }
    }

    private fun namesMatch(left: String, right: String): Boolean {
        return normalizeName(left) == normalizeName(right)
    }

    private fun normalizeName(value: String): String {
        return value.replace('_', ' ').trim().lowercase()
    }
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
    private var videoForwardPort: Int? = null
    private var connectedSerial: String? = null
    private var connectedConfig: MirrorVideoConfig? = null
    private val controlLock = Any()

    override suspend fun connect(serial: String, config: MirrorVideoConfig): CommandResult {
        if (connectedSerial == serial && connectedConfig == config && videoJob?.isActive == true) {
            return CommandResult.success("Embedded mirror already connected for $serial")
        }
        disconnect()
        connectedSerial = serial
        connectedConfig = config
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        val scrcpyServer = findScrcpyServer()
            ?: return CommandResult.failure("scrcpy-server not found. Install scrcpy with `brew install scrcpy` or set SCRCPY_SERVER_PATH.")
        frames.value = MirrorFrame(1, 1, intArrayOf(0xff000000.toInt()))
        status.value = "Starting scrcpy-server raw H.264 mirror for $serial (${config.maxSize}px, ${config.bitRate / 1_000_000.0} Mbps)"
        videoJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runNativeVideoLoop(adb, serial, scrcpyServer, config)
        }
        return CommandResult.success("Embedded mirror starting for $serial")
    }

    override suspend fun disconnect() {
        videoJob?.cancel()
        videoJob = null
        synchronized(controlLock) {
            runCatching { controlOutput?.close() }
            controlOutput = null
            runCatching { controlSocket?.close() }
            controlSocket = null
        }
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
        status.value = "Disconnected"
    }

    override suspend fun sendInput(input: MirrorInput): CommandResult {
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
            val bgra = BytePointer(width.toLong() * height * 4)
            val dstData = PointerPointer<BytePointer>(4)
            val dstLinesize = IntPointer(4)
            avutil.av_image_fill_arrays(dstData, dstLinesize, bgra, avutil.AV_PIX_FMT_BGRA, width, height, 1)
            val scaledRows = swscale.sws_scale(context, frame.data(), frame.linesize(), 0, height, dstData, dstLinesize)
            if (scaledRows <= 0) {
                bgra.close()
                dstData.close()
                dstLinesize.close()
                avutil.av_frame_unref(frame)
                continue
            }
            frames.value = MirrorFrame(width, height, bgraToArgb(bgra, width * height), ++nextFrameNumber, decodedFps.takeIf { it > 0f })
            bgra.close()
            dstData.close()
            dstLinesize.close()
            avutil.av_frame_unref(frame)
        }
        currentSwsContext = context
        return nextFrameNumber
    }

    private fun bgraToArgb(bytes: BytePointer, pixelCount: Int): IntArray {
        val pixels = IntArray(pixelCount)
        var byteIndex = 0L
        for (pixelIndex in pixels.indices) {
            val b = bytes.get(byteIndex++).toInt() and 0xff
            val g = bytes.get(byteIndex++).toInt() and 0xff
            val r = bytes.get(byteIndex++).toInt() and 0xff
            val a = bytes.get(byteIndex++).toInt() and 0xff
            pixels[pixelIndex] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return pixels
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

    private fun findScrcpyServer(): File? {
        val envPath = System.getenv("SCRCPY_SERVER_PATH")?.takeIf { it.isNotBlank() }?.let(::File)
        if (envPath != null && envPath.isFile) return envPath

        val candidates = listOf(
            "/opt/homebrew/Cellar/scrcpy/4.0/share/scrcpy/scrcpy-server",
            "/opt/homebrew/share/scrcpy/scrcpy-server",
            "/usr/local/share/scrcpy/scrcpy-server",
            "/Applications/scrcpy/scrcpy-server",
        ).map(::File)
        return candidates.firstOrNull { it.isFile }
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

class DesktopLogcatService(
    private val runner: CommandRunner,
    private val devices: DesktopDeviceService,
) : LogcatService {
    override fun stream(serial: String, filter: LogcatFilter): Flow<List<LogcatEntry>> = channelFlow {
        val adb = devices.adbPath() ?: return@channelFlow
        val command = listOf(adb, "-s", serial, "logcat", "-v", "threadtime") + filter.buffers.flatMap { listOf("-b", it) }
        val normalizedFilter = resolveLogcatFilter(serial, filter)
        var process: Process? = null
        val reader = launch(Dispatchers.IO) {
            process = ProcessBuilder(command).redirectErrorStream(true).start()
            val batch = ArrayList<LogcatEntry>(80)
            var lastFlush = System.nanoTime()
            try {
                process?.inputStream?.bufferedReader()?.useLines { lines ->
                    for (line in lines) {
                        if (!isActive) break
                        val entry = AndroidParsers.parseLogcatLine(line)
                        if (entry != null && matchesLogcatFilter(entry, normalizedFilter)) {
                            batch += entry
                        }
                        val now = System.nanoTime()
                        if (batch.size >= 80 || (batch.isNotEmpty() && now - lastFlush > 80_000_000L)) {
                            send(batch.toList())
                            batch.clear()
                            lastFlush = now
                        }
                    }
                }
                if (batch.isNotEmpty()) send(batch.toList())
            } finally {
                process?.destroy()
            }
        }
        awaitClose {
            reader.cancel()
            process?.destroy()
            process?.destroyForcibly()
        }
    }

    override suspend fun snapshot(serial: String, filter: LogcatFilter, limit: Int): List<LogcatEntry> {
        val adb = devices.adbPath() ?: return emptyList()
        val normalizedFilter = resolveLogcatFilter(serial, filter)
        val result = runner.run(listOf(adb, "-s", serial, "logcat", "-d", "-v", "threadtime", "-t", limit.toString()), 10)
        return result.stdout.lineSequence()
            .mapNotNull(AndroidParsers::parseLogcatLine)
            .filter { matchesLogcatFilter(it, normalizedFilter) }
            .toList()
    }

    override suspend fun clear(serial: String) {
        val adb = devices.adbPath() ?: return
        runner.run(listOf(adb, "-s", serial, "logcat", "-c"), 10)
    }

    private suspend fun resolveLogcatFilter(serial: String, filter: LogcatFilter): ResolvedLogcatFilter {
        val (packageName, search) = AndroidParsers.extractPackageFilter(filter.search)
        val explicitPackage = filter.packageName ?: packageName
        val packagePids = explicitPackage?.takeIf { it.isNotBlank() }?.let { name ->
            devices.shell(serial, listOf("pidof", name)).stdout
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .toSet()
        }.orEmpty()
        return ResolvedLogcatFilter(search = search, levels = filter.levels, packageName = explicitPackage, packagePids = packagePids)
    }

    private fun matchesLogcatFilter(entry: LogcatEntry, filter: ResolvedLogcatFilter): Boolean {
        if (entry.level !in filter.levels) return false
        if (filter.packageName != null && entry.pid !in filter.packagePids) return false
        return filter.search.isBlank() ||
            entry.message.contains(filter.search, true) ||
            entry.tag.contains(filter.search, true)
    }

    private data class ResolvedLogcatFilter(
        val search: String,
        val levels: Set<LogLevel>,
        val packageName: String?,
        val packagePids: Set<String>,
    )
}

class DesktopIntentService(
    private val runner: CommandRunner,
    private val devices: DesktopDeviceService,
) : IntentService {
    override fun buildCommand(draft: IntentDraft): List<String> {
        val verb = when (draft.mode) {
            IntentMode.Activity, IntentMode.DeepLink -> "start"
            IntentMode.Service -> "startservice"
            IntentMode.Broadcast -> "broadcast"
        }
        return buildList {
            add("am")
            add(verb)
            if (draft.action.isNotBlank()) addAll(listOf("-a", draft.action))
            if (draft.component.isNotBlank()) addAll(listOf("-n", draft.component))
            draft.categories.filter { it.isNotBlank() }.forEach { addAll(listOf("-c", it)) }
            draft.flags.filter { it.isNotBlank() }.forEach { addAll(listOf("-f", it)) }
            draft.extras.forEach { extra ->
                val flag = when (extra.type) {
                    ExtraType.StringValue -> "--es"
                    ExtraType.BooleanValue -> "--ez"
                    ExtraType.IntValue -> "--ei"
                    ExtraType.LongValue -> "--el"
                    ExtraType.FloatValue -> "--ef"
                }
                addAll(listOf(flag, extra.key, extra.value))
            }
            if (draft.dataUri.isNotBlank()) addAll(listOf("-d", draft.dataUri))
        }
    }

    override suspend fun send(serial: String, draft: IntentDraft): CommandResult {
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        return runner.run(listOf(adb, "-s", serial, "shell") + buildCommand(draft))
    }
}

class DesktopAppService(
    private val runner: CommandRunner,
    private val devices: DesktopDeviceService,
) : AppService {
    override suspend fun listApps(serial: String): List<AndroidApp> {
        val packages = devices.shell(serial, listOf("cmd", "package", "list", "packages", "-U", "--show-versioncode")).stdout
            .lineSequence()
            .mapNotNull { line ->
                val packageName = Regex("""package:([^\s]+)""").find(line)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
                val versionCode = Regex("""versionCode:([^\s]+)""").find(line)?.groupValues?.getOrNull(1)
                packageName to versionCode
            }
            .toList()
        val systemPackages = devices.shell(serial, listOf("cmd", "package", "list", "packages", "-s")).stdout
            .lineSequence()
            .mapNotNull { it.substringAfter("package:", "").takeIf(String::isNotBlank)?.trim() }
            .toSet()
        val disabledPackages = devices.shell(serial, listOf("cmd", "package", "list", "packages", "-d")).stdout
            .lineSequence()
            .mapNotNull { it.substringAfter("package:", "").takeIf(String::isNotBlank)?.trim() }
            .toSet()
        return packages
            .map { (packageName, versionCode) ->
                AndroidApp(
                    packageName = packageName,
                    label = packageName.substringAfterLast('.'),
                    system = packageName in systemPackages,
                    enabled = packageName !in disabledPackages,
                    versionCode = versionCode,
                )
            }
            .sortedWith(compareBy<AndroidApp> { it.system }.thenBy { it.packageName })
    }

    override suspend fun launch(serial: String, packageName: String): CommandResult {
        return devices.shell(serial, listOf("monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1"))
    }

    override suspend fun stop(serial: String, packageName: String): CommandResult {
        return devices.shell(serial, listOf("am", "force-stop", packageName))
    }

    override suspend fun clearData(serial: String, packageName: String): CommandResult {
        return devices.shell(serial, listOf("pm", "clear", packageName))
    }

    override suspend fun resetPermissions(serial: String, packageName: String): CommandResult {
        return devices.shell(serial, listOf("pm", "reset-permissions", packageName))
    }

    override suspend fun uninstall(serial: String, packageName: String): CommandResult {
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        return runner.run(listOf(adb, "-s", serial, "uninstall", packageName), 60)
    }

    override suspend fun listPermissions(serial: String, packageName: String): List<AndroidPermission> {
        val output = devices.shell(serial, listOf("dumpsys", "package", packageName)).stdout
        return AndroidParsers.parsePackagePermissions(output)
    }

    override suspend fun listActivities(serial: String, packageName: String): List<AndroidActivity> {
        val output = devices.shell(serial, listOf("dumpsys", "package", packageName)).stdout
        return AndroidParsers.parsePackageActivities(packageName, output)
    }
}

class DesktopFileService(
    private val runner: CommandRunner,
    private val devices: DesktopDeviceService,
) : FileService {
    override suspend fun list(serial: String, path: String): List<DeviceFile> {
        val adb = devices.adbPath() ?: return emptyList()
        val listPath = if (path != "/" && !path.endsWith("/")) "$path/" else path
        val result = runner.run(listOf(adb, "-s", serial, "shell", "ls", "-la", listPath), 10)
        return AndroidParsers.parseFileListing(path, result.stdout)
    }

    override suspend fun pull(serial: String, remotePath: String, localPath: String): CommandResult {
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        return runner.run(listOf(adb, "-s", serial, "pull", remotePath, localPath), 120)
    }

    override suspend fun push(serial: String, localPath: String, remotePath: String): CommandResult {
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        return runner.run(listOf(adb, "-s", serial, "push", localPath, remotePath), 120)
    }

    override suspend fun delete(serial: String, remotePath: String): CommandResult {
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        return runner.run(listOf(adb, "-s", serial, "shell", "rm", "-rf", remotePath), 30)
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
        killOrphanedProxies()
        stop()
        proxyDir.mkdirs()
        writeAddon()
        writeRules(rules)
        val command = listOf(
            executable,
            "--listen-host", "0.0.0.0",
            "--listen-port", port.toString(),
            "--set", "confdir=${proxyDir.absolutePath}",
            "-s", addonFile.absolutePath,
            "--set", "termlog_verbosity=warn",
        )
        runCatching {
            process = processStarter(command, proxyDir, mapOf("ANDY_RULES_PATH" to rulesFile.absolutePath))
            status.value = "mitmdump listening on 0.0.0.0:$port"
            pumpProcess(process!!)
            CommandResult.success("mitmdump listening on 0.0.0.0:$port")
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
        stdoutJob?.cancel()
        stderrJob?.cancel()
        stdoutJob = null
        stderrJob = null
        process?.destroy()
        process = null
        status.value = "Proxy stopped"
        CommandResult.success("Proxy stopped")
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
        return runAdbShellSequence(adb, serial, commands, "Device proxy configured at $host:$port")
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
        return runAdbShellSequence(adb, serial, commands, "Device proxy cleared")
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
                "ADB root is not available for this emulator. Use a non-Google-Play emulator image to install Andy's CA as a system root. $rootOutput".trim(),
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

    private fun pumpProcess(proxyProcess: ProxyProcess) {
        stdoutJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            proxyProcess.stdout.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    println("[Proxy stdout] $line")
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
            if (!proxyProcess.isAlive()) status.value = "mitmdump exited"
        }
        stderrJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            proxyProcess.stderr.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    System.err.println("[Proxy stderr] $line")
                    if (line.isNotBlank()) status.value = line.take(220)
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

private val AndyMitmAddonSource = """
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
            flow.response.headers.pop(header, None)
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

class DesktopMetricsService(
    private val runner: CommandRunner,
    private val devices: DesktopDeviceService,
) : MetricsService {
    override fun stream(serial: String, packageName: String?): Flow<PerformanceSample> = flow {
        while (true) {
            val cpu = devices.shell(serial, listOf("dumpsys", "cpuinfo")).stdout
            val processRows = devices.shell(serial, listOf("top", "-b", "-n", "1", "-o", "PID,%CPU,RES,ARGS", "-m", "80")).stdout
                .ifBlank { devices.shell(serial, listOf("ps", "-A", "-o", "PID,RSS,NAME")).stdout }
            val processes = AndroidParsers.parseProcessMetrics(processRows)
            val mem = packageName?.let { devices.shell(serial, listOf("dumpsys", "meminfo", it)).stdout }
            val battery = devices.shell(serial, listOf("dumpsys", "battery")).stdout
            val focusedPackage = packageName
                ?: AndroidParsers.parseFocusedPackage(devices.shell(serial, listOf("dumpsys", "window", "windows")).stdout)
                ?: AndroidParsers.parseFocusedPackage(devices.shell(serial, listOf("dumpsys", "activity", "activities")).stdout)
            val frameTimes = focusedPackage?.let {
                AndroidParsers.parseFrameStats(devices.shell(serial, listOf("dumpsys", "gfxinfo", it, "framestats")).stdout)
            }.orEmpty()
            emit(
                PerformanceSample(
                    timestampMillis = System.currentTimeMillis(),
                    cpuPercent = Regex("""(\d+(?:\.\d+)?)%""").find(cpu)?.groupValues?.getOrNull(1)?.toFloatOrNull(),
                    memoryMb = (mem?.let { Regex("""TOTAL\s+(\d+)""").find(it)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.div(1024f) }
                        ?: processes.sumOf { it.memoryMb?.toDouble() ?: 0.0 }.toFloat().takeIf { it > 0f }),
                    fps = frameTimes.takeLast(60).count { it.millis <= 16.6f }.takeIf { frameTimes.isNotEmpty() }?.toFloat(),
                    batteryPercent = AndroidParsers.parseBatteryPercent(battery),
                    thermalStatus = null,
                    processes = processes,
                    frameRenderTimes = frameTimes,
                ),
            )
            delay(600)
        }
    }
}

class DesktopAccessibilityService(
    private val runner: CommandRunner,
    private val devices: DesktopDeviceService,
) : AccessibilityService {
    override suspend fun dump(serial: String): AccessibilityNode? {
        val adb = devices.adbPath() ?: return null
        runner.run(listOf(adb, "-s", serial, "shell", "uiautomator", "dump", "/sdcard/window_dump.xml"), 10)
        val result = runner.run(listOf(adb, "-s", serial, "exec-out", "cat", "/sdcard/window_dump.xml"), 10)
        return runCatching { AndroidParsers.parseAccessibilityXml(result.stdout) }.getOrNull()
    }
}

class DesktopWorkspaceStore : WorkspaceStore {
    private val file = File(System.getProperty("user.home"), ".andy/workspace.properties")

    override suspend fun load(): WorkspaceState = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext WorkspaceState()
        val props = Properties().apply { file.inputStream().use(::load) }
        WorkspaceState(
            selectedSdkPath = props.getProperty("selectedSdkPath")?.takeIf { it.isNotBlank() },
            selectedDeviceSerial = props.getProperty("selectedDeviceSerial")?.takeIf { it.isNotBlank() },
            logSearch = props.getProperty("logSearch").orEmpty(),
            proxyPort = props.getProperty("proxyPort")?.toIntOrNull() ?: 9099,
            proxyRules = loadProxyRules(props),
            liveDevicePaneWidth = props.getProperty("liveDevicePaneWidth")?.toFloatOrNull() ?: 390f,
            liveControlsPaneHeight = props.getProperty("liveControlsPaneHeight")?.toFloatOrNull() ?: 230f,
            appsListPaneWidth = props.getProperty("appsListPaneWidth")?.toFloatOrNull() ?: 520f,
            appsDetailsPaneHeight = props.getProperty("appsDetailsPaneHeight")?.toFloatOrNull() ?: 350f,
            performanceProcessesPaneWidth = props.getProperty("performanceProcessesPaneWidth")?.toFloatOrNull() ?: 760f,
            designDevicePaneWidth = props.getProperty("designDevicePaneWidth")?.toFloatOrNull() ?: 520f,
            accessibilityTreePaneWidth = props.getProperty("accessibilityTreePaneWidth")?.toFloatOrNull() ?: 760f,
        )
    }

    override suspend fun save(state: WorkspaceState) = withContext(Dispatchers.IO) {
        file.parentFile.mkdirs()
        val props = Properties().apply {
            setProperty("selectedSdkPath", state.selectedSdkPath.orEmpty())
            setProperty("selectedDeviceSerial", state.selectedDeviceSerial.orEmpty())
            setProperty("logSearch", state.logSearch)
            setProperty("proxyPort", state.proxyPort.toString())
            setProperty("proxyRuleCount", state.proxyRules.size.toString())
            state.proxyRules.forEachIndexed { index, rule ->
                val prefix = "proxyRule.$index."
                setProperty(prefix + "id", rule.id)
                setProperty(prefix + "name", rule.name)
                setProperty(prefix + "enabled", rule.enabled.toString())
                setProperty(prefix + "urlPattern", rule.urlPattern)
                setProperty(prefix + "method", rule.method.orEmpty())
                setProperty(prefix + "statusCode", rule.statusCode?.toString().orEmpty())
                setProperty(prefix + "setHeaders", encodeHeaderMap(rule.setHeaders))
                setProperty(prefix + "removeHeaders", rule.removeHeaders.joinToString("\n"))
                setProperty(prefix + "responseBody", rule.responseBody.orEmpty())
            }
            setProperty("liveDevicePaneWidth", state.liveDevicePaneWidth.toString())
            setProperty("liveControlsPaneHeight", state.liveControlsPaneHeight.toString())
            setProperty("appsListPaneWidth", state.appsListPaneWidth.toString())
            setProperty("appsDetailsPaneHeight", state.appsDetailsPaneHeight.toString())
            setProperty("performanceProcessesPaneWidth", state.performanceProcessesPaneWidth.toString())
            setProperty("designDevicePaneWidth", state.designDevicePaneWidth.toString())
            setProperty("accessibilityTreePaneWidth", state.accessibilityTreePaneWidth.toString())
        }
        file.outputStream().use { props.store(it, "Andy workspace") }
    }

    private fun loadProxyRules(props: Properties): List<ProxyRule> {
        val count = props.getProperty("proxyRuleCount")?.toIntOrNull() ?: return emptyList()
        return (0 until count).mapNotNull { index ->
            val prefix = "proxyRule.$index."
            val id = props.getProperty(prefix + "id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ProxyRule(
                id = id,
                name = props.getProperty(prefix + "name").orEmpty().ifBlank { id },
                enabled = props.getProperty(prefix + "enabled")?.toBooleanStrictOrNull() ?: true,
                urlPattern = props.getProperty(prefix + "urlPattern").orEmpty(),
                method = props.getProperty(prefix + "method")?.takeIf { it.isNotBlank() },
                statusCode = props.getProperty(prefix + "statusCode")?.toIntOrNull(),
                setHeaders = decodeHeaderMap(props.getProperty(prefix + "setHeaders").orEmpty()),
                removeHeaders = props.getProperty(prefix + "removeHeaders").orEmpty().lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList(),
                responseBody = props.getProperty(prefix + "responseBody")?.takeIf { it.isNotBlank() },
            )
        }
    }

    private fun encodeHeaderMap(headers: Map<String, String>): String {
        return headers.entries.joinToString("\n") { "${it.key}:${it.value}" }
    }

    private fun decodeHeaderMap(value: String): Map<String, String> {
        return value.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && ":" in it }
            .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
    }
}
