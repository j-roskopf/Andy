@file:Suppress("DEPRECATION")

package app.andy.ui.agents

/**
 * Legacy structured-event transcript UI.
 *
 * The embedded-terminal redesign replaces this with [AgentTerminalSurface];
 * the PTY buffer is the transcript. Kept for unit tests and gradual removal.
 */
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.andy.loadImageBitmap
import app.andy.model.AgentEvent
import app.andy.model.stripDecisionCheckpointMarkup
import app.andy.model.AgentSkill
import app.andy.ui.components.AndyMarkdownDensity
import app.andy.ui.components.Button
import app.andy.ui.components.ChatMarkdown
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Explicit per-task scroll snapshot. */
internal data class TranscriptScrollPosition(
    val index: Int,
    val offset: Int,
    val stickToBottom: Boolean,
    /** Stable row identity keeps the same viewport when newer rows arrive while this chat is away. */
    val anchorKey: String? = null,
)

private sealed class TranscriptRestorePlan {
    data object StickToBottom : TranscriptRestorePlan()
    data class Exact(val index: Int, val offset: Int, val anchorKey: String?) : TranscriptRestorePlan()
}

/**
 * Remembers where each chat was scrolled. First open has no entry → stick to bottom.
 */
internal class TranscriptScrollMemory {
    private val positions = mutableMapOf<String, TranscriptScrollPosition>()

    fun get(taskId: String): TranscriptScrollPosition? = positions[taskId]

    fun save(taskId: String, position: TranscriptScrollPosition) {
        positions[taskId] = position
    }

    fun remove(taskId: String) {
        positions.remove(taskId)
    }
}

@Composable
internal fun AgentTranscript(
    events: List<AgentEvent>,
    isActive: Boolean,
    agentLabel: String = "agent",
    compactToolCalls: Boolean = true,
    headerContent: (@Composable () -> Unit)? = null,
    pendingContent: (@Composable () -> Unit)? = null,
    originalPrompt: String? = null,
    originalImagePaths: List<String> = emptyList(),
    completedContent: (@Composable () -> Unit)? = null,
    /**
     * False while a completed chat's transcript (and trailing UI) is still loading.
     * Prevents pinning to the prompt-only stub before history arrives.
     */
    eventsReady: Boolean = true,
    onSkillOpen: (AgentSkill) -> Unit = {},
    restoreScrollKey: String? = null,
    scrollMemory: TranscriptScrollMemory? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val displayItems = remember(events, compactToolCalls) { transcriptDisplayItems(events, compactToolCalls) }
    val originalPromptVisible = !originalPrompt.isNullOrBlank() || originalImagePaths.isNotEmpty()
    val latestTaskResultItemIndex = displayItems.indexOfLast { item ->
        item is TranscriptDisplayItem.Event && item.event is AgentEvent.TaskResult
    }
    val taskId = restoreScrollKey
    // Freeze restore intent for this visit. A bottom-origin list makes index 0 the live edge;
    // streamed rows can then grow upward without any imperative per-token scrolling.
    val restorePlan = remember(taskId) {
        val saved = taskId?.let { scrollMemory?.get(it) }
        when {
            saved == null || saved.stickToBottom -> TranscriptRestorePlan.StickToBottom
            else -> TranscriptRestorePlan.Exact(saved.index, saved.offset, saved.anchorKey)
        }
    }
    // Always start at the live edge. Exact restoration happens only after async history is
    // ready, so a prompt-only loading stub cannot clamp a saved index back to zero.
    val listState = remember(taskId) { LazyListState(0, 0) }
    var stickToBottom by remember(taskId) {
        mutableStateOf(restorePlan is TranscriptRestorePlan.StickToBottom)
    }
    var scrollInitialized by remember(taskId) { mutableStateOf(false) }
    var expandedToolKeys by remember(taskId) { mutableStateOf(setOf<String>()) }
    var expandedToolGroups by remember(taskId) { mutableStateOf(setOf<String>()) }
    // Desktop wheel/trackpad input can complete without isScrollInProgress ever becoming true.
    var userScrollGeneration by remember(taskId) { mutableStateOf(0) }
    val rowKeys = remember(
        displayItems,
        isActive,
        originalPromptVisible,
        pendingContent != null,
        headerContent != null,
    ) {
        buildList {
            if (pendingContent != null) add("pending-task-input")
            if (isActive) add("agent-thinking")
            displayItems.asReversed().forEach { add(transcriptDisplayItemKey(it)) }
            if (originalPromptVisible) add("original-prompt")
            if (headerContent != null) add("task-header")
        }
    }

    LaunchedEffect(taskId, eventsReady, listState.layoutInfo.totalItemsCount, rowKeys) {
        if (scrollInitialized || !eventsReady) return@LaunchedEffect
        val itemCount = listState.layoutInfo.totalItemsCount
        when (val plan = restorePlan) {
            TranscriptRestorePlan.StickToBottom -> {
                // Index zero is bottom in reverseLayout. No settling loop or post-layout nudge.
                stickToBottom = true
                scrollInitialized = true
            }
            is TranscriptRestorePlan.Exact -> {
                if (itemCount == 0) return@LaunchedEffect
                val anchoredIndex = plan.anchorKey
                    ?.let(rowKeys::indexOf)
                    ?.takeIf { it >= 0 }
                listState.scrollToItem(
                    index = (anchoredIndex ?: plan.index).coerceIn(0, itemCount - 1),
                    scrollOffset = plan.offset,
                )
                stickToBottom = false
                scrollInitialized = true
            }
        }
    }

    // Persist each conversation independently. Streaming does not touch these coordinates
    // when detached because its changing rows live below the visible reverse-layout anchor.
    LaunchedEffect(taskId, listState, scrollInitialized) {
        if (!scrollInitialized) return@LaunchedEffect
        val id = taskId ?: return@LaunchedEffect
        val memory = scrollMemory ?: return@LaunchedEffect
        snapshotFlow {
            TranscriptScrollPosition(
                index = listState.firstVisibleItemIndex,
                offset = listState.firstVisibleItemScrollOffset,
                stickToBottom = stickToBottom,
                anchorKey = listState.firstVisibleAnchorKey(),
            )
        }.distinctUntilChanged().collect { memory.save(id, it) }
    }
    DisposableEffect(taskId, listState, scrollInitialized, stickToBottom) {
        onDispose {
            if (scrollInitialized && taskId != null && scrollMemory != null) {
                scrollMemory.save(
                    taskId,
                    TranscriptScrollPosition(
                        index = listState.firstVisibleItemIndex,
                        offset = listState.firstVisibleItemScrollOffset,
                        stickToBottom = stickToBottom,
                        anchorKey = listState.firstVisibleAnchorKey(),
                    ),
                )
            }
        }
    }

    // Detect non-pointer scrolling (keyboard, accessibility, scrollbar). Position changes do
    // not drive auto-scroll; they only re-arm following once index zero is reached exactly.
    LaunchedEffect(taskId, listState, scrollInitialized) {
        if (!scrollInitialized) return@LaunchedEffect
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress,
            )
        }.distinctUntilChanged().collect { (index, offset, inProgress) ->
            if (transcriptIsAtBottom(index, offset)) {
                stickToBottom = true
            } else if (inProgress) {
                stickToBottom = false
            }
        }
    }
    LaunchedEffect(userScrollGeneration, scrollInitialized) {
        if (!scrollInitialized || userScrollGeneration == 0) return@LaunchedEffect
        // Let wheel/trackpad input settle. A blocked downward tick at the live edge has no
        // position change, so explicitly re-arm in that case.
        withFrameMillis { }
        withFrameMillis { }
        if (transcriptIsAtBottom(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)) {
            stickToBottom = true
        }
    }

    fun jumpToLatest() {
        stickToBottom = true
        scope.launch { listState.scrollToItem(0) }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(AndyRadius.R4))
            .background(AndyColors.Neutral900.copy(alpha = 0.38f))
            .border(1.dp, Border.copy(alpha = 0.76f), RoundedCornerShape(AndyRadius.R4)),
    ) {
        if (events.isEmpty() && !originalPromptVisible && !isActive) {
            EmptyState("waiting for agent output")
        } else {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("transcript-list")
                    .graphicsLayer { alpha = if (scrollInitialized) 1f else 0f }
                    .padding(end = 8.dp)
                    .pointerInput(taskId) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                if (event.type == PointerEventType.Scroll) {
                                    // Detach before the wheel delta is applied. New streaming
                                    // content then grows below this viewport without moving it.
                                    stickToBottom = false
                                    userScrollGeneration++
                                }
                            }
                        }
                    },
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // reverseLayout lays index zero at the visual bottom, so declare rows newest
                // first while preserving the transcript's chronological reading order.
                if (pendingContent != null) {
                    item(key = "pending-task-input", contentType = "request") { pendingContent() }
                }
                if (isActive) {
                    item(key = "agent-thinking", contentType = "presence") { AgentThinkingIndicator() }
                }
                items(
                    count = displayItems.size,
                    key = { reversedIndex ->
                        transcriptDisplayItemKey(displayItems[displayItems.lastIndex - reversedIndex])
                    },
                    contentType = { reversedIndex ->
                        when (displayItems[displayItems.lastIndex - reversedIndex]) {
                            is TranscriptDisplayItem.Event -> "event"
                            is TranscriptDisplayItem.ToolCalls -> "tool-group"
                        }
                    },
                ) { reversedIndex ->
                    val itemIndex = displayItems.lastIndex - reversedIndex
                    val item = displayItems[itemIndex]
                    SelectionContainer(
                        modifier = Modifier.testTag("transcript-row-${transcriptDisplayItemKey(item)}"),
                    ) {
                        when (item) {
                            is TranscriptDisplayItem.Event -> TranscriptEvent(
                                event = item.event,
                                eventKey = transcriptEventKey(item.index, item.event),
                                expandedToolKeys = expandedToolKeys,
                                agentLabel = agentLabel,
                                completedContent = if (itemIndex == latestTaskResultItemIndex) completedContent else null,
                                onToolExpandedChange = { key, expanded ->
                                    expandedToolKeys = if (expanded) expandedToolKeys + key else expandedToolKeys - key
                                },
                                onSkillOpen = onSkillOpen,
                            )
                            is TranscriptDisplayItem.ToolCalls -> CompactToolCallsBlock(
                                events = item.events,
                                startIndex = item.startIndex,
                                expanded = transcriptDisplayItemKey(item) in expandedToolGroups,
                                onExpandedChange = { expanded ->
                                    val key = transcriptDisplayItemKey(item)
                                    expandedToolGroups = if (expanded) expandedToolGroups + key else expandedToolGroups - key
                                },
                                expandedToolKeys = expandedToolKeys,
                                onToolExpandedChange = { key, expanded ->
                                    expandedToolKeys = if (expanded) expandedToolKeys + key else expandedToolKeys - key
                                },
                            )
                        }
                    }
                }
                if (originalPromptVisible) {
                    item(key = "original-prompt", contentType = "message") {
                        SelectionContainer {
                            ChatMessageBubble(
                                author = "you",
                                authorColor = Rust,
                                alignEnd = true,
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    originalPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
                                        ChatMarkdown(prompt, lineHeight = 18.sp)
                                    }
                                    ChatAttachedImages(originalImagePaths)
                                }
                            }
                        }
                    }
                }
                if (headerContent != null) {
                    item(key = "task-header", contentType = "header") { headerContent() }
                }
            }
            DraggableScrollbar(
                listState = listState,
                reverseLayout = true,
                onScroll = { stickToBottom = false },
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
            AnimatedVisibility(
                visible = scrollInitialized && !stickToBottom,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
            ) {
                Button(
                    onClick = ::jumpToLatest,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                    shape = RoundedCornerShape(AndyRadius.Pill),
                ) {
                    Text(
                        if (isActive) "↓  follow live" else "↓  latest",
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** Bottom is an invariant instead of a layout estimate in the reverse transcript. */
internal fun transcriptIsAtBottom(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int): Boolean =
    firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset <= 1

private fun LazyListState.firstVisibleAnchorKey(): String? = layoutInfo.visibleItemsInfo
    .firstOrNull { it.index == firstVisibleItemIndex }
    ?.key
    ?.toString()

/**
 * Providers commonly emit the final response once as an assistant message and
 * again in their completion record. The completion record owns that response
 * in the transcript so it is visible once, with its completed state.
 */
internal fun transcriptDisplayEvents(events: List<AgentEvent>): List<AgentEvent> = events.filterIndexed { index, event ->
    val completion = events.getOrNull(index + 1) as? AgentEvent.TaskResult
    event !is AgentEvent.AssistantText || completion?.finalText?.trim() != event.text.trim()
}

internal sealed class TranscriptDisplayItem {
    data class Event(val index: Int, val event: AgentEvent) : TranscriptDisplayItem()
    data class ToolCalls(val startIndex: Int, val events: List<AgentEvent>) : TranscriptDisplayItem()
}

internal fun transcriptDisplayItems(
    events: List<AgentEvent>,
    compactToolCalls: Boolean,
): List<TranscriptDisplayItem> {
    val display = transcriptDisplayEvents(events).filterNot { it is AgentEvent.ContextUsage }
    if (!compactToolCalls) {
        return display.mapIndexed { index, event -> TranscriptDisplayItem.Event(index, event) }
    }
    val items = mutableListOf<TranscriptDisplayItem>()
    var index = 0
    while (index < display.size) {
        val event = display[index]
        if (!event.isToolTranscriptEvent()) {
            items += TranscriptDisplayItem.Event(index, event)
            index += 1
            continue
        }
        val startIndex = index
        val group = mutableListOf<AgentEvent>()
        while (index < display.size && display[index].isToolTranscriptEvent()) {
            group += display[index]
            index += 1
        }
        if (group.size == 1) {
            items += TranscriptDisplayItem.Event(startIndex, group.single())
        } else {
            items += TranscriptDisplayItem.ToolCalls(startIndex, group)
        }
    }
    return items
}

private fun AgentEvent.isToolTranscriptEvent(): Boolean =
    this is AgentEvent.ToolCall || this is AgentEvent.ToolResult

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
        is AgentEvent.AssistantText -> {
            val visibleText = stripDecisionCheckpointMarkup(event.text)
            if (visibleText.isBlank()) return
            ChatMessageBubble(
                author = agentLabel,
                authorColor = Cyan,
                alignEnd = false,
            ) {
                ChatMarkdown(visibleText, lineHeight = 19.sp)
            }
        }
        is AgentEvent.Thinking -> ThinkingStep(event.text)
        is AgentEvent.UserMessage -> ChatMessageBubble(
            author = "you",
            authorColor = Rust,
            alignEnd = true,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (event.text.isNotBlank()) {
                    ChatMarkdown(event.text, lineHeight = 18.sp)
                }
                ChatAttachedImages(event.imagePaths)
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
                    ChatMarkdown(stripDecisionCheckpointMarkup(it), lineHeight = 18.sp)
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
    // Open aside — left accent only, no filled card, so it reads lighter than chat/tool blocks.
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(Cyan.copy(alpha = 0.28f), RoundedCornerShape(1.dp)),
        )
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("···", color = Cyan.copy(alpha = 0.75f), fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text("thinking", color = Cyan.copy(alpha = 0.65f), fontFamily = MonoFont, fontSize = 10.sp)
            }
            ChatMarkdown(
                text,
                density = AndyMarkdownDensity.Thinking,
                lineHeight = 15.sp,
                modifier = Modifier.fillMaxWidth().heightIn(max = 60.dp).clipToBounds(),
            )
        }
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

@Composable
internal fun ChatAttachedImages(
    paths: List<String>,
    onRemove: ((String) -> Unit)? = null,
    maxWidth: Dp = 260.dp,
    maxHeight: Dp = 180.dp,
) {
    if (paths.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        paths.forEach { path ->
            ChatAttachedImage(
                path = path,
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                onRemove = onRemove?.let { remove -> { remove(path) } },
            )
        }
    }
}

@Composable
private fun ChatAttachedImage(
    path: String,
    maxWidth: Dp = 260.dp,
    maxHeight: Dp = 180.dp,
    onRemove: (() -> Unit)? = null,
) {
    val fileName = path.substringAfterLast('/').substringAfterLast('\\')
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.Default) {
            runCatching { loadImageBitmap(path) }.getOrNull()
        }
    }
    var previewOpen by remember(path) { mutableStateOf(false) }
    val image = bitmap
    DisableSelection {
        Box(
            Modifier
                .widthIn(max = maxWidth)
                .heightIn(max = maxHeight)
                .clip(RoundedCornerShape(AndyRadius.R2))
                .background(AndyColors.Neutral900.copy(alpha = 0.72f))
                .border(1.dp, Border.copy(alpha = 0.75f), RoundedCornerShape(AndyRadius.R2))
                .then(
                    if (image != null) {
                        Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { previewOpen = true }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = fileName,
                    modifier = Modifier
                        .widthIn(max = maxWidth)
                        .heightIn(max = maxHeight),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    fileName.ifBlank { "image" },
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                )
            }
            if (onRemove != null) {
                Text(
                    "×",
                    color = TextPrimary,
                    fontFamily = MonoFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(AndyRadius.R2))
                        .background(AndyColors.Neutral900.copy(alpha = 0.82f))
                        .border(1.dp, Border.copy(alpha = 0.8f), RoundedCornerShape(AndyRadius.R2))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClick = onRemove)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
    if (previewOpen && image != null) {
        ChatImagePreviewDialog(
            bitmap = image,
            fileName = fileName,
            onDismiss = { previewOpen = false },
        )
    }
}

@Composable
private fun ChatImagePreviewDialog(
    bitmap: ImageBitmap,
    fileName: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .widthIn(max = 1100.dp)
                .heightIn(max = 860.dp)
                .background(AndyColors.Neutral900.copy(alpha = 0.96f), RoundedCornerShape(AndyRadius.R3))
                .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                fileName.ifBlank { "image" },
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Image(
                bitmap = bitmap,
                contentDescription = fileName,
                modifier = Modifier
                    .widthIn(max = 1060.dp)
                    .heightIn(max = 780.dp),
                contentScale = ContentScale.Fit,
            )
            Text(
                "click to close",
                color = TextSecondary.copy(alpha = 0.8f),
                fontFamily = MonoFont,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun CompactToolCallsBlock(
    events: List<AgentEvent>,
    startIndex: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    expandedToolKeys: Set<String>,
    onToolExpandedChange: (String, Boolean) -> Unit,
) {
    val toolNames = events.mapNotNull { event ->
        when (event) {
            is AgentEvent.ToolCall -> event.toolName.takeIf { it.isNotBlank() }
            is AgentEvent.ToolResult -> event.toolName?.takeIf { it.isNotBlank() }
            else -> null
        }
    }.distinct()
    val maxHeadlineNames = 3
    val headline = buildString {
        append(events.size)
        append(if (events.size == 1) " tool" else " tools")
        if (toolNames.isNotEmpty()) {
            append(": ")
            append(toolNames.take(maxHeadlineNames).joinToString(", "))
            if (toolNames.size > maxHeadlineNames) append(", …")
        }
    }
    val hasError = events.any { it is AgentEvent.ToolResult && it.isError }
    val color = if (hasError) Red else Cyan

    Column(
        Modifier.fillMaxWidth()
            .animateContentSize()
            .background(AndyColors.Neutral850.copy(alpha = 0.55f), RoundedCornerShape(AndyRadius.R2))
            .border(1.dp, Border.copy(alpha = 0.65f), RoundedCornerShape(AndyRadius.R2))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DisableSelection {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (expanded) "v" else ">",
                    color = color,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.width(10.dp),
                )
                Text(
                    headline,
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                events.forEachIndexed { offset, event ->
                    val eventKey = transcriptEventKey(startIndex + offset, event)
                    when (event) {
                        is AgentEvent.ToolCall -> ToolBlock(
                            expanded = eventKey in expandedToolKeys,
                            onExpandedChange = { value -> onToolExpandedChange(eventKey, value) },
                            marker = "▸",
                            name = event.toolName,
                            summary = event.summary,
                            detail = event.detail,
                            color = Cyan,
                        )
                        is AgentEvent.ToolResult -> ToolBlock(
                            expanded = eventKey in expandedToolKeys,
                            onExpandedChange = { value -> onToolExpandedChange(eventKey, value) },
                            marker = if (event.isError) "✗" else "✓",
                            name = event.toolName,
                            summary = event.summary,
                            detail = event.detail,
                            color = if (event.isError) Red else TextSecondary,
                        )
                        else -> Unit
                    }
                }
            }
        }
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
                } else {
                    Modifier
                },
            )
            .padding(horizontal = if (expandable) 10.dp else 0.dp, vertical = if (expandable) 10.dp else 0.dp),
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

/**
 * Lazy identity for a transcript row. Must stay stable while streamed text / tool
 * groups grow in place — putting size, text, or summary hashes here remounts the
 * row every token and reads as flicker.
 */
internal fun transcriptDisplayItemKey(item: TranscriptDisplayItem): String = when (item) {
    is TranscriptDisplayItem.Event -> transcriptEventKey(item.index, item.event)
    is TranscriptDisplayItem.ToolCalls -> {
        val first = item.events.firstOrNull()
        "tool-group-${item.startIndex}-${first?.atMillis ?: 0}"
    }
}

internal fun transcriptEventKey(index: Int, event: AgentEvent): String = when (event) {
    is AgentEvent.ToolCall -> "tool-call-$index-${event.atMillis}-${event.toolName}"
    is AgentEvent.ToolResult -> "tool-result-$index-${event.atMillis}-${event.toolName}-${event.isError}"
    else -> "${event::class.simpleName}-$index-${event.atMillis}"
}
