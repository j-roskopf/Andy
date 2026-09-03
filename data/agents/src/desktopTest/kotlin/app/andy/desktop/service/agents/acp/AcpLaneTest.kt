package app.andy.desktop.service.agents.acp

import app.andy.model.AgentChangeSummary
import app.andy.model.AgentEvent
import app.andy.model.AgentFileChange
import app.andy.model.AgentKind
import app.andy.model.AgentLaneKind
import app.andy.model.AgentSlashCommand
import app.andy.model.AgentTask
import app.andy.model.AgentThreadChangeSnapshot
import app.andy.model.AgentToolKind
import app.andy.model.AgentToolState
import app.andy.model.LocalAgentRuntime
import app.andy.model.acpSupported
import app.andy.model.defaultLane
import app.andy.model.modelForCli
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanEntryPriority
import com.agentclientprotocol.model.PlanEntryStatus
import com.agentclientprotocol.model.PlanVariant
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.andy.desktop.service.agents.AndyMcpEndpoint
import app.andy.desktop.service.agents.acpEndpointUrl
import app.andy.desktop.service.agents.inferAgentLaneFromArtifacts
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AcpLaneTest {
    @Test
    fun codexAcpUsesStreamableHttpEndpoint() {
        val endpoint = AndyMcpEndpoint(
            port = 8565,
            httpUrl = "http://127.0.0.1:8565/mcp-http?andyTaskId=task-1",
        )
        assertEquals(endpoint.httpUrl, AgentKind.Codex.acpEndpointUrl(endpoint))
        assertEquals(endpoint.httpUrl, AgentKind.ClaudeCode.acpEndpointUrl(endpoint))
    }

    @Test
    fun providerCommandFilterKeepsForeignSkillsOnly() {
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
    fun supportedProvidersAlwaysUseAcpAndTerminalOnlyProvidersStayTerminal() {
        assertEquals(AgentLaneKind.Acp, AgentKind.ClaudeCode.defaultLane())
        assertEquals(AgentLaneKind.Acp, AgentKind.Codex.defaultLane())
        assertEquals(AgentLaneKind.Acp, AgentKind.Cursor.defaultLane())
        assertEquals(AgentLaneKind.Acp, AgentKind.OpenCode.defaultLane())
        assertEquals(AgentLaneKind.Acp, AgentKind.Pi.defaultLane())
        assertEquals(AgentLaneKind.Acp, AgentKind.Goose.defaultLane())
        assertTrue(AgentKind.ClaudeCode.acpSupported)
        assertTrue(AgentKind.Codex.acpSupported)
        assertTrue(AgentKind.Cursor.acpSupported)
        assertTrue(AgentKind.OpenCode.acpSupported)
        assertTrue(AgentKind.Pi.acpSupported)
        assertTrue(AgentKind.Goose.acpSupported)
        assertEquals(AgentLaneKind.Terminal, AgentKind.Antigravity.defaultLane())
        assertEquals(AgentLaneKind.Terminal, AgentKind.Hermes.defaultLane())
        assertEquals(AgentLaneKind.Terminal, AgentKind.OpenClaw.defaultLane())
        assertFalse(AgentKind.Antigravity.acpSupported)
        assertFalse(AgentKind.Hermes.acpSupported)
        assertFalse(AgentKind.OpenClaw.acpSupported)
    }

    @Test
    fun gooseAcpLaunchSpecEnablesDeveloperBuiltin() {
        val spec = AcpRegistry.spec(AgentKind.Goose)
        assertIs<AcpLaunchSpec.Native>(spec)
        assertEquals("goose", spec.command)
        assertEquals(listOf("acp", "--with-builtin", "developer"), spec.args)
    }

    @Test
    fun gooseAcpUsesUnprefixedLocalModelIds() {
        assertEquals(
            "muse-glimmer:30b-mlx",
            acpSessionModelId(
                AgentTask(
                    id = "t1",
                    title = "local",
                    prompt = "hi",
                    agent = AgentKind.Ollama,
                    localRuntime = LocalAgentRuntime.Goose,
                    model = "ollama/muse-glimmer:30b-mlx",
                    createdAtMillis = 0,
                ),
            ),
        )
        assertEquals(
            "qwen/qwen3.8-27b",
            acpSessionModelId(
                AgentTask(
                    id = "t1",
                    title = "local",
                    prompt = "hi",
                    agent = AgentKind.LMStudio,
                    localRuntime = LocalAgentRuntime.Goose,
                    model = "lmstudio/qwen/qwen3.8-27b",
                    createdAtMillis = 0,
                ),
            ),
        )
        assertEquals(
            "claude-sonnet-4-5",
            acpSessionModelId(
                AgentTask(
                    id = "t1",
                    title = "native",
                    prompt = "hi",
                    agent = AgentKind.Goose,
                    model = "anthropic/claude-sonnet-4-5",
                    createdAtMillis = 0,
                ),
            ),
        )
        val openCodeOllama = AgentTask(
            id = "t1",
            title = "local",
            prompt = "hi",
            agent = AgentKind.Ollama,
            localRuntime = LocalAgentRuntime.OpenCode,
            model = "muse-glimmer:30b-mlx",
            createdAtMillis = 0,
        )
        assertEquals("ollama/muse-glimmer:30b-mlx", acpSessionModelId(openCodeOllama))
        assertEquals("ollama/muse-glimmer:30b-mlx", openCodeOllama.modelForCli())
    }

    @Test
    fun piAcpForwardsSelectedLmStudioModel() {
        val spec = AcpRegistry.specFor(
            app.andy.model.AgentTask(
                id = "t1",
                title = "local",
                prompt = "hi",
                agent = AgentKind.LMStudio,
                localRuntime = app.andy.model.LocalAgentRuntime.Pi,
                model = "qwen/qwen3.8-27b",
                createdAtMillis = 0,
            ),
        )
        assertIs<AcpLaunchSpec.Npx>(spec)
        assertEquals("pi-acp", spec.packageName)
        assertEquals(
            listOf("--provider", "lmstudio", "--model", "lmstudio/qwen/qwen3.8-27b"),
            spec.extraArgs,
        )
        assertEquals(emptyList(), piAcpModelArgs(app.andy.model.AgentTask(
            id = "t1",
            title = "g",
            prompt = "hi",
            agent = AgentKind.Goose,
            createdAtMillis = 0,
        )))
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
    fun mapperSplitsEditActionTitlesAndKeepsPendingEnrichment() {
        val pending = AcpEventMapper.map(
            SessionUpdate.ToolCall(
                toolCallId = com.agentclientprotocol.model.ToolCallId("edit-1"),
                title = "Edit",
                kind = ToolKind.EDIT,
                status = ToolCallStatus.PENDING,
                content = emptyList(),
                locations = emptyList(),
                rawInput = buildJsonObject {},
                rawOutput = null,
            ),
            atMillis = 1,
        ) as AgentEvent.ToolCall
        assertEquals("Edit", pending.toolName)
        assertEquals("", pending.summary)

        val enriched = AcpEventMapper.reduce(
            listOf(pending),
            AcpEventMapper.map(
                SessionUpdate.ToolCallUpdate(
                    toolCallId = com.agentclientprotocol.model.ToolCallId("edit-1"),
                    title = "Edit src/commonMain/kotlin/app/andy/ui/settings/SettingsScreen.kt",
                    kind = ToolKind.EDIT,
                    status = ToolCallStatus.PENDING,
                    content = null,
                    locations = listOf(
                        com.agentclientprotocol.model.ToolCallLocation(
                            "src/commonMain/kotlin/app/andy/ui/settings/SettingsScreen.kt",
                        ),
                    ),
                    rawInput = null,
                    rawOutput = null,
                ),
                atMillis = 2,
            ) as AgentEvent.ToolCall,
        ).single() as AgentEvent.ToolCall

        assertEquals("Edit", enriched.toolName)
        assertEquals("SettingsScreen.kt", enriched.summary)
        assertEquals(AgentToolState.Pending, enriched.state)
    }

    @Test
    fun mapperInfersEditKindAndPathWhenProviderReportsOther() {
        val mapped = assertIs<AgentEvent.ToolCall>(
            AcpEventMapper.map(
                SessionUpdate.ToolCall(
                    toolCallId = com.agentclientprotocol.model.ToolCallId("edit-other"),
                    title = "Edit File",
                    kind = ToolKind.OTHER,
                    status = ToolCallStatus.COMPLETED,
                    content = listOf(
                        com.agentclientprotocol.model.ToolCallContent.Diff(
                            path = "/Users/joer/Code/Andy/Andy/src/Main.kt",
                            oldText = "foo\n",
                            newText = "bar\n",
                        ),
                    ),
                    locations = emptyList(),
                    rawInput = null,
                    rawOutput = null,
                ),
                atMillis = 1,
            ),
        )
        assertEquals(AgentToolKind.Edit, mapped.kind)
        assertEquals(listOf("/Users/joer/Code/Andy/Andy/src/Main.kt"), mapped.locations)
    }

    @Test
    fun mapperKeepsArgumentOnlyEditJsonUntilStructuredParsing() {
        val mapped = assertIs<AgentEvent.ToolCall>(
            AcpEventMapper.map(
                SessionUpdate.ToolCall(
                    toolCallId = com.agentclientprotocol.model.ToolCallId("edit-json"),
                    title = "Edit File",
                    kind = ToolKind.EDIT,
                    status = ToolCallStatus.PENDING,
                    content = emptyList(),
                    rawInput = buildJsonObject {
                        put("file_path", "README.md")
                        put("old_string", "old")
                        put("new_string", "new")
                    },
                ),
                atMillis = 1,
            ),
        )

        assertTrue(mapped.detail.startsWith("{"))
        val parsed = assertIs<app.andy.domain.ToolCallFileContent>(
            app.andy.domain.parseToolCallFileArguments(mapped.detail, mapped.kind),
        )
        assertEquals("README.md", parsed.path)
        assertEquals("old", parsed.oldText)
        assertEquals("new", parsed.newText)

        val bundled = assertIs<AgentEvent.ToolCall>(
            AcpEventMapper.map(
                SessionUpdate.ToolCall(
                    toolCallId = com.agentclientprotocol.model.ToolCallId("edit-json-output"),
                    title = "Edit File",
                    kind = ToolKind.EDIT,
                    status = ToolCallStatus.COMPLETED,
                    content = emptyList(),
                    rawInput = buildJsonObject {
                        put("file_path", "README.md")
                        put("old_string", "old")
                        put("new_string", "new")
                    },
                    rawOutput = buildJsonObject { put("success", true) },
                ),
                atMillis = 2,
            ),
        )
        assertEquals(mapped.detail, bundled.detail)

        val withContent = assertIs<AgentEvent.ToolCall>(
            AcpEventMapper.map(
                SessionUpdate.ToolCall(
                    toolCallId = com.agentclientprotocol.model.ToolCallId("edit-json-content"),
                    title = "Edit File",
                    kind = ToolKind.EDIT,
                    status = ToolCallStatus.COMPLETED,
                    content = listOf(
                        com.agentclientprotocol.model.ToolCallContent.Content(
                            content = ContentBlock.Text("warning: formatter skipped generated file"),
                        ),
                    ),
                    rawInput = buildJsonObject {
                        put("file_path", "README.md")
                        put("old_string", "old")
                        put("new_string", "new")
                    },
                ),
                atMillis = 3,
            ),
        )
        val withContentParsed = assertIs<app.andy.domain.ToolCallFileContent>(
            app.andy.domain.parseToolCallFileArguments(withContent.detail, withContent.kind),
        )
        assertEquals("README.md", withContentParsed.path)
        assertEquals("warning: formatter skipped generated file", withContentParsed.extraDetail)

        val primitiveMove = assertIs<AgentEvent.ToolCall>(
            AcpEventMapper.map(
                SessionUpdate.ToolCall(
                    toolCallId = com.agentclientprotocol.model.ToolCallId("move-primitive-input"),
                    title = "Move File",
                    kind = ToolKind.MOVE,
                    status = ToolCallStatus.COMPLETED,
                    content = emptyList(),
                    rawInput = kotlinx.serialization.json.JsonPrimitive("README.md"),
                    rawOutput = buildJsonObject { put("moved", true) },
                ),
                atMillis = 4,
            ),
        )
        val primitiveMoveParsed = assertIs<app.andy.domain.ToolCallFileContent>(
            app.andy.domain.parseToolCallFileArguments(primitiveMove.detail, primitiveMove.kind),
        )
        assertEquals("README.md", primitiveMoveParsed.path)
        assertEquals("""{"moved":true}""", primitiveMoveParsed.extraDetail)
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
    fun mapperMapsSessionInfoTitleAndIgnoresBlank() {
        val titled = AcpEventMapper.map(
            SessionUpdate.SessionInfoUpdate(title = "Fix login flakiness", updatedAt = null, _meta = null),
            atMillis = 20,
        )
        assertEquals(AgentEvent.SessionInfo(20, "Fix login flakiness"), titled)

        assertEquals(
            null,
            AcpEventMapper.map(
                SessionUpdate.SessionInfoUpdate(title = "  ", updatedAt = "2026-09-03T12:00:00Z", _meta = null),
                atMillis = 21,
            ),
        )
    }

    @Test
    fun mapperRendersPlanUpdateV2Items() {
        val event = AcpEventMapper.map(
            SessionUpdate.PlanUpdateV2(
                plan = PlanVariant.Items(
                    id = "plan-1",
                    entries = listOf(
                        PlanEntry(
                            content = "Inspect CI workflow",
                            priority = PlanEntryPriority.MEDIUM,
                            status = PlanEntryStatus.PENDING,
                            _meta = JsonNull,
                        ),
                    ),
                    _meta = JsonNull,
                ),
                _meta = JsonNull,
            ),
            atMillis = 42,
        )

        assertIs<AgentEvent.PlanUpdate>(event)
        assertEquals("Inspect CI workflow", event.entries.single().content)
        assertEquals("pending", event.entries.single().status)
    }

    @Test
    fun mapperRendersPlanUpdateV2Markdown() {
        val event = AcpEventMapper.map(
            SessionUpdate.PlanUpdateV2(
                plan = PlanVariant.Markdown(
                    id = "plan-md",
                    content = "## Plan\n\n1. Profile iOS job",
                    _meta = JsonNull,
                ),
                _meta = JsonNull,
            ),
            atMillis = 43,
        )

        assertIs<AgentEvent.PlanUpdate>(event)
        assertEquals("## Plan\n\n1. Profile iOS job", event.markdown)
        assertTrue(event.entries.isEmpty())
    }

    @Test
    fun replayFilterDropsProviderHistoryEchoButKeepsNewAssistantText() {
        val existing = listOf(
            AgentEvent.UserMessage(atMillis = 1, text = "first question"),
            AgentEvent.AssistantText(atMillis = 2, text = "first answer about the weather"),
        )
        val replayScratch = StringBuilder()
        assertEquals(
            AcpReplayFilterResult.Ignore,
            filterAcpProviderHistoryReplay(
                existing,
                AgentEvent.AssistantText(atMillis = 3, text = "first answer", isStreamDelta = true),
                replayScratch,
            ),
        )
        assertEquals(
            AcpReplayFilterResult.Ignore,
            filterAcpProviderHistoryReplay(
                existing,
                AgentEvent.AssistantText(atMillis = 4, text = " about the weather", isStreamDelta = true),
                replayScratch,
            ),
        )
        assertEquals(
            AcpReplayFilterResult.Accept(),
            filterAcpProviderHistoryReplay(
                existing,
                AgentEvent.AssistantText(atMillis = 5, text = "brand new answer", isStreamDelta = true),
                StringBuilder(),
            ),
        )
        assertEquals(
            AcpReplayFilterResult.Ignore,
            filterAcpProviderHistoryReplay(
                existing,
                AgentEvent.UserMessage(atMillis = 6, text = "first question"),
                StringBuilder(),
            ),
            "echoed user turns already in Andy's transcript must not duplicate",
        )
        assertEquals(
            AcpReplayFilterResult.Accept(),
            filterAcpProviderHistoryReplay(
                existing,
                AgentEvent.UserMessage(atMillis = 7, text = "typed in the terminal"),
                StringBuilder(),
            ),
            "new terminal user turns should appear in Compose after switching back",
        )
        assertEquals(
            AcpReplayFilterResult.Ignore,
            filterAcpProviderHistoryReplay(
                existing + AgentEvent.ToolCall(atMillis = 8, toolName = "read", summary = "x", toolCallId = "call-1"),
                AgentEvent.ToolCall(atMillis = 9, toolName = "read", summary = "done", toolCallId = "call-1"),
                StringBuilder(),
            ),
        )
    }

    @Test
    fun replayFilterKeepsNewTextOpeningLikeAStrandedEarlierChunk() {
        // A tool call between chunks ends the preceding turn, stranding its opening chunk as a
        // one-character assistant message. A later answer beginning "I'll …" must survive intact.
        val existing = listOf(
            AgentEvent.AssistantText(atMillis = 1, text = "I", isStreamDelta = true),
            AgentEvent.ToolCall(atMillis = 2, toolName = "read", summary = "x", toolCallId = "call-1"),
            AgentEvent.AssistantText(atMillis = 3, text = "'ve kicked off research", isStreamDelta = true),
        )
        val replayScratch = StringBuilder()
        assertEquals(
            AcpReplayFilterResult.Accept(),
            filterAcpProviderHistoryReplay(
                existing,
                AgentEvent.AssistantText(atMillis = 4, text = "I", isStreamDelta = true),
                replayScratch,
            ),
            "a one-character prior turn is not evidence of a replay",
        )
        assertEquals(
            AcpReplayFilterResult.Accept(),
            filterAcpProviderHistoryReplay(
                existing,
                AgentEvent.AssistantText(atMillis = 5, text = "'ll leave it wired up", isStreamDelta = true),
                replayScratch,
            ),
        )
    }

    @Test
    fun replayFilterRecoversBufferedPrefixWhenNewThinkingDivergesFromPrior() {
        val existing = listOf(
            AgentEvent.Thinking(atMillis = 1, text = "reverting the old layout"),
        )
        val replayScratch = StringBuilder()
        assertEquals(
            AcpReplayFilterResult.Ignore,
            filterAcpProviderHistoryReplay(
                existing,
                AgentEvent.Thinking(atMillis = 2, text = "re", isStreamDelta = true),
                replayScratch,
            ),
        )
        assertEquals(
            AcpReplayFilterResult.Accept(text = "reverting the layout to the original"),
            filterAcpProviderHistoryReplay(
                existing,
                AgentEvent.Thinking(atMillis = 3, text = "verting the layout to the original", isStreamDelta = true),
                replayScratch,
            ),
        )
    }

    @Test
    fun replayFilterEmitsOnlySuffixWhenNewAssistantTextExtendsPrior() {
        val existing = listOf(
            AgentEvent.AssistantText(atMillis = 1, text = "**Width** — nearly full width"),
        )
        val replayScratch = StringBuilder()
        assertEquals(
            AcpReplayFilterResult.Ignore,
            filterAcpProviderHistoryReplay(
                existing,
                AgentEvent.AssistantText(atMillis = 2, text = "**Width** — nearly full width", isStreamDelta = true),
                replayScratch,
            ),
        )
        assertEquals(
            AcpReplayFilterResult.Accept(),
            filterAcpProviderHistoryReplay(
                existing,
                AgentEvent.AssistantText(
                    atMillis = 3,
                    text = " again (`fillMaxWidth` with 8dp padding)",
                    isStreamDelta = true,
                ),
                replayScratch,
            ),
        )
    }

    @Test
    fun transcriptStoreCoalescesStreamDeltasOnDisk() {
        val root = createTempDirectory("andy-acp-coalesce").toFile()
        try {
            val store = AcpTranscriptStore(fileFor = { id -> root.resolve(id).resolve("transcript.jsonl") })
            store.append("task-1", AgentEvent.AssistantText(1, "Hey", isStreamDelta = true))
            store.append("task-1", AgentEvent.AssistantText(2, " there", isStreamDelta = true))
            store.append("task-1", AgentEvent.UserMessage(3, "next turn"))
            val loaded = store.load("task-1")
            assertEquals(2, loaded.size)
            assertEquals("Hey there", (loaded[0] as AgentEvent.AssistantText).text)
            assertEquals("next turn", (loaded[1] as AgentEvent.UserMessage).text)
            assertEquals(2, root.resolve("task-1/transcript.jsonl").readLines().size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun transcriptStoreRoundTripsFileChangesAndMarksUndone() {
        val root = createTempDirectory("andy-acp-file-changes").toFile()
        try {
            val store = AcpTranscriptStore(fileFor = { id -> root.resolve(id).resolve("transcript.jsonl") })
            val snapshot = AgentThreadChangeSnapshot(
                summary = AgentChangeSummary(listOf(AgentFileChange("src/Main.kt", 2, 1))),
                diffs = emptyMap(),
            )
            store.append(
                "task-1",
                AgentEvent.FileChanges(
                    atMillis = 1,
                    batchId = "batch-1",
                    baselineTree = "abc123",
                    snapshot = snapshot,
                ),
            )
            val loaded = store.load("task-1").single() as AgentEvent.FileChanges
            assertEquals("batch-1", loaded.batchId)
            assertEquals("src/Main.kt", loaded.snapshot.summary.files.single().path)

            store.markFileChangesUndone("task-1", "batch-1")
            val undone = store.load("task-1").single() as AgentEvent.FileChanges
            assertTrue(undone.undone)
        } finally {
            root.deleteRecursively()
        }
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

    /**
     * cursor-agent announces a call with its title and empty arguments, then reports completion in a
     * separate update carrying only status and output. Replacing the stored row erased the title and
     * left finished calls persisted as pending rows with nothing in them.
     */
    @Test
    fun transcriptStoreMergesSparseToolCallUpdatesInsteadOfOverwriting() {
        val root = createTempDirectory("andy-acp-tool-merge").toFile()
        try {
            val store = AcpTranscriptStore(fileFor = { id -> root.resolve(id).resolve("transcript.jsonl") })
            store.upsert(
                "task-1",
                AgentEvent.ToolCall(
                    atMillis = 1,
                    toolName = "Read File",
                    summary = "",
                    detail = "{}",
                    toolCallId = "call-1",
                    kind = AgentToolKind.Read,
                    state = AgentToolState.Pending,
                ),
            )
            store.upsert(
                "task-1",
                AgentEvent.ToolCall(
                    atMillis = 2,
                    toolName = "tool",
                    summary = "content=alpha line",
                    detail = """{"content":"alpha line"}""",
                    toolCallId = "call-1",
                    kind = AgentToolKind.Read,
                    state = AgentToolState.Completed,
                ),
            )

            val row = assertIs<AgentEvent.ToolCall>(store.load("task-1").single())
            assertEquals("Read File", row.toolName)
            assertEquals(AgentToolState.Completed, row.state)
            assertTrue(row.detail.contains("alpha line"), "lost the output: ${row.detail}")
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * The real cursor-agent sequence for an edit: a pending row with a bare title and no arguments,
     * then a completing update whose `content` carries the path plus before/after text. The stored
     * row has to end up with both, and in the shape the transcript's diff viewer parses.
     */
    @Test
    fun editToolCallKeepsItsPathAndDiffAcrossUpdates() {
        val root = createTempDirectory("andy-acp-edit-diff").toFile()
        try {
            val store = AcpTranscriptStore(fileFor = { id -> root.resolve(id).resolve("transcript.jsonl") })
            val callId = "call-b6cc1564-0"
            store.upsert(
                "task-1",
                AcpEventMapper.map(
                    SessionUpdate.ToolCall(
                        toolCallId = com.agentclientprotocol.model.ToolCallId(callId),
                        title = "Edit File",
                        kind = ToolKind.EDIT,
                        status = ToolCallStatus.PENDING,
                        rawInput = buildJsonObject { },
                    ),
                )!!,
            )
            store.upsert(
                "task-1",
                AcpEventMapper.map(
                    SessionUpdate.ToolCallUpdate(
                        toolCallId = com.agentclientprotocol.model.ToolCallId(callId),
                        status = ToolCallStatus.COMPLETED,
                        content = listOf(
                            com.agentclientprotocol.model.ToolCallContent.Diff(
                                path = "/tmp/probe/notes.txt",
                                oldText = "alpha line\n",
                                newText = "ALPHA line\n",
                            ),
                        ),
                    ),
                )!!,
            )

            val row = assertIs<AgentEvent.ToolCall>(store.load("task-1").single())
            assertEquals("Edit File", row.toolName)
            assertEquals(AgentToolState.Completed, row.state)
            val parsed = assertIs<app.andy.domain.ToolCallFileContent>(
                app.andy.domain.parseToolCallFileContent(row.detail),
            )
            assertEquals("/tmp/probe/notes.txt", parsed.path)
            assertEquals("alpha line", parsed.oldText?.trim())
            assertEquals("ALPHA line", parsed.newText?.trim())
            assertTrue(parsed.hasDiff)
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
