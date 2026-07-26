package app.andy.desktop.service.proxy

import com.sun.net.httpserver.HttpServer
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.Executors
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import app.andy.desktop.test.OptInGates
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Assume.assumeTrue

/**
 * End-to-end proof that Andy's proxy is a transparent pass-through: each matrix
 * case through the proxy must match the direct baseline (status, meaningful
 * headers, decompressed body bytes).
 *
 * Gated: `ANDY_PROXY_CONFORMANCE=1` (set on PR CI). Locally:
 * `ANDY_PROXY_CONFORMANCE=1 ./gradlew desktopTest --tests '*ProxyConformanceTest*'`
 */
class ProxyConformanceTest {
    private var proxyProcess: Process? = null
    private var proxyDir: File? = null
    private val originServers = mutableListOf<HttpServer>()

    @BeforeTest
    fun requireFlag() {
        OptInGates.requireProxyConformance()
    }

    @AfterTest
    fun tearDown() {
        proxyProcess?.destroyForcibly()
        proxyProcess = null
        originServers.forEach { runCatching { it.stop(0) } }
        originServers.clear()
        proxyDir?.deleteRecursively()
        proxyDir = null
    }

    @Test
    fun proxyMatchesDirectBaselineForMatrix() {
        val resolved = MitmRuntime.resolveMitmdump(provisionIfNeeded = true)
        assumeTrue(resolved.message, resolved.executable != null)

        val dir = kotlin.io.path.createTempDirectory("andy-proxy-conformance").toFile()
        proxyDir = dir
        val addon = File(dir, "andy_mitm_addon.py").also { it.writeBytes(MitmAddon.getAddonSource()) }
        File(dir, "rules.json").writeText("""{"rules":[]}""")

        val ipv4Origin = startOrigin(bindHost = "127.0.0.1")
        val ipv6Origin = runCatching { startOrigin(bindHost = "::1") }.getOrNull()

        val listenPort = freePort()
        val command = listOf(
            resolved.executable!!,
            "--listen-host", "127.0.0.1",
            "--listen-port", listenPort.toString(),
            "--set", "confdir=${dir.absolutePath}",
            "-s", addon.absolutePath,
            "--set", "termlog_verbosity=error",
            "--set", "ssl_insecure=true",
            // Conformance origins bind to loopback; mitmproxy blocks literal IPs by default.
            "--set", "block_global=false",
            "--set", "block_private=false",
        )
        val process = ProcessBuilder(command)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        proxyProcess = process
        val startup = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(startup) { startup.appendLine(line) }
                }
            }
        }.also { it.isDaemon = true; it.start() }
        waitUntilListening(listenPort, process, startup)

        val caFile = File(dir, "mitmproxy-ca-cert.pem").takeIf { it.isFile }
            ?: File(dir, "mitmproxy-ca-cert.cer")
        assumeTrue("mitmproxy CA was not generated", caFile.isFile)
        val proxySsl = sslContextTrusting(caFile)

        val cases = buildList {
            addAll(httpMatrixCases(ipv4Origin, "127.0.0.1"))
            ipv6Origin?.let { addAll(httpMatrixCases(it, "[::1]")) }
            add(wsEchoCase(ipv4Origin))
        }

        val failures = mutableListOf<String>()
        cases.forEach { case ->
            runCatching {
                val direct = case.executeDirect()
                val viaProxy = case.executeViaProxy(listenPort, proxySsl)
                assertEquivalent(case.name, direct, viaProxy)
            }.onFailure { error ->
                failures += "${case.name}: ${error.message ?: error}"
            }
        }

        if (failures.isNotEmpty()) {
            fail("Proxy conformance failures:\n" + failures.joinToString("\n"))
        }
    }

    private fun httpMatrixCases(server: HttpServer, host: String): List<ConformanceCase> {
        val port = server.address.port
        val base = "http://$host:$port"
        return listOf(
            plainGet("$base/ok", "http11-plain"),
            plainGet("$base/gzip", "http11-gzip"),
            plainGet("$base/deflate", "http11-deflate"),
            plainGet("$base/chunked", "http11-chunked"),
            plainGet("$base/large512", "http11-512k"),
            plainGet("$base/large2m", "http11-2m"),
            redirectCase("$base/redir301", "redir-301"),
            redirectCase("$base/redir302", "redir-302"),
            redirectCase("$base/redir307", "redir-307"),
            redirectCase("$base/redir308", "redir-308"),
            plainGet("$base/error500", "http11-500"),
            plainGet("$base/slow", "http11-slow"),
        )
    }

    private fun plainGet(url: String, name: String) = object : ConformanceCase {
        override val name = name
        override fun executeDirect(): ExchangeResult = fetch(url, proxy = null, ssl = null)
        override fun executeViaProxy(proxyPort: Int, ssl: SSLContext?): ExchangeResult =
            fetch(url, proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)), ssl = null)
    }

    private fun redirectCase(url: String, name: String) = object : ConformanceCase {
        override val name = name
        override fun executeDirect(): ExchangeResult = fetch(url, proxy = null, ssl = null, followRedirects = false)
        override fun executeViaProxy(proxyPort: Int, ssl: SSLContext?): ExchangeResult =
            fetch(
                url,
                proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)),
                ssl = null,
                followRedirects = false,
            )
    }

    private fun wsEchoCase(server: HttpServer): ConformanceCase {
        val port = server.address.port
        return object : ConformanceCase {
            override val name = "websocket-upgrade-echo"
            override fun executeDirect(): ExchangeResult = websocketEcho("127.0.0.1", port, proxy = null)
            override fun executeViaProxy(proxyPort: Int, ssl: SSLContext?): ExchangeResult =
                websocketEcho("127.0.0.1", port, proxy = InetSocketAddress("127.0.0.1", proxyPort))
        }
    }

    private fun startOrigin(bindHost: String): HttpServer {
        val address = InetSocketAddress(InetAddress.getByName(bindHost), 0)
        val server = HttpServer.create(address, 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/ok") { exchange ->
            val body = "hello-ok".toByteArray()
            exchange.responseHeaders.add("X-Andy", "ok")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/gzip") { exchange ->
            val raw = "hello-gzip".toByteArray()
            val compressed = ByteArrayOutputStream().also { out ->
                GZIPOutputStream(out).use { it.write(raw) }
            }.toByteArray()
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.responseHeaders.add("X-Andy", "gzip")
            exchange.sendResponseHeaders(200, compressed.size.toLong())
            exchange.responseBody.use { it.write(compressed) }
        }
        server.createContext("/deflate") { exchange ->
            val raw = "hello-deflate".toByteArray()
            val compressed = ByteArrayOutputStream().also { out ->
                DeflaterOutputStream(out).use { it.write(raw) }
            }.toByteArray()
            exchange.responseHeaders.add("Content-Encoding", "deflate")
            exchange.responseHeaders.add("X-Andy", "deflate")
            exchange.sendResponseHeaders(200, compressed.size.toLong())
            exchange.responseBody.use { it.write(compressed) }
        }
        server.createContext("/chunked") { exchange ->
            exchange.responseHeaders.add("X-Andy", "chunked")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                out.write("chunk-a".toByteArray())
                out.flush()
                out.write("chunk-b".toByteArray())
            }
        }
        server.createContext("/large512") { exchange ->
            val body = ByteArray(512 * 1024) { (it % 251).toByte() }
            exchange.responseHeaders.add("X-Andy", "large512")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/large2m") { exchange ->
            val body = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
            exchange.responseHeaders.add("X-Andy", "large2m")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/redir301") { exchange ->
            exchange.responseHeaders.add("Location", "/ok")
            exchange.sendResponseHeaders(301, -1)
            exchange.close()
        }
        server.createContext("/redir302") { exchange ->
            exchange.responseHeaders.add("Location", "/ok")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/redir307") { exchange ->
            exchange.responseHeaders.add("Location", "/ok")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        server.createContext("/redir308") { exchange ->
            exchange.responseHeaders.add("Location", "/ok")
            exchange.sendResponseHeaders(308, -1)
            exchange.close()
        }
        server.createContext("/error500") { exchange ->
            val body = "boom".toByteArray()
            exchange.sendResponseHeaders(500, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/slow") { exchange ->
            Thread.sleep(250)
            val body = "slow-ok".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/ws") { exchange ->
            // HttpServer cannot do real WebSocket; the upgrade probe uses a raw socket
            // against a dedicated acceptor below. Keep a 404 marker here.
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        originServers += server
        return server
    }

    private fun fetch(
        url: String,
        proxy: Proxy?,
        ssl: SSLContext?,
        followRedirects: Boolean = true,
    ): ExchangeResult {
        val connection = if (proxy != null) {
            URL(url).openConnection(proxy)
        } else {
            URL(url).openConnection()
        } as HttpURLConnection
        connection.instanceFollowRedirects = followRedirects
        connection.connectTimeout = 8_000
        connection.readTimeout = 15_000
        if (connection is HttpsURLConnection && ssl != null) {
            connection.sslSocketFactory = ssl.socketFactory
        }
        connection.requestMethod = "GET"
        val code = connection.responseCode
        val headers = connection.headerFields
            .filterKeys { it != null }
            .mapKeys { it.key!!.lowercase() }
            .mapValues { it.value.joinToString(", ") }
        val stream = if (code in 200..299 || code in 300..399) connection.inputStream else connection.errorStream
        val body = stream?.use { it.readBytes() } ?: ByteArray(0)
        return ExchangeResult(code, headers, body)
    }

    private fun websocketEcho(host: String, originPort: Int, proxy: InetSocketAddress?): ExchangeResult {
        // Minimal WebSocket upgrade + text echo against a tiny raw server spun per call.
        val wsServer = startRawWebSocketEcho(host)
        try {
            val targetHost = host
            val targetPort = wsServer.port
            val socket = if (proxy == null) {
                Socket(targetHost, targetPort)
            } else {
                val tunnel = Socket(proxy.hostName, proxy.port)
                val connect = "CONNECT $targetHost:$targetPort HTTP/1.1\r\nHost: $targetHost:$targetPort\r\n\r\n"
                tunnel.getOutputStream().write(connect.toByteArray())
                tunnel.getOutputStream().flush()
                val reply = readHttpHead(tunnel)
                check(reply.startsWith("HTTP/1.1 200") || reply.startsWith("HTTP/1.0 200")) {
                    "CONNECT failed: $reply"
                }
                tunnel
            }
            socket.soTimeout = 5_000
            val key = "dGhlIHNhbXBsZSBub25jZQ=="
            val req = buildString {
                append("GET /echo HTTP/1.1\r\n")
                append("Host: $targetHost:$targetPort\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: $key\r\n")
                append("Sec-WebSocket-Version: 13\r\n\r\n")
            }
            socket.getOutputStream().write(req.toByteArray())
            socket.getOutputStream().flush()
            val head = readHttpHead(socket)
            assertTrue(head.contains("101"), head)
            // Send masked text frame "ping"
            val payload = "ping".toByteArray()
            val mask = byteArrayOf(1, 2, 3, 4)
            val frame = ByteArrayOutputStream()
            frame.write(0x81)
            frame.write(0x80 or payload.size)
            frame.write(mask)
            payload.forEachIndexed { i, b -> frame.write(b.toInt() xor mask[i % 4].toInt()) }
            socket.getOutputStream().write(frame.toByteArray())
            socket.getOutputStream().flush()
            val input = BufferedInputStream(socket.getInputStream())
            val b0 = input.read()
            val b1 = input.read()
            check(b0 == 0x81) { "expected text frame, got $b0" }
            val len = b1 and 0x7f
            val echoed = input.readNBytes(len)
            socket.close()
            return ExchangeResult(101, mapOf("upgrade" to "websocket"), echoed)
        } finally {
            wsServer.close()
        }
    }

    private data class RawWsServer(val port: Int, private val closer: () -> Unit) {
        fun close() = closer()
    }

    private fun startRawWebSocketEcho(host: String): RawWsServer {
        val serverSocket = java.net.ServerSocket()
        serverSocket.bind(InetSocketAddress(InetAddress.getByName(host), 0))
        val thread = Thread {
            runCatching {
                val client = serverSocket.accept()
                val head = readHttpHead(client)
                check(head.contains("Upgrade: websocket", ignoreCase = true))
                val accept = buildString {
                    append("HTTP/1.1 101 Switching Protocols\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n")
                }
                client.getOutputStream().write(accept.toByteArray())
                client.getOutputStream().flush()
                val input = BufferedInputStream(client.getInputStream())
                val b0 = input.read()
                val b1 = input.read()
                val masked = (b1 and 0x80) != 0
                val len = b1 and 0x7f
                val mask = if (masked) input.readNBytes(4) else ByteArray(0)
                val payload = input.readNBytes(len)
                val clear = if (masked) {
                    ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
                } else {
                    payload
                }
                client.getOutputStream().write(byteArrayOf(0x81.toByte(), clear.size.toByte()) + clear)
                client.getOutputStream().flush()
                client.close()
            }
            runCatching { serverSocket.close() }
        }.also { it.isDaemon = true; it.start() }
        return RawWsServer(serverSocket.localPort) {
            runCatching { serverSocket.close() }
            runCatching { thread.join(1_000) }
        }
    }

    private fun readHttpHead(socket: Socket): String {
        val input = BufferedInputStream(socket.getInputStream())
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) break
            bytes.write(b)
            val text = bytes.toString(Charsets.ISO_8859_1.name())
            if (text.contains("\r\n\r\n")) break
            if (bytes.size() > 16_384) error("HTTP head too large")
        }
        return bytes.toString(Charsets.ISO_8859_1.name())
    }

    private fun assertEquivalent(name: String, direct: ExchangeResult, viaProxy: ExchangeResult) {
        assertEquals(direct.status, viaProxy.status, "$name status")
        MEANINGFUL_HEADERS.forEach { header ->
            assertEquals(
                direct.headers[header]?.lowercase(),
                viaProxy.headers[header]?.lowercase(),
                "$name header $header",
            )
        }
        assertTrue(
            direct.body.contentEquals(viaProxy.body),
            "$name body mismatch (direct=${direct.body.size}, proxy=${viaProxy.body.size})",
        )
    }

    private fun waitUntilListening(port: Int, process: Process, startup: StringBuilder) {
        val deadline = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                fail("mitmdump exited during startup:\n$startup")
            }
            runCatching {
                Socket("127.0.0.1", port).use { }
                // Give mitmproxy a moment to generate the CA after bind.
                Thread.sleep(400)
                return
            }
            Thread.sleep(150)
        }
        fail("mitmdump did not listen on $port:\n$startup")
    }

    private fun freePort(): Int =
        java.net.ServerSocket(0).use { it.localPort }

    private fun sslContextTrusting(caFile: File): SSLContext {
        val cf = CertificateFactory.getInstance("X.509")
        val cert = caFile.inputStream().use { cf.generateCertificate(it) }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        ks.setCertificateEntry("andy", cert)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, tmf.trustManagers, null)
        return ctx
    }

    private interface ConformanceCase {
        val name: String
        fun executeDirect(): ExchangeResult
        fun executeViaProxy(proxyPort: Int, ssl: SSLContext?): ExchangeResult
    }

    private data class ExchangeResult(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    companion object {
        private val MEANINGFUL_HEADERS = setOf("content-type", "content-encoding", "location", "x-andy", "upgrade")
    }
}
