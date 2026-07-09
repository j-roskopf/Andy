package app.andy.ui.bugs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.BugLogcatTextSurface
import app.andy.MirrorVideoSurface
import app.andy.domain.activeBugActionIndex
import app.andy.domain.activeBugPointerEvent
import app.andy.domain.bugPlaybackMillis
import app.andy.domain.BugPointerEvent
import app.andy.model.BugReport
import app.andy.service.BugService
import app.andy.service.MirrorFrame
import app.andy.ui.components.Button
import app.andy.ui.components.DetailRow
import app.andy.ui.components.DetailSection
import app.andy.ui.components.EmptyState
import app.andy.ui.components.FilterPill
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PaneDivider
import app.andy.ui.components.PanelCard
import app.andy.ui.components.Toolbar
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.live.fittedRect
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Panel
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
internal fun BugsScreen(bugs: BugService) {
    val scope = rememberCoroutineScope()
    var reports by remember { mutableStateOf<List<BugReport>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<BugReport?>(null) }
    var logcat by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf("Details") }
    var playbackFrame by remember { mutableStateOf<MirrorFrame?>(null) }
    var playbackRunId by remember { mutableStateOf(0) }
    var isReplaying by remember { mutableStateOf(false) }
    var playbackFrameCount by remember { mutableStateOf(0) }
    var playbackFrameIndex by remember { mutableStateOf(0) }
    var playbackStartFrameIndex by remember { mutableStateOf(0) }
    var isInspectingPlayback by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var stepsPaneWidth by remember { mutableStateOf(380f) }
    var bugDetailsPaneWidth by remember { mutableStateOf(320f) }
    val expandedStepIds = remember { mutableStateMapOf<String, Boolean>() }
    val stepsListState = rememberLazyListState()

    fun refreshReports() {
        scope.launch {
            reports = bugs.listBugs()
            if (selectedId == null || reports.none { it.id == selectedId }) {
                selectedId = reports.firstOrNull()?.id
            }
        }
    }

    LaunchedEffect(Unit) { refreshReports() }
    LaunchedEffect(selectedId, reports) {
        val id = selectedId
        selected = reports.firstOrNull { it.id == id } ?: id?.let { bugs.loadBug(it) }
        logcat = id?.let { bugs.loadBugLog(it) }.orEmpty()
        playbackFrame = null
        playbackFrameIndex = 0
        playbackStartFrameIndex = 0
        isInspectingPlayback = false
        playbackFrameCount = id?.let { bugs.bugVideoFrameCount(it) } ?: 0
        isReplaying = false
        expandedStepIds.clear()
    }
    LaunchedEffect(selectedId, playbackFrameCount, playbackFrameIndex, isReplaying) {
        val id = selectedId ?: return@LaunchedEffect
        if (isReplaying || playbackFrameCount <= 0) return@LaunchedEffect
        bugs.loadBugVideoFrame(id, playbackFrameIndex)?.let { frame ->
            playbackFrame = frame
        }
    }
    LaunchedEffect(selectedId, playbackRunId, isReplaying) {
        val id = selectedId ?: return@LaunchedEffect
        if (!isReplaying || playbackRunId == 0) return@LaunchedEffect
        val runId = playbackRunId
        playbackFrame = null
        try {
            bugs.playbackFrames(id, playbackStartFrameIndex).collect { frame ->
                playbackFrame = frame
                playbackFrameIndex = frame.frameNumber.toInt().coerceAtLeast(1) - 1
                isInspectingPlayback = true
            }
        } finally {
            if (playbackRunId == runId) {
                isReplaying = false
            }
        }
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelCard(Modifier.width(270.dp).fillMaxHeight()) {
            Toolbar("Bugs", "${reports.size} reports", onPrimary = { refreshReports() }, primaryLabel = "Refresh")
            if (reports.isEmpty()) {
                EmptyState("No bug reports yet")
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(reports) { report ->
                        val active = report.id == selectedId
                        Column(
                            Modifier.fillMaxWidth()
                                .background(if (active) PanelSoft else Panel, RoundedCornerShape(AndyRadius.R3))
                                .border(1.dp, if (active) Rust.copy(alpha = 0.45f) else Border, RoundedCornerShape(AndyRadius.R3))
                                .clickable { selectedId = report.id }
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

        val report = selected
        if (report == null) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text("Save a bug from Live to see its replay here.", color = TextSecondary)
            }
        } else {
            val playbackMillis = bugPlaybackMillis(report, playbackFrameIndex, playbackFrameCount)
            val showReplayAnnotations = isInspectingPlayback && playbackFrame != null
            val activeActionIndex = if (showReplayAnnotations) activeBugActionIndex(report.actions, playbackMillis) else -1
            val pointerEvent = if (showReplayAnnotations) activeBugPointerEvent(report.actions, playbackMillis) else null
            fun toggleBugReplay() {
                if (isReplaying) {
                    isReplaying = false
                } else {
                    isInspectingPlayback = true
                    playbackStartFrameIndex = playbackFrameIndex
                    isReplaying = true
                    playbackRunId++
                }
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
                    ) { Text(if (isReplaying) "Pause" else "Reproduce") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        scope.launch {
                            status = bugs.exportBug(report.id)?.let { "Exported to $it" } ?: "Export failed"
                        }
                    }) { Text("Export") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                bugs.deleteBug(report.id)
                                status = "Deleted ${report.title}"
                                selectedId = null
                                refreshReports()
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Red),
                    ) { Text("Delete") }
                }
                if (status.isNotBlank()) Text(status, color = Rust, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PanelCard(Modifier.width(stepsPaneWidth.dp).fillMaxHeight()) {
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
                                val expanded = expandedStepIds[action.id] == true
                                Column(
                                    Modifier.fillMaxWidth()
                                        .animateContentSize()
                                        .background(if (active) Rust.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(AndyRadius.R2))
                                        .border(1.dp, if (active) Rust.copy(alpha = 0.55f) else Color.Transparent, RoundedCornerShape(AndyRadius.R2))
                                        .clickable { expandedStepIds[action.id] = !expanded }
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
                        onDrag = { dragX -> stepsPaneWidth = (stepsPaneWidth + dragX).coerceIn(260f, 1_400f) },
                    )
                    PanelCard(Modifier.weight(1f).widthIn(min = 96.dp).fillMaxHeight()) {
                        Text("VIDEO", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                Modifier.weight(1f).fillMaxWidth()
                                    .background(Color.Black, RoundedCornerShape(AndyRadius.R3))
                                    .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
                                    .clickable(enabled = playbackFrameCount > 0) { toggleBugReplay() },
                                contentAlignment = Alignment.Center,
                            ) {
                                val frame = playbackFrame
                                if (frame != null) {
                                    Box(Modifier.fillMaxSize()) {
                                        MirrorVideoSurface(
                                            frame = frame,
                                            modifier = Modifier.fillMaxSize(),
                                            onInput = {},
                                            passThroughInput = false,
                                            onDevicePointClick = { _, _ -> toggleBugReplay() },
                                        )
                                        pointerEvent?.let { event ->
                                            BugPointerOverlay(frame, event)
                                        }
                                    }
                                } else {
                                    Text("Press Reproduce to play capture.mp4", color = TextSecondary, fontSize = 12.sp)
                                }
                            }
                            if (playbackFrameCount > 0) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    val sliderMax = (playbackFrameCount - 1).coerceAtLeast(1).toFloat()
                                    Slider(
                                        value = playbackFrameIndex.toFloat().coerceIn(0f, sliderMax),
                                        onValueChange = { value ->
                                            val index = value.toInt().coerceIn(0, playbackFrameCount - 1)
                                            isReplaying = false
                                            isInspectingPlayback = true
                                            playbackFrameIndex = index
                                        },
                                        valueRange = 0f..sliderMax,
                                        enabled = playbackFrameCount > 1,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "${playbackFrameIndex + 1}/$playbackFrameCount",
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
                        onDrag = { dragX -> bugDetailsPaneWidth = (bugDetailsPaneWidth - dragX).coerceIn(220f, 900f) },
                    )
                    PanelCard(Modifier.width(bugDetailsPaneWidth.dp).fillMaxHeight()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterPill("Details", selectedTab == "Details", Rust) { selectedTab = "Details" }
                            FilterPill("Logcat", selectedTab == "Logcat", Rust) { selectedTab = "Logcat" }
                        }
                        if (selectedTab == "Details") {
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
                            BugLogcatView(logcat, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
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

@Composable
private fun BugPointerOverlay(frame: MirrorFrame, event: BugPointerEvent) {
    Canvas(Modifier.fillMaxSize()) {
        val rect = fittedRect(size.width, size.height, frame.width, frame.height)
        val scaleX = rect.width / frame.width.coerceAtLeast(1)
        val scaleY = rect.height / frame.height.coerceAtLeast(1)
        val center = Offset(rect.left + event.x * scaleX, rect.top + event.y * scaleY)
        val alpha = (1f - event.progress).coerceIn(0f, 1f)
        val radius = 14.dp.toPx() + 20.dp.toPx() * event.progress
        drawCircle(
            color = Rust.copy(alpha = 0.20f * alpha),
            radius = radius,
            center = center,
        )
        drawCircle(
            color = Rust.copy(alpha = 0.95f * alpha),
            radius = radius,
            center = center,
            style = Stroke(width = 2.dp.toPx()),
        )
        drawCircle(
            color = AndyColors.Neutral100.copy(alpha = 0.90f * alpha),
            radius = 4.dp.toPx(),
            center = center,
        )
        drawLine(
            color = Rust.copy(alpha = 0.75f * alpha),
            start = Offset(center.x - 18.dp.toPx(), center.y),
            end = Offset(center.x + 18.dp.toPx(), center.y),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = Rust.copy(alpha = 0.75f * alpha),
            start = Offset(center.x, center.y - 18.dp.toPx()),
            end = Offset(center.x, center.y + 18.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

private fun relativeSeconds(timestampMillis: Long, endMillis: Long): String {
    val seconds = ((timestampMillis - endMillis) / 1000.0)
    return "%.1fs".format(seconds)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    return "%.1f MB".format(kb / 1024.0)
}

