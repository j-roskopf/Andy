package app.andy.ui.agents

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
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import app.andy.model.AgentSkill
import app.andy.ui.components.AndyMarkdownDensity
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
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

private const val TRANSCRIPT_BOTTOM_THRESHOLD_PX = 32

/** Explicit per-task scroll snapshot. */
internal data class TranscriptScrollPosition(
    val index: Int,
    val offset: Int,
    val stickToBottom: Boolean,
)

private sealed class TranscriptRestorePlan {
    data object StickToBottom : TranscriptRestorePlan()
    data class Exact(val index: Int, val offset: Int) : TranscriptRestorePlan()
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

/** Suppresses stick-to-bottom updates while we programmatically scroll. */
private class TranscriptScrollGate {
    private var depth = 0
    val isAutoScrolling: Boolean get() = depth > 0

    suspend fun <T> run(block: suspend () -> T): T {
        depth += 1
        try {
            return block()
        } finally {
            withFrameMillis { }
            depth -= 1
        }
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
    /** Changes when trailing completed UI (e.g. change summary) mounts after first layout. */
    completedContentKey: Any? = null,
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
    val displayItems = remember(events, compactToolCalls) { transcriptDisplayItems(events, compactToolCalls) }
    val originalPromptVisible = !originalPrompt.isNullOrBlank() || originalImagePaths.isNotEmpty()
    val latestTaskResultItemIndex = displayItems.indexOfLast { item ->
        item is TranscriptDisplayItem.Event && item.event is AgentEvent.TaskResult
    }
    val taskId = restoreScrollKey
    // Freeze restore intent for this visit from memory at entry — never mutate this plan mid-load.
    val restorePlan = remember(taskId) {
        val saved = taskId?.let { scrollMemory?.get(it) }
        when {
            saved == null || saved.stickToBottom -> TranscriptRestorePlan.StickToBottom
            else -> TranscriptRestorePlan.Exact(saved.index, saved.offset)
        }
    }
    val listState = remember(taskId) {
        when (val plan = restorePlan) {
            is TranscriptRestorePlan.Exact -> LazyListState(plan.index, plan.offset)
            TranscriptRestorePlan.StickToBottom -> LazyListState(0, 0)
        }
    }
    var stickToBottom by remember(taskId) {
        mutableStateOf(restorePlan is TranscriptRestorePlan.StickToBottom)
    }
    var scrollInitialized by remember(taskId) { mutableStateOf(false) }
    val scrollGate = remember(taskId) { TranscriptScrollGate() }
    var expandedToolKeys by remember(taskId) { mutableStateOf(setOf<String>()) }
    var expandedToolGroups by remember(taskId) { mutableStateOf(setOf<String>()) }
    // Bumped on real user scroll (mouse wheel / trackpad) — desktop often skips isScrollInProgress.
    var userScrollGeneration by remember(taskId) { mutableStateOf(0) }
    val bottomItemIndex = remember(
        displayItems.size,
        headerContent != null,
        pendingContent != null,
        originalPromptVisible,
        isActive,
    ) {
        transcriptBottomItemIndex(
            displayItemCount = displayItems.size,
            hasHeader = headerContent != null,
            hasPending = pendingContent != null,
            hasOriginalPrompt = originalPromptVisible,
            isActive = isActive,
        )
    }
    val scrollAnchor = remember(
        displayItems,
        headerContent != null,
        pendingContent != null,
        originalPromptVisible,
        isActive,
        completedContentKey,
    ) {
        transcriptScrollAnchor(
            displayItems = displayItems,
            hasHeader = headerContent != null,
            hasPending = pendingContent != null,
            hasOriginalPrompt = originalPromptVisible,
            isActive = isActive,
            completedContentKey = completedContentKey,
        )
    }
    val scrollInitializedState = rememberUpdatedState(scrollInitialized)
    val stickToBottomState = rememberUpdatedState(stickToBottom)
    // Observable content fingerprint so a single snapshotFlow can react to streamed text
    // growth without a keyed LaunchedEffect restarting (and cancelling a mid-scroll) per token.
    val scrollAnchorState = rememberUpdatedState(scrollAnchor)
    val isActiveState = rememberUpdatedState(isActive)

    // Only persist AFTER init — saving during the loading stub was overwriting mid-scroll
    // positions with "stick=true at index 0" when the prompt alone fit the viewport.
    LaunchedEffect(taskId, listState, scrollInitialized) {
        if (!scrollInitialized) return@LaunchedEffect
        val id = taskId ?: return@LaunchedEffect
        val memory = scrollMemory ?: return@LaunchedEffect
        snapshotFlow {
            TranscriptScrollPosition(
                index = listState.firstVisibleItemIndex,
                offset = listState.firstVisibleItemScrollOffset,
                stickToBottom = stickToBottomState.value,
            )
        }.distinctUntilChanged().collect { memory.save(id, it) }
    }

    LaunchedEffect(
        taskId,
        bottomItemIndex,
        listState.layoutInfo.totalItemsCount,
        eventsReady,
        displayItems.size,
        completedContentKey,
    ) {
        if (scrollInitialized || bottomItemIndex < 0) return@LaunchedEffect
        if (!eventsReady) return@LaunchedEffect
        val itemCount = listState.layoutInfo.totalItemsCount
        if (itemCount == 0) return@LaunchedEffect
        scrollGate.run {
            when (val plan = restorePlan) {
                TranscriptRestorePlan.StickToBottom -> {
                    stickToBottom = true
                    // Keep settling until truly pinned — change-summary can mount a frame late.
                    repeat(24) {
                        listState.scrollToEndAligned(settleFrames = 1)
                        if (listState.isNearBottom(thresholdPx = 1) && !listState.canScrollForward) {
                            withFrameMillis { }
                            listState.scrollToEndAligned(settleFrames = 1)
                            if (listState.isNearBottom(thresholdPx = 1) && !listState.canScrollForward) return@run
                        }
                        withFrameMillis { }
                    }
                }
                is TranscriptRestorePlan.Exact -> {
                    stickToBottom = false
                    listState.scrollToItem(
                        plan.index.coerceIn(0, itemCount - 1),
                        plan.offset,
                    )
                }
            }
        }
        scrollInitialized = true
    }
    // Re-pin only on stick (re)engaging or the completed chat becoming ready — NOT per token.
    LaunchedEffect(stickToBottom, scrollInitialized, eventsReady) {
        if (!scrollInitialized || !stickToBottom || !eventsReady) return@LaunchedEffect
        scrollGate.run { listState.scrollToEndAligned() }
    }
    // Non-active settle: images, change-summary, and restored history all mount/measure
    // after the fact. React to those layout changes so a finished chat pins cleanly.
    // While the agent is active, the per-frame follower below owns pinning instead.
    LaunchedEffect(listState, taskId, scrollInitialized) {
        if (!scrollInitialized) return@LaunchedEffect
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            scrollAnchorState.value to listOf(
                listState.bottomGapPx(),
                last?.index,
                last?.size,
                listState.canScrollForward,
                listState.layoutInfo.totalItemsCount,
            )
        }.distinctUntilChanged().collect {
            if (isActiveState.value) return@collect
            if (!stickToBottomState.value || scrollGate.isAutoScrolling) return@collect
            if (!listState.isNearBottom(thresholdPx = 1)) {
                scrollGate.run { listState.scrollToEndAligned() }
            }
        }
    }
    // Streaming follower: while the agent is active, re-check the bottom every frame and
    // nudge back by the exact remaining delta. Per-frame coverage means we never drift more
    // than one frame off the bottom; the exact-delta nudge (no scrollToItem jump, no second
    // competing driver) means an already-pinned list does nothing, so there is no flicker.
    LaunchedEffect(taskId, scrollInitialized, isActive) {
        if (!scrollInitialized || !isActive) return@LaunchedEffect
        while (true) {
            withFrameMillis { }
            if (!stickToBottomState.value || scrollGate.isAutoScrolling) continue
            if (!listState.isNearBottom(thresholdPx = 1)) {
                scrollGate.run { listState.scrollToEndAligned(settleFrames = 1) }
            }
        }
    }
    LaunchedEffect(userScrollGeneration, scrollInitialized) {
        if (!scrollInitialized || userScrollGeneration == 0) return@LaunchedEffect
        // Let the wheel/trackpad scroll apply, then sync stick from the settled
        // position. Detach immediately on Scroll (below) so streaming doesn't yank
        // an upward gesture; this settle is what re-locks after scrolling back down.
        // Without it, the near-bottom snapshotFlow often won't re-emit — nearBottom
        // was already true when the last wheel tick cleared stickToBottom.
        withFrameMillis { }
        withFrameMillis { }
        if (!scrollGate.isAutoScrolling) {
            stickToBottom = listState.isNearBottom()
        }
    }
    LaunchedEffect(listState, taskId, scrollInitialized) {
        if (!scrollInitialized) return@LaunchedEffect
        snapshotFlow { listState.isNearBottom() to listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { (nearBottom, inProgress) ->
                if (scrollGate.isAutoScrolling || !scrollInitializedState.value) return@collect
                if (nearBottom) stickToBottom = true
                else if (inProgress) stickToBottom = false
            }
    }
    Box(
        modifier
            .background(AndyColors.Neutral900.copy(alpha = 0.45f), RoundedCornerShape(AndyRadius.R4))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R4)),
    ) {
        if (events.isEmpty() && !originalPromptVisible && !isActive) {
            EmptyState("waiting for agent output")
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (scrollInitialized) 1f else 0f }
                    .padding(end = 8.dp)
                    .pointerInput(taskId) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                if (event.type == PointerEventType.Scroll) {
                                    // Detach the instant the user scrolls so the per-frame
                                    // follower doesn't yank an upward gesture during streaming.
                                    // userScrollGeneration's settle effect re-locks once the
                                    // wheel/trackpad stops near the bottom.
                                    stickToBottom = false
                                    userScrollGeneration++
                                }
                            }
                        }
                    },
                contentPadding = PaddingValues(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 0.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (headerContent != null) {
                    item(key = "task-header") { headerContent() }
                }
                if (pendingContent != null) {
                    item(key = "pending-task-input") { pendingContent() }
                }
                if (originalPromptVisible) {
                    item(key = "original-prompt") {
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
                itemsIndexed(
                    items = displayItems,
                    key = { _, item -> transcriptDisplayItemKey(item) },
                ) { itemIndex, item ->
                    SelectionContainer {
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
                if (isActive) {
                    item(key = "agent-thinking") { AgentThinkingIndicator() }
                }
            }
            DraggableScrollbar(
                listState = listState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

internal fun transcriptBottomItemIndex(
    displayItemCount: Int,
    hasHeader: Boolean,
    hasPending: Boolean,
    hasOriginalPrompt: Boolean,
    isActive: Boolean,
): Int {
    var index = -1
    if (hasHeader) index++
    if (hasPending) index++
    if (hasOriginalPrompt) index++
    index += displayItemCount
    if (isActive) index++
    return index
}

internal fun transcriptBottomGapPx(
    lastItemIndex: Int,
    lastItemOffset: Int,
    lastItemSize: Int,
    totalItems: Int,
    viewportEndOffset: Int,
    canScrollForward: Boolean,
    canScrollBackward: Boolean,
): Int? {
    if (totalItems <= 0) return 0
    if (!canScrollForward && !canScrollBackward) return 0
    if (lastItemIndex < totalItems - 1) return null
    return (lastItemOffset + lastItemSize) - viewportEndOffset
}

internal fun transcriptIsNearBottom(
    gapPx: Int?,
    thresholdPx: Int = TRANSCRIPT_BOTTOM_THRESHOLD_PX,
): Boolean = gapPx != null && abs(gapPx) <= thresholdPx

private fun LazyListState.isNearBottom(
    thresholdPx: Int = TRANSCRIPT_BOTTOM_THRESHOLD_PX,
): Boolean = transcriptIsNearBottom(gapPx = bottomGapPx(), thresholdPx = thresholdPx)

private fun LazyListState.bottomGapPx(): Int? {
    val layout = layoutInfo
    val lastVisible = layout.visibleItemsInfo.lastOrNull()
    return transcriptBottomGapPx(
        lastItemIndex = lastVisible?.index ?: -1,
        lastItemOffset = lastVisible?.offset ?: 0,
        lastItemSize = lastVisible?.size ?: 0,
        totalItems = layout.totalItemsCount,
        viewportEndOffset = layout.viewportEndOffset - layout.afterContentPadding,
        canScrollForward = canScrollForward,
        canScrollBackward = canScrollBackward,
    )
}

/**
 * Scroll so the bottom of the last item sits on the bottom of the viewport.
 * Prefer [scrollBy] when the last item is already visible — [scrollToItem] alone
 * parks it at the *top* and causes a visible flash during streaming.
 */
private suspend fun LazyListState.scrollToEndAligned(settleFrames: Int = 4) {
    // Markdown/images/change-summary often measure across multiple frames after content arrives.
    var stable = 0
    var previousGap: Int? = null
    repeat(settleFrames.coerceAtLeast(1)) { attempt ->
        alignLastItemToBottom()
        val gap = bottomGapPx()
        if (gap != null && abs(gap) <= 1 && !canScrollForward) {
            if (gap == previousGap) stable++ else stable = 1
            previousGap = gap
            if (stable >= 2) return
        } else {
            stable = 0
            previousGap = gap
        }
        if (attempt < settleFrames - 1) withFrameMillis { }
    }
}

private suspend fun LazyListState.alignLastItemToBottom() {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return

    var lastItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == lastIndex }
    if (lastItem == null) {
        // Jump near the end in one shot, then fine-tune with scrollBy so we never
        // paint a frame with the last item parked at the top of the viewport.
        scrollToItem(lastIndex, scrollOffset = Int.MAX_VALUE / 4)
        lastItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == lastIndex } ?: return
        val viewportEnd = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
        val jumpDelta = (lastItem.offset + lastItem.size) - viewportEnd
        if (abs(jumpDelta) > 1) {
            scroll { scrollBy(jumpDelta.toFloat()) }
        }
        return
    }

    val viewportEnd = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
    val delta = (lastItem.offset + lastItem.size) - viewportEnd
    if (abs(delta) > 1) {
        scroll { scrollBy(delta.toFloat()) }
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

/**
 * Fingerprint for transcript auto-scroll. Streamed assistant/thinking text grows in place,
 * so the anchor tracks content length rather than relying on [ScrollState.maxValue] keys.
 */
internal fun transcriptScrollAnchor(
    displayItems: List<TranscriptDisplayItem>,
    hasHeader: Boolean,
    hasPending: Boolean,
    hasOriginalPrompt: Boolean,
    isActive: Boolean,
    completedContentKey: Any? = null,
): List<Any?> {
    val lastMarker = when (val last = displayItems.lastOrNull()) {
        is TranscriptDisplayItem.Event -> when (val event = last.event) {
            is AgentEvent.AssistantText -> listOf("assistant", event.text.length, event.text.takeLast(96))
            is AgentEvent.Thinking -> listOf("thinking", event.text.length, event.text.takeLast(96))
            else -> listOf(transcriptEventKey(last.index, last.event))
        }
        is TranscriptDisplayItem.ToolCalls -> listOf(transcriptDisplayItemKey(last), last.events.size)
        null -> listOf("empty")
    }
    return listOf(
        displayItems.size,
        lastMarker,
        hasHeader,
        hasPending,
        hasOriginalPrompt,
        isActive,
        completedContentKey,
    )
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
        is AgentEvent.AssistantText -> ChatMessageBubble(
            author = agentLabel,
            authorColor = Cyan,
            alignEnd = false,
        ) {
            ChatMarkdown(event.text, lineHeight = 19.sp)
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
                    ChatMarkdown(it, lineHeight = 18.sp)
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
