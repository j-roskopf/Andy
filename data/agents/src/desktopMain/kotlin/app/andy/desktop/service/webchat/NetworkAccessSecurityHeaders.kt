package app.andy.desktop.service.webchat

import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.path
import io.ktor.server.response.header

/**
 * Baseline browser hardening for the web-chat surface and JSON API.
 * HSTS is added only when the inbound request looks TLS-terminated.
 */
internal val NetworkAccessSecurityHeadersPlugin = createApplicationPlugin(
    name = "NetworkAccessSecurityHeaders",
) {
    onCall { call ->
        val path = call.request.path()
        if (!path.startsWith("/api/") && !isPublicWebChatPath(path)) {
            return@onCall
        }
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("X-Frame-Options", "DENY")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data:; connect-src 'self'; manifest-src 'self'; worker-src 'self'",
        )
        if (isSecureRequest(call)) {
            call.response.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        }
    }
}

internal fun Application.installNetworkAccessSecurityHeaders() {
    install(NetworkAccessSecurityHeadersPlugin)
}
