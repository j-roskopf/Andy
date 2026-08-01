package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentTask
import app.andy.model.defaultSandboxMode
import app.andy.model.modelForCli
import app.andy.model.promptForCli

/** OpenClaw local TUI adapter. Turns are embedded with --message. */
class OpenClawAdapter : AgentCliAdapter {
    override val kind = AgentKind.OpenClaw
    override val embedsInitialPrompt = true
    override val embedsResumePrompt = true

    override fun buildInteractiveCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> = buildList {
        add(binary); add("chat")
        openClawLaunchSessionKey(task)?.let { add("--session"); add(it) }
        addOpenClawFlags(task)
        task.promptForCli().takeIf { it.isNotBlank() }?.let { add("--message"); add(it) }
    }

    override fun buildInteractiveResumeCommand(
        binary: String, task: AgentTask, mcpUrl: String?, followUp: String?, followUpImagePaths: List<String>,
    ): List<String> = buildList {
        add(binary); add("chat")
        task.vendorSessionId?.takeIf { it.isNotBlank() }?.let { add("--session"); add(it) }
        addOpenClawFlags(task)
        composeResumePrompt(task.promptForCli(), followUp, task.vendorSessionId != null)
            ?.takeIf { it.isNotBlank() }?.let { add("--message"); add(it) }
    }

    override fun interactiveResumeCommand(binary: String, task: AgentTask): String = buildString {
        append(shellQuote(binary)); append(" chat")
        task.vendorSessionId?.takeIf { it.isNotBlank() }?.let { append(" --session "); append(shellQuote(it)) }
    }
}

private fun MutableList<String>.addOpenClawFlags(task: AgentTask) {
    task.reasoningEffort?.let { add("--thinking"); add(it.cliValue) }
    // OpenClaw local chat has no model flag. DesktopAgentRunService handles
    // provider model selection in the persisted config/preflight path.
    val mode = task.sandboxMode ?: task.autonomy.defaultSandboxMode()
    @Suppress("UNUSED_VARIABLE") val nativeApprovalMode = mode != AgentSandboxMode.None
}

internal fun openClawLaunchSessionKey(task: AgentTask): String? = when {
    !task.openClawNewSession -> null
    else -> OpenClawSessionIds.andyTaskSessionKey(task.id)
}
