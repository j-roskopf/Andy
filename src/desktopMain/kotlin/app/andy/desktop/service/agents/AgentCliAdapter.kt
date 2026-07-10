package app.andy.desktop.service.agents

import app.andy.model.AgentEvent
import app.andy.model.AgentKind
import app.andy.model.AgentTask
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Translates between Andy's unified task/event model and one vendor CLI:
 * task -> argv for a headless run, and stdout lines -> [AgentEvent]s.
 */
interface AgentCliAdapter {
    val kind: AgentKind
    val supportsHeadlessResume: Boolean
    val supportsStreamJson: Boolean

    /** Argv for a fresh headless run. [mcpUrl] non-null means wire Andy's MCP server in. */
    fun buildCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String>

    /** Argv for a headless follow-up on an existing session, or null if unsupported. */
    fun buildResumeCommand(binary: String, task: AgentTask, followUp: String, imagePaths: List<String>, mcpUrl: String?): List<String>?

    /** Shell one-liner for the "continue interactively" escape hatch. */
    fun interactiveResumeCommand(binary: String, task: AgentTask): String

    /** Must never throw; anything unrecognized becomes [AgentEvent.Raw]. */
    fun parseLine(line: String, nowMillis: Long): List<AgentEvent>

    fun extractSessionId(events: List<AgentEvent>): String? =
        events.filterIsInstance<AgentEvent.SessionStarted>().firstNotNullOfOrNull { it.sessionId }
}

internal val agentJson = Json { ignoreUnknownKeys = true }

internal fun parseJsonObject(line: String): JsonObject? =
    runCatching { agentJson.parseToJsonElement(line).jsonObject }.getOrNull()

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

internal fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.longOrNull(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.doubleOrNull(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.booleanOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

/** Converts unknown provider JSON into a compact transcript event instead of exposing transport payloads. */
internal fun compactProviderJson(obj: JsonObject, nowMillis: Long): AgentEvent.ToolResult {
    if (obj.stringOrNull("type") == "rate_limit_event") {
        val info = obj.objectOrNull("rate_limit_info")
        val status = info?.stringOrNull("status")?.replaceFirstChar(Char::uppercase) ?: "Updated"
        val window = info?.stringOrNull("rateLimitType")?.humanizeEventValue()?.let { "$it window" }
        val reset = info?.longOrNull("resetsAt")?.let { resetMillis ->
            (resetMillis - nowMillis).takeIf { it > 0 }?.let(::formatRemainingTime)?.let { "resets in $it" }
        }
        val summary = listOfNotNull(status, window, reset).joinToString(" · ")
        val detail = buildList {
            add("Rate-limit status: ${status.lowercase()}.")
            window?.let { add("Window: $it.") }
            reset?.let { add(reset.replaceFirstChar(Char::uppercase) + ".") }
            info?.stringOrNull("overageDisabledReason")?.takeIf { it.isNotBlank() }?.let {
                add("Overage is unavailable for this account.")
            }
        }.joinToString(" ")
        return AgentEvent.ToolResult(nowMillis, "rate limit", summary, detail, isError = false)
    }

    val type = obj.stringOrNull("type")?.humanizeEventValue()
    val status = obj.stringOrNull("status") ?: obj.objectOrNull("error")?.stringOrNull("message")
    val summary = listOfNotNull(type, status?.truncateForSummary(100)).joinToString(" · ")
        .ifBlank { "provider metadata" }
    return AgentEvent.ToolResult(
        atMillis = nowMillis,
        toolName = "provider event",
        summary = summary,
        detail = "The provider sent structured metadata that does not need action.",
        isError = false,
    )
}

private fun String.humanizeEventValue(): String =
    replace('_', ' ').replace('-', ' ').replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").trim()

private fun formatRemainingTime(millis: Long): String {
    val minutes = (millis / 60_000).coerceAtLeast(1)
    val hours = minutes / 60
    return if (hours > 0) "${hours}h ${minutes % 60}m" else "${minutes}m"
}

/** Compact human summary of a tool input object, e.g. the command or file path. */
internal fun summarizeToolInput(input: JsonObject?): String = summarizeToolInputFields(input).first

internal fun summarizeToolInputFields(input: JsonObject?): Pair<String, String> =
    fieldsForToolText(toolInputDetailText(input))

private fun toolInputDetailText(input: JsonObject?): String {
    if (input == null) return ""
    val interesting = listOf(
        "command", "file_path", "path", "pattern", "url", "query", "description", "prompt",
        "glob_pattern", "target_file", "old_string", "new_string", "workingDirectory",
    )
    for (key in interesting) {
        val value = input.stringOrNull(key)
        if (!value.isNullOrBlank()) return value.trim()
    }
    return input.toString().trim()
}

internal fun parseCursorStyleToolCall(obj: JsonObject, nowMillis: Long): List<AgentEvent>? {
    val envelope = obj.objectOrNull("tool_call") ?: return null
    val payload = firstCursorToolPayload(envelope) ?: return null
    val toolName = cursorToolDisplayName(envelope)
    return when (obj.stringOrNull("subtype")) {
        "started" -> {
            val (summary, detail) = summarizeToolInputFields(cursorToolArgs(payload))
            listOf(AgentEvent.ToolCall(nowMillis, toolName, summary, detail))
        }
        "completed" -> {
            val detail = cursorToolResultDetail(payload)
            val (summary, fullDetail) = fieldsForToolText(detail)
            listOf(
                AgentEvent.ToolResult(
                    atMillis = nowMillis,
                    toolName = toolName,
                    summary = summary,
                    detail = fullDetail,
                    isError = cursorToolHasError(payload),
                ),
            )
        }
        else -> null
    }
}

private fun firstCursorToolPayload(toolCall: JsonObject): JsonObject? =
    toolCall.entries.firstOrNull { (key, value) -> key.endsWith("ToolCall") && value is JsonObject }
        ?.value as? JsonObject

private fun cursorToolDisplayName(toolCall: JsonObject): String = when (val key = toolCall.keys.firstOrNull { it.endsWith("ToolCall") }) {
    "readToolCall" -> "Read"
    "editToolCall" -> "Edit"
    "writeToolCall" -> "Write"
    "shellToolCall" -> "Shell"
    "grepToolCall" -> "Grep"
    "lsToolCall" -> "Ls"
    "globToolCall" -> "Glob"
    "deleteToolCall" -> "Delete"
    "todoToolCall" -> "Todo"
    null -> "tool"
    else -> key.removeSuffix("ToolCall")
}

private fun cursorToolArgs(payload: JsonObject): JsonObject? = payload.objectOrNull("args")

private fun cursorToolHasError(payload: JsonObject): Boolean =
    payload.objectOrNull("result")?.containsKey("error") == true

private fun cursorToolResultDetail(payload: JsonObject): String {
    val result = payload.objectOrNull("result") ?: return ""
    result.objectOrNull("error")?.let { error ->
        return error.stringOrNull("errorMessage")
            ?: error.stringOrNull("message")
            ?: error.toString()
    }
    val success = result.objectOrNull("success") ?: return result.toString()
    return listOfNotNull(
        success.stringOrNull("resultForModel"),
        success.stringOrNull("stdout")?.let { stdout ->
            val stderr = success.stringOrNull("stderr").orEmpty()
            if (stderr.isNotBlank()) "$stdout\n\nstderr:\n$stderr" else stdout
        },
        success.stringOrNull("content"),
        success.stringOrNull("diffString"),
        success.stringOrNull("afterFullFileContent"),
    ).firstOrNull { it.isNotBlank() }
        ?: success.toString()
}

internal fun fieldsForToolText(raw: String): Pair<String, String> {
    val trimmed = raw.trim()
    return trimmed.truncateForSummary() to trimmed.truncateForDetail()
}

internal fun String.truncateForSummary(max: Int = 160): String {
    val flat = replace('\n', ' ').trim()
    return if (flat.length <= max) flat else flat.take(max - 1) + "…"
}

internal fun String.truncateForDetail(max: Int = 4000): String =
    if (length <= max) this else take(max - 1) + "…"

/** Quote a string for display in a copy-pasteable shell one-liner. */
internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
