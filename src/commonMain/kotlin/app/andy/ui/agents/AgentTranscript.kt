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
import app.andy.model.isRetriableConnectionStallMessage
import app.andy.model.shouldShowConnectionStallBanner
import app.andy.model.stripDecisionCheckpointMarkup
import app.andy.model.AgentSkill
import app.andy.model.coalesceAcpTranscriptEvents
import app.andy.model.coalesceAgentStreamDeltas
import app.andy.ui.components.AndyMarkdownDensity
import app.andy.ui.components.AndyHorizontalDivider
import app.andy.ui.components.Button
import app.andy.ui.components.ChatMarkdown
import app.andy.ui.components.DraggableScrollbar
import app.andy.ui.components.EmptyState
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PanelCard
import app.andy.ui.components.ThinkingOrb
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyOverlay
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    headerContent: (@Composable () -> Unit)? = null,
    pendingContent: (@Composable () -> Unit)? = null,
    /** When set, the matching [AgentEvent.PermissionRequest] row is omitted (shown via [pendingContent]). */
    activePermissionRequestId: String? = null,
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
    val displayItems = remember(events) { transcriptDisplayItems(events) }
    val originalPromptVisible = shouldDisplayOriginalPrompt(events, originalPrompt, originalImagePaths)
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
    var expandedThinkingKeys by remember(taskId) { mutableStateOf(setOf<String>()) }
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
            .clip(RoundedCornerShape(AndyRadius.Row))
            .background(AndyColors.Neutral900.copy(alpha = 0.38f))
            .border(1.dp, Border.copy(alpha = 0.76f), RoundedCornerShape(AndyRadius.Row)),
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
                                expandedThinkingKeys = expandedThinkingKeys,
                                agentLabel = agentLabel,
                                completedContent = if (itemIndex == latestTaskResultItemIndex) completedContent else null,
                                activePermissionRequestId = activePermissionRequestId,
                                onToolExpandedChange = { key, expanded ->
                                    expandedToolKeys = if (expanded) expandedToolKeys + key else expandedToolKeys - key
                                },
                                onThinkingExpandedChange = { key, expanded ->
                                    expandedThinkingKeys = if (expanded) expandedThinkingKeys + key else expandedThinkingKeys - key
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
                                alignEnd = true,
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    originalPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
                                        ChatMarkdown(prompt, lineHeight = 18.sp, preserveLineBreaks = true)
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

/**
 * ACP records the user turn in the transcript, while terminal tasks still need the task prompt
 * fallback. Do not render both representations when a recorded user turn is already present.
 */
internal fun shouldDisplayOriginalPrompt(
    events: List<AgentEvent>,
    originalPrompt: String?,
    originalImagePaths: List<String>,
): Boolean = events.none { it is AgentEvent.UserMessage } &&
    (!originalPrompt.isNullOrBlank() || originalImagePaths.isNotEmpty())

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
internal fun transcriptDisplayEvents(events: List<AgentEvent>): List<AgentEvent> {
    val coalesced = coalesceAcpTranscriptEvents(events)
    val displayable = coalesced.filterNot { event ->
        event is AgentEvent.AvailableCommands ||
            event is AgentEvent.Raw ||
            event.isHiddenConnectionStallMessage()
    }
    return displayable.filterIndexed { index, event ->
        val completion = displayable.getOrNull(index + 1) as? AgentEvent.TaskResult
        event !is AgentEvent.AssistantText || completion?.finalText?.trim() != event.text.trim()
    }
}

internal sealed class TranscriptDisplayItem {
    data class Event(val index: Int, val event: AgentEvent) : TranscriptDisplayItem()
    data class ToolCalls(val startIndex: Int, val events: List<AgentEvent>) : TranscriptDisplayItem()
}

internal fun transcriptDisplayItems(
    events: List<AgentEvent>,
): List<TranscriptDisplayItem> {
    val display = transcriptDisplayEvents(events).filterNot { it is AgentEvent.ContextUsage }
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
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThinkingOrb(size = 18.dp, color = Cyan, contentDescription = "Thinking")
        Text("thinking", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
    }
}

@Composable
private fun TranscriptEvent(
    event: AgentEvent,
    eventKey: String,
    expandedToolKeys: Set<String>,
    expandedThinkingKeys: Set<String>,
    agentLabel: String,
    completedContent: (@Composable () -> Unit)?,
    activePermissionRequestId: String? = null,
    onToolExpandedChange: (String, Boolean) -> Unit,
    onThinkingExpandedChange: (String, Boolean) -> Unit,
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
            if (visibleText.isBlank() || visibleText.isRetriableConnectionStallMessage()) return
            AgentResponse {
                ChatMarkdown(visibleText, lineHeight = 19.sp)
            }
        }
        is AgentEvent.Thinking -> ThinkingStep(
            text = event.text,
            expanded = eventKey in expandedThinkingKeys,
            onExpandedChange = { expanded -> onThinkingExpandedChange(eventKey, expanded) },
        )
        is AgentEvent.UserMessage -> ChatMessageBubble(
            alignEnd = true,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (event.text.isNotBlank()) {
                    ChatMarkdown(event.text, lineHeight = 18.sp, preserveLineBreaks = true)
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
        is AgentEvent.TaskError -> {
            if (event.message.isRetriableConnectionStallMessage()) return
            Text(event.message, color = Red, fontFamily = MonoFont, fontSize = 12.sp, lineHeight = 16.sp)
        }
        is AgentEvent.TaskResult -> AgentCompletion(
            event = event,
            completedContent = completedContent,
        )
        // The header owns this live status; a transcript row would only add noise.
        is AgentEvent.ContextUsage -> Unit
        is AgentEvent.PlanUpdate -> PanelCard {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("plan", color = Cyan, fontFamily = MonoFont, fontSize = 11.sp)
                event.entries.forEach { entry ->
                    Text("${entry.status}  ${entry.content}", color = TextPrimary, fontSize = 12.sp)
                }
            }
        }
        is AgentEvent.ModeChanged -> Text(
            "mode: ${event.modeId}",
            color = TextSecondary,
            fontFamily = MonoFont,
            fontSize = 11.sp,
        )
        // Provider command metadata powers the composer; it is not conversation content.
        is AgentEvent.AvailableCommands -> Unit
        // Mode metadata powers the mode picker in the composer header; not conversation content.
        is AgentEvent.AvailableModes -> Unit
        is AgentEvent.PermissionRequest -> {
            if (event.requestId != activePermissionRequestId) {
                PanelCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(event.toolName.ifBlank { "permission" }, color = Cyan, fontFamily = MonoFont, fontSize = 11.sp)
                        Text(event.question, color = TextPrimary, fontSize = 12.sp)
                        Text(event.options.joinToString(" · ") { it.label }, color = TextSecondary, fontSize = 11.sp)
                    }
                }
            }
        }
        is AgentEvent.PermissionResolved -> Text(
            buildString {
                append("permission ${if (event.allowed) "allowed" else "rejected"}: ${event.optionId}")
                event.note?.let { append(" ($it)") }
            },
            color = if (event.allowed) Green else Red,
            fontFamily = MonoFont,
            fontSize = 11.sp,
        )
        // Raw adapter diagnostics are retained for debugging but should never become
        // visible chat bubbles. User-facing failures have dedicated event types.
        is AgentEvent.Raw -> Unit
    }
}

@Composable
private fun ThinkingStep(
    text: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val expandable = text.lineSequence().any { it.isNotBlank() }
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
            Modifier.weight(1f).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            DisableSelection {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (expandable) Modifier.clickable { onExpandedChange(!expanded) } else Modifier),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (expandable) {
                        Text(
                            if (expanded) "v" else ">",
                            color = Cyan.copy(alpha = 0.65f),
                            fontFamily = MonoFont,
                            fontSize = 10.sp,
                            modifier = Modifier.width(10.dp),
                        )
                    }
                    ThinkingOrb(size = 14.dp, color = Cyan, animate = false, contentDescription = "Thinking")
                    Text("thinking", color = Cyan.copy(alpha = 0.65f), fontFamily = MonoFont, fontSize = 10.sp)
                }
            }
            AnimatedVisibility(visible = expandable) {
                val bodyModifier = if (expanded) {
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                } else {
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 60.dp)
                        .clipToBounds()
                }
                ChatMarkdown(
                    text,
                    density = AndyMarkdownDensity.Thinking,
                    lineHeight = 15.sp,
                    modifier = bodyModifier,
                )
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(
    alignEnd: Boolean,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        PanelCard(
            modifier = Modifier
                .testTag(if (alignEnd) "user-message-bubble" else "agent-message-bubble")
                .widthIn(max = 720.dp)
                .fillMaxWidth(if (alignEnd) 0.78f else 1f)
                .align(if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart),
            background = AndyColors.Neutral700.copy(alpha = AndyOverlay.Strong),
            borderColor = null,
            contentPadding = PaddingValues(horizontal = AndySpace.Space4, vertical = AndySpace.Space3),
            verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            content()
        }
    }
}

@Composable
private fun AgentResponse(
    content: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = { content() },
    )
}

@Composable
private fun AgentCompletion(
    event: AgentEvent.TaskResult,
    completedContent: (@Composable () -> Unit)?,
) {
    val duration = event.durationMs
        ?.takeIf { it >= 0L }
        ?.let { formatElapsed(0L, it, 0L) }
    val cost = formatCost(event.costUsd, event.costIsEstimated)
    val tokens = formatTokens(event.inputTokens, event.outputTokens)

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        duration?.let {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (event.success) "Worked for $it" else "Failed after $it",
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
                Text(">", color = TextSecondary.copy(alpha = 0.72f), fontSize = 11.sp)
            }
            AndyHorizontalDivider(color = Border)
        }
        if (cost != null || tokens != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cost?.let { Text(it, color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp) }
                tokens?.let { Text(it, color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp) }
            }
        }
        event.finalText?.takeIf { it.isNotBlank() }?.let {
            AgentResponse {
                ChatMarkdown(stripDecisionCheckpointMarkup(it), lineHeight = 18.sp)
            }
        }
        if (event.success) completedContent?.invoke()
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
                .clip(RoundedCornerShape(AndyRadius.Control))
                .background(AndyColors.Neutral900.copy(alpha = AndyOverlay.Medium))
                .border(1.dp, Border.copy(alpha = 0.75f), RoundedCornerShape(AndyRadius.Control))
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
                        .clip(RoundedCornerShape(AndyRadius.Control))
                        .background(AndyColors.Neutral900.copy(alpha = AndyOverlay.Strong))
                        .border(1.dp, Border.copy(alpha = 0.8f), RoundedCornerShape(AndyRadius.Control))
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
                .background(AndyColors.Neutral900.copy(alpha = AndyOverlay.Strong), RoundedCornerShape(AndyRadius.Control))
                .border(1.dp, Border, RoundedCornerShape(AndyRadius.Control))
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

    PanelCard(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        background = AndyColors.Neutral850.copy(alpha = AndyOverlay.Subtle),
        borderColor = Border.copy(alpha = 0.65f),
        contentPadding = PaddingValues(AndySpace.Space3),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
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
                        .background(AndyColors.Neutral850.copy(alpha = AndyOverlay.Subtle), RoundedCornerShape(AndyRadius.Control))
                        .border(1.dp, Border.copy(alpha = 0.65f), RoundedCornerShape(AndyRadius.Control))
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
                    .background(Color.Black.copy(alpha = 0.28f), RoundedCornerShape(AndyRadius.Control))
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

private fun AgentEvent.isHiddenConnectionStallMessage(): Boolean = when (this) {
    is AgentEvent.AssistantText -> text.isRetriableConnectionStallMessage()
    is AgentEvent.TaskError -> message.isRetriableConnectionStallMessage()
    else -> false
}

@Composable
internal fun ConnectionStallBanner(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelCard(
        modifier = modifier.fillMaxWidth(),
        background = AndyColors.Neutral900.copy(alpha = AndyOverlay.Medium),
        contentPadding = PaddingValues(horizontal = AndySpace.Space3, vertical = AndySpace.Space2),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        Text(
            "Connection stalled",
            color = Red,
            fontFamily = MonoFont,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Andy lost the stream to Cursor before this turn finished. Retry to pick up where the agent left off.",
            color = TextSecondary,
            fontFamily = MonoFont,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onRetry) { Text("retry", fontSize = 11.sp) }
        }
    }
}
