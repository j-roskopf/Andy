package app.andy.desktop.service.hub

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class McpUpstreamTool(
    val name: String,
    val description: String?,
    val inputSchema: JsonObject?,
)

/**
 * Minimal streamable-HTTP MCP client (initialize + tools/list + tools/call).
 * Uses JDK HttpClient so we do not depend on Ktor SSE for hub upstreams.
 */
class McpUpstreamHttpSession(
    private val url: String,
    private val accessTokenProvider: () -> String?,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) {
    private val idSeq = AtomicLong(1)
    @Volatile private var initialized = false
    @Volatile private var sessionId: String? = null

    fun close() {
        initialized = false
    }

    fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            rpc(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", idSeq.getAndIncrement())
                    put("method", "initialize")
                    put(
                        "params",
                        buildJsonObject {
                            put("protocolVersion", "2024-11-05")
                            put("capabilities", buildJsonObject {})
                            put(
                                "clientInfo",
                                buildJsonObject {
                                    put("name", "andy-mcp-hub")
                                    put("version", "1.0.0")
                                },
                            )
                        },
                    )
                },
            )
            notify(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("method", "notifications/initialized")
                },
            )
            initialized = true
        }
    }

    fun listTools(): List<McpUpstreamTool> {
        ensureInitialized()
        val result = rpc(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", idSeq.getAndIncrement())
                put("method", "tools/list")
                put("params", buildJsonObject {})
            },
        )
        val tools = result["result"]?.jsonObject?.get("tools")?.jsonArray ?: JsonArray(emptyList())
        return tools.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            McpUpstreamTool(
                name = name,
                description = obj["description"]?.jsonPrimitive?.contentOrNull,
                inputSchema = obj["inputSchema"] as? JsonObject,
            )
        }
    }

    fun callTool(name: String, arguments: Map<String, JsonElement>): JsonObject {
        ensureInitialized()
        val result = rpc(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", idSeq.getAndIncrement())
                put("method", "tools/call")
                put(
                    "params",
                    buildJsonObject {
                        put("name", name)
                        put(
                            "arguments",
                            buildJsonObject {
                                arguments.forEach { (k, v) -> put(k, v) }
                            },
                        )
                    },
                )
            },
        )
        return result["result"]?.jsonObject
            ?: result["error"]?.jsonObject
            ?: buildJsonObject {
                put("isError", true)
                put(
                    "content",
                    buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", result.toString())
                        })
                    },
                )
            }
    }

    private fun notify(payload: JsonObject) {
        post(payload, expectBody = false)
    }

    private fun rpc(payload: JsonObject): JsonObject {
        val body = post(payload, expectBody = true)
        val parsed = parseMcpHttpBody(body)
        return parsed ?: error("Empty MCP response from $url")
    }

    private fun post(payload: JsonObject, expectBody: Boolean): String {
        val token = accessTokenProvider()?.takeIf { it.isNotBlank() }
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        sessionId?.takeIf { it.isNotBlank() }?.let { builder.header("Mcp-Session-Id", it) }
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        // Stateful Streamable HTTP servers return Mcp-Session-Id on initialize and expect it
        // echoed on every later request; capture it so tools/list and tools/call are accepted.
        response.headers().firstValue("Mcp-Session-Id").ifPresent { sessionId = it }
        if (response.statusCode() !in 200..299) {
            val snippet = response.body().orEmpty().take(400)
            error("Upstream MCP HTTP ${response.statusCode()}: $snippet")
        }
        return if (expectBody) response.body().orEmpty() else ""
    }

    companion object {
        internal fun parseMcpHttpBody(body: String): JsonObject? {
            val trimmed = body.trim()
            if (trimmed.isEmpty()) return null
            // SSE: look for last data: JSON line
            if (trimmed.contains("data:")) {
                val dataLines = trimmed.lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("data:") }
                    .map { it.removePrefix("data:").trim() }
                    .filter { it.isNotEmpty() && it != "[DONE]" }
                    .toList()
                for (line in dataLines.asReversed()) {
                    val obj = runCatching {
                        Json.parseToJsonElement(line).jsonObject
                    }.getOrNull()
                    if (obj != null) return obj
                }
            }
            return runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
        }
    }
}
