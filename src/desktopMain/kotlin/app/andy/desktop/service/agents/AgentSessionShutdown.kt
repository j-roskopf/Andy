package app.andy.desktop.service.agents

import app.andy.desktop.service.DesktopWorkspaceStore
import app.andy.service.WorkspaceStore
import app.andy.terminal.TmuxAndy

/**
 * Andy-owned agent CLIs run in detached `tmux -L andy` sessions. On full process exit
 * (embedded daemon GUI quit or `andyd` shutdown), tear those down unless the user opted
 * into keeping sessions alive for resume-after-restart.
 */
internal object AgentSessionShutdown {
    fun keepSessionsOnQuit(workspaceStore: WorkspaceStore): Boolean =
        when (workspaceStore) {
            is DesktopWorkspaceStore -> workspaceStore.state.value.keepAgentSessionsOnShutdown
            else -> false
        }

    /**
     * @param ownsAgentSessions false for the GUI attach bridge — detach viewers only,
     *   never kill sessions owned by a running `andyd`.
     */
    fun onProcessExit(
        terminals: AgentTerminalManager,
        activeTaskIds: Collection<String>,
        workspaceStore: WorkspaceStore,
        ownsAgentSessions: Boolean,
    ) {
        when {
            !ownsAgentSessions -> activeTaskIds.forEach(terminals::detach)
            keepSessionsOnQuit(workspaceStore) -> activeTaskIds.forEach(terminals::detach)
            else -> {
                activeTaskIds.forEach(terminals::stop)
                if (TmuxAndy.isAvailable()) {
                    runCatching { TmuxAndy.killServer() }
                }
            }
        }
    }
}
