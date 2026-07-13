package app.andy.desktop.service.agents

import app.andy.model.AgentAutonomy
import app.andy.model.AgentEvent
import app.andy.model.AgentKind
import app.andy.model.AgentTask
import app.andy.model.modelForCli
import app.andy.model.promptForCli
import app.andy.model.promptWithImageHints
import kotlinx.serialization.json.JsonObject

class CodexAdapter : AgentCliAdapter {
    override val kind = AgentKind.Codex
    override val supportsHeadlessResume = true
    override val supportsStreamJson = true

    override fun buildCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> = buildList {
        add(binary)
        add("exec")
        add("--json")
        task.imagePaths.forEach { path -> add("--image"); add(path) }
        task.cwd?.let { add("-C"); add(it) }
        add("--skip-git-repo-check")
        task.modelForCli()?.let { add("--model"); add(it) }
        task.reasoningEffort?.let { add("-c"); add("model_reasoning_effort=\"${it.cliValue}\"") }
        when (task.autonomy) {
            AgentAutonomy.ReadOnly -> { add("--sandbox"); add("read-only") }
            AgentAutonomy.Standard -> { add("--sandbox"); add("workspace-write") }
            AgentAutonomy.Full -> add("--dangerously-bypass-approvals-and-sandbox")
        }
        mcpUrl?.let { add("-c"); add("mcp_servers.andy.url=\"$it\"") }
        add(task.promptForCli())
    }

    override fun buildResumeCommand(binary: String, task: AgentTask, followUp: String, imagePaths: List<String>, mcpUrl: String?): List<String>? {
        val threadId = task.vendorSessionId ?: return null
        return buildList {
            add(binary)
            add("exec")
            add("resume")
            add("--json")
            imagePaths.forEach { path -> add("--image"); add(path) }
            add("--skip-git-repo-check")
            task.modelForCli()?.let { add("--model"); add(it) }
            task.reasoningEffort?.let { add("-c"); add("model_reasoning_effort=\"${it.cliValue}\"") }
            // `codex exec resume` restores the original session's sandbox and rejects
            // sandbox flags in current CLI builds.
            add(threadId)
            add(promptWithImageHints(followUp, imagePaths))
        }
    }

    override fun interactiveResumeCommand(binary: String, task: AgentTask): String {
        val threadId = task.vendorSessionId
        return if (threadId != null) "${shellQuote(binary)} resume ${shellQuote(threadId)}" else shellQuote(binary)
    }

    override fun parseLine(line: String, nowMillis: Long): List<AgentEvent> {
        if (isIgnorableCodexDiagnostic(line)) return emptyList()
        val obj = parseJsonObject(line) ?: return rawIfNotBlank(line, nowMillis)
        return parseThreadShape(obj, nowMillis)
            ?: parseLegacyShape(obj, nowMillis)
            ?: rawIfNotBlank(line, nowMillis)
    }

    /** Current `codex exec --json` schema: thread.* / turn.* / item.* events. */
    private fun parseThreadShape(obj: JsonObject, nowMillis: Long): List<AgentEvent>? {
        return when (obj.stringOrNull("type")) {
            "rate_limit_event", "rate_limits.updated" -> parseQuotaUpdate(obj, nowMillis)
            "token_count", "context_window" -> parseContextUsage(obj, nowMillis)
            "thread.started" -> listOf(AgentEvent.SessionStarted(nowMillis, obj.stringOrNull("thread_id"), model = null))
            "turn.started" -> emptyList()
            "item.started", "item.updated", "item.completed" -> {
                val completed = obj.stringOrNull("type") == "item.completed"
                val item = obj.objectOrNull("item") ?: return emptyList()
                parseItem(item, completed, nowMillis)
            }
            "turn.completed" -> {
                val usage = obj.objectOrNull("usage")
                listOf(
                    AgentEvent.TaskResult(
                        atMillis = nowMillis,
                        success = true,
                        finalText = null,
                        inputTokens = usage?.longOrNull("input_tokens"),
                        outputTokens = usage?.longOrNull("output_tokens"),
                    ),
                )
            }
            "turn.failed" -> {
                val message = obj.objectOrNull("error")?.stringOrNull("message") ?: "turn failed"
                listOf(AgentEvent.TaskError(nowMillis, message))
            }
            "error" -> listOf(AgentEvent.TaskError(nowMillis, obj.stringOrNull("message") ?: "error"))
            else -> null
        }
    }

    private fun parseItem(item: JsonObject, completed: Boolean, nowMillis: Long): List<AgentEvent> {
        return when (item.stringOrNull("item_type") ?: item.stringOrNull("type")) {
            "agent_message" -> if (completed) {
                listOfNotNull(item.stringOrNull("text")?.takeIf { it.isNotBlank() }?.let { AgentEvent.AssistantText(nowMillis, it) })
            } else {
                emptyList()
            }
            "reasoning" -> if (completed) {
                listOfNotNull(item.stringOrNull("text")?.takeIf { it.isNotBlank() }?.let { AgentEvent.Thinking(nowMillis, it) })
            } else {
                emptyList()
            }
            "command_execution" -> {
                val command = item.stringOrNull("command") ?: ""
                val (summary, detail) = fieldsForToolText(command)
                if (completed) {
                    listOf(
                        AgentEvent.ToolResult(
                            atMillis = nowMillis,
                            toolName = "shell",
                            summary = summary,
                            detail = detail,
                            isError = (item.longOrNull("exit_code") ?: 0L) != 0L,
                        ),
                    )
                } else {
                    listOf(AgentEvent.ToolCall(nowMillis, "shell", summary, detail))
                }
            }
            "file_change" -> if (completed) {
                val (summary, detail) = summarizeToolInputFields(item)
                listOf(AgentEvent.ToolCall(nowMillis, "edit", summary, detail))
            } else {
                emptyList()
            }
            "mcp_tool_call" -> {
                val tool = listOfNotNull(item.stringOrNull("server"), item.stringOrNull("tool")).joinToString(".")
                if (completed) {
                    listOf(AgentEvent.ToolResult(nowMillis, tool.ifBlank { "mcp" }, "", isError = item.stringOrNull("status") == "failed"))
                } else {
                    listOf(AgentEvent.ToolCall(nowMillis, tool.ifBlank { "mcp" }, ""))
                }
            }
            "error" -> listOf(AgentEvent.TaskError(nowMillis, item.stringOrNull("message") ?: "error"))
            else -> emptyList()
        }
    }

    /** Older codex builds: `{"id": ..., "msg": {"type": ...}}` envelopes. */
    private fun parseLegacyShape(obj: JsonObject, nowMillis: Long): List<AgentEvent>? {
        val msg = obj.objectOrNull("msg") ?: return null
        return when (msg.stringOrNull("type")) {
            "session_configured" -> listOf(AgentEvent.SessionStarted(nowMillis, msg.stringOrNull("session_id"), msg.stringOrNull("model")))
            "agent_message" -> listOfNotNull(
                (msg.stringOrNull("message") ?: msg.stringOrNull("text"))?.takeIf { it.isNotBlank() }
                    ?.let { AgentEvent.AssistantText(nowMillis, it) },
            )
            "agent_reasoning" -> listOfNotNull(
                msg.stringOrNull("text")?.takeIf { it.isNotBlank() }?.let { AgentEvent.Thinking(nowMillis, it) },
            )
            "exec_command_begin" -> {
                val (summary, detail) = summarizeToolInputFields(msg)
                listOf(AgentEvent.ToolCall(nowMillis, "shell", summary, detail))
            }
            "exec_command_end" -> listOf(
                AgentEvent.ToolResult(nowMillis, "shell", "", isError = (msg.longOrNull("exit_code") ?: 0L) != 0L),
            )
            "task_complete" -> listOf(
                AgentEvent.TaskResult(nowMillis, success = true, finalText = msg.stringOrNull("last_agent_message")),
            )
            "token_count", "context_window" -> parseContextUsage(msg, nowMillis)
            "error" -> listOf(AgentEvent.TaskError(nowMillis, msg.stringOrNull("message") ?: "error"))
            "task_started" -> emptyList()
            else -> emptyList()
        }
    }

    /**
     * Codex reports this separately from `turn.completed.usage`: it describes the
     * conversation currently in memory, including the model's active-window cap.
     * Keep the alternate field names so both the legacy and thread JSONL formats work.
     */
    private fun parseContextUsage(obj: JsonObject, nowMillis: Long): List<AgentEvent> {
        val usage = obj.objectOrNull("info") ?: obj.objectOrNull("usage") ?: obj
        val used = listOf("total_token_usage", "total_tokens", "context_tokens")
            .firstNotNullOfOrNull(usage::longOrNull)
        val window = listOf("model_context_window", "context_window_tokens", "context_window")
            .firstNotNullOfOrNull(usage::longOrNull)
        return if (used == null && window == null) emptyList() else {
            listOf(AgentEvent.ContextUsage(nowMillis, usedTokens = used, windowTokens = window))
        }
    }
}

/**
 * Codex writes these startup diagnostics to stderr even though the actual turn
 * continues normally. stderr is merged with stdout so streamed JSON can be read
 * in one pass; exclude only these known, non-actionable messages from the chat.
 */
private fun isIgnorableCodexDiagnostic(line: String): Boolean {
    val text = line.trim()
    return text == "Reading additional input from stdin..." ||
        ("rmcp::transport::worker" in text && "AuthRequired" in text && "api.githubcopilot.com" in text) ||
        ("codex_models_manager::manager" in text && "failed to refresh available models" in text)
}
