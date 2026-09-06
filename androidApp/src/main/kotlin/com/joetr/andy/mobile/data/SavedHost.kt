package com.joetr.andy.mobile.data

import app.andy.service.RemoteHostCapabilities
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class SavedHost(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    /** MagicDNS name, Tailscale IP, or LAN hostname — used for VNC and as URL host fallback. */
    val address: String,
    val vncPort: Int = RemoteHostCapabilities.DefaultVncPort,
    /**
     * Network Access base URL (Tailscale Serve HTTPS or LAN HTTP), e.g.
     * `https://laptop.tailnet.ts.net` or `http://100.x.y.z:8565`.
     * When blank, derived as `http://$address:8565`.
     */
    val networkAccessBaseUrl: String = "",
    val notes: String = "",
    /**
     * Optional macOS account name for ARD auth. Leave blank when using the
     * Screen Sharing “VNC viewers may control screen with password” password.
     */
    val vncUsername: String = "",
) {
    fun resolvedNetworkAccessBaseUrl(): String {
        val trimmed = networkAccessBaseUrl.trim().trimEnd('/')
        if (trimmed.isNotEmpty()) return trimmed
        val host = address.trim()
        return if (host.startsWith("http://") || host.startsWith("https://")) {
            host.trimEnd('/')
        } else {
            "http://$host:8565"
        }
    }

    fun vncHost(): String {
        val trimmed = address.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") ->
                trimmed.substringAfter("://").substringBefore('/').substringBefore(':')
            else -> trimmed.substringBefore(':')
        }
    }
}

enum class HostReachability {
    Unknown,
    Checking,
    VncOpen,
    Unreachable,
}

data class HostStatus(
    val reachability: HostReachability = HostReachability.Unknown,
    val message: String? = null,
)
