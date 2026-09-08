package com.joetr.andy.mobile.ui

import com.joetr.andy.mobile.data.networkaccess.ChatEventDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTranscriptDisplayTest {
    @Test
    fun thinkingAndToolStaySeparateWhenCollapseDisabled() {
        val events = listOf(
            ChatEventDto(type = "user", text = "Find it", atMillis = 1),
            ChatEventDto(type = "thinking", text = "Need to search", atMillis = 2),
            ChatEventDto(type = "tool", toolName = "Grep", summary = "AgentTranscript", atMillis = 3),
            ChatEventDto(type = "assistant", text = "Done.", atMillis = 4),
        )
        val items = wireTranscriptDisplayItems(events, collapseActivityBetweenMessages = false)
        assertEquals(4, items.size)
        assertTrue(items[1] is WireDisplayItem.Event)
        assertEquals("thinking", (items[1] as WireDisplayItem.Event).event.type)
        assertTrue(items[2] is WireDisplayItem.Event)
        assertEquals("tool", (items[2] as WireDisplayItem.Event).event.type)
    }

    @Test
    fun collapseGroupsThinkingAndTool() {
        val events = listOf(
            ChatEventDto(type = "user", text = "Find it", atMillis = 1),
            ChatEventDto(type = "thinking", text = "Need to search", atMillis = 2),
            ChatEventDto(type = "tool", toolName = "Grep", summary = "AgentTranscript", atMillis = 3),
            ChatEventDto(type = "assistant", text = "Done.", atMillis = 4),
        )
        val items = wireTranscriptDisplayItems(events, collapseActivityBetweenMessages = true)
        assertEquals(3, items.size)
        val group = items[1] as WireDisplayItem.ToolGroup
        assertEquals(2, group.events.size)
    }

    @Test
    fun keepThinkingOnTimelineLeavesThoughtsOutOfGroups() {
        val events = listOf(
            ChatEventDto(type = "user", text = "Find it", atMillis = 1),
            ChatEventDto(type = "thinking", text = "Need to search", atMillis = 2),
            ChatEventDto(type = "tool", toolName = "Grep", summary = "a", atMillis = 3),
            ChatEventDto(type = "tool", toolName = "Read", summary = "b", atMillis = 4),
            ChatEventDto(type = "thinking", text = "That matches", atMillis = 5),
            ChatEventDto(type = "tool", toolName = "Edit", summary = "c", atMillis = 6),
            ChatEventDto(type = "assistant", text = "Done.", atMillis = 7),
        )
        val items = wireTranscriptDisplayItems(
            events,
            collapseActivityBetweenMessages = true,
            keepThinkingOnTimeline = true,
        )
        assertEquals("thinking", (items[1] as WireDisplayItem.Event).event.type)
        val tools = items[2] as WireDisplayItem.ToolGroup
        assertTrue(tools.events.none { it.type == "thinking" })
        assertEquals("thinking", (items[3] as WireDisplayItem.Event).event.type)
    }

    @Test
    fun autoExpandTreatsUnsetKeysAsExpanded() {
        assertTrue(wireActivityExpanded("tool-1", emptySet(), autoExpand = true))
        assertFalse(wireActivityExpanded("tool-1", setOf("tool-1"), autoExpand = true))
        assertFalse(wireActivityExpanded("tool-1", emptySet(), autoExpand = false))
        assertTrue(wireActivityExpanded("tool-1", setOf("tool-1"), autoExpand = false))
    }
}
