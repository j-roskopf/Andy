package app.andy.terminal

actual object TerminalSessions {
    actual fun create(request: TerminalLaunchRequest): TerminalSession {
        AndyKetraTermConfig.ensureInitialized()
        val session = KetraTermBackend(
            sessionId = request.sessionId,
            cols = request.cols,
            rows = request.rows,
            appearance = request.appearance,
        )
        session.start(request.argv, request.cwd, request.env)
        return session
    }
}
