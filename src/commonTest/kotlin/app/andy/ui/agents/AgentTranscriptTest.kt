package app.andy.ui.agents

import app.andy.model.AgentEvent
import app.andy.model.AgentToolKind
import app.andy.model.coalesceAcpTranscriptEvents
import app.andy.model.coalesceAgentStreamDeltas
import app.andy.model.planTextFromAcpTranscript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentTranscriptTest {
    @Test
    fun storedPromptIsHiddenWhenTranscriptAlreadyContainsUserTurn() {
        assertFalse(
            shouldDisplayOriginalPrompt(
                events = listOf(AgentEvent.UserMessage(atMillis = 1, text = "hello")),
                originalPrompt = "hello",
                originalImagePaths = emptyList(),
            ),
        )
    }

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
    fun adjacentStreamChunksRenderAsOneAssistantMessage() {
        val first = AgentEvent.AssistantText(atMillis = 1, text = "Hey! What", isStreamDelta = true)
        val second = AgentEvent.AssistantText(atMillis = 2, text = " are we working on today?", isStreamDelta = true)

        val displayed = transcriptDisplayEvents(listOf(first, second))

        assertEquals(listOf(first.copy(text = "Hey! What are we working on today?")), displayed)
    }

    @Test
    fun collapseActivityBetweenMessagesGroupsThinkingAndSingleTool() {
        val events = listOf(
            AgentEvent.UserMessage(atMillis = 1, text = "Find it"),
            AgentEvent.Thinking(atMillis = 2, text = "Need to search the repo"),
            AgentEvent.ToolCall(atMillis = 3, toolName = "Grep", summary = "AgentTranscript"),
            AgentEvent.AssistantText(atMillis = 4, text = "Done."),
        )

        val items = transcriptDisplayItems(events, collapseActivityBetweenMessages = true)

        assertEquals(3, items.size)
        assertIs<TranscriptDisplayItem.Event>(items[0])
        val group = assertIs<TranscriptDisplayItem.ToolCalls>(items[1])
        assertEquals(2, group.events.size)
        assertIs<TranscriptDisplayItem.Event>(items[2])
    }

    @Test
    fun autoExpandTreatsUnsetKeysAsExpanded() {
        assertTrue(transcriptActivityExpanded("tool-1", emptySet(), autoExpand = true))
        assertFalse(transcriptActivityExpanded("tool-1", setOf("tool-1"), autoExpand = true))
    }

    @Test
    fun manualExpandRequiresExplicitKey() {
        assertFalse(transcriptActivityExpanded("tool-1", emptySet(), autoExpand = false))
        assertTrue(transcriptActivityExpanded("tool-1", setOf("tool-1"), autoExpand = false))
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

        val items = transcriptDisplayItems(events)

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

        val items = transcriptDisplayItems(events)

        assertEquals(2, items.size)
        assertIs<TranscriptDisplayItem.Event>(items[0])
        assertTrue(items[0] is TranscriptDisplayItem.Event && (items[0] as TranscriptDisplayItem.Event).event is AgentEvent.ToolCall)
    }

    @Test
    fun reverseTranscriptBottomIsIndexZeroWithNoOffset() {
        assertTrue(transcriptIsAtBottom(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0))
        assertTrue(transcriptIsAtBottom(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 1))
        assertTrue(!transcriptIsAtBottom(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 2))
        assertTrue(!transcriptIsAtBottom(firstVisibleItemIndex = 1, firstVisibleItemScrollOffset = 0))
    }

    @Test
    fun scrollMemoryKeepsIndependentConversationPositions() {
        val memory = TranscriptScrollMemory()
        val first = TranscriptScrollPosition(index = 8, offset = 14, stickToBottom = false)
        val second = TranscriptScrollPosition(index = 0, offset = 0, stickToBottom = true)

        memory.save("first", first)
        memory.save("second", second)

        assertEquals(first, memory.get("first"))
        assertEquals(second, memory.get("second"))
        memory.remove("first")
        assertEquals(null, memory.get("first"))
        assertEquals(second, memory.get("second"))
    }

    @Test
    fun firstConversationVisitHasNoSavedPositionAndDefaultsToLiveEdge() {
        val memory = TranscriptScrollMemory()

        assertEquals(null, memory.get("new-chat"))
        assertTrue(
            transcriptIsAtBottom(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
            ),
        )
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
    fun planTextFromAcpTranscriptUsesLastAssistantMessage() {
        val events = listOf(
            AgentEvent.UserMessage(atMillis = 1, text = "plan this"),
            AgentEvent.AssistantText(atMillis = 2, text = "Looking at the repo...", isStreamDelta = false),
            AgentEvent.ToolCall(atMillis = 3, toolName = "Read", summary = "README"),
            AgentEvent.AssistantText(atMillis = 4, text = "## Plan\n\n1. First\n2. Second", isStreamDelta = true),
        )

        assertEquals("## Plan\n\n1. First\n2. Second", planTextFromAcpTranscript(events))
    }

    @Test
    fun planTextFromAcpTranscriptPrefersTaskResultFinalText() {
        val events = listOf(
            AgentEvent.AssistantText(atMillis = 1, text = "draft", isStreamDelta = false),
            AgentEvent.TaskResult(atMillis = 2, success = true, finalText = "## Final plan\n\nStep one"),
        )

        assertEquals("## Final plan\n\nStep one", planTextFromAcpTranscript(events))
    }

    @Test
    fun coalesceAcpTranscriptEventsFoldsManyStreamDeltas() {
        val deltas = (1..4_000).map { index ->
            AgentEvent.AssistantText(atMillis = index.toLong(), text = "x", isStreamDelta = true)
        } + listOf(
            AgentEvent.AssistantText(atMillis = 4_001, text = "\n\n## 1. First step", isStreamDelta = true),
            AgentEvent.AssistantText(atMillis = 4_002, text = "\n\n## 2. Second step", isStreamDelta = true),
            AgentEvent.AssistantText(atMillis = 4_003, text = "\n\n## 3. Third step", isStreamDelta = true),
        )

        val coalesced = coalesceAcpTranscriptEvents(deltas)
        val assistant = coalesced.filterIsInstance<AgentEvent.AssistantText>()

        assertEquals(1, assistant.size)
        assertTrue(assistant.single().text.contains("## 1. First step"))
        assertTrue(assistant.single().text.contains("## 3. Third step"))
        assertEquals(1, coalesced.size)
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
    fun acpWhitespaceRawChunksRecoverAndCoalesceIntoAssistantResponse() {
        val events = listOf(
            AgentEvent.AssistantText(atMillis = 1, text = "In Minneapolis today (Monday, August", isStreamDelta = true),
            AgentEvent.Raw(atMillis = 2, line = "Text(text= , annotations=null, _meta=null)"),
            AgentEvent.AssistantText(atMillis = 3, text = "3,", isStreamDelta = true),
            AgentEvent.Raw(atMillis = 4, line = "Text(text= , annotations=null, _meta=null)"),
            AgentEvent.AssistantText(atMillis = 5, text = "2026)", isStreamDelta = true),
            AgentEvent.Raw(atMillis = 6, line = "Text(text=\\n\\n, annotations=null, _meta=null)"),
            AgentEvent.AssistantText(atMillis = 7, text = "- highs possibly in the", isStreamDelta = true),
            AgentEvent.Raw(atMillis = 8, line = "Text(text= , annotations=null, _meta=null)"),
            AgentEvent.AssistantText(atMillis = 9, text = "90s", isStreamDelta = true),
            AgentEvent.Raw(atMillis = 10, line = "Text(text=\\n\\n, annotations=null, _meta=null)"),
            AgentEvent.AssistantText(atMillis = 11, text = "Stay hydrated.", isStreamDelta = true),
        )

        val displayed = transcriptDisplayEvents(events)
        val assistant = displayed.filterIsInstance<AgentEvent.AssistantText>()

        assertEquals(1, assistant.size)
        assertEquals(
            "In Minneapolis today (Monday, August 3, 2026)\n\n- highs possibly in the 90s\n\nStay hydrated.",
            assistant.single().text,
        )
    }

    @Test
    fun streamCoalescingStillBreaksAcrossToolCalls() {
        val events = listOf(
            AgentEvent.AssistantText(atMillis = 1, text = "first", isStreamDelta = true),
            AgentEvent.ToolCall(atMillis = 2, toolName = "search", summary = "weather"),
            AgentEvent.AssistantText(atMillis = 3, text = "second", isStreamDelta = true),
        )

        val displayed = transcriptDisplayEvents(events).filterIsInstance<AgentEvent.AssistantText>()

        assertEquals(listOf("first", "second"), displayed.map { it.text })
    }

    @Test
    fun compactToolActivityHeadlineSummarizesEmptyEditCalls() {
        val events = listOf(
            AgentEvent.ToolCall(
                atMillis = 1,
                toolName = "Edit File",
                summary = "{}",
                kind = AgentToolKind.Edit,
            ),
            AgentEvent.ToolCall(
                atMillis = 2,
                toolName = "Edit File",
                summary = "{}",
                kind = AgentToolKind.Edit,
            ),
        )

        val headline = compactToolActivityHeadline(events)

        assertEquals("edited 2 files", headline)
    }

    @Test
    fun connectionStallErrorsAreHiddenFromTranscriptDisplay() {
        val events = listOf(
            AgentEvent.ToolCall(atMillis = 1, toolName = "read", summary = "gradle"),
            AgentEvent.AssistantText(atMillis = 2, text = "Error: RetriableError: Connection stalled"),
            AgentEvent.TaskError(atMillis = 3, message = "RetriableError: Connection stalled"),
        )

        val displayed = transcriptDisplayEvents(events)
        assertEquals(1, displayed.size)
        assertIs<AgentEvent.ToolCall>(displayed.single())
    }
}
