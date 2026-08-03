package app.andy.desktop.service.agents.acp

import app.andy.model.AgentEvent
import app.andy.model.AgentKind
import app.andy.model.AgentLaneKind
import app.andy.model.AgentSlashCommand
import app.andy.model.AgentToolKind
import app.andy.model.AgentToolState
import app.andy.model.defaultLane
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.andy.desktop.service.agents.inferAgentLaneFromArtifacts
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AcpLaneTest {
    @Test
    fun providerCommandFilterKeepsBuiltInsAndCurrentProviderSkills() {
        val commands = listOf(
            AgentSlashCommand("brandkit", "shared skill"),
            AgentSlashCommand("review", "provider built-in"),
            AgentSlashCommand("compose-expert", "current provider skill"),
        )

        assertEquals(
            listOf(commands[1], commands[2]),
            AcpEventMapper.filterProviderCommands(
                commands = commands,
                knownSkillNames = setOf("brandkit", "compose-expert"),
                allowedSkillNames = setOf("compose-expert"),
            ),
        )
    }

    @Test
    fun supportedProvidersDefaultToAcpAndTerminalOnlyProvidersStayTerminal() {
        assertEquals(AgentLaneKind.Acp, AgentKind.ClaudeCode.defaultLane())
        assertEquals(AgentLaneKind.Acp, AgentKind.Codex.defaultLane())
        assertEquals(AgentLaneKind.Acp, AgentKind.Cursor.defaultLane())
        assertEquals(AgentLaneKind.Acp, AgentKind.OpenCode.defaultLane())
        assertEquals(AgentLaneKind.Acp, AgentKind.Pi.defaultLane())
        assertEquals(AgentLaneKind.Terminal, AgentKind.Antigravity.defaultLane())
        assertEquals(AgentLaneKind.Terminal, AgentKind.Hermes.defaultLane())
        assertEquals(AgentLaneKind.Terminal, AgentKind.OpenClaw.defaultLane())
    }

    @Test
    fun mapperLabelsAndyMcpToolsFromSparseUpdates() {
        val tool = AcpEventMapper.map(
            SessionUpdate.ToolCallUpdate(
                toolCallId = com.agentclientprotocol.model.ToolCallId("call-1"),
                title = "mcp_andy_tap",
                kind = ToolKind.EXECUTE,
                status = ToolCallStatus.IN_PROGRESS,
                content = null,
                locations = null,
                rawInput = buildJsonObject {
                    put("x", 666)
                    put("y", 1837)
                    put("serial", "R3CXB056ZZB")
                },
                rawOutput = null,
            ),
            atMillis = 10,
        ) as AgentEvent.ToolCall

        assertEquals("Andy MCP · tap", tool.toolName)
        assertEquals("x=666, y=1837, serial=R3CXB056ZZB", tool.summary)

        val completed = AcpEventMapper.reduce(
            listOf(tool),
            AcpEventMapper.map(
                SessionUpdate.ToolCallUpdate(
                    toolCallId = com.agentclientprotocol.model.ToolCallId("call-1"),
                    title = null,
                    kind = ToolKind.EXECUTE,
                    status = ToolCallStatus.COMPLETED,
                    content = null,
                    locations = null,
                    rawInput = null,
                    rawOutput = JsonPrimitive("""{"success":true}"""),
                ),
                atMillis = 11,
            ) as AgentEvent.ToolCall,
        ).single() as AgentEvent.ToolCall

        assertEquals("Andy MCP · tap", completed.toolName)
        assertEquals("x=666, y=1837, serial=R3CXB056ZZB", completed.summary)
        assertEquals(AgentToolState.Completed, completed.state)
    }

    @Test
    fun mapperPreservesAssistantTextAndToolState() {
        val assistant = AcpEventMapper.map(
            SessionUpdate.AgentMessageChunk(ContentBlock.Text("hello")),
            atMillis = 10,
        )
        assertEquals(AgentEvent.AssistantText(10, "hello", isStreamDelta = true), assistant)

        val whitespace = AcpEventMapper.map(
            SessionUpdate.AgentMessageChunk(ContentBlock.Text(" ")),
            atMillis = 11,
        )
        assertEquals(AgentEvent.AssistantText(11, " ", isStreamDelta = true), whitespace)

        val tool = AgentEvent.ToolCall(
            atMillis = 11,
            toolName = "read",
            summary = "file",
            detail = "file",
            toolCallId = "call-1",
            kind = AgentToolKind.Read,
            state = AgentToolState.InProgress,
        )
        val reduced = AcpEventMapper.reduce(listOf(tool), tool.copy(state = AgentToolState.Completed))
        assertEquals(1, reduced.size)
        assertEquals(AgentToolState.Completed, (reduced.single() as AgentEvent.ToolCall).state)
    }

    @Test
    fun mapperDoesNotEchoProviderUserMessagesIntoTranscript() {
        assertEquals(
            null,
            AcpEventMapper.map(
                SessionUpdate.UserMessageChunk(ContentBlock.Text("already recorded by Andy")),
                atMillis = 12,
            ),
        )
    }

    @Test
    fun replayFilterDropsProviderHistoryEchoButKeepsNewAssistantText() {
        val existing = listOf(
            AgentEvent.UserMessage(atMillis = 1, text = "first question"),
            AgentEvent.AssistantText(atMillis = 2, text = "first answer about the weather"),
        )
        val replayScratch = StringBuilder()
        assertTrue(
            shouldIgnoreAcpProviderHistoryReplay(
                existing,
                AgentEvent.AssistantText(atMillis = 3, text = "first answer", isStreamDelta = true),
                replayScratch,
            ),
        )
        assertTrue(
            shouldIgnoreAcpProviderHistoryReplay(
                existing,
                AgentEvent.AssistantText(atMillis = 4, text = " about the weather", isStreamDelta = true),
                replayScratch,
            ),
        )
        assertFalse(
            shouldIgnoreAcpProviderHistoryReplay(
                existing,
                AgentEvent.AssistantText(atMillis = 5, text = "brand new answer", isStreamDelta = true),
                StringBuilder(),
            ),
        )
        assertTrue(
            shouldIgnoreAcpProviderHistoryReplay(
                existing + AgentEvent.ToolCall(atMillis = 6, toolName = "read", summary = "x", toolCallId = "call-1"),
                AgentEvent.ToolCall(atMillis = 7, toolName = "read", summary = "done", toolCallId = "call-1"),
                StringBuilder(),
            ),
        )
    }

    @Test
    fun transcriptStoreRoundTripsAndUpsertsToolCalls() {
        val root = createTempDirectory("andy-acp-transcript").toFile()
        try {
            val store = AcpTranscriptStore(fileFor = { id -> root.resolve(id).resolve("transcript.jsonl") })
            store.append("task-1", AgentEvent.AssistantText(1, "hello"))
            store.append("task-1", AgentEvent.ToolCall(2, "read", "one", toolCallId = "call-1"))
            store.upsert("task-1", AgentEvent.ToolCall(3, "read", "done", toolCallId = "call-1"))
            val loaded = store.load("task-1")
            assertEquals(2, loaded.size)
            assertEquals("hello", (loaded[0] as AgentEvent.AssistantText).text)
            assertEquals("done", (loaded[1] as AgentEvent.ToolCall).summary)
            assertTrue(root.resolve("task-1/transcript.jsonl").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stopReasonsProduceConfidentTurnBoundaries() {
        assertEquals(app.andy.model.AgentStatus.Done, AcpStatusModel.fromStopReason("end_turn").status)
        assertEquals(app.andy.model.AgentStatus.Done, AcpStatusModel.fromStopReason("cancelled").status)
        assertTrue(AcpStatusModel.fromStopReason("max_tokens").confident)
    }

    @Test
    fun laneInferencePrefersOnDiskArtifactsOverDeclaredLane() {
        val root = createTempDirectory("andy-lane-infer").toFile()
        try {
            val taskId = "task-weather"
            val transcript = root.resolve("$taskId/transcript.jsonl")
            transcript.parentFile.mkdirs()
            transcript.writeText("""{"type":"user","atMillis":1,"text":"hi"}""" + "\n")

            assertEquals(
                AgentLaneKind.Acp,
                inferAgentLaneFromArtifacts(taskId, AgentLaneKind.Terminal, AgentKind.Cursor, root),
            )

            val scrollOnly = "task-terminal"
            val scrollback = root.resolve("$scrollOnly/scrollback.ansi")
            scrollback.parentFile.mkdirs()
            scrollback.writeText("\u001b[0m>")

            assertEquals(
                AgentLaneKind.Terminal,
                inferAgentLaneFromArtifacts(scrollOnly, null, AgentKind.Codex, root),
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
