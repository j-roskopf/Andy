package app.andy.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.TimelineAxis
import app.andy.model.TimelineModel
import app.andy.model.TimelineRow
import app.andy.model.TimelineRowKind
import app.andy.model.TimelineTurn
import app.andy.ui.components.FieldChromeStyle
import app.andy.ui.components.FilterPill
import app.andy.ui.components.Lucide
import app.andy.ui.components.LucideIcon
import app.andy.ui.components.TextField
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.PaneDividerTint
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

private val BadgeSystem = Color(0xFF6B7280)
private val BadgeUser = Color(0xFF5B8DEF)
private val BadgeContext = Color(0xFF3D9A6A)
private val BadgeAssistant = Color(0xFF9B7EDE)
private val BadgeThinking = Color(0xFF7A6BB5)
private val BadgeTool = Color(0xFFE0A15A)
private val BadgeResult = Color(0xFF4A9EAD)
private val BadgePermission = Color(0xFFC9844A)
private val BadgeFiles = Color(0xFF8B8FA3)
private val BadgeError = Color(0xFFD96B6B)
private val BrushHighlight = Color(0x335B8DEF)
private val SelectionHighlight = Color(0x445B8DEF)

internal data class TimelineListEntry(
    val key: String,
    val turnNumber: Int,
    val showTurnLabel: Boolean,
    val row: TimelineRow? = null,
    val summaryText: String? = null,
)

@Composable
fun ChatTimelineView(
    model: TimelineModel,
    axis: TimelineAxis,
    onAxisChange: (TimelineAxis) -> Unit,
    durationMode: Boolean,
    onDurationModeChange: (Boolean) -> Unit,
    selectedRowKey: String?,
    onSelectRow: (String?) -> Unit,
    brushedKeys: Set<String>,
    brushStart: Float?,
    brushEnd: Float?,
    onBrushChange: (Float?, Float?) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    matchingKeys: Set<String>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val entries = remember(model, axis) { timelineListEntries(model, axis) }
    val filtering = searchQuery.isNotBlank()

    LaunchedEffect(brushedKeys, entries) {
        if (brushedKeys.isEmpty()) return@LaunchedEffect
        val index = entries.indexOfFirst { entry ->
            entry.row?.key in brushedKeys || entry.key in brushedKeys
        }
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }

    Column(modifier.fillMaxSize().testTag("chat-timeline-view")) {
        TimelineToolbar(
            axis = axis,
            onAxisChange = onAxisChange,
            durationMode = durationMode,
            onDurationModeChange = onDurationModeChange,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
        )
        ChatTimelineLanes(
            model = model,
            axis = axis,
            durationMode = durationMode,
            brushStart = brushStart,
            brushEnd = brushEnd,
            onBrushChange = onBrushChange,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(AndySpace.Space2))
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = AndySpace.Space3),
        ) {
            itemsIndexed(entries, key = { _, entry -> entry.key }) { _, entry ->
                val row = entry.row
                val selected = row != null && row.key == selectedRowKey
                val brushed = row != null && row.key in brushedKeys
                val brushActive = brushedKeys.isNotEmpty()
                val matches = !filtering || row == null || row.key in matchingKeys
                TimelineRowLine(
                    entry = entry,
                    selected = selected,
                    brushed = brushed,
                    dimmed = (filtering && !matches) || (brushActive && row != null && !brushed),
                    onClick = {
                        if (row != null) {
                            onSelectRow(if (selected) null else row.key)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TimelineToolbar(
    axis: TimelineAxis,
    onAxisChange: (TimelineAxis) -> Unit,
    durationMode: Boolean,
    onDurationModeChange: (Boolean) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AndySpace.Space2, vertical = AndySpace.Space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        FilterPill(
            text = "Duration",
            selected = durationMode,
            color = AndyColors.Blue,
            leadingContent = {
                LucideIcon(
                    Lucide.Clock,
                    if (durationMode) TextPrimary else TextSecondary,
                    Modifier.width(12.dp).height(12.dp),
                )
            },
            onClick = { onDurationModeChange(!durationMode) },
        )
        TimelineAxis.entries.forEach { value ->
            FilterPill(
                text = value.name,
                selected = axis == value,
                color = AndyColors.Blue,
                leadingContent = {
                    LucideIcon(
                        axisIcon(value),
                        if (axis == value) TextPrimary else TextSecondary,
                        Modifier.width(12.dp).height(12.dp),
                    )
                },
                onClick = { onAxisChange(value) },
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .widthIn(max = 220.dp)
                .background(AndyColors.SurfaceRaised, RoundedCornerShape(AndyRadius.Control))
                .border(1.dp, PaneDividerTint, RoundedCornerShape(AndyRadius.Control))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LucideIcon(Lucide.Search, TextSecondary, Modifier.width(14.dp).height(14.dp))
            TextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.weight(1f),
                chromeStyle = FieldChromeStyle.Borderless,
                placeholder = {
                    Text("Search", color = TextSecondary, fontFamily = DisplayFont, fontSize = 12.sp)
                },
                singleLine = true,
            )
        }
    }
}

@Composable
private fun TimelineRowLine(
    entry: TimelineListEntry,
    selected: Boolean,
    brushed: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    val bg = when {
        selected -> SelectionHighlight
        brushed -> BrushHighlight
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.35f else 1f)
            .background(bg)
            .then(
                if (selected) Modifier.border(1.dp, AndyColors.Blue.copy(alpha = 0.55f))
                else Modifier,
            )
            .clickable(enabled = entry.row != null, onClick = onClick)
            .padding(vertical = 3.dp)
            .testTag(if (entry.row != null) "chat-timeline-row:${entry.row.key}" else "chat-timeline-summary:${entry.turnNumber}"),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(56.dp).padding(start = 8.dp)) {
            if (entry.showTurnLabel) {
                Text(
                    "Turn ${entry.turnNumber}",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                )
            }
        }
        val row = entry.row
        if (row != null) {
            TimelineBadge(row.kind, row.badge)
            Spacer(Modifier.width(8.dp))
            Text(
                row.title,
                color = TextPrimary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            row.resultPreview?.let { preview ->
                Text(
                    " → $preview",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } ?: Spacer(Modifier.weight(1f))
        } else {
            Text(
                entry.summaryText.orEmpty(),
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun TimelineBadge(kind: TimelineRowKind, label: String) {
    val color = when (kind) {
        TimelineRowKind.System -> BadgeSystem
        TimelineRowKind.User -> BadgeUser
        TimelineRowKind.Context -> BadgeContext
        TimelineRowKind.Assistant -> BadgeAssistant
        TimelineRowKind.Thinking -> BadgeThinking
        TimelineRowKind.Tool -> BadgeTool
        TimelineRowKind.Result -> BadgeResult
        TimelineRowKind.Permission -> BadgePermission
        TimelineRowKind.FileChanges -> BadgeFiles
        TimelineRowKind.Error -> BadgeError
    }
    Text(
        label,
        color = Color.White,
        fontFamily = MonoFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 9.sp,
        modifier = Modifier
            .background(color, RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

internal fun timelineListEntries(model: TimelineModel, axis: TimelineAxis): List<TimelineListEntry> {
    if (model.rows.isEmpty()) return emptyList()
    return when (axis) {
        TimelineAxis.Turns -> {
            val byTurn = model.rows.groupBy { it.turnNumber }
            val out = ArrayList<TimelineListEntry>()
            model.turns.forEach { turn ->
                val rows = byTurn[turn.number].orEmpty()
                var first = true
                rows.filter { it.kind == TimelineRowKind.System || it.kind == TimelineRowKind.User }
                    .forEach { row ->
                        out += TimelineListEntry(
                            key = row.key,
                            turnNumber = turn.number,
                            showTurnLabel = first,
                            row = row,
                        )
                        first = false
                    }
                out += TimelineListEntry(
                    key = "turn-summary-${turn.number}",
                    turnNumber = turn.number,
                    showTurnLabel = first,
                    summaryText = turnSummaryLabel(turn),
                )
            }
            out
        }
        TimelineAxis.Calls -> {
            var lastTurn = -1
            model.rows.map { row ->
                val show = row.turnNumber != lastTurn
                lastTurn = row.turnNumber
                TimelineListEntry(
                    key = row.key,
                    turnNumber = row.turnNumber,
                    showTurnLabel = show,
                    row = row,
                )
            }
        }
    }
}

private fun turnSummaryLabel(turn: TimelineTurn): String {
    val steps = turn.stepCount
    val tools = turn.toolCallCount
    val stepLabel = if (steps == 1) "1 step" else "$steps steps"
    val toolLabel = if (tools == 1) "1 tool call" else "$tools tool calls"
    return "… $stepLabel · $toolLabel"
}

private fun axisIcon(axis: TimelineAxis): String = when (axis) {
    TimelineAxis.Turns -> Lucide.LayoutGrid
    TimelineAxis.Calls -> Lucide.MessageSquare
}
