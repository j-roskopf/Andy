package app.andy.domain

import app.andy.model.DiffLineKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertFalse(content.hasDiff)
    }

    @Test
    fun parseToolCallFileArgumentsReadsJsonEditPayload() {
        val content = parseToolCallFileArguments(
            """{"file_path":"src/Main.kt","old_string":"fun old()","new_string":"fun new()"}""",
        )

        assertNotNull(content)
        assertEquals("src/Main.kt", content.path)
        assertEquals("fun old()", content.oldText)
        assertEquals("fun new()", content.newText)
        assertTrue(content.hasDiff)
    }

    @Test
    fun structuredFilePathsMayContainSpaces() {
        val diff = parseToolCallFileContent(
            "/Users/dev/My Project/Main.kt\n--- old\nfun old()\n+++ new\nfun new()",
        )
        val arguments = parseToolCallFileArguments(
            """{"file_path":"/Users/dev/My Project/Main.kt","old_string":"fun old()","new_string":"fun new()"}""",
        )

        assertEquals("/Users/dev/My Project/Main.kt", assertNotNull(diff).path)
        assertEquals("/Users/dev/My Project/Main.kt", assertNotNull(arguments).path)
        assertTrue(looksLikeFilePath("/Users/dev/My Project/Main.kt"))
        assertTrue(looksLikeFilePath("My Project/Main.kt"))
    }

    @Test
    fun parseToolCallFileArgumentsIgnoresUnrelatedJson() {
        assertNull(parseToolCallFileArguments("""{"totalMatches":45,"truncated":false}"""))
        assertNull(parseToolCallFileArguments("""{"path":"src/Main.kt"}"""))
        assertNull(parseToolCallFileArguments("not json"))
    }

    /**
     * A command result is one long line full of slashes and dots. Treating it as a path rendered the
     * whole payload as a clickable file name instead of showing the diff it carried.
     */
    @Test
    fun commandResultPayloadIsNeverMistakenForAFilePath() {
        val payload =
            """{"exitCode":0,"stdout":"diff --git a/src/Main.kt b/src/Main.kt\n--- a/src/Main.kt\n","stderr":""}"""

        assertNull(parseToolCallFileContent(payload))
        assertNull(parseToolCallFileArguments(payload))
        assertFalse(looksLikeFilePath(payload))
        assertNull(parseToolCallFileContent("exitCode=0, stdout=diff --git a/src/Main.kt b/src/Main.kt"))
        assertFalse(looksLikeFilePath("- **command:** ls src/Main.kt"))
        assertTrue(looksLikeFilePath("src/Main.kt"))
        assertTrue(looksLikeFilePath("/Users/dev/project/Main.kt"))
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
