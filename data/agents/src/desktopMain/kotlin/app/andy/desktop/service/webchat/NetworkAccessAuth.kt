package app.andy.desktop.service.webchat

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Non-standard WS close code reserved for auth failures (used in docs/tests). */
internal const val NetworkAccessAuthFailureCloseCode = 4401

/** Non-standard WS close code when chat-scoped token hits MCP-only routes. */
internal const val NetworkAccessScopeFailureCloseCode = 4403

/** Resolved auth attached after a successful handshake (used by push subscribe binding). */
internal val NetworkAccessAuthFingerprintKey =
    AttributeKey<String>("NetworkAccessAuthFingerprint")

/** Stable owner id for unauthenticated loopback web-chat (Network Access off). */
internal const val LocalLoopbackPushOwnerFingerprint = "local-loopback"

/**
 * Set by [NetworkAccessAuthPlugin] when a WebSocket handshake failed auth.
 * The WS route upgrades then closes with [NetworkAccessAuthFailureCloseCode]
 * (browsers cannot observe a useful close code from a pre-upgrade HTTP 401).
 */
internal val NetworkAccessWsAuthRejectedKey =
    AttributeKey<Boolean>("NetworkAccessWsAuthRejected")

internal val NetworkAccessWsScopeRejectedKey =
    AttributeKey<Boolean>("NetworkAccessWsScopeRejected")

internal class NetworkAccessAuthConfig {
    var tokenProvider: () -> String = { "" }
    var sessionStore: NetworkAccessSessionStore = NetworkAccessSessionStore()
    /** When true, loopback also requires a valid credential (closes Tailscale Serve bypass). */
    var networkAccessEnabledProvider: () -> Boolean = { false }
    /**
     * When Network Access is on and this is true, non-loopback peers must be Tailscale
     * (100.64/10 or fd7a:115c:a1e0::/48). LAN / other VPN addresses get 403.
     */
    var tailscaleOnlyProvider: () -> Boolean = { true }
    var maxFailures: Int = 10
    var windowMillis: Long = 5 * 60_000L
    var cooldownMillis: Long = 5 * 60_000L
    var loginMaxFailures: Int = 5
    var loginWindowMillis: Long = 60_000L
    var loginCooldownMillis: Long = 5 * 60_000L
    var clock: () -> Long = { System.currentTimeMillis() }
    /** Resolves the TCP peer address; overridable in tests to simulate non-loopback. */
    var peerAddressResolver: (ApplicationCall) -> String = { it.remotePeerAddress() }
}

/**
 * Gates Network Access:
 * - Static web-chat assets and `/api/auth/login` stay public.
 * - When Network Access is **off**, loopback is unauthenticated (vendor CLIs).
 * - When Network Access is **on**, every non-public route needs credentials — including loopback.
 * - Chat-scoped session tokens work on /api/ and /ws/ routes only; MCP requires the master token.
 * - Optional Tailscale-only filter rejects non-Tailscale remote peers with 403.
 * - LAN mode allows plain HTTP (bearer token still required); use Tailscale Serve for TLS on untrusted networks.
 */
internal val NetworkAccessAuthPlugin = createApplicationPlugin(
    name = "NetworkAccessAuth",
    createConfiguration = ::NetworkAccessAuthConfig,
) {
    val ipLimiter = AuthFailureLimiter(
        maxFailures = pluginConfig.maxFailures,
        windowMillis = pluginConfig.windowMillis,
        cooldownMillis = pluginConfig.cooldownMillis,
        clock = pluginConfig.clock,
    )
    val tokenLimiter = AuthFailureLimiter(
        maxFailures = pluginConfig.maxFailures,
        windowMillis = pluginConfig.windowMillis,
        cooldownMillis = pluginConfig.cooldownMillis,
        clock = pluginConfig.clock,
    )
    val loginLimiter = AuthFailureLimiter(
        maxFailures = pluginConfig.loginMaxFailures,
        windowMillis = pluginConfig.loginWindowMillis,
        cooldownMillis = pluginConfig.loginCooldownMillis,
        clock = pluginConfig.clock,
    )

    onCall { call ->
        val path = call.request.path()
        if (isPublicWebChatPath(path) || path == "/api/auth/login") {
            return@onCall
        }

        val remote = pluginConfig.peerAddressResolver(call)
        val networkAccessEnabled = pluginConfig.networkAccessEnabledProvider()
        val loopback = isLoopbackAddress(remote)
        val tailscaleOnly = pluginConfig.tailscaleOnlyProvider()

        if (!loopback && networkAccessEnabled && tailscaleOnly) {
            if (!isTailscalePeerAddress(remote)) {
                call.respondText(
                    """{"error":"forbidden: Tailscale-only Network Access (peer not on Tailscale)"}""",
                    status = HttpStatusCode.Forbidden,
                )
                return@onCall
            }
        }

        if (loopback && !networkAccessEnabled) {
            call.attributes.put(
                NetworkAccessAuthFingerprintKey,
                LocalLoopbackPushOwnerFingerprint,
            )
            return@onCall
        }

        val limiter = if (path == "/api/auth/login") loginLimiter else ipLimiter
        if (limiter.isBlocked(remote)) {
            call.respondText(
                """{"error":"too many failed auth attempts"}""",
                status = HttpStatusCode.TooManyRequests,
            )
            return@onCall
        }

        val expectedMaster = pluginConfig.tokenProvider().trim()
        val provided = call.request.extractAccessToken()
        val resolved = pluginConfig.sessionStore.resolveAuth(provided, expectedMaster)
        val requiredScope = requiredScopeForPath(path)

        if (resolved != null && !scopeAllows(requiredScope, resolved.scope)) {
            if (isWebSocketPath(path)) {
                call.attributes.put(NetworkAccessWsScopeRejectedKey, true)
                return@onCall
            }
            call.respondText(
                """{"error":"forbidden: chat credentials cannot access MCP endpoints"}""",
                status = HttpStatusCode.Forbidden,
            )
            return@onCall
        }

        if (resolved != null) {
            if (provided != null && path != "/api/auth/login") {
                tokenLimiter.clear(pluginConfig.sessionStore.fingerprint(provided))
            }
            ipLimiter.clear(remote)
            call.attributes.put(
                NetworkAccessAuthFingerprintKey,
                resolved.fingerprint,
            )
            return@onCall
        }

        if (provided != null) {
            tokenLimiter.recordFailure(pluginConfig.sessionStore.fingerprint(provided))
        }
        limiter.recordFailure(remote)
        System.err.println(
            "andy-mcp: unauthorized ${call.request.uriForLog()} from $remote",
        )

        if (isWebSocketPath(path)) {
            call.attributes.put(NetworkAccessWsAuthRejectedKey, true)
            return@onCall
        }

        call.respondText(
            """{"error":"unauthorized"}""",
            status = HttpStatusCode.Unauthorized,
        )
    }
}

/** Static SPA assets must load without Authorization so login/QR bootstrap works. */
internal fun isPublicWebChatPath(path: String): Boolean {
    val normalized = path.substringBefore('?').let { raw ->
        when {
            raw.isEmpty() -> "/"
            raw.length > 1 && raw.endsWith('/') -> raw.dropLast(1)
            else -> raw
        }
    }
    return when {
        normalized == "/" || normalized == "/index.html" -> true
        normalized == "/app.js" || normalized == "/styles.css" -> true
        normalized == "/manifest.json" || normalized == "/sw.js" -> true
        normalized.startsWith("/icons/") -> true
        else -> false
    }
}

internal fun isWebSocketPath(path: String): Boolean =
    path == "/ws" || path.startsWith("/ws/")

/**
 * REST requires `Authorization: Bearer`. WebSocket accepts, in order:
 * handshake header, `Sec-WebSocket-Protocol: bearer.<token>`, or query `?token=` (legacy).
 */
internal fun extractAccessToken(
    authorizationHeader: String?,
    path: String,
    queryToken: String?,
    webSocketSubprotocol: String? = null,
): String? {
    val header = authorizationHeader?.trim().orEmpty()
    if (header.startsWith("Bearer ", ignoreCase = true)) {
        return header.substring(7).trim().ifBlank { null }
    }
    if (isWebSocketPath(path)) {
        val fromSubprotocol = parseBearerWebSocketSubprotocol(webSocketSubprotocol)
        if (fromSubprotocol != null) return fromSubprotocol
        return queryToken?.trim()?.ifBlank { null }
    }
    return null
}

internal fun parseBearerWebSocketSubprotocol(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    for (part in raw.split(',')) {
        val trimmed = part.trim()
        if (trimmed.startsWith("bearer.", ignoreCase = true)) {
            return trimmed.substringAfter('.').trim().ifBlank { null }
        }
    }
    return null
}

internal fun ApplicationRequest.extractAccessToken(): String? =
    extractAccessToken(
        authorizationHeader = headers["Authorization"],
        path = path(),
        queryToken = queryParameters["token"],
        webSocketSubprotocol = headers[HttpHeaders.SecWebSocketProtocol],
    )

/** Scrub `token` from a query string before writing access logs. */
internal fun scrubTokenQuery(query: String?): String {
    if (query.isNullOrBlank()) return ""
    return query.split('&').filterNot { param ->
        param.startsWith("token=", ignoreCase = true) ||
            param.startsWith("code=", ignoreCase = true)
    }.joinToString("&")
}

/** Path + query suitable for access logs (token/code query params removed). */
internal fun ApplicationRequest.uriForLog(): String {
    val path = path()
    val query = scrubTokenQuery(queryString())
    return if (query.isEmpty()) path else "$path?$query"
}

/**
 * Raw TCP peer address from the socket — never X-Forwarded-For / origin headers,
 * and never a reverse-DNS hostname (`remoteHost`).
 */
internal fun ApplicationCall.remotePeerAddress(): String =
    normalizePeerAddress(request.local.remoteAddress)

/** Strip brackets / trailing :port; keep the literal address string. */
internal fun normalizePeerAddress(raw: String): String {
    var s = raw.trim()
    if (s.startsWith("/")) s = s.removePrefix("/")
    if (s.startsWith("[")) {
        val end = s.indexOf(']')
        if (end > 0) return s.substring(1, end)
    }
    // ipv4:port
    if (s.count { it == ':' } == 1) {
        val host = s.substringBefore(':')
        if (IPV4_LITERAL.matches(host)) return host
    }
    return s
}

private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")

/**
 * Loopback check on a **literal** IP only. Hostnames are never treated as loopback
 * (no DNS), so a reverse-DNS name that happens to resolve to 127.0.0.1 cannot bypass auth.
 */
internal fun isLoopbackAddress(hostOrIp: String): Boolean {
    if (hostOrIp.isBlank()) return false
    val normalized = normalizePeerAddress(hostOrIp)
    if (!looksLikeLiteralIp(normalized)) return false
    return runCatching { InetAddress.getByName(normalized).isLoopbackAddress }.getOrDefault(false)
}

/** @deprecated Use [isTailscalePeerAddress] — kept for tests migrating from CGNAT-only check. */
internal fun isTailscaleCgnatAddress(hostOrIp: String): Boolean =
    isTailscalePeerAddress(hostOrIp)

private fun looksLikeLiteralIp(value: String): Boolean {
    if (IPV4_LITERAL.matches(value)) return true
    return value.contains(':')
}

internal fun generateNetworkAccessTokenBytes(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal fun constantTimeEquals(a: String, b: String): Boolean {
    val left = a.toByteArray(Charsets.UTF_8)
    val right = b.toByteArray(Charsets.UTF_8)
    return MessageDigest.isEqual(left, right)
}

/**
 * Pure auth decision for unit tests (decouided from sockets).
 * Returns null when allowed, otherwise the HTTP status to return.
 */
internal fun evaluateNetworkAccessAuth(
    remoteHost: String,
    tokenHeaderOrQuery: String?,
    expectedToken: String,
    limiter: AuthFailureLimiter,
    networkAccessEnabled: Boolean = false,
    tailscaleOnly: Boolean = true,
    requiredScope: NetworkAccessScope = NetworkAccessScope.CHAT,
    sessionStore: NetworkAccessSessionStore = NetworkAccessSessionStore(),
): HttpStatusCode? {
    val loopback = isLoopbackAddress(remoteHost)
    if (!loopback && networkAccessEnabled && tailscaleOnly && !isTailscalePeerAddress(remoteHost)) {
        return HttpStatusCode.Forbidden
    }
    if (loopback && !networkAccessEnabled) return null
    if (limiter.isBlocked(remoteHost)) return HttpStatusCode.TooManyRequests
    val resolved = sessionStore.resolveAuth(tokenHeaderOrQuery, expectedToken)
        ?: return HttpStatusCode.Unauthorized.also { limiter.recordFailure(remoteHost) }
    if (!scopeAllows(requiredScope, resolved.scope)) return HttpStatusCode.Forbidden
    limiter.clear(remoteHost)
    return null
}

internal class AuthFailureLimiter(
    private val maxFailures: Int,
    private val windowMillis: Long,
    private val cooldownMillis: Long,
    private val clock: () -> Long,
) {
    private class Bucket {
        val failures: MutableList<Long> = mutableListOf()
        var blockedUntil: Long = 0L
    }

    private val byKey = ConcurrentHashMap<String, Bucket>()

    fun isBlocked(key: String): Boolean {
        val bucket = byKey[key] ?: return false
        synchronized(bucket) {
            val now = clock()
            if (bucket.blockedUntil > now) return true
            bucket.failures.removeAll { now - it > windowMillis }
            return false
        }
    }

    fun recordFailure(key: String) {
        val bucket = byKey.getOrPut(key) { Bucket() }
        synchronized(bucket) {
            val now = clock()
            bucket.failures.removeAll { now - it > windowMillis }
            bucket.failures += now
            if (bucket.failures.size >= maxFailures) {
                bucket.blockedUntil = now + cooldownMillis
                bucket.failures.clear()
            }
        }
    }

    fun resetKey(key: String) {
        byKey.remove(key)
    }

    @Deprecated("Use resetKey", ReplaceWith("resetKey(key)"))
    fun clear(key: String) = resetKey(key)

    fun failureCount(key: String): Int {
        val bucket = byKey[key] ?: return 0
        synchronized(bucket) {
            return bucket.failures.size
        }
    }
}