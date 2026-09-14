package app.andy.ui.agents

import app.andy.model.AgentEvent
import app.andy.model.AgentSkill
import app.andy.model.AcpToolCallPresentation
import app.andy.model.TimelineAxis
import app.andy.model.buildChatTimeline
import app.andy.model.projectedInterval
import app.andy.model.timelineBrushSelection
import app.andy.model.timelineFilterRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatTimelineViewTest {
    private val sampleEvents = listOf(
        AgentEvent.SessionStarted(atMillis = 1_000, sessionId = "s", model = "test"),
        AgentEvent.UserMessage(
            atMillis = 2_000,
            text = "hello",
            skills = listOf(AgentSkill("compose-expert", "help", "/skills/compose")),
        ),
        AgentEvent.AssistantText(atMillis = 3_000, text = "Hi"),
        AgentEvent.ToolCall(
            atMillis = 4_000,
            toolName = "bash",
            summary = "pwd",
            detail = """{"command":"pwd"}""" + AcpToolCallPresentation.DetailSeparator + "/tmp",
            toolCallId = "t1",
            startedAtMillis = 3_500,
            endedAtMillis = 4_000,
        ),
        AgentEvent.UserMessage(atMillis = 5_000, text = "next"),
        AgentEvent.ToolCall(
            atMillis = 6_000,
            toolName = "read",
            summary = "README",
            detail = "README",
            toolCallId = "t2",
            startedAtMillis = 5_500,
            endedAtMillis = 6_000,
        ),
    )

    @Test
    fun turnsAxisCollapsesToUserAndSummaryEntries() {
        val model = buildChatTimeline(sampleEvents)
        val entries = timelineListEntries(model, TimelineAxis.Turns)
        assertTrue(entries.any { it.row?.kind?.name == "System" || it.row?.badge == "SYSTEM" })
        assertTrue(entries.any { it.row?.badge == "USER" })
        assertTrue(entries.any { it.summaryText?.contains("tool call") == true })
        assertFalse(entries.any { it.row?.badge == "TOOL" })
    }

    @Test
    fun callsAxisKeepsToolRows() {
        val model = buildChatTimeline(sampleEvents)
        val entries = timelineListEntries(model, TimelineAxis.Calls)
        assertTrue(entries.any { it.row?.badge == "TOOL" })
        assertTrue(entries.any { it.row?.badge == "CONTEXT" })
        assertEquals(model.rows.size, entries.size)
    }

    @Test
    fun filterAndBrushSelectionAgreeWithDomainHelpers() {
        val model = buildChatTimeline(sampleEvents)
        val filtered = timelineFilterRows(model, "compose-expert")
        assertTrue(filtered.isNotEmpty())
        assertTrue(filtered.all { key -> model.rows.any { it.key == key && it.title.contains("compose-expert") } })

        val firstTool = model.rows.first { it.key == "tool-t1" }
        val (start, end) = firstTool.projectedInterval(model, TimelineAxis.Calls, durationMode = false)
        val brushed = timelineBrushSelection(model, TimelineAxis.Calls, start, end, durationMode = false)
        assertTrue("tool-t1" in brushed)
        assertFalse("tool-t2" in brushed)
    }

    @Test
    fun turnsAxisSurfacesHiddenRowsMatchedBySearch() {
        val model = buildChatTimeline(sampleEvents)
        val matching = timelineFilterRows(model, "README")
        assertTrue(matching.isNotEmpty())
        val entries = timelineListEntries(model, TimelineAxis.Turns, matching)
        // The hidden tool row matched by the query is now visible and not dimmed.
        assertTrue(entries.any { it.row?.key in matching && it.matchesSearch })
        // Turns with no match stay listed but dimmed.
        assertTrue(entries.any { !it.matchesSearch })
    }
}
