package app.andy.desktop.service

import app.andy.model.AgentEvent
import app.andy.model.AgentStatus
import app.andy.service.AgentRunService
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.BaseNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.CustomNotification
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

private class ChatSubscribeFinished(val reason: String) : Exception(reason)

/** MCP notification method for [chat.subscribe] event batches. */
const val ChatSubscribeNotificationMethod = "notifications/andy/chat.events"

private val AllowedImageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp")

/** Tracks live [chat.subscribe] collectors so tests can assert disconnect cleanup. */
internal object ChatSubscribeMetrics {
    private val activeCollectors = AtomicInteger(0)

    fun activeCollectorCount(): Int = activeCollectors.get()

    fun track(job: Job) {
        activeCollectors.incrementAndGet()
        job.invokeOnCompletion {
            activeCollectors.decrementAndGet()
        }
    }
}

/**
 * The MCP Kotlin SDK does not cancel in-flight tool handlers when the transport
 * closes. [chat.subscribe] therefore registers its collector Job here so
 * [McpUnixSocketServer] can cancel it from the session `onClose` callback.
 */
internal object ChatSubscribeRegistry {
    private val bySession = ConcurrentHashMap<String, CopyOnWriteArrayList<Job>>()

    fun register(sessionId: String, job: Job) {
        bySession.getOrPut(sessionId) { CopyOnWriteArrayList() }.add(job)
        job.invokeOnCompletion {
            bySession[sessionId]?.remove(job)
            if (bySession[sessionId].isNullOrEmpty()) {
                bySession.remove(sessionId)
            }
        }
    }

    fun cancelSession(sessionId: String) {
        val jobs = bySession.remove(sessionId).orEmpty()
        jobs.forEach { job ->
            job.cancel(CancellationException("chat.subscribe: client disconnected"))
        }
    }

    fun cancelAll() {
        bySession.keys.toList().forEach { cancelSession(it) }
    }
}

/**
 * Parses and validates the MCP `imagePaths` argument. Rejects wrong container/item
 * types before checking absolute-path shape, existence, regular-file status, and extension.
 */
internal fun parseImagePathsArg(args: Map<String, JsonElement>): List<String> {
    val element = args["imagePaths"] ?: return emptyList()
    val array = element as? JsonArray
        ?: error("imagePaths must be an array of strings")
    val paths = array.mapIndexed { index, item ->
        val primitive = item as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            error("imagePaths[$index] must be a string")
        }
        primitive.content
    }
    return validateImagePaths(paths)
}

/**
 * Validates raw local image paths for MCP chat tools. Rejects missing files and
 * non-image extensions rather than silently dropping them.
 */
internal fun validateImagePaths(paths: List<String>): List<String> {
    for (path in paths) {
        val file = File(path)
        if (!file.isAbsolute) {
            error("image path must be absolute: $path")
        }
        if (!file.isFile) {
            error("image path not found or not a regular file: $path")
        }
        val ext = file.extension.lowercase()
        if (ext !in AllowedImageExtensions) {
            error(
                "unsupported image type '.$ext' for $path " +
                    "(allowed: ${AllowedImageExtensions.joinToString(", ")})",
            )
        }
    }
    return paths
}

/**
 * First index where [next] differs from [prev], or null when identical.
 * In-place ACP coalescing (stream text / tool updates) keeps list size stable while
 * mutating entries — callers must push from this index with replace semantics.
 */
internal fun firstChangedEventIndex(prev: List<AgentEvent>, next: List<AgentEvent>): Int? {
    if (next.size < prev.size) return 0
    val shared = prev.size
    for (i in 0 until shared) {
        if (prev[i] != next[i]) return i
    }
    return if (next.size > prev.size) prev.size else null
}

/** Wire shape shared by [chat.events] and [chat.subscribe] payloads. */
internal fun AgentEvent.toWire(): JsonObject = buildJsonObject {
    put("atMillis", atMillis)
    when (this@toWire) {
        is AgentEvent.SessionStarted -> {
            put("type", "session")
            put("sessionId", sessionId.orEmpty())
            put("model", model.orEmpty())
        }
        is AgentEvent.AssistantText -> {
            put("type", "assistant")
            put("text", text)
            put("stream", isStreamDelta)
        }
        is AgentEvent.Thinking -> {
            put("type", "thinking")
            put("text", text)
            put("stream", isStreamDelta)
        }
        is AgentEvent.UserMessage -> {
            put("type", "user")
            put("text", text)
            put("images", JsonArray(imagePaths.map(::JsonPrimitive)))
        }
        is AgentEvent.ToolCall -> {
            put("type", "tool")
            put("toolName", toolName)
            put("toolCallId", toolCallId.orEmpty())
            put("summary", summary)
            put("detail", detail)
            put("kind", kind?.name.orEmpty())
            put("state", state.name)
            put("locations", JsonArray(locations.map(::JsonPrimitive)))
        }
        is AgentEvent.ToolResult -> {
            put("type", "tool-result")
            put("toolName", toolName.orEmpty())
            put("summary", summary)
            put("detail", detail)
            put("isError", isError)
        }
        is AgentEvent.TaskError -> {
            put("type", "error")
            put("text", message)
        }
        is AgentEvent.TaskResult -> {
            put("type", "result")
            put("success", success)
            put("finalText", finalText.orEmpty())
            put("costUsd", costUsd ?: 0.0)
            put("costEstimated", costIsEstimated)
            put("inputTokens", inputTokens ?: 0L)
            put("outputTokens", outputTokens ?: 0L)
            put("durationMs", durationMs ?: 0L)
        }
        is AgentEvent.ContextUsage -> {
            put("type", "usage")
            put("usedTokens", usedTokens ?: 0L)
            put("windowTokens", windowTokens ?: 0L)
        }
        is AgentEvent.PlanUpdate -> {
            put("type", "plan")
            put(
                "entries",
                buildJsonArray {
                    entries.forEach { entry ->
                        add(
                            buildJsonObject {
                                put("content", entry.content)
                                put("status", entry.status)
                            },
                        )
                    }
                },
            )
            markdown?.let { put("markdown", it) }
        }
        is AgentEvent.ModeChanged -> {
            put("type", "mode")
            put("modeId", modeId)
        }
        is AgentEvent.AvailableCommands -> {
            put("type", "commands")
            put(
                "commands",
                buildJsonArray {
                    commands.forEach { command ->
                        add(
                            buildJsonObject {
                                put("name", command.name)
                                put("description", command.description)
                                put("inputHint", command.inputHint.orEmpty())
                            },
                        )
                    }
                },
            )
        }
        is AgentEvent.AvailableModes -> {
            put("type", "modes")
            put("currentModeId", currentModeId.orEmpty())
            put(
                "modes",
                buildJsonArray {
                    modes.forEach { mode ->
                        add(
                            buildJsonObject {
                                put("id", mode.id)
                                put("name", mode.name)
                                put("description", mode.description.orEmpty())
                            },
                        )
                    }
                },
            )
        }
        is AgentEvent.PermissionRequest -> {
            put("type", "permission")
            put("requestId", requestId)
            put("toolName", toolName)
            put("question", question)
            put(
                "options",
                buildJsonArray {
                    options.forEach { option ->
                        add(
                            buildJsonObject {
                                put("label", option.label)
                                put("description", option.description)
                            },
                        )
                    }
                },
            )
        }
        is AgentEvent.PermissionResolved -> {
            put("type", "permission-resolved")
            put("requestId", requestId)
            put("optionId", optionId)
            put("allowed", allowed)
            note?.let { put("note", it) }
        }
        is AgentEvent.Raw -> {
            put("type", "raw")
            put("line", line)
        }
    }
}

/**
 * Streams transcript events for [taskId] as MCP notifications until the client
 * cancels, the chat disappears, or the task reaches a terminal status with the
 * backlog flushed.
 */
internal suspend fun runChatSubscribe(
    client: ClientConnection,
    agentRuns: AgentRunService,
    taskId: String,
): CallToolResult {
    val taskExists = agentRuns.tasks.value.any { it.id == taskId }
    if (!taskExists && agentRuns.events(taskId).value.isEmpty()) {
        return CallToolResult(
            content = listOf(TextContent(text = "Error: chat no longer exists: $taskId")),
            isError = true,
        )
    }

    val subscriptionId = UUID.randomUUID().toString()
    var lastSent: List<AgentEvent> = emptyList()
    var sawInitial = false
    var endReason = "cancelled"

    // Lifetime token: cancelled by ChatSubscribeRegistry on transport close. Metrics track
    // this token (not the collector) so the count drops even if a mid-push write is stuck.
    val lifetime = Job()
    ChatSubscribeMetrics.track(lifetime)
    ChatSubscribeRegistry.register(client.sessionId, lifetime)

    fun batchPayload(
        events: List<AgentEvent>,
        done: Boolean = false,
        error: String? = null,
        replaceFrom: Int? = null,
    ): JsonObject =
        buildJsonObject {
            put("subscriptionId", subscriptionId)
            put("taskId", taskId)
            put("events", buildJsonArray { events.forEach { add(it.toWire()) } })
            if (replaceFrom != null) put("replaceFrom", replaceFrom)
            if (done) put("done", true)
            if (error != null) put("error", error)
        }

    suspend fun push(
        events: List<AgentEvent>,
        done: Boolean = false,
        error: String? = null,
        replaceFrom: Int? = null,
    ) {
        if (events.isEmpty() && replaceFrom == null && !done && error == null && sawInitial) return
        try {
            client.notification(
                CustomNotification(
                    method = Method.Custom(ChatSubscribeNotificationMethod),
                    params = BaseNotificationParams(meta = batchPayload(events, done, error, replaceFrom)),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Peer disconnect / wedged writer: drop the lifetime token so collectors are not leaked.
            lifetime.cancel(CancellationException("chat.subscribe: push failed: ${error.message}"))
            throw error
        }
    }

    suspend fun pushSnapshotDiff(snapshot: List<AgentEvent>) {
        val diffFrom = firstChangedEventIndex(lastSent, snapshot)
        if (!sawInitial) {
            push(snapshot)
            lastSent = snapshot.toList()
            sawInitial = true
            return
        }
        if (diffFrom == null) return
        push(snapshot.subList(diffFrom, snapshot.size), replaceFrom = diffFrom)
        lastSent = snapshot.toList()
    }

    try {
        coroutineScope {
            val collectorJob = launch {
                val eventsFlow = agentRuns.events(taskId)
                val taskFlow = agentRuns.tasks
                    .map { list -> list.firstOrNull { it.id == taskId } }
                    .distinctUntilChanged()
                combine(eventsFlow, taskFlow) { snapshot, task -> snapshot to task }
                    .collect { (snapshot, task) ->
                        lifetime.ensureActive()
                        pushSnapshotDiff(snapshot)

                        if (task == null) {
                            push(emptyList(), done = true, error = "chat no longer exists")
                            throw ChatSubscribeFinished("gone")
                        }
                        if (task.status == AgentStatus.Done || task.status == AgentStatus.Error) {
                            pushSnapshotDiff(eventsFlow.value)
                            push(emptyList(), done = true)
                            // Distinguish Error from Done so the CLI can lock the composer on
                            // crash/failure (AgentStatus.Error may arrive without TaskError/TaskResult).
                            throw ChatSubscribeFinished(
                                if (task.status == AgentStatus.Error) "error" else "terminal",
                            )
                        }
                    }
            }
            lifetime.invokeOnCompletion { collectorJob.cancel() }
            collectorJob.join()
        }
    } catch (finished: ChatSubscribeFinished) {
        endReason = finished.reason
    } catch (cancelled: CancellationException) {
        endReason = "cancelled"
        // Disconnect cancel is an expected end of subscribe — return ok rather than error.
        if (cancelled.message?.contains("client disconnected") != true) {
            throw cancelled
        }
    } finally {
        lifetime.complete()
    }

    return CallToolResult(
        content = listOf(
            TextContent(
                text = buildJsonObject {
                    put("ok", true)
                    put("subscriptionId", subscriptionId)
                    put("taskId", taskId)
                    put("reason", endReason)
                }.toString(),
            ),
        ),
    )
}
