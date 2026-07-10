package app.andy.ui.agents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AgentEvent
import app.andy.model.AgentSkill
import app.andy.ui.components.DraggableScrollbar
import app.andy.ui.components.EmptyState
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
internal fun AgentTranscript(
    events: List<AgentEvent>,
    isActive: Boolean,
    onSkillOpen: (AgentSkill) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var stickToBottom by remember { mutableStateOf(true) }
    var expandedToolKeys by remember { mutableStateOf(setOf<String>()) }
    val isAtBottom by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            if (total == 0) true else (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= total - 1
        }
    }
    // Stream deltas replace the final item in place, so size alone does not
    // change while a long assistant message is growing.
    LaunchedEffect(events.lastOrNull(), isActive, stickToBottom) {
        if (stickToBottom && (events.isNotEmpty() || isActive)) {
            listState.scrollToItem(if (isActive) events.size else events.lastIndex)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to isAtBottom }
            .distinctUntilChanged()
            .collect { (scrolling, atBottom) ->
                if (scrolling && !atBottom) stickToBottom = false
                if (atBottom) stickToBottom = true
            }
    }
    Box(modifier.background(AndyColors.Neutral900.copy(alpha = 0.72f), RoundedCornerShape(AndyRadius.R3)).border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))) {
        if (events.isEmpty() && !isActive) {
            EmptyState("waiting for agent output")
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(10.dp).padding(end = 8.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(
                    events,
                    key = { index, event -> transcriptEventKey(index, event) },
                ) { index, event ->
                    TranscriptEvent(
                        event = event,
                        eventKey = transcriptEventKey(index, event),
                        expandedToolKeys = expandedToolKeys,
                        onToolExpandedChange = { key, expanded ->
                            expandedToolKeys = if (expanded) expandedToolKeys + key else expandedToolKeys - key
                        },
                        onSkillOpen = onSkillOpen,
                    )
                }
                if (isActive) {
                    item(key = "agent-thinking") { AgentThinkingIndicator() }
                }
            }
            DraggableScrollbar(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                visibleItems = listState.layoutInfo.visibleItemsInfo.size,
                totalItems = listState.layoutInfo.totalItemsCount,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                onDragToIndex = { index ->
                    stickToBottom = index >= (listState.layoutInfo.totalItemsCount - listState.layoutInfo.visibleItemsInfo.size - 1)
                    scope.launch { listState.scrollToItem(index.coerceAtLeast(0)) }
                },
            )
        }
    }
}

@Composable
private fun AgentThinkingIndicator() {
    val pulse = rememberInfiniteTransition(label = "agent_thinking_pulse")
    val alpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "agent_thinking_alpha",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("···", color = Cyan, fontFamily = MonoFont, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("thinking", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
    }
}

@Composable
private fun TranscriptEvent(
    event: AgentEvent,
    eventKey: String,
    expandedToolKeys: Set<String>,
    onToolExpandedChange: (String, Boolean) -> Unit,
    onSkillOpen: (AgentSkill) -> Unit,
) {
    when (event) {
        is AgentEvent.SessionStarted -> Text(
            "session started${event.model?.let { " · $it" }.orEmpty()}",
            color = TextSecondary,
            fontFamily = MonoFont,
            fontSize = 11.sp,
        )
        is AgentEvent.AssistantText -> Text(
            event.text,
            color = TextPrimary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(vertical = 2.dp),
        )
        is AgentEvent.Thinking -> Text(
            event.text,
            color = TextSecondary,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            fontStyle = FontStyle.Italic,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        is AgentEvent.UserMessage -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("you", color = Rust, fontFamily = MonoFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(event.text, color = TextPrimary, fontSize = 13.sp, lineHeight = 18.sp)
                if (event.skills.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        event.skills.forEach { skill ->
                            Text(
                                "/${skill.name}",
                                color = Cyan,
                                fontFamily = MonoFont,
                                fontSize = 11.sp,
                                textDecoration = TextDecoration.Underline,
                                modifier = Modifier.clickable { onSkillOpen(skill) },
                            )
                        }
                    }
                }
            }
        }
        is AgentEvent.ToolCall -> ToolBlock(
            expanded = eventKey in expandedToolKeys,
            onExpandedChange = { expanded -> onToolExpandedChange(eventKey, expanded) },
            marker = "▸",
            name = event.toolName,
            summary = event.summary,
            detail = event.detail,
            color = Cyan,
        )
        is AgentEvent.ToolResult -> ToolBlock(
            expanded = eventKey in expandedToolKeys,
            onExpandedChange = { expanded -> onToolExpandedChange(eventKey, expanded) },
            marker = if (event.isError) "✗" else "✓",
            name = event.toolName,
            summary = event.summary,
            detail = event.detail,
            color = if (event.isError) Red else TextSecondary,
        )
        is AgentEvent.TaskError -> Text(event.message, color = Red, fontFamily = MonoFont, fontSize = 12.sp, lineHeight = 16.sp)
        is AgentEvent.TaskResult -> Column(
            Modifier.fillMaxWidth()
                .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R2))
                .border(1.dp, (if (event.success) Green else Red).copy(alpha = 0.35f), RoundedCornerShape(AndyRadius.R2))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (event.success) "done" else "failed",
                    color = if (event.success) Green else Red,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                formatCost(event.costUsd, event.costIsEstimated)?.let { Text(it, color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp) }
                formatTokens(event.inputTokens, event.outputTokens)?.let { Text(it, color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp) }
            }
            event.finalText?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = TextPrimary, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        is AgentEvent.Raw -> Text(
            event.line,
            color = TextSecondary.copy(alpha = 0.75f),
            fontFamily = MonoFont,
            fontSize = 11.sp,
            lineHeight = 14.sp,
        )
    }
}

@Composable
private fun ToolBlock(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    marker: String,
    name: String?,
    summary: String,
    detail: String,
    color: Color,
) {
    val headline = listOfNotNull(name?.takeIf { it.isNotBlank() }, summary.takeIf { it.isNotBlank() }).joinToString(": ")
    val body = detail.ifBlank { summary }.ifBlank { name.orEmpty() }
    val expandable = headline.isNotBlank() || body.isNotBlank()

    Column(
        Modifier.fillMaxWidth()
            .animateContentSize()
            .then(
                if (expandable) {
                    Modifier
                        .background(AndyColors.Neutral850.copy(alpha = 0.55f), RoundedCornerShape(AndyRadius.R2))
                        .border(1.dp, Border.copy(alpha = 0.65f), RoundedCornerShape(AndyRadius.R2))
                        .clickable { onExpandedChange(!expanded) }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = if (expandable) 8.dp else 0.dp, vertical = if (expandable) 5.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
            Text(
                if (expandable) if (expanded) "v" else ">" else marker,
                color = color,
                fontFamily = MonoFont,
                fontSize = 12.sp,
                modifier = if (expandable) Modifier.width(10.dp) else Modifier,
            )
            Text(
                headline.ifBlank { name.orEmpty() },
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        AnimatedVisibility(visible = expanded && body.isNotBlank()) {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .background(Color.Black.copy(alpha = 0.28f), RoundedCornerShape(AndyRadius.R2))
                    .padding(horizontal = 8.dp, vertical = 7.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    body,
                    color = TextPrimary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

private fun transcriptEventKey(index: Int, event: AgentEvent): String = when (event) {
    is AgentEvent.ToolCall -> "tool-call-$index-${event.atMillis}-${event.toolName}-${event.summary.hashCode()}"
    is AgentEvent.ToolResult -> "tool-result-$index-${event.atMillis}-${event.toolName}-${event.summary.hashCode()}-${event.isError}"
    else -> "${event::class.simpleName}-$index-${event.atMillis}"
}
