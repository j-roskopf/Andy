package app.andy.desktop.service.hub

import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * OAuth (PKCE + dynamic client registration) for remote HTTP MCP servers such as Sentry.
 */
object McpHubOAuthLogin {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    data class AuthorizationServerMetadata(
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val registrationEndpoint: String?,
    )

    fun discover(mcpUrl: String): AuthorizationServerMetadata {
        val base = mcpUrl.trimEnd('/')
        val candidates = listOf(
            "$base/.well-known/oauth-authorization-server",
            // Some hosts serve metadata at origin root.
            URI.create(base).let { uri ->
                "${uri.scheme}://${uri.authority}/.well-known/oauth-authorization-server"
            },
        )
        for (url in candidates) {
            val body = getJson(url) ?: continue
            val auth = body["authorization_endpoint"]?.jsonPrimitive?.contentOrNull ?: continue
            val token = body["token_endpoint"]?.jsonPrimitive?.contentOrNull ?: continue
            val registration = body["registration_endpoint"]?.jsonPrimitive?.contentOrNull
            return AuthorizationServerMetadata(auth, token, registration)
        }
        error("Could not discover OAuth metadata for $mcpUrl")
    }

    fun login(
        mcpUrl: String,
        existingClientId: String? = null,
        openBrowser: (URI) -> Unit = ::defaultBrowse,
        timeoutSeconds: Long = 180,
    ): McpHubOAuthTokens {
        val meta = discover(mcpUrl)
        val callback = startCallbackServer()
        try {
            val redirectUri = "http://127.0.0.1:${callback.port}/callback"
            val clientId = existingClientId?.takeIf { it.isNotBlank() }
                ?: registerClient(meta.registrationEndpoint, redirectUri)
                ?: error("OAuth server requires dynamic client registration but none is advertised")

            val verifier = pkceVerifier()
            val challenge = pkceChallenge(verifier)
            val state = randomUrlSafe(16)
            val authUrl = buildString {
                append(meta.authorizationEndpoint)
                append(if (meta.authorizationEndpoint.contains('?')) '&' else '?')
                append("response_type=code")
                append("&client_id=").append(enc(clientId))
                append("&redirect_uri=").append(enc(redirectUri))
                append("&state=").append(enc(state))
                append("&code_challenge=").append(enc(challenge))
                append("&code_challenge_method=S256")
                // Sentry scopes; harmless if ignored.
                append("&scope=").append(enc("org:read project:write team:write event:write"))
            }
            openBrowser(URI(authUrl))
            val code = callback.awaitCode(state, timeoutSeconds)
            return exchangeCode(
                tokenEndpoint = meta.tokenEndpoint,
                clientId = clientId,
                redirectUri = redirectUri,
                code = code,
                verifier = verifier,
            )
        } finally {
            callback.close()
        }
    }

    fun refresh(
        mcpUrl: String,
        tokens: McpHubOAuthTokens,
    ): McpHubOAuthTokens {
        val refresh = tokens.refreshToken?.takeIf { it.isNotBlank() }
            ?: error("No refresh token available")
        val clientId = tokens.clientId?.takeIf { it.isNotBlank() }
            ?: error("No OAuth client_id stored for refresh")
        val meta = discover(mcpUrl)
        val form = buildString {
            append("grant_type=refresh_token")
            append("&refresh_token=").append(enc(refresh))
            append("&client_id=").append(enc(clientId))
        }
        val body = postForm(meta.tokenEndpoint, form)
        return parseTokenResponse(body, clientId, fallbackRefresh = refresh)
    }

    private fun registerClient(registrationEndpoint: String?, redirectUri: String): String? {
        if (registrationEndpoint.isNullOrBlank()) return null
        val payload = buildJsonObject {
            put("client_name", "Andy MCP Hub")
            putJsonArray("redirect_uris") { add(kotlinx.serialization.json.JsonPrimitive(redirectUri)) }
            putJsonArray("grant_types") {
                add(kotlinx.serialization.json.JsonPrimitive("authorization_code"))
                add(kotlinx.serialization.json.JsonPrimitive("refresh_token"))
            }
            putJsonArray("response_types") { add(kotlinx.serialization.json.JsonPrimitive("code")) }
            put("token_endpoint_auth_method", "none")
        }
        val req = HttpRequest.newBuilder(URI.create(registrationEndpoint))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            error("OAuth client registration failed (${resp.statusCode()}): ${resp.body().take(300)}")
        }
        val obj = json.parseToJsonElement(resp.body()).jsonObject
        return obj["client_id"]?.jsonPrimitive?.contentOrNull
            ?: error("OAuth registration response missing client_id")
    }

    private fun exchangeCode(
        tokenEndpoint: String,
        clientId: String,
        redirectUri: String,
        code: String,
        verifier: String,
    ): McpHubOAuthTokens {
        val form = buildString {
            append("grant_type=authorization_code")
            append("&code=").append(enc(code))
            append("&redirect_uri=").append(enc(redirectUri))
            append("&client_id=").append(enc(clientId))
            append("&code_verifier=").append(enc(verifier))
        }
        val body = postForm(tokenEndpoint, form)
        return parseTokenResponse(body, clientId, fallbackRefresh = null)
    }

    private fun parseTokenResponse(
        body: String,
        clientId: String,
        fallbackRefresh: String?,
    ): McpHubOAuthTokens {
        val obj = json.parseToJsonElement(body).jsonObject
        val access = obj["access_token"]?.jsonPrimitive?.contentOrNull
            ?: error("Token response missing access_token: ${body.take(300)}")
        val refresh = obj["refresh_token"]?.jsonPrimitive?.contentOrNull ?: fallbackRefresh
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        return McpHubOAuthTokens(
            accessToken = access,
            refreshToken = refresh,
            tokenType = obj["token_type"]?.jsonPrimitive?.contentOrNull ?: "bearer",
            expiresAtEpochMs = expiresIn?.let { System.currentTimeMillis() + it * 1000L },
            scope = obj["scope"]?.jsonPrimitive?.contentOrNull,
            clientId = clientId,
        )
    }

    private fun postForm(url: String, form: String): String {
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            error("OAuth token request failed (${resp.statusCode()}): ${resp.body().take(300)}")
        }
        return resp.body()
    }

    private fun getJson(url: String): kotlinx.serialization.json.JsonObject? {
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json")
            .GET()
            .build()
        val resp = runCatching { http.send(req, HttpResponse.BodyHandlers.ofString()) }.getOrNull()
            ?: return null
        if (resp.statusCode() !in 200..299) return null
        return runCatching { json.parseToJsonElement(resp.body()).jsonObject }.getOrNull()
    }

    private class CallbackServer(
        val port: Int,
        private val server: HttpServer,
        private val future: CompletableFuture<Pair<String, String>>,
    ) {
        fun awaitCode(expectedState: String, timeoutSeconds: Long): String {
            val (code, state) = future.get(timeoutSeconds, TimeUnit.SECONDS)
            if (state != expectedState) error("OAuth state mismatch")
            return code
        }

        fun close() {
            runCatching { server.stop(0) }
        }
    }

    private fun startCallbackServer(): CallbackServer {
        val future = CompletableFuture<Pair<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/callback") { exchange ->
            val query = exchange.requestURI.query.orEmpty()
            val params = query.split('&').mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null
                else part.substring(0, idx) to java.net.URLDecoder.decode(part.substring(idx + 1), StandardCharsets.UTF_8)
            }.toMap()
            val error = params["error"]
            val html: String
            val code = 200
            if (error != null) {
                future.completeExceptionally(IllegalStateException("OAuth error: $error ${params["error_description"].orEmpty()}"))
                html = "<html><body><h2>Andy MCP Hub</h2><p>Sign-in failed. You can close this window.</p></body></html>"
            } else {
                val authCode = params["code"]
                val state = params["state"].orEmpty()
                if (authCode.isNullOrBlank()) {
                    future.completeExceptionally(IllegalStateException("OAuth callback missing code"))
                    html = "<html><body><h2>Andy MCP Hub</h2><p>Missing authorization code.</p></body></html>"
                } else {
                    future.complete(authCode to state)
                    html = "<html><body><h2>Andy MCP Hub</h2><p>Signed in. You can close this window and return to Andy.</p></body></html>"
                }
            }
            val bytes = html.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.executor = null
        server.start()
        return CallbackServer(server.address.port, server, future)
    }

    private fun defaultBrowse(uri: URI) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(uri)
            return
        }
        // Fallback for headless / restricted environments.
        val os = System.getProperty("os.name").lowercase()
        val cmd = when {
            os.contains("mac") -> listOf("open", uri.toString())
            os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", uri.toString())
            else -> listOf("xdg-open", uri.toString())
        }
        ProcessBuilder(cmd).start()
    }

    private fun pkceVerifier(): String = randomUrlSafe(32)

    private fun pkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)
}
