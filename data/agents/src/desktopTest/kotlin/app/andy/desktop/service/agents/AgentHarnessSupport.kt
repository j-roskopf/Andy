package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentLaneKind
import app.andy.model.AgentProviderDefaults

/** Shell used by harness tests instead of real vendor CLIs. */
internal fun harnessShellBinary(): String =
    if (System.getProperty("os.name").contains("windows", ignoreCase = true)) {
        checkNotNull(System.getenv("ComSpec")) { "ComSpec is required to run agent harness tests on Windows" }
    } else {
        "/bin/sh"
    }

/**
 * Override every [AgentKind] to the shell stub so [AgentCliLocator.locateAll] never runs
 * a login-shell PATH scan or `--version` probes against real CLIs when
 * [DesktopAgentRunService] refreshes statuses on init.
 */
internal fun harnessBinaryOverrides(extra: Map<String, String> = emptyMap()): Map<String, String> =
    AgentKind.entries.associate { it.cliName to harnessShellBinary() } + extra

internal fun harnessBinaryOverridesForCodex(codexPath: String): Map<String, String> =
    harnessBinaryOverrides(mapOf(AgentKind.Codex.cliName to codexPath))

/** Shell-backed harnesses must run the terminal lane, not live ACP. */
internal fun harnessTerminalProviderDefaults(): Map<AgentKind, AgentProviderDefaults> =
    AgentKind.entries.associateWith { AgentProviderDefaults(lane = AgentLaneKind.Terminal) }

internal fun DesktopAgentRunService.applyHarnessTerminalLanes() {
    AgentKind.entries.forEach { setProviderLane(it, AgentLaneKind.Terminal) }
}
