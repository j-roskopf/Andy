package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentTask
import app.andy.model.defaultSandboxMode
import app.andy.model.modelForCli
import app.andy.model.promptForCli

/**
 * Pi coding agent CLI (`pi`). Minimal terminal harness with trailing prompt support.
 *
 * Resume uses `--session <id>` when Andy has captured a vendor session id.
 * Pi has no native plan/sandbox flags — read-only mode is enforced via a prompt
 * addendum. MCP and status hooks load through Andy's Pi extension (`-e`).
 */
class PiAdapter : AgentCliAdapter {
    override val kind = AgentKind.Pi
    override val embedsInitialPrompt = true
    override val embedsResumePrompt = true

    override fun buildInteractiveCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> = buildList {
        add(binary)
        addPiModelFlags(task)
        addPiExtensionFlags(mcpUrl)
        // Interactive pi accepts a trailing prompt as the first user turn.
        piPrompt(task)?.let(::add)
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
            originalPrompt = piPrompt(task).orEmpty(),
            followUp = followUp,
            boundToConversation = sessionId != null,
        )
        return if (sessionId != null) {
            buildList {
                add(binary)
                add("--session")
                add(sessionId)
                addPiModelFlags(task)
                addPiExtensionFlags(mcpUrl)
                prompt?.let(::add)
            }
        } else {
            buildInteractiveCommand(
                binary,
                task.copy(prompt = prompt ?: followUp.orEmpty()),
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

    companion object {
        internal fun piPrompt(task: AgentTask): String? {
            val base = task.promptForCli().takeIf { it.isNotBlank() } ?: return null
            val mode = if (task.planMode) {
                AgentSandboxMode.ReadOnly
            } else {
                task.sandboxMode ?: task.autonomy.defaultSandboxMode()
            }
            return if (mode == AgentSandboxMode.ReadOnly) {
                buildString {
                    appendLine(base)
                    appendLine()
                    append(
                        "Andy read-only mode: inspect the workspace and produce a plan only. " +
                            "Do not create, edit, or delete files. Do not run mutating shell commands.",
                    )
                }
            } else {
                base
            }
        }
    }
}

private fun MutableList<String>.addPiModelFlags(task: AgentTask) {
    task.modelForCli()?.let {
        add("--model")
        add(it)
    }
    task.reasoningEffort?.let {
        add("--thinking")
        add(it.cliValue)
    }
}

private fun MutableList<String>.addPiExtensionFlags(mcpUrl: String?) {
    val extension = AndyPiExtensionInstaller.extensionPath()
    if (extension.isFile) {
        add("-e")
        add(extension.absolutePath)
    }
    // mcpUrl is communicated via ANDY_MCP_URL in the session environment.
    // Keep the parameter so the adapter signature matches other providers.
    @Suppress("UNUSED_EXPRESSION")
    mcpUrl
}
