package app.andy.domain

import app.andy.model.AcpToolCallPresentation
import app.andy.model.AgentToolKind
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
    fun parseToolCallFileContentSeparatesOutputAfterTheDiff() {
        val content = parseToolCallFileContent(
            "src/Main.kt\n--- old\nold\n+++ new\nnew" +
                AcpToolCallPresentation.DetailSeparator +
                "warning: formatter skipped generated file",
        )

        assertNotNull(content)
        assertEquals("new", content.newText)
        assertEquals("warning: formatter skipped generated file", content.extraDetail)
    }

    @Test
    fun parseToolCallFileContentKeepsSeparatorLikeFileContent() {
        val content = parseToolCallFileContent(
            "README.md\n--- old\nold\n+++ new\nheading\n--- tool output\nbody",
        )

        assertNotNull(content)
        assertEquals("heading\n--- tool output\nbody", content.newText)
        assertNull(content.extraDetail)
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
    fun structuredEditArgumentsAcceptRootPathsButSearchPayloadsStayArguments() {
        val edit = parseToolCallFileArguments(
            """{"file_path":"README.md","old_string":"old","new_string":"new"}""",
            AgentToolKind.Edit,
        )
        val search = parseToolCallFileArguments(
            """{"path":"src/Main.kt","search":"TODO"}""",
            AgentToolKind.Search,
        )

        assertEquals("README.md", assertNotNull(edit).path)
        assertNull(search)
    }

    @Test
    fun structuredEditArgumentsKeepOutputAfterTheJson() {
        val arguments = """{"file_path":"README.md","old_string":"old","new_string":"new"}"""
        val parsed = parseToolCallFileArguments(
            "$arguments${AcpToolCallPresentation.DetailSeparator}warning: generated file skipped",
            AgentToolKind.Edit,
        )

        assertEquals("README.md", parsed?.path)
        assertEquals("old", parsed?.oldText)
        assertEquals("new", parsed?.newText)
        assertEquals("warning: generated file skipped", parsed?.extraDetail)
    }

    @Test
    fun emptyOldSnapshotProducesANewFileDiff() {
        val diff = diffTextLines("new.txt", "", "one\ntwo")

        assertTrue(diff.isNewFile)
        assertEquals(0, diff.deletions)
        assertEquals(2, diff.additions)
    }

    @Test
    fun structuredArgumentsPreserveEmptyOldSnapshot() {
        val content = parseToolCallFileArguments(
            """{"file_path":"new.txt","old_string":"","new_string":"one\ntwo"}""",
            AgentToolKind.Edit,
        )

        assertNotNull(content)
        assertEquals("", content.oldText)
        assertTrue(content.hasDiff)
        assertTrue(diffFromToolCallFileContent(content).isNewFile)
    }

    @Test
    fun pathOnlyDeleteArgumentsRemainOpenableWithOutput() {
        val arguments = """{"path":"README.md"}"""
        val content = parseToolCallFileArguments(
            "$arguments${AcpToolCallPresentation.DetailSeparator}deleted",
            AgentToolKind.Delete,
        )

        assertNotNull(content)
        assertEquals("README.md", content.path)
        assertFalse(content.hasDiff)
        assertEquals("deleted", content.extraDetail)
    }

    @Test
    fun primitiveMovePathRemainsOpenableWithOutput() {
        val content = parseToolCallFileArguments(
            "README.md${AcpToolCallPresentation.DetailSeparator}moved",
            AgentToolKind.Move,
        )

        assertNotNull(content)
        assertEquals("README.md", content.path)
        assertEquals("moved", content.extraDetail)
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

    @Test
    fun largeSnapshotsUseBoundedLinearDiffing() {
        val oldText = (1..1_100).joinToString("\n") { "old $it" }
        val newText = (1..1_100).joinToString("\n") { "new $it" }

        val diff = diffTextLines("large.txt", oldText, newText)

        assertEquals(1_100, diff.deletions)
        assertEquals(1_100, diff.additions)
        assertEquals(2_200, diff.lines.size)
    }

    @Test
    fun highlySkewedSnapshotsAlsoUseBoundedLinearDiffing() {
        val oldText = (1..10_001).joinToString("\n") { "old $it" }

        val diff = diffTextLines("skewed.txt", oldText, "new")

        assertEquals(2_001, diff.deletions)
        assertEquals(1, diff.additions)
        assertEquals("… 8001 lines omitted", diff.lines[2_000].text)
    }
}
