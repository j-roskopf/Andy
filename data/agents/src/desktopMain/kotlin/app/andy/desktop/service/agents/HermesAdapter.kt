package app.andy.desktop.service.agents

import app.andy.model.AgentAutonomy
import app.andy.model.AgentKind
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentTask
import app.andy.model.defaultSandboxMode
import app.andy.model.modelForCli
import app.andy.model.promptForCli

/** Hermes interactive chat adapter. Hermes receives turns through the PTY. */
class HermesAdapter : AgentCliAdapter {
    override val kind = AgentKind.Hermes
    override val embedsInitialPrompt = false
    override val embedsResumePrompt = false

    override fun buildInteractiveCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> = buildList {
        add(binary); add("chat")
        addHermesFlags(task)
        if (task.vendorSessionId.isNullOrBlank()) add("--checkpoints")
        task.imagePaths.forEach { add("--image"); add(it) }
    }

    override fun buildInteractiveResumeCommand(
        binary: String, task: AgentTask, mcpUrl: String?, followUp: String?, followUpImagePaths: List<String>,
    ): List<String> = buildList {
        add(binary); add("chat")
        task.vendorSessionId?.takeIf { it.isNotBlank() }?.let { add("--resume"); add(it) }
        addHermesFlags(task)
        (task.imagePaths + followUpImagePaths).forEach { add("--image"); add(it) }
    }

    override fun interactiveResumeCommand(binary: String, task: AgentTask): String = buildString {
        append(shellQuote(binary)); append(" chat")
        task.vendorSessionId?.takeIf { it.isNotBlank() }?.let { append(" --resume "); append(shellQuote(it)) }
    }
}

private fun MutableList<String>.addHermesFlags(task: AgentTask) {
    HermesProviderIds.resolveForLaunch()?.let { add("--provider"); add(it) }
    task.modelForCli()?.let { add("--model"); add(it) }
    val mode = if (task.planMode) AgentSandboxMode.ReadOnly else task.sandboxMode ?: task.autonomy.defaultSandboxMode()
    add("--toolsets")
    add(if (mode == AgentSandboxMode.ReadOnly) "web,skills" else "web,terminal,skills")
    task.skills.forEach { add("-s"); add(it.name) }
    if (mode == AgentSandboxMode.None || task.autonomy == AgentAutonomy.Full && !task.planMode) add("--yolo")
}
