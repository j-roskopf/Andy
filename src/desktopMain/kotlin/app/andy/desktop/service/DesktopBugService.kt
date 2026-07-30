package app.andy.desktop.service

import app.andy.domain.BugReplayFps
import app.andy.domain.investigationTimelineFor
import app.andy.domain.toInvestigationEvent
import app.andy.desktop.service.mirror.DesktopMirrorEngine
import app.andy.desktop.service.mirror.GpuMirrorSessions
import app.andy.desktop.service.mirror.NativeMirrorJni
import app.andy.model.AndroidDevice
import app.andy.model.AccessibilityNode
import app.andy.model.BugAction
import app.andy.model.BugArtifact
import app.andy.model.BugCaptureDraft
import app.andy.model.BugCaptureStatus
import app.andy.model.BugReport
import app.andy.model.CrashRecord
import app.andy.model.InvestigationCaptureMode
import app.andy.model.InvestigationEvent
import app.andy.model.InvestigationReportSchemaVersion
import app.andy.model.InvestigationTimeline
import app.andy.model.InvestigationTimelineSchemaVersion
import app.andy.model.LogLevel
import app.andy.service.AccessibilityService
import app.andy.service.ActionConfigStore
import app.andy.service.AppService
import app.andy.service.BugService
import app.andy.service.CommandResult
import app.andy.service.CrashInspectorService
import app.andy.service.DeviceService
import app.andy.service.LogcatFilter
import app.andy.service.LogcatService
import app.andy.service.MetricsService
import app.andy.service.MirrorEngine
import app.andy.service.MirrorFrame
import app.andy.service.ProxyService
import app.andy.service.ViewHierarchyService
import app.andy.service.WorkspaceStore
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
import kotlinx.coroutines.withTimeoutOrNull
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
    private val proxy: ProxyService? = null,
    private val metrics: MetricsService? = null,
    private val crashInspector: CrashInspectorService? = null,
    private val viewHierarchy: ViewHierarchyService? = null,
    private val apps: AppService? = null,
    private val workspaceStore: WorkspaceStore? = null,
    private val actionConfig: ActionConfigStore? = null,
) : BugService {
    override val status = MutableStateFlow(BugCaptureStatus())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val actions = ArrayDeque<BugAction>()
    private val logs = ArrayDeque<TimestampedLogLine>()
    private val frames = ArrayDeque<TimestampedFrame>()
    private val h264Units = ArrayDeque<TimestampedH264>()
    private val networkEvents = LinkedHashMap<String, TimestampedNetworkEvent>()
    private val proxyWarningEvents = LinkedHashMap<String, TimestampedEvent>()
    private val metricEvents = ArrayDeque<TimestampedEvent>()
    private val crashRecords = LinkedHashMap<String, CrashRecord>()
    private val hierarchyEvents = ArrayDeque<TimestampedHierarchyEvent>()
    private val screenshotEvents = ArrayDeque<TimestampedScreenshotEvent>()
    private var latestH264Config: ByteArray? = null
    private var captureSerial: String? = null
    private var captureDevice: AndroidDevice? = null
    private var captureStartedAtMillis: Long = 0L
    private var recordingActive = false
    private var frameJob: Job? = null
    private var encodedJob: Job? = null
    private var logJob: Job? = null
    private var screenJob: Job? = null
    private var networkJob: Job? = null
    private var warningsJob: Job? = null
    private var metricsJob: Job? = null
    private var crashJob: Job? = null
    @Volatile private var lastFrameSampledAtMillis: Long = 0L

    private val bugsDir: File get() = File(homeDir, ".andy/bugs")
    private val exportsDir: File get() = File(homeDir, ".andy/exports")

    override suspend fun startCapture(serial: String, device: AndroidDevice?) {
        if (captureSerial == serial && status.value.active) return
        stopCapture()
        synchronized(lock) {
            clearCaptureLocked()
            captureSerial = serial
            captureDevice = device
            captureStartedAtMillis = System.currentTimeMillis()
            recordingActive = false
            lastFrameSampledAtMillis = 0L
        }
        status.value = BugCaptureStatus(active = true, deviceSerial = serial, message = "Recording last 30s for $serial")
        // Prefer the live Annex-B H.264 bitstream (full stream FPS). Keep a sparse, byte-capped
        // ARGB safety net even when H.264 looks healthy — remux still fails when SPS/PPS are
        // missing from the rolling window (scrcpy often sends them as separate AUs).
        encodedJob = scope.launch {
            val encoded = (mirror as? DesktopMirrorEngine)?.encodedVideo ?: mirror.encodedVideo
            encoded.collect { unit ->
                val now = unit.timestampMillis
                synchronized(lock) {
                    if (captureSerial == null) return@synchronized
                    if (isH264ConfigAccessUnit(unit.bytes)) {
                        // Scrcpy may send SPS and PPS as separate AUs; replacing would drop one.
                        latestH264Config = mergeH264Config(latestH264Config, unit.bytes)
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
                    delay(ARGB_POLL_MILLIS)
                    val now = System.currentTimeMillis()
                    if (recordingActive) {
                        // Native VideoToolbox mirroring keeps H.264 healthy for live display while MP4
                        // remux can still fail; sample decoded frames during explicit recordings.
                        if (now - lastFrameSampledAtMillis >= RECORDING_ARGB_SAMPLE_INTERVAL_MILLIS) {
                            sampleArgbBackup(now, lastCpuFrame)
                        }
                        continue
                    }
                    if (now - lastFrameSampledAtMillis < ARGB_FALLBACK_SAMPLE_INTERVAL_MILLIS) continue
                    // Sparse ARGB backup for bug capture when remux fails (missing SPS/PPS, etc.).
                    sampleArgbBackup(now, lastCpuFrame)
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
                    while (logs.size > InvestigationMaxLogLinesInRing) logs.removeFirst()
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
        networkJob = proxy?.let { proxyService ->
            scope.launch { collectNetworkExchanges(serial, proxyService) }
        }
        warningsJob = proxy?.let { proxyService ->
            scope.launch { collectProxyWarnings(serial, proxyService) }
        }
        metricsJob = metrics?.let { metricsService ->
            scope.launch { collectMetrics(serial, metricsService) }
        }
        crashJob = crashInspector?.let { inspector ->
            scope.launch { pollCrashes(serial, inspector) }
        }
        viewHierarchy?.let {
            scope.launch { captureHierarchySnapshot(serial, "start") }
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
        networkJob?.cancel()
        networkJob = null
        warningsJob?.cancel()
        warningsJob = null
        metricsJob?.cancel()
        metricsJob = null
        crashJob?.cancel()
        crashJob = null
        synchronized(lock) {
            clearCaptureLocked()
            captureSerial = null
            captureDevice = null
            captureStartedAtMillis = 0L
            recordingActive = false
        }
        status.value = BugCaptureStatus(message = "Bug capture idle")
    }

    override suspend fun beginRecording() {
        val serial = synchronized(lock) {
            checkNotNull(captureSerial) { "Connect to a device before recording." }
            // Keep SPS/PPS across the reset — scrcpy usually sends them once at stream start,
            // and wiping them here leaves picture AUs that FFmpeg cannot remux ("non-existing PPS").
            clearCaptureLocked(preserveH264Config = true)
            captureStartedAtMillis = System.currentTimeMillis()
            recordingActive = true
            captureSerial
        }
        synchronized(lock) {
            publishStatusLocked("Recording screen and inputs for $serial")
        }
    }

    override fun recordAction(kind: String, label: String, detail: String?) {
        appendAction(kind, label, detail)
    }

    override fun recordScreenshot(pngBytes: ByteArray, label: String, detail: String?) {
        if (pngBytes.size > InvestigationMaxScreenshotBytes) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val serial = captureSerial ?: return
            val idSuffix = "$now-${screenshotEvents.size}"
            val event = screenshotEvent(idSuffix, now, label, detail, pngBytes.size)
            screenshotEvents += TimestampedScreenshotEvent(now, event, pngBytes.copyOf())
            while (screenshotEvents.size > InvestigationMaxScreenshots) screenshotEvents.removeFirst()
            trimLocked(now)
            publishStatusLocked(
                if (recordingActive) "Recording screen and inputs for $serial" else "Recording last 30s for $serial",
            )
        }
    }

    override suspend fun loadBugTimeline(id: String): InvestigationTimeline? = withContext(Dispatchers.IO) {
        val file = File(File(bugsDir, id), InvestigationJson.TimelineRelativePath)
        if (!file.isFile) return@withContext null
        runCatching { InvestigationJson.readTimeline(file.readText()) }.getOrNull()
    }

    private fun sampleArgbBackup(now: Long, lastCpuFrame: MirrorFrame?) {
        val sampled = when {
            recordingActive -> copyDecodedArgbBackup() ?: lastCpuFrame?.let { it.copy(argb = it.argb.copyOf()) }
            else -> lastCpuFrame?.let { it.copy(argb = it.argb.copyOf()) } ?: copyDecodedArgbBackup()
        } ?: return
        synchronized(lock) {
            if (captureSerial == null) return
            val minInterval = if (recordingActive) {
                RECORDING_ARGB_SAMPLE_INTERVAL_MILLIS
            } else {
                ARGB_FALLBACK_SAMPLE_INTERVAL_MILLIS
            }
            if (now - lastFrameSampledAtMillis < minInterval) return
            lastFrameSampledAtMillis = now
            frames += TimestampedFrame(now, sampled)
            trimLocked(now)
            val serial = captureSerial ?: return
            publishStatusLocked(
                if (recordingActive) "Recording screen and inputs for $serial" else "Recording last 30s for $serial",
            )
        }
    }

    /**
     * Prefer the active GPU-hub decoder's latest pixels (Live's default macOS path). Fall back to
     * the legacy singleton renderer used by older CPU / inline Metal presentation.
     */
    private fun copyDecodedArgbBackup(): MirrorFrame? {
        val serial = captureSerial
        if (serial != null) {
            GpuMirrorSessions.get(serial)?.copyLatestFrameArgb()?.let { return it }
        }
        return NativeMirrorJni.copyLatestFrameArgb()
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
                val transitioned = when {
                    last == null -> {
                        appendAction("screen", "Screen ${screen.shortActivityName}", screen.detail)
                        true
                    }
                    last.packageName != screen.packageName -> {
                        appendAction("screen", "Launch ${screen.packageName}", screen.detail)
                        true
                    }
                    last.activityName != screen.activityName || last.fragments != screen.fragments -> {
                        appendAction("screen", "Screen ${screen.shortActivityName}", screen.detail)
                        true
                    }
                    last.semanticSignature != null && last.semanticSignature != screen.semanticSignature -> {
                        appendAction("screen", "Screen ${screen.semanticTitle ?: screen.shortActivityName}", screen.detail)
                        true
                    }
                    else -> false
                }
                if (transitioned) captureHierarchySnapshot(serial, "screen")
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

    /** Subscribes to proxy exchanges, deduping by exchange id. Never claims package ownership. */
    private suspend fun collectNetworkExchanges(serial: String, proxyService: ProxyService) {
        proxyService.exchanges.collect { exchanges ->
            val now = System.currentTimeMillis()
            synchronized(lock) {
                if (captureSerial == null) return@synchronized
                exchanges.forEach { exchange ->
                    val (event, sidecar) = networkEventAndSidecar(exchange)
                    networkEvents[exchange.id] = TimestampedNetworkEvent(event.atMillis, event, sidecar)
                }
                while (networkEvents.size > InvestigationMaxNetworkEvents) {
                    val oldestKey = networkEvents.keys.firstOrNull() ?: break
                    networkEvents.remove(oldestKey)
                }
                trimLocked(now)
            }
        }
    }

    private suspend fun collectProxyWarnings(serial: String, proxyService: ProxyService) {
        proxyService.warnings.collect { warnings ->
            val now = System.currentTimeMillis()
            synchronized(lock) {
                if (captureSerial == null) return@synchronized
                warnings.forEach { warning ->
                    proxyWarningEvents[warning.id] = TimestampedEvent(warning.atMillis, proxyWarningEvent(warning))
                }
                while (proxyWarningEvents.size > InvestigationMaxProxyWarnings) {
                    val oldestKey = proxyWarningEvents.keys.firstOrNull() ?: break
                    proxyWarningEvents.remove(oldestKey)
                }
                trimLocked(now)
            }
        }
    }

    /** Refreshes the focused package periodically since [MetricsService.stream] cannot re-target mid-collection. */
    private suspend fun collectMetrics(serial: String, metricsService: MetricsService) {
        while (currentCoroutineContext().isActive) {
            val focusedPackage = apps?.let { runCatching { it.focusedPackage(serial) }.getOrNull() }
            withTimeoutOrNull(InvestigationMetricsRefreshIntervalMillis) {
                metricsService.stream(serial, focusedPackage).collect { sample ->
                    val now = System.currentTimeMillis()
                    synchronized(lock) {
                        if (captureSerial == null) return@synchronized
                        metricEvents += TimestampedEvent(sample.timestampMillis, metricSampleEvent(sample))
                        while (metricEvents.size > InvestigationMaxMetricSamples) metricEvents.removeFirst()
                        trimLocked(now)
                    }
                }
            }
        }
    }

    private suspend fun pollCrashes(serial: String, inspector: CrashInspectorService) {
        // First poll seeds IDs already on the device so we only emit crashes that appear
        // during this capture window (dropbox lists historical entries every time).
        var baselineSeeded = false
        val seenIds = linkedSetOf<String>()
        while (currentCoroutineContext().isActive) {
            val records = runCatching { inspector.listCrashes(serial) }.getOrDefault(emptyList())
            var newCrashObserved = false
            synchronized(lock) {
                if (captureSerial != null) {
                    if (!baselineSeeded) {
                        seenIds += records.map { it.id }
                        baselineSeeded = true
                    } else {
                        val observedAt = System.currentTimeMillis()
                        records.forEach { record ->
                            if (seenIds.add(record.id) && !crashRecords.containsKey(record.id)) {
                                crashRecords[record.id] = record.copy(
                                    timestampMillis = record.timestampMillis.takeIf { it > 0L } ?: observedAt,
                                )
                                newCrashObserved = true
                            }
                        }
                        while (crashRecords.size > InvestigationMaxCrashEvents) {
                            val oldestKey = crashRecords.keys.firstOrNull() ?: break
                            crashRecords.remove(oldestKey)
                        }
                        trimLocked(observedAt)
                    }
                }
            }
            if (newCrashObserved) captureHierarchySnapshot(serial, "crash")
            delay(InvestigationCrashPollIntervalMillis)
        }
    }

    /** Captured at investigation start, on screen transitions, and when a new crash is observed. */
    private suspend fun captureHierarchySnapshot(serial: String, reason: String) {
        val service = viewHierarchy ?: return
        val now = System.currentTimeMillis()
        val result = runCatching { service.capture(serial) }.getOrElse { Result.failure(it) }
        synchronized(lock) {
            if (captureSerial == null) return@synchronized
            val (event, sidecar) = result.fold(
                onSuccess = { snapshot -> hierarchySuccessEventAndSidecar(snapshot, now, reason) },
                onFailure = { error -> hierarchyErrorEvent(now, reason, error.message ?: error.toString()) to null },
            )
            hierarchyEvents += TimestampedHierarchyEvent(now, event, sidecar)
            while (hierarchyEvents.size > InvestigationMaxHierarchyEvents) hierarchyEvents.removeFirst()
            trimLocked(now)
        }
    }

    override suspend fun saveBug(draft: BugCaptureDraft, device: AndroidDevice?): BugReport = saveCapture(
        draft = draft,
        device = device,
        idPrefix = "bug-",
    )

    override suspend fun saveRecording(device: AndroidDevice?): BugReport {
        val report = saveCapture(
            draft = BugCaptureDraft(title = "Screen recording"),
            device = device,
            idPrefix = "recording-",
        )
        synchronized(lock) {
            // Resume the rolling bug window with the live stream's parameter sets intact.
            clearCaptureLocked(preserveH264Config = true)
            captureStartedAtMillis = System.currentTimeMillis()
            recordingActive = false
            publishStatusLocked("Bug capture ready")
        }
        return report
    }

    private suspend fun saveCapture(
        draft: BugCaptureDraft,
        device: AndroidDevice?,
        idPrefix: String,
    ): BugReport = withContext(Dispatchers.IO) {
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
                recordingActive = recordingActive,
                networkEvents = networkEvents.values.toList(),
                proxyWarningEvents = proxyWarningEvents.values.toList(),
                metricEvents = metricEvents.toList(),
                crashRecords = crashRecords.values.toList(),
                hierarchyEvents = hierarchyEvents.toList(),
                screenshotEvents = screenshotEvents.toList(),
            )
        }
        val reportId = "$idPrefix$now"
        val reportDir = File(bugsDir, reportId).apply { mkdirs() }
        val captureFile = File(reportDir, "capture.mp4")
        val logFile = File(reportDir, "logcat.txt")
        val actionsFile = File(reportDir, "actions.json")
        val metadataFile = File(reportDir, "metadata.json")
        val timelineFile = File(reportDir, InvestigationJson.TimelineRelativePath)

        logFile.writeText(snapshot.logs.joinToString("\n") { it.line } + if (snapshot.logs.isNotEmpty()) "\n" else "")
        actionsFile.writeText(BugJson.writeActions(snapshot.actions))
        val videoMeta = encodeCaptureVideo(snapshot, captureFile)
        if (videoMeta.warning != null) {
            logCaptureIssue(
                "saveCapture($reportId) saved without playable video: ${videoMeta.warning} " +
                    "[h264Units=${snapshot.h264Units.size}, argbFrames=${snapshot.frames.size}, " +
                    "fileBytes=${captureFile.length()}]",
            )
        }

        // Crash sidecars load lazily here (full stack text) so the polling ring stays light.
        // A load failure must not fail the save — the event survives without a payloadRef.
        // Also synthesize Crash events from AndroidRuntime FATAL stacks in logcat — dropbox is
        // often empty/laggy on production devices, which is exactly when users hit crash buttons.
        val dropboxCrashResults = snapshot.crashRecords.map { record ->
            val text = crashInspector?.let { inspector ->
                runCatching { inspector.loadCrash(snapshot.serial, record.id) }.getOrNull()
            }
            record to text?.takeIf { it.isNotBlank() }?.let { crashSidecar(record, it) }
        }
        val logcatCrashResults = extractFatalExceptionsFromLogs(snapshot.logs)
        val crashResults = dropboxCrashResults + logcatCrashResults.map { (record, sidecar) -> record to sidecar }
        val timeline = buildInvestigationTimeline(snapshot, videoMeta, crashResults, now)
        timelineFile.writeText(InvestigationJson.writeTimeline(timeline))
        writeInvestigationSidecars(reportDir, snapshot, crashResults)

        val artifacts = listOf(
            BugArtifact("actions.json", "actions.json", "actions", actionsFile.length()),
            BugArtifact("logcat.txt", "logcat.txt", "logcat", logFile.length()),
            BugArtifact("capture.mp4", "capture.mp4", "video", captureFile.length()),
            BugArtifact("timeline.json", InvestigationJson.TimelineRelativePath, "timeline", timelineFile.length()),
            BugArtifact("metadata.json", "metadata.json", "metadata", null),
        )
        val windowStart = timeline.originMillis
        val appIdentity = runCatching { resolveAppIdentity(apps, snapshot.serial) }.getOrNull()
        val projectIdentity = runCatching { resolveProjectIdentity(workspaceStore, actionConfig) }.getOrNull()
        val captureMode = if (snapshot.recordingActive) InvestigationCaptureMode.Recording else InvestigationCaptureMode.Rolling
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
            videoCaptureWarning = videoMeta.warning,
            schemaVersion = InvestigationReportSchemaVersion,
            timelineRelativePath = InvestigationJson.TimelineRelativePath,
            captureMode = captureMode,
            appIdentity = appIdentity,
            projectIdentity = projectIdentity,
            hostIdentity = hostIdentity(),
        )
        metadataFile.writeText(BugJson.writeReport(report.copy(artifacts = artifacts.map {
            if (it.name == "metadata.json") it.copy(sizeBytes = metadataFile.length()) else it
        })))
        report
    }

    /** Merges all capture rings into a single sorted timeline; a `LogLine` cap keeps the index compact. */
    private fun buildInvestigationTimeline(
        snapshot: BugSnapshot,
        videoMeta: VideoEncodeMeta,
        crashResults: List<Pair<CrashRecord, CrashEventSidecarDto?>>,
        now: Long,
    ): InvestigationTimeline {
        val actionEvents = snapshot.actions.map { it.toInvestigationEvent() }
        val logEvents = selectLogLinesForTimeline(snapshot.logs).mapIndexed { index, line ->
            logLineEvent(index, line.timestampMillis, line.line)
        }
        val networkTimelineEvents = snapshot.networkEvents.map { it.event }
        val warningTimelineEvents = snapshot.proxyWarningEvents.map { it.event }
        val metricTimelineEvents = snapshot.metricEvents.map { it.event }
        val crashTimelineEvents = crashResults.map { (record, sidecar) -> crashEvent(record, hasSidecar = sidecar != null) }
        val hierarchyTimelineEvents = snapshot.hierarchyEvents.map { it.event }
        val screenshotTimelineEvents = snapshot.screenshotEvents.map { it.event }

        val allEvents = (
            actionEvents + logEvents + networkTimelineEvents + warningTimelineEvents +
                metricTimelineEvents + crashTimelineEvents + hierarchyTimelineEvents + screenshotTimelineEvents
            ).sortedBy(InvestigationEvent::atMillis)

        val originMillis = listOfNotNull(
            snapshot.actions.minOfOrNull { it.timestampMillis },
            snapshot.logs.minOfOrNull { it.timestampMillis },
            snapshot.networkEvents.minOfOrNull { it.atMillis },
            snapshot.proxyWarningEvents.minOfOrNull { it.atMillis },
            snapshot.metricEvents.minOfOrNull { it.atMillis },
            snapshot.crashRecords.minOfOrNull { it.timestampMillis },
            snapshot.hierarchyEvents.minOfOrNull { it.atMillis },
            snapshot.screenshotEvents.minOfOrNull { it.atMillis },
            videoMeta.startedAtMillis,
        ).minOrNull() ?: max(snapshot.startedAtMillis, now - WINDOW_MILLIS)

        return InvestigationTimeline(
            schemaVersion = InvestigationTimelineSchemaVersion,
            originMillis = originMillis,
            endedAtMillis = now,
            events = allEvents,
        )
    }

    /** Writes sidecars alongside `timeline.json`. Each write is independently best-effort. */
    private fun writeInvestigationSidecars(
        reportDir: File,
        snapshot: BugSnapshot,
        crashResults: List<Pair<CrashRecord, CrashEventSidecarDto?>>,
    ) {
        if (snapshot.networkEvents.isNotEmpty()) {
            val dir = File(reportDir, InvestigationJson.EventsNetworkDir).apply { mkdirs() }
            snapshot.networkEvents.forEach { entry ->
                runCatching { File(dir, "${entry.event.id}.json").writeText(InvestigationJson.writeNetworkSidecar(entry.sidecar)) }
            }
        }
        val crashSidecars = crashResults.mapNotNull { (record, sidecar) -> sidecar?.let { record.id to it } }
        if (crashSidecars.isNotEmpty()) {
            val dir = File(reportDir, InvestigationJson.EventsCrashesDir).apply { mkdirs() }
            crashSidecars.forEach { (crashId, sidecar) ->
                runCatching { File(dir, "crash-$crashId.json").writeText(InvestigationJson.writeCrashSidecar(sidecar)) }
            }
        }
        val hierarchySidecars = snapshot.hierarchyEvents.mapNotNull { entry -> entry.sidecar?.let { entry.event.id to it } }
        if (hierarchySidecars.isNotEmpty()) {
            val dir = File(reportDir, InvestigationJson.EventsHierarchyDir).apply { mkdirs() }
            hierarchySidecars.forEach { (id, sidecar) ->
                runCatching { File(dir, "$id.json").writeText(InvestigationJson.writeHierarchySidecar(sidecar)) }
            }
        }
        if (snapshot.screenshotEvents.isNotEmpty()) {
            val dir = File(reportDir, InvestigationJson.EventsScreenshotsDir).apply { mkdirs() }
            snapshot.screenshotEvents.forEach { entry ->
                runCatching { File(dir, "${entry.event.id}.png").writeBytes(entry.pngBytes) }
            }
        }
    }

    override suspend fun listBugs(): List<BugReport> = withContext(Dispatchers.IO) {
        listReports("bug-")
    }

    override suspend fun listRecordings(): List<BugReport> = withContext(Dispatchers.IO) {
        listReports("recording-")
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

    /**
     * Folder-based investigation bundle (§4): a duplicate of the report directory plus
     * `manifest.json` and `summary.md`, so a bundle can be shared without a zip dependency.
     * `timeline.json` is backfilled for v1 reports that only ever had `actions.json`.
     */
    override suspend fun exportInvestigationBundle(id: String): String? = withContext(Dispatchers.IO) {
        val source = File(bugsDir, id).takeIf { it.isDirectory } ?: return@withContext null
        val report = readReport(source) ?: return@withContext null
        val timeline = loadBugTimeline(id) ?: investigationTimelineFor(report, null)
        val target = File(exportsDir, "$id-bundle")
        if (target.exists()) target.deleteRecursively()
        copyDirectory(source, target)
        val timelineFile = File(target, InvestigationJson.TimelineRelativePath)
        if (!timelineFile.isFile) timelineFile.writeText(InvestigationJson.writeTimeline(timeline))
        File(target, "manifest.json").writeText(writeInvestigationBundleManifest(buildInvestigationBundleManifest(report, timeline)))
        File(target, "summary.md").writeText(buildInvestigationBundleSummaryMarkdown(report, timeline))
        target.absolutePath
    }

    override suspend fun revealBug(id: String): CommandResult = withContext(Dispatchers.IO) {
        val dir = File(bugsDir, id)
        if (!dir.isDirectory) return@withContext CommandResult.failure("Report not found: $id")
        val target = File(dir, "capture.mp4").takeIf { it.isFile } ?: dir
        runCatching {
            val desktop = java.awt.Desktop.getDesktop()
            if (java.awt.Desktop.isDesktopSupported() && desktop.isSupported(java.awt.Desktop.Action.BROWSE_FILE_DIR)) {
                desktop.browseFileDirectory(target)
            } else {
                desktop.open(dir)
            }
            CommandResult.success(dir.absolutePath)
        }.getOrElse { CommandResult.failure(it.message ?: "Reveal failed") }
    }

    override suspend fun bugDirectoryPath(id: String): String? = withContext(Dispatchers.IO) {
        File(bugsDir, id).takeIf { it.isDirectory }?.absolutePath
    }

    override suspend fun renameBug(id: String, title: String): CommandResult = withContext(Dispatchers.IO) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return@withContext CommandResult.failure("Title is required")
        val reportDir = File(bugsDir, id)
        val report = readReport(reportDir) ?: return@withContext CommandResult.failure("Report not found: $id")
        val metadataFile = File(reportDir, "metadata.json")
        runCatching {
            metadataFile.writeText(BugJson.writeReport(report.copy(title = trimmed)))
            CommandResult.success(trimmed)
        }.getOrElse { CommandResult.failure(it.message ?: "Rename failed") }
    }

    override fun playbackFrames(id: String, startFrameIndex: Int): Flow<MirrorFrame> = flow {
        val report = readReport(File(bugsDir, id))
        val file = File(File(bugsDir, id), "capture.mp4")
        if (!file.isFile || file.length() == 0L) return@flow
        val grabber = FFmpegFrameGrabber(file)
        val converter = Java2DFrameConverter()
        try {
            grabber.start()
            val fps = saneFrameRate(grabber.frameRate)
                ?: report?.videoFrameRate?.let(::saneFrameRate)
                ?: BugReplayFps
            val frameBudgetMillis = (1000.0 / fps).toLong().coerceIn(8L, 100L)
            val startIndex = startFrameIndex.coerceAtLeast(0)
            if (!seekToVideoFrame(grabber, file, startIndex)) return@flow
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
        } catch (error: Throwable) {
            // Older captures may have been written by the packet-copy path as a header-only MP4.
            // Preserve their logs/actions and leave the replay surface empty instead of failing a
            // Compose collector on the AWT event thread.
            logCaptureIssue("playbackFrames($id) failed to decode capture.mp4", error)
            return@flow
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
            converter.close()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun bugVideoFrameCount(id: String): Int = withContext(Dispatchers.IO) {
        val reportDir = File(bugsDir, id)
        val file = File(reportDir, "capture.mp4")
        if (!file.isFile || file.length() == 0L) return@withContext 0
        val metaCount = readReport(reportDir)?.videoFrameTimestampsMillis?.size?.takeIf { it > 0 }
        val grabber = FFmpegFrameGrabber(file)
        try {
            grabber.start()
            val fps = saneFrameRate(grabber.frameRate)
            if (fps != null) {
                metaCount
                    ?: grabber.lengthInVideoFrames.takeIf { it > 0 }
                    ?: grabber.lengthInFrames.coerceAtLeast(0)
            } else {
                // Absurd container fps (e.g. 1001k from broken packet-copy): count by decoding.
                var count = 0
                while (grabber.grabImage() != null) count++
                count.takeIf { it > 0 } ?: metaCount ?: 0
            }
        } catch (error: Throwable) {
            logCaptureIssue("bugVideoFrameCount($id) failed to read capture.mp4", error)
            metaCount ?: 0
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
            val target = frameIndex.coerceAtLeast(0)
            if (!seekToVideoFrame(grabber, file, target)) return@withContext null
            val image = grabber.grabImage()?.let(converter::convert)
            image?.toMirrorFrame(target.toLong() + 1)
        } catch (error: Throwable) {
            logCaptureIssue("loadBugVideoFrame($id, $frameIndex) failed to decode capture.mp4", error)
            null
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
            converter.close()
        }
    }

    /**
     * Packet-copy remuxes often advertise a nonsense timebase (~1001k fps). Seeking by frame then
     * fails with avformat_seek_file; walk forward from the start instead.
     */
    private fun seekToVideoFrame(grabber: FFmpegFrameGrabber, file: File, frameIndex: Int): Boolean {
        if (frameIndex <= 0) return true
        val sought = runCatching {
            grabber.setVideoFrameNumber(frameIndex)
            true
        }.getOrDefault(false)
        if (sought) return true
        runCatching { grabber.stop() }
        return try {
            grabber.start()
            repeat(frameIndex) {
                if (grabber.grabImage() == null) return false
            }
            true
        } catch (error: Throwable) {
            logCaptureIssue(
                "seekToVideoFrame(${file.name}, $frameIndex) sequential scan failed",
                error,
            )
            false
        }
    }

    /** Container fps usable for pacing / validation; rejects packet-copy 1001k-style garbage. */
    private fun saneFrameRate(fps: Double?): Double? =
        fps?.takeIf { it.isFinite() && it in MIN_SANE_FRAME_RATE..MAX_SANE_FRAME_RATE }

    private fun readReport(reportDir: File): BugReport? {
        val metadata = File(reportDir, "metadata.json")
        if (!metadata.isFile) return null
        return runCatching { BugJson.readReport(metadata.readText()) }.getOrNull()
    }

    private fun logCaptureIssue(message: String, error: Throwable? = null) {
        val suffix = error?.let { " (${it::class.simpleName}: ${it.message})" }.orEmpty()
        System.err.println("andy-bug: $message$suffix")
        error?.printStackTrace()
    }

    private fun encodeCaptureVideo(snapshot: BugSnapshot, captureFile: File): VideoEncodeMeta {
        if (snapshot.h264Units.isNotEmpty()) {
            val meta = remuxH264Mp4(snapshot.h264Units, snapshot.h264Config, captureFile)
            if (isPlayableCapture(captureFile) && !shouldPreferArgbBackup(snapshot, meta)) {
                return meta
            }
        }
        captureFile.delete()
        encodeArgbMp4(snapshot.frames, captureFile)
        val spanMillis = snapshot.frames.let { frames ->
            if (frames.size < 2) return@let null
            frames.last().timestampMillis - frames.first().timestampMillis
        }
        val frameRate = if (spanMillis != null && spanMillis > 0L) {
            (snapshot.frames.size * 1000.0 / spanMillis).coerceIn(2.0, 60.0)
        } else {
            ARGB_FALLBACK_FRAME_RATE
        }
        val warning = if (isPlayableCapture(captureFile)) {
            null
        } else if (snapshot.h264Units.isEmpty() && snapshot.frames.isEmpty()) {
            "No video frames were captured for this device."
        } else {
            "Video failed to encode; report saved without playable video."
        }
        return VideoEncodeMeta(
            frameRate = frameRate,
            startedAtMillis = snapshot.frames.firstOrNull()?.timestampMillis,
            endedAtMillis = snapshot.frames.lastOrNull()?.timestampMillis,
            timestampsMillis = snapshot.frames.map { it.timestampMillis },
            warning = warning,
        )
    }

    /** Prefer ARGB when the bitstream remux is playable but shorter than the sampled backup. */
    private fun shouldPreferArgbBackup(snapshot: BugSnapshot, h264Meta: VideoEncodeMeta): Boolean {
        if (snapshot.frames.size < 2) return false
        val h264Span = ((h264Meta.endedAtMillis ?: 0L) - (h264Meta.startedAtMillis ?: 0L)).coerceAtLeast(0L)
        val argbSpan = (
            snapshot.frames.last().timestampMillis - snapshot.frames.first().timestampMillis
            ).coerceAtLeast(0L)
        return argbSpan > h264Span + 1_000L
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
        val spanMillis = (usable.last().timestampMillis - usable.first().timestampMillis).coerceAtLeast(1L)
        val frameRate = if (usable.size >= 2) {
            (usable.size * 1000.0 / spanMillis).coerceIn(2.0, 60.0)
        } else {
            ARGB_FALLBACK_FRAME_RATE
        }
        val recorder = FFmpegFrameRecorder(file, width, height)
        val converter = Java2DFrameConverter()
        try {
            configureSoftwareH264(recorder, frameRate, 4_000_000)
            recorder.start()
            usable.forEach { sample ->
                if (sample.frame.width != width || sample.frame.height != height) return@forEach
                recorder.record(converter.convert(sample.frame.toBufferedImage()))
            }
        } catch (error: Throwable) {
            logCaptureIssue("encodeArgbMp4 failed for ${usable.size} frames at ${width}x$height", error)
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
        // Drop leading non-IDR pictures so the MP4 starts on a keyframe (Record often begins
        // mid-GOP after we cleared the rolling window but kept SPS/PPS).
        val alignedUnits = unitsStartingAtIdr(units)
        val width = alignedUnits.last().width.coerceAtLeast(2)
        val height = alignedUnits.last().height.coerceAtLeast(2)
        val pictureUnits = alignedUnits.filter { isH264PictureAccessUnit(it.bytes) }
        val started = (pictureUnits.firstOrNull() ?: alignedUnits.first()).timestampMillis
        val ended = (pictureUnits.lastOrNull() ?: alignedUnits.last()).timestampMillis
        val durationMillis = (ended - started).coerceAtLeast(1L)
        val pictureCount = pictureUnits.size.coerceAtLeast(1)
        val estimatedFps = (pictureCount * 1000.0 / durationMillis).coerceIn(15.0, 120.0)
        val raw = File(file.parentFile, "capture-raw.h264")
        val meta = VideoEncodeMeta(
            frameRate = estimatedFps,
            startedAtMillis = started,
            endedAtMillis = ended,
            // Prefer picture timestamps so replay pacing matches decoded frames, not SPS/PPS AUs.
            timestampsMillis = (pictureUnits.ifEmpty { alignedUnits }).map { it.timestampMillis },
        )
        try {
            val resolvedConfig = resolveH264Config(alignedUnits, config)
            raw.outputStream().use { out ->
                // Always lead with merged SPS/PPS. Rolling bug windows often trim the original
                // config AUs, and unitsStartingAtIdr drops everything before the keyframe.
                if (resolvedConfig != null) {
                    out.write(resolvedConfig)
                }
                alignedUnits
                    .dropWhile { isH264ConfigAccessUnit(it.bytes) && !isH264PictureAccessUnit(it.bytes) }
                    .forEach { out.write(it.bytes) }
            }
            if (raw.length() == 0L) {
                file.writeBytes(ByteArray(0))
                return meta
            }
            if (resolvedConfig == null) {
                logCaptureIssue(
                    "remuxH264Mp4 has ${alignedUnits.size} units but no SPS/PPS — " +
                        "packet/transcode will likely fail; ARGB backup should cover",
                )
            }
            // Prefer bitstream copy (full live FPS/quality). OpenH264 re-encode is fallback only —
            // the bundled FFmpeg has no libx264, and VT H.264 encode races the live decoder.
            packetCopyH264(raw, file, width, height, estimatedFps)
            // Packet copy can leave a tiny MP4 with a `moov` box but no video track, or an MP4
            // that decodes frame 0 but advertises ~1001k fps / ~0 duration (unseekable).
            if (!isSeekableCapture(file, estimatedFps)) {
                file.delete()
                transcodeH264(raw, file, width, height, estimatedFps)
            }
        } catch (error: Throwable) {
            logCaptureIssue("remuxH264Mp4 failed for ${alignedUnits.size} units at ${width}x$height", error)
            file.writeBytes(ByteArray(0))
        } finally {
            raw.delete()
        }
        return meta
    }

    /** Merge retained config with any SPS/PPS still present in the capture window. */
    private fun resolveH264Config(units: List<TimestampedH264>, config: ByteArray?): ByteArray? {
        var merged = config
        units.forEach { unit ->
            if (isH264ConfigAccessUnit(unit.bytes)) {
                merged = mergeH264Config(merged, unit.bytes)
            }
        }
        return merged?.takeIf { isH264ConfigAccessUnit(it) }
    }

    /** Drop units before the first IDR so remux/transcode start on a clean keyframe. */
    private fun unitsStartingAtIdr(units: List<TimestampedH264>): List<TimestampedH264> {
        val idrIndex = units.indexOfFirst { isH264IdrAccessUnit(it.bytes) }
        return if (idrIndex <= 0) units else units.drop(idrIndex)
    }

    private fun isPlayableCapture(file: File): Boolean = isSeekableCapture(file, expectedFps = null)

    /**
     * Decodes frame 0 and rejects packet-copy containers with nonsense timing that break scrubbing
     * (`setVideoFrameNumber` → avformat_seek_file fails).
     */
    private fun isSeekableCapture(file: File, expectedFps: Double?): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val grabber = FFmpegFrameGrabber(file)
        val converter = Java2DFrameConverter()
        return try {
            grabber.start()
            val first = generateSequence { grabber.grabImage() }.firstOrNull()?.let(converter::convert)
            if (first == null) return false
            val fps = grabber.frameRate
            if (saneFrameRate(fps) == null) {
                logCaptureIssue(
                    "isSeekableCapture(${file.name}) rejected absurd frameRate=$fps " +
                        "(expected≈${expectedFps ?: "?"})",
                )
                return false
            }
            val frameCount = grabber.lengthInVideoFrames.takeIf { it > 0 } ?: grabber.lengthInFrames
            if (frameCount <= 2) return true
            val mid = (frameCount / 2).coerceAtLeast(1)
            runCatching {
                grabber.setVideoFrameNumber(mid)
                grabber.grabImage() != null
            }.getOrElse { error ->
                logCaptureIssue(
                    "isSeekableCapture(${file.name}) mid-seek to frame $mid failed",
                    error,
                )
                false
            }
        } catch (error: Throwable) {
            logCaptureIssue("isSeekableCapture(${file.name}, ${file.length()} bytes) failed to decode", error)
            false
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
            converter.close()
        }
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
                    logCaptureIssue("packetCopyH264 copied 0 packets from ${raw.length()}-byte raw stream")
                    file.writeBytes(ByteArray(0))
                }
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
        } catch (error: Throwable) {
            logCaptureIssue("packetCopyH264 failed at ${width}x$height", error)
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
                    logCaptureIssue("transcodeH264 recorded 0 frames from ${raw.length()}-byte raw stream")
                    file.writeBytes(ByteArray(0))
                }
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
        } catch (error: Throwable) {
            logCaptureIssue("transcodeH264 failed at ${width}x$height", error)
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
        if (recordingActive) return
        val cutoff = now - WINDOW_MILLIS
        while (actions.firstOrNull()?.timestampMillis?.let { it < cutoff } == true) actions.removeFirst()
        while (logs.firstOrNull()?.timestampMillis?.let { it < cutoff } == true) logs.removeFirst()
        while (frames.firstOrNull()?.timestampMillis?.let { it < cutoff } == true) frames.removeFirst()
        while (h264Units.firstOrNull()?.timestampMillis?.let { it < cutoff } == true) h264Units.removeFirst()
        trimArgbBudgetLocked()
        trimInvestigationRingsLocked(cutoff)
    }

    private fun trimInvestigationRingsLocked(cutoff: Long) {
        networkEvents.entries.removeAll { it.value.atMillis < cutoff }
        proxyWarningEvents.entries.removeAll { it.value.atMillis < cutoff }
        while (metricEvents.firstOrNull()?.atMillis?.let { it < cutoff } == true) metricEvents.removeFirst()
        crashRecords.entries.removeAll { it.value.timestampMillis < cutoff }
        while (hierarchyEvents.firstOrNull()?.atMillis?.let { it < cutoff } == true) hierarchyEvents.removeFirst()
        while (screenshotEvents.firstOrNull()?.atMillis?.let { it < cutoff } == true) screenshotEvents.removeFirst()
    }

    /** Test hook: force a trim without waiting on the wall clock. */
    internal fun forceTrimForTest(now: Long) = synchronized(lock) { trimLocked(now) }

    /** Test hook: current size of each investigation capture ring, for cap/trim assertions. */
    internal fun investigationRingSizesForTest(): Map<String, Int> = synchronized(lock) {
        mapOf(
            "network" to networkEvents.size,
            "proxyWarnings" to proxyWarningEvents.size,
            "metrics" to metricEvents.size,
            "crashes" to crashRecords.size,
            "hierarchy" to hierarchyEvents.size,
            "screenshots" to screenshotEvents.size,
        )
    }

    /** Cap ARGB backup so soft-decode fallback cannot grow into multi‑GB rings. */
    private fun trimArgbBudgetLocked() {
        var totalBytes = frames.sumOf { it.frame.argb.size.toLong() * Int.SIZE_BYTES }
        while (totalBytes > ARGB_MAX_BYTES && frames.isNotEmpty()) {
            val removed = frames.removeFirst()
            totalBytes -= removed.frame.argb.size.toLong() * Int.SIZE_BYTES
        }
    }

    private fun clearCaptureLocked(preserveH264Config: Boolean = false) {
        actions.clear()
        logs.clear()
        frames.clear()
        h264Units.clear()
        networkEvents.clear()
        proxyWarningEvents.clear()
        metricEvents.clear()
        crashRecords.clear()
        hierarchyEvents.clear()
        screenshotEvents.clear()
        if (!preserveH264Config) {
            latestH264Config = null
        }
        lastFrameSampledAtMillis = 0L
    }

    /** Test hook: SPS/PPS retained for remux after [beginRecording] clears picture AUs. */
    internal fun latestH264ConfigSizeForTest(): Int = synchronized(lock) { latestH264Config?.size ?: 0 }

    private fun listReports(idPrefix: String): List<BugReport> = bugsDir.listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory && it.name.startsWith(idPrefix) }
        ?.mapNotNull(::readReport)
        ?.sortedByDescending { it.capturedAtMillis }
        ?.toList()
        ?: emptyList()

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
        followOnly = true,
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
    private data class TimestampedNetworkEvent(val atMillis: Long, val event: InvestigationEvent, val sidecar: NetworkEventSidecarDto)
    private data class TimestampedEvent(val atMillis: Long, val event: InvestigationEvent)
    private data class TimestampedHierarchyEvent(val atMillis: Long, val event: InvestigationEvent, val sidecar: HierarchyEventSidecarDto?)
    private data class TimestampedScreenshotEvent(val atMillis: Long, val event: InvestigationEvent, val pngBytes: ByteArray)
    private data class VideoEncodeMeta(
        val frameRate: Double,
        val startedAtMillis: Long?,
        val endedAtMillis: Long?,
        val timestampsMillis: List<Long>,
        val warning: String? = null,
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
        val recordingActive: Boolean,
        val networkEvents: List<TimestampedNetworkEvent>,
        val proxyWarningEvents: List<TimestampedEvent>,
        val metricEvents: List<TimestampedEvent>,
        val crashRecords: List<CrashRecord>,
        val hierarchyEvents: List<TimestampedHierarchyEvent>,
        val screenshotEvents: List<TimestampedScreenshotEvent>,
    )

    companion object {
        private const val WINDOW_MILLIS = 30_000L
        /** ARGB fallback only — used when no H.264 bitstream tap is available. */
        private const val ARGB_FALLBACK_FRAME_RATE = 2.0
        /** How often we check whether H.264 is healthy enough to skip ARGB. */
        private const val ARGB_POLL_MILLIS = 100L
        /** Sparse ARGB backup when the bitstream tap is missing (~2 fps). */
        private const val ARGB_FALLBACK_SAMPLE_INTERVAL_MILLIS = 500L
        /** Full-rate ARGB backup while an explicit screen recording is active (~30 fps). */
        private const val RECORDING_ARGB_SAMPLE_INTERVAL_MILLIS = 33L
        /** Hard cap so even ARGB-only soft decode cannot retain multi‑GB of pixels. */
        private const val ARGB_MAX_BYTES = 96L * 1024L * 1024L
        private const val SCREEN_POLL_MILLIS = 3_000L
        private const val MIN_SANE_FRAME_RATE = 1.0
        private const val MAX_SANE_FRAME_RATE = 120.0

        private fun isH264ConfigAccessUnit(bytes: ByteArray): Boolean {
            return h264NalTypes(bytes).any { it == 7 || it == 8 }
        }

        private fun isH264PictureAccessUnit(bytes: ByteArray): Boolean {
            return h264NalTypes(bytes).any { it == 1 || it == 5 }
        }

        private fun isH264IdrAccessUnit(bytes: ByteArray): Boolean {
            return h264NalTypes(bytes).any { it == 5 }
        }

        /**
         * Scrcpy often emits SPS (7) and PPS (8) as separate access units. Keep both — replacing
         * the previous config AU drops whichever arrived first and remux then fails with
         * "non-existing PPS".
         */
        internal fun mergeH264Config(existing: ByteArray?, incoming: ByteArray): ByteArray {
            val sps = lastAnnexBNal(incoming, nalType = 7) ?: lastAnnexBNal(existing, nalType = 7)
            val pps = lastAnnexBNal(incoming, nalType = 8) ?: lastAnnexBNal(existing, nalType = 8)
            if (sps == null && pps == null) return incoming.copyOf()
            val out = java.io.ByteArrayOutputStream((sps?.size ?: 0) + (pps?.size ?: 0))
            sps?.let(out::write)
            pps?.let(out::write)
            return out.toByteArray()
        }

        private fun lastAnnexBNal(bytes: ByteArray?, nalType: Int): ByteArray? {
            if (bytes == null || bytes.isEmpty()) return null
            var last: ByteArray? = null
            var i = 0
            while (i + 3 < bytes.size) {
                val startLen = when {
                    i + 4 <= bytes.size &&
                        bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() &&
                        bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte() -> 4
                    bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() &&
                        bytes[i + 2] == 1.toByte() -> 3
                    else -> {
                        i++
                        continue
                    }
                }
                val nalStart = i
                val typeIndex = i + startLen
                if (typeIndex >= bytes.size) break
                var next = typeIndex + 1
                while (next + 3 < bytes.size) {
                    val nextStart = when {
                        next + 4 <= bytes.size &&
                            bytes[next] == 0.toByte() && bytes[next + 1] == 0.toByte() &&
                            bytes[next + 2] == 0.toByte() && bytes[next + 3] == 1.toByte() -> true
                        bytes[next] == 0.toByte() && bytes[next + 1] == 0.toByte() &&
                            bytes[next + 2] == 1.toByte() -> true
                        else -> false
                    }
                    if (nextStart) break
                    next++
                }
                if (next + 3 >= bytes.size) next = bytes.size
                if ((bytes[typeIndex].toInt() and 0x1F) == nalType) {
                    last = bytes.copyOfRange(nalStart, next)
                }
                i = next
            }
            return last
        }

        private fun h264NalTypes(bytes: ByteArray): Sequence<Int> = sequence {
            var i = 0
            while (i + 3 < bytes.size) {
                val startLen = when {
                    i + 4 <= bytes.size &&
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
