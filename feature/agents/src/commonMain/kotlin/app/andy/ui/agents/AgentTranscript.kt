@file:Suppress("DEPRECATION")

package app.andy.ui.agents

/**
 * Legacy structured-event transcript UI.
 *
 * The embedded-terminal redesign replaces this with [AgentTerminalSurface];
 * the PTY buffer is the transcript. Kept for unit tests and gradual removal.
 */
import androidx.compose.animation.AnimatedVisibility
import app.andy.ui.components.Lucide
import app.andy.ui.components.LucideIcon
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.ui.platform.LocalDensity
import app.andy.formatDisplayTime
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
import app.andy.model.AgentConnectionRecovery
import app.andy.model.AgentConnectionRecoveryReason
import app.andy.model.maxAutomaticAttempts
import app.andy.model.AgentToolImage
import app.andy.model.AgentToolKind
import app.andy.model.AgentToolState
import app.andy.model.isRetriableConnectionStallMessage
import app.andy.model.isSilentConnectionRecoveryPrompt
import app.andy.model.stripTrailingConnectionStallError
import app.andy.model.stripDecisionCheckpointMarkup
import app.andy.model.AgentSkill
import app.andy.model.TranscriptDisplayItem
import app.andy.model.compactActivityHeadline
import app.andy.model.toolBlockHeadline
import app.andy.model.toolRowShowsNothing
import app.andy.model.transcriptActivityExpanded
import app.andy.model.transcriptDisplayEvents
import app.andy.model.transcriptDisplayItems
import app.andy.model.withLinkedChildSpawnItems
import app.andy.model.coalesceAgentStreamDeltas
import app.andy.model.promptWithImageHints
import app.andy.model.promptWithSkillHints
import app.andy.model.isSkillOnlyMessage
import app.andy.model.stripCliSkillHints
import app.andy.service.OpenAgentTaskRequest
import app.andy.ui.components.LocalOpenAgentTask
import app.andy.ui.components.ReportContentScrollBusy
import app.andy.ui.components.AndyMarkdownDensity
import app.andy.ui.components.ChatMarkdown
import app.andy.ui.components.EmptyState
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.TextField
import app.andy.ui.components.ThinkingOrb
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyOverlay
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.PaneDividerTint
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
class TranscriptCompositionCounter {
    var rootRestarts = 0
}

val LocalTranscriptCompositionCounter = compositionLocalOf<TranscriptCompositionCounter?> { null }

/** Explicit per-task scroll snapshot. */
data class TranscriptScrollPosition(
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
class TranscriptScrollMemory {
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
fun AgentTranscript(
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
    originalSkills: List<AgentSkill> = emptyList(),
    /** Wall time for the launch prompt bubble when it is synthesized (not from [AgentEvent.UserMessage]). */
    originalPromptAtMillis: Long? = null,
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
    /**
     * Exact user text from the latest send. When that row appears in [events], play a one-shot
     * entrance on it only — not on assistant/tool rows or historical messages.
     */
    pendingSendEntranceText: String? = null,
    onPendingSendEntranceConsumed: () -> Unit = {},
    autoExpandThinkingSections: Boolean = false,
    autoExpandToolSections: Boolean = false,
    collapseActivityBetweenMessages: Boolean = false,
    onToolFileOpen: (ToolCallFileContent) -> Unit = {},
    onFileChangesReview: (AgentEvent.FileChanges) -> Unit = {},
    onFileChangesUndo: (AgentEvent.FileChanges) -> Unit = {},
    /** Known Andy chats used to resolve spawn rows to openable task ids. */
    knownTasks: List<AgentTask> = emptyList(),
    /** Current chat id so spawn resolution does not link a row back to itself. */
    currentTaskId: String? = null,
    modifier: Modifier = Modifier,
) {
    val compositionCounter = LocalTranscriptCompositionCounter.current
    SideEffect { compositionCounter?.let { it.rootRestarts++ } }
    val scope = rememberCoroutineScope()
    val displayItems = remember(
        events,
        collapseActivityBetweenMessages,
        autoExpandThinkingSections,
        isActive,
        knownTasks,
        currentTaskId,
    ) {
        val base = transcriptDisplayItems(
            events,
            collapseActivityBetweenMessages = collapseActivityBetweenMessages,
            keepThinkingOnTimeline = autoExpandThinkingSections,
            hideOpenTurnFileChanges = isActive,
        )
        val alreadyLinked = AgentSpawnPresentation.resolvedSpawnTaskIds(
            events,
            knownTasks,
            excludeTaskId = currentTaskId,
        )
        val children = AgentSpawnPresentation.childrenOfParent(currentTaskId, knownTasks)
            .filter { it.id !in alreadyLinked }
        withLinkedChildSpawnItems(base, children)
    }
    val originalPromptVisible = shouldDisplayOriginalPrompt(events, originalPrompt, originalImagePaths, originalSkills)
    val latestTaskResultItemIndex = displayItems.indexOfLast { item ->
        item is TranscriptDisplayItem.Event && item.event is AgentEvent.TaskResult
    }
    val latestPlanUpdateItemIndex = displayItems.indexOfLast { item ->
        item is TranscriptDisplayItem.Event && item.event is AgentEvent.PlanUpdate
    }
    val taskId = restoreScrollKey
    // Forward layout: oldest at top, live edge at the end. Growing a detached row expands
    // downward from the top-anchored scroll position, so the text you scrolled to stays put
    // without any per-token scroll compensation (which previously froze the app).
    val restorePlan = remember(taskId) {
        val saved = taskId?.let { scrollMemory?.get(it) }
        when {
            saved == null || saved.stickToBottom -> TranscriptRestorePlan.StickToBottom
            else -> TranscriptRestorePlan.Exact(saved.index, saved.offset, saved.anchorKey)
        }
    }
    // Stick-to-bottom visits start at 0; we jump to the live edge once items exist.
    val listState = remember(taskId) { LazyListState(0, 0) }
    var stickToBottom by remember(taskId) {
        mutableStateOf(restorePlan is TranscriptRestorePlan.StickToBottom)
    }
    var scrollInitialized by remember(taskId) { mutableStateOf(false) }
    // Programmatic scrolls set isScrollInProgress; [active] must be snapshot-observable so
    // user-settle logic can ignore those jumps (plain Boolean is invisible to snapshotFlow).
    val programmaticScroll = remember(taskId) { ProgrammaticScrollFlag() }
    val liveEdgeRequester = remember(taskId) { BringIntoViewRequester() }
    // One-shot entrance for the bubble matching [pendingSendEntranceText] after send.
    var sendEntranceKey by remember(taskId) { mutableStateOf<String?>(null) }
    val onPendingSendEntranceConsumedLatest = rememberUpdatedState(onPendingSendEntranceConsumed)
    LaunchedEffect(pendingSendEntranceText, displayItems) {
        val needle = pendingSendEntranceText?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@LaunchedEffect
        val key = displayItems.asReversed().firstNotNullOfOrNull { item ->
            if (item !is TranscriptDisplayItem.Event) return@firstNotNullOfOrNull null
            val event = item.event as? AgentEvent.UserMessage ?: return@firstNotNullOfOrNull null
            if (event.text.isSilentConnectionRecoveryPrompt()) return@firstNotNullOfOrNull null
            if (event.text.trim() != needle) return@firstNotNullOfOrNull null
            transcriptDisplayItemKey(item)
        } ?: return@LaunchedEffect
        sendEntranceKey = key
        onPendingSendEntranceConsumedLatest.value()
    }
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
    // Content revision used to re-pin while following (stream tokens, new rows, thinking orb).
    val followEpoch = remember(
        events,
        displayItems.size,
        showThinkingIndicator,
        pendingContent != null,
        trailingContent != null,
    ) {
        val streamLen = events.asReversed()
            .firstOrNull { it is AgentEvent.AssistantText }
            ?.let { (it as AgentEvent.AssistantText).text.length }
            ?: 0
        val thinkingLen = events.asReversed()
            .firstOrNull { it is AgentEvent.Thinking }
            ?.let { (it as AgentEvent.Thinking).text.length }
            ?: 0
        listOf(
            streamLen,
            thinkingLen,
            displayItems.size,
            showThinkingIndicator,
            pendingContent != null,
            trailingContent != null,
        )
    }
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
            if (headerContent != null) add("task-header")
            if (originalPromptVisible) add("original-prompt")
            displayItems.forEach { add(transcriptDisplayItemKey(it)) }
            if (trailingContent != null) add("trailing-content")
            if (showThinkingIndicator) add("agent-thinking")
            if (pendingContent != null) add("pending-task-input")
            add("live-edge-anchor")
        }
    }

    // Never read listState.layoutInfo as a composition / LaunchedEffect key — that state
    // updates every scroll frame and would recompose the entire transcript (markdown rows
    // included). Stick-to-bottom restore needs no item count; exact restore waits in a flow.
    LaunchedEffect(taskId, eventsReady, rowKeys) {
        if (scrollInitialized || !eventsReady) return@LaunchedEffect
        when (val plan = restorePlan) {
            TranscriptRestorePlan.StickToBottom -> {
                stickToBottom = true
                snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
                listState.runProgrammaticScroll(programmaticScroll) {
                    liveEdgeRequester.bringIntoView()
                    scrollToLiveEdge()
                }
                scrollInitialized = true
            }
            is TranscriptRestorePlan.Exact -> {
                val itemCount = snapshotFlow { listState.layoutInfo.totalItemsCount }
                    .first { it > 0 }
                val anchoredIndex = plan.anchorKey
                    ?.let(rowKeys::indexOf)
                    ?.takeIf { it >= 0 }
                listState.runProgrammaticScroll(programmaticScroll) {
                    scrollToItem(
                        index = (anchoredIndex ?: plan.index).coerceIn(0, itemCount - 1),
                        scrollOffset = plan.offset,
                    )
                }
                stickToBottom = false
                scrollInitialized = true
            }
        }
    }

    // Persist each conversation independently. While detached, streaming growth is below the
    // forward-layout anchor so these coordinates stay stable without compensation.
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

    // User-driven scroll settle only. Programmatic live-edge jumps must not clear follow —
    // that was leaving stickToBottom false right after "follow live" when layout was one
    // frame behind the jump.
    LaunchedEffect(taskId, listState, scrollInitialized) {
        if (!scrollInitialized) return@LaunchedEffect
        var sawProgrammatic = false
        snapshotFlow {
            listState.isScrollInProgress to programmaticScroll.active
        }.distinctUntilChanged().collect { (inProgress, programmatic) ->
            if (programmatic) sawProgrammatic = true
            if (inProgress) return@collect
            if (sawProgrammatic) {
                sawProgrammatic = false
                return@collect
            }
            stickToBottom = listState.isAtLiveEdge()
        }
    }
    // Wheel settle: only re-arm follow at the live edge. Clearing happens on scroll-away
    // (dy < 0) so chasing the stream downward does not keep clearing stick every tick.
    LaunchedEffect(taskId, listState, scrollInitialized, wheelScrollTicks) {
        if (!scrollInitialized) return@LaunchedEffect
        wheelScrollTicks.collectLatest {
            withFrameMillis { }
            withFrameMillis { }
            if (listState.isAtLiveEdge()) {
                stickToBottom = true
            }
        }
    }

    // While following, re-pin on every content revision. Bring the end sentinel into view,
    // then drain any remaining forward scroll after layout measures the new text.
    LaunchedEffect(stickToBottom, followEpoch, scrollInitialized, taskId) {
        if (!stickToBottom || !scrollInitialized) return@LaunchedEffect
        repeat(3) {
            listState.runProgrammaticScroll(programmaticScroll) {
                liveEdgeRequester.bringIntoView()
                scrollToLiveEdge()
            }
            withFrameMillis { }
            if (!stickToBottom) return@LaunchedEffect
            if (!listState.canScrollForward && listState.isAtLiveEdge()) return@LaunchedEffect
        }
    }

    // Pixel-chase: when the visible live edge grows while sticking, scroll by that delta.
    // Handles the frame where stream text remeasures before canScrollForward flips.
    LaunchedEffect(taskId, listState, scrollInitialized) {
        if (!scrollInitialized) return@LaunchedEffect
        var prevMaxEnd = 0
        var armed = false
        snapshotFlow {
            val maxEnd = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.offset + it.size } ?: 0
            stickToBottom to maxEnd
        }.distinctUntilChanged().collect { (sticking, maxEnd) ->
            if (!sticking) {
                armed = false
                return@collect
            }
            if (!armed) {
                prevMaxEnd = maxEnd
                armed = true
                return@collect
            }
            val delta = maxEnd - prevMaxEnd
            prevMaxEnd = maxEnd
            if (delta <= 0) return@collect
            if (programmaticScroll.active) return@collect
            listState.runProgrammaticScroll(programmaticScroll) {
                scrollBy(delta.toFloat())
                if (canScrollForward) scrollToLiveEdge()
            }
            prevMaxEnd = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.offset + it.size } ?: 0
        }
    }

    fun jumpToLatest() {
        stickToBottom = true
        scope.launch {
            listState.runProgrammaticScroll(programmaticScroll) {
                liveEdgeRequester.bringIntoView()
                scrollToLiveEdge()
            }
        }
    }

    LaunchedEffect(scrollToLatestRequest, scrollInitialized) {
        if (scrollToLatestRequest == 0 || !scrollInitialized) return@LaunchedEffect
        stickToBottom = true
        listState.runProgrammaticScroll(programmaticScroll) {
            liveEdgeRequester.bringIntoView()
            scrollToLiveEdge()
        }
    }

    Box(modifier.fillMaxSize()) {
        if (events.isEmpty() && !originalPromptVisible && !isActive) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("waiting for agent output")
            }
        } else {
            LazyColumn(
                state = listState,
                reverseLayout = false,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = AndyLayout.ChatContentMaxWidth)
                    .fillMaxWidth()
                    .fillMaxSize()
                    .testTag("transcript-list")
                    .graphicsLayer { alpha = if (scrollInitialized) 1f else 0f }
                    .padding(end = 8.dp)
                    .pointerInput(taskId) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                if (event.type == PointerEventType.Scroll) {
                                    // Only detach when scrolling away from the live edge.
                                    // Detaching on scroll-toward-bottom made "scroll down to
                                    // catch up" lose follow on every tick while tokens arrived.
                                    val dy = event.changes.sumOf { it.scrollDelta.y.toDouble() }
                                    if (dy < 0.0) {
                                        stickToBottom = false
                                    }
                                    wheelScrollTicks.tryEmit(Unit)
                                }
                            }
                        }
                    },
                contentPadding = PaddingValues(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Chronological: oldest at top, live edge (thinking / pending) at the bottom.
                if (headerContent != null) {
                    item(key = "task-header", contentType = "header") { headerContent() }
                }
                if (originalPromptVisible) {
                    item(key = "original-prompt", contentType = "message") {
                        SelectionContainer {
                            val originalTimestamp = originalPromptAtMillis
                                ?.takeIf { it > 0L }
                                ?.let(::formatDisplayTime)
                            val originalCopyText = originalPrompt?.takeIf { it.isNotBlank() }
                            ChatMessageBubble(
                                sender = ChatBubbleSender.User,
                                testTag = "user-message-bubble",
                                metadata = if (originalTimestamp != null || originalCopyText != null) {
                                    {
                                        ChatMessageMetadata(
                                            timestamp = originalTimestamp,
                                            footer = originalCopyText?.let { prompt ->
                                                { ChatMessageCopyAction(prompt) }
                                            },
                                        )
                                    }
                                } else {
                                    null
                                },
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    originalPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
                                        if (!isSkillOnlyMessage(prompt, originalSkills)) {
                                            ChatUserText(prompt)
                                        }
                                    }
                                    ChatAttachedImages(originalImagePaths)
                                    if (originalSkills.isNotEmpty()) {
                                        DisableSelection {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                originalSkills.forEach { skill ->
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
                    }
                }
                items(
                    count = displayItems.size,
                    key = { index -> transcriptDisplayItemKey(displayItems[index]) },
                    contentType = { index ->
                        when (displayItems[index]) {
                            is TranscriptDisplayItem.Event -> "event"
                            is TranscriptDisplayItem.ToolCalls -> "tool-group"
                            is TranscriptDisplayItem.ChildSpawns -> "child-spawns"
                        }
                    },
                ) { itemIndex ->
                    val item = displayItems[itemIndex]
                    val itemKey = transcriptDisplayItemKey(item)
                    val isUserMessage = item is TranscriptDisplayItem.Event &&
                        item.event is AgentEvent.UserMessage &&
                        !(item.event as AgentEvent.UserMessage).text.isSilentConnectionRecoveryPrompt()
                    SelectionContainer(
                        modifier = Modifier
                            .then(
                                if (isUserMessage) {
                                    Modifier.userMessageSendEnter(
                                        play = itemKey == sendEntranceKey,
                                        onFinished = {
                                            if (sendEntranceKey == itemKey) sendEntranceKey = null
                                        },
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .testTag("transcript-row-$itemKey"),
                    ) {
                        when (item) {
                            is TranscriptDisplayItem.Event -> {
                                val bubbleGroup = transcriptChatBubbleGroup(displayItems, itemIndex)
                                TranscriptEvent(
                                event = item.event,
                                eventKey = transcriptEventKey(item.index, item.event),
                                bubbleGroup = bubbleGroup,
                                streamPlainText = isActive &&
                                    item.event is AgentEvent.AssistantText &&
                                    (item.event as AgentEvent.AssistantText).isStreamDelta,
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
                                onFileChangesReview = onFileChangesReview,
                                onFileChangesUndo = onFileChangesUndo,
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
                            is TranscriptDisplayItem.ChildSpawns -> LinkedChildChatsBlock(
                                children = item.tasks,
                                knownTasks = knownTasks,
                                currentTaskId = currentTaskId,
                            )
                        }
                    }
                }
                if (trailingContent != null) {
                    item(key = "trailing-content", contentType = "trailing") { trailingContent() }
                }
                if (showThinkingIndicator) {
                    item(key = "agent-thinking", contentType = "presence") { AgentThinkingIndicator() }
                }
                if (pendingContent != null) {
                    item(key = "pending-task-input", contentType = "request") { pendingContent() }
                }
                // Zero-height isn't reliable for bring-into-view; 1dp sentinel marks the true end.
                item(key = "live-edge-anchor", contentType = "anchor") {
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .bringIntoViewRequester(liveEdgeRequester),
                    )
                }
            }
            PlatformLazyListScrollbar(
                listState = listState,
                reverseLayout = false,
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
 * One-shot entrance for the user bubble created by pressing send.
 * Springs in from the trailing edge with a little scale pop — playful, but still
 * lands on the real row bounds (no overlay flight).
 */
@Composable
private fun Modifier.userMessageSendEnter(
    play: Boolean,
    onFinished: () -> Unit,
): Modifier {
    val progress = remember { Animatable(1f) }
    val slidePx = with(LocalDensity.current) { 72.dp.toPx() }
    LaunchedEffect(play) {
        if (!play) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
        onFinished()
    }
    if (!play && progress.value >= 1f) return this
    return graphicsLayer {
        val t = progress.value
        // Fade finishes early so the spring overshoot reads as motion, not opacity flicker.
        alpha = (t * 1.35f).coerceIn(0f, 1f)
        translationX = (1f - t) * slidePx
        val scale = 0.84f + (0.16f * t)
        scaleX = scale
        scaleY = scale
        // Grow from the trailing edge — matches send coming out of the composer.
        transformOrigin = TransformOrigin(1f, 0.5f)
    }
}

/**
 * ACP records the user turn in the transcript, while terminal tasks still need the task prompt
 * fallback. Do not render both representations when a recorded user turn is already present.
 */
fun shouldDisplayOriginalPrompt(
    events: List<AgentEvent>,
    originalPrompt: String?,
    originalImagePaths: List<String>,
    originalSkills: List<AgentSkill> = emptyList(),
): Boolean {
    val prompt = originalPrompt?.trim().orEmpty()
    val recordedInTranscript = events.filterIsInstance<AgentEvent.UserMessage>().any { event ->
        userMessageMatchesOriginalPrompt(event, prompt, originalImagePaths, originalSkills)
    }
    return !recordedInTranscript && (prompt.isNotBlank() || originalImagePaths.isNotEmpty() || originalSkills.isNotEmpty())
}

/** Launch turns are stored for the CLI with image hints while [originalPrompt] stays user-facing. */
internal fun userMessageMatchesOriginalPrompt(
    event: AgentEvent.UserMessage,
    prompt: String,
    imagePaths: List<String>,
    skills: List<AgentSkill> = emptyList(),
): Boolean {
    val text = event.text.trim()
    val resolvedSkills = skills.ifEmpty { event.skills }
    return when {
        prompt.isNotBlank() && text == prompt -> true
        prompt.isNotBlank() && text == promptWithImageHints(prompt, imagePaths) -> true
        prompt.isNotBlank() && resolvedSkills.isNotEmpty() && text == promptWithSkillHints(prompt, resolvedSkills) -> true
        prompt.isNotBlank() && resolvedSkills.isNotEmpty() &&
            stripCliSkillHints(text) == prompt && event.skills.map { it.path } == resolvedSkills.map { it.path } -> true
        prompt.isBlank() && imagePaths.isNotEmpty() && event.imagePaths == imagePaths -> true
        else -> false
    }
}

/** User-facing transcript text; strips CLI-only skill hints and redundant slash-only prose. */
internal fun userMessageDisplayText(event: AgentEvent.UserMessage): String {
    val stripped = stripCliSkillHints(event.text).trim()
    return if (isSkillOnlyMessage(stripped, event.skills)) "" else stripped
}

/** True when the transcript viewport is pinned to the live edge (end of the forward list). */
fun transcriptIsAtBottom(listState: LazyListState, thresholdPx: Int = 4): Boolean =
    listState.isAtLiveEdge(thresholdPx)

/**
 * Legacy reverse-layout helper kept for call sites that only have index/offset.
 * Prefer [transcriptIsAtBottom] / [LazyListState.isAtLiveEdge] with a live [LazyListState].
 */
fun transcriptIsAtBottom(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int): Boolean =
    firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset <= 1

/** Marks scrollToItem/scrollBy calls that must not be treated as user-driven follow re-arm. */
private class ProgrammaticScrollFlag {
    var active by mutableStateOf(false)
}

/**
 * Run a list scroll while [flag] is armed, and keep it armed until [LazyListState.isScrollInProgress]
 * clears. Clearing earlier lets the user-scroll effect treat the jump as a detach.
 */
private suspend fun LazyListState.runProgrammaticScroll(
    flag: ProgrammaticScrollFlag,
    block: suspend LazyListState.() -> Unit,
) {
    flag.active = true
    try {
        block()
        if (isScrollInProgress) {
            snapshotFlow { isScrollInProgress }.first { !it }
        }
    } finally {
        flag.active = false
    }
}

/** Forward-list live edge: nothing left to scroll toward newer content. */
internal fun LazyListState.isAtLiveEdge(thresholdPx: Int = 4): Boolean {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return true
    if (!canScrollForward) return true
    // Near-end tolerance: last row is on screen and nearly flush with the viewport end.
    val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return false
    if (lastVisible.index < info.totalItemsCount - 1) return false
    val itemEnd = lastVisible.offset + lastVisible.size
    return itemEnd <= info.viewportEndOffset + thresholdPx
}

/**
 * Bring the end of the list flush with the viewport bottom.
 *
 * Never use [LazyListState.scrollToItem] on the last index (or last-1): with a short
 * trailing "Working" row that parks the live edge at the TOP of the viewport, and every
 * follow nudge while thinking is visible jumps the streaming message back to its top —
 * racing new tokens so the user is never at the bottom.
 */
internal suspend fun LazyListState.scrollToLiveEdge() {
    if (layoutInfo.totalItemsCount <= 0) return
    var guard = 0
    while (guard++ < 128) {
        val info = layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        val overflow = if (last != null) {
            (last.offset + last.size) - info.viewportEndOffset
        } else {
            0
        }
        when {
            canScrollForward -> {
                val beforeIndex = firstVisibleItemIndex
                val beforeOffset = firstVisibleItemScrollOffset
                val consumed = scrollBy(50_000f)
                if (consumed == 0f &&
                    beforeIndex == firstVisibleItemIndex &&
                    beforeOffset == firstVisibleItemScrollOffset
                ) {
                    break
                }
            }
            overflow > 1 -> {
                val consumed = scrollBy(overflow.toFloat())
                if (consumed == 0f) break
            }
            last != null && last.index < info.totalItemsCount - 1 -> {
                // Last composed row isn't the list end — jump forward and keep draining.
                scrollToItem((last.index + 1).coerceAtMost(info.totalItemsCount - 1), 0)
            }
            else -> break
        }
    }
}

private fun LazyListState.firstVisibleAnchorKey(): String? = layoutInfo.visibleItemsInfo
    .firstOrNull { it.index == firstVisibleItemIndex }
    ?.key
    ?.toString()

/** Whether this event renders as a user/assistant chat bubble in the transcript. */
fun AgentEvent.chatBubbleSenderOrNull(): ChatBubbleSender? = when (this) {
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

/** Groups consecutive bubbles from the same sender while preserving transcript chronology. */
fun transcriptChatBubbleGroup(
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
    /**
     * True only for the in-progress live assistant bubble. ACP often leaves
     * [AgentEvent.AssistantText.isStreamDelta] set after the turn ends; finished rows must still
     * render through [ChatMarkdown].
     */
    streamPlainText: Boolean = false,
    awaitingPlanConfirmation: Boolean = false,
    activePermissionRequestId: String? = null,
    onToolExpandedChange: (String, Boolean) -> Unit,
    onThinkingExpandedChange: (String, Boolean) -> Unit,
    onSkillOpen: (AgentSkill) -> Unit,
    onToolFileOpen: (ToolCallFileContent) -> Unit,
    onFileChangesReview: (AgentEvent.FileChanges) -> Unit,
    onFileChangesUndo: (AgentEvent.FileChanges) -> Unit,
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
            AgentResponse(
                group = bubbleGroup,
                copyText = visibleText,
                atMillis = event.atMillis,
            ) {
                // While tokens are still arriving, render plain text. Full GFM reparse on every
                // delta thrash-measures the row and makes detached scroll compensation flicker.
                if (streamPlainText) {
                    Text(
                        text = visibleText,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        fontFamily = DisplayFont,
                    )
                } else {
                    ChatMarkdown(visibleText, lineHeight = 21.sp)
                }
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
            val displayText = userMessageDisplayText(event)
            val copyText = displayText.takeIf { it.isNotBlank() }
                ?: event.skills.takeIf { it.isNotEmpty() }?.joinToString(" ") { "/${it.name}" }
            val timestamp = event.atMillis.takeIf { it > 0L }?.let(::formatDisplayTime)
            ChatMessageBubble(
                sender = ChatBubbleSender.User,
                group = bubbleGroup,
                testTag = "user-message-bubble",
                metadata = if (timestamp != null || copyText != null) {
                    {
                        ChatMessageMetadata(
                            timestamp = timestamp,
                            footer = copyText?.let { text -> { ChatMessageCopyAction(text) } },
                        )
                    }
                } else {
                    null
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (displayText.isNotBlank()) {
                        ChatUserText(displayText)
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
                color = TextSecondary,
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
        is AgentEvent.FileChanges -> {
            var showAllFiles by remember(event.batchId) { mutableStateOf(false) }
            EditedFilesCard(
                snapshot = event.snapshot,
                showAllFiles = showAllFiles,
                onShowAllFilesChange = { showAllFiles = it },
                onUndo = { onFileChangesUndo(event) },
                onReview = { onFileChangesReview(event) },
                canUndo = event.baselineTree.isNotBlank(),
            )
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
        // Provider session titles rename the chat (when enabled); not transcript content.
        is AgentEvent.SessionInfo -> Unit
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

// Chat prose is flush left; tool/thinking asides indent past it.
private val TranscriptAsideIndent = AndySpace.Space4
private val TranscriptAsideContentIndent = AndySpace.Space4 + 8.dp

/** Leading action verb weight for activity headlines ("Edited", "Thought", "Ran", …). */
private val ActivityActionWeight = FontWeight.SemiBold

/**
 * Bolds the first whitespace-delimited token when it looks like an action verb
 * (letters only — skips shell/code dumps that start with punctuation).
 */
private fun activityHeadlineAnnotated(text: String, color: Color): AnnotatedString {
    val trimmed = text.trimStart()
    val leading = text.length - trimmed.length
    val firstBreak = trimmed.indexOfFirst { it.isWhitespace() }
    val verbEnd = if (firstBreak < 0) trimmed.length else firstBreak
    val verb = trimmed.take(verbEnd)
    val boldVerb = verb.isNotEmpty() && verb.all { it.isLetter() }
    return buildAnnotatedString {
        if (leading > 0) append(text.take(leading))
        if (boldVerb) {
            withStyle(SpanStyle(color = color, fontWeight = ActivityActionWeight)) { append(verb) }
            if (verbEnd < trimmed.length) {
                withStyle(SpanStyle(color = color, fontWeight = FontWeight.Normal)) {
                    append(trimmed.substring(verbEnd))
                }
            }
        } else {
            withStyle(SpanStyle(color = color, fontWeight = FontWeight.Normal)) { append(trimmed) }
        }
    }
}

@Composable
private fun TranscriptActivityLine(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .then(
                if (onClick != null) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(vertical = 3.dp),
    ) {
        content()
    }
}

@Composable
private fun ThinkingStep(
    text: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    animateExpansion: Boolean = true,
) {
    val expandable = text.lineSequence().any { it.isNotBlank() }
    TranscriptExpandableRow(
        headline = "Thought",
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
    atMillis: Long? = null,
    content: @Composable () -> Unit,
) {
    val timestamp = atMillis?.takeIf { it > 0L }?.let(::formatDisplayTime)
    val copyable = copyText?.takeIf { it.isNotBlank() }
    ChatMessageBubble(
        sender = ChatBubbleSender.Assistant,
        variant = ChatBubbleVariant.Ghost,
        group = group,
        metadata = if (timestamp != null || copyable != null) {
            {
                ChatMessageMetadata(
                    timestamp = timestamp,
                    footer = copyable?.let { text -> { ChatMessageCopyAction(text) } },
                )
            }
        } else {
            null
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
            AgentResponse(
                copyText = stripDecisionCheckpointMarkup(it),
                atMillis = event.atMillis,
            ) {
                ChatMarkdown(stripDecisionCheckpointMarkup(it), lineHeight = 18.sp)
            }
        }
        if (event.success) completedContent?.invoke()
    }
}

@Composable
fun ChatAttachedImages(
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
                .border(1.dp, PaneDividerTint, RoundedCornerShape(AndyRadius.Control))
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
                LucideIcon(
                    Lucide.X,
                    TextPrimary,
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(AndyRadius.Control))
                        .background(AndyColors.Neutral900.copy(alpha = AndyOverlay.Strong))
                        .border(1.dp, PaneDividerTint, RoundedCornerShape(AndyRadius.Control))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClick = onRemove)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .size(14.dp),
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
                    .border(1.dp, PaneDividerTint, RoundedCornerShape(AndyRadius.Control))
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
                .border(1.dp, PaneDividerTint, RoundedCornerShape(AndyRadius.Control))
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
                            color = TextSecondary,
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

/**
 * Always-visible clickable rows for child chats linked by parentChatTaskId.
 * Used when Cursor (and similar) collapse MCP tool calls to opaque "MCP: tool" events
 * so [AgentSpawnPresentation.isAgentSpawn] never fires for chat.start.
 */
@Composable
private fun LinkedChildChatsBlock(
    children: List<AgentTask>,
    knownTasks: List<AgentTask>,
    currentTaskId: String?,
) {
    if (children.isEmpty()) return
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        children.forEach { child ->
            SpawnedAgentLine(
                spawn = AgentSpawnPresentation.fromTask(child),
                knownTasks = knownTasks,
                currentTaskId = currentTaskId,
            )
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
        Row(Modifier.fillMaxWidth().padding(start = indent)) {
            TranscriptActivityLine(modifier = Modifier.weight(1f, fill = false)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Created ",
                        color = muted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        maxLines = 1,
                        fontWeight = ActivityActionWeight,
                    )
                    Text(
                        displayName,
                        color = nameColor,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
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
                            lineHeight = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Stable pastel accents so parallel spawn rows stay distinguishable, Cursor-style. */
fun agentSpawnNameColor(name: String): Color {
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
        TranscriptActivityLine(modifier = Modifier.padding(start = indent)) {
            ToolPathText(
                text = headline.ifBlank { marker },
                fileContent = openableContent,
                onOpen = onToolFileOpen,
                color = color.copy(alpha = 0.92f),
                maxLines = 1,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                boldFirstWord = true,
            )
        }
        return
    }

    TranscriptExpandableRow(
        headline = headline,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        animateExpansion = animateExpansion,
        headlineColor = color.copy(alpha = 0.92f),
        indent = indent,
        headlineContent = openableContent?.let { content ->
            {
                ToolPathText(
                    text = headline,
                    fileContent = content,
                    onOpen = onToolFileOpen,
                    color = color.copy(alpha = 0.92f),
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    boldFirstWord = true,
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
fun toolDetailMarkdown(body: String, path: String? = null): String {
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
    val dedented = AcpToolCallPresentation.dedentCommonIndent(body.trimEnd())
    val longestRun = Regex("`+").findAll(dedented).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxOf(3, longestRun + 1))
    return "$fence$language\n$dedented\n$fence"
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
    boldFirstWord: Boolean = false,
) {
    val path = fileContent?.path?.takeIf { it.isNotBlank() && it in text }
    if (path == null) {
        Text(
            text = if (boldFirstWord) activityHeadlineAnnotated(text, color) else
                buildAnnotatedString { withStyle(SpanStyle(color = color)) { append(text) } },
            fontFamily = DisplayFont,
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
                val prefix = text.substring(0, prefixEnd)
                Text(
                    text = if (boldFirstWord) activityHeadlineAnnotated(prefix, color) else
                        buildAnnotatedString { withStyle(SpanStyle(color = color)) { append(prefix) } },
                    fontFamily = DisplayFont,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                path,
                color = TextPrimary.copy(alpha = 0.92f),
                fontFamily = DisplayFont,
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
                    fontFamily = DisplayFont,
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
    // Animate only the expand/collapse transition. animateContentSize on the whole column would
    // also tween every streamed token while the row is open, which fights LazyColumn anchors.
    Column(modifier.fillMaxWidth()) {
        DisableSelection {
            Row(Modifier.fillMaxWidth().padding(start = indent)) {
                TranscriptActivityLine(
                    modifier = Modifier.weight(1f, fill = false),
                    onClick = if (expandable) {
                        { onExpandedChange(!expanded) }
                    } else {
                        null
                    },
                ) {
                    if (headlineContent != null) {
                        headlineContent()
                    } else {
                        Text(
                            text = activityHeadlineAnnotated(headline, headlineColor),
                            fontFamily = DisplayFont,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            maxLines = if (expanded) Int.MAX_VALUE else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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


/**
 * Lazy identity for a transcript row. Must stay stable while streamed text / tool
 * groups grow in place — putting size, text, or summary hashes here remounts the
 * row every token and reads as flicker.
 */
fun transcriptDisplayItemKey(item: TranscriptDisplayItem): String = when (item) {
    is TranscriptDisplayItem.Event -> transcriptEventKey(item.index, item.event)
    is TranscriptDisplayItem.ToolCalls -> {
        val first = item.events.firstOrNull()
        "tool-group-${item.startIndex}-${first?.atMillis ?: 0}"
    }
    is TranscriptDisplayItem.ChildSpawns ->
        "child-spawns-${item.tasks.joinToString("-") { it.id }}"
}

fun transcriptEventKey(index: Int, event: AgentEvent): String = when (event) {
    is AgentEvent.ToolCall -> "tool-call-$index-${event.atMillis}-${event.toolName}"
    is AgentEvent.ToolResult -> "tool-result-$index-${event.atMillis}-${event.toolName}-${event.isError}"
    is AgentEvent.FileChanges -> {
        val batchKey = event.groupedBatchIds.takeIf { it.isNotEmpty() }?.joinToString("+") ?: event.batchId
        "file-changes-$index-$batchKey"
    }
    else -> "${event::class.simpleName}-$index-${event.atMillis}"
}

@Composable
fun ConnectionStallBanner(
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
 * Quiet status for the normal, bounded recovery path. The red retry card remains reserved for
 * an unexpected stall that Andy could not safely schedule.
 */
@Composable
fun ConnectionRecoveryStatus(
    recovery: AgentConnectionRecovery,
    onResumeNow: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val limit = recovery.reason.maxAutomaticAttempts()
    val label = when {
        recovery.paused -> "Connection needs attention"
        recovery.reason == AgentConnectionRecoveryReason.ResourceExhausted -> "Waiting for provider capacity"
        else -> "Reconnecting automatically"
    }
    val detail = when {
        recovery.paused -> "Andy paused after $limit attempts without new progress."
        else -> "Retry ${recovery.attemptsWithoutProgress} of $limit"
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("•", color = Cyan, fontSize = 13.sp)
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Text(detail, color = TextSecondary.copy(alpha = 0.72f), fontSize = 12.sp)
        if (onResumeNow != null) {
            Text(
                "Resume",
                color = Cyan,
                fontSize = 12.sp,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onResumeNow)
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
fun PlanApprovalCard(
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
        Box(Modifier.fillMaxWidth().height(1.dp).background(PaneDividerTint))
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
