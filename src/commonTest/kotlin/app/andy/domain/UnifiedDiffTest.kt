package app.andy.domain

import app.andy.model.DiffLine
import app.andy.model.DiffLineKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnifiedDiffTest {
    @Test
    fun parseUnifiedDiffTracksLineNumbersAndKinds() {
        val diff = parseUnifiedDiff(
            """
            diff --git a/src/Example.kt b/src/Example.kt
            index 111..222 100644
            --- a/src/Example.kt
            +++ b/src/Example.kt
            @@ -10,6 +10,7 @@ package demo
             import a
             import b
            -import gone
            +import added
            +import also
             import keep
            """.trimIndent(),
            "src/Example.kt",
        )

        assertEquals("src/Example.kt", diff.path)
        assertEquals(2, diff.additions)
        assertEquals(1, diff.deletions)
        assertFalse(diff.isBinary)
        assertEquals(
            listOf(
                DiffLineKind.Context,
                DiffLineKind.Context,
                DiffLineKind.Deletion,
                DiffLineKind.Addition,
                DiffLineKind.Addition,
                DiffLineKind.Context,
            ),
            diff.lines.map { it.kind },
        )
        assertEquals(10, diff.lines[0].oldLineNumber)
        assertEquals(10, diff.lines[0].newLineNumber)
        assertEquals(12, diff.lines[2].oldLineNumber)
        assertEquals(null, diff.lines[2].newLineNumber)
        assertEquals(null, diff.lines[3].oldLineNumber)
        assertEquals(12, diff.lines[3].newLineNumber)
        assertEquals("import added", diff.lines[3].text)
    }

    @Test
    fun parseUnifiedDiffMarksBinaryFiles() {
        val diff = parseUnifiedDiff(
            "diff --git a/icon.png b/icon.png\nBinary files a/icon.png and b/icon.png differ\n",
            "icon.png",
        )
        assertTrue(diff.isBinary)
        assertTrue(diff.lines.isEmpty())
    }

    @Test
    fun diffForNewFileMarksEveryLineAsAddition() {
        val diff = diffForNewFile("new.kt", "one\ntwo\n")
        assertTrue(diff.isNewFile)
        assertEquals(2, diff.additions)
        assertEquals(0, diff.deletions)
        assertEquals(listOf(1, 2), diff.lines.map { it.newLineNumber })
        assertEquals(listOf("one", "two"), diff.lines.map { it.text })
    }

    @Test
    fun buildSplitDiffPairsAlignsChangeGroupsSideBySide() {
        val lines = listOf(
            DiffLine(DiffLineKind.Context, "keep", 1, 1),
            DiffLine(DiffLineKind.Deletion, "old-a", 2, null),
            DiffLine(DiffLineKind.Deletion, "old-b", 3, null),
            DiffLine(DiffLineKind.Addition, "new-a", null, 2),
            DiffLine(DiffLineKind.Addition, "new-b", null, 3),
            DiffLine(DiffLineKind.Addition, "new-c", null, 4),
            DiffLine(DiffLineKind.Context, "tail", 4, 5),
        )
        val pairs = buildSplitDiffPairs(lines)
        assertEquals(5, pairs.size)
        assertTrue(pairs[0].isContext)
        assertEquals("old-a", pairs[1].old?.text)
        assertEquals("new-a", pairs[1].new?.text)
        assertEquals("old-b", pairs[2].old?.text)
        assertEquals("new-b", pairs[2].new?.text)
        assertEquals(null, pairs[3].old)
        assertEquals("new-c", pairs[3].new?.text)
        assertTrue(pairs[4].isContext)
    }
}
