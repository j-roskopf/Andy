package app.andy.ui.components

import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SafeHighlightedMarkdownCodeTest {
    @Test
    fun dropsHighlightSpansThatExtendPastCodeLength() {
        val builder = Highlights.Builder().theme(SyntaxThemes.atom(darkMode = true))
        val code = "key: maven-\${{ runner.os }}-\${{ hashFiles('**/pom.xml') }}"
        val highlighted = buildSafeHighlightedAnnotatedString(code, language = "yaml", highlightsBuilder = builder)
        assertEquals(code, highlighted.text)
        highlighted.spanStyles.forEach { range ->
            assertTrue(range.start >= 0, "start=${range.start}")
            assertTrue(range.end <= code.length, "end=${range.end} length=${code.length}")
            assertTrue(range.start < range.end, "start=${range.start} end=${range.end}")
        }
    }

    @Test
    fun fallsBackToPlainCodeWhenHighlightingFails() {
        val builder = Highlights.Builder().theme(SyntaxThemes.atom(darkMode = true))
        val code = "val answer = 42"
        val highlighted = buildSafeHighlightedAnnotatedString(code, language = "kotlin", highlightsBuilder = builder)
        assertEquals(code, highlighted.text)
    }
}
