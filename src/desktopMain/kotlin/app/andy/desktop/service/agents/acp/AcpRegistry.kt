package app.andy.desktop.service.agents.acp

import app.andy.model.AgentKind

sealed interface AcpLaunchSpec {
    data class Npx(val packageName: String, val version: String) : AcpLaunchSpec
    data class Native(val command: String, val args: List<String>) : AcpLaunchSpec
}

/** ACP launchers are kept in one table so provider command changes cannot leak into routing. */
object AcpRegistry {
    private val specs = mapOf(
        AgentKind.ClaudeCode to AcpLaunchSpec.Npx(
            packageName = "@agentclientprotocol/claude-agent-acp",
            version = "0.64.2",
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
    )

    fun spec(kind: AgentKind): AcpLaunchSpec? = specs[kind]
}
