package app.andy.ui.agents

import app.andy.model.AgentEvent
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
}
