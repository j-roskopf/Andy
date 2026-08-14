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
    fun summarySkipsBareCodeFenceMarkerLines() {
        val (summary, _) = AcpToolCallPresentation.formatSummary(
            toolName = "tool",
            rawInput = "",
            rawOutput = "",
            contentDetails = "```console\nsrc/App.kt:12:fun main() {}\n```",
        )
        assertEquals("src/App.kt:12:fun main() {}", summary)
    }

    @Test
    fun summaryFallbackSkipsBareFenceMarkerWhenNoOtherContent() {
        val (summary, _) = AcpToolCallPresentation.formatSummary(
            toolName = "tool",
            rawInput = "",
            rawOutput = "```console",
            contentDetails = "```console",
        )
        assertTrue(summary.isBlank(), "expected no bare fence marker leaking into summary, got \"$summary\"")
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

    @Test
    fun parsesActionTitleIntoVerbAndPath() {
        val presented = AcpToolCallPresentation.present(
            title = "Edit src/commonMain/kotlin/app/andy/ui/agents/AgentTranscript.kt",
            rawInput = "{}",
            rawOutput = null,
            contentDetails = "",
        )
        assertEquals("Edit", presented.toolName)
        assertEquals("AgentTranscript.kt", AcpToolCallPresentation.enrichSummary(
            presented.summary,
            AgentToolKind.Edit,
            emptyList(),
        ))
    }

    @Test
    fun executeTitleFallsBackToCommandString() {
        val presented = AcpToolCallPresentation.present(
            title = "./gradlew desktopTest --tests AcpToolCallPresentationTest",
            rawInput = "{}",
            rawOutput = null,
            contentDetails = "",
        )
        assertEquals("./gradlew desktopTest --tests AcpToolCallPresentationTest", presented.toolName)
        assertEquals("./gradlew desktopTest --tests AcpToolCallPresentationTest", presented.summary)
    }

    @Test
    fun commandArgumentBecomesSummaryAlone() {
        val (summary, _) = AcpToolCallPresentation.formatSummary(
            toolName = "Terminal",
            rawInput = """{"command":"git status --short","cwd":"/Users/dev/Andy"}""",
            rawOutput = "",
            contentDetails = "",
        )
        assertEquals("git status --short", summary)
    }

    @Test
    fun jsonToolDetailsBecomeReadableMarkdown() {
        val presented = AcpToolCallPresentation.present(
            title = "Search",
            rawInput = """{"query":"ToolBlock","case_sensitive":false,"paths":["src","test"]}""",
            rawOutput = null,
            contentDetails = "",
        )

        assertEquals(
            """
            - **query:** ToolBlock
            - **case sensitive:** false
            - **paths:**
              - src
              - test
            """.trimIndent(),
            presented.detail,
        )
        assertFalse(presented.detail.contains("{"))
        assertFalse(presented.detail.contains("\"query\""))
    }

    @Test
    fun echoedArgumentsDoNotCountAsExtraDetail() {
        val headline = "totalMatches=45, truncated=false"
        val body = AcpToolCallPresentation.displayDetail("""{"totalMatches":45,"truncated":false}""")

        assertFalse(body.contains("{"))
        assertFalse(AcpToolCallPresentation.detailAddsInformation(headline, body))
        assertFalse(AcpToolCallPresentation.detailAddsInformation(headline, ""))
        assertTrue(
            AcpToolCallPresentation.detailAddsInformation(headline, "$body\n- **path:** src/Main.kt"),
        )
    }

    /** `{"exitCode":0,"stdout":"…"}` is the shape every shell tool returns; the output is the point. */
    @Test
    fun commandResultOutputRendersAsABlockNotAnInlineValue() {
        val payload = """{"exitCode":0,"stdout":"first line\nsecond line","stderr":""}"""

        val body = AcpToolCallPresentation.displayDetail(payload)

        assertFalse(body.contains("{"))
        assertFalse(body.contains("\"stdout\""))
        assertEquals(
            """
            - **exitCode:** 0
            - **stdout:**
            ```
            first line
            second line
            ```
            - **stderr:** —
            """.trimIndent(),
            body,
        )
        assertEquals(listOf("first line\nsecond line"), AcpToolCallPresentation.payloadTextValues(payload))
        assertTrue(AcpToolCallPresentation.payloadTextValues("plain text output").isEmpty())
    }

    @Test
    fun placeholderDetailsCountAsNoOutput() {
        assertTrue(AcpToolCallPresentation.isMinimalOutput("No details"))
        assertTrue(AcpToolCallPresentation.isMinimalOutput("none"))
        assertFalse(AcpToolCallPresentation.isMinimalOutput("42 matches"))
    }

    @Test
    fun markdownToolDetailsRemainMarkdown() {
        val detail = "### Matches\n\n- `AgentTranscript.kt`\n- `AgentModels.kt`"

        assertEquals(detail, AcpToolCallPresentation.displayDetail(detail))
    }

    @Test
    fun mergeKeepsRicherNameAndDoesNotRegressState() {
        val first = AgentEvent.ToolCall(
            atMillis = 1,
            toolName = "Terminal",
            summary = "",
            detail = "{}",
            toolCallId = "call-1",
            kind = AgentToolKind.Execute,
            state = AgentToolState.InProgress,
        )
        val update = AgentEvent.ToolCall(
            atMillis = 2,
            toolName = "./gradlew test",
            summary = "./gradlew test",
            detail = "./gradlew test",
            toolCallId = "call-1",
            kind = AgentToolKind.Execute,
            state = AgentToolState.Pending,
        )
        val merged = AcpToolCallPresentation.mergeToolCalls(first, update)
        assertEquals("./gradlew test", merged.toolName)
        assertEquals("./gradlew test", merged.summary)
        assertEquals(AgentToolState.InProgress, merged.state)
    }

    @Test
    fun mergePrefersActionPathOverSparseEditLabel() {
        val first = AgentEvent.ToolCall(
            atMillis = 1,
            toolName = "Edit",
            summary = "",
            detail = "{}",
            toolCallId = "call-1",
            kind = AgentToolKind.Edit,
            state = AgentToolState.Pending,
        )
        val update = AgentEvent.ToolCall(
            atMillis = 2,
            toolName = "Edit",
            summary = "src/Foo.kt",
            detail = "Edit src/Foo.kt",
            toolCallId = "call-1",
            kind = AgentToolKind.Edit,
            state = AgentToolState.Pending,
            locations = listOf("/Users/dev/Andy/src/Foo.kt"),
        )
        val merged = AcpToolCallPresentation.mergeToolCalls(first, update)
        assertEquals("Edit", merged.toolName)
        assertEquals("Foo.kt", merged.summary)
    }
}
