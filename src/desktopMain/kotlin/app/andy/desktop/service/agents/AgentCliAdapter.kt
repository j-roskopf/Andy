package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentTask

/**
 * Translates Andy's unified task model into an interactive vendor CLI argv.
 * Autonomy/sandbox → flags mapping is transport-independent; structured
 * outputs move to `.andy/<taskId>/` artifact files rather than stdout.
 */
interface AgentCliAdapter {
    val kind: AgentKind

    /**
     * When true, [buildInteractiveCommand] already embeds the first-turn prompt
     * (positional argv or a provider flag like agy's `--prompt-interactive`).
     * Andy must not also type the prompt into the PTY.
     */
    val embedsInitialPrompt: Boolean get() = true

    /**
     * When true, [buildInteractiveResumeCommand] embeds [followUp] (e.g. agy
     * `--prompt-interactive`). Andy must not also type the follow-up into the PTY.
     */
    val embedsResumePrompt: Boolean get() = false

    /**
     * Argv for a fresh interactive TUI session.
     * [mcpUrl] non-null means wire Andy's MCP server in.
     */
    fun buildInteractiveCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String>

    /**
     * Argv to reopen an existing interactive session (resume), or null if the
     * provider cannot resume. When [embedsResumePrompt] is false, [followUp] is
     * written to the PTY after start.
     */
    fun buildInteractiveResumeCommand(
        binary: String,
        task: AgentTask,
        mcpUrl: String?,
        followUp: String? = null,
        followUpImagePaths: List<String> = emptyList(),
    ): List<String>?

    /** Shell one-liner for copy/paste escape hatch. */
    fun interactiveResumeCommand(binary: String, task: AgentTask): String
}

/** Quote a string for display in a copy-pasteable shell one-liner. */
internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

internal fun String.truncateForSummary(max: Int = 160): String {
    val flat = replace('\n', ' ').trim()
    return if (flat.length <= max) flat else flat.take(max - 1) + "…"
}

/**
 * Build the prompt embedded on resume.
 *
 * When bound to a real vendor thread, only the follow-up is needed.
 * When not, re-include the original Andy prompt so the agent still has task context.
 */
internal fun composeResumePrompt(originalPrompt: String, followUp: String?, boundToConversation: Boolean): String? {
    val next = followUp?.trim().orEmpty()
    val original = originalPrompt.trim()
    return when {
        boundToConversation -> next.takeIf { it.isNotBlank() }
        next.isBlank() && original.isBlank() -> null
        next.isBlank() -> original
        original.isBlank() || original.equals(next, ignoreCase = true) -> next
        else -> buildString {
            appendLine("Continuing an Andy task. Original request:")
            appendLine(original)
            appendLine()
            appendLine("Follow-up:")
            append(next)
        }
    }
}
