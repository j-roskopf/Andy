package app.andy.desktop.service.webchat

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkAccessAuthTest {
    @Test
    fun loopbackBypassesTokenWhenNetworkAccessOff() {
        val limiter = AuthFailureLimiter(10, 60_000, 60_000) { 0L }
        assertNull(evaluateNetworkAccessAuth("127.0.0.1", null, "secret", limiter))
        assertNull(evaluateNetworkAccessAuth("::1", null, "secret", limiter))
        assertNull(evaluateNetworkAccessAuth("127.0.0.2", null, "secret", limiter))
    }

    @Test
    fun loopbackRequiresTokenWhenNetworkAccessOn() {
        val limiter = AuthFailureLimiter(10, 60_000, 60_000) { 0L }
        assertEquals(
            HttpStatusCode.Unauthorized,
            evaluateNetworkAccessAuth(
                "127.0.0.1",
                null,
                "secret",
                limiter,
                networkAccessEnabled = true,
            ),
        )
        assertNull(
            evaluateNetworkAccessAuth(
                "127.0.0.1",
                "secret",
                "secret",
                limiter,
                networkAccessEnabled = true,
            ),
        )
    }

    @Test
    fun tailscaleOnlyRejectsLanPeers() {
        val limiter = AuthFailureLimiter(10, 60_000, 60_000) { 0L }
        assertEquals(
            HttpStatusCode.Forbidden,
            evaluateNetworkAccessAuth(
                "192.168.1.20",
                "secret",
                "secret",
                limiter,
                networkAccessEnabled = true,
                tailscaleOnly = true,
            ),
        )
        assertNull(
            evaluateNetworkAccessAuth(
                "100.72.168.32",
                "secret",
                "secret",
                limiter,
                networkAccessEnabled = true,
                tailscaleOnly = true,
            ),
        )
        assertNull(
            evaluateNetworkAccessAuth(
                "192.168.1.20",
                "secret",
                "secret",
                limiter,
                networkAccessEnabled = true,
                tailscaleOnly = false,
            ),
        )
    }

    @Test
    fun hostnamesAreNeverLoopbackWithoutDns() {
        // Must not resolve hostnames — a name that DNS-maps to loopback must not bypass auth.
        assertFalse(isLoopbackAddress("localhost"))
        assertFalse(isLoopbackAddress("evil.example"))
        assertFalse(isLoopbackAddress("localhost.localdomain"))
    }

    @Test
    fun nonLoopbackRequiresToken() {
        val limiter = AuthFailureLimiter(10, 60_000, 60_000) { 0L }
        assertEquals(
            HttpStatusCode.Unauthorized,
            evaluateNetworkAccessAuth("192.168.1.20", null, "secret", limiter),
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            evaluateNetworkAccessAuth("192.168.1.20", "wrong", "secret", limiter),
        )
        assertNull(evaluateNetworkAccessAuth("192.168.1.20", "secret", "secret", limiter))
    }

    @Test
    fun rateLimitBlocksAfterFailures() {
        var now = 1_000L
        val limiter = AuthFailureLimiter(maxFailures = 3, windowMillis = 60_000, cooldownMillis = 60_000) { now }
        repeat(3) {
            assertEquals(
                HttpStatusCode.Unauthorized,
                evaluateNetworkAccessAuth("10.0.0.8", "bad", "secret", limiter),
            )
        }
        assertEquals(
            HttpStatusCode.TooManyRequests,
            evaluateNetworkAccessAuth("10.0.0.8", "secret", "secret", limiter),
        )
        now += 61_000
        assertNull(evaluateNetworkAccessAuth("10.0.0.8", "secret", "secret", limiter))
    }

    @Test
    fun scrubTokenQueryRemovesTokenParam() {
        assertEquals("foo=1", scrubTokenQuery("token=abc&foo=1"))
        assertEquals("foo=1&bar=2", scrubTokenQuery("foo=1&token=abc&bar=2"))
        assertEquals("", scrubTokenQuery("token=only"))
    }

    @Test
    fun queryTokenAcceptedOnlyOnWebSocketPaths() {
        assertEquals(
            "secret",
            extractAccessToken(authorizationHeader = null, path = "/ws/chats/abc", queryToken = "secret"),
        )
        assertNull(
            extractAccessToken(authorizationHeader = null, path = "/api/chats", queryToken = "secret"),
        )
        assertNull(
            extractAccessToken(authorizationHeader = null, path = "/", queryToken = "secret"),
        )
        assertNull(
            extractAccessToken(authorizationHeader = null, path = "/mcp", queryToken = "secret"),
        )
        assertNull(
            extractAccessToken(authorizationHeader = null, path = "/mcp-http", queryToken = "secret"),
        )
        assertEquals(
            "secret",
            extractAccessToken(
                authorizationHeader = "Bearer secret",
                path = "/api/chats",
                queryToken = "ignored",
            ),
        )
    }

    @Test
    fun publicWebChatPathsAreUnauthenticatedBootstrapSurface() {
        assertTrue(isPublicWebChatPath("/"))
        assertTrue(isPublicWebChatPath("/index.html"))
        assertTrue(isPublicWebChatPath("/app.js"))
        assertTrue(isPublicWebChatPath("/styles.css"))
        assertTrue(isPublicWebChatPath("/manifest.json"))
        assertTrue(isPublicWebChatPath("/sw.js"))
        assertTrue(isPublicWebChatPath("/icons/icon-192.png"))
        assertFalse(isPublicWebChatPath("/api/chats"))
        assertFalse(isPublicWebChatPath("/mcp"))
        assertFalse(isPublicWebChatPath("/mcp-http"))
        assertFalse(isPublicWebChatPath("/ws/chats/x"))
    }

    @Test
    fun isLoopbackRecognizesLiteralIpsOnly() {
        assertTrue(isLoopbackAddress("127.0.0.1"))
        assertTrue(isLoopbackAddress("::1"))
        assertTrue(isLoopbackAddress("[::1]"))
        assertTrue(isLoopbackAddress("127.0.0.1:54321"))
        assertFalse(isLoopbackAddress("192.168.0.1"))
        assertFalse(isLoopbackAddress("10.0.0.2"))
        assertFalse(isLoopbackAddress("203.0.113.10"))
    }

    @Test
    fun normalizePeerAddressStripsBracketsAndPort() {
        assertEquals("127.0.0.1", normalizePeerAddress("127.0.0.1:9"))
        assertEquals("::1", normalizePeerAddress("[::1]:443"))
        assertEquals("192.168.1.5", normalizePeerAddress("/192.168.1.5"))
    }

    @Test
    fun generateTokenIsUrlSafeAndLongEnough() {
        val a = generateNetworkAccessTokenBytes()
        val b = generateNetworkAccessTokenBytes()
        assertTrue(a.length >= 40)
        assertNotEquals(a, b)
        assertFalse(a.contains('+') || a.contains('/') || a.contains('='))
    }

    @Test
    fun rateLimiterIsThreadSafeUnderConcurrentFailures() {
        val limiter = AuthFailureLimiter(maxFailures = 50, windowMillis = 60_000, cooldownMillis = 60_000) { 1_000L }
        val threads = List(8) {
            Thread {
                repeat(100) { limiter.recordFailure("10.0.0.9") }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        // 800 failures against a threshold of 50 must trip the cooldown without corrupting state.
        assertTrue(limiter.isBlocked("10.0.0.9"))
    }
}
