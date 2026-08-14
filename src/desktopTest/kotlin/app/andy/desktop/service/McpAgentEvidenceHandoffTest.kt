package app.andy.desktop.service

import app.andy.model.AgentContextualProvenance
import app.andy.model.AgentKind
import app.andy.model.AgentLaneKind
import app.andy.model.AgentSkill
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.model.AgentTaskDraft
import app.andy.model.ContextualActionKind
import app.andy.service.AgentRunService
import app.andy.service.UnavailableAgentRunService
import app.andy.service.UnavailableProjectWorkflowService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Covers agent-handoff MCP transport (plan §4/§5): [registerAgentProjectTools] accepting
 * managed `contextBundleIds` for chat.start/chat.resume, rejecting raw filesystem path
 * parameters, and [McpAgentRunClient] forwarding bundle ids without ever sending a local path.
 */
class McpAgentEvidenceHandoffTest {
    @Test
    fun chatStartForwardsContextBundleIdsAndProvenanceToTheAgentRunService() = runBlocking {
        withMcpHarness { fakeAgentRuns, client, _ ->
            val provenance = AgentContextualProvenance(
                sourceKind = ContextualActionKind.ExplainCrash,
                investigationId = "inv-1",
                crashId = "crash-1",
            )
            client.createAndStart(
                AgentTaskDraft(
                    title = "investigate crash",
                    prompt = "why did this crash",
                    agent = AgentKind.Codex,
                    projectId = null,
                    contextBundleIds = listOf("bundle-a", "bundle-b"),
                    provenance = provenance,
                ),
            )
            withTimeout(10_000) { while (fakeAgentRuns.startCalls.isEmpty()) delay(25) }
            val call = fakeAgentRuns.startCalls.single()
            assertEquals(listOf("bundle-a", "bundle-b"), call.contextBundleIds)
            assertEquals(provenance, call.provenance)
        }
    }

    @Test
    fun chatStartForwardsAttachAndyMcpThroughDaemonClient() = runBlocking {
        withMcpHarness { fakeAgentRuns, client, _ ->
            client.createAndStart(
                AgentTaskDraft(
                    title = "orchestrate",
                    prompt = "/andy-handoff do the thing",
                    agent = AgentKind.Codex,
                    projectId = null,
                    attachAndyMcp = true,
                ),
            )
            withTimeout(10_000) { while (fakeAgentRuns.startCalls.isEmpty()) delay(25) }
            val call = fakeAgentRuns.startCalls.single()
            assertTrue(call.draft.attachAndyMcp, "attachAndyMcp must reach the daemon-side createAndStart")
            assertTrue(
                fakeAgentRuns.tasks.value.single().attachAndyMcp,
                "created task must retain attachAndyMcp for MCP attach on launch",
            )
        }
    }

    @Test
    fun chatStartForwardsExplicitLaneThroughDaemonClient() = runBlocking {
        withMcpHarness { fakeAgentRuns, client, _ ->
            client.createAndStart(
                AgentTaskDraft(
                    title = "terminal handoff seed",
                    prompt = "continue this in the terminal",
                    agent = AgentKind.Codex,
                    projectId = null,
                    lane = AgentLaneKind.Terminal,
                ),
            )
            withTimeout(10_000) { while (fakeAgentRuns.startCalls.isEmpty()) delay(25) }
            assertEquals(AgentLaneKind.Terminal, fakeAgentRuns.startCalls.single().draft.lane)
        }
    }

    @Test
    fun chatStartForwardsExistingWorktreePathThroughDaemonClient() = runBlocking {
        withMcpHarness { fakeAgentRuns, client, _ ->
            client.createAndStart(
                AgentTaskDraft(
                    title = "reuse wt",
                    prompt = "/andy-handoff continue in worktree",
                    agent = AgentKind.Codex,
                    projectId = "proj-1",
                    directory = "/tmp/project",
                    useWorktree = true,
                    existingWorktreePath = "/tmp/project/.andy-worktrees/x",
                    attachAndyMcp = true,
                    autonomy = app.andy.model.AgentAutonomy.ReadOnly,
                ),
            )
            withTimeout(10_000) { while (fakeAgentRuns.startCalls.isEmpty()) delay(25) }
            val draft = fakeAgentRuns.startCalls.single().draft
            assertEquals("/tmp/project/.andy-worktrees/x", draft.existingWorktreePath)
            assertEquals(app.andy.model.AgentAutonomy.ReadOnly, draft.autonomy)
            // chat.start clears useWorktree when reuse path is present (create must not run).
            assertFalse(draft.useWorktree)
        }
    }

    @Test
    fun chatResumeForwardsContextBundleIdsToTheAgentRunService() = runBlocking {
        withMcpHarness { fakeAgentRuns, client, _ ->
            client.resume("task-1", "check the logs", contextBundleIds = listOf("bundle-c"))
            withTimeout(10_000) { while (fakeAgentRuns.resumeCalls.isEmpty()) delay(25) }
            val call = fakeAgentRuns.resumeCalls.single()
            assertEquals("task-1", call.taskId)
            assertEquals("check the logs", call.followUp)
            assertEquals(listOf("bundle-c"), call.contextBundleIds)
        }
    }

    @Test
    fun chatStartRejectsRawEvidencePathArguments() = runBlocking {
        withMcpHarness { fakeAgentRuns, _, socketPath ->
            val (isError, text) = callRawTool(
                socketPath,
                "chat.start",
                mapOf(
                    "prompt" to JsonPrimitive("investigate"),
                    "agent" to JsonPrimitive("Codex"),
                    "evidencePath" to JsonPrimitive("/Users/someone/.andy/evidence/bundle-a"),
                ),
            )
            assertTrue(isError, "chat.start should reject a raw evidencePath argument: $text")
            assertTrue(text.contains("contextBundleIds"), "error should point callers at contextBundleIds: $text")
            assertTrue(fakeAgentRuns.startCalls.isEmpty(), "the raw path must never reach the agent run service")
        }
    }

    @Test
    fun chatResumeRejectsRawFilesystemPathArguments() = runBlocking {
        withMcpHarness { fakeAgentRuns, _, socketPath ->
            val (isError, text) = callRawTool(
                socketPath,
                "chat.resume",
                mapOf(
                    "taskId" to JsonPrimitive("task-1"),
                    "followUp" to JsonPrimitive("check this"),
                    "filePath" to JsonPrimitive("/etc/passwd"),
                ),
            )
            assertTrue(isError, "chat.resume should reject a raw filePath argument: $text")
            assertFalse(
                fakeAgentRuns.resumeCalls.any { it.taskId == "task-1" },
                "the raw path must never reach the agent run service",
            )
        }
    }

    /** Starts a real Unix-socket MCP server bound to a [FakeEvidenceAgentRunService] for the block. */
    private suspend fun withMcpHarness(
        block: suspend (FakeEvidenceAgentRunService, McpAgentRunClient, socketPath: File) -> Unit,
    ) {
        val dir = File.createTempFile("andy-mcp-evidence", null).also {
            it.delete()
            it.mkdirs()
        }
        val socketPath = File(dir, "andyd.sock")
        val fakeAgentRuns = FakeEvidenceAgentRunService()
        val unixServer = McpUnixSocketServer(socketPath) {
            Server(
                serverInfo = Implementation("andy-test", "1.0.0"),
                options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))),
            ).apply { registerAgentProjectTools(fakeAgentRuns, UnavailableProjectWorkflowService) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            unixServer.startBlocking()
            withTimeout(10_000) { while (!socketPath.exists()) delay(25) }
            val client = McpAgentRunClient(scope, socketPath)
            block(fakeAgentRuns, client, socketPath)
        } finally {
            unixServer.stopBlocking()
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}

/** Raw JSON-RPC `tools/call`, bypassing [McpAgentRunClient] so tests can send disallowed arguments. */
private suspend fun callRawTool(
    socketPath: File,
    name: String,
    arguments: Map<String, JsonElement>,
): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    val json = Json { ignoreUnknownKeys = true }
    SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
        channel.connect(UnixDomainSocketAddress.of(socketPath.toPath()))
        val reader = BufferedReader(Channels.newReader(channel, Charsets.UTF_8))
        val writer = BufferedWriter(Channels.newWriter(channel, Charsets.UTF_8))

        writer.write(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "initialize")
                put(
                    "params",
                    buildJsonObject {
                        put("protocolVersion", "2024-11-05")
                        put("capabilities", buildJsonObject {})
                        put(
                            "clientInfo",
                            buildJsonObject {
                                put("name", "andy-test")
                                put("version", "1.0.0")
                            },
                        )
                    },
                )
            }.toString(),
        )
        writer.write("\n")
        writer.flush()
        reader.readLine()

        writer.write(buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }.toString())
        writer.write("\n")
        writer.flush()

        writer.write(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 2)
                put("method", "tools/call")
                put(
                    "params",
                    buildJsonObject {
                        put("name", name)
                        put("arguments", JsonObject(arguments))
                    },
                )
            }.toString(),
        )
        writer.write("\n")
        writer.flush()

        // The SDK may emit a notification (for example tools/list_changed) before the call
        // response. Linux CI exposed this ordering more often than macOS, so match by request id
        // instead of assuming the next line is our response.
        val root = generateSequence { reader.readLine() }
            .map { json.parseToJsonElement(it).jsonObject }
            .firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == "2" }
            ?: error("no response for $name")
        val rpcError = root["error"]?.jsonObject
        if (rpcError != null) {
            return@use true to (rpcError["message"]?.jsonPrimitive?.contentOrNull ?: rpcError.toString())
        }
        val result = root["result"]?.jsonObject ?: return@use (false to "")
        val content = result["content"]?.jsonArray
        val text = content?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
        val isError = result["isError"]?.jsonPrimitive?.booleanOrNull == true
        isError to text
    }
}

/** Records chat.start/chat.resume calls so tests can assert exactly what reached [AgentRunService]. */
private class FakeEvidenceAgentRunService : AgentRunService by UnavailableAgentRunService {
    data class StartCall(
        val draft: AgentTaskDraft,
        val contextBundleIds: List<String>,
        val provenance: AgentContextualProvenance?,
    )
    data class ResumeCall(
        val taskId: String,
        val followUp: String,
        val contextBundleIds: List<String>,
        val imagePaths: List<String>,
    )

    private val _tasks = MutableStateFlow<List<AgentTask>>(emptyList())
    override val tasks: StateFlow<List<AgentTask>> = _tasks

    val startCalls = mutableListOf<StartCall>()
    val resumeCalls = mutableListOf<ResumeCall>()

    override suspend fun createAndStart(draft: AgentTaskDraft): AgentTask {
        startCalls += StartCall(draft, draft.contextBundleIds, draft.provenance)
        val task = AgentTask(
            id = "fake-task-${startCalls.size}",
            title = draft.title,
            prompt = draft.prompt,
            agent = draft.agent,
            projectId = draft.projectId,
            status = AgentStatus.Working,
            createdAtMillis = 1,
            attachAndyMcp = draft.attachAndyMcp,
            contextBundleIds = draft.contextBundleIds,
            provenance = draft.provenance,
        )
        _tasks.value = _tasks.value + task
        return task
    }

    override fun resume(
        taskId: String,
        followUp: String,
        imagePaths: List<String>,
        skills: List<AgentSkill>,
        contextBundleIds: List<String>,
        provenance: app.andy.model.AgentContextualProvenance?,
    ) {
        resumeCalls += ResumeCall(taskId, followUp, contextBundleIds, imagePaths)
    }
}
