package app.andy.desktop.service.webchat

import app.andy.desktop.service.firstChangedEventIndex
import app.andy.desktop.service.toWire
import app.andy.model.AgentAutonomy
import app.andy.model.AgentEvent
import app.andy.model.AgentKind
import app.andy.model.AgentLaneKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTaskDraft
import app.andy.model.acpSupported
import app.andy.model.mergedComposerSlashCommands
import app.andy.service.ActionConfigStore
import app.andy.service.AgentRunService
import app.andy.service.ProjectWorkflowService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private val WebChatJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Strict string field: rejects arrays/objects/non-string primitives so handlers return 4xx, not 500. */
private fun JsonObject.requiredString(name: String): String? {
    val el = this[name] ?: return null
    return el.asJsonStringOrNull()
}

private fun JsonElement.asJsonStringOrNull(): String? {
    val prim = this as? JsonPrimitive ?: return null
    if (!prim.isString) return null
    return prim.content
}

private suspend fun io.ktor.server.application.ApplicationCall.respondJsonError(
    status: HttpStatusCode,
    message: String,
) {
    respondText(
        buildJsonObject { put("error", message) }.toString(),
        ContentType.Application.Json,
        status,
    )
}

internal fun Application.installWebChatRoutes(
    agentRuns: () -> AgentRunService?,
    projectWorkflows: () -> ProjectWorkflowService? = { null },
    actionConfig: () -> ActionConfigStore? = { null },
    push: WebPushService,
) {
    routing {
        staticResources("/", "webchat") {
            default("index.html")
        }

        route("/api") {
            get("/chats") {
                val agents = agentRuns()
                    ?: return@get call.respondText(
                        """{"error":"agent services unavailable"}""",
                        status = HttpStatusCode.ServiceUnavailable,
                    )
                val chats = agents.tasks.value
                    .filter { it.lane == AgentLaneKind.Acp && !it.archived }
                    .sortedByDescending { it.createdAtMillis }
                call.respondText(buildJsonArray { chats.forEach { add(it.toChatJson()) } }.toString(), ContentType.Application.Json)
            }

            get("/projects") {
                val configured = try {
                    actionConfig()?.load()?.projects.orEmpty()
                } catch (_: Exception) {
                    emptyList()
                }
                val byId = linkedMapOf<String, Triple<String, String, String>>()
                for (project in configured.sortedBy { it.name.lowercase() }) {
                    byId[project.id] = Triple(project.id, project.name, project.contextDir)
                }
                val workflows = projectWorkflows()
                if (workflows != null) {
                    for (id in workflows.projects.value.keys.sorted()) {
                        if (id.isBlank() || byId.containsKey(id)) continue
                        val directory = try {
                            workflows.projectContextDir(id).orEmpty()
                        } catch (_: Exception) {
                            ""
                        }
                        byId[id] = Triple(id, id, directory)
                    }
                }
                call.respondText(
                    buildJsonArray {
                        byId.values.forEach { (id, name, directory) ->
                            add(
                                buildJsonObject {
                                    put("id", id)
                                    put("name", name)
                                    put("directory", directory)
                                },
                            )
                        }
                    }.toString(),
                    ContentType.Application.Json,
                )
            }

            get("/slash-commands") {
                val agents = agentRuns()
                    ?: return@get call.respondText(
                        """{"error":"agent services unavailable"}""",
                        status = HttpStatusCode.ServiceUnavailable,
                    )
                val agentName = call.request.queryParameters["agent"].orEmpty().trim()
                val directory = call.request.queryParameters["directory"]?.trim()?.takeIf { it.isNotBlank() }
                val agent = AgentKind.entries.firstOrNull {
                    it.name.equals(agentName, ignoreCase = true) ||
                        it.cliName.equals(agentName, ignoreCase = true)
                } ?: return@get call.respondJsonError(HttpStatusCode.BadRequest, "unknown agent: $agentName")
                if (!agent.acpSupported) {
                    return@get call.respondJsonError(
                        HttpStatusCode.BadRequest,
                        "agent must be ACP-lane (ClaudeCode, Codex, Cursor, OpenCode, Pi)",
                    )
                }
                agents.refreshSlashCommands(agent, directory)
                val provider = agents.slashCommands(agent, directory).value
                val merged = mergedComposerSlashCommands(agent, provider)
                call.respondText(
                    buildJsonArray {
                        merged.forEach { command ->
                            add(
                                buildJsonObject {
                                    put("name", command.name)
                                    put("description", command.description)
                                },
                            )
                        }
                    }.toString(),
                    ContentType.Application.Json,
                )
            }

            get("/chats/recent-directories") {
                val agents = agentRuns()
                    ?: return@get call.respondText(
                        """{"error":"agent services unavailable"}""",
                        status = HttpStatusCode.ServiceUnavailable,
                    )
                val dirs = agents.tasks.value
                    .asSequence()
                    .flatMap { listOfNotNull(it.cwd?.takeIf { p -> p.isNotBlank() }, it.originDir?.takeIf { p -> p.isNotBlank() }) }
                    .distinct()
                    .sorted()
                    .toList()
                call.respondText(buildJsonArray { dirs.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }.toString(), ContentType.Application.Json)
            }

            get("/chats/{id}") {
                val agents = agentRuns()
                    ?: return@get call.respondText(
                        """{"error":"agent services unavailable"}""",
                        status = HttpStatusCode.ServiceUnavailable,
                    )
                val id = call.parameters["id"].orEmpty()
                val task = agents.tasks.value.firstOrNull { it.id == id }
                    ?: return@get call.respondText(
                        """{"error":"chat not found"}""",
                        status = HttpStatusCode.NotFound,
                    )
                if (task.lane != AgentLaneKind.Acp) {
                    return@get call.respondText(
                        """{"error":"this chat isn't supported in the web client yet — use `andy attach` over SSH"}""",
                        status = HttpStatusCode.Conflict,
                    )
                }
                val events = agents.events(id).value
                val body = buildJsonObject {
                    put("chat", task.toChatJson())
                    putJsonArray("events") { events.forEach { add(it.toWire()) } }
                }
                call.respondText(body.toString(), ContentType.Application.Json)
            }

            post("/chats/{id}/reply") {
                val agents = agentRuns()
                    ?: return@post call.respondJsonError(
                        HttpStatusCode.ServiceUnavailable,
                        "agent services unavailable",
                    )
                val id = call.parameters["id"].orEmpty()
                val task = agents.tasks.value.firstOrNull { it.id == id }
                    ?: return@post call.respondJsonError(HttpStatusCode.NotFound, "chat not found")
                if (task.lane != AgentLaneKind.Acp) {
                    return@post call.respondJsonError(
                        HttpStatusCode.Conflict,
                        "this chat isn't supported in the web client yet — use `andy attach` over SSH",
                    )
                }
                val body = runCatching { WebChatJson.parseToJsonElement(call.receiveText()).jsonObject }
                    .getOrElse {
                        return@post call.respondJsonError(HttpStatusCode.BadRequest, "invalid json")
                    }
                val messageField = body["message"]
                if (messageField != null && messageField.asJsonStringOrNull() == null) {
                    return@post call.respondJsonError(HttpStatusCode.BadRequest, "message must be string")
                }
                val message = body.requiredString("message")?.trim().orEmpty()
                if (message.isEmpty()) {
                    return@post call.respondJsonError(HttpStatusCode.BadRequest, "message required")
                }
                agents.resume(id, message)
                call.respondText(
                    buildJsonObject {
                        put("ok", true)
                        put("id", id)
                    }.toString(),
                    ContentType.Application.Json,
                )
            }

            post("/chats/{id}/respond") {
                val agents = agentRuns()
                    ?: return@post call.respondJsonError(
                        HttpStatusCode.ServiceUnavailable,
                        "agent services unavailable",
                    )
                val id = call.parameters["id"].orEmpty()
                val task = agents.tasks.value.firstOrNull { it.id == id }
                    ?: return@post call.respondJsonError(HttpStatusCode.NotFound, "chat not found")
                if (task.lane != AgentLaneKind.Acp) {
                    return@post call.respondJsonError(
                        HttpStatusCode.Conflict,
                        "this chat isn't supported in the web client yet — use `andy attach` over SSH",
                    )
                }
                val body = runCatching { WebChatJson.parseToJsonElement(call.receiveText()).jsonObject }
                    .getOrElse {
                        return@post call.respondJsonError(HttpStatusCode.BadRequest, "invalid json")
                    }
                val requestIdField = body["requestId"]
                if (requestIdField != null && requestIdField.asJsonStringOrNull() == null) {
                    return@post call.respondJsonError(HttpStatusCode.BadRequest, "requestId must be string")
                }
                val requestId = body.requiredString("requestId")?.trim().orEmpty()
                if (requestId.isEmpty()) {
                    return@post call.respondJsonError(HttpStatusCode.BadRequest, "requestId required")
                }
                val answersEl = body["answers"]
                    ?: return@post call.respondJsonError(HttpStatusCode.BadRequest, "answers must be object")
                val answersObj = answersEl as? JsonObject
                    ?: return@post call.respondJsonError(HttpStatusCode.BadRequest, "answers must be object")
                val answers = buildMap<String, String> {
                    for ((key, value) in answersObj) {
                        val text = value.asJsonStringOrNull()
                            ?: return@post call.respondJsonError(
                                HttpStatusCode.BadRequest,
                                "answers values must be strings",
                            )
                        put(key, text)
                    }
                }
                agents.respondToUserInput(id, requestId, answers)
                call.respondText(
                    buildJsonObject {
                        put("ok", true)
                        put("id", id)
                    }.toString(),
                    ContentType.Application.Json,
                )
            }

            post("/chats/start") {
                val agents = agentRuns()
                    ?: return@post call.respondJsonError(
                        HttpStatusCode.ServiceUnavailable,
                        "agent services unavailable",
                    )
                val body = runCatching { WebChatJson.parseToJsonElement(call.receiveText()).jsonObject }
                    .getOrElse {
                        return@post call.respondJsonError(HttpStatusCode.BadRequest, "invalid json")
                    }
                for (field in listOf("prompt", "agent", "directory", "autonomy", "title", "projectId")) {
                    val el = body[field] ?: continue
                    if (el.asJsonStringOrNull() == null && el !is JsonNull) {
                        return@post call.respondJsonError(
                            HttpStatusCode.BadRequest,
                            "$field must be string",
                        )
                    }
                }
                val prompt = body.requiredString("prompt")?.trim().orEmpty()
                val agentName = body.requiredString("agent")?.trim().orEmpty()
                val directory = body.requiredString("directory")?.trim().orEmpty()
                val autonomyName = body.requiredString("autonomy")?.trim().orEmpty()
                val title = body.requiredString("title")?.trim()
                val projectId = body.requiredString("projectId")?.trim()?.takeIf { it.isNotBlank() }
                if (prompt.isEmpty()) {
                    return@post call.respondJsonError(HttpStatusCode.BadRequest, "prompt required")
                }
                if (agentName.isEmpty()) {
                    return@post call.respondJsonError(HttpStatusCode.BadRequest, "agent required")
                }
                val agent = AgentKind.entries.firstOrNull {
                    it.name.equals(agentName, ignoreCase = true) ||
                        it.cliName.equals(agentName, ignoreCase = true)
                } ?: return@post call.respondJsonError(
                    HttpStatusCode.BadRequest,
                    "unknown agent: $agentName",
                )
                if (!agent.acpSupported) {
                    return@post call.respondJsonError(
                        HttpStatusCode.BadRequest,
                        "agent must be ACP-lane (ClaudeCode, Codex, Cursor, OpenCode, Pi)",
                    )
                }
                val autonomy = if (autonomyName.isBlank()) {
                    AgentAutonomy.Standard
                } else {
                    AgentAutonomy.entries.firstOrNull { it.name.equals(autonomyName, ignoreCase = true) }
                        ?: return@post call.respondJsonError(
                            HttpStatusCode.BadRequest,
                            "unknown autonomy: $autonomyName",
                        )
                }
                val resolvedDirectory = directory.takeIf { it.isNotBlank() }
                    ?: projectId?.let { id ->
                        try {
                            projectWorkflows()?.projectContextDir(id)
                        } catch (_: Exception) {
                            null
                        } ?: try {
                            actionConfig()?.load()?.projects?.firstOrNull { it.id == id }?.contextDir
                        } catch (_: Exception) {
                            null
                        }
                    }?.takeIf { it.isNotBlank() }
                val task = agents.createAndStart(
                    AgentTaskDraft(
                        title = title?.takeIf { it.isNotBlank() } ?: prompt.take(48),
                        prompt = prompt,
                        agent = agent,
                        projectId = projectId,
                        directory = resolvedDirectory,
                        autonomy = autonomy,
                    ),
                )
                if (task.status == AgentStatus.Error) {
                    call.respondText(
                        buildJsonObject {
                            put("error", task.errorMessage?.takeIf { it.isNotBlank() } ?: "task failed to start")
                            put("id", task.id)
                        }.toString(),
                        ContentType.Application.Json,
                        status = HttpStatusCode.BadRequest,
                    )
                } else {
                    call.respondText(
                        buildJsonObject {
                            put("id", task.id)
                            put("status", task.status?.name.orEmpty())
                        }.toString(),
                        ContentType.Application.Json,
                    )
                }
            }

            get("/push/vapid-key") {
                val key = push.publicVapidKey()
                call.respondText(
                    buildJsonObject { put("publicKey", key) }.toString(),
                    ContentType.Application.Json,
                )
            }

            post("/push/subscribe") {
                val body = runCatching { WebChatJson.parseToJsonElement(call.receiveText()).jsonObject }
                    .getOrElse {
                        return@post call.respondJsonError(HttpStatusCode.BadRequest, "invalid json")
                    }
                val endpointField = body["endpoint"]
                if (endpointField != null && endpointField.asJsonStringOrNull() == null) {
                    return@post call.respondJsonError(HttpStatusCode.BadRequest, "endpoint must be string")
                }
                val endpoint = body.requiredString("endpoint").orEmpty()
                val keys = body["keys"] as? JsonObject
                if (body["keys"] != null && keys == null) {
                    return@post call.respondJsonError(HttpStatusCode.BadRequest, "keys must be object")
                }
                fun optionalString(obj: JsonObject, name: String): String? {
                    val el = obj[name] ?: return null
                    return el.asJsonStringOrNull()
                        ?: throw IllegalArgumentException("$name must be string")
                }
                val p256dh: String
                val auth: String
                try {
                    p256dh = (keys?.let { optionalString(it, "p256dh") })
                        ?: optionalString(body, "p256dh")
                        ?: optionalString(body, "key")
                        ?: ""
                    auth = (keys?.let { optionalString(it, "auth") })
                        ?: optionalString(body, "auth")
                        ?: ""
                } catch (error: IllegalArgumentException) {
                    return@post call.respondJsonError(
                        HttpStatusCode.BadRequest,
                        error.message ?: "invalid subscription fields",
                    )
                }
                try {
                    push.subscribe(endpoint, p256dh, auth)
                    call.respondText("""{"ok":true}""", ContentType.Application.Json)
                } catch (error: Exception) {
                    call.respondJsonError(
                        HttpStatusCode.BadRequest,
                        error.message?.takeIf { it.isNotBlank() } ?: "subscribe failed",
                    )
                }
            }

            delete("/push/subscribe") {
                val bodyText = runCatching { call.receiveText() }.getOrDefault("")
                val endpoint = if (bodyText.isNotBlank()) {
                    val obj = runCatching { WebChatJson.parseToJsonElement(bodyText).jsonObject }
                        .getOrElse {
                            return@delete call.respondJsonError(HttpStatusCode.BadRequest, "invalid json")
                        }
                    val field = obj["endpoint"]
                    if (field != null && field.asJsonStringOrNull() == null) {
                        return@delete call.respondJsonError(HttpStatusCode.BadRequest, "endpoint must be string")
                    }
                    obj.requiredString("endpoint")
                } else {
                    call.request.queryParameters["endpoint"]
                }.orEmpty()
                push.unsubscribe(endpoint)
                call.respondText("""{"ok":true}""", ContentType.Application.Json)
            }
        }

        webSocket("/ws/chats/{id}") {
            if (call.attributes.getOrNull(NetworkAccessWsAuthRejectedKey) == true) {
                close(
                    CloseReason(
                        NetworkAccessAuthFailureCloseCode.toShort(),
                        "unauthorized",
                    ),
                )
                return@webSocket
            }
            val agents = agentRuns()
            if (agents == null) {
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "agent services unavailable"))
                return@webSocket
            }
            val id = call.parameters["id"].orEmpty()
            val task = agents.tasks.value.firstOrNull { it.id == id }
            if (task == null) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "chat not found"))
                return@webSocket
            }
            if (task.lane != AgentLaneKind.Acp) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "terminal-lane unsupported"))
                return@webSocket
            }

            var lastSent: List<AgentEvent> = emptyList()
            var sawInitial = false

            suspend fun pushBatch(
                events: List<AgentEvent>,
                done: Boolean = false,
                error: String? = null,
                replaceFrom: Int? = null,
                terminalStatus: String? = null,
            ) {
                val payload = buildJsonObject {
                    put("taskId", id)
                    putJsonArray("events") { events.forEach { add(it.toWire()) } }
                    if (replaceFrom != null) put("replaceFrom", replaceFrom)
                    if (done) put("done", true)
                    if (error != null) put("error", error)
                    if (terminalStatus != null) put("terminalStatus", terminalStatus)
                    // Include pending user-input so the client can render approve/deny UI.
                    agents.tasks.value.firstOrNull { it.id == id }?.userInputRequest?.let { request ->
                        putJsonObject("userInputRequest") {
                            put("id", request.id)
                            put("origin", request.origin.name)
                            putJsonArray("questions") {
                                request.questions.forEach { question ->
                                    add(
                                        buildJsonObject {
                                            put("id", question.id)
                                            put("header", question.header)
                                            put("question", question.question)
                                            putJsonArray("options") {
                                                question.options.forEach { option ->
                                                    add(
                                                        buildJsonObject {
                                                            put("label", option.label)
                                                            put("description", option.description)
                                                        },
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    } ?: put("userInputRequest", JsonNull)
                }
                send(Frame.Text(payload.toString()))
            }

            try {
                val eventsFlow = agents.events(id)
                val taskFlow = agents.tasks
                    .map { list -> list.firstOrNull { it.id == id } }
                    .distinctUntilChanged()
                // Keep the socket open after Done/Error so follow-up replies can stream
                // without a reconnect. Only close when the chat is deleted/gone.
                var lastTerminalStatus: String? = null
                combine(eventsFlow, taskFlow) { snapshot, current -> snapshot to current }
                    .collect { (snapshot, current) ->
                        val diffFrom = firstChangedEventIndex(lastSent, snapshot)
                        if (!sawInitial) {
                            // replaceFrom=0 so clients that already loaded REST history
                            // replace rather than append (avoids a duplicated transcript).
                            pushBatch(snapshot, replaceFrom = 0)
                            lastSent = snapshot.toList()
                            sawInitial = true
                        } else if (diffFrom != null) {
                            pushBatch(snapshot.subList(diffFrom, snapshot.size), replaceFrom = diffFrom)
                            lastSent = snapshot.toList()
                        } else {
                            // Still push when only userInputRequest / status changes.
                            pushBatch(emptyList())
                        }

                        if (current == null) {
                            pushBatch(emptyList(), done = true, error = "chat no longer exists", terminalStatus = "gone")
                            close(CloseReason(CloseReason.Codes.NORMAL, "gone"))
                            return@collect
                        }
                        if (current.status == AgentStatus.Done || current.status == AgentStatus.Error) {
                            val terminal = if (current.status == AgentStatus.Error) "error" else "terminal"
                            if (lastTerminalStatus != terminal) {
                                val finalSnapshot = eventsFlow.value
                                val finalDiff = firstChangedEventIndex(lastSent, finalSnapshot)
                                if (finalDiff != null) {
                                    pushBatch(
                                        finalSnapshot.subList(finalDiff, finalSnapshot.size),
                                        replaceFrom = finalDiff,
                                    )
                                    lastSent = finalSnapshot.toList()
                                }
                                pushBatch(emptyList(), done = true, terminalStatus = terminal)
                                lastTerminalStatus = terminal
                            }
                        } else {
                            lastTerminalStatus = null
                        }
                    }
            } catch (_: Exception) {
                close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "stream error"))
            }
        }
    }
}

private fun app.andy.model.AgentTask.toChatJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("title", title)
    put("prompt", prompt)
    put("agent", agent.name)
    put("lane", lane.name)
    put("status", status?.name.orEmpty())
    put("projectId", projectId.orEmpty())
    put("cwd", cwd.orEmpty())
    put("originDir", originDir.orEmpty())
    put("autonomy", autonomy.name)
    put("unread", unread)
    put("archived", archived)
    put("createdAtMillis", createdAtMillis)
    put("startedAtMillis", startedAtMillis ?: 0L)
    put("finishedAtMillis", finishedAtMillis ?: 0L)
    put("resumable", resumable)
    put("errorMessage", errorMessage.orEmpty())
    userInputRequest?.let { request ->
        putJsonObject("userInputRequest") {
            put("id", request.id)
            put("origin", request.origin.name)
            putJsonArray("questions") {
                request.questions.forEach { question ->
                    add(
                        buildJsonObject {
                            put("id", question.id)
                            put("header", question.header)
                            put("question", question.question)
                            putJsonArray("options") {
                                question.options.forEach { option ->
                                    add(
                                        buildJsonObject {
                                            put("label", option.label)
                                            put("description", option.description)
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
