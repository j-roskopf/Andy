package app.andy.ui.agents

import app.andy.model.AgentEvent
import app.andy.model.CONNECTION_STALL_RETRY_PROMPT
import app.andy.model.transcriptDisplayItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatFindTest {
    @Test
    fun findsMatchesAcrossUserAssistantAndThinking() {
        val events = listOf(
            AgentEvent.UserMessage(1L, "search for token"),
            AgentEvent.Thinking(2L, "consider Token reuse"),
            AgentEvent.AssistantText(3L, "No token here"),
        )
        val items = transcriptDisplayItems(events, keepThinkingOnTimeline = true)
        val matches = findChatMatches(items, "token")
        assertEquals(3, matches.size)
        assertTrue(matches.all { it.itemKey.isNotBlank() })
        val thinking = matches.single { it.nestedExpandKind == ChatFindExpandKind.Thinking }
        assertEquals(ChatFindExpandKind.Thinking, thinking.nestedExpandKind)
        assertEquals(setOf(thinking.nestedEventKey), chatFindExpandKeys(thinking).filter { it == thinking.nestedEventKey }.toSet())
        assertTrue(thinking.nestedEventKey in chatFindExpandKeys(thinking))
    }

    @Test
    fun includesOriginalPromptWhenVisible() {
        val matches = findChatMatches(
            displayItems = emptyList(),
            query = "hello",
            originalPrompt = "say hello world",
            originalPromptVisible = true,
        )
        assertEquals(1, matches.size)
        assertEquals("original-prompt", matches.single().itemKey)
        assertEquals(4, matches.single().start)
    }

    @Test
    fun skipsSilentRecoveryAndBlankQuery() {
        val events = listOf(
            AgentEvent.UserMessage(1L, CONNECTION_STALL_RETRY_PROMPT),
            AgentEvent.AssistantText(2L, "visible"),
        )
        val items = transcriptDisplayItems(events)
        assertTrue(findChatMatches(items, "  ").isEmpty())
        assertTrue(findChatMatches(items, "CONNECTION").isEmpty())
        assertEquals(1, findChatMatches(items, "visible").size)
    }

    @Test
    fun searchesToolTitleSummaryAndDetail() {
        val events = listOf(
            AgentEvent.ToolCall(
                atMillis = 1L,
                toolName = "Read",
                summary = "ChatFind.kt",
                detail = "line with uniqueNeedle inside",
            ),
        )
        val items = transcriptDisplayItems(events)
        val matches = findChatMatches(items, "uniqueNeedle")
        assertEquals(1, matches.size)
        assertEquals(ChatFindExpandKind.Tool, matches.single().nestedExpandKind)
        assertTrue(matches.single().nestedEventKey in chatFindExpandKeys(matches.single()))
    }

    @Test
    fun collapsedToolGroupMatchExpandsGroupAndNestedRow() {
        val events = listOf(
            AgentEvent.UserMessage(1L, "go"),
            AgentEvent.Thinking(2L, "plan with secretPhrase"),
            AgentEvent.ToolCall(3L, toolName = "Read", summary = "a.kt"),
            AgentEvent.AssistantText(4L, "done"),
        )
        val items = transcriptDisplayItems(
            events,
            collapseActivityBetweenMessages = true,
            keepThinkingOnTimeline = false,
        )
        val group = items.filterIsInstance<app.andy.model.TranscriptDisplayItem.ToolCalls>().single()
        val matches = findChatMatches(items, "secretPhrase")
        assertEquals(1, matches.size)
        val match = matches.single()
        assertEquals(transcriptDisplayItemKey(group), match.itemKey)
        assertEquals(ChatFindExpandKind.Thinking, match.nestedExpandKind)
        val expand = chatFindExpandKeys(match)
        assertTrue(match.itemKey in expand, "group should expand")
        assertTrue(match.nestedEventKey in expand, "nested thinking should expand")
    }
}
