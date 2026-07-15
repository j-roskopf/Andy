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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
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
    agentLabel: String = "agent",
    headerContent: (@Composable () -> Unit)? = null,
    pendingContent: (@Composable () -> Unit)? = null,
    originalPrompt: String? = null,
    completedContent: (@Composable () -> Unit)? = null,
    onSkillOpen: (AgentSkill) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val displayEvents = remember(events) { transcriptDisplayEvents(events) }
    val originalPromptVisible = originalPrompt?.takeIf { it.isNotBlank() }
    val latestTaskResultIndex = displayEvents.indexOfLast { it is AgentEvent.TaskResult }
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
    val transcriptItemCount = displayEvents.size + (if (headerContent != null) 1 else 0) + (if (pendingContent != null) 1 else 0) + (if (originalPromptVisible != null) 1 else 0) + if (isActive) 1 else 0
    LaunchedEffect(displayEvents.lastOrNull(), headerContent != null, pendingContent != null, originalPromptVisible, isActive, stickToBottom) {
        if (stickToBottom && transcriptItemCount > 0) {
            listState.scrollToItem(transcriptItemCount - 1)
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
    Box(
        modifier
            .background(AndyColors.Neutral900.copy(alpha = 0.45f), RoundedCornerShape(AndyRadius.R4))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R4)),
    ) {
        if (events.isEmpty() && originalPromptVisible == null && !isActive) {
            EmptyState("waiting for agent output")
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp).padding(end = 8.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                headerContent?.let { header ->
                    item(key = "task-header") { header() }
                }
                pendingContent?.let { content ->
                    item(key = "pending-task-input") { content() }
                }
                originalPromptVisible?.let { prompt ->
                    item(key = "original-prompt") {
                        SelectionContainer {
                            ChatMessageBubble(
                                author = "you",
                                authorColor = Rust,
                                alignEnd = true,
                            ) {
                                MarkdownMessageText(prompt, lineHeight = 18.sp)
                            }
                        }
                    }
                }
                itemsIndexed(
                    displayEvents,
                    key = { index, event -> transcriptEventKey(index, event) },
                ) { index, event ->
                    SelectionContainer {
                        TranscriptEvent(
                            event = event,
                            eventKey = transcriptEventKey(index, event),
                            expandedToolKeys = expandedToolKeys,
                            agentLabel = agentLabel,
                            completedContent = if (index == latestTaskResultIndex) completedContent else null,
                            onToolExpandedChange = { key, expanded ->
                                expandedToolKeys = if (expanded) expandedToolKeys + key else expandedToolKeys - key
                            },
                            onSkillOpen = onSkillOpen,
                        )
                    }
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

/**
 * Providers commonly emit the final response once as an assistant message and
 * again in their completion record. The completion record owns that response
 * in the transcript so it is visible once, with its completed state.
 */
internal fun transcriptDisplayEvents(events: List<AgentEvent>): List<AgentEvent> = events.filterIndexed { index, event ->
    val completion = events.getOrNull(index + 1) as? AgentEvent.TaskResult
    event !is AgentEvent.AssistantText || completion?.finalText?.trim() != event.text.trim()
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
    agentLabel: String,
    completedContent: (@Composable () -> Unit)?,
    onToolExpandedChange: (String, Boolean) -> Unit,
    onSkillOpen: (AgentSkill) -> Unit,
) {
    when (event) {
        is AgentEvent.SessionStarted -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "${event.model ?: agentLabel}  session started",
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 10.sp,
            )
        }
        is AgentEvent.AssistantText -> ChatMessageBubble(
            author = agentLabel,
            authorColor = Cyan,
            alignEnd = false,
        ) {
            MarkdownMessageText(event.text, lineHeight = 19.sp)
        }
        is AgentEvent.Thinking -> ThinkingStep(event.text)
        is AgentEvent.UserMessage -> ChatMessageBubble(
            author = "you",
            authorColor = Rust,
            alignEnd = true,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MarkdownMessageText(event.text, lineHeight = 18.sp)
                if (event.skills.isNotEmpty()) {
                    DisableSelection {
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
        is AgentEvent.TaskResult -> ChatMessageBubble(
            author = if (event.success) "completed" else "failed",
            authorColor = if (event.success) Green else Red,
            alignEnd = false,
            borderColor = (if (event.success) Green else Red).copy(alpha = 0.38f),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    formatCost(event.costUsd, event.costIsEstimated)?.let { Text(it, color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp) }
                    formatTokens(event.inputTokens, event.outputTokens)?.let { Text(it, color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp) }
                }
                event.finalText?.takeIf { it.isNotBlank() }?.let {
                    MarkdownMessageText(it, lineHeight = 18.sp)
                }
                if (event.success) completedContent?.invoke()
            }
        }
        // The header owns this live status; a transcript row would only add noise.
        is AgentEvent.ContextUsage -> Unit
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
private fun ThinkingStep(text: String) {
    Column(
        Modifier.fillMaxWidth()
            .background(AndyColors.Neutral850.copy(alpha = 0.46f), RoundedCornerShape(AndyRadius.R2))
            .border(1.dp, Cyan.copy(alpha = 0.18f), RoundedCornerShape(AndyRadius.R2))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("···", color = Cyan, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("thinking", color = Cyan.copy(alpha = 0.82f), fontFamily = MonoFont, fontSize = 10.sp)
        }
        Text(
            text,
            color = TextSecondary,
            fontFamily = MonoFont,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChatMessageBubble(
    author: String,
    authorColor: Color,
    alignEnd: Boolean,
    borderColor: Color = Border,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .widthIn(max = if (alignEnd) 720.dp else 860.dp)
                .fillMaxWidth()
                .align(if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart)
                .background(
                    if (alignEnd) AndyColors.OrangeSubtle.copy(alpha = 0.72f) else AndyColors.Neutral850.copy(alpha = 0.90f),
                    RoundedCornerShape(AndyRadius.R4),
                )
                .border(1.dp, borderColor, RoundedCornerShape(AndyRadius.R4))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(author, color = authorColor, fontFamily = MonoFont, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/**
 * Renders safe inline markdown that is common in agent replies. URLs are kept
 * as Compose [LinkAnnotation]s so the platform handles opening them, while
 * malformed or unsupported markdown stays visible as the original text.
 */
@Composable
private fun MarkdownMessageText(
    text: String,
    lineHeight: androidx.compose.ui.unit.TextUnit,
) {
    val annotated = remember(text) { parseChatMarkdown(text) }
    Text(annotated, color = TextPrimary, fontSize = 13.sp, lineHeight = lineHeight)
}

internal fun parseChatMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var currentIndex = 0
    MarkdownLinkPattern.findAll(text).forEach { match ->
        append(text.substring(currentIndex, match.range.first))
        val label = match.groupValues[1]
        val url = match.groupValues[2]
        withLink(
            LinkAnnotation.Url(
                url = url,
                styles = TextLinkStyles(
                    style = SpanStyle(color = Cyan, textDecoration = TextDecoration.Underline),
                    hoveredStyle = SpanStyle(color = Cyan.copy(alpha = 0.78f)),
                ),
            ),
        ) {
            append(label)
        }
        currentIndex = match.range.last + 1
    }
    append(text.substring(currentIndex))
}

private val MarkdownLinkPattern = Regex("""\[([^\]\n]+)]\((https?://[^\s)]+)\)""", RegexOption.IGNORE_CASE)

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
                } else {
                    Modifier
                },
            )
            .padding(horizontal = if (expandable) 8.dp else 0.dp, vertical = if (expandable) 5.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DisableSelection {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (expandable) Modifier.clickable { onExpandedChange(!expanded) } else Modifier),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
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
