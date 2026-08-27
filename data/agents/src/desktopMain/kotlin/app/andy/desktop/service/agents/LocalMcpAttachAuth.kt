package app.andy.desktop.service.agents

import java.util.concurrent.atomic.AtomicReference

/**
 * Bearer token for local MCP attach while Network Access is on.
 *
 * Loopback is token-gated in that mode (so Tailscale Serve cannot bypass auth).
 * Terminal-lane adapters read [bearerToken] when building `--mcp-config` JSON;
 * ACP uses [AndyMcpEndpoint.bearerToken] instead.
 */
internal object LocalMcpAttachAuth {
    private val token = AtomicReference<String?>(null)

    fun <T> withBearerToken(value: String?, block: () -> T): T {
        val next = value?.trim()?.takeIf { it.isNotEmpty() }
        val previous = token.getAndSet(next)
        return try {
            block()
        } finally {
            token.set(previous)
        }
    }

    fun bearerToken(): String? = token.get()
}

/** JSON fragment for HTTP MCP server entries that support a `headers` map. */
internal fun andyHttpMcpServerJson(url: String, bearerToken: String?): String {
    val headers = bearerToken?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
        ""","headers":{"Authorization":"Bearer $token"}"""
    }.orEmpty()
    return """{"mcpServers":{"andy":{"type":"http","url":"$url"$headers}}}"""
}
