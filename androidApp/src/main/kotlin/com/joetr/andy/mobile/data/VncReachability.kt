package com.joetr.andy.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket

object VncReachability {
    suspend fun probe(host: String, port: Int, timeoutMs: Long = 2_500): HostStatus =
        withContext(Dispatchers.IO) {
            val open = withTimeoutOrNull(timeoutMs) {
                runCatching {
                    Socket().use { socket ->
                        socket.tcpNoDelay = true
                        socket.connect(InetSocketAddress(host, port), timeoutMs.toInt())
                        true
                    }
                }.getOrDefault(false)
            } == true
            if (open) {
                HostStatus(
                    reachability = HostReachability.VncOpen,
                    message = "VNC listening on $host:$port",
                )
            } else {
                HostStatus(
                    reachability = HostReachability.Unreachable,
                    message = "Nothing answered on $host:$port — enable Screen Sharing / start a VNC server, and stay on Tailscale.",
                )
            }
        }
}
