package app.andy.desktop.service

import app.andy.desktop.updates.SimpleJsonParser
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
    private var captureSerial: String? = null
    private var captureDevice: AndroidDevice? = null
    private var captureStartedAtMillis: Long = 0L
    private var frameJob: Job? = null
    private var logJob: Job? = null
    private var screenJob: Job? = null
    private var lastFrameSampledAtMillis: Long = 0L

    private val bugsDir: File get() = File(homeDir, ".andy/bugs")
    private val exportsDir: File get() = File(homeDir, ".andy/exports")

    override suspend fun startCapture(serial: String, device: AndroidDevice?) {
        if (captureSerial == serial && status.value.active) return
        stopCapture()
        synchronized(lock) {
            actions.clear()
            logs.clear()
            frames.clear()
            captureSerial = serial
            captureDevice = device
            captureStartedAtMillis = System.currentTimeMillis()
            lastFrameSampledAtMillis = 0L
        }
        status.value = BugCaptureStatus(active = true, deviceSerial = serial, message = "Recording last 30s for $serial")
        frameJob = scope.launch {
            mirror.frames.collect { frame ->
                val now = System.currentTimeMillis()
                if (frame.width <= 1 || frame.height <= 1) return@collect
                synchronized(lock) {
                    if (now - lastFrameSampledAtMillis < 66L) return@synchronized
                    lastFrameSampledAtMillis = now
                    frames += TimestampedFrame(now, frame.copy(argb = frame.argb.copyOf()))
                    trimLocked(now)
                    publishStatusLocked("Recording last 30s for $serial")
                }
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
        encodeMp4(snapshot.frames, captureFile)

        val artifacts = listOf(
            BugArtifact("actions.json", "actions.json", "actions", actionsFile.length()),
            BugArtifact("logcat.txt", "logcat.txt", "logcat", logFile.length()),
            BugArtifact("capture.mp4", "capture.mp4", "video", captureFile.length()),
            BugArtifact("metadata.json", "metadata.json", "metadata", null),
        )
        val windowStart = listOfNotNull(
            snapshot.actions.minOfOrNull { it.timestampMillis },
            snapshot.logs.minOfOrNull { it.timestampMillis },
            snapshot.frames.minOfOrNull { it.timestampMillis },
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
            videoStartedAtMillis = snapshot.frames.firstOrNull()?.timestampMillis,
            videoEndedAtMillis = snapshot.frames.lastOrNull()?.timestampMillis,
            videoFrameRate = BUG_VIDEO_FRAME_RATE,
            videoFrameTimestampsMillis = snapshot.frames.map { it.timestampMillis },
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
        val file = File(File(bugsDir, id), "capture.mp4")
        if (!file.isFile || file.length() == 0L) return@flow
        val grabber = FFmpegFrameGrabber(file)
        val converter = Java2DFrameConverter()
        try {
            grabber.start()
            val startIndex = startFrameIndex.coerceAtLeast(0)
            if (startIndex > 0) {
                grabber.setVideoFrameNumber(startIndex)
            }
            var frameNumber = startIndex.toLong()
            while (currentCoroutineContext().isActive) {
                val grabbed = grabber.grabImage() ?: break
                val image = converter.convert(grabbed) ?: continue
                emit(image.toMirrorFrame(++frameNumber))
                delay(66L)
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

    private fun encodeMp4(sourceFrames: List<TimestampedFrame>, file: File) {
        file.parentFile.mkdirs()
        if (sourceFrames.isEmpty()) {
            file.writeBytes(ByteArray(0))
            return
        }
        val width = sourceFrames.first().frame.width
        val height = sourceFrames.first().frame.height
        val recorder = FFmpegFrameRecorder(file, width, height)
        val converter = Java2DFrameConverter()
        try {
            recorder.format = "mp4"
            recorder.frameRate = BUG_VIDEO_FRAME_RATE
            recorder.videoCodec = avcodec.AV_CODEC_ID_H264
            recorder.videoBitrate = 2_000_000
            recorder.start()
            sourceFrames.forEach { sample ->
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

    private fun trimLocked(now: Long) {
        val cutoff = now - WINDOW_MILLIS
        while (actions.firstOrNull()?.timestampMillis?.let { it < cutoff } == true) actions.removeFirst()
        while (logs.firstOrNull()?.timestampMillis?.let { it < cutoff } == true) logs.removeFirst()
        while (frames.firstOrNull()?.timestampMillis?.let { it < cutoff } == true) frames.removeFirst()
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
    private data class TimestampedLogLine(val timestampMillis: Long, val line: String)
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
    )

    companion object {
        private const val WINDOW_MILLIS = 30_000L
        private const val BUG_VIDEO_FRAME_RATE = 15.0
        private const val SCREEN_POLL_MILLIS = 3_000L

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

internal object BugJson {
    fun writeActions(actions: List<BugAction>): String {
        return actions.joinToString(prefix = "{\"actions\":[", postfix = "]}\n") { action ->
            buildString {
                append("{")
                field("id", action.id)
                append(",\"timestampMillis\":${action.timestampMillis}")
                append(",")
                field("kind", action.kind)
                append(",")
                field("label", action.label)
                append(",\"detail\":")
                append(action.detail?.let(::quote) ?: "null")
                append("}")
            }
        }
    }

    fun writeReport(report: BugReport): String {
        return buildString {
            append("{")
            field("id", report.id)
            append(",")
            field("title", report.title)
            append(",")
            field("notes", report.notes)
            append(",")
            field("deviceSerial", report.deviceSerial)
            append(",\"deviceModel\":")
            append(report.deviceModel?.let(::quote) ?: "null")
            append(",\"apiLevel\":")
            append(report.apiLevel?.let(::quote) ?: "null")
            append(",\"abi\":")
            append(report.abi?.let(::quote) ?: "null")
            append(",\"resolution\":")
            append(report.resolution?.let(::quote) ?: "null")
            append(",\"capturedAtMillis\":${report.capturedAtMillis}")
            append(",\"windowStartedAtMillis\":${report.windowStartedAtMillis}")
            append(",\"windowEndedAtMillis\":${report.windowEndedAtMillis}")
            append(",\"videoStartedAtMillis\":")
            append(report.videoStartedAtMillis?.toString() ?: "null")
            append(",\"videoEndedAtMillis\":")
            append(report.videoEndedAtMillis?.toString() ?: "null")
            append(",\"videoFrameRate\":")
            append(report.videoFrameRate?.toString() ?: "null")
            append(",\"videoFrameTimestampsMillis\":[")
            append(report.videoFrameTimestampsMillis.joinToString(","))
            append("]")
            append(",\"actions\":")
            append(writeActions(report.actions).substringAfter('[').let { "[${it.substringBeforeLast(']')}]" })
            append(",\"artifacts\":[")
            append(report.artifacts.joinToString(",") { artifact ->
                buildString {
                    append("{")
                    field("name", artifact.name)
                    append(",")
                    field("relativePath", artifact.relativePath)
                    append(",")
                    field("kind", artifact.kind)
                    append(",\"sizeBytes\":")
                    append(artifact.sizeBytes?.toString() ?: "null")
                    append("}")
                }
            })
            append("]}\n")
        }
    }

    fun readReport(json: String): BugReport {
        val root = SimpleJsonParser(json).parse() as Map<*, *>
        val actions = (root["actions"] as? List<*>).orEmpty().mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            BugAction(
                id = map["id"] as? String ?: return@mapNotNull null,
                timestampMillis = (map["timestampMillis"] as? Number)?.toLong() ?: return@mapNotNull null,
                kind = map["kind"] as? String ?: "action",
                label = map["label"] as? String ?: "Action",
                detail = map["detail"] as? String,
            )
        }
        val artifacts = (root["artifacts"] as? List<*>).orEmpty().mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            BugArtifact(
                name = map["name"] as? String ?: return@mapNotNull null,
                relativePath = map["relativePath"] as? String ?: return@mapNotNull null,
                kind = map["kind"] as? String ?: "file",
                sizeBytes = (map["sizeBytes"] as? Number)?.toLong(),
            )
        }
        return BugReport(
            id = root["id"] as? String ?: error("Missing bug id"),
            title = root["title"] as? String ?: "Untitled bug",
            notes = root["notes"] as? String ?: "",
            deviceSerial = root["deviceSerial"] as? String ?: "unknown-device",
            deviceModel = root["deviceModel"] as? String,
            apiLevel = root["apiLevel"] as? String,
            abi = root["abi"] as? String,
            resolution = root["resolution"] as? String,
            capturedAtMillis = (root["capturedAtMillis"] as? Number)?.toLong() ?: 0L,
            windowStartedAtMillis = (root["windowStartedAtMillis"] as? Number)?.toLong() ?: 0L,
            windowEndedAtMillis = (root["windowEndedAtMillis"] as? Number)?.toLong() ?: 0L,
            actions = actions,
            artifacts = artifacts,
            videoStartedAtMillis = (root["videoStartedAtMillis"] as? Number)?.toLong(),
            videoEndedAtMillis = (root["videoEndedAtMillis"] as? Number)?.toLong(),
            videoFrameRate = (root["videoFrameRate"] as? Number)?.toDouble(),
            videoFrameTimestampsMillis = (root["videoFrameTimestampsMillis"] as? List<*>)
                .orEmpty()
                .mapNotNull { (it as? Number)?.toLong() },
        )
    }

    private fun StringBuilder.field(name: String, value: String) {
        append(quote(name))
        append(":")
        append(quote(value))
    }

    private fun quote(value: String): String {
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
}
