package app.andy.desktop.service

import app.andy.model.AgentEvent
import app.andy.model.AgentKind
import app.andy.model.AgentLaneKind
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Covers [chat.subscribe] streaming over a real [McpUnixSocketServer] plus
 * `imagePaths` plumbing/validation on chat.start / chat.resume / chat.queue_follow_up.
 */
class McpChatSubscribeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun subscribePushesBacklogThenLiveEvents() = runBlocking {
        withMcpHarness { fake, socketPath ->
            fake.seedTask("task-1", AgentStatus.Working)
            fake.appendEvent("task-1", AgentEvent.UserMessage(1, "hello"))

            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(socketPath.toPath()))
                val reader = BufferedReader(Channels.newReader(channel, Charsets.UTF_8))
                val writer = BufferedWriter(Channels.newWriter(channel, Charsets.UTF_8))
                handshake(writer, reader)

                writer.write(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 2)
                        put("method", "tools/call")
                        put(
                            "params",
                            buildJsonObject {
                                put("name", "chat.subscribe")
                                put("arguments", buildJsonObject { put("taskId", "task-1") })
                            },
                        )
                    }.toString(),
                )
                writer.write("\n")
                writer.flush()

                val backlog = withTimeout(10_000) {
                    awaitSubscribeNotification(reader)
                }
                assertEquals("task-1", backlog.string("taskId"))
                assertEquals(1, backlog.array("events").size)
                assertEquals("user", backlog.array("events")[0].jsonObject.string("type"))

                withTimeout(10_000) {
                    while (ChatSubscribeMetrics.activeCollectorCount() < 1) delay(25)
                }

                fake.appendEvent("task-1", AgentEvent.AssistantText(2, "world"))
                val live = withTimeout(10_000) {
                    awaitSubscribeNotification(reader)
                }
                assertEquals(1, live.array("events").size)
                assertEquals("assistant", live.array("events")[0].jsonObject.string("type"))
                assertEquals("world", live.array("events")[0].jsonObject.string("text"))

                // Closing the socket + a subsequent event wakes the collector; the failed
                // push cancels the lifetime token (SDK transport close can hang on writers).
                disconnectClient(channel)
                fake.appendEvent("task-1", AgentEvent.AssistantText(3, "after-disconnect"))
                withTimeout(10_000) {
                    while (ChatSubscribeMetrics.activeCollectorCount() > 0) delay(25)
                }
            }
        }
    }

    @Test
    fun subscribePushesInPlaceCoalescedReplacements() = runBlocking {
        withMcpHarness { fake, socketPath ->
            fake.seedTask("task-coalesce", AgentStatus.Working)
            fake.appendEvent("task-coalesce", AgentEvent.UserMessage(1, "hi"))
            fake.appendEvent(
                "task-coalesce",
                AgentEvent.AssistantText(2, "hel", isStreamDelta = true),
            )

            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(socketPath.toPath()))
                val reader = BufferedReader(Channels.newReader(channel, Charsets.UTF_8))
                val writer = BufferedWriter(Channels.newWriter(channel, Charsets.UTF_8))
                handshake(writer, reader)

                writer.write(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 2)
                        put("method", "tools/call")
                        put(
                            "params",
                            buildJsonObject {
                                put("name", "chat.subscribe")
                                put("arguments", buildJsonObject { put("taskId", "task-coalesce") })
                            },
                        )
                    }.toString(),
                )
                writer.write("\n")
                writer.flush()

                val backlog = withTimeout(10_000) { awaitSubscribeNotification(reader) }
                assertEquals(2, backlog.array("events").size)

                withTimeout(10_000) {
                    while (ChatSubscribeMetrics.activeCollectorCount() < 1) delay(25)
                }

                // Same-size in-place coalesce (stream delta append) must still notify.
                fake.replaceEvent(
                    "task-coalesce",
                    1,
                    AgentEvent.AssistantText(2, "hello", isStreamDelta = true),
                )
                val live = withTimeout(10_000) { awaitSubscribeNotification(reader) }
                assertEquals(1, live.int("replaceFrom"))
                assertEquals(1, live.array("events").size)
                assertEquals("assistant", live.array("events")[0].jsonObject.string("type"))
                assertEquals("hello", live.array("events")[0].jsonObject.string("text"))

                disconnectClient(channel)
                fake.appendEvent("task-coalesce", AgentEvent.AssistantText(3, "after"))
                withTimeout(10_000) {
                    while (ChatSubscribeMetrics.activeCollectorCount() > 0) delay(25)
                }
            }
        }
    }

    @Test
    fun subscribeEndsWhenTaskReachesTerminalStatus() = runBlocking {
        withMcpHarness { fake, socketPath ->
            fake.seedTask("done-1", AgentStatus.Working)
            fake.appendEvent("done-1", AgentEvent.UserMessage(1, "hi"))

            val resultText = async(Dispatchers.IO) {
                callSubscribeUntilResult(socketPath, "done-1") {
                    fake.setStatus("done-1", AgentStatus.Done)
                }
            }
            val text = withTimeout(15_000) { resultText.await() }
            assertTrue(text.contains("\"ok\":true") || text.contains("\"ok\": true"), text)
            assertTrue(text.contains("\"reason\":\"terminal\"") || text.contains("\"reason\": \"terminal\""), text)
            withTimeout(10_000) {
                while (ChatSubscribeMetrics.activeCollectorCount() > 0) delay(25)
            }
        }
    }

    @Test
    fun subscribeEndsWithErrorReasonWhenTaskFails() = runBlocking {
        withMcpHarness { fake, socketPath ->
            fake.seedTask("err-1", AgentStatus.Working)
            fake.appendEvent("err-1", AgentEvent.UserMessage(1, "hi"))

            val resultText = async(Dispatchers.IO) {
                callSubscribeUntilResult(socketPath, "err-1") {
                    // Error without TaskError/TaskResult — status alone must signal failure.
                    fake.setStatus("err-1", AgentStatus.Error)
                }
            }
            val text = withTimeout(15_000) { resultText.await() }
            assertTrue(text.contains("\"ok\":true") || text.contains("\"ok\": true"), text)
            assertTrue(text.contains("\"reason\":\"error\"") || text.contains("\"reason\": \"error\""), text)
            assertFalse(text.contains("\"reason\":\"terminal\""), text)
            withTimeout(10_000) {
                while (ChatSubscribeMetrics.activeCollectorCount() > 0) delay(25)
            }
        }
    }

    @Test
    fun resumeForwardsImagePaths() = runBlocking {
        withMcpHarness { fake, socketPath ->
            val png = File.createTempFile("andy-img", ".png").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            try {
                val (isError, text) = callRawTool(
                    socketPath,
                    "chat.resume",
                    mapOf(
                        "taskId" to JsonPrimitive("task-1"),
                        "followUp" to JsonPrimitive("look"),
                        "imagePaths" to JsonArray(listOf(JsonPrimitive(png.absolutePath))),
                    ),
                )
                assertFalse(isError, text)
                withTimeout(10_000) { while (fake.resumeCalls.isEmpty()) delay(25) }
                assertEquals(listOf(png.absolutePath), fake.resumeCalls.single().imagePaths)
            } finally {
                png.delete()
            }
        }
    }

    @Test
    fun queueFollowUpForwardsImagePaths() = runBlocking {
        withMcpHarness { fake, socketPath ->
            val jpg = File.createTempFile("andy-img", ".jpg").also { it.writeBytes(byteArrayOf(9)) }
            try {
                val (isError, text) = callRawTool(
                    socketPath,
                    "chat.queue_follow_up",
                    mapOf(
                        "taskId" to JsonPrimitive("task-1"),
                        "followUp" to JsonPrimitive("queued"),
                        "imagePaths" to JsonArray(listOf(JsonPrimitive(jpg.absolutePath))),
                    ),
                )
                assertFalse(isError, text)
                withTimeout(10_000) { while (fake.queueCalls.isEmpty()) delay(25) }
                assertEquals(listOf(jpg.absolutePath), fake.queueCalls.single().imagePaths)
            } finally {
                jpg.delete()
            }
        }
    }

    @Test
    fun chatStartForwardsImagePaths() = runBlocking {
        withMcpHarness { fake, socketPath ->
            val webp = File.createTempFile("andy-img", ".webp").also { it.writeBytes(byteArrayOf(4)) }
            try {
                val (isError, text) = callRawTool(
                    socketPath,
                    "chat.start",
                    mapOf(
                        "prompt" to JsonPrimitive("with image"),
                        "agent" to JsonPrimitive("Codex"),
                        "imagePaths" to JsonArray(listOf(JsonPrimitive(webp.absolutePath))),
                    ),
                )
                assertFalse(isError, text)
                withTimeout(10_000) { while (fake.startCalls.isEmpty()) delay(25) }
                assertEquals(listOf(webp.absolutePath), fake.startCalls.single().imagePaths)
            } finally {
                webp.delete()
            }
        }
    }

    @Test
    fun resumeRejectsInvalidImageExtension() = runBlocking {
        withMcpHarness { fake, socketPath ->
            val txt = File.createTempFile("andy-img", ".txt").also { it.writeText("nope") }
            try {
                val (isError, text) = callRawTool(
                    socketPath,
                    "chat.resume",
                    mapOf(
                        "taskId" to JsonPrimitive("task-1"),
                        "followUp" to JsonPrimitive("look"),
                        "imagePaths" to JsonArray(listOf(JsonPrimitive(txt.absolutePath))),
                    ),
                )
                assertTrue(isError, text)
                assertTrue(text.contains("unsupported image type") || text.contains(".txt"), text)
                assertTrue(fake.resumeCalls.isEmpty())
            } finally {
                txt.delete()
            }
        }
    }

    @Test
    fun resumeRejectsMissingImagePath() = runBlocking {
        withMcpHarness { fake, socketPath ->
            val missing = File("/tmp/andy-missing-image-${System.nanoTime()}.png")
            val (isError, text) = callRawTool(
                socketPath,
                "chat.resume",
                mapOf(
                    "taskId" to JsonPrimitive("task-1"),
                    "followUp" to JsonPrimitive("look"),
                    "imagePaths" to JsonArray(listOf(JsonPrimitive(missing.absolutePath))),
                ),
            )
            assertTrue(isError, text)
            assertTrue(text.contains("not found") || text.contains("not a regular file"), text)
            assertTrue(fake.resumeCalls.isEmpty())
        }
    }

    @Test
    fun resumeRejectsNonArrayImagePaths() = runBlocking {
        withMcpHarness { fake, socketPath ->
            val (isError, text) = callRawTool(
                socketPath,
                "chat.resume",
                mapOf(
                    "taskId" to JsonPrimitive("task-1"),
                    "followUp" to JsonPrimitive("look"),
                    "imagePaths" to JsonPrimitive("/tmp/not-an-array.png"),
                ),
            )
            assertTrue(isError, text)
            assertTrue(text.contains("imagePaths must be an array"), text)
            assertTrue(fake.resumeCalls.isEmpty())
        }
    }

    @Test
    fun resumeRejectsNullImagePathEntries() = runBlocking {
        withMcpHarness { fake, socketPath ->
            val (isError, text) = callRawTool(
                socketPath,
                "chat.resume",
                mapOf(
                    "taskId" to JsonPrimitive("task-1"),
                    "followUp" to JsonPrimitive("look"),
                    "imagePaths" to JsonArray(listOf(JsonNull)),
                ),
            )
            assertTrue(isError, text)
            assertTrue(text.contains("imagePaths[0] must be a string"), text)
            assertTrue(fake.resumeCalls.isEmpty())
        }
    }

    /** Half-close output before close so Windows UDS delivers EOF to the server reader. */
    private fun disconnectClient(channel: SocketChannel) {
        runCatching { channel.shutdownOutput() }
        runCatching { channel.close() }
    }

    private suspend fun withMcpHarness(
        block: suspend (FakeSubscribeAgentRunService, File) -> Unit,
    ) {
        val dir = File.createTempFile("andy-mcp-subscribe", null).also {
            it.delete()
            it.mkdirs()
        }
        val socketPath = File(dir, "andyd.sock")
        val fake = FakeSubscribeAgentRunService()
        val unixServer = McpUnixSocketServer(socketPath) {
            Server(
                serverInfo = Implementation("andy-test", "1.0.0"),
                options = ServerOptions(
                    capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
                ),
            ).apply { registerAgentProjectTools(fake, UnavailableProjectWorkflowService) }
        }
        try {
            unixServer.startBlocking()
            withTimeout(10_000) { while (!socketPath.exists()) delay(25) }
            block(fake, socketPath)
        } finally {
            unixServer.stopBlocking()
            dir.deleteRecursively()
        }
    }

    private fun handshake(writer: BufferedWriter, reader: BufferedReader) {
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
    }

    private fun awaitSubscribeNotification(reader: BufferedReader): JsonObject {
        while (true) {
            val line = reader.readLine() ?: error("socket closed before subscribe notification")
            val root = json.parseToJsonElement(line).jsonObject
            if (root["id"] != null) continue
            val method = root["method"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (method != ChatSubscribeNotificationMethod) continue
            val params = root["params"]?.jsonObject ?: error("missing params: $line")
            return params["_meta"]?.jsonObject
                ?: params["meta"]?.jsonObject
                ?: error("missing _meta on subscribe notification: $line")
        }
    }

    private suspend fun callSubscribeUntilResult(
        socketPath: File,
        taskId: String,
        afterSubscribe: suspend () -> Unit,
    ): String = withContext(Dispatchers.IO) {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(socketPath.toPath()))
            val reader = BufferedReader(Channels.newReader(channel, Charsets.UTF_8))
            val writer = BufferedWriter(Channels.newWriter(channel, Charsets.UTF_8))
            handshake(writer, reader)
            writer.write(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", 2)
                    put("method", "tools/call")
                    put(
                        "params",
                        buildJsonObject {
                            put("name", "chat.subscribe")
                            put("arguments", buildJsonObject { put("taskId", taskId) })
                        },
                    )
                }.toString(),
            )
            writer.write("\n")
            writer.flush()

            // Wait for first notification (backlog), then flip status.
            awaitSubscribeNotification(reader)
            afterSubscribe()

            while (true) {
                val line = reader.readLine() ?: error("no final result")
                val root = json.parseToJsonElement(line).jsonObject
                val idEl = root["id"]
                val matchesId = idEl?.jsonPrimitive?.contentOrNull == "2" ||
                    idEl?.jsonPrimitive?.contentOrNull?.toIntOrNull() == 2 ||
                    idEl?.toString() == "2"
                if (!matchesId) continue
                val result = root["result"]?.jsonObject
                return@use result
                    ?.get("content")
                    ?.jsonArray
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
    }

    private suspend fun callRawTool(
        socketPath: File,
        name: String,
        arguments: Map<String, JsonElement>,
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(socketPath.toPath()))
            val reader = BufferedReader(Channels.newReader(channel, Charsets.UTF_8))
            val writer = BufferedWriter(Channels.newWriter(channel, Charsets.UTF_8))
            handshake(writer, reader)
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
            val line = reader.readLine() ?: error("no response for $name")
            val root = json.parseToJsonElement(line).jsonObject
            val rpcError = root["error"]?.jsonObject
            if (rpcError != null) {
                return@use true to (rpcError["message"]?.jsonPrimitive?.contentOrNull ?: rpcError.toString())
            }
            val result = root["result"]?.jsonObject ?: return@use (false to "")
            val text = result["content"]?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull
                .orEmpty()
            val isError = result["isError"]?.jsonPrimitive?.booleanOrNull == true
            isError to text
        }
    }
}

private fun JsonObject.string(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.int(key: String): Int =
    this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        ?: error("missing int $key in $this")

private fun JsonObject.array(key: String): List<JsonElement> =
    this[key]?.jsonArray?.toList().orEmpty()

private class FakeSubscribeAgentRunService : AgentRunService by UnavailableAgentRunService {
    data class StartCall(val imagePaths: List<String>)
    data class ResumeCall(val taskId: String, val followUp: String, val imagePaths: List<String>)
    data class QueueCall(val taskId: String, val followUp: String, val imagePaths: List<String>)

    private val _tasks = MutableStateFlow<List<AgentTask>>(emptyList())
    private val eventFlows = mutableMapOf<String, MutableStateFlow<List<AgentEvent>>>()

    override val tasks: StateFlow<List<AgentTask>> = _tasks
    val startCalls = mutableListOf<StartCall>()
    val resumeCalls = mutableListOf<ResumeCall>()
    val queueCalls = mutableListOf<QueueCall>()

    fun seedTask(id: String, status: AgentStatus) {
        _tasks.value = _tasks.value.filterNot { it.id == id } + AgentTask(
            id = id,
            title = id,
            prompt = "",
            agent = AgentKind.Codex,
            lane = AgentLaneKind.Acp,
            status = status,
            createdAtMillis = 1,
        )
        eventFlows.getOrPut(id) { MutableStateFlow(emptyList()) }
    }

    fun appendEvent(taskId: String, event: AgentEvent) {
        val flow = eventFlows.getOrPut(taskId) { MutableStateFlow(emptyList()) }
        flow.value = flow.value + event
    }

    /** Same-size in-place replacement — mirrors ACP stream/tool coalescing. */
    fun replaceEvent(taskId: String, index: Int, event: AgentEvent) {
        val flow = eventFlows.getOrPut(taskId) { MutableStateFlow(emptyList()) }
        val next = flow.value.toMutableList()
        next[index] = event
        flow.value = next
    }

    fun setStatus(taskId: String, status: AgentStatus) {
        _tasks.value = _tasks.value.map {
            if (it.id == taskId) it.copy(status = status) else it
        }
    }

    override fun events(taskId: String): StateFlow<List<AgentEvent>> =
        eventFlows.getOrPut(taskId) { MutableStateFlow(emptyList()) }

    override suspend fun createAndStart(draft: AgentTaskDraft): AgentTask {
        startCalls += StartCall(draft.imagePaths)
        val task = AgentTask(
            id = "fake-task-${startCalls.size}",
            title = draft.title,
            prompt = draft.prompt,
            agent = draft.agent,
            lane = AgentLaneKind.Acp,
            status = AgentStatus.Working,
            createdAtMillis = 1,
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
        resumeCalls += ResumeCall(taskId, followUp, imagePaths)
    }

    override fun queueFollowUp(
        taskId: String,
        followUp: String,
        imagePaths: List<String>,
        skills: List<AgentSkill>,
        contextBundleIds: List<String>,
        provenance: app.andy.model.AgentContextualProvenance?,
    ) {
        queueCalls += QueueCall(taskId, followUp, imagePaths)
    }
}
