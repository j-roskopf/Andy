package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpToolCallPresentationTest {
    @Test
    fun formatsAndyMcpToolNames() {
        assertEquals("Andy MCP · list devices", AcpToolCallPresentation.formatMcpToolName("mcp_andy_list_devices"))
        assertEquals("Andy MCP · tap", AcpToolCallPresentation.formatMcpToolName("mcp_andy_tap"))
    }

    @Test
    fun resolvesGenericTitleFromRawInput() {
        assertEquals(
            "Andy MCP · tap",
            AcpToolCallPresentation.displayToolName("tool", """{"x":666,"y":1837}""", "mcp_andy_tap"),
        )
    }

    @Test
    fun replacesMinimalSuccessOutputWithArguments() {
        val presented = AcpToolCallPresentation.present(
            title = "mcp_andy_tap",
            rawInput = """{"serial":"R3CXB056ZZB","x":666,"y":1837}""",
            rawOutput = """{"success":true}""",
            contentDetails = "",
        )
        assertEquals("Andy MCP · tap", presented.toolName)
        assertEquals("serial=R3CXB056ZZB, x=666, y=1837", presented.summary)
        assertFalse(AcpToolCallPresentation.isMinimalOutput(presented.summary))
    }

    @Test
    fun summarizesCompactJsonArguments() {
        val (summary, _) = AcpToolCallPresentation.formatSummary(
            toolName = "Andy MCP · tap",
            rawInput = """{"x":666,"y":1837,"serial":"R3CXB056ZZB"}""",
            rawOutput = "",
            contentDetails = "",
        )
        assertEquals("x=666, y=1837, serial=R3CXB056ZZB", summary)
    }

    @Test
    fun mergePreservesNamedToolWhenUpdateIsGeneric() {
        val first = AgentEvent.ToolCall(
            atMillis = 1,
            toolName = "Andy MCP · list devices",
            summary = "serial=R3CXB056ZZB",
            detail = """mcp_andy_list_devices""",
            toolCallId = "call-1",
        )
        val update = AgentEvent.ToolCall(
            atMillis = 2,
            toolName = "tool",
            summary = """{"success":true}""",
            detail = """{"success":true}""",
            toolCallId = "call-1",
            state = AgentToolState.Completed,
        )
        val merged = AcpToolCallPresentation.mergeToolCalls(first, update)
        assertEquals("Andy MCP · list devices", merged.toolName)
        assertEquals("serial=R3CXB056ZZB", merged.summary)
        assertEquals(AgentToolState.Completed, merged.state)
    }

    @Test
    fun emptyJsonArgumentsAreMinimal() {
        assertTrue(AcpToolCallPresentation.isMinimalOutput("{}"))
        assertTrue(AcpToolCallPresentation.isMinimalOutput("""{"success":true}"""))
    }

    @Test
    fun editFileSummaryUsesLocationsWhenArgumentsAreEmpty() {
        val presented = AcpToolCallPresentation.present(
            title = "Edit File",
            rawInput = "{}",
            rawOutput = null,
            contentDetails = "",
        )
        assertEquals("", presented.summary)
        assertEquals(
            "AgentTranscript.kt",
            AcpToolCallPresentation.enrichSummary(
                presented.summary,
                AgentToolKind.Edit,
                listOf("/Users/dev/Andy/src/commonMain/kotlin/app/andy/ui/agents/AgentTranscript.kt"),
            ),
        )
    }
}
