package app.andy.terminal

actual object TerminalSessions {
    actual fun create(request: TerminalLaunchRequest): TerminalSession {
        AndyKetraTermConfig.ensureInitialized()
        return when (request.mode) {
            TerminalMode.DirectPty -> {
                val session = KetraTermBackend(
                    sessionId = request.sessionId,
                    cols = request.cols,
                    rows = request.rows,
                    appearance = request.appearance,
                )
                session.start(request.argv, request.cwd, request.env)
                session
            }
            TerminalMode.TmuxAgent -> {
                val session = TmuxAgentBackend(sessionId = request.sessionId)
                session.setKillOnClose(request.killTmuxOnClose)
                session.start(request.argv, request.cwd, request.env)
                session
            }
            TerminalMode.TmuxAttach -> {
                // Ensure the agent session exists (create if argv provided and missing).
                if (!TmuxAndy.hasSession(request.sessionId) && request.argv.isNotEmpty()) {
                    TmuxAndy.newSession(request.sessionId, request.cwd, request.argv, request.env)
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
