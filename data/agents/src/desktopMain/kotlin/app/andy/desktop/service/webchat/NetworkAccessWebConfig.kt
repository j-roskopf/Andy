package app.andy.desktop.service.webchat

/** Wiring for web-chat security features (sessions, login rate limits). */
internal data class NetworkAccessWebConfig(
    val sessionStore: NetworkAccessSessionStore = NetworkAccessSessionStore(),
    val loginLimiter: AuthFailureLimiter = AuthFailureLimiter(
        maxFailures = 5,
        windowMillis = 60_000L,
        cooldownMillis = 5 * 60_000L,
        clock = { System.currentTimeMillis() },
    ),
    val masterTokenProvider: () -> String = { "" },
    val sessionTtlMillis: Long = 24 * 60 * 60_000L,
)
