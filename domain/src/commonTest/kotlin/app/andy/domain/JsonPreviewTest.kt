package app.andy.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonPreviewTest {
    @Test
    fun parseJsonBodyPreviewReturnsNullForNonJson() {
        assertNull(parseJsonBodyPreview(null))
        assertNull(parseJsonBodyPreview(""))
        assertNull(parseJsonBodyPreview("not-json"))
        assertNull(parseJsonBodyPreview("123"))
    }

    @Test
    fun parseObjectAndFlattenRespectsExpansion() {
        val root = parseJsonBodyPreview("""{"name":"andy","count":2}""")
        assertIs<JsonPreviewNode.ObjectNode>(root)
        assertEquals(2, root.children.size)

        val collapsed = flattenJsonPreview(root, emptyMap())
        assertEquals(1, collapsed.size)
        assertTrue(collapsed.first().text.contains("2 keys"))

        val expanded = flattenJsonPreview(root, mapOf("$" to true))
        assertEquals(3, expanded.size)
        assertTrue(expanded.any { it.text.contains("\"name\"") && it.text.contains("\"andy\"") })
    }

    @Test
    fun parseArrayAndQuoteHelpers() {
        val root = parseJsonBodyPreview("""[1,"two"]""")
        assertIs<JsonPreviewNode.ArrayNode>(root)
        assertEquals(2, root.children.size)

        assertEquals("a\\.b", escapePathSegment("a.b"))
        assertEquals("\"hi\\\"there\"", quoteJsonPreview("hi\"there"))
    }
}
