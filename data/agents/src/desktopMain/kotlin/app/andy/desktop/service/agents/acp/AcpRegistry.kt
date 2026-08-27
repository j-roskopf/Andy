package app.andy.desktop.service.agents.acp

import app.andy.model.AgentKind
import app.andy.model.AgentTask
import app.andy.model.isLocalModelBackend
import app.andy.model.localModelProviderId
import app.andy.model.modelForCli
import app.andy.model.runtimeKind

sealed interface AcpLaunchSpec {
    data class Npx(
        val packageName: String,
        val version: String,
        val extraArgs: List<String> = emptyList(),
    ) : AcpLaunchSpec
    data class Native(val command: String, val args: List<String>) : AcpLaunchSpec
}

/** ACP launchers are kept in one table so provider command changes cannot leak into routing. */
object AcpRegistry {
    private val specs = mapOf(
        AgentKind.ClaudeCode to AcpLaunchSpec.Npx(
            packageName = "@agentclientprotocol/claude-agent-acp",
            version = "0.65.0",
        ),
        AgentKind.Codex to AcpLaunchSpec.Npx(
            packageName = "@agentclientprotocol/codex-acp",
            version = "1.1.9",
        ),
        AgentKind.Cursor to AcpLaunchSpec.Native("cursor-agent", listOf("acp")),
        AgentKind.OpenCode to AcpLaunchSpec.Native("opencode", listOf("acp")),
        AgentKind.Pi to AcpLaunchSpec.Npx(
            packageName = "pi-acp",
            version = "0.0.33",
        ),
        AgentKind.Goose to AcpLaunchSpec.Native("goose", listOf("acp", "--with-builtin", "developer")),
    )

    fun spec(kind: AgentKind, extraArgs: List<String> = emptyList()): AcpLaunchSpec? {
        val base = specs[kind] ?: return null
        if (extraArgs.isEmpty()) return base
        return when (base) {
            is AcpLaunchSpec.Native -> base.copy(args = base.args + extraArgs)
            is AcpLaunchSpec.Npx -> base.copy(extraArgs = extraArgs)
        }
    }

    fun specFor(task: AgentTask): AcpLaunchSpec? = spec(task.runtimeKind(), extraArgs = piAcpModelArgs(task))
}

/** `pi-acp` forwards unknown flags to `pi --mode rpc`; without `--model` it uses Pi's default cloud provider. */
internal fun piAcpModelArgs(task: AgentTask): List<String> {
    if (task.runtimeKind() != AgentKind.Pi) return emptyList()
    val model = task.modelForCli()?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
    return buildList {
        if (task.agent.isLocalModelBackend) {
            add("--provider")
            add(task.agent.localModelProviderId)
        }
        add("--model")
        add(model)
    }
}
