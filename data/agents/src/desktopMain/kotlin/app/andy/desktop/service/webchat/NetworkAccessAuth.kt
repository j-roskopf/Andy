package app.andy.desktop.service.webchat

import app.andy.desktop.service.proxy.isCarrierGradeNat
import io.ktor.http.HttpStatusCode
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

/**
 * Set by [NetworkAccessAuthPlugin] when a WebSocket handshake failed auth.
 * The WS route upgrades then closes with [NetworkAccessAuthFailureCloseCode]
 * (browsers cannot observe a useful close code from a pre-upgrade HTTP 401).
 */
internal val NetworkAccessWsAuthRejectedKey =
    AttributeKey<Boolean>("NetworkAccessWsAuthRejected")

internal class NetworkAccessAuthConfig {
    var tokenProvider: () -> String = { "" }
    /** When true, loopback also requires the token (closes Tailscale Serve / reverse-proxy bypass). */
    var networkAccessEnabledProvider: () -> Boolean = { false }
    /**
     * When Network Access is on and this is true, non-loopback peers must be Tailscale CGNAT
     * (`100.64/10`). LAN / other VPN addresses get 403.
     */
    var tailscaleOnlyProvider: () -> Boolean = { true }
    var maxFailures: Int = 10
    var windowMillis: Long = 5 * 60_000L
    var cooldownMillis: Long = 5 * 60_000L
    var clock: () -> Long = { System.currentTimeMillis() }
    /** Resolves the TCP peer address; overridable in tests to simulate non-loopback. */
    var peerAddressResolver: (ApplicationCall) -> String = { it.remotePeerAddress() }
}

/**
 * Gates Network Access:
 * - Static web-chat assets stay public (login/QR bootstrap).
 * - When Network Access is **off**, loopback is unauthenticated (vendor CLIs).
 * - When Network Access is **on**, every non-public route needs the bearer token —
 *   including loopback — so Tailscale Serve / localhost reverse proxies cannot bypass auth.
 * - Optional Tailscale-only filter rejects non-CGNAT remote peers with 403.
 */
internal val NetworkAccessAuthPlugin = createApplicationPlugin(
    name = "NetworkAccessAuth",
    createConfiguration = ::NetworkAccessAuthConfig,
) {
    val limiter = AuthFailureLimiter(
        maxFailures = pluginConfig.maxFailures,
        windowMillis = pluginConfig.windowMillis,
        cooldownMillis = pluginConfig.cooldownMillis,
        clock = pluginConfig.clock,
    )

    onCall { call ->
        val path = call.request.path()
        if (isPublicWebChatPath(path)) {
            return@onCall
        }

        val remote = pluginConfig.peerAddressResolver(call)
        val networkAccessEnabled = pluginConfig.networkAccessEnabledProvider()
        val loopback = isLoopbackAddress(remote)

        if (!loopback && networkAccessEnabled && pluginConfig.tailscaleOnlyProvider()) {
            if (!isTailscaleCgnatAddress(remote)) {
                call.respondText(
                    """{"error":"forbidden: Tailscale-only Network Access (peer not on 100.64/10)"}""",
                    status = HttpStatusCode.Forbidden,
                )
                return@onCall
            }
        }

        // Local vendor CLIs keep working when Network Access is off.
        if (loopback && !networkAccessEnabled) {
            return@onCall
        }

        if (limiter.isBlocked(remote)) {
            call.respondText(
                """{"error":"too many failed auth attempts"}""",
                status = HttpStatusCode.TooManyRequests,
            )
            return@onCall
        }
        val expected = pluginConfig.tokenProvider().trim()
        val provided = call.request.extractAccessToken()
        val authorized = expected.isNotEmpty() &&
            provided != null &&
            constantTimeEquals(provided, expected)
        if (authorized) {
            limiter.clear(remote)
            return@onCall
        }

        limiter.recordFailure(remote)
        System.err.println(
            "andy-mcp: unauthorized ${call.request.uriForLog()} from $remote",
        )

        if (isWebSocketPath(path)) {
            // Allow the upgrade so the WS handler can close with 4401 (HTTP 401
            // during handshake surfaces as an opaque abnormal close in browsers).
            call.attributes.put(NetworkAccessWsAuthRejectedKey, true)
            return@onCall
        }

        call.respondText(
            """{"error":"unauthorized"}""",
            status = HttpStatusCode.Unauthorized,
        )
    }
}

/** Static SPA assets must load without Authorization so QR/`?token=` login can bootstrap. */
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
 * REST/`/mcp*` require `Authorization: Bearer`. Query `?token=` is accepted only on
 * `/ws/...` (browsers cannot set WS handshake headers).
 */
internal fun extractAccessToken(
    authorizationHeader: String?,
    path: String,
    queryToken: String?,
): String? {
    val header = authorizationHeader?.trim().orEmpty()
    if (header.startsWith("Bearer ", ignoreCase = true)) {
        return header.substring(7).trim().ifBlank { null }
    }
    if (isWebSocketPath(path)) {
        return queryToken?.trim()?.ifBlank { null }
    }
    return null
}

internal fun ApplicationRequest.extractAccessToken(): String? =
    extractAccessToken(
        authorizationHeader = headers["Authorization"],
        path = path(),
        queryToken = queryParameters["token"],
    )

/** Scrub `token` from a query string before writing access logs. */
internal fun scrubTokenQuery(query: String?): String {
    if (query.isNullOrBlank()) return ""
    return query.split('&').filterNot { it.startsWith("token=", ignoreCase = true) }.joinToString("&")
}

/** Path + query suitable for access logs (token query param removed). */
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

/** Tailscale userspace / CGNAT range 100.64.0.0/10. */
internal fun isTailscaleCgnatAddress(hostOrIp: String): Boolean {
    val normalized = normalizePeerAddress(hostOrIp)
    return isCarrierGradeNat(normalized)
}

private fun looksLikeLiteralIp(value: String): Boolean {
    if (IPV4_LITERAL.matches(value)) return true
    // IPv6 literals always contain ':'
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
 * Pure auth decision for unit tests (decoupled from sockets).
 * Returns null when allowed, otherwise the HTTP status to return.
 * WebSocket paths are not modeled here — they use the 4401 close path instead.
 */
internal fun evaluateNetworkAccessAuth(
    remoteHost: String,
    tokenHeaderOrQuery: String?,
    expectedToken: String,
    limiter: AuthFailureLimiter,
    networkAccessEnabled: Boolean = false,
    tailscaleOnly: Boolean = true,
): HttpStatusCode? {
    val loopback = isLoopbackAddress(remoteHost)
    if (!loopback && networkAccessEnabled && tailscaleOnly && !isTailscaleCgnatAddress(remoteHost)) {
        return HttpStatusCode.Forbidden
    }
    if (loopback && !networkAccessEnabled) return null
    if (limiter.isBlocked(remoteHost)) return HttpStatusCode.TooManyRequests
    if (expectedToken.isBlank()) return HttpStatusCode.Unauthorized
    if (tokenHeaderOrQuery.isNullOrBlank() || !constantTimeEquals(tokenHeaderOrQuery, expectedToken)) {
        limiter.recordFailure(remoteHost)
        return HttpStatusCode.Unauthorized
    }
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

    private val byIp = ConcurrentHashMap<String, Bucket>()

    fun isBlocked(ip: String): Boolean {
        val bucket = byIp[ip] ?: return false
        synchronized(bucket) {
            val now = clock()
            if (bucket.blockedUntil > now) return true
            bucket.failures.removeAll { now - it > windowMillis }
            return false
        }
    }

    fun recordFailure(ip: String) {
        val bucket = byIp.getOrPut(ip) { Bucket() }
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

    fun clear(ip: String) {
        byIp.remove(ip)
    }

    fun failureCount(ip: String): Int {
        val bucket = byIp[ip] ?: return 0
        synchronized(bucket) {
            return bucket.failures.size
        }
    }
}
