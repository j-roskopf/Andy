package app.andy.ui.bugs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.BugLogcatTextSurface
import app.andy.MirrorGestureOverlay
import app.andy.MirrorOverlay
import app.andy.MirrorVideoSurface
import app.andy.domain.actionEventsForOverlay
import app.andy.domain.activeBugPointerEvent
import app.andy.domain.activeInvestigationEventIndex
import app.andy.domain.bugPlaybackMillis
import app.andy.domain.BugPointerEvent
import app.andy.domain.filtered
import app.andy.domain.investigationTimelineFor
import app.andy.domain.nearestBugFrameIndex
import app.andy.model.InvestigationEvent
import app.andy.model.explainMomentRequest
import app.andy.model.investigateSelectionRequest
import app.andy.rememberCopyText
import app.andy.service.AndyServices
import app.andy.service.BugService
import app.andy.service.MirrorFrame
import app.andy.service.OpenInvestigationRequest
import app.andy.service.RecordingExportService
import app.andy.service.UnavailableRecordingExportService
import app.andy.ui.agents.ContextualAiActionHost
import app.andy.ui.agents.ExplainActionButton
import app.andy.ui.agents.contextualAiActionsEnabled
import app.andy.ui.agents.rememberContextualAiActionState
import app.andy.ui.components.Button
import app.andy.ui.components.ConfirmationDialog
import app.andy.ui.components.DetailRow
import app.andy.ui.components.DetailSection
import app.andy.ui.components.EmptyState
import app.andy.ui.components.FilterPill
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PaneDivider
import app.andy.ui.components.PanelCard
import app.andy.ui.components.PendingConfirmation
import app.andy.ui.components.Toolbar
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.live.RecordingExportSheet
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.AndyStroke
import app.andy.ui.theme.Border
import app.andy.ui.theme.Panel
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

@Composable
internal fun BugsScreen(
    services: AndyServices,
    recordings: Boolean = false,
    pendingInvestigation: OpenInvestigationRequest? = null,
    onPendingInvestigationConsumed: () -> Unit = {},
) {
    val bugs: BugService = services.bugs
    val recordingExport: RecordingExportService =
        if (recordings) services.recordingExport else UnavailableRecordingExportService
    val scope = rememberCoroutineScope()
    val copyText = rememberCopyText()
    val state = remember(bugs) { BugsScreenState(bugs) }
    val timelineListState = rememberLazyListState()
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    var exportSheetVisible by remember { mutableStateOf(false) }
    var pendingSeek by remember { mutableStateOf<OpenInvestigationRequest?>(null) }
    val contextualActions = rememberContextualAiActionState()
    val explainAvailable = contextualAiActionsEnabled(services) && !recordings
    val pageTitle = if (recordings) "Recordings" else "Bugs"
    val itemLabel = if (recordings) "recording" else "bug report"

    fun refreshReports() {
        scope.launch {
            state.reports = if (recordings) state.bugs.listRecordings() else state.bugs.listBugs()
            if (state.selectedId == null || state.reports.none { it.id == state.selectedId }) {
                state.selectedId = state.reports.firstOrNull()?.id
            }
        }
    }

    LaunchedEffect(Unit) { refreshReports() }
    LaunchedEffect(pendingInvestigation) {
        val request = pendingInvestigation ?: return@LaunchedEffect
        state.reports = if (recordings) state.bugs.listRecordings() else state.bugs.listBugs()
        state.selectedId = request.investigationId
        pendingSeek = request
        onPendingInvestigationConsumed()
    }
    LaunchedEffect(state.selectedId, state.reports) {
        exportSheetVisible = false
        val id = state.selectedId
        state.selected = state.reports.firstOrNull { it.id == id } ?: id?.let { state.bugs.loadBug(it) }
        state.logcat = id?.let { state.bugs.loadBugLog(it) }.orEmpty()
        state.resetPlaybackForSelection()
        state.timeline = id?.let { runCatching { state.bugs.loadBugTimeline(it) }.getOrNull() }
        state.isVideoLoading = id != null
        state.playbackFrameCount = id?.let { state.bugs.bugVideoFrameCount(it) } ?: 0
        if (state.playbackFrameCount <= 0) state.isVideoLoading = false
    }
    LaunchedEffect(state.selectedId, state.playbackFrameCount, state.playbackFrameIndex, state.isReplaying) {
        val id = state.selectedId ?: return@LaunchedEffect
        if (state.isReplaying || state.playbackFrameCount <= 0) return@LaunchedEffect
        state.bugs.loadBugVideoFrame(id, state.playbackFrameIndex)?.let { frame ->
            state.playbackFrame = frame
        }
        state.isVideoLoading = false
    }
    LaunchedEffect(pendingSeek, state.selectedId, state.playbackFrameCount) {
        val request = pendingSeek ?: return@LaunchedEffect
        val report = state.selected ?: return@LaunchedEffect
        if (report.id != request.investigationId || state.playbackFrameCount <= 0) return@LaunchedEffect
        val atMillis = request.playbackMillis
            ?: state.timeline?.events?.firstOrNull { it.id == request.eventId }?.atMillis
        if (atMillis != null) {
            val index = nearestBugFrameIndex(report, atMillis, state.playbackFrameCount)
            val eventId = request.eventId
            if (eventId != null) state.seekPlaybackToEvent(index, eventId) else state.seekPlayback(index)
        }
        pendingSeek = null
    }
    LaunchedEffect(state.selectedId, state.playbackRunId, state.isReplaying) {
        val id = state.selectedId ?: return@LaunchedEffect
        if (!state.isReplaying || state.playbackRunId == 0) return@LaunchedEffect
        val runId = state.playbackRunId
        state.playbackFrame = null
        try {
            // Drop queued frames when Compose can't paint full capture FPS — keeps real-time feel.
            state.bugs.playbackFrames(id, state.playbackStartFrameIndex).conflate().collect { frame ->
                state.playbackFrame = frame
                state.playbackFrameIndex = frame.frameNumber.toInt().coerceAtLeast(1) - 1
                state.isInspectingPlayback = true
            }
        } finally {
            if (state.playbackRunId == runId) {
                state.isReplaying = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelCard(Modifier.width(250.dp).fillMaxHeight()) {
            Toolbar(pageTitle, "${state.reports.size} ${if (recordings) "recordings" else "reports"}", onPrimary = { refreshReports() }, primaryLabel = "Refresh")
            if (state.reports.isEmpty()) {
                EmptyState(if (recordings) "No recordings yet" else "No bug reports yet")
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.reports) { report ->
                        val active = report.id == state.selectedId
                        Column(
                            Modifier.fillMaxWidth()
                                .background(if (active) PanelSoft else Panel, RoundedCornerShape(AndyRadius.Control))
                                .border(1.dp, if (active) Rust.copy(alpha = 0.45f) else Border, RoundedCornerShape(AndyRadius.Control))
                                .clickable { state.selectedId = report.id }
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(report.title, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${report.actions.size} actions · ${formatMillis(report.capturedAtMillis)}", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
                            Text(report.deviceSerial, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        val report = state.selected
        if (report == null) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text(if (recordings) "Start a recording from Live to see its replay here." else "Save a bug from Live to see its replay here.", color = TextSecondary)
            }
        } else {
            val effectiveTimeline = remember(report.id, state.timeline) { investigationTimelineFor(report, state.timeline) }
            val overlayActions = remember(effectiveTimeline) { effectiveTimeline.actionEventsForOverlay() }
            val filteredEvents = remember(effectiveTimeline, state.timelineFilters) { effectiveTimeline.filtered(state.timelineFilters) }
            val playbackMillis = bugPlaybackMillis(report, state.playbackFrameIndex, state.playbackFrameCount)
            val showReplayAnnotations = state.isInspectingPlayback && state.playbackFrame != null
            val pointerEvent = if (showReplayAnnotations) activeBugPointerEvent(overlayActions, playbackMillis) else null
            val activeEventIndex = if (showReplayAnnotations) activeInvestigationEventIndex(filteredEvents, playbackMillis) else -1
            val activeEvent = filteredEvents.getOrNull(activeEventIndex)
            fun toggleBugReplay() {
                state.toggleReplay()
            }
            fun onToggleTimelineEvent(event: InvestigationEvent) {
                state.expandedEventIds[event.id] = state.expandedEventIds[event.id] != true
                val targetIndex = nearestBugFrameIndex(report, event.atMillis, state.playbackFrameCount)
                state.seekPlaybackToEvent(targetIndex, event.id)
            }
            LaunchedEffect(report.id, activeEventIndex) {
                if (activeEventIndex >= 0) {
                    val isVisible = timelineListState.layoutInfo.visibleItemsInfo.any { it.index == activeEventIndex }
                    if (!isVisible) {
                        timelineListState.scrollToItem(activeEventIndex)
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(report.title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(report.deviceModel, "API ${report.apiLevel ?: "-"}", report.abi, report.resolution, formatMillis(report.capturedAtMillis))
                                .filterNotNull()
                                .joinToString(" · "),
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(
                        onClick = { toggleBugReplay() },
                        colors = primaryButtonColors(),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text(if (state.isReplaying) "Pause" else if (recordings) "Play" else "Reproduce") }
                    Spacer(Modifier.width(8.dp))
                    if (explainAvailable) {
                        val momentEventId = state.selectedEventId ?: activeEvent?.id
                        ExplainActionButton("Explain this moment…") {
                            contextualActions.open(
                                explainMomentRequest(
                                    investigationId = report.id,
                                    eventId = momentEventId,
                                    playbackMillis = playbackMillis,
                                    momentSummary = filteredEvents.firstOrNull { it.id == momentEventId }?.summary,
                                    packageName = report.appIdentity?.packageName,
                                ),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        val selectionEvents = investigationSelectionAround(filteredEvents, playbackMillis)
                        ExplainActionButton("Investigate selection…", enabled = selectionEvents.isNotEmpty()) {
                            contextualActions.open(
                                investigateSelectionRequest(
                                    investigationId = report.id,
                                    eventIds = selectionEvents.map { it.id },
                                    playbackMillis = playbackMillis,
                                    selectionSummary = investigationSelectionSummary(selectionEvents),
                                    packageName = report.appIdentity?.packageName,
                                ),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    if (recordings) {
                        OutlinedButton(onClick = { exportSheetVisible = true }) { Text("Export…") }
                        Spacer(Modifier.width(8.dp))
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            state.status = state.bugs.exportBug(report.id)?.let { "Exported to $it" } ?: "Export failed"
                        }
                    }) { Text("Duplicate") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { scope.launch { state.bugs.revealBug(report.id) } }) { Text("Reveal") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        scope.launch {
                            val path = state.bugs.bugDirectoryPath(report.id)
                            state.status = if (path != null) {
                                copyText(path)
                                "Copied path to clipboard"
                            } else {
                                "Path is not available on this platform"
                            }
                        }
                    }) { Text("Copy path") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            pendingConfirmation = PendingConfirmation(
                                title = "Delete $itemLabel?",
                                message = "\"${report.title}\" will be permanently deleted.",
                                confirmLabel = "Delete",
                            ) {
                                scope.launch {
                                    state.bugs.deleteBug(report.id)
                                    state.status = "Deleted ${report.title}"
                                    state.selectedId = null
                                    refreshReports()
                                }
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Red),
                    ) { Text("Delete") }
                }
                report.videoCaptureWarning?.let { warning ->
                    Text("⚠ $warning", color = Rust, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (state.status.isNotBlank()) Text(state.status, color = Rust, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val paneGuttersWidth = AndySpace.Space4 * 2 + AndyStroke.PaneHandleHitWidth * 2
                    val minimumVideoWidth = 260.dp
                    val minimumStepsWidth = 220.dp
                    val minimumDetailsWidth = 220.dp
                    val availableForSidePanes = maxWidth - paneGuttersWidth - minimumVideoWidth
                    val maximumStepsWidth = (availableForSidePanes - minimumDetailsWidth).coerceAtLeast(minimumStepsWidth)
                    val maximumDetailsWidth = (availableForSidePanes - minimumStepsWidth).coerceAtLeast(minimumDetailsWidth)
                    val (displayStepsWidth, displayDetailsWidth) = remember(
                        maxWidth,
                        state.timelinePaneWidth,
                        state.bugDetailsPaneWidth,
                    ) {
                        val maxSteps = maximumStepsWidth.value.coerceIn(minimumStepsWidth.value, 1_400f)
                        val maxDetails = maximumDetailsWidth.value.coerceIn(minimumDetailsWidth.value, 900f)
                        var steps = state.timelinePaneWidth.coerceIn(minimumStepsWidth.value, maxSteps)
                        var details = state.bugDetailsPaneWidth.coerceIn(minimumDetailsWidth.value, maxDetails)
                        val overflow = steps + details - availableForSidePanes.value
                        if (overflow > 0f) {
                            val stepsShare = steps / (steps + details)
                            steps = (steps - overflow * stepsShare).coerceAtLeast(minimumStepsWidth.value)
                            details = (details - overflow * (1f - stepsShare)).coerceAtLeast(minimumDetailsWidth.value)
                        }
                        steps to details
                    }

                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PanelCard(Modifier.width(displayStepsWidth.dp).fillMaxHeight()) {
                        InvestigationTimelinePane(
                            events = filteredEvents,
                            totalEventCount = effectiveTimeline.events.size,
                            filters = state.timelineFilters,
                            onFiltersChange = { state.timelineFilters = it },
                            activeEventId = activeEvent?.id,
                            expandedEventIds = state.expandedEventIds,
                            onToggleEvent = ::onToggleTimelineEvent,
                            referenceMillis = report.windowEndedAtMillis,
                            listState = timelineListState,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    PaneDivider(
                        onDrag = { dragX ->
                            val maxSteps = (availableForSidePanes - displayDetailsWidth.dp)
                                .coerceAtLeast(minimumStepsWidth)
                                .value
                                .coerceAtMost(1_400f)
                            state.timelinePaneWidth = (displayStepsWidth + dragX)
                                .coerceIn(minimumStepsWidth.value, maxSteps)
                        },
                    )
                    PanelCard(Modifier.weight(1f).widthIn(min = minimumVideoWidth).fillMaxHeight()) {
                        Text("VIDEO", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                Modifier.weight(1f).fillMaxWidth()
                                    .background(Color.Black, RoundedCornerShape(AndyRadius.Control))
                                    .border(1.dp, Border, RoundedCornerShape(AndyRadius.Control))
                                    .clickable(enabled = state.playbackFrameCount > 0) { toggleBugReplay() },
                                contentAlignment = Alignment.Center,
                            ) {
                                val frame = state.playbackFrame
                                if (frame != null) {
                                    Box(Modifier.fillMaxSize()) {
                                        MirrorVideoSurface(
                                            frame = frame,
                                            modifier = Modifier.fillMaxSize(),
                                            onInput = {},
                                            passThroughInput = false,
                                            onDevicePointClick = { _, _ -> toggleBugReplay() },
                                            overlay = pointerEvent?.toMirrorGestureOverlay()?.let { gesture ->
                                                MirrorOverlay(gesture = gesture)
                                            } ?: MirrorOverlay(),
                                        )
                                    }
                                } else if (state.isVideoLoading || state.playbackFrameCount > 0) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = Rust)
                                        Text("Loading capture.mp4…", color = TextSecondary, fontSize = 12.sp)
                                    }
                                } else {
                                    Text(if (recordings) "Press Play to watch capture.mp4" else "Press Reproduce to play capture.mp4", color = TextSecondary, fontSize = 12.sp)
                                }
                            }
                            if (state.playbackFrameCount > 0) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    val sliderMax = (state.playbackFrameCount - 1).coerceAtLeast(1).toFloat()
                                    Slider(
                                        value = state.playbackFrameIndex.toFloat().coerceIn(0f, sliderMax),
                                        onValueChange = { value ->
                                            val index = value.toInt().coerceIn(0, state.playbackFrameCount - 1)
                                            state.seekPlayback(index)
                                        },
                                        valueRange = 0f..sliderMax,
                                        enabled = state.playbackFrameCount > 1,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "${state.playbackFrameIndex + 1}/${state.playbackFrameCount}",
                                        color = TextSecondary,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        modifier = Modifier.width(84.dp),
                                    )
                                }
                            } else if (state.isVideoLoading) {
                                Text("Loading video…", color = TextSecondary, fontSize = 12.sp)
                            } else {
                                Text("No video frames captured", color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                    PaneDivider(
                        onDrag = { dragX ->
                            val maxDetails = (availableForSidePanes - displayStepsWidth.dp)
                                .coerceAtLeast(minimumDetailsWidth)
                                .value
                                .coerceAtMost(900f)
                            state.bugDetailsPaneWidth = (displayDetailsWidth - dragX)
                                .coerceIn(minimumDetailsWidth.value, maxDetails)
                        },
                    )
                    PanelCard(Modifier.width(displayDetailsWidth.dp).fillMaxHeight()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterPill("Details", state.selectedTab == "Details", Rust) { state.selectedTab = "Details" }
                            FilterPill("Logcat", state.selectedTab == "Logcat", Rust) { state.selectedTab = "Logcat" }
                        }
                        if (state.selectedTab == "Details") {
                            DetailSection("APP")
                            DetailRow("Package", report.appIdentity?.packageName)
                            DetailRow("Version", listOfNotNull(report.appIdentity?.versionName, report.appIdentity?.versionCode?.let { "($it)" }).joinToString(" ").ifBlank { null })
                            DetailRow("Min/Target SDK", listOfNotNull(report.appIdentity?.minSdk, report.appIdentity?.targetSdk).joinToString(" / ").ifBlank { null })
                            DetailRow("Debuggable", report.appIdentity?.debuggable?.toString())
                            DetailSection("PROJECT / BUILD")
                            DetailRow("Project", report.projectIdentity?.projectId)
                            DetailRow("Git branch", report.projectIdentity?.gitBranch)
                            DetailRow("Git head", report.projectIdentity?.gitHead)
                            DetailRow("Working tree", report.projectIdentity?.gitDirty?.let { if (it) "dirty" else "clean" })
                            DetailSection("HOST")
                            DetailRow("Andy version", report.hostIdentity?.andyVersionName)
                            DetailRow("Host OS", report.hostIdentity?.hostOs)
                            Text(
                                "Host clock is authoritative; device timestamps are approximate when shown.",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            )
                            DetailSection("DEVICE")
                            DetailRow("Model", report.deviceModel)
                            DetailRow("Serial", report.deviceSerial)
                            DetailRow("API Level", report.apiLevel)
                            DetailRow("ABI", report.abi)
                            DetailRow("Resolution", report.resolution)
                            DetailRow("Captured", formatMillis(report.capturedAtMillis))
                            if (recordings) {
                                DetailSection("VIDEO")
                                DetailRow("Duration", formatDurationSeconds(report))
                                DetailRow("Frame rate", report.videoFrameRate?.let { "${app.andy.formatDecimal(it, 1)} fps" })
                                DetailRow("Frames", report.videoFrameTimestampsMillis.size.takeIf { it > 0 }?.toString())
                            }
                            DetailSection("ARTIFACT FILES")
                            report.artifacts.forEach { artifact ->
                                DetailRow(artifact.name, artifact.sizeBytes?.let(::formatBytes) ?: artifact.kind)
                            }
                            report.videoCaptureWarning?.let { warning ->
                                Text(
                                    "⚠ $warning",
                                    color = Rust,
                                    fontSize = 12.sp,
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                )
                            }
                            DetailSection("NOTES")
                            SelectionContainer {
                                Text(report.notes.ifBlank { "<none>" }, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().background(Color.Black, RoundedCornerShape(AndyRadius.Control)).padding(10.dp))
                            }
                        } else {
                            BugLogcatView(state.logcat, Modifier.fillMaxSize())
                        }
                    }
                    }
                }
            }
        }
    }
    ContextualAiActionHost(services, contextualActions)
    }
    pendingConfirmation?.let { confirmation ->
        ConfirmationDialog(
            confirmation = confirmation,
            onDismiss = { pendingConfirmation = null },
            onConfirm = {
                pendingConfirmation = null
                confirmation.onConfirm()
            },
        )
    }
    if (exportSheetVisible) {
        state.selected?.let { report ->
            RecordingExportSheet(
                report = report,
                bugs = state.bugs,
                recordingExport = recordingExport,
                onDismiss = { exportSheetVisible = false },
                onRenamed = { refreshReports() },
            )
        }
    }
}

@Composable
private fun BugLogcatView(logcat: String, modifier: Modifier = Modifier) {
    BugLogcatTextSurface(logcat, modifier.background(Color.Black, RoundedCornerShape(AndyRadius.Control)))
}

private fun formatMillis(value: Long): String = if (value <= 0L) "-" else value.toString()

private fun formatDurationSeconds(report: app.andy.model.BugReport): String? {
    val start = report.videoStartedAtMillis ?: return null
    val end = report.videoEndedAtMillis ?: return null
    val seconds = (end - start).coerceAtLeast(0L) / 1000.0
    return "${app.andy.formatDecimal(seconds, 1)}s"
}

private fun BugPointerEvent.toMirrorGestureOverlay() = MirrorGestureOverlay(
    startX = x,
    startY = y,
    endX = endX,
    endY = endY,
    fadeProgress = progress,
    swipeProgress = swipeProgress,
)

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${app.andy.formatDecimal(kb, 1)} KB"
    return "${app.andy.formatDecimal(kb / 1024.0, 1)} MB"
}
