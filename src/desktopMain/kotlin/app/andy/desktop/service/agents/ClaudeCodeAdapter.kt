package app.andy.desktop.service.agents

import app.andy.model.AgentAutonomy
import app.andy.model.AgentEvent
import app.andy.model.AgentKind
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentTask
import app.andy.model.followUpPromptForCli
import app.andy.model.modelForCli
import app.andy.model.promptForCli
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class ClaudeCodeAdapter : AgentCliAdapter {
    override val kind = AgentKind.ClaudeCode
    override val supportsHeadlessResume = true
    override val supportsStreamJson = true

    override fun buildCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> = buildList {
        add(binary)
        add("-p")
        add("--output-format"); add("stream-json")
        add("--verbose")
        // Session id is pre-assigned by the run service so resume works even if parsing fails.
        task.vendorSessionId?.let { add("--session-id"); add(it) }
        task.modelForCli()?.let { add("--model"); add(it) }
        task.reasoningEffort?.let { add("--effort"); add(it.cliValue) }
        addClaudePermissionMode(task)
        task.maxBudgetUsd?.let { add("--max-budget-usd"); add(it.toString()) }
        mcpUrl?.let {
            add("--mcp-config")
            add("""{"mcpServers":{"andy":{"type":"http","url":"$it"}}}""")
        }
        add(task.promptForCli())
    }

    override fun buildResumeCommand(binary: String, task: AgentTask, followUp: String, imagePaths: List<String>, mcpUrl: String?): List<String>? {
        val sessionId = task.vendorSessionId ?: return null
        return buildList {
            add(binary)
            add("-p")
            add("--output-format"); add("stream-json")
            add("--verbose")
            add("--resume"); add(sessionId)
            task.modelForCli()?.let { add("--model"); add(it) }
            task.reasoningEffort?.let { add("--effort"); add(it.cliValue) }
            addClaudePermissionMode(task)
            mcpUrl?.let {
                add("--mcp-config")
                add("""{"mcpServers":{"andy":{"type":"http","url":"$it"}}}""")
            }
            add(task.followUpPromptForCli(followUp, imagePaths))
        }
    }

    override fun interactiveResumeCommand(binary: String, task: AgentTask): String {
        val sessionId = task.vendorSessionId
        return if (sessionId != null) "${shellQuote(binary)} --resume ${shellQuote(sessionId)}" else shellQuote(binary)
    }

    override fun parseLine(line: String, nowMillis: Long): List<AgentEvent> {
        val trimmed = line.trim()
        // Claude prints some fatal startup failures as plain text before any stream-json.
        if (trimmed.startsWith("Error:", ignoreCase = true)) {
            return listOf(AgentEvent.TaskError(nowMillis, trimmed.substring(6).trim().ifBlank { trimmed }))
        }
        val obj = parseJsonObject(line) ?: return rawIfNotBlank(line, nowMillis)
        return parseClaudeStyleObject(obj, nowMillis) ?: rawIfNotBlank(line, nowMillis)
    }
}

private fun MutableList<String>.addClaudePermissionMode(task: AgentTask) {
    if (task.planMode) {
        add("--permission-mode"); add("plan")
        return
    }
    when (task.sandboxMode) {
        AgentSandboxMode.ReadOnly -> { add("--permission-mode"); add("plan") }
        AgentSandboxMode.WorkspaceWrite -> { add("--permission-mode"); add("acceptEdits") }
        AgentSandboxMode.None -> add("--dangerously-skip-permissions")
        null -> when (task.autonomy) {
            AgentAutonomy.ReadOnly -> { add("--permission-mode"); add("plan") }
            AgentAutonomy.Standard -> { add("--permission-mode"); add("acceptEdits") }
            AgentAutonomy.Full -> add("--dangerously-skip-permissions")
        }
    }
}

internal fun rawIfNotBlank(line: String, nowMillis: Long): List<AgentEvent> {
    if (line.isBlank()) return emptyList()
    val json = parseJsonObject(line)
    return if (json != null) listOf(compactProviderJson(json, nowMillis)) else listOf(AgentEvent.Raw(nowMillis, line))
}

/**
 * Parses one Claude Code stream-json object into events. Cursor's stream-json
 * mimics this schema, so [CursorAdapter] shares it. Returns null when the
 * object shape is unrecognized (caller falls back to Raw).
 */
internal fun parseClaudeStyleObject(obj: JsonObject, nowMillis: Long): List<AgentEvent>? {
    return when (obj.stringOrNull("type")) {
        "rate_limit_event", "rate_limits.updated" -> parseQuotaUpdate(obj, nowMillis)
        "system" -> {
            if (obj.stringOrNull("subtype") == "init") {
                listOf(
                    AgentEvent.SessionStarted(
                        atMillis = nowMillis,
                        sessionId = obj.stringOrNull("session_id") ?: obj.stringOrNull("chat_id"),
                        model = obj.stringOrNull("model"),
                    ),
                )
            } else {
                emptyList()
            }
        }
        "assistant" -> {
            // Cursor and newer Claude stream-json builds emit text a fragment at a
            // time rather than wrapping it in the usual message.content blocks.
            // Treat those fragments as normal assistant speech so the transcript
            // can grow one readable message instead of printing JSON lines.
            if (obj.stringOrNull("subtype") == "delta") {
                parseStreamDelta(obj, nowMillis)
            } else {
                parseContentBlocks(obj.objectOrNull("message")?.get("content") as? JsonArray, nowMillis)
            }
        }
        "thinking" -> {
            // Claude and Cursor expose reasoning as a separate stream. Keep that
            // distinction so the transcript can render it as a process step.
            if (obj.stringOrNull("subtype") == "delta") parseStreamDelta(obj, nowMillis, isThinking = true) else emptyList()
        }
        "user" -> parseToolResults(obj.objectOrNull("message")?.get("content") as? JsonArray, nowMillis)
        "tool_call" -> parseCursorStyleToolCall(obj, nowMillis) ?: run {
            // Flat Claude/Cursor variant: top-level name + input/args.
            val name = obj.stringOrNull("name") ?: obj.objectOrNull("tool_call")?.stringOrNull("name") ?: "tool"
            val (summary, detail) = summarizeToolInputFields(obj.objectOrNull("input") ?: obj.objectOrNull("args"))
            listOf(AgentEvent.ToolCall(nowMillis, name, summary, detail))
        }
        "result" -> {
            val usage = obj.objectOrNull("usage")
            listOf(
                AgentEvent.TaskResult(
                    atMillis = nowMillis,
                    success = obj.booleanOrNull("is_error") != true && obj.stringOrNull("subtype")?.startsWith("error") != true,
                    finalText = obj.stringOrNull("result"),
                    costUsd = obj.doubleOrNull("total_cost_usd"),
                    inputTokens = usage?.longOrNull("input_tokens"),
                    outputTokens = usage?.longOrNull("output_tokens"),
                    durationMs = obj.longOrNull("duration_ms"),
                ),
            )
        }
        else -> null
    }
}

private fun parseStreamDelta(obj: JsonObject, nowMillis: Long, isThinking: Boolean = false): List<AgentEvent> {
    val text = obj.stringOrNull("text")
        ?: obj.objectOrNull("delta")?.stringOrNull("text")
        ?: return emptyList()
    return text.takeIf { it.isNotEmpty() }?.let {
        listOf(
            if (isThinking) {
                AgentEvent.Thinking(nowMillis, it, isStreamDelta = true)
            } else {
                AgentEvent.AssistantText(nowMillis, it, isStreamDelta = true)
            },
        )
    }.orEmpty()
}

private fun parseContentBlocks(content: JsonArray?, nowMillis: Long): List<AgentEvent> {
    if (content == null) return emptyList()
    return content.mapNotNull { element ->
        val block = element as? JsonObject ?: return@mapNotNull null
        when (block.stringOrNull("type")) {
            "text" -> block.stringOrNull("text")?.takeIf { it.isNotBlank() }?.let { AgentEvent.AssistantText(nowMillis, it) }
            "thinking" -> block.stringOrNull("thinking")?.takeIf { it.isNotBlank() }?.let { AgentEvent.Thinking(nowMillis, it) }
            "tool_use" -> {
                val (summary, detail) = summarizeToolInputFields(block.objectOrNull("input"))
                AgentEvent.ToolCall(
                    atMillis = nowMillis,
                    toolName = block.stringOrNull("name") ?: "tool",
                    summary = summary,
                    detail = detail,
                )
            }
            else -> null
        }
    }
}

private fun parseToolResults(content: JsonArray?, nowMillis: Long): List<AgentEvent> {
    if (content == null) return emptyList()
    return content.mapNotNull { element ->
        val block = element as? JsonObject ?: return@mapNotNull null
        if (block.stringOrNull("type") != "tool_result") return@mapNotNull null
        val detail = toolResultText(block)
        AgentEvent.ToolResult(
            atMillis = nowMillis,
            toolName = null,
            summary = detail.truncateForSummary(),
            detail = detail.truncateForDetail(),
            isError = block.booleanOrNull("is_error") == true,
        )
    }
}

private fun toolResultText(block: JsonObject): String {
    return when (val content = block["content"]) {
        is JsonPrimitive -> content.content
        is JsonArray -> content.joinToString(" ") { part ->
            (part as? JsonObject)?.stringOrNull("text").orEmpty()
        }.trim()
        else -> ""
    }
}
