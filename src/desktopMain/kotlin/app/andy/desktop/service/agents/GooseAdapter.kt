package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentTask
import app.andy.model.defaultSandboxMode
import app.andy.model.isLocalModelBackend
import app.andy.model.localModelIdWithoutProviderPrefix
import app.andy.model.localModelProviderId
import app.andy.model.modelForCli

/**
 * Goose interactive session adapter (`goose session`).
 *
 * ACP is the default lane (`goose acp --with-builtin developer`). This adapter
 * covers the terminal override, copy-paste resume, and External.
 *
 * Goose's TUI does not take a positional first-turn prompt — Andy types into the PTY.
 * Model/mode ride on `--provider`/`--model` plus [gooseLaunchEnvironment] (`GOOSE_*`).
 */
class GooseAdapter : AgentCliAdapter {
    override val kind = AgentKind.Goose
    override val embedsInitialPrompt = false
    override val embedsResumePrompt = false

    override fun buildInteractiveCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> = buildList {
        add(binary)
        add("session")
        add("--name")
        add(gooseSessionName(task))
        addGooseFlags(task)
        addGooseExtensions(mcpUrl)
    }

    override fun buildInteractiveResumeCommand(
        binary: String,
        task: AgentTask,
        mcpUrl: String?,
        followUp: String?,
        followUpImagePaths: List<String>,
    ): List<String> = buildList {
        add(binary)
        add("session")
        add("--resume")
        addGooseResumeIdentity(task)
        addGooseFlags(task)
        addGooseExtensions(mcpUrl)
    }

    override fun interactiveResumeCommand(binary: String, task: AgentTask): String = buildString {
        append(shellQuote(binary))
        append(" session --resume")
        val sessionId = task.vendorSessionId?.takeIf { it.isNotBlank() }
        if (sessionId != null && looksLikeGooseCliSessionId(sessionId)) {
            append(" --session-id ")
            append(shellQuote(sessionId))
        } else {
            append(" --name ")
            append(shellQuote(gooseSessionName(task)))
        }
    }
}

internal fun gooseSessionName(task: AgentTask): String = "andy-${task.id}"

internal fun gooseLaunchEnvironment(task: AgentTask): Map<String, String> = buildMap {
    val (provider, model) = gooseProviderAndModel(task)
    provider?.let { put("GOOSE_PROVIDER", it) }
    model?.let { put("GOOSE_MODEL", it) }
    put("GOOSE_MODE", gooseModeValue(task))
}

internal fun gooseProviderAndModel(task: AgentTask): Pair<String?, String?> {
    val selected = task.modelForCli()?.trim()?.takeIf { it.isNotBlank() } ?: return null to null
    if (task.agent.isLocalModelBackend) {
        val model = localModelIdWithoutProviderPrefix(task.agent, selected).takeIf { it.isNotBlank() }
        return task.agent.localModelProviderId to model
    }
    val slash = selected.indexOf('/')
    return if (slash > 0 && slash < selected.lastIndex) {
        selected.take(slash) to selected.substring(slash + 1)
    } else {
        null to selected
    }
}

internal fun gooseModeValue(task: AgentTask): String {
    val mode = if (task.planMode) {
        AgentSandboxMode.ReadOnly
    } else {
        task.sandboxMode ?: task.autonomy.defaultSandboxMode()
    }
    return when (mode) {
        AgentSandboxMode.ReadOnly -> "chat"
        AgentSandboxMode.WorkspaceWrite -> "approve"
        AgentSandboxMode.None -> "auto"
    }
}

internal fun looksLikeGooseCliSessionId(value: String): Boolean =
    Regex("""^\d{8}_\d+$""").matches(value.trim())

private fun MutableList<String>.addGooseResumeIdentity(task: AgentTask) {
    val sessionId = task.vendorSessionId?.takeIf { it.isNotBlank() }
    if (sessionId != null && looksLikeGooseCliSessionId(sessionId)) {
        add("--session-id")
        add(sessionId)
    } else {
        add("--name")
        add(gooseSessionName(task))
    }
}

private fun MutableList<String>.addGooseFlags(task: AgentTask) {
    val (provider, model) = gooseProviderAndModel(task)
    provider?.let {
        add("--provider")
        add(it)
    }
    model?.let {
        add("--model")
        add(it)
    }
    add("--with-builtin")
    add("developer")
}

private fun MutableList<String>.addGooseExtensions(mcpUrl: String?) {
    mcpUrl?.takeIf { it.isNotBlank() }?.let {
        add("--with-streamable-http-extension")
        add(it)
    }
}
