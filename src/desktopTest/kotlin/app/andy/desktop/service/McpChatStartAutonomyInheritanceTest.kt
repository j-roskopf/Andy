package app.andy.desktop.service

import app.andy.desktop.service.agents.mcpUrlWithCallerTaskId
import app.andy.model.AgentAutonomy
import app.andy.model.AgentKind
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

class McpChatStartAutonomyInheritanceTest {
    @Test
    fun mcpUrlWithCallerTaskIdAppendsQuery() {
        assertEquals(
            "http://127.0.0.1:8565/mcp-http?andyTaskId=task-1",
            mcpUrlWithCallerTaskId("http://127.0.0.1:8565/mcp-http", "task-1"),
        )
        assertEquals(
            "http://127.0.0.1:8565/mcp?foo=1&andyTaskId=task-1",
            mcpUrlWithCallerTaskId("http://127.0.0.1:8565/mcp?foo=1", "task-1"),
        )
        assertEquals(
            "http://127.0.0.1:8565/mcp-http",
            mcpUrlWithCallerTaskId("http://127.0.0.1:8565/mcp-http", "  "),
        )
    }

    @Test
    fun chatStartInheritsAutonomyFromCallerTaskIdWhenOmitted() = runBlocking {
        withHarness(parentAutonomy = AgentAutonomy.Full) { fake, socket ->
            val (isError, text) = callTool(
                socket,
                "chat.start",
                mapOf(
                    "prompt" to JsonPrimitive("keep going"),
                    "agent" to JsonPrimitive("Codex"),
                    "callerTaskId" to JsonPrimitive("parent-1"),
                ),
            )
            assertFalse(isError, text)
            assertEquals(AgentAutonomy.Full, fake.startCalls.single().draft.autonomy)
        }
    }

    @Test
    fun chatStartInheritsAutonomyFromSessionCallerTaskIdWhenOmitted() = runBlocking {
        withHarness(
            parentAutonomy = AgentAutonomy.Full,
            sessionCallerTaskId = "parent-1",
        ) { fake, socket ->
            val (isError, text) = callTool(
                socket,
                "chat.start",
                mapOf(
                    "prompt" to JsonPrimitive("keep going"),
                    "agent" to JsonPrimitive("Codex"),
                ),
            )
            assertFalse(isError, text)
            assertEquals(AgentAutonomy.Full, fake.startCalls.single().draft.autonomy)
        }
    }

    @Test
    fun chatStartExplicitAutonomyWinsOverCaller() = runBlocking {
        withHarness(parentAutonomy = AgentAutonomy.Full) { fake, socket ->
            val (isError, text) = callTool(
                socket,
                "chat.start",
                mapOf(
                    "prompt" to JsonPrimitive("verify only"),
                    "agent" to JsonPrimitive("Codex"),
                    "callerTaskId" to JsonPrimitive("parent-1"),
                    "autonomy" to JsonPrimitive("ReadOnly"),
                ),
            )
            assertFalse(isError, text)
            assertEquals(AgentAutonomy.ReadOnly, fake.startCalls.single().draft.autonomy)
        }
    }

    @Test
    fun chatStartDefaultsToStandardWithoutCaller() = runBlocking {
        withHarness(parentAutonomy = AgentAutonomy.Full, seedParent = false) { fake, socket ->
            val (isError, text) = callTool(
                socket,
                "chat.start",
                mapOf(
                    "prompt" to JsonPrimitive("solo"),
                    "agent" to JsonPrimitive("Codex"),
                ),
            )
            assertFalse(isError, text)
            assertEquals(AgentAutonomy.Standard, fake.startCalls.single().draft.autonomy)
        }
    }

    private suspend fun withHarness(
        parentAutonomy: AgentAutonomy,
        sessionCallerTaskId: String? = null,
        seedParent: Boolean = true,
        block: suspend (FakeAutonomyAgentRunService, File) -> Unit,
    ) {
        val dir = File.createTempFile("andy-mcp-autonomy", null).also {
            it.delete()
            it.mkdirs()
        }
        val socketPath = File(dir, "andyd.sock")
        val fake = FakeAutonomyAgentRunService()
        if (seedParent) {
            fake.seedParent("parent-1", parentAutonomy)
        }
        val unixServer = McpUnixSocketServer(socketPath) {
            Server(
                serverInfo = Implementation("andy-test", "1.0.0"),
                options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))),
            ).apply {
                registerAgentProjectTools(
                    fake,
                    UnavailableProjectWorkflowService,
                    callerTaskId = sessionCallerTaskId,
                )
            }
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

private class FakeAutonomyAgentRunService : AgentRunService by UnavailableAgentRunService {
    data class StartCall(val draft: AgentTaskDraft)

    private val _tasks = MutableStateFlow<List<AgentTask>>(emptyList())
    override val tasks: StateFlow<List<AgentTask>> = _tasks
    val startCalls = mutableListOf<StartCall>()

    fun seedParent(id: String, autonomy: AgentAutonomy) {
        _tasks.value = _tasks.value + AgentTask(
            id = id,
            title = "parent",
            prompt = "loop",
            agent = AgentKind.Codex,
            status = AgentStatus.Working,
            createdAtMillis = 1,
            autonomy = autonomy,
            attachAndyMcp = true,
        )
    }

    override suspend fun createAndStart(draft: AgentTaskDraft): AgentTask {
        startCalls += StartCall(draft)
        val task = AgentTask(
            id = "child-${startCalls.size}",
            title = draft.title,
            prompt = draft.prompt,
            agent = draft.agent,
            status = AgentStatus.Working,
            createdAtMillis = 1,
            autonomy = draft.autonomy,
            attachAndyMcp = draft.attachAndyMcp,
        )
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
