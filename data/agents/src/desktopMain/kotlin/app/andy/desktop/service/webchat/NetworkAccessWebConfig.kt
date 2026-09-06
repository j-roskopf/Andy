package app.andy.desktop.service.webchat

import app.andy.service.NetworkAccessSessionTtlMillis

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
    /** Argon2id hash from workspace; empty means password login is disabled. */
    val masterPasswordHashProvider: () -> String = { "" },
    val sessionTtlMillis: Long = NetworkAccessSessionTtlMillis,
)
