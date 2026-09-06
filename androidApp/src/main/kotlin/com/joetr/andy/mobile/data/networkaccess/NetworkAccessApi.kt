package com.joetr.andy.mobile.data.networkaccess

import com.joetr.andy.mobile.data.attention.ChatAttentionEvent
import com.joetr.andy.mobile.data.attention.ChatAttentionKind
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import java.io.Closeable
import java.util.concurrent.TimeUnit

class NetworkAccessClient(
    private val baseUrl: String,
    longLived: Boolean = false,
) : Closeable {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // OkHttp: reliable WSS through Tailscale Serve (CIO often fails TLS handshake on Android).
    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        engine {
            config {
                connectTimeout(15, TimeUnit.SECONDS)
                if (longLived) {
                    readTimeout(0, TimeUnit.MILLISECONDS)
                    writeTimeout(0, TimeUnit.MILLISECONDS)
                    pingInterval(20, TimeUnit.SECONDS)
                } else {
                    readTimeout(30, TimeUnit.SECONDS)
                    writeTimeout(30, TimeUnit.SECONDS)
                }
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            if (longLived) {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            } else {
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
            }
        }
        install(ContentNegotiation) { json(json) }
        install(WebSockets)
    }

    private val root = baseUrl.trim().trimEnd('/')

    var sessionToken: String? = null

    suspend fun loginWithToken(masterToken: String): LoginResponse {
        val response = rawRequest(HttpMethod.Post, "/api/auth/login", authed = false) {
            contentType(ContentType.Application.Json)
            setBody("""{"token":${json.encodeToString(masterToken.trim())}}""")
        }
        return parseLogin(response)
    }

    suspend fun loginWithCode(code: String): LoginResponse {
        val response = rawRequest(HttpMethod.Post, "/api/auth/login", authed = false) {
            contentType(ContentType.Application.Json)
            setBody("""{"code":${json.encodeToString(code.trim())}}""")
        }
        return parseLogin(response)
    }

    suspend fun listProjects(): List<ProjectDto> =
        getJson("/api/projects").jsonArray.map {
            json.decodeFromJsonElement(ProjectDto.serializer(), it)
        }

    suspend fun listChats(): List<ChatDto> =
        getJson("/api/chats").jsonArray.map {
            json.decodeFromJsonElement(ChatDto.serializer(), it)
        }

    suspend fun listAgents(): List<AgentDto> =
        getJson("/api/agents").jsonArray.map {
            json.decodeFromJsonElement(AgentDto.serializer(), it)
        }

    suspend fun listModels(agent: String): ModelsResponse {
        val element = getJson("/api/models?agent=${agent.encodeURL()}")
        return json.decodeFromJsonElement(ModelsResponse.serializer(), element)
    }

    suspend fun listSlashCommands(agent: String, directory: String? = null): List<SlashCommandDto> {
        val query = buildString {
            append("/api/slash-commands?agent=${agent.encodeURL()}")
            if (!directory.isNullOrBlank()) {
                append("&directory=${directory.encodeURL()}")
            }
        }
        return getJson(query).jsonArray.map {
            json.decodeFromJsonElement(SlashCommandDto.serializer(), it)
        }
    }

    suspend fun chatDetail(id: String): ChatDetailDto {
        val element = getJson("/api/chats/${id.encodeURL()}")
        return json.decodeFromJsonElement(ChatDetailDto.serializer(), element)
    }

    suspend fun reply(chatId: String, message: String): OkResponse {
        val response = rawRequest(HttpMethod.Post, "/api/chats/${chatId.encodeURL()}/reply") {
            contentType(ContentType.Application.Json)
            setBody(ReplyRequest(message))
        }
        ensureOk(response)
        return response.body()
    }

    suspend fun respond(chatId: String, requestId: String, answers: Map<String, String>): OkResponse {
        val response = rawRequest(HttpMethod.Post, "/api/chats/${chatId.encodeURL()}/respond") {
            contentType(ContentType.Application.Json)
            setBody(RespondRequest(requestId, answers))
        }
        ensureOk(response)
        return response.body()
    }

    suspend fun startChat(request: StartChatRequest): StartChatResponse {
        val response = rawRequest(HttpMethod.Post, "/api/chats/start") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        val text = response.bodyAsText()
        val parsed = runCatching { json.decodeFromString(StartChatResponse.serializer(), text) }
            .getOrElse { StartChatResponse(error = text) }
        if (response.status.value >= 400) {
            throw NetworkAccessException(parsed.error ?: "Start failed (${response.status.value})")
        }
        return parsed
    }

    /**
     * Live chat updates over Network Access WebSocket.
     * Uses `?token=` query auth (supported by andyd) to avoid header friction on WS.
     */
    fun observeChat(chatId: String): Flow<ChatWsBatch> = flow {
        val token = sessionToken?.takeIf { it.isNotBlank() }
            ?: throw NetworkAccessException("Not signed in")
        val url = "$root/ws/chats/${chatId.encodeURL()}?token=${token.encodeURL()}"
        client.webSocket(urlString = url) {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: continue
                val obj = element as? JsonObject ?: continue
                emit(parseWsBatch(obj))
            }
        }
    }

    /**
     * Host-pushed attention stream (Blocked / Done / Error). Same auth + URL style as [observeChat]
     * (`https://…/ws/…` — Ktor upgrades; do not force `wss://` or Tailscale Serve TLS breaks).
     */
    fun observeAttention(
        onReady: suspend () -> Unit = {},
    ): Flow<ChatAttentionEvent> = flow {
        val token = sessionToken?.takeIf { it.isNotBlank() }
            ?: throw NetworkAccessException("Not signed in")
        val url = "$root/ws/attention?token=${token.encodeURL()}"
        try {
            client.webSocket(urlString = url) {
                var ready = false
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val text = frame.readText()
                    val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: continue
                    val obj = element as? JsonObject ?: continue
                    if (!ready) {
                        val stream = (obj["stream"] as? JsonPrimitive)?.content
                        val okPrim = obj["ok"] as? JsonPrimitive
                        val ok = okPrim?.booleanOrNull == true ||
                            okPrim?.content.equals("true", ignoreCase = true)
                        if (ok && stream == "attention") {
                            ready = true
                            onReady()
                            continue
                        }
                        // Host without /ws/attention may serve HTML/404 body as text — fail fast.
                        throw NetworkAccessException(
                            "Host did not ready /ws/attention — restart Andy Desktop / andyd after update",
                        )
                    }
                    parseAttentionEvent(obj)?.let { emit(it) }
                }
                if (!ready) {
                    throw NetworkAccessException(
                        "No /ws/attention ready frame — restart Andy Desktop / andyd after update",
                    )
                }
            }
        } catch (e: NetworkAccessException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            val hint = when {
                msg.contains("404", ignoreCase = true) ||
                    msg.contains("Connection refused", ignoreCase = true) ->
                    " (restart Andy Desktop / andyd with the latest build)"
                msg.contains("401") || msg.contains("unauthorized", ignoreCase = true) ||
                    msg.contains("4401") ->
                    " (session expired — sign in again)"
                msg.contains("Handshake", ignoreCase = true) ->
                    " (TLS/WebSocket upgrade failed — check Network Access URL)"
                else -> ""
            }
            throw NetworkAccessException("Attention push failed: ${msg.take(120)}$hint", cause = e)
        }
    }

    private fun parseAttentionEvent(obj: JsonObject): ChatAttentionEvent? {
        val kindName = (obj["kind"] as? JsonPrimitive)?.content ?: return null
        val kind = ChatAttentionKind.entries.firstOrNull { it.name.equals(kindName, ignoreCase = true) }
            ?: return null
        val taskId = (obj["taskId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: return null
        val title = (obj["title"] as? JsonPrimitive)?.content.orEmpty().ifBlank { "Chat" }
        val projectId = (obj["projectId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        return ChatAttentionEvent(
            chatId = taskId,
            projectId = projectId,
            title = title,
            kind = kind,
        )
    }

    private fun parseWsBatch(obj: JsonObject): ChatWsBatch {
        val chat = obj["chat"]?.let {
            runCatching { json.decodeFromJsonElement(ChatDto.serializer(), it) }.getOrNull()
        }
        val events = obj["events"]?.jsonArray?.mapNotNull {
            runCatching { json.decodeFromJsonElement(ChatEventDto.serializer(), it) }.getOrNull()
        }.orEmpty()
        val userInput = when (val el = obj["userInputRequest"]) {
            null -> null
            is JsonNull -> null
            else -> runCatching { json.decodeFromJsonElement(UserInputRequestDto.serializer(), el) }.getOrNull()
        }
        val clearUserInput = obj.containsKey("userInputRequest") && obj["userInputRequest"] is JsonNull
        return ChatWsBatch(
            chat = chat,
            events = events,
            userInputRequest = userInput,
            clearUserInput = clearUserInput,
        )
    }

    private suspend fun parseLogin(response: HttpResponse): LoginResponse {
        val text = response.bodyAsText()
        if (response.status == HttpStatusCode.Unauthorized || response.status.value >= 400) {
            val err = runCatching { json.decodeFromString(ApiError.serializer(), text) }.getOrNull()
            throw NetworkAccessException(err?.error ?: "Login failed")
        }
        val body = json.decodeFromString(LoginResponse.serializer(), text)
        sessionToken = body.sessionToken
        return body
    }

    private suspend fun getJson(path: String): JsonElement {
        val response = rawRequest(HttpMethod.Get, path)
        ensureOk(response)
        return json.parseToJsonElement(response.bodyAsText())
    }

    private suspend fun rawRequest(
        method: HttpMethod,
        path: String,
        authed: Boolean = true,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        return client.request("$root$path") {
            this.method = method
            if (authed) {
                val token = sessionToken?.takeIf { it.isNotBlank() }
                    ?: throw NetworkAccessException("Not signed in")
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            header(HttpHeaders.CacheControl, "no-store")
            block()
        }
    }

    private suspend fun ensureOk(response: HttpResponse) {
        if (response.status == HttpStatusCode.Unauthorized) {
            throw NetworkAccessException("Session expired — sign in again.", unauthorized = true)
        }
        if (response.status.value >= 400) {
            val text = response.bodyAsText()
            val err = runCatching { json.decodeFromString(ApiError.serializer(), text) }.getOrNull()
            throw NetworkAccessException(err?.error ?: "Request failed (${response.status.value})")
        }
    }

    override fun close() {
        client.close()
    }
}

data class ChatWsBatch(
    val chat: ChatDto? = null,
    val events: List<ChatEventDto> = emptyList(),
    val userInputRequest: UserInputRequestDto? = null,
    val clearUserInput: Boolean = false,
)

class NetworkAccessException(
    message: String,
    val unauthorized: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

private fun String.encodeURL(): String =
    java.net.URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
