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
import app.andy.domain.ToolCallFileContent
import app.andy.domain.looksLikeFilePath
import app.andy.domain.parseToolCallFileContent
import app.andy.model.AcpToolCallPresentation
import app.andy.model.AgentEvent
import app.andy.model.AgentToolImage
import app.andy.model.AgentToolKind
import app.andy.model.isRetriableConnectionStallMessage
import app.andy.model.shouldShowConnectionStallBanner
import app.andy.model.stripDecisionCheckpointMarkup
import app.andy.model.AgentSkill
import app.andy.model.coalesceAcpTranscriptEvents
import app.andy.model.coalesceAgentStreamDeltas
import app.andy.ui.components.AndyMarkdownDensity
import app.andy.ui.components.ChatMarkdown
import app.andy.ui.components.DraggableScrollbar
import app.andy.ui.components.EmptyState
import app.andy.ui.components.ThinkingOrb
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyOverlay
import app.andy.ui.theme.AndyRadius
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
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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
    awaitingPlanConfirmation: Boolean = false,
    agentLabel: String = "agent",
    headerContent: (@Composable () -> Unit)? = null,
    pendingContent: (@Composable () -> Unit)? = null,
    /** When set, the matching [AgentEvent.PermissionRequest] row is omitted (shown via [pendingContent]). */
    activePermissionRequestId: String? = null,
    originalPrompt: String? = null,
    originalImagePaths: List<String> = emptyList(),
    completedContent: (@Composable () -> Unit)? = null,
    /** Scrolls with the transcript on the live edge, below pending input and above events. */
    trailingContent: (@Composable () -> Unit)? = null,
    /**
     * False while a completed chat's transcript (and trailing UI) is still loading.
     * Prevents pinning to the prompt-only stub before history arrives.
     */
    eventsReady: Boolean = true,
    onSkillOpen: (AgentSkill) -> Unit = {},
    restoreScrollKey: String? = null,
    scrollMemory: TranscriptScrollMemory? = null,
    /** Increment to jump to the live edge (e.g. after the user sends a follow-up). */
    scrollToLatestRequest: Int = 0,
    autoExpandActivitySections: Boolean = false,
    collapseActivityBetweenMessages: Boolean = false,
    onToolFileOpen: (ToolCallFileContent) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val displayItems = remember(events, collapseActivityBetweenMessages) {
        transcriptDisplayItems(events, collapseActivityBetweenMessages)
    }
    val originalPromptVisible = shouldDisplayOriginalPrompt(events, originalPrompt, originalImagePaths)
    val latestTaskResultItemIndex = displayItems.indexOfLast { item ->
        item is TranscriptDisplayItem.Event && item.event is AgentEvent.TaskResult
    }
    val latestPlanUpdateItemIndex = displayItems.indexOfLast { item ->
        item is TranscriptDisplayItem.Event && item.event is AgentEvent.PlanUpdate
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
    fun setActivityExpanded(
        key: String,
        expanded: Boolean,
        overrides: Set<String>,
        onOverridesChange: (Set<String>) -> Unit,
    ) {
        onOverridesChange(
            when {
                autoExpandActivitySections && expanded -> overrides - key
                autoExpandActivitySections && !expanded -> overrides + key
                !autoExpandActivitySections && expanded -> overrides + key
                else -> overrides - key
            },
        )
    }
    // Desktop wheel/trackpad input can complete without isScrollInProgress ever becoming true.
    var userScrollGeneration by remember(taskId) { mutableStateOf(0) }
    val rowKeys = remember(
        displayItems,
        isActive,
        originalPromptVisible,
        pendingContent != null,
        trailingContent != null,
        headerContent != null,
    ) {
        buildList {
            if (pendingContent != null) add("pending-task-input")
            if (isActive) add("agent-thinking")
            if (trailingContent != null) add("trailing-content")
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

    LaunchedEffect(scrollToLatestRequest, scrollInitialized) {
        if (scrollToLatestRequest == 0 || !scrollInitialized) return@LaunchedEffect
        stickToBottom = true
        listState.scrollToItem(0)
    }

    Box(modifier) {
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
                contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // reverseLayout lays index zero at the visual bottom, so declare rows newest
                // first while preserving the transcript's chronological reading order.
                if (pendingContent != null) {
                    item(key = "pending-task-input", contentType = "request") { pendingContent() }
                }
                if (isActive) {
                    item(key = "agent-thinking", contentType = "presence") { AgentThinkingIndicator() }
                }
                if (trailingContent != null) {
                    item(key = "trailing-content", contentType = "trailing") { trailingContent() }
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
                                toolExpanded = transcriptActivityExpanded(
                                    transcriptEventKey(item.index, item.event),
                                    expandedToolKeys,
                                    autoExpandActivitySections,
                                ),
                                thinkingExpanded = transcriptActivityExpanded(
                                    transcriptEventKey(item.index, item.event),
                                    expandedThinkingKeys,
                                    autoExpandActivitySections,
                                ),
                                agentLabel = agentLabel,
                                completedContent = if (itemIndex == latestTaskResultItemIndex) completedContent else null,
                                awaitingPlanConfirmation = awaitingPlanConfirmation &&
                                    itemIndex == latestPlanUpdateItemIndex,
                                activePermissionRequestId = activePermissionRequestId,
                                onToolExpandedChange = { key, expanded ->
                                    setActivityExpanded(key, expanded, expandedToolKeys) { expandedToolKeys = it }
                                },
                                onThinkingExpandedChange = { key, expanded ->
                                    setActivityExpanded(key, expanded, expandedThinkingKeys) { expandedThinkingKeys = it }
                                },
                                onSkillOpen = onSkillOpen,
                                onToolFileOpen = onToolFileOpen,
                            )
                            is TranscriptDisplayItem.ToolCalls -> CompactToolCallsBlock(
                                events = item.events,
                                startIndex = item.startIndex,
                                expanded = transcriptActivityExpanded(
                                    transcriptDisplayItemKey(item),
                                    expandedToolGroups,
                                    autoExpandActivitySections,
                                ),
                                onExpandedChange = { expanded ->
                                    val key = transcriptDisplayItemKey(item)
                                    setActivityExpanded(key, expanded, expandedToolGroups) { expandedToolGroups = it }
                                },
                                expandedToolKeys = expandedToolKeys,
                                expandedThinkingKeys = expandedThinkingKeys,
                                autoExpandActivitySections = autoExpandActivitySections,
                                onToolExpandedChange = { key, expanded ->
                                    setActivityExpanded(key, expanded, expandedToolKeys) { expandedToolKeys = it }
                                },
                                onThinkingExpandedChange = { key, expanded ->
                                    setActivityExpanded(key, expanded, expandedThinkingKeys) { expandedThinkingKeys = it }
                                },
                                onToolFileOpen = onToolFileOpen,
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
                Text(
                    if (isActive) "↓ follow live" else "↓ latest",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(AndyRadius.Pill))
                        .background(AndyColors.Neutral850.copy(alpha = 0.92f))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClick = ::jumpToLatest)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
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

internal fun transcriptActivityExpanded(
    key: String,
    overrides: Set<String>,
    autoExpand: Boolean,
): Boolean = if (autoExpand) key !in overrides else key in overrides

internal fun transcriptDisplayItems(
    events: List<AgentEvent>,
    collapseActivityBetweenMessages: Boolean = false,
): List<TranscriptDisplayItem> {
    val display = transcriptDisplayEvents(events).filterNot { it is AgentEvent.ContextUsage }
    val items = mutableListOf<TranscriptDisplayItem>()
    var index = 0
    while (index < display.size) {
        val event = display[index]
        if (!event.isTranscriptActivityEvent()) {
            items += TranscriptDisplayItem.Event(index, event)
            index += 1
            continue
        }
        val startIndex = index
        val group = mutableListOf<AgentEvent>()
        while (index < display.size && display[index].isTranscriptActivityEvent()) {
            group += display[index]
            index += 1
        }
        when {
            group.size == 1 -> items += TranscriptDisplayItem.Event(startIndex, group.single())
            collapseActivityBetweenMessages -> items += TranscriptDisplayItem.ToolCalls(startIndex, group)
            group.all { it is AgentEvent.ToolCall || it is AgentEvent.ToolResult } ->
                items += TranscriptDisplayItem.ToolCalls(startIndex, group)
            else -> group.forEachIndexed { offset, activity ->
                items += TranscriptDisplayItem.Event(startIndex + offset, activity)
            }
        }
    }
    return items
}

internal fun AgentEvent.isTranscriptActivityEvent(): Boolean =
    this is AgentEvent.Thinking || this is AgentEvent.ToolCall || this is AgentEvent.ToolResult

@Composable
private fun AgentThinkingIndicator() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThinkingOrb(size = 14.dp, color = Cyan, contentDescription = "Thinking")
        Text("Thinking", color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun TranscriptEvent(
    event: AgentEvent,
    eventKey: String,
    toolExpanded: Boolean,
    thinkingExpanded: Boolean,
    agentLabel: String,
    completedContent: (@Composable () -> Unit)?,
    awaitingPlanConfirmation: Boolean = false,
    activePermissionRequestId: String? = null,
    onToolExpandedChange: (String, Boolean) -> Unit,
    onThinkingExpandedChange: (String, Boolean) -> Unit,
    onSkillOpen: (AgentSkill) -> Unit,
    onToolFileOpen: (ToolCallFileContent) -> Unit,
) {
    when (event) {
        is AgentEvent.SessionStarted -> Unit
        is AgentEvent.AssistantText -> {
            val visibleText = stripDecisionCheckpointMarkup(event.text)
            if (visibleText.isBlank() || visibleText.isRetriableConnectionStallMessage()) return
            AgentResponse {
                ChatMarkdown(visibleText, lineHeight = 21.sp)
            }
        }
        is AgentEvent.Thinking -> ThinkingStep(
            text = event.text,
            expanded = thinkingExpanded,
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
            expanded = toolExpanded,
            onExpandedChange = { expanded -> onToolExpandedChange(eventKey, expanded) },
            marker = "▸",
            name = event.toolName,
            summary = event.summary,
            detail = event.detail,
            kind = event.kind,
            locations = event.locations,
            images = event.images,
            color = Cyan,
            onToolFileOpen = onToolFileOpen,
        )
        is AgentEvent.ToolResult -> ToolBlock(
            expanded = toolExpanded,
            onExpandedChange = { expanded -> onToolExpandedChange(eventKey, expanded) },
            marker = if (event.isError) "✗" else "✓",
            name = event.toolName,
            summary = event.summary,
            detail = event.detail,
            color = if (event.isError) Red else TextSecondary,
            onToolFileOpen = onToolFileOpen,
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
        is AgentEvent.PlanUpdate -> Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Plan", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            event.markdown?.takeIf { it.isNotBlank() }?.let { markdown ->
                ChatMarkdown(markdown, lineHeight = 18.sp)
            }
            event.entries.forEach { entry ->
                val prefix = when (entry.status) {
                    "file" -> "file"
                    else -> entry.status
                }
                Text("$prefix  ${entry.content}", color = TextPrimary, fontSize = 13.sp, lineHeight = 18.sp)
            }
            if (awaitingPlanConfirmation) {
                Text(
                    "Waiting for you to continue — refine below or implement the plan.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
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
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        event.toolName.ifBlank { "permission" },
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                    Text(event.question, color = TextPrimary, fontSize = 13.sp, lineHeight = 18.sp)
                    Text(
                        event.options.joinToString(" · ") { it.label },
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
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

private val TranscriptAsideIndent = 14.dp
private val TranscriptAsideContentIndent = 22.dp

@Composable
private fun ThinkingStep(
    text: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val expandable = text.lineSequence().any { it.isNotBlank() }
    TranscriptExpandableRow(
        headline = "Thinking",
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        expandable = expandable,
        headlineColor = TextSecondary,
        indent = TranscriptAsideIndent,
    ) {
        val bodyModifier = if (expanded) {
            Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
        } else {
            Modifier
                .fillMaxWidth()
                .heightIn(max = 48.dp)
                .clipToBounds()
        }
        ChatMarkdown(
            text,
            density = AndyMarkdownDensity.Thinking,
            lineHeight = 16.sp,
            modifier = bodyModifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ChatMessageBubble(
    alignEnd: Boolean,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .testTag(if (alignEnd) "user-message-bubble" else "agent-message-bubble")
                .widthIn(max = 640.dp)
                .fillMaxWidth(if (alignEnd) 0.82f else 1f)
                .align(if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart)
                .clip(RoundedCornerShape(14.dp))
                .background(AndyColors.Neutral800.copy(alpha = 0.5f))
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = { content() },
        )
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
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        duration?.let {
            TranscriptExpandableRow(
                headline = if (event.success) "Worked for $it" else "Failed after $it",
                expanded = false,
                onExpandedChange = {},
                expandable = false,
                headlineColor = TextSecondary,
            )
        }
        if (cost != null || tokens != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                cost?.let { Text(it, color = TextSecondary.copy(alpha = 0.8f), fontFamily = MonoFont, fontSize = 11.sp) }
                tokens?.let { Text(it, color = TextSecondary.copy(alpha = 0.8f), fontFamily = MonoFont, fontSize = 11.sp) }
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
private fun ChatAttachedToolImages(
    images: List<AgentToolImage>,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 260.dp,
    maxHeight: Dp = 180.dp,
) {
    if (images.isEmpty()) return
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        images.forEachIndexed { index, image ->
            ChatAttachedToolImage(
                image = image,
                label = "image ${index + 1}",
                maxWidth = maxWidth,
                maxHeight = maxHeight,
            )
        }
    }
}

@Composable
private fun ChatAttachedToolImage(
    image: AgentToolImage,
    label: String,
    maxWidth: Dp = 260.dp,
    maxHeight: Dp = 180.dp,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, image.dataUri) {
        value = withContext(Dispatchers.Default) {
            decodeDataUriBytes(image.dataUri)?.let { bytes -> runCatching { loadImageBitmap(bytes) }.getOrNull() }
        }
    }
    var previewOpen by remember(image.dataUri) { mutableStateOf(false) }
    val bmp = bitmap
    DisableSelection {
        Box(
            Modifier
                .widthIn(max = maxWidth)
                .heightIn(max = maxHeight)
                .clip(RoundedCornerShape(AndyRadius.Control))
                .background(AndyColors.Neutral900.copy(alpha = AndyOverlay.Medium))
                .border(1.dp, Border.copy(alpha = 0.75f), RoundedCornerShape(AndyRadius.Control))
                .then(
                    if (bmp != null) {
                        Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { previewOpen = true }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = label,
                    modifier = Modifier
                        .widthIn(max = maxWidth)
                        .heightIn(max = maxHeight),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    label,
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                )
            }
        }
    }
    if (previewOpen && bmp != null) {
        ChatImagePreviewDialog(
            bitmap = bmp,
            fileName = label,
            onDismiss = { previewOpen = false },
        )
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun decodeDataUriBytes(dataUri: String): ByteArray? {
    val base64 = dataUri.substringAfter(',', missingDelimiterValue = "")
    if (base64.isBlank()) return null
    return runCatching { Base64.decode(base64) }.getOrNull()
}

@Composable
private fun CompactToolCallsBlock(
    events: List<AgentEvent>,
    startIndex: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    expandedToolKeys: Set<String>,
    expandedThinkingKeys: Set<String>,
    autoExpandActivitySections: Boolean,
    onToolExpandedChange: (String, Boolean) -> Unit,
    onThinkingExpandedChange: (String, Boolean) -> Unit,
    onToolFileOpen: (ToolCallFileContent) -> Unit,
) {
    val hasError = events.any { it is AgentEvent.ToolResult && it.isError }
    val headlineColor = if (hasError) Red.copy(alpha = 0.9f) else TextSecondary

    TranscriptExpandableRow(
        headline = compactActivityHeadline(events),
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        headlineColor = headlineColor,
        indent = TranscriptAsideIndent,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            events.forEachIndexed { offset, event ->
                val eventKey = transcriptEventKey(startIndex + offset, event)
                when (event) {
                    is AgentEvent.Thinking -> ThinkingStep(
                        text = event.text,
                        expanded = transcriptActivityExpanded(eventKey, expandedThinkingKeys, autoExpandActivitySections),
                        onExpandedChange = { value -> onThinkingExpandedChange(eventKey, value) },
                    )
                    is AgentEvent.ToolCall -> ToolBlock(
                        expanded = transcriptActivityExpanded(eventKey, expandedToolKeys, autoExpandActivitySections),
                        onExpandedChange = { value -> onToolExpandedChange(eventKey, value) },
                        marker = "▸",
                        name = event.toolName,
                        summary = event.summary,
                        detail = event.detail,
                        kind = event.kind,
                        locations = event.locations,
                        color = Cyan,
                        indent = TranscriptAsideContentIndent,
                        onToolFileOpen = onToolFileOpen,
                    )
                    is AgentEvent.ToolResult -> ToolBlock(
                        expanded = transcriptActivityExpanded(eventKey, expandedToolKeys, autoExpandActivitySections),
                        onExpandedChange = { value -> onToolExpandedChange(eventKey, value) },
                        marker = if (event.isError) "✗" else "✓",
                        name = event.toolName,
                        summary = event.summary,
                        detail = event.detail,
                        color = if (event.isError) Red else TextSecondary,
                        indent = TranscriptAsideContentIndent,
                        onToolFileOpen = onToolFileOpen,
                    )
                    else -> Unit
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
    kind: AgentToolKind? = null,
    locations: List<String> = emptyList(),
    images: List<AgentToolImage> = emptyList(),
    color: Color,
    indent: Dp = TranscriptAsideIndent,
    onToolFileOpen: (ToolCallFileContent) -> Unit = {},
) {
    val headline = toolBlockHeadline(name, summary, kind, locations)
    val body = detail
        .takeUnless { AcpToolCallPresentation.isMinimalOutput(it) }
        .orEmpty()
        .ifBlank { summary.takeUnless { AcpToolCallPresentation.isMinimalOutput(it) }.orEmpty() }
        .ifBlank { name.orEmpty() }
    val expandable = images.isNotEmpty() || (body.isNotBlank() && body != headline)
    val fileContent = remember(body) { parseToolCallFileContent(body) }
    val openableContent = fileContent ?: locations.firstOrNull { looksLikeFilePath(it) }?.let {
        ToolCallFileContent(path = it, oldText = null, newText = null)
    }

    if (!expandable) {
        ToolPathText(
            text = headline.ifBlank { marker },
            fileContent = openableContent,
            onOpen = onToolFileOpen,
            color = color.copy(alpha = 0.88f),
            modifier = Modifier.padding(start = indent),
            maxLines = 1,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
        return
    }

    TranscriptExpandableRow(
        headline = headline,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        headlineColor = color.copy(alpha = 0.88f),
        indent = indent,
        headlineContent = openableContent?.let { content ->
            {
                ToolPathText(
                    text = headline,
                    fileContent = content,
                    onOpen = onToolFileOpen,
                    color = color.copy(alpha = 0.88f),
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        },
    ) {
        ToolCallDetailBody(
            body = body,
            fileContent = fileContent,
            images = images,
            onOpen = onToolFileOpen,
        )
    }
}

@Composable
private fun ToolCallDetailBody(
    body: String,
    fileContent: ToolCallFileContent?,
    images: List<AgentToolImage> = emptyList(),
    onOpen: (ToolCallFileContent) -> Unit,
) {
    if (images.isNotEmpty()) {
        ChatAttachedToolImages(images, modifier = Modifier.padding(top = 4.dp))
    }
    if (fileContent != null) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DisableSelection {
                ToolPathText(
                    text = fileContent.path,
                    fileContent = fileContent,
                    onOpen = onOpen,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
            val preview = when {
                fileContent.hasDiff -> buildString {
                    if (!fileContent.oldText.isNullOrBlank()) {
                        append("--- old\n")
                        append(fileContent.oldText.orEmpty())
                    }
                    if (!fileContent.newText.isNullOrBlank()) {
                        if (isNotEmpty()) append('\n')
                        append("+++ new\n")
                        append(fileContent.newText.orEmpty())
                    }
                }
                else -> fileContent.newText.orEmpty()
            }
            if (preview.isNotBlank()) {
                ChatMarkdown(
                    preview,
                    density = AndyMarkdownDensity.Thinking,
                    lineHeight = 16.sp,
                    preserveLineBreaks = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
        return
    }
    if (body.isBlank()) return
    ChatMarkdown(
        body,
        density = AndyMarkdownDensity.Thinking,
        lineHeight = 16.sp,
        preserveLineBreaks = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState()),
    )
}

@Composable
private fun ToolPathText(
    text: String,
    fileContent: ToolCallFileContent?,
    onOpen: (ToolCallFileContent) -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    fontSize: androidx.compose.ui.unit.TextUnit = 11.sp,
    lineHeight: androidx.compose.ui.unit.TextUnit = 15.sp,
) {
    val path = fileContent?.path?.takeIf { it.isNotBlank() && it in text }
    if (path == null) {
        Text(
            text,
            color = color,
            fontFamily = MonoFont,
            fontSize = fontSize,
            lineHeight = lineHeight,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        return
    }
    val prefixEnd = text.indexOf(path)
    val suffixStart = prefixEnd + path.length
    DisableSelection {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            if (prefixEnd > 0) {
                Text(
                    text.substring(0, prefixEnd),
                    color = color,
                    fontFamily = MonoFont,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                path,
                color = Cyan,
                fontFamily = MonoFont,
                fontSize = fontSize,
                lineHeight = lineHeight,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { onOpen(fileContent) },
            )
            if (suffixStart < text.length) {
                Text(
                    text.substring(suffixStart),
                    color = color,
                    fontFamily = MonoFont,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TranscriptExpandableRow(
    headline: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    expandable: Boolean = true,
    headlineColor: Color = TextSecondary,
    indent: Dp = 0.dp,
    contentIndent: Dp = indent + 8.dp,
    headlineContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth().animateContentSize()) {
        DisableSelection {
            if (headlineContent != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = indent)
                        .then(
                            if (expandable) {
                                Modifier
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable { onExpandedChange(!expanded) }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    headlineContent()
                }
            } else {
                Text(
                    headline,
                    color = headlineColor,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = indent)
                        .then(
                            if (expandable) {
                                Modifier
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable { onExpandedChange(!expanded) }
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }
        if (expandable) {
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.fillMaxWidth().padding(start = contentIndent)) {
                    content()
                }
            }
        }
    }
}

private fun compactActivityHeadline(events: List<AgentEvent>): String {
    val thinkingCount = events.count { it is AgentEvent.Thinking }
    val toolHeadline = compactToolActivityHeadline(events)
    return when {
        thinkingCount > 0 && toolHeadline.isNotBlank() -> {
            val thinkingLabel = if (thinkingCount == 1) "thought" else "$thinkingCount thoughts"
            "$thinkingLabel, $toolHeadline"
        }
        thinkingCount > 0 -> if (thinkingCount == 1) "Thinking" else "$thinkingCount thinking steps"
        else -> toolHeadline
    }
}

internal fun compactToolActivityHeadline(events: List<AgentEvent>): String {
    val toolCalls = events.filterIsInstance<AgentEvent.ToolCall>()
    if (toolCalls.isEmpty()) {
        val count = events.size
        return "$count tool ${if (count == 1) "result" else "results"}"
    }
    if (toolCalls.size == 1) {
        val call = toolCalls.single()
        return toolActionPhrase(call.toolName, call.summary, call.kind, call.locations)
    }
    val phrases = toolCalls.map { toolActionPhrase(it.toolName, it.summary, it.kind, it.locations) }
    val readCount = toolCalls.count { it.toolName.lowercase() in ReadToolNames || it.kind == AgentToolKind.Read }
    val commandCount = toolCalls.count { it.toolName.lowercase() in CommandToolNames || it.kind == AgentToolKind.Execute }
    val agentCount = toolCalls.count { it.toolName.lowercase() in AgentToolNames }
    val editCount = toolCalls.count {
        it.kind == AgentToolKind.Edit || it.toolName.lowercase() in EditToolNames
    }
    val summaryParts = buildList {
        if (agentCount > 0) add("ran ${if (agentCount == 1) "an agent" else "$agentCount agents"}")
        if (readCount > 0) add("read $readCount ${if (readCount == 1) "file" else "files"}")
        if (editCount > 0) add("edited $editCount ${if (editCount == 1) "file" else "files"}")
        if (commandCount > 0) add("ran $commandCount ${if (commandCount == 1) "command" else "commands"}")
    }
    if (summaryParts.isNotEmpty()) {
        return summaryParts.joinToString(", ")
    }
    val meaningfulPhrases = phrases.filter { it.isNotBlank() && !AcpToolCallPresentation.isMinimalOutput(it) }
    if (meaningfulPhrases.isEmpty()) {
        val count = toolCalls.size
        return "$count tool ${if (count == 1) "call" else "calls"}"
    }
    return meaningfulPhrases.take(3).joinToString(", ").let { headline ->
        if (meaningfulPhrases.size > 3) "$headline, …" else headline
    }
}

private fun toolBlockHeadline(
    name: String?,
    summary: String,
    kind: AgentToolKind?,
    locations: List<String>,
): String {
    val label = name?.trim().orEmpty()
    val detail = summary
        .takeUnless { AcpToolCallPresentation.isMinimalOutput(it) }
        .orEmpty()
        .ifBlank { AcpToolCallPresentation.enrichSummary("", kind, locations) }
    return when {
        detail.isNotBlank() && label.isNotBlank() && !label.equals(detail, ignoreCase = true) ->
            "$label: $detail"
        label.isNotBlank() -> label
        detail.isNotBlank() -> detail
        else -> toolActionPhrase(label, summary, kind, locations)
    }
}

private fun toolActionPhrase(
    toolName: String,
    summary: String,
    kind: AgentToolKind? = null,
    locations: List<String> = emptyList(),
): String {
    val lower = toolName.lowercase()
    val trimmedSummary = summary
        .trim()
        .takeUnless { AcpToolCallPresentation.isMinimalOutput(it) }
        .orEmpty()
        .ifBlank { AcpToolCallPresentation.enrichSummary("", kind, locations) }
    return when {
        lower in AgentToolNames -> "Ran an agent"
        lower in ReadToolNames || kind == AgentToolKind.Read ->
            trimmedSummary.takeIf { it.isNotBlank() }?.let { "Read $it" } ?: "Read file"
        lower in EditToolNames || kind == AgentToolKind.Edit ->
            trimmedSummary.takeIf { it.isNotBlank() }?.let { "Edited $it" } ?: "Edited file"
        lower in CommandToolNames || kind == AgentToolKind.Execute ->
            trimmedSummary.takeIf { it.isNotBlank() }?.let { "Ran $it" } ?: "Ran command"
        trimmedSummary.isNotBlank() -> trimmedSummary
        toolName.isNotBlank() -> toolName
        else -> "Tool call"
    }
}

private val ReadToolNames = setOf("read", "grep", "glob", "list_dir", "file_read", "get_network_request")
private val CommandToolNames = setOf("shell", "run_terminal_cmd", "bash", "write", "strreplace")
private val EditToolNames = setOf("edit", "edit file", "edit_file", "str_replace", "apply_patch", "write")
private val AgentToolNames = setOf("task", "agent", "mcp_task")

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
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Connection stalled",
            color = Red,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "Andy lost the stream before this turn finished. Retry to pick up where the agent left off.",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                "Retry",
                color = Cyan,
                fontSize = 12.sp,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
internal fun PlanReadyBanner(
    showImplementAction: Boolean,
    onImplement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Plan ready",
            color = Green,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            if (showImplementAction) {
                "This turn finished in plan mode — nothing was changed. Implement when you're ready, or reply to refine the plan."
            } else {
                "This turn finished in plan mode — nothing was changed. Review the plan in Projects, or reply to refine it here."
            },
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        if (showImplementAction) {
            Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    "Implement plan",
                    color = Cyan,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClick = onImplement)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}
