package app.andy.desktop.service.agents

import app.andy.model.AgentAutonomy
import app.andy.model.AgentKind
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentTask
import app.andy.model.modelForCli
import app.andy.model.promptForCli

class ClaudeCodeAdapter : AgentCliAdapter {
    override val kind = AgentKind.ClaudeCode
    override val embedsInitialPrompt = true
    override val embedsResumePrompt = true

    override fun buildInteractiveCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> = buildList {
        add(binary)
        task.modelForCli()?.let { add("--model"); add(it) }
        task.reasoningEffort?.let { add("--effort"); add(it.cliValue) }
        addClaudePermissionMode(task)
        task.maxBudgetUsd?.let { add("--max-budget-usd"); add(it.toString()) }
        mcpUrl?.let {
            add("--mcp-config")
            add("""{"mcpServers":{"andy":{"type":"http","url":"$it"}}}""")
        }
        // Interactive Claude accepts a trailing prompt as the first user turn.
        task.promptForCli().takeIf { it.isNotBlank() }?.let(::add)
    }

    override fun buildInteractiveResumeCommand(
        binary: String,
        task: AgentTask,
        mcpUrl: String?,
        followUp: String?,
        followUpImagePaths: List<String>,
    ): List<String> {
        val sessionId = task.vendorSessionId?.takeIf { it.isNotBlank() }
        return buildList {
            add(binary)
            if (sessionId != null) {
                add("--resume"); add(sessionId)
            }
            task.modelForCli()?.let { add("--model"); add(it) }
            task.reasoningEffort?.let { add("--effort"); add(it.cliValue) }
            addClaudePermissionMode(task)
            mcpUrl?.let {
                add("--mcp-config")
                add("""{"mcpServers":{"andy":{"type":"http","url":"$it"}}}""")
            }
            composeResumePrompt(
                originalPrompt = task.promptForCli(),
                followUp = followUp,
                boundToConversation = sessionId != null,
            )?.let(::add)
        }
    }

    override fun interactiveResumeCommand(binary: String, task: AgentTask): String {
        val sessionId = task.vendorSessionId
        return if (sessionId != null) "${shellQuote(binary)} --resume ${shellQuote(sessionId)}" else shellQuote(binary)
    }
}

internal fun MutableList<String>.addClaudePermissionMode(task: AgentTask) {
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
