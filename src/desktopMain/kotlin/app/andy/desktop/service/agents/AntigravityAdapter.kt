package app.andy.desktop.service.agents

import app.andy.model.AgentAutonomy
import app.andy.model.AgentEvent
import app.andy.model.AgentKind
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentTask
import app.andy.model.modelForCli
import app.andy.model.promptForCli

/**
 * Google Antigravity's CLI (`agy`). Headless via `-p/--print`, but the output
 * is plain text (no stream-json), so every stdout line becomes [AgentEvent.Raw]
 * and the run service synthesizes the final result from the exit code and tail.
 * Print-mode conversations leave no local conversation ID behind, so headless
 * resume is unsupported; the interactive hatch uses `--continue` (most recent
 * conversation), which is right when used straight after the task finishes.
 */
class AntigravityAdapter : AgentCliAdapter {
    override val kind = AgentKind.Antigravity
    override val supportsHeadlessResume = false
    override val supportsStreamJson = false

    override fun buildCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> = buildList {
        add(binary)
        task.modelForCli()?.let { add("--model"); add(it) }
        add("-p"); add(task.promptForCli())
        // Print mode defaults to a 5m ceiling; agentic tasks routinely run longer.
        add("--print-timeout"); add("60m")
        addAntigravityPermissionMode(task)
    }

    override fun buildResumeCommand(binary: String, task: AgentTask, followUp: String, imagePaths: List<String>, mcpUrl: String?): List<String>? = null

    override fun interactiveResumeCommand(binary: String, task: AgentTask): String =
        "${shellQuote(binary)} --continue"

    override fun parseLine(line: String, nowMillis: Long): List<AgentEvent> = rawIfNotBlank(line, nowMillis)
}

private fun MutableList<String>.addAntigravityPermissionMode(task: AgentTask) {
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
