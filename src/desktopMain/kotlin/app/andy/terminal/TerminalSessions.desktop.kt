package app.andy.terminal

import app.andy.desktop.service.agents.AgentScratchWorkspace
import app.andy.terminal.rust.RustTerminalBackend

actual object TerminalSessions {
    actual fun create(request: TerminalLaunchRequest): TerminalSession {
        val cwd = AgentScratchWorkspace.resolveCwd(request.cwd)
        return when (request.mode) {
            TerminalMode.DirectPty -> {
                // Phase-1 opt-in: `-Dandy.terminal.engine=rust` swaps the Actions/DirectPty
                // emulator for the alacritty_terminal JNI engine. Agent tmux attach stays BossTerm.
                val session = if (!request.agentCli && RustTerminalBackend.isEnabled()) {
                    RustTerminalBackend(
                        sessionId = request.sessionId,
                        cols = request.cols,
                        rows = request.rows,
                        appearance = request.appearance,
                    )
                } else {
                    BossTermBackend(
                        sessionId = request.sessionId,
                        cols = request.cols,
                        rows = request.rows,
                        appearance = request.appearance,
                        agentCliMode = request.agentCli,
                    )
                }
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
