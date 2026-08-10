package app.andy.desktop.service

import app.andy.model.AgentSkill
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.model.AgentTaskDraft
import app.andy.service.AgentRunService
import app.andy.service.UnavailableAgentRunService
import app.andy.service.UnavailableProjectWorkflowService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

class McpAgentToolsWorktreeTest {
    @Test
    fun chatStartExistingWorktreePathTakesPrecedenceOverUseWorktree() = runBlocking {
        withHarness { fake, socket ->
            val (isError, text) = callTool(
                socket,
                "chat.start",
                mapOf(
                    "prompt" to JsonPrimitive("do the thing"),
                    "agent" to JsonPrimitive("Codex"),
                    "directory" to JsonPrimitive("/tmp/project"),
                    "useWorktree" to JsonPrimitive(true),
                    "existingWorktreePath" to JsonPrimitive("/tmp/project/.andy-worktrees/x"),
                ),
            )
            assertFalse(isError, text)
            val draft = fake.startCalls.single().draft
            assertFalse(draft.useWorktree, "reuse must not request a new worktree create")
            assertEquals("/tmp/project/.andy-worktrees/x", draft.existingWorktreePath)
            assertEquals("/tmp/project", draft.directory)
            assertTrue(text.contains("fake-task-1"))
        }
    }

    @Test
    fun chatStartUseWorktreeWithoutDirectorySurfacesCreateError() = runBlocking {
        withHarness(failWorktreeWithoutDirectory = true) { fake, socket ->
            val (isError, text) = callTool(
                socket,
                "chat.start",
                mapOf(
                    "prompt" to JsonPrimitive("do the thing"),
                    "agent" to JsonPrimitive("Codex"),
                    "useWorktree" to JsonPrimitive(true),
                ),
            )
            assertTrue(isError, text)
            assertTrue(text.contains("project directory is required to create a worktree"), text)
            assertEquals(1, fake.startCalls.size)
        }
    }

    @Test
    fun chatStartInvalidExistingWorktreePathSurfacesError() = runBlocking {
        withHarness(failInvalidExistingWorktree = true) { fake, socket ->
            val (isError, text) = callTool(
                socket,
                "chat.start",
                mapOf(
                    "prompt" to JsonPrimitive("do the thing"),
                    "agent" to JsonPrimitive("Codex"),
                    "directory" to JsonPrimitive("/tmp/project"),
                    "existingWorktreePath" to JsonPrimitive("/tmp/project/.andy-worktrees/missing"),
                ),
            )
            assertTrue(isError, text)
            assertTrue(text.contains("existing worktree path is missing or not a directory"), text)
            assertEquals(1, fake.startCalls.size)
            assertEquals(
                "/tmp/project/.andy-worktrees/missing",
                fake.startCalls.single().draft.existingWorktreePath,
            )
        }
    }

    @Test
    fun chatStartForwardsAttachAndyMcp() = runBlocking {
        withHarness { fake, socket ->
            val (isError, text) = callTool(
                socket,
                "chat.start",
                mapOf(
                    "prompt" to JsonPrimitive("/andy-loop keep going"),
                    "agent" to JsonPrimitive("Codex"),
                    "attachAndyMcp" to JsonPrimitive(true),
                ),
            )
            assertFalse(isError, text)
            assertTrue(fake.startCalls.single().draft.attachAndyMcp)
        }
    }

    private suspend fun withHarness(
        failWorktreeWithoutDirectory: Boolean = false,
        failInvalidExistingWorktree: Boolean = false,
        block: suspend (FakeWorktreeAgentRunService, File) -> Unit,
    ) {
        val dir = File.createTempFile("andy-mcp-worktree", null).also {
            it.delete()
            it.mkdirs()
        }
        val socketPath = File(dir, "andyd.sock")
        val fake = FakeWorktreeAgentRunService(
            failWorktreeWithoutDirectory = failWorktreeWithoutDirectory,
            failInvalidExistingWorktree = failInvalidExistingWorktree,
        )
        val unixServer = McpUnixSocketServer(socketPath) {
            Server(
                serverInfo = Implementation("andy-test", "1.0.0"),
                options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))),
            ).apply { registerAgentProjectTools(fake, UnavailableProjectWorkflowService) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            unixServer.startBlocking()
            withTimeout(10_000) { while (!socketPath.exists()) delay(25) }
            block(fake, socketPath)
        } finally {
            unixServer.stopBlocking()
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}

private suspend fun callTool(
    socketPath: File,
    name: String,
    arguments: Map<String, kotlinx.serialization.json.JsonElement>,
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
                        put("arguments", kotlinx.serialization.json.JsonObject(arguments))
                    },
                )
            }.toString(),
        )
        writer.write("\n")
        writer.flush()

        val line = reader.readLine() ?: error("no response for $name")
        val root = json.parseToJsonElement(line).jsonObject
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

private class FakeWorktreeAgentRunService(
    private val failWorktreeWithoutDirectory: Boolean = false,
    private val failInvalidExistingWorktree: Boolean = false,
) : AgentRunService by UnavailableAgentRunService {
    data class StartCall(val draft: AgentTaskDraft)

    private val _tasks = MutableStateFlow<List<AgentTask>>(emptyList())
    override val tasks: StateFlow<List<AgentTask>> = _tasks
    val startCalls = mutableListOf<StartCall>()

    override suspend fun createAndStart(draft: AgentTaskDraft): AgentTask {
        startCalls += StartCall(draft)
        // Mirror DesktopAgentRunService: missing directory + useWorktree / invalid reuse path
        // return Error-shaped tasks rather than throwing, so chat.start surfaces isError.
        val task = when {
            failWorktreeWithoutDirectory &&
                draft.useWorktree &&
                draft.directory.isNullOrBlank() &&
                draft.existingWorktreePath == null -> {
                AgentTask(
                    id = "fake-task-${startCalls.size}",
                    title = draft.title,
                    prompt = draft.prompt,
                    agent = draft.agent,
                    projectId = draft.projectId,
                    status = AgentStatus.Error,
                    errorMessage = "a project directory is required to create a worktree",
                    createdAtMillis = 1,
                    useWorktree = draft.useWorktree,
                )
            }
            failInvalidExistingWorktree &&
                !draft.existingWorktreePath.isNullOrBlank() -> {
                AgentTask(
                    id = "fake-task-${startCalls.size}",
                    title = draft.title,
                    prompt = draft.prompt,
                    agent = draft.agent,
                    projectId = draft.projectId,
                    cwd = null,
                    status = AgentStatus.Error,
                    errorMessage = "existing worktree path is missing or not a directory",
                    createdAtMillis = 1,
                    useWorktree = draft.useWorktree,
                    worktreePath = draft.existingWorktreePath,
                )
            }
            else -> {
                AgentTask(
                    id = "fake-task-${startCalls.size}",
                    title = draft.title,
                    prompt = draft.prompt,
                    agent = draft.agent,
                    projectId = draft.projectId,
                    status = AgentStatus.Working,
                    createdAtMillis = 1,
                    useWorktree = draft.useWorktree,
                    worktreePath = draft.existingWorktreePath
                        ?: draft.directory?.let { "$it/.andy-worktrees/fake" }?.takeIf { draft.useWorktree },
                    attachAndyMcp = draft.attachAndyMcp,
                )
            }
        }
        _tasks.value = _tasks.value + task
        return task
    }

    @Suppress("UNUSED_PARAMETER")
    override fun resume(
        taskId: String,
        followUp: String,
        imagePaths: List<String>,
        skills: List<AgentSkill>,
        contextBundleIds: List<String>,
        provenance: app.andy.model.AgentContextualProvenance?,
    ) = Unit
}
