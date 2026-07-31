package app.andy.terminal

import app.andy.desktop.service.agents.AgentScratchWorkspace

actual object TerminalSessions {
    actual fun create(request: TerminalLaunchRequest): TerminalSession {
        val cwd = AgentScratchWorkspace.resolveCwd(request.cwd)
        return when (request.mode) {
            TerminalMode.DirectPty -> {
                val session = BossTermBackend(
                    sessionId = request.sessionId,
                    cols = request.cols,
                    rows = request.rows,
                    appearance = request.appearance,
                    agentCliMode = request.agentCli,
                )
                session.start(request.argv, cwd, request.env)
                session
            }
            TerminalMode.TmuxAgent -> {
                val session = TmuxAgentBackend(sessionId = request.sessionId)
                session.setKillOnClose(request.killTmuxOnClose)
                session.start(request.argv, cwd, request.env)
                session
            }
            TerminalMode.TmuxAttach -> {
                val sessionId = request.sessionId
                if (TmuxAndy.hasSession(sessionId) && TmuxAndy.sessionLooksBroken(sessionId)) {
                    TmuxAndy.killSession(sessionId)
                }
                if (!TmuxAndy.hasSession(sessionId) && request.argv.isNotEmpty()) {
                    TmuxAndy.newSession(sessionId, cwd, request.argv, request.env)
                }
                check(TmuxAndy.hasSession(sessionId)) {
                    "tmux session ${TmuxAndy.sessionName(sessionId)} missing after create"
                }
                val session = TmuxAttachBackend(
                    sessionId = request.sessionId,
                    cols = request.cols,
                    rows = request.rows,
                    appearance = request.appearance,
                    killTmuxOnClose = request.killTmuxOnClose,
                )
                session.attach()
                session
            }
        }
    }
}
