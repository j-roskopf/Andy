package app.andy.desktop.service.webchat

import app.andy.service.NetworkLoginCodeTtlMillis
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Chat-only (web) vs full MCP + chat. */
internal enum class NetworkAccessScope {
    CHAT,
    FULL,
}

internal data class ResolvedNetworkAuth(
    val scope: NetworkAccessScope,
    val fingerprint: String,
)

/**
 * In-memory login codes and chat-scoped session tokens.
 * Master [networkAccessToken] stays in workspace and grants FULL scope when presented directly.
 */
internal class NetworkAccessSessionStore(
    private val codeTtlMillis: Long = NetworkLoginCodeTtlMillis,
    private val sessionTtlMillis: Long = 24 * 60 * 60_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: SecureRandom = SecureRandom(),
) {
    private data class TimedCode(val expiresAtMillis: Long)
    private data class TimedSession(val expiresAtMillis: Long)

    private val loginCodes = ConcurrentHashMap<String, TimedCode>()
    private val sessions = ConcurrentHashMap<String, TimedSession>()

    fun clearAll() {
        loginCodes.clear()
        sessions.clear()
    }

    fun createLoginCode(): String {
        purgeExpired()
        val bytes = ByteArray(6)
        random.nextBytes(bytes)
        val code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        loginCodes[code] = TimedCode(clock() + codeTtlMillis)
        return code
    }

    /** Single-use: returns a new chat session token or null if invalid/expired. */
    fun exchangeLoginCode(code: String): String? {
        purgeExpired()
        val normalized = code.trim()
        if (normalized.isEmpty()) return null
        val entry = loginCodes.remove(normalized) ?: return null
        if (entry.expiresAtMillis <= clock()) return null
        return issueSession()
    }

    /** Validates master token and returns a chat-scoped session (never store master on phone). */
    fun exchangeMasterToken(provided: String, expectedMaster: String): String? {
        if (expectedMaster.isBlank()) return null
        if (!constantTimeEquals(provided.trim(), expectedMaster.trim())) return null
        return issueSession()
    }

    fun resolveAuth(provided: String?, expectedMaster: String): ResolvedNetworkAuth? {
        val token = provided?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (expectedMaster.isNotBlank() && constantTimeEquals(token, expectedMaster)) {
            return ResolvedNetworkAuth(
                scope = NetworkAccessScope.FULL,
                fingerprint = fingerprint(expectedMaster),
            )
        }
        purgeExpired()
        val session = sessions[token] ?: return null
        if (session.expiresAtMillis <= clock()) {
            sessions.remove(token)
            return null
        }
        return ResolvedNetworkAuth(
            scope = NetworkAccessScope.CHAT,
            fingerprint = fingerprint(token),
        )
    }

    fun fingerprint(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(token.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash.copyOf(12))
    }

    private fun issueSession(): String {
        val sessionToken = generateNetworkAccessTokenBytes(random)
        sessions[sessionToken] = TimedSession(clock() + sessionTtlMillis)
        return sessionToken
    }

    private fun purgeExpired() {
        val now = clock()
        loginCodes.entries.removeIf { it.value.expiresAtMillis <= now }
        sessions.entries.removeIf { it.value.expiresAtMillis <= now }
    }
}

internal fun requiredScopeForPath(path: String): NetworkAccessScope? {
    val normalized = path.substringBefore('?').let { raw ->
        when {
            raw.isEmpty() -> "/"
            raw.length > 1 && raw.endsWith('/') -> raw.dropLast(1)
            else -> raw
        }
    }
    if (isPublicWebChatPath(normalized)) return null
    if (normalized == "/api/auth/login") return null
    return when {
        normalized.startsWith("/api/") -> NetworkAccessScope.CHAT
        isWebSocketPath(normalized) -> NetworkAccessScope.CHAT
        normalized == "/mcp" || normalized.startsWith("/mcp/") -> NetworkAccessScope.FULL
        normalized == "/mcp-http" || normalized.startsWith("/mcp-http/") -> NetworkAccessScope.FULL
        else -> NetworkAccessScope.FULL
    }
}

internal fun scopeAllows(required: NetworkAccessScope?, granted: NetworkAccessScope): Boolean {
    if (required == null) return true
    if (required == NetworkAccessScope.CHAT) {
        return granted == NetworkAccessScope.CHAT || granted == NetworkAccessScope.FULL
    }
    return granted == NetworkAccessScope.FULL
}
