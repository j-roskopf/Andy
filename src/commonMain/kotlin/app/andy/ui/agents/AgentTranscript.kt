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
import androidx.compose.foundation.layout.defaultMinSize
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
import app.andy.ui.components.ChatBubbleGroup
import app.andy.ui.components.ChatBubbleSender
import app.andy.ui.components.ChatBubbleVariant
import app.andy.ui.components.ChatMessageBubble
import app.andy.ui.components.ChatMessageCopyAction
import app.andy.ui.components.ChatMessageMetadata
import app.andy.ui.components.PlatformLazyListScrollbar
import app.andy.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.andy.loadImageBitmap
import app.andy.domain.ToolCallFileContent
import app.andy.domain.detectUnifiedDiff
import app.andy.domain.diffFromToolCallFileContent
import app.andy.domain.extractUnifiedDiffText
import app.andy.domain.looksLikeFilePath
import app.andy.domain.parseToolCallFileArguments
import app.andy.domain.parseToolCallFileContent
import app.andy.model.AcpToolCallPresentation
import app.andy.model.AgentEvent
import app.andy.model.AgentFileDiff
import app.andy.model.AgentPlanEntry
import app.andy.model.AgentSpawnPresentation
import app.andy.model.AgentTask
import app.andy.model.AgentToolImage
import app.andy.model.AgentToolKind
import app.andy.model.AgentToolState
import app.andy.model.isRetriableConnectionStallMessage
import app.andy.model.isSilentConnectionRecoveryPrompt
import app.andy.model.stripTrailingConnectionStallError
import app.andy.model.stripDecisionCheckpointMarkup
import app.andy.model.AgentSkill
import app.andy.model.coalesceAcpTranscriptEvents
import app.andy.model.coalesceAgentStreamDeltas
import app.andy.service.OpenAgentTaskRequest
import app.andy.ui.shell.LocalOpenAgentTask
import app.andy.ui.shell.ReportContentScrollBusy
import app.andy.ui.components.AndyMarkdownDensity
import app.andy.ui.components.ChatMarkdown
import app.andy.ui.components.EmptyState
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.TextField
import app.andy.ui.components.ThinkingOrb
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyOverlay
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Yellow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Test-only counter for how often [AgentTranscript]'s composition scope restarts.
 * Reading [LazyListState.layoutInfo] during composition (e.g. as a [LaunchedEffect] key)
 * forces a restart on every scroll frame — this counter catches that regression.
 */
internal class TranscriptCompositionCounter {
    var rootRestarts = 0
}

internal val LocalTranscriptCompositionCounter = compositionLocalOf<TranscriptCompositionCounter?> { null }

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
    /**
     * Live "Working" orb. Defaults off when [isActive] is only Blocked (permission wait) so a
     * parked ACP chat does not keep a ~12fps full-window Skiko redraw alive.
     */
    showThinkingIndicator: Boolean = isActive,
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
    autoExpandThinkingSections: Boolean = false,
    autoExpandToolSections: Boolean = false,
    collapseActivityBetweenMessages: Boolean = false,
    onToolFileOpen: (ToolCallFileContent) -> Unit = {},
    /** Known Andy chats used to resolve spawn rows to openable task ids. */
    knownTasks: List<AgentTask> = emptyList(),
    /** Current chat id so spawn resolution does not link a row back to itself. */
    currentTaskId: String? = null,
    modifier: Modifier = Modifier,
) {
    val compositionCounter = LocalTranscriptCompositionCounter.current
    SideEffect { compositionCounter?.let { it.rootRestarts++ } }
    val scope = rememberCoroutineScope()
    val displayItems = remember(events, collapseActivityBetweenMessages, autoExpandThinkingSections) {
        transcriptDisplayItems(
            events,
            collapseActivityBetweenMessages = collapseActivityBetweenMessages,
            keepThinkingOnTimeline = autoExpandThinkingSections,
        )
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
        autoExpand: Boolean,
        onOverridesChange: (Set<String>) -> Unit,
    ) {
        onOverridesChange(
            when {
                autoExpand && expanded -> overrides - key
                autoExpand && !expanded -> overrides + key
                !autoExpand && expanded -> overrides + key
                else -> overrides - key
            },
        )
    }
    // Desktop wheel/trackpad often never sets isScrollInProgress. Emit ticks without Compose
    // state so each wheel event does not recompose the whole transcript. Keep a single slot and
    // DROP_OLDEST so a fast fling cannot queue dozens of settle waits.
    val wheelScrollTicks = remember(taskId) {
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    }
    ReportContentScrollBusy(listState = listState, wheelScrollTicks = wheelScrollTicks)
    val rowKeys = remember(
        displayItems,
        isActive,
        showThinkingIndicator,
        originalPromptVisible,
        pendingContent != null,
        trailingContent != null,
        headerContent != null,
    ) {
        buildList {
            if (pendingContent != null) add("pending-task-input")
            if (showThinkingIndicator) add("agent-thinking")
            if (trailingContent != null) add("trailing-content")
            displayItems.asReversed().forEach { add(transcriptDisplayItemKey(it)) }
            if (originalPromptVisible) add("original-prompt")
            if (headerContent != null) add("task-header")
        }
    }

    // Never read listState.layoutInfo as a composition / LaunchedEffect key — that state
    // updates every scroll frame and would recompose the entire transcript (markdown rows
    // included). Stick-to-bottom restore needs no item count; exact restore waits in a flow.
    LaunchedEffect(taskId, eventsReady, rowKeys) {
        if (scrollInitialized || !eventsReady) return@LaunchedEffect
        when (val plan = restorePlan) {
            TranscriptRestorePlan.StickToBottom -> {
                // Index zero is bottom in reverseLayout. No settling loop or post-layout nudge.
                stickToBottom = true
                scrollInitialized = true
            }
            is TranscriptRestorePlan.Exact -> {
                val itemCount = snapshotFlow { listState.layoutInfo.totalItemsCount }
                    .first { it > 0 }
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
    LaunchedEffect(taskId, listState, scrollInitialized, wheelScrollTicks) {
        if (!scrollInitialized) return@LaunchedEffect
        // collectLatest matches the old LaunchedEffect(userScrollGeneration) restart: a new
        // tick cancels the in-flight settle instead of queueing two frames per event.
        wheelScrollTicks.collectLatest {
            // Let wheel/trackpad input settle. A blocked downward tick at the live edge has no
            // position change, so explicitly re-arm in that case.
            withFrameMillis { }
            withFrameMillis { }
            if (transcriptIsAtBottom(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)) {
                stickToBottom = true
            }
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
                                    wheelScrollTicks.tryEmit(Unit)
                                }
                            }
                        }
                    },
                contentPadding = PaddingValues(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // reverseLayout lays index zero at the visual bottom, so declare rows newest
                // first while preserving the transcript's chronological reading order.
                if (pendingContent != null) {
                    item(key = "pending-task-input", contentType = "request") { pendingContent() }
                }
                if (showThinkingIndicator) {
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
                            is TranscriptDisplayItem.Event -> {
                                val bubbleGroup = transcriptChatBubbleGroup(displayItems, itemIndex)
                                TranscriptEvent(
                                event = item.event,
                                eventKey = transcriptEventKey(item.index, item.event),
                                bubbleGroup = bubbleGroup,
                                toolExpanded = transcriptActivityExpanded(
                                    transcriptEventKey(item.index, item.event),
                                    expandedToolKeys,
                                    autoExpandToolSections,
                                ),
                                thinkingExpanded = transcriptActivityExpanded(
                                    transcriptEventKey(item.index, item.event),
                                    expandedThinkingKeys,
                                    autoExpandThinkingSections,
                                ),
                                agentLabel = agentLabel,
                                completedContent = if (itemIndex == latestTaskResultItemIndex) completedContent else null,
                                awaitingPlanConfirmation = awaitingPlanConfirmation &&
                                    itemIndex == latestPlanUpdateItemIndex,
                                activePermissionRequestId = activePermissionRequestId,
                                onToolExpandedChange = { key, expanded ->
                                    setActivityExpanded(
                                        key,
                                        expanded,
                                        expandedToolKeys,
                                        autoExpandToolSections,
                                    ) { expandedToolKeys = it }
                                },
                                onThinkingExpandedChange = { key, expanded ->
                                    setActivityExpanded(
                                        key,
                                        expanded,
                                        expandedThinkingKeys,
                                        autoExpandThinkingSections,
                                    ) { expandedThinkingKeys = it }
                                },
                                onSkillOpen = onSkillOpen,
                                onToolFileOpen = onToolFileOpen,
                                knownTasks = knownTasks,
                                currentTaskId = currentTaskId,
                                autoExpandThinkingSections = autoExpandThinkingSections,
                                autoExpandToolSections = autoExpandToolSections,
                            )
                            }
                            is TranscriptDisplayItem.ToolCalls -> CompactToolCallsBlock(
                                events = item.events,
                                startIndex = item.startIndex,
                                expanded = transcriptActivityExpanded(
                                    transcriptDisplayItemKey(item),
                                    expandedToolGroups,
                                    autoExpandToolSections,
                                ),
                                onExpandedChange = { expanded ->
                                    val key = transcriptDisplayItemKey(item)
                                    setActivityExpanded(
                                        key,
                                        expanded,
                                        expandedToolGroups,
                                        autoExpandToolSections,
                                    ) { expandedToolGroups = it }
                                },
                                expandedToolKeys = expandedToolKeys,
                                expandedThinkingKeys = expandedThinkingKeys,
                                autoExpandThinkingSections = autoExpandThinkingSections,
                                autoExpandToolSections = autoExpandToolSections,
                                onToolExpandedChange = { key, expanded ->
                                    setActivityExpanded(
                                        key,
                                        expanded,
                                        expandedToolKeys,
                                        autoExpandToolSections,
                                    ) { expandedToolKeys = it }
                                },
                                onThinkingExpandedChange = { key, expanded ->
                                    setActivityExpanded(
                                        key,
                                        expanded,
                                        expandedThinkingKeys,
                                        autoExpandThinkingSections,
                                    ) { expandedThinkingKeys = it }
                                },
                                onToolFileOpen = onToolFileOpen,
                                knownTasks = knownTasks,
                                currentTaskId = currentTaskId,
                            )
                        }
                    }
                }
                if (originalPromptVisible) {
                    item(key = "original-prompt", contentType = "message") {
                        SelectionContainer {
                            ChatMessageBubble(
                                sender = ChatBubbleSender.User,
                                testTag = "user-message-bubble",
                                metadata = originalPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
                                    {
                                        ChatMessageMetadata(
                                            reverse = true,
                                            footer = { ChatMessageCopyAction(prompt) },
                                        )
                                    }
                                },
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    originalPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
                                        ChatUserText(prompt)
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
            PlatformLazyListScrollbar(
                listState = listState,
                reverseLayout = true,
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
): Boolean {
    val prompt = originalPrompt?.trim().orEmpty()
    val recordedInTranscript = prompt.isNotBlank() &&
        events.filterIsInstance<AgentEvent.UserMessage>().any { it.text.trim() == prompt }
    return !recordedInTranscript && (prompt.isNotBlank() || originalImagePaths.isNotEmpty())
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
internal fun transcriptDisplayEvents(events: List<AgentEvent>): List<AgentEvent> {
    val coalesced = coalesceAcpTranscriptEvents(events)
    val displayable = coalesced.mapNotNull { event ->
        when {
            event is AgentEvent.AvailableCommands || event is AgentEvent.Raw -> null
            event is AgentEvent.AssistantText -> {
                val stripped = event.text.stripTrailingConnectionStallError()
                when {
                    stripped.isBlank() -> null
                    stripped == event.text -> event
                    else -> event.copy(text = stripped)
                }
            }
            event.isHiddenConnectionStallMessage() -> null
            else -> event
        }
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
    keepThinkingOnTimeline: Boolean = false,
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
        // Keep thinking as first-class timeline rows when requested, even if tool activity collapses.
        if (keepThinkingOnTimeline && event is AgentEvent.Thinking) {
            items += TranscriptDisplayItem.Event(index, event)
            index += 1
            continue
        }
        val startIndex = index
        val group = mutableListOf<AgentEvent>()
        while (index < display.size && display[index].isTranscriptActivityEvent()) {
            val next = display[index]
            if (keepThinkingOnTimeline && next is AgentEvent.Thinking) break
            group += next
            index += 1
        }
        when {
            group.isEmpty() -> Unit
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

/** Whether this event renders as a user/assistant chat bubble in the transcript. */
internal fun AgentEvent.chatBubbleSenderOrNull(): ChatBubbleSender? = when (this) {
    is AgentEvent.UserMessage -> if (text.isSilentConnectionRecoveryPrompt()) null else ChatBubbleSender.User
    is AgentEvent.AssistantText -> {
        val stripped = stripDecisionCheckpointMarkup(text.stripTrailingConnectionStallError())
        when {
            stripped.isBlank() -> null
            stripped.isRetriableConnectionStallMessage() -> null
            else -> ChatBubbleSender.Assistant
        }
    }
    else -> null
}

/** Astryx-style grouping for consecutive bubbles from the same sender. */
internal fun transcriptChatBubbleGroup(
    displayItems: List<TranscriptDisplayItem>,
    itemIndex: Int,
): ChatBubbleGroup {
    val current = displayItems.getOrNull(itemIndex) as? TranscriptDisplayItem.Event
        ?: return ChatBubbleGroup.Single
    val sender = current.event.chatBubbleSenderOrNull() ?: return ChatBubbleGroup.Single
    fun neighborSender(index: Int): ChatBubbleSender? {
        val neighbor = displayItems.getOrNull(index) as? TranscriptDisplayItem.Event ?: return null
        return neighbor.event.chatBubbleSenderOrNull()
    }
    val hasPrev = neighborSender(itemIndex - 1) == sender
    val hasNext = neighborSender(itemIndex + 1) == sender
    return when {
        hasPrev && hasNext -> ChatBubbleGroup.Middle
        hasPrev -> ChatBubbleGroup.Last
        hasNext -> ChatBubbleGroup.First
        else -> ChatBubbleGroup.Single
    }
}

@Composable
private fun AgentThinkingIndicator() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThinkingOrb(size = 12.dp, color = Cyan, contentDescription = "Thinking")
        Text("Working", color = TextSecondary.copy(alpha = 0.85f), fontSize = 12.sp)
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
    bubbleGroup: ChatBubbleGroup = ChatBubbleGroup.Single,
    awaitingPlanConfirmation: Boolean = false,
    activePermissionRequestId: String? = null,
    onToolExpandedChange: (String, Boolean) -> Unit,
    onThinkingExpandedChange: (String, Boolean) -> Unit,
    onSkillOpen: (AgentSkill) -> Unit,
    onToolFileOpen: (ToolCallFileContent) -> Unit,
    knownTasks: List<AgentTask> = emptyList(),
    currentTaskId: String? = null,
    autoExpandThinkingSections: Boolean = false,
    autoExpandToolSections: Boolean = false,
) {
    when (event) {
        is AgentEvent.SessionStarted -> Unit
        is AgentEvent.AssistantText -> {
            val visibleText = stripDecisionCheckpointMarkup(
                event.text.stripTrailingConnectionStallError(),
            )
            if (visibleText.isBlank() || visibleText.isRetriableConnectionStallMessage()) return
            AgentResponse(group = bubbleGroup, copyText = visibleText) {
                ChatMarkdown(visibleText, lineHeight = 21.sp)
            }
        }
        is AgentEvent.Thinking -> ThinkingStep(
            text = event.text,
            expanded = thinkingExpanded,
            onExpandedChange = { expanded -> onThinkingExpandedChange(eventKey, expanded) },
            animateExpansion = !autoExpandThinkingSections,
        )
        is AgentEvent.UserMessage -> {
            if (event.text.isSilentConnectionRecoveryPrompt()) return
            ChatMessageBubble(
                sender = ChatBubbleSender.User,
                group = bubbleGroup,
                testTag = "user-message-bubble",
                metadata = event.text.takeIf { it.isNotBlank() }?.let { text ->
                    {
                        ChatMessageMetadata(
                            reverse = true,
                            footer = { ChatMessageCopyAction(text) },
                        )
                    }
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (event.text.isNotBlank()) {
                        ChatUserText(event.text)
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
        }
        is AgentEvent.ToolCall -> if (AgentSpawnPresentation.isAgentSpawn(event.toolName, event.summary, event.detail)) {
            SpawningAgentsBlock(
                sources = listOf(
                    AgentSpawnPresentation.SpawnSource(event.toolName, event.summary, event.detail),
                ),
                expanded = toolExpanded,
                onExpandedChange = { expanded -> onToolExpandedChange(eventKey, expanded) },
                animateExpansion = !autoExpandToolSections,
                knownTasks = knownTasks,
                currentTaskId = currentTaskId,
            )
        } else {
            ToolBlock(
                expanded = toolExpanded,
                onExpandedChange = { expanded -> onToolExpandedChange(eventKey, expanded) },
                animateExpansion = !autoExpandToolSections,
                marker = "▸",
                name = event.toolName,
                summary = event.summary,
                detail = event.detail,
                kind = event.kind,
                locations = event.locations,
                images = event.images,
                color = Cyan,
                forceVisible = event.state == AgentToolState.Failed,
                onToolFileOpen = onToolFileOpen,
            )
        }
        is AgentEvent.ToolResult -> if (event.isError || !AgentSpawnPresentation.isAgentSpawn(event.toolName, event.summary, event.detail)) {
            ToolBlock(
                expanded = toolExpanded,
                onExpandedChange = { expanded -> onToolExpandedChange(eventKey, expanded) },
                animateExpansion = !autoExpandToolSections,
                marker = if (event.isError) "✗" else "✓",
                name = event.toolName,
                summary = event.summary,
                detail = event.detail,
                color = if (event.isError) Red else TextSecondary,
                forceVisible = event.isError,
                onToolFileOpen = onToolFileOpen,
            )
        }
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
        is AgentEvent.PlanUpdate -> PlanDocumentBlock(
            entries = event.entries,
            markdown = event.markdown,
            awaitingApproval = awaitingPlanConfirmation,
        )
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

/** Plan Mode's warm gold status accent — the one punctuation color for the whole card. */
private val PlanAccent = Yellow

/** Technical-token accent for inline code inside a plan document, distinct from chat's default. */
private val PlanCodeAccent = Color(0xFF4EC5B4)

/**
 * A provider's plan, rendered as its own nested document rather than plain transcript text.
 * The outer gold frame is the one non-neutral border in the transcript; it exists purely to
 * mark "this needs your attention" without resorting to a banner or a modal.
 */
@Composable
private fun PlanDocumentBlock(
    entries: List<AgentPlanEntry>,
    markdown: String?,
    awaitingApproval: Boolean,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AndyRadius.Sheet))
            .background(AndyColors.Neutral900)
            .border(1.dp, PlanAccent.copy(alpha = 0.85f), RoundedCornerShape(AndyRadius.Sheet))
            .padding(AndySpace.Space4),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("≡", color = PlanAccent, fontFamily = MonoFont, fontSize = 12.sp)
            Text("Plan", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (awaitingApproval) {
                Text("Awaiting approval", color = TextSecondary, fontSize = 12.sp)
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(AndyColors.SurfaceRaised, RoundedCornerShape(AndyRadius.Control))
                .border(1.dp, Border, RoundedCornerShape(AndyRadius.Control))
                .padding(AndySpace.Space4),
            verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            markdown?.takeIf { it.isNotBlank() }?.let { text ->
                ChatMarkdown(text, lineHeight = 20.sp, codeAccent = PlanCodeAccent)
            }
            entries.forEachIndexed { index, entry ->
                PlanEntryLine(index + 1, entry)
            }
        }
    }
}

@Composable
private fun PlanEntryLine(index: Int, entry: AgentPlanEntry) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "$index.",
            color = TextSecondary.copy(alpha = 0.7f),
            fontFamily = MonoFont,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ChatMarkdown(entry.content, lineHeight = 19.sp, codeAccent = PlanCodeAccent)
            if (entry.status.isNotBlank() && entry.status != "pending") {
                Text(
                    entry.status,
                    color = TextSecondary.copy(alpha = 0.55f),
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

private val TranscriptAsideIndent = 14.dp
private val TranscriptAsideContentIndent = 22.dp

@Composable
private fun ThinkingStep(
    text: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    animateExpansion: Boolean = true,
) {
    val expandable = text.lineSequence().any { it.isNotBlank() }
    TranscriptExpandableRow(
        headline = "Thinking",
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        expandable = expandable,
        animateExpansion = animateExpansion,
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
private fun ChatUserText(text: String) {
    Text(
        text,
        color = TextPrimary,
        fontFamily = DisplayFont,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AgentResponse(
    group: ChatBubbleGroup = ChatBubbleGroup.Single,
    copyText: String? = null,
    content: @Composable () -> Unit,
) {
    ChatMessageBubble(
        sender = ChatBubbleSender.Assistant,
        variant = ChatBubbleVariant.Ghost,
        group = group,
        metadata = copyText?.takeIf { it.isNotBlank() }?.let { text ->
            {
                ChatMessageMetadata(
                    footer = { ChatMessageCopyAction(text) },
                )
            }
        },
    ) {
        content()
    }
}

@Composable
private fun AgentCompletion(
    event: AgentEvent.TaskResult,
    completedContent: (@Composable () -> Unit)?,
) {
    val duration = event.durationMs
        ?.takeIf { it >= 0L }
        ?.let { formatWorkedClock(it) }
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
            AgentResponse(copyText = stripDecisionCheckpointMarkup(it)) {
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
        // Dialogs are a separate window/layout tree. This preview is still composed
        // under the transcript SelectionContainer, so a mouse press on Text here
        // crashes desktop Compose: "layouts are not part of the same hierarchy".
        DisableSelection {
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
    autoExpandThinkingSections: Boolean,
    autoExpandToolSections: Boolean,
    onToolExpandedChange: (String, Boolean) -> Unit,
    onThinkingExpandedChange: (String, Boolean) -> Unit,
    onToolFileOpen: (ToolCallFileContent) -> Unit,
    knownTasks: List<AgentTask> = emptyList(),
    currentTaskId: String? = null,
) {
    // isAgentSpawn runs several regex passes over each event's summary/detail. Classifying
    // once per composition (memoized on `events`) instead of on every recomposition — e.g.
    // toggling one tool's expanded state used to reclassify every event in the block — is
    // what kept this from being a per-frame cost while scrolling an ACP transcript.
    val classification = remember(events) {
        val spawnFlags = events.map { event ->
            when (event) {
                is AgentEvent.ToolCall -> AgentSpawnPresentation.isAgentSpawn(event.toolName, event.summary, event.detail)
                is AgentEvent.ToolResult -> AgentSpawnPresentation.isAgentSpawn(event.toolName, event.summary, event.detail)
                else -> false
            }
        }
        val spawnSources = AgentSpawnPresentation.spawnSources(events)
        val spawnOnly = spawnSources.isNotEmpty() &&
            events.none { it is AgentEvent.ToolResult && it.isError } &&
            spawnFlags.all { it }
        ToolCallsBlockClassification(
            spawnFlags = spawnFlags,
            spawnSources = spawnSources,
            spawnOnly = spawnOnly,
            hasError = events.any { it is AgentEvent.ToolResult && it.isError },
            headline = compactActivityHeadline(events),
        )
    }
    if (classification.spawnOnly) {
        SpawningAgentsBlock(
            sources = classification.spawnSources,
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            animateExpansion = !autoExpandToolSections,
            knownTasks = knownTasks,
            currentTaskId = currentTaskId,
        )
        return
    }

    val headlineColor = if (classification.hasError) Red.copy(alpha = 0.9f) else TextSecondary

    TranscriptExpandableRow(
        headline = classification.headline,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        animateExpansion = !autoExpandToolSections,
        headlineColor = headlineColor,
        indent = TranscriptAsideIndent,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            events.forEachIndexed { offset, event ->
                val eventKey = transcriptEventKey(startIndex + offset, event)
                val isSpawn = classification.spawnFlags[offset]
                when (event) {
                    is AgentEvent.Thinking -> ThinkingStep(
                        text = event.text,
                        expanded = transcriptActivityExpanded(eventKey, expandedThinkingKeys, autoExpandThinkingSections),
                        onExpandedChange = { value -> onThinkingExpandedChange(eventKey, value) },
                        animateExpansion = !autoExpandThinkingSections,
                    )
                    is AgentEvent.ToolCall -> if (isSpawn) {
                        val source = AgentSpawnPresentation.spawnSources(listOf(event)).singleOrNull()
                            ?: AgentSpawnPresentation.SpawnSource(event.toolName, event.summary, event.detail)
                        SpawnedAgentLine(
                            spawn = AgentSpawnPresentation.parse(source.toolName, source.summary, source.detail),
                            indent = TranscriptAsideContentIndent,
                            knownTasks = knownTasks,
                            currentTaskId = currentTaskId,
                        )
                    } else {
                        ToolBlock(
                            expanded = transcriptActivityExpanded(eventKey, expandedToolKeys, autoExpandToolSections),
                            onExpandedChange = { value -> onToolExpandedChange(eventKey, value) },
                            animateExpansion = !autoExpandToolSections,
                            marker = "▸",
                            name = event.toolName,
                            summary = event.summary,
                            detail = event.detail,
                            kind = event.kind,
                            locations = event.locations,
                            images = event.images,
                            color = Cyan,
                            forceVisible = event.state == AgentToolState.Failed,
                            indent = TranscriptAsideContentIndent,
                            onToolFileOpen = onToolFileOpen,
                        )
                    }
                    is AgentEvent.ToolResult -> if (event.isError || !isSpawn) {
                        ToolBlock(
                            expanded = transcriptActivityExpanded(eventKey, expandedToolKeys, autoExpandToolSections),
                            onExpandedChange = { value -> onToolExpandedChange(eventKey, value) },
                            animateExpansion = !autoExpandToolSections,
                            marker = if (event.isError) "✗" else "✓",
                            name = event.toolName,
                            summary = event.summary,
                            detail = event.detail,
                            color = if (event.isError) Red else TextSecondary,
                            forceVisible = event.isError,
                            indent = TranscriptAsideContentIndent,
                            onToolFileOpen = onToolFileOpen,
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}

private data class ToolCallsBlockClassification(
    val spawnFlags: List<Boolean>,
    val spawnSources: List<AgentSpawnPresentation.SpawnSource>,
    val spawnOnly: Boolean,
    val hasError: Boolean,
    val headline: String,
)

@Composable
private fun SpawningAgentsBlock(
    sources: List<AgentSpawnPresentation.SpawnSource>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    animateExpansion: Boolean = true,
    knownTasks: List<AgentTask> = emptyList(),
    currentTaskId: String? = null,
) {
    val count = sources.size.coerceAtLeast(1)
    TranscriptExpandableRow(
        headline = AgentSpawnPresentation.spawningHeadline(count),
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        animateExpansion = animateExpansion,
        headlineColor = TextSecondary,
        indent = TranscriptAsideIndent,
        headlineContent = { SpawningAgentsHeadline(count) },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            sources.forEach { source ->
                SpawnedAgentLine(
                    spawn = AgentSpawnPresentation.parse(source.toolName, source.summary, source.detail),
                    indent = TranscriptAsideContentIndent,
                    knownTasks = knownTasks,
                    currentTaskId = currentTaskId,
                )
            }
        }
    }
}

@Composable
private fun SpawningAgentsHeadline(count: Int) {
    if (count <= 1) {
        Text(
            "Spawning agent",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = TextSecondary)) { append("Spawning ") }
            withStyle(SpanStyle(color = TextPrimary)) { append("$count") }
            withStyle(SpanStyle(color = TextSecondary)) { append(" agents") }
        },
        fontSize = 12.sp,
        lineHeight = 17.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SpawnedAgentLine(
    spawn: AgentSpawnPresentation.Spawn,
    indent: Dp = TranscriptAsideIndent,
    knownTasks: List<AgentTask> = emptyList(),
    currentTaskId: String? = null,
) {
    val openAgentTask = LocalOpenAgentTask.current
    val taskId = remember(spawn, knownTasks, currentTaskId) {
        AgentSpawnPresentation.resolveTaskId(spawn, knownTasks, excludeTaskId = currentTaskId)
    }
    val linkedTask = remember(taskId, knownTasks) { knownTasks.firstOrNull { it.id == taskId } }
    // Prefer the live Andy chat title so the colored link matches the child inbox row.
    val displayName = linkedTask?.title?.takeIf { it.isNotBlank() } ?: spawn.name
    val displayType = spawn.type
        ?: linkedTask?.agent?.cliName
    val instructions = spawn.instructions.ifBlank {
        linkedTask?.prompt?.lineSequence()?.firstOrNull()?.trim().orEmpty()
    }
    val nameColor = remember(displayName) { agentSpawnNameColor(displayName) }
    val muted = TextSecondary.copy(alpha = 0.88f)
    val suffix = buildString {
        displayType
            ?.takeIf { it.isNotBlank() && !it.equals(displayName, ignoreCase = true) }
            ?.let { append(" ($it)") }
        if (instructions.isNotBlank()) {
            append(" with the instructions: ")
            append(instructions)
        }
    }
    DisableSelection {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = indent),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Created ", color = muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1)
            Text(
                displayName,
                color = nameColor,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .then(
                        if (taskId != null) {
                            Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable {
                                    openAgentTask(OpenAgentTaskRequest(taskId, linkedTask?.projectId))
                                }
                        } else {
                            Modifier
                        },
                    ),
            )
            if (suffix.isNotBlank()) {
                Text(
                    suffix,
                    color = muted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

/** Stable pastel accents so parallel spawn rows stay distinguishable, Cursor-style. */
internal fun agentSpawnNameColor(name: String): Color {
    val palette = listOf(
        Color(0xFFF07178),
        Color(0xFFC3A6FF),
        Color(0xFFE6A35C),
        Color(0xFF7DCEA0),
        Color(0xFF6EC6E0),
        Color(0xFFE8A0BF),
        Color(0xFFD4C05C),
        Color(0xFF9BDBB3),
    )
    var hash = 0
    for (ch in name) hash = hash * 31 + ch.code
    // Unsigned view avoids abs(Int.MIN_VALUE) staying negative and indexing the palette with < 0.
    return palette[(hash.toUInt() % palette.size.toUInt()).toInt()]
}

@Composable
private fun ToolBlock(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    animateExpansion: Boolean = true,
    marker: String,
    name: String?,
    summary: String,
    detail: String,
    kind: AgentToolKind? = null,
    locations: List<String> = emptyList(),
    images: List<AgentToolImage> = emptyList(),
    color: Color,
    forceVisible: Boolean = false,
    indent: Dp = TranscriptAsideIndent,
    onToolFileOpen: (ToolCallFileContent) -> Unit = {},
) {
    if (!forceVisible && toolRowShowsNothing(name, summary, detail, locations, images.isNotEmpty())) return
    val headline = toolBlockHeadline(name, summary, kind, locations)
    val rawBody = detail
        .takeUnless { AcpToolCallPresentation.isMinimalOutput(it) }
        .orEmpty()
        .ifBlank { summary.takeUnless { AcpToolCallPresentation.isMinimalOutput(it) }.orEmpty() }
    // Persisted transcripts and the MCP lane both hand back provider payloads verbatim, so the
    // JSON-to-Markdown conversion has to happen here rather than where events are first mapped.
    val body = remember(rawBody) { AcpToolCallPresentation.displayDetail(rawBody) }
    val expandable = images.isNotEmpty() ||
        AcpToolCallPresentation.detailAddsInformation(headline, body)
    val fileContent = remember(rawBody, kind) {
        parseToolCallFileContent(rawBody) ?: parseToolCallFileArguments(rawBody, kind)
    }
    // Command results arrive as {"exitCode":…,"stdout":"<a diff>"}, so the diff worth reviewing is
    // one level inside the payload rather than the payload itself.
    val payloadDiffData = remember(rawBody) {
        (AcpToolCallPresentation.payloadTextValues(rawBody) + rawBody)
            .firstNotNullOfOrNull { candidate ->
                val patch = extractUnifiedDiffText(candidate) ?: candidate
                detectUnifiedDiff(patch)?.let { patch to it }
            }
    }
    val payloadDiff = payloadDiffData?.second
    val payloadExtraBody = remember(rawBody, payloadDiffData) {
        payloadDiffData?.first
            ?.let { AcpToolCallPresentation.displayDetailExcludingPayload(rawBody, it) }
            .orEmpty()
    }
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
        animateExpansion = animateExpansion,
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
            diff = payloadDiff,
            diffExtraBody = payloadExtraBody,
            images = images,
            onOpen = onToolFileOpen,
        )
    }
}

@Composable
private fun ToolCallDetailBody(
    body: String,
    fileContent: ToolCallFileContent?,
    diff: AgentFileDiff?,
    diffExtraBody: String = "",
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
            val fileDiff = if (fileContent.hasDiff) {
                remember(fileContent) { diffFromToolCallFileContent(fileContent) }
            } else {
                diff
            }
            val preview = fileContent.newText.orEmpty()
            if (fileDiff != null) {
                ToolCallDiff(fileDiff)
            } else if (preview.isNotBlank()) {
                ChatMarkdown(
                    toolDetailMarkdown(preview, fileContent.path),
                    density = AndyMarkdownDensity.Thinking,
                    lineHeight = 16.sp,
                    preserveLineBreaks = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
            fileContent.extraDetail?.takeIf { it.isNotBlank() }?.let { extra ->
                ChatMarkdown(
                    toolDetailMarkdown(extra),
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
    if (diff != null) {
        ToolCallDiff(diff, modifier = Modifier.padding(top = 4.dp))
        if (diffExtraBody.isNotBlank()) {
            ChatMarkdown(
                toolDetailMarkdown(diffExtraBody),
                density = AndyMarkdownDensity.Thinking,
                lineHeight = 16.sp,
                preserveLineBreaks = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
        return
    }
    if (body.isBlank()) return
    ChatMarkdown(
        toolDetailMarkdown(body),
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
private fun ToolCallDiff(diff: AgentFileDiff, modifier: Modifier = Modifier) {
    var viewMode by remember(diff) { mutableStateOf(DiffViewMode.Unified) }
    AgentFileDiffViewer(
        diff = diff,
        viewMode = viewMode,
        onViewModeChange = { viewMode = it },
        onCollapse = {},
        showCollapseControl = false,
        showPath = false,
        maxHeight = 220.dp,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Keeps authored Markdown intact while giving plain source/terminal output a highlighted code block. */
internal fun toolDetailMarkdown(body: String, path: String? = null): String {
    val trimmed = body.trim()
    if (trimmed.isEmpty() || looksLikeMarkdown(trimmed)) return trimmed
    return if (path != null || looksLikeCode(trimmed)) {
        fencedCodeBlock(trimmed, codeLanguageForPath(path))
    } else {
        trimmed
    }
}

// Checked per line of a tool's detail body (a file read or command output can be thousands of
// lines), so these must be compiled once — constructing a fresh Regex per line per render was
// measurable CPU on any transcript row with a sizeable tool output.
private val MarkdownHeadingPattern = Regex("""^#{1,6}\s+""")
private val MarkdownBulletPattern = Regex("""^[-*+]\s+""")
private val MarkdownOrderedListPattern = Regex("""^\d+[.)]\s+""")
private val MarkdownTableRowPattern = Regex("""^\|.+\|$""")

private fun looksLikeMarkdown(text: String): Boolean = text.lineSequence().any { line ->
    val trimmed = line.trimStart()
    trimmed.startsWith("```") ||
        trimmed.startsWith("~~~") ||
        MarkdownHeadingPattern.containsMatchIn(trimmed) ||
        trimmed.startsWith("> ") ||
        MarkdownBulletPattern.containsMatchIn(trimmed) ||
        MarkdownOrderedListPattern.containsMatchIn(trimmed) ||
        MarkdownTableRowPattern.containsMatchIn(trimmed)
}

private val CodeKeywordLinePattern =
    Regex("""^\s*(fun|class|interface|object|enum|data class|val|var|import|package|def|function|const|let|fn|struct)\b""")
private val CodeShellLinePattern = Regex("""^\s*([+>$]|at\s+\S+|[A-Za-z0-9_./-]+:\d+:)""")

private fun looksLikeCode(text: String): Boolean =
    text.contains('\n') && text.lineSequence().any { line ->
        line.startsWith("    ") ||
            line.startsWith("\t") ||
            CodeKeywordLinePattern.containsMatchIn(line) ||
            CodeShellLinePattern.containsMatchIn(line) ||
            line.trimEnd().endsWith("{") ||
            line.trimEnd().endsWith(";")
    }

private fun codeLanguageForPath(path: String?): String = when (path?.substringAfterLast('.', "").orEmpty().lowercase()) {
    "kt", "kts" -> "kotlin"
    "java" -> "java"
    "js", "mjs", "cjs" -> "javascript"
    "ts", "tsx" -> "typescript"
    "py" -> "python"
    "rs" -> "rust"
    "go" -> "go"
    "rb" -> "ruby"
    "sh", "bash", "zsh" -> "shell"
    "json" -> "json"
    "yaml", "yml" -> "yaml"
    "md", "markdown" -> "markdown"
    "html" -> "html"
    "css" -> "css"
    "sql" -> "sql"
    else -> ""
}

/** Wraps [body] in a Markdown fence long enough to survive any backtick runs already inside it. */
private fun fencedCodeBlock(body: String, language: String = ""): String {
    val longestRun = Regex("`+").findAll(body).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxOf(3, longestRun + 1))
    return "$fence$language\n$body\n$fence"
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
    animateExpansion: Boolean = true,
    headlineColor: Color = TextSecondary,
    indent: Dp = 0.dp,
    contentIndent: Dp = indent + 8.dp,
    headlineContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    val columnModifier = if (animateExpansion) {
        modifier.fillMaxWidth().animateContentSize()
    } else {
        modifier.fillMaxWidth()
    }
    Column(columnModifier) {
        DisableSelection {
            Row(
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (expandable) {
                    Text(
                        if (expanded) "v" else ">",
                        color = headlineColor.copy(alpha = 0.7f),
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                        modifier = Modifier.width(10.dp),
                    )
                }
                if (headlineContent != null) {
                    Box(Modifier.weight(1f, fill = false)) { headlineContent() }
                } else {
                    Text(
                        headline,
                        color = headlineColor,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
        if (expandable) {
            if (animateExpansion) {
                AnimatedVisibility(visible = expanded) {
                    Column(Modifier.fillMaxWidth().padding(start = contentIndent)) {
                        content()
                    }
                }
            } else if (expanded) {
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
    val allCalls = events.filterIsInstance<AgentEvent.ToolCall>()
    if (allCalls.isEmpty()) {
        val count = events.size
        return "$count tool ${if (count == 1) "result" else "results"}"
    }
    // Content-free calls are not rendered as rows, so they must not colour the group headline
    // either — but they still happened, so a group made only of them is counted, not named.
    val toolCalls = allCalls.filterNot {
        toolRowShowsNothing(
            it.toolName,
            it.summary,
            it.detail,
            it.locations,
            it.images.isNotEmpty(),
            isFailure = it.state == AgentToolState.Failed,
        )
    }
    if (toolCalls.isEmpty()) {
        val count = allCalls.size
        return "$count tool ${if (count == 1) "call" else "calls"}"
    }
    if (toolCalls.size == 1) {
        val call = toolCalls.single()
        return toolActionPhrase(call.toolName, call.summary, call.kind, call.locations)
    }
    val counts = toolCalls.groupingBy { it.activityCategory() }.eachCount()
    val summaryParts = buildList {
        counts[ToolActivityCategory.Spawn]?.let { add(AgentSpawnPresentation.spawningHeadline(it)) }
        counts[ToolActivityCategory.Read]?.let { add("read $it ${if (it == 1) "file" else "files"}") }
        counts[ToolActivityCategory.Search]?.let { add(if (it == 1) "searched" else "searched $it times") }
        counts[ToolActivityCategory.Edit]?.let { add("edited $it ${if (it == 1) "file" else "files"}") }
        counts[ToolActivityCategory.Command]?.let { add("ran $it ${if (it == 1) "command" else "commands"}") }
        counts[ToolActivityCategory.Fetch]?.let { add("fetched $it ${if (it == 1) "resource" else "resources"}") }
    }
    if (summaryParts.isNotEmpty()) {
        val other = counts[ToolActivityCategory.Other] ?: 0
        val parts = if (other > 0) {
            summaryParts + "$other other tool ${if (other == 1) "call" else "calls"}"
        } else {
            summaryParts
        }
        return parts.joinToString(", ")
    }
    val phrases = toolCalls.map { toolActionPhrase(it.toolName, it.summary, it.kind, it.locations) }
    val meaningfulPhrases = phrases.filter { it.isNotBlank() && !AcpToolCallPresentation.isMinimalOutput(it) }
    if (meaningfulPhrases.isEmpty()) {
        val count = toolCalls.size
        return "$count tool ${if (count == 1) "call" else "calls"}"
    }
    return meaningfulPhrases.take(3).joinToString(", ").let { headline ->
        if (meaningfulPhrases.size > 3) "$headline, …" else headline
    }
}

private enum class ToolActivityCategory { Spawn, Read, Search, Edit, Command, Fetch, Other }

/**
 * Every call in a group lands in exactly one bucket, so the group headline can account for all of
 * them. Naming only the recognized categories dropped the rest of the group from the headline
 * entirely — a turn of one read and eight greps was headlined "read 1 file" — and cursor-agent makes
 * that the common case: it reports every kind as [AgentToolKind.Other] and titles a shell call with
 * the command itself, leaving the arguments as the only evidence of what ran.
 */
private fun AgentEvent.ToolCall.activityCategory(): ToolActivityCategory {
    if (AgentSpawnPresentation.isAgentSpawn(toolName, summary, detail)) return ToolActivityCategory.Spawn
    val declared = kind?.takeUnless { it == AgentToolKind.Other }
    if (declared != null) return declared.activityCategory()
    val lower = toolName.trim().lowercase()
    return when {
        lower in SearchToolNames -> ToolActivityCategory.Search
        lower in ReadToolNames -> ToolActivityCategory.Read
        lower in EditToolNames -> ToolActivityCategory.Edit
        lower in CommandToolNames -> ToolActivityCategory.Command
        else -> AcpToolCallPresentation.inferKindFromArguments(detail)
            ?.activityCategory()
            ?: ToolActivityCategory.Other
    }
}

private fun AgentToolKind.activityCategory(): ToolActivityCategory = when (this) {
    AgentToolKind.Read -> ToolActivityCategory.Read
    AgentToolKind.Search -> ToolActivityCategory.Search
    AgentToolKind.Edit, AgentToolKind.Delete, AgentToolKind.Move -> ToolActivityCategory.Edit
    AgentToolKind.Execute -> ToolActivityCategory.Command
    AgentToolKind.Fetch -> ToolActivityCategory.Fetch
    AgentToolKind.Think, AgentToolKind.Other -> ToolActivityCategory.Other
}

private const val ToolHeadlineLimit = 160

/**
 * A row headline is a label, but the fields it comes from are provider-controlled: a `summary` can
 * be a whole multi-line command or a 4 KB command result. Collapse it to one short line and leave
 * the rest to the expanded body.
 */
private val WhitespaceRunPattern = Regex("""\s+""")

internal fun condenseToolHeadline(text: String): String {
    val collapsed = text.replace(WhitespaceRunPattern, " ").trim()
    return if (collapsed.length <= ToolHeadlineLimit) {
        collapsed
    } else {
        collapsed.take(ToolHeadlineLimit).trimEnd() + "…"
    }
}

/**
 * ACP lanes emit bookkeeping tool events carrying only a call id — no name, arguments, output, or
 * location. A row for one of those says nothing at all, so the transcript leaves it out.
 */
internal fun toolRowShowsNothing(
    name: String?,
    summary: String,
    detail: String,
    locations: List<String>,
    hasImages: Boolean,
    isFailure: Boolean = false,
): Boolean {
    if (isFailure || hasImages || locations.any { it.isNotBlank() }) return false
    if (!AcpToolCallPresentation.isGenericTitle(name?.trim().orEmpty())) return false
    return AcpToolCallPresentation.isMinimalOutput(summary) &&
        AcpToolCallPresentation.isMinimalOutput(detail)
}

internal fun toolBlockHeadline(
    name: String?,
    summary: String,
    kind: AgentToolKind?,
    locations: List<String>,
): String = condenseToolHeadline(rawToolBlockHeadline(name, summary, kind, locations))

private fun rawToolBlockHeadline(
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
    // Bare "Edit" / "Terminal" labels read as empty chrome — prefer an action phrase, and
    // when we do have a path/command, lead with Edited/Ran rather than "Edit: path".
    // A generic "tool" is not a name at all, so it must never prefix the summary.
    if (AcpToolCallPresentation.isGenericOrSparseTitle(label) || label.isBlank()) {
        return toolActionPhrase(label, detail.ifBlank { summary }, kind, locations)
    }
    return when {
        detail.isNotBlank() && !label.equals(detail, ignoreCase = true) -> "$label: $detail"
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
): String = condenseToolHeadline(rawToolActionPhrase(toolName, summary, kind, locations))

private fun rawToolActionPhrase(
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
        .let { text ->
            // Avoid "Ran Terminal" / "Edited Edit" when the only "summary" is the sparse label.
            if (AcpToolCallPresentation.isSparseToolTitle(text)) "" else text
        }
    return when {
        AgentSpawnPresentation.isAgentSpawn(toolName, summary, "") -> AgentSpawnPresentation.spawningHeadline(1)
        lower in SearchToolNames || kind == AgentToolKind.Search ->
            trimmedSummary.takeIf { it.isNotBlank() }?.let { "Searched $it" } ?: "Searched"
        lower in ReadToolNames || kind == AgentToolKind.Read ->
            trimmedSummary.takeIf { it.isNotBlank() }?.let { "Read $it" } ?: "Read file"
        lower in EditToolNames || kind == AgentToolKind.Edit || kind == AgentToolKind.Delete ->
            trimmedSummary.takeIf { it.isNotBlank() }?.let {
                if (kind == AgentToolKind.Delete || lower == "delete") "Deleted $it" else "Edited $it"
            } ?: if (kind == AgentToolKind.Delete || lower == "delete") "Deleted file" else "Edited file"
        lower in CommandToolNames || kind == AgentToolKind.Execute ->
            trimmedSummary.takeIf { it.isNotBlank() }?.let { "Ran $it" } ?: "Ran command"
        trimmedSummary.isNotBlank() -> trimmedSummary
        toolName.isNotBlank() && !AcpToolCallPresentation.isGenericTitle(toolName) -> toolName
        else -> toolKindPhrase(kind) ?: "Tool call"
    }
}

/** Last resort when a provider sent neither a usable name nor arguments: name the action by kind. */
private fun toolKindPhrase(kind: AgentToolKind?): String? = when (kind) {
    AgentToolKind.Read -> "Read file"
    AgentToolKind.Edit -> "Edited file"
    AgentToolKind.Delete -> "Deleted file"
    AgentToolKind.Move -> "Moved file"
    AgentToolKind.Search -> "Searched"
    AgentToolKind.Execute -> "Ran command"
    AgentToolKind.Think -> "Thought"
    AgentToolKind.Fetch -> "Fetched a resource"
    AgentToolKind.Other, null -> null
}

private val ReadToolNames = setOf(
    "read", "read file", "read_file", "file_read", "get_network_request",
)
private val SearchToolNames = setOf(
    "grep", "grep_search", "glob", "glob_file_search", "search", "find", "file_search",
    "codebase_search", "list_dir", "list dir", "web search", "web_search",
)
private val CommandToolNames = setOf(
    "shell", "run_terminal_cmd", "bash", "terminal", "execute", "run", "command",
)
private val EditToolNames = setOf(
    "edit", "edit file", "edit_file", "editing files", "str_replace", "apply_patch",
    "write", "delete", "create", "update",
)

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
    is AgentEvent.UserMessage -> text.isSilentConnectionRecoveryPrompt()
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

/**
 * The approval gate for a finished plan turn: a quiet gold-framed card that sits right above
 * the composer. Reject and Approve stay separate actions on purpose — refining is a low-emphasis
 * text action (it just continues the conversation), while implementing is the one that changes
 * the workspace, so it gets the outlined button treatment.
 */
@Composable
internal fun PlanApprovalCard(
    showImplementAction: Boolean,
    onImplement: () -> Unit,
    onRefine: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var feedback by remember { mutableStateOf("") }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AndyRadius.Sheet))
            .background(AndyColors.Neutral900)
            .border(1.dp, PlanAccent.copy(alpha = 0.85f), RoundedCornerShape(AndyRadius.Sheet))
            .padding(AndySpace.Space4),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("≡", color = PlanAccent, fontFamily = MonoFont, fontSize = 12.sp)
            Text("Plan", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("Awaiting approval", color = TextSecondary, fontSize = 12.sp)
        }
        Text(
            if (showImplementAction) {
                "This turn finished in plan mode. Nothing was changed. Implement when you're ready, or leave feedback and refine below."
            } else {
                "This turn finished in plan mode. Nothing was changed. Review the plan in Projects, or leave feedback and refine below."
            },
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
        TextField(
            value = feedback,
            onValueChange = { feedback = it },
            singleLine = true,
            placeholder = {
                Text("Optional feedback if refining…", color = TextSecondary, fontFamily = MonoFont, fontSize = 12.sp)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    val trimmed = feedback.trim()
                    if (trimmed.isNotEmpty()) {
                        onRefine(trimmed)
                        feedback = ""
                    }
                },
                enabled = feedback.isNotBlank(),
                modifier = Modifier
                    .height(AndyLayout.ControlHeightMd)
                    .defaultMinSize(minHeight = AndyLayout.ControlHeightMd),
                contentPadding = PaddingValues(horizontal = AndySpace.Space5, vertical = 0.dp),
            ) {
                Text("Refine", fontSize = 12.sp)
            }
            if (showImplementAction) {
                OutlinedButton(onClick = onImplement) {
                    Text("Implement plan", fontSize = 12.sp)
                }
            }
        }
    }
}
