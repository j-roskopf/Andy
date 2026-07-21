package app.andy.desktop.service.tracing

import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraceServerTest {
    @Test
    fun servesTraceBytesAndOpenerPage() = runBlocking {
        val traces = createTempDirectory("andy-trace-server").toFile()
        val payload = byteArrayOf(7, 8, 9, 10)
        File(traces, "andy-demo.perfetto-trace").writeBytes(payload)
        val service = DesktopTraceViewerService(tracesDir = traces)
        try {
            val port = service.startServerForTests()

            val ok = get("http://127.0.0.1:$port/traces/andy-demo.perfetto-trace")
            assertEquals(200, ok.first)
            assertEquals(payload.toList(), ok.second.toList())
            assertEquals("*", ok.third)

            val blocked = get("http://127.0.0.1:$port/traces/../secret.perfetto-trace")
            assertEquals(404, blocked.first)

            val result = service.openExternally("andy-demo")
            // Desktop.browse may fail in headless CI; URL construction still matters via /view.
            if (result.isSuccess) {
                assertTrue(result.stdout.startsWith("http://127.0.0.1:$port/view?"))
                assertTrue(result.stdout.contains("name=andy-demo.perfetto-trace"))
            }

            val view = get("http://127.0.0.1:$port/view?name=andy-demo.perfetto-trace")
            assertEquals(200, view.first)
            val html = view.second.toString(Charsets.UTF_8)
            assertTrue(html.contains("ui.perfetto.dev"))
            assertTrue(html.contains("postMessage"))
            assertTrue(html.contains("andy-demo.perfetto-trace"))
            assertTrue(!html.contains("mode=embedded"))
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun openTraceHtmlIncludesHandshake() {
        val html = DesktopTraceViewerService.openTraceHtml("andy-demo.perfetto-trace")
        assertTrue(html.contains("andy-demo.perfetto-trace"))
        assertTrue(html.contains("PING"))
        assertTrue(html.contains("postMessage"))
    }

    private fun get(url: String): Triple<Int, ByteArray, String?> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 5_000
            readTimeout = 5_000
        }
        return try {
            val code = connection.responseCode
            val body = runCatching {
                (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.readBytes()
                    ?: ByteArray(0)
            }.getOrDefault(ByteArray(0))
            Triple(code, body, connection.getHeaderField("Access-Control-Allow-Origin"))
        } finally {
            connection.disconnect()
        }
    }
}
