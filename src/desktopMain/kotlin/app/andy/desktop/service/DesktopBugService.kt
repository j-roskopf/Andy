package app.andy.desktop.service

import app.andy.domain.BugReplayFps
import app.andy.desktop.service.mirror.DesktopMirrorEngine
import app.andy.desktop.service.mirror.NativeMirrorJni
import app.andy.model.AndroidDevice
import app.andy.model.AccessibilityNode
import app.andy.model.BugAction
import app.andy.model.BugArtifact
import app.andy.model.BugCaptureDraft
import app.andy.model.BugCaptureStatus
import app.andy.model.BugReport
import app.andy.model.LogLevel
import app.andy.service.AccessibilityService
import app.andy.service.BugService
import app.andy.service.DeviceService
import app.andy.service.LogcatFilter
import app.andy.service.LogcatService
import app.andy.service.MirrorEngine
import app.andy.service.MirrorFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.max

class DesktopBugService(
    private val mirror: MirrorEngine,
    private val logcat: LogcatService,
    private val homeDir: File = File(System.getProperty("user.home")),
    private val devices: DeviceService? = null,
    private val accessibility: AccessibilityService? = null,
) : BugService {
    override val status = MutableStateFlow(BugCaptureStatus())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val actions = ArrayDeque<BugAction>()
    private val logs = ArrayDeque<TimestampedLogLine>()
    private val frames = ArrayDeque<TimestampedFrame>()
    private val h264Units = ArrayDeque<TimestampedH264>()
    private var latestH264Config: ByteArray? = null
    private var captureSerial: String? = null
    private var captureDevice: AndroidDevice? = null
    private var captureStartedAtMillis: Long = 0L
    private var frameJob: Job? = null
    private var encodedJob: Job? = null
    private var logJob: Job? = null
    private var screenJob: Job? = null
    @Volatile private var lastFrameSampledAtMillis: Long = 0L

    private val bugsDir: File get() = File(homeDir, ".andy/bugs")
    private val exportsDir: File get() = File(homeDir, ".andy/exports")

    override suspend fun startCapture(serial: String, device: AndroidDevice?) {
        if (captureSerial == serial && status.value.active) return
        stopCapture()
        synchronized(lock) {
            actions.clear()
            logs.clear()
            frames.clear()
            h264Units.clear()
            latestH264Config = null
            captureSerial = serial
            captureDevice = device
            captureStartedAtMillis = System.currentTimeMillis()
            lastFrameSampledAtMillis = 0L
        }
        status.value = BugCaptureStatus(active = true, deviceSerial = serial, message = "Recording last 30s for $serial")
        // Prefer the live Annex-B H.264 bitstream (full stream FPS). Keep ARGB samples as a
        // parallel backup — packet remux can fail, and GPU metadata frames alone cannot encode.
        encodedJob = scope.launch {
            val encoded = (mirror as? DesktopMirrorEngine)?.encodedVideo ?: mirror.encodedVideo
            encoded.collect { unit ->
                val now = unit.timestampMillis
                synchronized(lock) {
                    if (captureSerial == null) return@synchronized
                    if (isH264ConfigAccessUnit(unit.bytes)) {
                        latestH264Config = unit.bytes.copyOf()
                    }
                    h264Units += TimestampedH264(now, unit.bytes.copyOf(), unit.width, unit.height)
                    trimLocked(now)
                    publishStatusLocked("Recording last 30s for $serial")
                }
            }
        }
        frameJob = scope.launch {
            var lastCpuFrame: MirrorFrame? = null
            val cpuJob = launch {
                mirror.frames.collect { frame ->
                    if (frame.width > 1 && frame.height > 1 && frame.argb.size >= frame.width * frame.height) {
                        lastCpuFrame = frame.copy(argb = frame.argb.copyOf())
                    }
                }
            }
            try {
                while (currentCoroutineContext().isActive) {
                    delay(ARGB_SAMPLE_INTERVAL_MILLIS)
                    val now = System.currentTimeMillis()
                    // Keep ARGB samples as a backup even when H.264 is flowing — remux can fail
                    // (missing encoder, bad annex-B window) and GPU metadata frames cannot encode.
                    // Native YUV snapshot copies planes quickly so VT/Metal stay responsive.
                    val sampled = lastCpuFrame?.let { it.copy(argb = it.argb.copyOf()) }
                        ?: NativeMirrorJni.copyLatestFrameArgb()
                    if (sampled == null) continue
                    synchronized(lock) {
                        if (captureSerial == null) return@synchronized
                        if (now - lastFrameSampledAtMillis < ARGB_SAMPLE_INTERVAL_MILLIS) return@synchronized
                        lastFrameSampledAtMillis = now
                        frames += TimestampedFrame(now, sampled)
                        trimLocked(now)
                        publishStatusLocked("Recording last 30s for $serial")
                    }
                }
            } finally {
                cpuJob.cancel()
            }
        }
        logJob = logcat.stream(serial, rollingLogcatFilter())
            .onEach { batch ->
                val now = System.currentTimeMillis()
                synchronized(lock) {
                    batch.forEach { entry ->
                        logs += TimestampedLogLine(
                            now,
                            "${entry.time} ${entry.pid ?: "-"} ${entry.tid ?: "-"} ${entry.level.name.first()} ${entry.tag}: ${entry.message}",
                        )
                    }
                    trimLocked(now)
                    publishStatusLocked("Recording last 30s for $serial")
                }
            }
            .launchIn(scope)
        screenJob = devices?.let { deviceService ->
            scope.launch {
                pollForegroundScreens(serial, deviceService, accessibility)
            }
        }
    }

    override suspend fun stopCapture() {
        frameJob?.cancel()
        frameJob = null
        encodedJob?.cancel()
        encodedJob = null
        logJob?.cancel()
        logJob = null
        screenJob?.cancel()
        screenJob = null
        synchronized(lock) {
            captureSerial = null
            captureDevice = null
            captureStartedAtMillis = 0L
        }
        status.value = BugCaptureStatus(message = "Bug capture idle")
    }

    override fun recordAction(kind: String, label: String, detail: String?) {
        appendAction(kind, label, detail)
    }

    private fun appendAction(kind: String, label: String, detail: String? = null, timestampMillis: Long = System.currentTimeMillis()) {
        val serial = captureSerial ?: return
        synchronized(lock) {
            actions += BugAction(
                id = "action-$timestampMillis-${actions.size + 1}",
                timestampMillis = timestampMillis,
                kind = kind,
                label = label,
                detail = detail,
            )
            trimLocked(timestampMillis)
            publishStatusLocked("Recording last 30s for $serial")
        }
    }

    private suspend fun pollForegroundScreens(serial: String, devices: DeviceService, accessibility: AccessibilityService?) {
        var previous: ForegroundScreen? = null
        while (currentCoroutineContext().isActive) {
            val screen = readForegroundScreen(serial, devices, accessibility)
            if (screen != null) {
                val last = previous
                when {
                    last == null -> appendAction("screen", "Screen ${screen.shortActivityName}", screen.detail)
                    last.packageName != screen.packageName -> appendAction("screen", "Launch ${screen.packageName}", screen.detail)
                    last.activityName != screen.activityName || last.fragments != screen.fragments ->
                        appendAction("screen", "Screen ${screen.shortActivityName}", screen.detail)
                    last.semanticSignature != null && last.semanticSignature != screen.semanticSignature ->
                        appendAction("screen", "Screen ${screen.semanticTitle ?: screen.shortActivityName}", screen.detail)
                }
                previous = screen
            }
            delay(SCREEN_POLL_MILLIS)
        }
    }

    private suspend fun readForegroundScreen(serial: String, devices: DeviceService, accessibility: AccessibilityService?): ForegroundScreen? {
        val activity = devices.shell(serial, listOf("dumpsys", "activity", "activities"))
        val window = devices.shell(serial, listOf("dumpsys", "window", "windows"))
        val activityOutput = activity.stdout.takeIf { activity.isSuccess }.orEmpty()
        val windowOutput = window.stdout.takeIf { window.isSuccess }.orEmpty()
        val semantic = accessibility?.dump(serial)?.toScreenSemantics()
        return parseForegroundScreen(activityOutput, windowOutput, semantic)
    }

    override suspend fun saveBug(draft: BugCaptureDraft, device: AndroidDevice?): BugReport = withContext(Dispatchers.IO) {
        val title = draft.title.trim()
        require(title.isNotBlank()) { "Bug title is required" }
        val now = System.currentTimeMillis()
        val snapshot = synchronized(lock) {
            trimLocked(now)
            BugSnapshot(
                serial = captureSerial ?: device?.serial ?: "unknown-device",
                device = device ?: captureDevice,
                startedAtMillis = captureStartedAtMillis.takeIf { it > 0L } ?: now,
                actions = actions.toList(),
                logs = logs.toList(),
                frames = frames.toList(),
                h264Units = h264Units.toList(),
                h264Config = latestH264Config?.copyOf(),
            )
        }
        val reportId = "bug-$now"
        val reportDir = File(bugsDir, reportId).apply { mkdirs() }
        val captureFile = File(reportDir, "capture.mp4")
        val logFile = File(reportDir, "logcat.txt")
        val actionsFile = File(reportDir, "actions.json")
        val metadataFile = File(reportDir, "metadata.json")

        logFile.writeText(snapshot.logs.joinToString("\n") { it.line } + if (snapshot.logs.isNotEmpty()) "\n" else "")
        actionsFile.writeText(BugJson.writeActions(snapshot.actions))
        val videoMeta = encodeCaptureVideo(snapshot, captureFile)

        val artifacts = listOf(
            BugArtifact("actions.json", "actions.json", "actions", actionsFile.length()),
            BugArtifact("logcat.txt", "logcat.txt", "logcat", logFile.length()),
            BugArtifact("capture.mp4", "capture.mp4", "video", captureFile.length()),
            BugArtifact("metadata.json", "metadata.json", "metadata", null),
        )
        val windowStart = listOfNotNull(
            snapshot.actions.minOfOrNull { it.timestampMillis },
            snapshot.logs.minOfOrNull { it.timestampMillis },
            videoMeta.startedAtMillis,
        ).minOrNull() ?: max(snapshot.startedAtMillis, now - WINDOW_MILLIS)
        val report = BugReport(
            id = reportId,
            title = title,
            notes = draft.notes.trim(),
            deviceSerial = snapshot.serial,
            deviceModel = snapshot.device?.model ?: snapshot.device?.displayName,
            apiLevel = snapshot.device?.apiLevel,
            abi = snapshot.device?.abi,
            resolution = snapshot.device?.screenSize,
            capturedAtMillis = now,
            windowStartedAtMillis = windowStart,
            windowEndedAtMillis = now,
            actions = snapshot.actions,
            artifacts = artifacts,
            videoStartedAtMillis = videoMeta.startedAtMillis,
            videoEndedAtMillis = videoMeta.endedAtMillis,
            videoFrameRate = videoMeta.frameRate,
            videoFrameTimestampsMillis = videoMeta.timestampsMillis,
        )
        metadataFile.writeText(BugJson.writeReport(report.copy(artifacts = artifacts.map {
            if (it.name == "metadata.json") it.copy(sizeBytes = metadataFile.length()) else it
        })))
        report
    }

    override suspend fun listBugs(): List<BugReport> = withContext(Dispatchers.IO) {
        bugsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { readReport(it) }
            ?.sortedByDescending { it.capturedAtMillis }
            ?: emptyList()
    }

    override suspend fun loadBug(id: String): BugReport? = withContext(Dispatchers.IO) {
        readReport(File(bugsDir, id))
    }

    override suspend fun loadBugLog(id: String): String = withContext(Dispatchers.IO) {
        File(File(bugsDir, id), "logcat.txt").takeIf { it.isFile }?.readText().orEmpty()
    }

    override suspend fun deleteBug(id: String): Boolean = withContext(Dispatchers.IO) {
        File(bugsDir, id).deleteRecursively()
    }

    override suspend fun exportBug(id: String): String? = withContext(Dispatchers.IO) {
        val source = File(bugsDir, id).takeIf { it.isDirectory } ?: return@withContext null
        val target = File(exportsDir, id)
        if (target.exists()) target.deleteRecursively()
        copyDirectory(source, target)
        target.absolutePath
    }

    override fun playbackFrames(id: String, startFrameIndex: Int): Flow<MirrorFrame> = flow {
        val report = readReport(File(bugsDir, id))
        val file = File(File(bugsDir, id), "capture.mp4")
        if (!file.isFile || file.length() == 0L) return@flow
        val grabber = FFmpegFrameGrabber(file)
        val converter = Java2DFrameConverter()
        try {
            grabber.start()
            val fps = grabber.frameRate.takeIf { it.isFinite() && it > 1.0 }
                ?: report?.videoFrameRate?.takeIf { it > 0.0 }
                ?: BugReplayFps
            val frameBudgetMillis = (1000.0 / fps).toLong().coerceIn(8L, 100L)
            val startIndex = startFrameIndex.coerceAtLeast(0)
            if (startIndex > 0) {
                grabber.setVideoFrameNumber(startIndex)
            }
            val timestamps = report?.videoFrameTimestampsMillis.orEmpty()
            val originMillis = timestamps.getOrNull(startIndex)
                ?: report?.videoStartedAtMillis
                ?: 0L
            val startNanos = System.nanoTime()
            var frameNumber = startIndex.toLong()
            while (currentCoroutineContext().isActive) {
                val grabbed = grabber.grabImage() ?: break
                val index = frameNumber.toInt()
                frameNumber++
                val targetOffsetMillis = timestamps.getOrNull(index)?.minus(originMillis)?.coerceAtLeast(0L)
                    ?: ((index - startIndex).coerceAtLeast(0) * frameBudgetMillis)
                val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L
                // High-FPS captures decode slower than real time in Compose. Skip presents when
                // behind so Reproduce stays wall-clock accurate instead of a choppy backlog.
                if (elapsedMillis > targetOffsetMillis + frameBudgetMillis) {
                    continue
                }
                if (elapsedMillis < targetOffsetMillis) {
                    delay(targetOffsetMillis - elapsedMillis)
                }
                val image = converter.convert(grabbed) ?: continue
                emit(image.toMirrorFrame(frameNumber))
            }
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
            converter.close()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun bugVideoFrameCount(id: String): Int = withContext(Dispatchers.IO) {
        val file = File(File(bugsDir, id), "capture.mp4")
        if (!file.isFile || file.length() == 0L) return@withContext 0
        val grabber = FFmpegFrameGrabber(file)
        try {
            grabber.start()
            grabber.lengthInVideoFrames.takeIf { it > 0 } ?: grabber.lengthInFrames.coerceAtLeast(0)
        } catch (_: Throwable) {
            0
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }

    override suspend fun loadBugVideoFrame(id: String, frameIndex: Int): MirrorFrame? = withContext(Dispatchers.IO) {
        val file = File(File(bugsDir, id), "capture.mp4")
        if (!file.isFile || file.length() == 0L) return@withContext null
        val grabber = FFmpegFrameGrabber(file)
        val converter = Java2DFrameConverter()
        try {
            grabber.start()
            grabber.setVideoFrameNumber(frameIndex.coerceAtLeast(0))
            val image = generateSequence { grabber.grabImage() }.firstOrNull()?.let(converter::convert)
            image?.toMirrorFrame(frameIndex.toLong() + 1)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
            converter.close()
        }
    }

    private fun readReport(reportDir: File): BugReport? {
        val metadata = File(reportDir, "metadata.json")
        if (!metadata.isFile) return null
        return runCatching { BugJson.readReport(metadata.readText()) }.getOrNull()
    }

    private fun encodeCaptureVideo(snapshot: BugSnapshot, captureFile: File): VideoEncodeMeta {
        if (snapshot.h264Units.isNotEmpty()) {
            val meta = remuxH264Mp4(snapshot.h264Units, snapshot.h264Config, captureFile)
            if (captureFile.isFile && captureFile.length() > 0L) return meta
        }
        encodeArgbMp4(snapshot.frames, captureFile)
        return VideoEncodeMeta(
            frameRate = ARGB_FALLBACK_FRAME_RATE,
            startedAtMillis = snapshot.frames.firstOrNull()?.timestampMillis,
            endedAtMillis = snapshot.frames.lastOrNull()?.timestampMillis,
            timestampsMillis = snapshot.frames.map { it.timestampMillis },
        )
    }

    private fun encodeArgbMp4(sourceFrames: List<TimestampedFrame>, file: File) {
        file.parentFile.mkdirs()
        val usable = sourceFrames.filter { sample ->
            sample.frame.width > 1 &&
                sample.frame.height > 1 &&
                sample.frame.argb.size >= sample.frame.width * sample.frame.height
        }
        if (usable.isEmpty()) {
            file.writeBytes(ByteArray(0))
            return
        }
        val width = usable.first().frame.width
        val height = usable.first().frame.height
        val recorder = FFmpegFrameRecorder(file, width, height)
        val converter = Java2DFrameConverter()
        try {
            configureSoftwareH264(recorder, ARGB_FALLBACK_FRAME_RATE, 4_000_000)
            recorder.start()
            usable.forEach { sample ->
                if (sample.frame.width != width || sample.frame.height != height) return@forEach
                recorder.record(converter.convert(sample.frame.toBufferedImage()))
            }
        } catch (_: Throwable) {
            file.writeBytes(ByteArray(0))
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            converter.close()
        }
    }

    /**
     * Writes the rolling Annex-B H.264 window to MP4. Packet-copy remux is unreliable with the
     * bundled JavaCV build, so we decode access units and re-encode — still at the live stream
     * frame rate, unlike the ARGB sample path.
     */
    private fun remuxH264Mp4(
        units: List<TimestampedH264>,
        config: ByteArray?,
        file: File,
    ): VideoEncodeMeta {
        file.parentFile.mkdirs()
        if (units.isEmpty()) {
            file.writeBytes(ByteArray(0))
            return VideoEncodeMeta(ARGB_FALLBACK_FRAME_RATE, null, null, emptyList())
        }
        val width = units.last().width.coerceAtLeast(2)
        val height = units.last().height.coerceAtLeast(2)
        val pictureUnits = units.filter { isH264PictureAccessUnit(it.bytes) }
        val started = (pictureUnits.firstOrNull() ?: units.first()).timestampMillis
        val ended = (pictureUnits.lastOrNull() ?: units.last()).timestampMillis
        val durationMillis = (ended - started).coerceAtLeast(1L)
        val pictureCount = pictureUnits.size.coerceAtLeast(1)
        val estimatedFps = (pictureCount * 1000.0 / durationMillis).coerceIn(15.0, 120.0)
        val raw = File(file.parentFile, "capture-raw.h264")
        val meta = VideoEncodeMeta(
            frameRate = estimatedFps,
            startedAtMillis = started,
            endedAtMillis = ended,
            // Prefer picture timestamps so replay pacing matches decoded frames, not SPS/PPS AUs.
            timestampsMillis = (pictureUnits.ifEmpty { units }).map { it.timestampMillis },
        )
        try {
            raw.outputStream().use { out ->
                val firstIsConfig = isH264ConfigAccessUnit(units.first().bytes)
                if (!firstIsConfig && config != null) {
                    out.write(config)
                }
                units.forEach { out.write(it.bytes) }
            }
            if (raw.length() == 0L) {
                file.writeBytes(ByteArray(0))
                return meta
            }
            // Prefer bitstream copy (full live FPS/quality). OpenH264 re-encode is fallback only —
            // the bundled FFmpeg has no libx264, and VT H.264 encode races the live decoder.
            packetCopyH264(raw, file, width, height, estimatedFps)
            if (!file.isFile || file.length() == 0L) {
                transcodeH264(raw, file, width, height, estimatedFps)
            }
        } catch (_: Throwable) {
            if (!file.isFile || file.length() == 0L) {
                file.writeBytes(ByteArray(0))
            }
        } finally {
            raw.delete()
        }
        return meta
    }

    private fun packetCopyH264(raw: File, file: File, width: Int, height: Int, frameRate: Double) {
        val grabber = FFmpegFrameGrabber(raw)
        try {
            grabber.format = "h264"
            grabber.frameRate = frameRate
            grabber.setOption("hwaccel", "none")
            grabber.setOption("fflags", "+genpts")
            grabber.start()
            val outWidth = grabber.imageWidth.takeIf { it > 0 } ?: width
            val outHeight = grabber.imageHeight.takeIf { it > 0 } ?: height
            if (file.exists()) file.delete()
            val recorder = FFmpegFrameRecorder(file, outWidth, outHeight)
            try {
                recorder.format = "mp4"
                recorder.frameRate = frameRate
                recorder.videoCodec = avcodec.AV_CODEC_ID_H264
                recorder.setOption("movflags", "+faststart")
                // Copy compressed access units into MP4 — preserves scrcpy's full capture FPS.
                recorder.start(grabber.formatContext)
                var copied = 0
                while (true) {
                    val packet = grabber.grabPacket() ?: break
                    if (packet.stream_index() == grabber.videoStream) {
                        if (recorder.recordPacket(packet)) copied++
                    }
                }
                if (copied == 0) {
                    file.writeBytes(ByteArray(0))
                }
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
        } catch (_: Throwable) {
            runCatching { if (file.isFile) file.writeBytes(ByteArray(0)) }
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }

    private fun transcodeH264(raw: File, file: File, width: Int, height: Int, frameRate: Double) {
        val grabber = FFmpegFrameGrabber(raw)
        try {
            grabber.format = "h264"
            grabber.frameRate = frameRate
            grabber.setOption("hwaccel", "none")
            grabber.start()
            val outWidth = grabber.imageWidth.takeIf { it > 0 } ?: width
            val outHeight = grabber.imageHeight.takeIf { it > 0 } ?: height
            val recorder = FFmpegFrameRecorder(file, outWidth, outHeight)
            try {
                configureSoftwareH264(recorder, frameRate, 8_000_000)
                recorder.start()
                var recorded = 0
                while (true) {
                    val frame = grabber.grabImage() ?: break
                    // Clear invented PTS so the recorder paces by frameRate (real-time duration).
                    frame.timestamp = 0L
                    recorder.record(frame)
                    recorded++
                }
                if (recorded == 0) {
                    file.writeBytes(ByteArray(0))
                }
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
        } catch (_: Throwable) {
            file.writeBytes(ByteArray(0))
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }

    /**
     * Software H.264 via OpenH264. Bundled bytedeco FFmpeg is LGPL (no libx264); using
     * `h264_videotoolbox` beside Andy's live VT decoder has crashed CoreMedia.
     */
    private fun configureSoftwareH264(recorder: FFmpegFrameRecorder, frameRate: Double, bitrate: Int) {
        recorder.format = "mp4"
        recorder.frameRate = frameRate
        recorder.videoBitrate = bitrate
        recorder.pixelFormat = avutil.AV_PIX_FMT_YUV420P
        recorder.videoCodec = avcodec.AV_CODEC_ID_H264
        recorder.videoCodecName = "libopenh264"
    }

    private fun trimLocked(now: Long) {
        val cutoff = now - WINDOW_MILLIS
        while (actions.firstOrNull()?.timestampMillis?.let { it < cutoff } == true) actions.removeFirst()
        while (logs.firstOrNull()?.timestampMillis?.let { it < cutoff } == true) logs.removeFirst()
        while (frames.firstOrNull()?.timestampMillis?.let { it < cutoff } == true) frames.removeFirst()
        while (h264Units.firstOrNull()?.timestampMillis?.let { it < cutoff } == true) h264Units.removeFirst()
    }

    private fun publishStatusLocked(message: String) {
        status.value = BugCaptureStatus(
            active = captureSerial != null,
            deviceSerial = captureSerial,
            actionCount = actions.size,
            logCount = logs.size,
            videoFrameCount = frames.size,
            message = message,
        )
    }

    private fun copyDirectory(source: File, target: File) {
        source.walkTopDown().forEach { file ->
            val relative = file.relativeTo(source)
            val destination = File(target, relative.path)
            if (file.isDirectory) {
                destination.mkdirs()
            } else {
                destination.parentFile.mkdirs()
                Files.copy(file.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun rollingLogcatFilter() = LogcatFilter(
        levels = setOf(LogLevel.Verbose, LogLevel.Debug, LogLevel.Info, LogLevel.Warn, LogLevel.Error, LogLevel.Fatal),
        buffers = setOf("main", "system", "crash"),
    )

    private fun MirrorFrame.toBufferedImage(): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
        image.setRGB(0, 0, width, height, argb, 0, width)
        return image
    }

    private fun BufferedImage.toMirrorFrame(frameNumber: Long): MirrorFrame {
        val pixels = IntArray(width * height)
        getRGB(0, 0, width, height, pixels, 0, width)
        return MirrorFrame(width, height, pixels, frameNumber)
    }

    private data class TimestampedFrame(val timestampMillis: Long, val frame: MirrorFrame)
    private data class TimestampedH264(
        val timestampMillis: Long,
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
    )
    private data class TimestampedLogLine(val timestampMillis: Long, val line: String)
    private data class VideoEncodeMeta(
        val frameRate: Double,
        val startedAtMillis: Long?,
        val endedAtMillis: Long?,
        val timestampsMillis: List<Long>,
    )
    private data class ForegroundScreen(
        val packageName: String,
        val activityName: String,
        val fragments: List<String>,
        val semanticTitle: String?,
        val semanticSignature: String?,
    ) {
        val shortActivityName: String get() = activityName.substringAfterLast('.')
        val detail: String get() = buildList {
            add("$packageName/$activityName")
            if (fragments.isNotEmpty()) add("fragments: ${fragments.joinToString(", ")}")
            if (!semanticTitle.isNullOrBlank()) add("content: $semanticTitle")
        }.joinToString(" · ")
    }

    private data class BugSnapshot(
        val serial: String,
        val device: AndroidDevice?,
        val startedAtMillis: Long,
        val actions: List<BugAction>,
        val logs: List<TimestampedLogLine>,
        val frames: List<TimestampedFrame>,
        val h264Units: List<TimestampedH264>,
        val h264Config: ByteArray?,
    )

    companion object {
        private const val WINDOW_MILLIS = 30_000L
        /** ARGB fallback only — used when no H.264 bitstream tap is available. */
        private const val ARGB_FALLBACK_FRAME_RATE = 30.0
        private const val ARGB_SAMPLE_INTERVAL_MILLIS = 33L
        private const val SCREEN_POLL_MILLIS = 3_000L

        private fun isH264ConfigAccessUnit(bytes: ByteArray): Boolean {
            return h264NalTypes(bytes).any { it == 7 || it == 8 }
        }

        private fun isH264PictureAccessUnit(bytes: ByteArray): Boolean {
            return h264NalTypes(bytes).any { it == 1 || it == 5 }
        }

        private fun h264NalTypes(bytes: ByteArray): Sequence<Int> = sequence {
            var i = 0
            while (i + 4 < bytes.size) {
                val startLen = when {
                    bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() &&
                        bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte() -> 4
                    bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() &&
                        bytes[i + 2] == 1.toByte() -> 3
                    else -> {
                        i++
                        continue
                    }
                }
                if (i + startLen < bytes.size) {
                    yield(bytes[i + startLen].toInt() and 0x1F)
                }
                i += startLen + 1
            }
        }

        private fun parseForegroundScreen(activityOutput: String, windowOutput: String, semantic: ScreenSemantics?): ForegroundScreen? {
            val combined = "$activityOutput\n$windowOutput"
            val component = listOf(
                Regex("""topResumedActivity=.*?\s([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)"""),
                Regex("""mResumedActivity=.*?\s([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)"""),
                Regex("""mFocusedApp=.*?\s([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)"""),
                Regex("""mCurrentFocus=.*?\s([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)"""),
            ).firstNotNullOfOrNull { pattern ->
                pattern.find(combined)?.let { match ->
                    match.groupValues[1] to match.groupValues[2].trimEnd('}', ')')
                }
            } ?: return null
            val packageName = component.first
            val rawActivity = component.second
            val activityName = when {
                rawActivity.startsWith(".") -> packageName + rawActivity
                rawActivity.contains(".") -> rawActivity
                else -> "$packageName.$rawActivity"
            }
            val fragments = Regex("""#\d+:\s+([A-Za-z0-9_.$]+)\{""")
                .findAll(activityOutput)
                .map { it.groupValues[1].substringAfterLast('.') }
                .filter { it.isNotBlank() }
                .distinct()
                .take(4)
                .toList()
            return ForegroundScreen(packageName, activityName, fragments, semantic?.title, semantic?.signature)
        }
    }
}

private data class ScreenSemantics(
    val title: String?,
    val signature: String?,
)

private fun AccessibilityNode.toScreenSemantics(): ScreenSemantics {
    val labels = flattenForScreenSemantics()
        .filter { node -> node.visible && node.enabled }
        .mapNotNull { node ->
            listOf(node.text, node.contentDescription, node.hint, node.resourceId)
                .firstOrNull { !it.isNullOrBlank() }
                ?.trim()
                ?.takeIf { it.length in 2..120 }
        }
        .filterNot { it.matches(Regex("""\d{1,2}:\d{2}""")) }
        .distinct()
        .take(8)
        .toList()
    return ScreenSemantics(
        title = labels.firstOrNull(),
        signature = labels.takeIf { it.isNotEmpty() }?.joinToString("|"),
    )
}

private fun AccessibilityNode.flattenForScreenSemantics(): List<AccessibilityNode> {
    return listOf(this) + children.flatMap { it.flattenForScreenSemantics() }
}
