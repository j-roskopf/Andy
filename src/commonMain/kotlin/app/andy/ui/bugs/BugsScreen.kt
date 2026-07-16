package app.andy.ui.bugs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonDefaults
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
import app.andy.domain.activeBugActionIndex
import app.andy.domain.activeBugPointerEvent
import app.andy.domain.bugPlaybackMillis
import app.andy.domain.BugPointerEvent
import app.andy.service.BugService
import app.andy.service.MirrorFrame
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
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
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
internal fun BugsScreen(bugs: BugService, recordings: Boolean = false) {
    val scope = rememberCoroutineScope()
    val state = remember(bugs) { BugsScreenState(bugs) }
    val stepsListState = rememberLazyListState()
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
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
    LaunchedEffect(state.selectedId, state.reports) {
        val id = state.selectedId
        state.selected = state.reports.firstOrNull { it.id == id } ?: id?.let { state.bugs.loadBug(it) }
        state.logcat = id?.let { state.bugs.loadBugLog(it) }.orEmpty()
        state.resetPlaybackForSelection()
        state.playbackFrameCount = id?.let { state.bugs.bugVideoFrameCount(it) } ?: 0
    }
    LaunchedEffect(state.selectedId, state.playbackFrameCount, state.playbackFrameIndex, state.isReplaying) {
        val id = state.selectedId ?: return@LaunchedEffect
        if (state.isReplaying || state.playbackFrameCount <= 0) return@LaunchedEffect
        state.bugs.loadBugVideoFrame(id, state.playbackFrameIndex)?.let { frame ->
            state.playbackFrame = frame
        }
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
                                .background(if (active) PanelSoft else Panel, RoundedCornerShape(AndyRadius.R3))
                                .border(1.dp, if (active) Rust.copy(alpha = 0.45f) else Border, RoundedCornerShape(AndyRadius.R3))
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
            val playbackMillis = bugPlaybackMillis(report, state.playbackFrameIndex, state.playbackFrameCount)
            val showReplayAnnotations = state.isInspectingPlayback && state.playbackFrame != null
            val activeActionIndex = if (showReplayAnnotations) activeBugActionIndex(report.actions, playbackMillis) else -1
            val pointerEvent = if (showReplayAnnotations) activeBugPointerEvent(report.actions, playbackMillis) else null
            fun toggleBugReplay() {
                state.toggleReplay()
            }
            LaunchedEffect(report.id, activeActionIndex) {
                if (activeActionIndex >= 0) {
                    val targetIndex = activeActionIndex + 1
                    val isVisible = stepsListState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
                    if (!isVisible) {
                        stepsListState.scrollToItem(targetIndex)
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
                    OutlinedButton(onClick = {
                        scope.launch {
                            state.status = state.bugs.exportBug(report.id)?.let { "Exported to $it" } ?: "Export failed"
                        }
                    }) { Text("Export") }
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
                if (state.status.isNotBlank()) Text(state.status, color = Rust, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val paneGuttersWidth = 38.dp // Two 12.dp gaps plus two 14.dp resize dividers.
                    val minimumVideoWidth = 260.dp
                    val minimumStepsWidth = 220.dp
                    val minimumDetailsWidth = 220.dp
                    val availableForSidePanes = maxWidth - paneGuttersWidth - minimumVideoWidth
                    val maximumStepsWidth = (availableForSidePanes - minimumDetailsWidth).coerceAtLeast(minimumStepsWidth)
                    val maximumDetailsWidth = (availableForSidePanes - minimumStepsWidth).coerceAtLeast(minimumDetailsWidth)
                    val (displayStepsWidth, displayDetailsWidth) = remember(
                        maxWidth,
                        state.stepsPaneWidth,
                        state.bugDetailsPaneWidth,
                    ) {
                        val maxSteps = maximumStepsWidth.value.coerceIn(minimumStepsWidth.value, 1_400f)
                        val maxDetails = maximumDetailsWidth.value.coerceIn(minimumDetailsWidth.value, 900f)
                        var steps = state.stepsPaneWidth.coerceIn(minimumStepsWidth.value, maxSteps)
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
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("STEPS", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Text("${report.actions.size} events", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                        LazyColumn(Modifier.fillMaxSize(), state = stepsListState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            item {
                                Text("captured here", color = Rust, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                            }
                            itemsIndexed(report.actions) { index, action ->
                                val active = index == activeActionIndex
                                val expanded = state.expandedStepIds[action.id] == true
                                Column(
                                    Modifier.fillMaxWidth()
                                        .animateContentSize()
                                        .background(if (active) Rust.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(AndyRadius.R2))
                                        .border(1.dp, if (active) Rust.copy(alpha = 0.55f) else Color.Transparent, RoundedCornerShape(AndyRadius.R2))
                                        .clickable { state.expandedStepIds[action.id] = !expanded }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(if (expanded) "v" else ">", color = if (active) Rust else TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(10.dp))
                                        Text("${index + 1}", color = if (active) Rust else TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(22.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(action.label, color = if (active) AndyColors.Neutral100 else TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            action.detail?.let { Text(it, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                        }
                                        Text(relativeSeconds(action.timestampMillis, report.windowEndedAtMillis), color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                    }
                                    AnimatedVisibility(visible = expanded) {
                                        Column(
                                            Modifier.fillMaxWidth()
                                                .background(Color.Black.copy(alpha = 0.28f), RoundedCornerShape(AndyRadius.R2))
                                                .padding(horizontal = 8.dp, vertical = 7.dp),
                                            verticalArrangement = Arrangement.spacedBy(5.dp),
                                        ) {
                                            BugStepExpandedRow("label", action.label)
                                            action.detail?.takeIf { it.isNotBlank() }?.let { BugStepExpandedRow("detail", it) }
                                            BugStepExpandedRow("kind", action.kind)
                                            BugStepExpandedRow("time", "${formatMillis(action.timestampMillis)}  ${relativeSeconds(action.timestampMillis, report.windowEndedAtMillis)}")
                                            BugStepExpandedRow("id", action.id)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    PaneDivider(
                        onDrag = { dragX ->
                            val maxSteps = (availableForSidePanes - displayDetailsWidth.dp)
                                .coerceAtLeast(minimumStepsWidth)
                                .value
                                .coerceAtMost(1_400f)
                            state.stepsPaneWidth = (displayStepsWidth + dragX)
                                .coerceIn(minimumStepsWidth.value, maxSteps)
                        },
                    )
                    PanelCard(Modifier.weight(1f).widthIn(min = minimumVideoWidth).fillMaxHeight()) {
                        Text("VIDEO", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                Modifier.weight(1f).fillMaxWidth()
                                    .background(Color.Black, RoundedCornerShape(AndyRadius.R3))
                                    .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
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
                            DetailSection("DEVICE")
                            DetailRow("Model", report.deviceModel)
                            DetailRow("Serial", report.deviceSerial)
                            DetailRow("API Level", report.apiLevel)
                            DetailRow("ABI", report.abi)
                            DetailRow("Resolution", report.resolution)
                            DetailRow("Captured", formatMillis(report.capturedAtMillis))
                            DetailSection("ARTIFACT FILES")
                            report.artifacts.forEach { artifact ->
                                DetailRow(artifact.name, artifact.sizeBytes?.let(::formatBytes) ?: artifact.kind)
                            }
                            DetailSection("NOTES")
                            SelectionContainer {
                                Text(report.notes.ifBlank { "<none>" }, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().background(Color.Black, RoundedCornerShape(AndyRadius.R3)).padding(10.dp))
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
}

@Composable
private fun BugLogcatView(logcat: String, modifier: Modifier = Modifier) {
    BugLogcatTextSurface(logcat, modifier.background(Color.Black, RoundedCornerShape(AndyRadius.R3)))
}

@Composable
private fun BugStepExpandedRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            color = TextSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.width(46.dp),
            maxLines = 1,
        )
        Text(
            value,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun formatMillis(value: Long): String = if (value <= 0L) "-" else value.toString()

private fun BugPointerEvent.toMirrorGestureOverlay() = MirrorGestureOverlay(
    startX = x,
    startY = y,
    endX = endX,
    endY = endY,
    fadeProgress = progress,
    swipeProgress = swipeProgress,
)

private fun relativeSeconds(timestampMillis: Long, endMillis: Long): String {
    val seconds = ((timestampMillis - endMillis) / 1000.0)
    return "${app.andy.formatDecimal(seconds, 1)}s"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${app.andy.formatDecimal(kb, 1)} KB"
    return "${app.andy.formatDecimal(kb / 1024.0, 1)} MB"
}
