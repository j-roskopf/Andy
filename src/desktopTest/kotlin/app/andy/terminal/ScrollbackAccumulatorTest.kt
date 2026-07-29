package app.andy.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScrollbackAccumulatorTest {
    private fun screen(vararg lines: String): List<StyledTerminalRow> =
        lines.map { StyledTerminalRow(plain = stripAnsi(it), ansi = it) }

    @Test
    fun firstSnapshotIsKeptVerbatim() {
        val accumulator = ScrollbackAccumulator()
        accumulator.merge(screen("\u001b[36m> hello\u001b[0m", "  world"))
        assertEquals("\u001b[36m> hello\u001b[0m\n  world", accumulator.render())
    }

    @Test
    fun unchangedScreenDoesNotDuplicate() {
        val accumulator = ScrollbackAccumulator()
        repeat(4) { accumulator.merge(screen("> hello", "  world")) }
        assertEquals("> hello\n  world", accumulator.render())
    }

    @Test
    fun redrawnStatusLineReplacesItselfInsteadOfPilingUp() {
        val accumulator = ScrollbackAccumulator()
        accumulator.merge(screen("> build the thing", "  working on it", "⠋ Working 1.2k tokens"))
        accumulator.merge(screen("> build the thing", "  working on it", "⠙ Working 2.4k tokens"))
        accumulator.merge(screen("> build the thing", "  working on it", "⠹ Working 3.6k tokens"))
        assertEquals(
            "> build the thing\n  working on it\n⠹ Working 3.6k tokens",
            accumulator.render(),
        )
    }

    @Test
    fun cursorStatusFooterRedrawReplacesItselfInsteadOfPilingUp() {
        val accumulator = ScrollbackAccumulator()
        accumulator.merge(
            screen(
                "> fix flicker",
                "  Cursor Grok 4.5 High Fast · 54.6% · 5 files edited",
                "~/Code/Andy/Andy · jr/cli · #57",
            ),
        )
        accumulator.merge(
            screen(
                "> fix flicker",
                "  Cursor Grok 4.5 High Fast · 55.1% · 6 files edited",
                "~/Code/Andy/Andy · jr/cli · #57",
            ),
        )
        assertEquals(
            "> fix flicker\n  Cursor Grok 4.5 High Fast · 55.1% · 6 files edited\n~/Code/Andy/Andy · jr/cli · #57",
            accumulator.render(),
        )
    }

    @Test
    fun scrolledRowsAreFrozenIntoHistory() {
        val accumulator = ScrollbackAccumulator()
        accumulator.merge(screen("line 1", "line 2", "line 3", "line 4"))
        accumulator.merge(screen("line 3", "line 4", "line 5", "line 6"))
        accumulator.merge(screen("line 5", "line 6", "line 7", "line 8"))
        assertEquals(
            (1..8).joinToString("\n") { "line $it" },
            accumulator.render(),
        )
    }

    @Test
    fun fixedFooterDoesNotBlockScrollAlignment() {
        val accumulator = ScrollbackAccumulator()
        accumulator.merge(screen("reply a", "reply b", "reply c", "─────", "> type here"))
        accumulator.merge(screen("reply b", "reply c", "reply d", "─────", "> type here"))
        assertEquals(
            "reply a\nreply b\nreply c\nreply d\n─────\n> type here",
            accumulator.render(),
        )
    }

    @Test
    fun unrelatedScreenIsAppendedWholesale() {
        val accumulator = ScrollbackAccumulator()
        accumulator.merge(screen("old session output"))
        accumulator.merge(screen("brand new screen", "nothing in common"))
        assertEquals(
            "old session output\nbrand new screen\nnothing in common",
            accumulator.render(),
        )
    }

    @Test
    fun growingBottomLineIsRewrittenNotAppendedTwice() {
        val accumulator = ScrollbackAccumulator()
        accumulator.merge(screen("> question", "Ans"))
        accumulator.merge(screen("> question", "Answer so"))
        accumulator.merge(screen("> question", "Answer so far", "and more"))
        assertEquals("> question\nAnswer so far\nand more", accumulator.render())
    }

    @Test
    fun trailingBlankRowsOfTheScreenAreIgnored() {
        val accumulator = ScrollbackAccumulator()
        accumulator.merge(screen("only line", "", "", ""))
        assertEquals("only line", accumulator.render())
    }

    @Test
    fun stylingSurvivesRepeatedMerges() {
        val accumulator = ScrollbackAccumulator()
        val styled = "\u001b[38;2;255;128;0m▎\u001b[0m\u001b[1m diff added\u001b[0m"
        accumulator.merge(screen("> patch it", styled))
        accumulator.merge(screen("> patch it", styled, "done"))
        assertTrue(accumulator.render().contains("\u001b[38;2;255;128;0m"))
        assertEquals("> patch it\n$styled\ndone", accumulator.render())
    }

    @Test
    fun styledRowsFromAnsiTextKeepsEscapesAndStripsThemForPlain() {
        val rows = styledRowsFromAnsiText("\u001b[31mred\u001b[0m\nplain\n")
        assertEquals(2, rows.size)
        assertEquals("red", rows[0].plain)
        assertEquals("\u001b[31mred\u001b[0m", rows[0].ansi)
        assertEquals("plain", rows[1].plain)
    }

    @Test
    fun seededHistoryIsNotWrittenTwiceOnReattach() {
        // Reattaching re-captures a pane whose output is already on disk.
        val saved = screen("reply a", "reply b", "reply c")
        val accumulator = ScrollbackAccumulator()
        accumulator.seed(saved)
        accumulator.merge(screen("reply b", "reply c", "reply d"))
        assertEquals("reply a\nreply b\nreply c\nreply d", accumulator.render())
    }

    @Test
    fun overlapPrefersLongestAlignmentOnTies() {
        val captured = screen("a", "b", "a", "b")
        val incoming = screen("a", "b")
        assertEquals(2, scrollbackSnapshotOverlap(captured, incoming))
    }

}
