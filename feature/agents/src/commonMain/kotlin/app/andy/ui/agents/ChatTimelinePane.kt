package app.andy.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AgentLaneKind
import app.andy.model.AgentTask
import app.andy.model.TimelineAxis
import app.andy.model.TimelineModel
import app.andy.model.WorkspaceState
import app.andy.model.buildChatTimeline
import app.andy.model.coalesceAcpTranscriptEvents
import app.andy.model.timelineBrushSelection
import app.andy.model.timelineFilterRows
import app.andy.model.timelineRowDetail
import app.andy.service.AndyServices
import app.andy.ui.components.PaneDivider
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Project-level Timeline canvas: inspects the currently selected ACP chat's event stream.
 * Empty / non-ACP states stay informative until a live session has structured events.
 */
@Composable
fun ChatTimelinePane(
    services: AndyServices,
    task: AgentTask?,
    workspaceState: WorkspaceState = WorkspaceState(),
    modifier: Modifier = Modifier,
) {
    when {
        task == null -> TimelineEmptyState(
            title = "No chat selected",
            body = "Pick a chat in the sidebar, or start one from the Chat tab. Timeline fills in once the session has events.",
            modifier = modifier,
        )
        task.lane != AgentLaneKind.Acp -> TimelineEmptyState(
            title = "Timeline needs an ACP chat",
            body = "This session runs on the terminal lane. Open or start an ACP chat (Codex, Claude Code, Cursor, …) to inspect its trajectory.",
            modifier = modifier,
        )
        else -> ChatTimelinePaneActive(
            services = services,
            task = task,
            workspaceState = workspaceState,
            modifier = modifier,
        )
    }
}

@Composable
private fun ChatTimelinePaneActive(
    services: AndyServices,
    task: AgentTask,
    workspaceState: WorkspaceState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val transcriptEvents by services.agentRuns.events(task.id).collectAsState()
    var timelineSelection by remember(task.id) { mutableStateOf<String?>(null) }
    var timelineBrushStart by remember(task.id) { mutableStateOf<Float?>(null) }
    var timelineBrushEnd by remember(task.id) { mutableStateOf<Float?>(null) }
    var timelineSearchQuery by remember(task.id) { mutableStateOf("") }
    var timelineAxis by remember(task.id) {
        mutableStateOf(parseTimelineAxis(workspaceState.agentTimelineAxis))
    }
    var timelineDurationMode by remember(task.id) {
        mutableStateOf(parseTimelineDuration(workspaceState))
    }
    var timelineModel by remember(task.id) { mutableStateOf<TimelineModel?>(null) }
    var sidePaneWidth by remember(task.id) { mutableStateOf(420f) }

    LaunchedEffect(workspaceState.agentTimelineAxis, workspaceState.agentTimelineDuration, task.id) {
        val parsedAxis = parseTimelineAxis(workspaceState.agentTimelineAxis)
        val parsedDuration = parseTimelineDuration(workspaceState)
        if (parsedAxis != timelineAxis) timelineAxis = parsedAxis
        if (parsedDuration != timelineDurationMode) timelineDurationMode = parsedDuration
    }
    LaunchedEffect(task.id, timelineAxis, task.isActive) {
        snapshotFlow { transcriptEvents }
            .conflate()
            .collectLatest { events ->
                if (task.isActive) delay(250)
                timelineModel = buildChatTimeline(events, task, timelineAxis)
            }
    }

    val model = timelineModel
    if (model == null) {
        TimelineEmptyState(
            title = "Loading timeline…",
            body = "Assembling session events.",
            modifier = modifier,
        )
        return
    }
    if (model.rows.isEmpty()) {
        TimelineEmptyState(
            title = if (task.isActive) "Waiting for events…" else "No timeline events yet",
            body = if (task.isActive) {
                "This chat is live — rows appear here as the agent works."
            } else {
                "This ACP session has no structured transcript events to plot."
            },
            modifier = modifier,
        )
        return
    }

    val brushedKeys = remember(model, timelineAxis, timelineDurationMode, timelineBrushStart, timelineBrushEnd) {
        val start = timelineBrushStart ?: return@remember emptySet()
        val end = timelineBrushEnd ?: return@remember emptySet()
        timelineBrushSelection(model, timelineAxis, start, end, timelineDurationMode)
    }
    val matchingKeys = remember(model, timelineSearchQuery) {
        timelineFilterRows(model, timelineSearchQuery)
    }
    val selectedRow = remember(model, timelineSelection) {
        model.rows.firstOrNull { it.key == timelineSelection }
    }
    val timelineDetail = remember(selectedRow, transcriptEvents, model) {
        val row = selectedRow ?: return@remember null
        val coalesced = coalesceAcpTranscriptEvents(transcriptEvents)
        val event = row.eventIndex.takeIf { it >= 0 }?.let { coalesced.getOrNull(it) }
        val turn = model.turns.firstOrNull { it.number == row.turnNumber }
        timelineRowDetail(row, event, turn)
    }

    Row(modifier.fillMaxSize().testTag("project-timeline-pane")) {
        ChatTimelineView(
            model = model,
            axis = timelineAxis,
            onAxisChange = { next ->
                timelineAxis = next
                scope.launch {
                    services.workspaceStore.update { it.copy(agentTimelineAxis = next.name) }
                }
            },
            durationMode = timelineDurationMode,
            onDurationModeChange = { next ->
                timelineDurationMode = next
                // Clear brush — fractions aren't comparable across equal vs time axes.
                timelineBrushStart = null
                timelineBrushEnd = null
                scope.launch {
                    services.workspaceStore.update { it.copy(agentTimelineDuration = next) }
                }
            },
            selectedRowKey = timelineSelection,
            onSelectRow = { timelineSelection = it },
            brushedKeys = brushedKeys,
            brushStart = timelineBrushStart,
            brushEnd = timelineBrushEnd,
            onBrushChange = { start, end ->
                timelineBrushStart = start
                timelineBrushEnd = end
            },
            searchQuery = timelineSearchQuery,
            onSearchQueryChange = { timelineSearchQuery = it },
            matchingKeys = matchingKeys,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        if (timelineDetail != null) {
            PaneDivider(
                onDrag = { dragX ->
                    sidePaneWidth = (sidePaneWidth - dragX).coerceIn(280f, 900f)
                },
            )
            ChatTimelineDetailPane(
                detail = timelineDetail,
                onClose = { timelineSelection = null },
                modifier = Modifier.width(sidePaneWidth.dp).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun TimelineEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .testTag("project-timeline-empty")
            .padding(AndySpace.Space4),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
            modifier = Modifier.fillMaxWidth(0.7f),
        ) {
            Text(
                title,
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                body,
                color = TextSecondary.copy(alpha = 0.8f),
                fontFamily = MonoFont,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun parseTimelineAxis(raw: String): TimelineAxis =
    when (raw) {
        "Calls" -> TimelineAxis.Calls
        else -> TimelineAxis.Turns // includes legacy "Duration"
    }

private fun parseTimelineDuration(state: WorkspaceState): Boolean =
    when {
        state.agentTimelineAxis == "Duration" -> true
        else -> state.agentTimelineDuration
    }
