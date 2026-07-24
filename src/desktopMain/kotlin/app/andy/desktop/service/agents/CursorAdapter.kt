package app.andy.desktop.service.agents

import app.andy.model.AgentAutonomy
import app.andy.model.AgentKind
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentTask
import app.andy.model.modelForCli
import app.andy.model.promptForCli

/**
 * Cursor Agent CLI (`cursor-agent`).
 *
 * Interactive sessions pre-allocate a chat id via `create-chat` and always
 * launch with `--resume <id>` so Andy can reopen the same thread later.
 * Without a stored id, resume reseeds the original Andy prompt + follow-up
 * into a fresh chat (never silently drops the original request).
 */
class CursorAdapter : AgentCliAdapter {
    override val kind = AgentKind.Cursor
    override val embedsInitialPrompt = true
    override val embedsResumePrompt = true

    override fun buildInteractiveCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> = buildList {
        add(binary)
        task.vendorSessionId?.let { chatId ->
            add("--resume"); add(chatId)
        }
        task.modelForCli()?.let { add("--model"); add(it) }
        addCursorSandboxMode(task)
        if (!task.planMode && task.autonomy == AgentAutonomy.Full) add("--force")
        // Interactive cursor-agent accepts trailing prompt args as the first turn.
        task.promptForCli().takeIf { it.isNotBlank() }?.let(::add)
    }

    override fun buildInteractiveResumeCommand(
        binary: String,
        task: AgentTask,
        mcpUrl: String?,
        followUp: String?,
        followUpImagePaths: List<String>,
    ): List<String> {
        val chatId = task.vendorSessionId?.takeIf { it.isNotBlank() }
        return buildList {
            add(binary)
            if (chatId != null) {
                add("--resume"); add(chatId)
            }
            task.modelForCli()?.let { add("--model"); add(it) }
            addCursorSandboxMode(task)
            if (!task.planMode && task.autonomy == AgentAutonomy.Full) add("--force")
            composeResumePrompt(
                originalPrompt = task.promptForCli(),
                followUp = followUp,
                boundToConversation = chatId != null,
            )?.let(::add)
        }
    }

    override fun interactiveResumeCommand(binary: String, task: AgentTask): String {
        val chatId = task.vendorSessionId?.takeIf { it.isNotBlank() }
        return if (chatId != null) {
            "${shellQuote(binary)} --resume ${shellQuote(chatId)}"
        } else {
            shellQuote(binary)
        }
    }
}

private fun MutableList<String>.addCursorSandboxMode(task: AgentTask) {
    if (task.planMode) {
        add("--mode"); add("plan"); add("--sandbox"); add("enabled")
        return
    }
    when (task.sandboxMode) {
        AgentSandboxMode.ReadOnly -> { add("--mode"); add("plan"); add("--sandbox"); add("enabled") }
        AgentSandboxMode.WorkspaceWrite -> { add("--sandbox"); add("enabled") }
        AgentSandboxMode.None -> { add("--sandbox"); add("disabled") }
        null -> Unit
    }
}
