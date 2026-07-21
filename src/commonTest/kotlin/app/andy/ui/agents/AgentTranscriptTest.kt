package app.andy.ui.agents

import app.andy.model.AgentEvent
import app.andy.model.coalesceAgentStreamDeltas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentTranscriptTest {
    @Test
    fun completionOwnsDuplicateFinalAssistantText() {
        val events = listOf(
            AgentEvent.AssistantText(atMillis = 1, text = "All set."),
            AgentEvent.TaskResult(atMillis = 2, success = true, finalText = "All set."),
        )

        assertEquals(listOf(events.last()), transcriptDisplayEvents(events))
    }

    @Test
    fun distinctAssistantTextRemainsVisibleBeforeCompletion() {
        val events = listOf(
            AgentEvent.AssistantText(atMillis = 1, text = "I checked the files."),
            AgentEvent.TaskResult(atMillis = 2, success = true, finalText = "All set."),
        )

        assertEquals(events, transcriptDisplayEvents(events))
    }

    @Test
    fun compactToolCallsGroupsConsecutiveToolEvents() {
        val events = listOf(
            AgentEvent.UserMessage(atMillis = 1, text = "Find it"),
            AgentEvent.ToolCall(atMillis = 2, toolName = "Grep", summary = "AgentTranscript"),
            AgentEvent.ToolCall(atMillis = 3, toolName = "Todo", summary = "update"),
            AgentEvent.ToolResult(atMillis = 4, toolName = "Grep", summary = "matched", isError = false),
            AgentEvent.AssistantText(atMillis = 5, text = "Done."),
        )

        val items = transcriptDisplayItems(events, compactToolCalls = true)

        assertEquals(3, items.size)
        assertIs<TranscriptDisplayItem.Event>(items[0])
        val group = assertIs<TranscriptDisplayItem.ToolCalls>(items[1])
        assertEquals(3, group.events.size)
        assertEquals(1, group.startIndex)
        assertIs<TranscriptDisplayItem.Event>(items[2])
    }

    @Test
    fun compactToolCallsLeavesSingleToolAsEvent() {
        val events = listOf(
            AgentEvent.ToolCall(atMillis = 1, toolName = "Read", summary = "file.kt"),
            AgentEvent.AssistantText(atMillis = 2, text = "Looks good."),
        )

        val items = transcriptDisplayItems(events, compactToolCalls = true)

        assertEquals(2, items.size)
        assertIs<TranscriptDisplayItem.Event>(items[0])
        assertTrue(items[0] is TranscriptDisplayItem.Event && (items[0] as TranscriptDisplayItem.Event).event is AgentEvent.ToolCall)
    }

    @Test
    fun disabledCompactKeepsEachToolAsItsOwnRow() {
        val events = listOf(
            AgentEvent.ToolCall(atMillis = 1, toolName = "Grep", summary = "a"),
            AgentEvent.ToolCall(atMillis = 2, toolName = "Todo", summary = "b"),
        )

        val items = transcriptDisplayItems(events, compactToolCalls = false)

        assertEquals(2, items.size)
        assertTrue(items.all { it is TranscriptDisplayItem.Event })
    }

    @Test
    fun bottomItemIndexAccountsForOptionalTranscriptRows() {
        assertEquals(
            4,
            transcriptBottomItemIndex(
                displayItemCount = 2,
                hasHeader = true,
                hasPending = false,
                hasOriginalPrompt = true,
                isActive = true,
            ),
        )
        assertEquals(
            1,
            transcriptBottomItemIndex(
                displayItemCount = 2,
                hasHeader = false,
                hasPending = false,
                hasOriginalPrompt = false,
                isActive = false,
            ),
        )
    }

    @Test
    fun scrollAnchorTracksStreamingAssistantGrowth() {
        val shortItems = transcriptDisplayItems(
            listOf(AgentEvent.AssistantText(atMillis = 1, text = "Hel")),
            compactToolCalls = true,
        )
        val longItems = transcriptDisplayItems(
            listOf(AgentEvent.AssistantText(atMillis = 1, text = "Hello world")),
            compactToolCalls = true,
        )

        val shortAnchor = transcriptScrollAnchor(shortItems, hasHeader = false, hasPending = false, hasOriginalPrompt = false, isActive = true)
        val longAnchor = transcriptScrollAnchor(longItems, hasHeader = false, hasPending = false, hasOriginalPrompt = false, isActive = true)

        assertTrue(shortAnchor != longAnchor)
    }

    @Test
    fun scrollAnchorTracksCompletedContentMount() {
        val items = transcriptDisplayItems(
            listOf(AgentEvent.TaskResult(atMillis = 1, success = true, finalText = "Done.")),
            compactToolCalls = true,
        )
        val before = transcriptScrollAnchor(
            items,
            hasHeader = false,
            hasPending = false,
            hasOriginalPrompt = false,
            isActive = false,
            completedContentKey = null,
        )
        val after = transcriptScrollAnchor(
            items,
            hasHeader = false,
            hasPending = false,
            hasOriginalPrompt = false,
            isActive = false,
            completedContentKey = 3,
        )
        assertTrue(before != after)
    }

    @Test
    fun streamDeltaKeysStayStableWhileTextGrows() {
        val short = AgentEvent.AssistantText(atMillis = 10, text = "Hel", isStreamDelta = true)
        val long = short.copy(text = "Hello world")
        val shortKey = transcriptEventKey(0, short)
        val longKey = transcriptEventKey(0, long)
        assertEquals(shortKey, longKey)
    }

    @Test
    fun toolGroupKeyStaysStableAsToolsAccumulate() {
        val first = listOf(
            AgentEvent.ToolCall(atMillis = 2, toolName = "Grep", summary = "a"),
            AgentEvent.ToolResult(atMillis = 3, toolName = "Grep", summary = "ok", isError = false),
        )
        val grown = first + AgentEvent.ToolCall(atMillis = 4, toolName = "Read", summary = "b")
        assertEquals(
            transcriptDisplayItemKey(TranscriptDisplayItem.ToolCalls(1, first)),
            transcriptDisplayItemKey(TranscriptDisplayItem.ToolCalls(1, grown)),
        )
    }

    @Test
    fun coalesceKeepsStreamStartTimestamp() {
        val merged = coalesceAgentStreamDeltas(
            existing = listOf(AgentEvent.AssistantText(atMillis = 10, text = "Hel", isStreamDelta = true)),
            incoming = listOf(AgentEvent.AssistantText(atMillis = 11, text = "lo", isStreamDelta = true)),
        )
        val text = assertIs<AgentEvent.AssistantText>(merged.single())
        assertEquals(10, text.atMillis)
        assertEquals("Hello", text.text)
    }

    @Test
    fun nearBottomRequiresLastItemBottomAlignedNotJustVisible() {
        // Last item parked at the top of the viewport with empty space below — not pinned.
        assertTrue(
            !transcriptIsNearBottom(
                transcriptBottomGapPx(
                    lastItemIndex = 4,
                    lastItemOffset = 0,
                    lastItemSize = 40,
                    totalItems = 5,
                    viewportEndOffset = 800,
                    canScrollForward = false,
                    canScrollBackward = true,
                ),
            ),
        )
        // Tall last item scrolled so its bottom meets the viewport bottom.
        assertTrue(
            transcriptIsNearBottom(
                transcriptBottomGapPx(
                    lastItemIndex = 4,
                    lastItemOffset = -1200,
                    lastItemSize = 2000,
                    totalItems = 5,
                    viewportEndOffset = 800,
                    canScrollForward = false,
                    canScrollBackward = true,
                ),
            ),
        )
        // Short transcript that fits entirely.
        assertTrue(
            transcriptIsNearBottom(
                transcriptBottomGapPx(
                    lastItemIndex = 1,
                    lastItemOffset = 100,
                    lastItemSize = 40,
                    totalItems = 2,
                    viewportEndOffset = 800,
                    canScrollForward = false,
                    canScrollBackward = false,
                ),
            ),
        )
    }
}
