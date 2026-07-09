package app.andy.desktop.service.proxy

internal data class VpnRouteState(val active: Boolean, val name: String?)

internal fun parseVpnRouteState(dumpsysConnectivity: String): VpnRouteState {
    val lines = dumpsysConnectivity.lines()
    val vpnLine = lines.firstOrNull { line ->
        line.contains("TRANSPORT_VPN") ||
            line.contains("type: VPN", ignoreCase = true) ||
            (line.contains("VPN") && line.contains("CONNECTED", ignoreCase = true))
    } ?: return VpnRouteState(active = false, name = null)
    val ownerLine = lines.firstOrNull { line ->
        line.contains("mOwnerName=", ignoreCase = true) ||
            line.contains("owner=", ignoreCase = true) ||
            (line.contains("vpn", ignoreCase = true) && line.contains("package", ignoreCase = true))
    }
    val name = ownerLine
        ?.let { Regex("""(?:mOwnerName|owner|packageName|package)=([A-Za-z0-9_.-]+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1) }
        ?: Regex("""\b([a-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_-]+){2,})\b""").find(vpnLine)?.groupValues?.getOrNull(1)
    return VpnRouteState(active = true, name = name)
}

internal data class HostProxyState(
    val active: Boolean,
    val summary: String? = null,
    val upstreamProxy: String? = null,
    val bypassLooksSafe: Boolean = true,
)

internal data class HostVpnState(val active: Boolean, val summary: String? = null)

internal fun parseMacProxyState(scutilProxy: String): HostProxyState {
    val entries = scutilProxy.lineSequence()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (":" !in trimmed) return@mapNotNull null
            trimmed.substringBefore(":").trim() to trimmed.substringAfter(":").trim()
        }
        .toMap()
    val http = proxyEndpoint(entries, "HTTP")
    val https = proxyEndpoint(entries, "HTTPS")
    val socks = proxyEndpoint(entries, "SOCKS")
    val upstream = https ?: http ?: socks
    val activeParts = listOfNotNull(
        http?.let { "HTTP $it" },
        https?.let { "HTTPS $it" },
        socks?.let { "SOCKS $it" },
        entries["ProxyAutoConfigEnable"]?.takeIf { it == "1" }?.let { entries["ProxyAutoConfigURLString"]?.let { url -> "PAC $url" } ?: "PAC" },
    )
    val active = activeParts.isNotEmpty()
    val exceptions = scutilProxy.lineSequence()
        .map { it.trim() }
        .filter { Regex("""^\d+\s*:""").containsMatchIn(it) }
        .map { it.substringAfter(":").trim() }
        .toList()
    val simpleHostsExcluded = entries["ExcludeSimpleHostnames"] == "1"
    val bypassLooksSafe = !active || simpleHostsExcluded || listOf("localhost", "127.0.0.1", "10.0.2.2").all { expected ->
        exceptions.any { exception -> proxyExceptionCovers(exception, expected) }
    }
    return HostProxyState(
        active = active,
        summary = activeParts.joinToString(", ").takeIf { it.isNotBlank() },
        upstreamProxy = upstream,
        bypassLooksSafe = bypassLooksSafe,
    )
}

internal fun proxyEndpoint(entries: Map<String, String>, prefix: String): String? {
    if (entries["${prefix}Enable"] != "1") return null
    val host = entries["${prefix}Proxy"]?.takeIf { it.isNotBlank() } ?: return null
    val port = entries["${prefix}Port"]?.takeIf { it.isNotBlank() } ?: return null
    val scheme = if (prefix == "SOCKS") "socks5" else "http"
    val authorityHost = if (':' in host && !host.startsWith("[")) "[$host]" else host
    return "$scheme://$authorityHost:$port"
}

internal fun isLocalHostProxy(upstream: String?): Boolean {
    if (upstream.isNullOrBlank()) return false
    val authority = upstream
        .substringAfter("://", missingDelimiterValue = upstream)
        .substringBefore('/')
    val host = when {
        authority.startsWith("[") -> authority.substringBefore(']') + "]"
        authority.count { it == ':' } > 1 -> authority // unbracketed IPv6 without port, or already host-only
        else -> authority.substringBefore(':')
    }.lowercase()
    return host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
}

internal fun proxyExceptionCovers(exception: String, expected: String): Boolean {
    val normalized = exception.trim().lowercase()
    val target = expected.lowercase()
    return normalized == target ||
        normalized == "<local>" && target == "localhost" ||
        normalized == "*.local" && target.endsWith(".local") ||
        normalized.endsWith(".*") && target.startsWith(normalized.removeSuffix(".*")) ||
        normalized.endsWith("/8") && normalized.substringBefore("/") == "10.0.0.0" && target.startsWith("10.") ||
        normalized.endsWith("/16") && target.startsWith(normalized.substringBeforeLast('.').removeSuffix(".0")) ||
        normalized.endsWith("/24") && target.startsWith(normalized.substringBeforeLast('.'))
}

