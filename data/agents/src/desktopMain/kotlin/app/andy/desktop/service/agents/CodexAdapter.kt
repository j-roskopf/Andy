package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentTask
import app.andy.model.defaultSandboxMode
import app.andy.model.modelForCli
import app.andy.model.promptForCli

class CodexAdapter : AgentCliAdapter {
    override val kind = AgentKind.Codex
    override val embedsInitialPrompt = true
    override val embedsResumePrompt = true

    override fun buildInteractiveCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> = buildList {
        add(binary)
        addCodexImageFlags(task.imagePaths)
        // Resolve here too so a stale task.cwd never reaches Codex even if a caller
        // builds argv before DesktopAgentRunService rewrites the task.
        task.cwd?.let { add("-C"); add(AgentScratchWorkspace.resolveCwd(it)) }
        task.modelForCli()?.let { add("--model"); add(it) }
        task.reasoningEffort?.let { add("-c"); add("model_reasoning_effort=\"${it.cliValue}\"") }
        when (if (task.planMode) AgentSandboxMode.ReadOnly else task.sandboxMode ?: task.autonomy.defaultSandboxMode()) {
            AgentSandboxMode.ReadOnly -> { add("--sandbox"); add("read-only") }
            AgentSandboxMode.WorkspaceWrite -> { add("--sandbox"); add("workspace-write") }
            AgentSandboxMode.None -> add("--dangerously-bypass-approvals-and-sandbox")
        }
        addCodexMcpFlags(mcpUrl)
        // Interactive codex accepts an optional trailing [PROMPT].
        task.promptForCli().takeIf { it.isNotBlank() }?.let(::add)
    }

    override fun buildInteractiveResumeCommand(
        binary: String,
        task: AgentTask,
        mcpUrl: String?,
        followUp: String?,
        followUpImagePaths: List<String>,
    ): List<String> {
        val threadId = task.vendorSessionId?.takeIf { it.isNotBlank() }
        val prompt = composeResumePrompt(
            originalPrompt = task.promptForCli(),
            followUp = followUp,
            boundToConversation = threadId != null,
        )
        return if (threadId != null) {
            buildList {
                add(binary)
                add("resume")
                add(threadId)
                addCodexImageFlags(followUpImagePaths)
                task.modelForCli()?.let { add("--model"); add(it) }
                task.reasoningEffort?.let { add("-c"); add("model_reasoning_effort=\"${it.cliValue}\"") }
                addCodexMcpFlags(mcpUrl)
                prompt?.let(::add)
            }
        } else {
            // No thread id — fresh interactive session with original + follow-up context.
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
        val threadId = task.vendorSessionId
        return if (threadId != null) "${shellQuote(binary)} resume ${shellQuote(threadId)}" else shellQuote(binary)
    }
}

private fun MutableList<String>.addCodexImageFlags(imagePaths: List<String>) {
    imagePaths.forEach { path ->
        add("--image")
        add(path)
    }
}

private fun MutableList<String>.addCodexMcpFlags(mcpUrl: String?) {
    val url = mcpUrl ?: return
    add("-c"); add("mcp_servers.andy.url=\"$url\"")
    LocalMcpAttachAuth.bearerToken()?.let { token ->
        // Codex accepts a string map for HTTP headers on remote MCP servers.
        add("-c"); add("mcp_servers.andy.http_headers={\"Authorization\"=\"Bearer $token\"}")
    }
}
