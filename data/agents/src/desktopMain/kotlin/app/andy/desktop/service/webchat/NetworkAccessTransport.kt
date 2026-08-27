package app.andy.desktop.service.webchat

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import java.net.InetAddress

/** Tailscale IPv4 CGNAT (100.64/10) and IPv6 ULA (fd7a:115c:a1e0::/48). */
internal fun isTailscalePeerAddress(hostOrIp: String): Boolean {
    val normalized = normalizePeerAddress(hostOrIp)
    if (isCarrierGradeNatIpv4(normalized)) return true
    return isTailscaleUlaIpv6(normalized)
}

internal fun isCarrierGradeNatIpv4(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    val a = parts[0].toIntOrNull() ?: return false
    val b = parts[1].toIntOrNull() ?: return false
    return a == 100 && b in 64..127
}

/** fd7a:115c:a1e0::/48 — Tailscale IPv6 ULA range. */
internal fun isTailscaleUlaIpv6(host: String): Boolean {
    if (!host.contains(':')) return false
    val lower = host.lowercase()
    if (lower.startsWith("fd7a:115c:a1e0:")) return true
    return runCatching {
        val address = InetAddress.getByName(lower)
        val bytes = address.address ?: return false
        if (bytes.size != 16) return false
        bytes[0] == 0xfd.toByte() &&
            bytes[1] == 0x7a.toByte() &&
            bytes[2] == 0x11.toByte() &&
            bytes[3] == 0x5c.toByte() &&
            bytes[4] == 0xa1.toByte() &&
            bytes[5] == 0xe0.toByte()
    }.getOrDefault(false)
}

/**
 * Remote LAN mode requires TLS (direct HTTPS or `X-Forwarded-Proto: https` from
 * Tailscale Serve / a reverse proxy). Loopback stays plain HTTP for local dev.
 */
internal fun isSecureRequest(call: ApplicationCall): Boolean {
    val remote = normalizePeerAddress(call.request.local.remoteAddress)
    if (isLoopbackAddress(remote)) return true
    val forwarded = call.request.header(HttpHeaders.XForwardedProto)?.trim()?.lowercase()
    if (forwarded == "https") return true
    return false
}

internal fun requiresTlsForRemoteLan(
    networkAccessEnabled: Boolean,
    tailscaleOnly: Boolean,
    remoteHost: String,
    call: ApplicationCall,
): Boolean {
    if (!networkAccessEnabled || tailscaleOnly) return false
    if (isLoopbackAddress(remoteHost)) return false
    return !isSecureRequest(call)
}
