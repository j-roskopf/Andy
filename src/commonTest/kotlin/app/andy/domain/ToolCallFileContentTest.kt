package app.andy.domain

import app.andy.model.DiffLineKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolCallFileContentTest {
    @Test
    fun parseToolCallFileContentReadsAcpDiffShape() {
        val content = parseToolCallFileContent(
            """
            src/Main.kt
            --- old
            fun old()
            +++ new
            fun new()
            """.trimIndent(),
        )

        assertNotNull(content)
        assertEquals("src/Main.kt", content.path)
        assertEquals("fun old()", content.oldText)
        assertEquals("fun new()", content.newText)
        assertTrue(content.hasDiff)
    }

    @Test
    fun parseToolCallFileContentReadsAbsolutePathReadShape() {
        val content = parseToolCallFileContent(
            """
            /Users/dev/project/Main.kt
            package demo
            """.trimIndent(),
        )

        assertNotNull(content)
        assertEquals("/Users/dev/project/Main.kt", content.path)
        assertNull(content.oldText)
        assertEquals("package demo", content.newText)
    }

    @Test
    fun diffTextLinesBuildsAdditionsAndDeletions() {
        val diff = diffTextLines(
            path = "Main.kt",
            oldText = "one\ntwo",
            newText = "one\nthree",
        )

        assertEquals(
            listOf(
                DiffLineKind.Context,
                DiffLineKind.Deletion,
                DiffLineKind.Addition,
            ),
            diff.lines.map { it.kind },
        )
        assertEquals("two", diff.lines[1].text)
        assertEquals("three", diff.lines[2].text)
    }
}
