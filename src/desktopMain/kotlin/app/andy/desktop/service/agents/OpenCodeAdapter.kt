package app.andy.desktop.service.agents

import app.andy.model.AgentAutonomy
import app.andy.model.AgentKind
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentTask
import app.andy.model.defaultSandboxMode
import app.andy.model.modelForCli
import app.andy.model.promptForCli

/**
 * OpenCode CLI (`opencode`). Interactive TUI in the project directory.
 *
 * Resume uses `--session <id>` when Andy has a trusted vendor session id.
 * Plan mode selects the built-in `plan` agent; full autonomy adds `--auto`.
 * MCP is wired via project `opencode.json` (see [DesktopAgentRunService.prepareMcp]).
 */
class OpenCodeAdapter : AgentCliAdapter {
    override val kind = AgentKind.OpenCode
    override val embedsInitialPrompt = true
    override val embedsResumePrompt = true

    override fun buildInteractiveCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> = buildList {
        add(binary)
        // OpenCode's positional argv is a project directory, not a prompt.
        // The working directory is already set by Andy's terminal launch; never
        // pass the user prompt as a bare argument or opencode will try to cd into it.
        addOpenCodeModelFlags(task)
        addOpenCodePermissionMode(task)
        task.imagePaths.forEach { path ->
            add("--file")
            add(path)
        }
        task.promptForCli().takeIf { it.isNotBlank() }?.let { prompt ->
            add("--prompt")
            add(prompt)
        }
    }

    override fun buildInteractiveResumeCommand(
        binary: String,
        task: AgentTask,
        mcpUrl: String?,
        followUp: String?,
        followUpImagePaths: List<String>,
    ): List<String> {
        val sessionId = task.vendorSessionId?.takeIf { it.isNotBlank() }
        val prompt = composeResumePrompt(
            originalPrompt = task.promptForCli(),
            followUp = followUp,
            boundToConversation = sessionId != null,
        )
        return if (sessionId != null) {
            buildList {
                add(binary)
                add("--session")
                add(sessionId)
                addOpenCodeModelFlags(task)
                addOpenCodePermissionMode(task)
                followUpImagePaths.forEach { path ->
                    add("--file")
                    add(path)
                }
                prompt?.takeIf { it.isNotBlank() }?.let {
                    add("--prompt")
                    add(it)
                }
            }
        } else {
            buildInteractiveCommand(
                binary,
                task.copy(
                    prompt = prompt ?: followUp.orEmpty(),
                    imagePaths = (task.imagePaths + followUpImagePaths).distinct(),
                ),
                mcpUrl,
            )
        }
    }

    override fun interactiveResumeCommand(binary: String, task: AgentTask): String {
        val sessionId = task.vendorSessionId
        return if (sessionId != null) {
            "${shellQuote(binary)} --session ${shellQuote(sessionId)}"
        } else {
            shellQuote(binary)
        }
    }
}

private fun MutableList<String>.addOpenCodeModelFlags(task: AgentTask) {
    task.modelForCli()?.let {
        add("--model")
        add(it)
    }
}

private fun MutableList<String>.addOpenCodePermissionMode(task: AgentTask) {
    if (task.planMode) {
        add("--agent")
        add("plan")
        return
    }
    val mode = task.sandboxMode ?: task.autonomy.defaultSandboxMode()
    when (mode) {
        AgentSandboxMode.ReadOnly -> {
            add("--agent")
            add("plan")
        }
        AgentSandboxMode.WorkspaceWrite -> Unit
        AgentSandboxMode.None -> add("--auto")
    }
    if (!task.planMode && task.autonomy == AgentAutonomy.Full && mode != AgentSandboxMode.None) {
        add("--auto")
    }
}
