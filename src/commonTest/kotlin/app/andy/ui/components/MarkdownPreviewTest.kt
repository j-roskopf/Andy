package app.andy.ui.components

import androidx.compose.ui.text.LinkAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MarkdownPreviewTest {
    @Test
    fun parsesCommonScratchpadBlocks() {
        val blocks = parseMarkdownBlocks(
            """
            # Release checklist

            - [x] Preserve the public API
            - [ ] Run desktop tests

            > Keep the diff focused.

            ```sh
            ./gradlew desktopTest
            ```
            """.trimIndent(),
        )

        assertEquals(MarkdownBlock.Heading(1, "Release checklist"), blocks[0])
        assertEquals(MarkdownBlock.ListItem("Preserve the public API", "•", checked = true), blocks[1])
        assertEquals(MarkdownBlock.ListItem("Run desktop tests", "•", checked = false), blocks[2])
        assertEquals(MarkdownBlock.Quote("Keep the diff focused."), blocks[3])
        assertEquals(MarkdownBlock.Code("sh", "./gradlew desktopTest"), blocks[4])
    }

    @Test
    fun rendersInlineFormattingAndLinksWithoutSyntaxMarkers() {
        val markdown = parseInlineMarkdown("Use **stable** APIs and read [the docs](https://example.com).")

        assertEquals("Use stable APIs and read the docs.", markdown.text)
        val link = assertIs<LinkAnnotation.Url>(markdown.getLinkAnnotations(0, markdown.length).single().item)
        assertEquals("https://example.com", link.url)
    }

    @Test
    fun leavesUnsupportedLinksVisible() {
        val text = "Read [local notes](file:///tmp/notes.md)."

        assertEquals(text, parseInlineMarkdown(text).text)
    }

    @Test
    fun preservesUnderscoresInsideWords() {
        val text = "Keep checkout_state source-compatible."

        assertEquals(text, parseInlineMarkdown(text).text)
    }
}
