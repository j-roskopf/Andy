package app.andy.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatFindHighlightTest {
    @Test
    fun findLiteralRangesIsCaseInsensitiveAndNonOverlapping() {
        assertEquals(
            listOf(0 until 3, 8 until 11),
            findLiteralRanges("Foo bar foo", "foo"),
        )
        assertEquals(emptyList(), findLiteralRanges("hello", "xyz"))
        assertEquals(emptyList(), findLiteralRanges("hello", "  "))
    }

    @Test
    fun highlightFindMatchesMarksActiveRange() {
        val annotated = highlightFindMatches(
            text = "alpha beta alpha",
            query = "alpha",
            activeRange = 11 until 16,
        )
        assertEquals("alpha beta alpha", annotated.text)
        assertEquals(2, annotated.spanStyles.size)
        assertEquals(ChatFindMatchBackground, annotated.spanStyles[0].item.background)
        assertEquals(ChatFindActiveMatchBackground, annotated.spanStyles[1].item.background)
    }

    @Test
    fun activeRangeInLeafMapsSourceOffsets() {
        val local = activeRangeInLeaf(leafStart = 10, leafEnd = 20, activeRange = 12 until 15)
        assertEquals(2 until 5, local)
        assertEquals(null, activeRangeInLeaf(0, 5, 10 until 12))
    }

    @Test
    fun highlightFindMatchesNoQueryReturnsPlain() {
        val annotated = highlightFindMatches("hello", "")
        assertEquals("hello", annotated.text)
        assertTrue(annotated.spanStyles.isEmpty())
    }
}
