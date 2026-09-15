package app.andy.desktop.service

import app.andy.desktop.service.agents.DesktopAgentTaskStore
import app.andy.service.UnavailableAgentRunService
import app.andy.service.UnavailableProjectWorkflowService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KanbanMcpToolsTest {
    @Test
    fun registersKanbanToolsAndHidesBoardDelete() = runBlocking {
        withHarness { _, socket ->
            val listed = listTools(socket)
            assertTrue(listed.contains("kanban.board_get"))
            assertTrue(listed.contains("kanban.card_create"))
            assertTrue(listed.contains("kanban.lane_delete"))
            assertTrue(listed.contains("chat.await"))
            assertFalse(listed.contains("kanban.board_delete"))
            assertTrue(agentProjectToolNames().contains("kanban.board_get"))
            assertFalse(agentProjectToolNames().contains("kanban.board_delete"))
        }
    }

    @Test
    fun laneDeleteRequiresConfirmAndRefusesNonEmpty() = runBlocking {
        withHarness { kanban, socket ->
            kanban.addCard("p1", "todo", "Keep me", "", emptyList())
            val (errNoConfirm, textNoConfirm) = callTool(
                socket,
                "kanban.lane_delete",
                mapOf(
                    "projectId" to JsonPrimitive("p1"),
                    "laneId" to JsonPrimitive("todo"),
                ),
            )
            assertTrue(errNoConfirm, textNoConfirm)
            assertTrue(textNoConfirm.contains("confirm"), textNoConfirm)

            val (errCards, textCards) = callTool(
                socket,
                "kanban.lane_delete",
                mapOf(
                    "projectId" to JsonPrimitive("p1"),
                    "laneId" to JsonPrimitive("todo"),
                    "confirm" to JsonPrimitive(true),
                ),
            )
            assertTrue(errCards, textCards)
            assertTrue(textCards.contains("card"), textCards)
        }
    }

    private suspend fun withHarness(
        block: suspend (DesktopKanbanService, File) -> Unit,
    ) {
        val dir = File.createTempFile("andy-mcp-kanban", null).also {
            it.delete()
            it.mkdirs()
        }
        val socketPath = File(dir, "andyd.sock")
        val kanban = DesktopKanbanService(DesktopAgentTaskStore(File(dir, "agents.db")))
        val unixServer = McpUnixSocketServer(socketPath) {
            Server(
                serverInfo = Implementation("andy-test", "1.0.0"),
                options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))),
            ).apply {
                registerAgentProjectTools(
                    UnavailableAgentRunService,
                    UnavailableProjectWorkflowService,
                    kanban = kanban,
                )
            }
        }
        try {
            unixServer.startBlocking()
            withTimeout(10_000) { while (!socketPath.exists()) delay(25) }
            block(kanban, socketPath)
        } finally {
            unixServer.stopBlocking()
            dir.deleteRecursively()
        }
    }
}

private suspend fun listTools(socketPath: File): Set<String> = withContext(Dispatchers.IO) {
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
                        put("clientInfo", buildJsonObject {
                            put("name", "andy-test")
                            put("version", "1.0.0")
                        })
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
                put("method", "tools/list")
                put("params", buildJsonObject {})
            }.toString(),
        )
        writer.write("\n")
        writer.flush()
        val line = reader.readLine() ?: return@withContext emptySet()
        val root = json.parseToJsonElement(line).jsonObject
        val arr = root["result"]?.jsonObject?.get("tools")
        val names = mutableSetOf<String>()
        if (arr is kotlinx.serialization.json.JsonArray) {
            arr.forEach { el ->
                el.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.let { names += it }
            }
        }
        names
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
                        put("arguments", buildJsonObject { arguments.forEach { (k, v) -> put(k, v) } })
                    },
                )
            }.toString(),
        )
        writer.write("\n")
        writer.flush()
        val line = reader.readLine() ?: return@withContext true to "no response"
        val root = json.parseToJsonElement(line).jsonObject
        val result = root["result"]?.jsonObject
        val isError = result?.get("isError")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: result?.get("isError")?.let { it.toString() == "true" }
            ?: (root["error"] != null)
        val text = result?.get("content")?.toString()
            ?: root["error"]?.toString()
            ?: line
        isError to text
    }
}
