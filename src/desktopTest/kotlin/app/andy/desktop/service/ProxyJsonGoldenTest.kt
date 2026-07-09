package app.andy.desktop.service

import app.andy.model.ProxyRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Golden fixtures for [ProxyRuleJson] and [parseMitmproxyFlowLine] captured before
 * the kotlinx.serialization rewrite. Fixture files under `golden/` lock the wire format.
 */
class ProxyJsonGoldenTest {
    private val goldenRules = listOf(
        ProxyRule(
            id = "rule-1",
            name = "Override",
            enabled = true,
            urlPattern = "example.test",
            method = "GET",
            statusCode = 503,
            setHeaders = mapOf("x-andy" to "yes", "content-type" to "application/json"),
            removeHeaders = listOf("etag"),
            responseBody = "{\"offline\":true}",
        ),
        ProxyRule(
            id = "rule-2",
            name = "Nulls",
            enabled = false,
            urlPattern = "*",
            method = null,
            statusCode = null,
            setHeaders = emptyMap(),
            removeHeaders = emptyList(),
            responseBody = null,
        ),
    )

    private val expectedRulesJson = readGolden("golden/proxy_rules.json")

    private val fullFlowLine = readGolden("golden/flow_full.jsonl").trimEnd('\n')

    private val requestOnlyFlowLine =
        """{"type":"flow","id":"flow-req","startedAtMillis":5,"completedAtMillis":null,"durationMillis":null,"method":"GET","url":"https://example.test/","statusCode":null,"contentType":null,"sizeBytes":null,"requestHeaders":{},"responseHeaders":{},"requestBodyPreview":null,"responseBodyPreview":null,"error":null,"tlsStatus":"plain","matchedRuleId":null}"""

    private val errorFlowLine =
        """{"type":"flow","id":"flow-err","startedAtMillis":1,"completedAtMillis":2,"durationMillis":1,"method":"POST","url":"https://example.test/fail","statusCode":null,"contentType":null,"sizeBytes":null,"requestHeaders":{},"responseHeaders":{},"requestBodyPreview":null,"responseBodyPreview":null,"error":"connection reset","tlsStatus":"tls","matchedRuleId":null}"""

    @Test
    fun writeRulesMatchesGoldenFixture() {
        assertEquals(expectedRulesJson, ProxyRuleJson.writeRules(goldenRules))
    }

    @Test
    fun parseFullFlowLineWithEscapesAndUnicode() {
        val exchange = parseMitmproxyFlowLine(fullFlowLine)
        assertNotNull(exchange)
        assertEquals("flow-7", exchange.flowId)
        assertEquals("PUT", exchange.method)
        assertEquals("""https://example.test/items?q="hi"""", exchange.url)
        assertEquals(202, exchange.statusCode)
        assertEquals(22, exchange.durationMillis)
        assertEquals(27, exchange.sizeBytes)
        assertEquals("redacted", exchange.requestHeaders["authorization"])
        assertEquals("a\\b", exchange.requestHeaders["x-path"])
        assertEquals("""a="b"""", exchange.responseHeaders["set-cookie"])
        assertEquals("""{"n":1}""", exchange.requestBodyPreview)
        assertEquals("""{"ok":true,"msg":"café"}""", exchange.responseBodyPreview)
        assertNull(exchange.error)
        assertEquals("tls", exchange.tlsStatus)
        assertEquals("rule-1", exchange.matchedRuleId)
    }

    @Test
    fun parseRequestOnlyFlowLineWithNulls() {
        val exchange = parseMitmproxyFlowLine(requestOnlyFlowLine)
        assertNotNull(exchange)
        assertEquals("flow-req", exchange.id)
        assertEquals(5L, exchange.startedAtMillis)
        assertNull(exchange.completedAtMillis)
        assertNull(exchange.durationMillis)
        assertNull(exchange.statusCode)
        assertNull(exchange.contentType)
        assertNull(exchange.sizeBytes)
        assertNull(exchange.requestBodyPreview)
        assertNull(exchange.responseBodyPreview)
        assertNull(exchange.error)
        assertEquals("plain", exchange.tlsStatus)
        assertNull(exchange.matchedRuleId)
    }

    @Test
    fun parseErrorFlowLine() {
        val exchange = parseMitmproxyFlowLine(errorFlowLine)
        assertNotNull(exchange)
        assertEquals("connection reset", exchange.error)
        assertNull(exchange.statusCode)
    }

    @Test
    fun nonFlowLinesAreIgnored() {
        assertNull(parseMitmproxyFlowLine("""{"type":"status","ok":true}"""))
        assertNull(parseMitmproxyFlowLine("not json"))
        assertNull(parseMitmproxyFlowLine(""))
    }

    private fun readGolden(path: String): String {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(path)) {
            "missing golden fixture $path"
        }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
