package app.andy.desktop.service.agents

import app.andy.model.AgentAutonomy
import app.andy.model.AgentKind
import app.andy.model.AgentModelCatalog
import app.andy.model.AgentReasoningEffort
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentTask
import app.andy.model.modelForCli
import app.andy.model.promptForCli

/**
 * Google Antigravity CLI (`agy`). Interactive TUI via `--prompt-interactive`
 * (not `-p`/`--print`, which is headless).
 *
 * Resume uses `--conversation <id>` only when Andy can prove the id belongs to
 * this task. Never uses `--continue`. When no trusted id exists, re-seeds the
 * original Andy prompt plus the follow-up into a fresh interactive session so
 * the agent still sees the task context.
 */
class AntigravityAdapter : AgentCliAdapter {
    override val kind = AgentKind.Antigravity
    override val embedsInitialPrompt = true
    override val embedsResumePrompt = true

    override fun buildInteractiveCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> = buildList {
        add(binary)
        addAntigravityModelFlags(task)
        addAntigravityPermissionMode(task)
        // Official interactive first-turn seam — survives splash/account checks
        // better than typing into the PTY after boot.
        task.promptForCli().takeIf { it.isNotBlank() }?.let { prompt ->
            add("--prompt-interactive")
            add(prompt)
        }
    }

    override fun buildInteractiveResumeCommand(
        binary: String,
        task: AgentTask,
        mcpUrl: String?,
        followUp: String?,
        followUpImagePaths: List<String>,
    ): List<String> = buildList {
        add(binary)
        val conversationId = AntigravityConversationIds.resolveForTask(task)
        if (conversationId != null) {
            add("--conversation")
            add(conversationId)
        }
        addAntigravityModelFlags(task)
        addAntigravityPermissionMode(task)
        val prompt = resumePrompt(task, followUp, boundToConversation = conversationId != null)
        prompt?.let {
            add("--prompt-interactive")
            add(it)
        }
    }

    override fun interactiveResumeCommand(binary: String, task: AgentTask): String {
        val conversationId = AntigravityConversationIds.resolveForTask(task)
        return buildString {
            append(shellQuote(binary))
            if (conversationId != null) {
                append(" --conversation ")
                append(shellQuote(conversationId))
            }
        }
    }

    companion object {
        internal fun resumePrompt(task: AgentTask, followUp: String?, boundToConversation: Boolean): String? =
            composeResumePrompt(task.promptForCli(), followUp, boundToConversation)
    }
}

private fun MutableList<String>.addAntigravityModelFlags(task: AgentTask) {
    val model = task.modelForCli() ?: return
    add("--model"); add(model)
    // agy rejects bare model ids that require an effort level.
    val catalog = AgentModelCatalog.options(AgentKind.Antigravity).firstOrNull { it.id == model }
    val effort = task.reasoningEffort
        ?: catalog?.preferredEffort()
        ?: AgentReasoningEffort.High
    val token = catalog?.effortToken(effort) ?: effort.cliValue
    if (token in setOf("low", "medium", "high")) {
        add("--effort"); add(token)
    }
}

private fun MutableList<String>.addAntigravityPermissionMode(task: AgentTask) {
    if (task.planMode) {
        add("--mode"); add("plan"); add("--sandbox")
        return
    }
    when (task.sandboxMode) {
        AgentSandboxMode.ReadOnly -> { add("--mode"); add("plan"); add("--sandbox") }
        AgentSandboxMode.WorkspaceWrite -> { add("--mode"); add("accept-edits") }
        AgentSandboxMode.None -> add("--dangerously-skip-permissions")
        null -> when (task.autonomy) {
            AgentAutonomy.ReadOnly -> { add("--mode"); add("plan"); add("--sandbox") }
            AgentAutonomy.Standard -> { add("--mode"); add("accept-edits") }
            AgentAutonomy.Full -> add("--dangerously-skip-permissions")
        }
    }
}
