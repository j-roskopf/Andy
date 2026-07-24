package app.andy.terminal

actual object TerminalSessions {
    private const val FORCE_LIBGHOSTTY_PROP = "andy.terminal.backend"

    actual fun preferredBackend(): TerminalBackendKind {
        when (System.getProperty(FORCE_LIBGHOSTTY_PROP)?.lowercase()) {
            "libghostty", "ghostty" -> return TerminalBackendKind.Libghostty
            "jediterm", "jedi" -> return TerminalBackendKind.JediTerm
        }
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val unix = os.contains("mac") || os.contains("linux") || os.contains("nux")
        return if (unix && LibghosttyBackend.isAvailable()) {
            TerminalBackendKind.Libghostty
        } else {
            TerminalBackendKind.JediTerm
        }
    }

    actual fun create(request: TerminalLaunchRequest): TerminalSession {
        val backend = preferredBackend()
        val session = when (backend) {
            TerminalBackendKind.Libghostty -> {
                if (LibghosttyBackend.isAvailable()) {
                    LibghosttyBackend(request.sessionId)
                } else {
                    JediTermBackend(
                        request.sessionId,
                        request.cols,
                        request.rows,
                        request.appearance,
                    )
                }
            }
            TerminalBackendKind.JediTerm -> JediTermBackend(
                request.sessionId,
                request.cols,
                request.rows,
                request.appearance,
            )
        }
        session.start(request.argv, request.cwd, request.env)
        return session
    }
}
