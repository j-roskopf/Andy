package app.andy.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatMarkdownTest {
    @Test
    fun promotesSingleChatNewlinesToMarkdownHardBreaks() {
        assertEquals("first line  \nsecond line", "first line\nsecond line".withChatLineBreaks())
    }

    @Test
    fun keepsParagraphAndFencedCodeFormattingIntact() {
        assertEquals(
            "first paragraph\n\nsecond paragraph\n```\nval answer = 42\n```",
            "first paragraph\n\nsecond paragraph\n```\nval answer = 42\n```".withChatLineBreaks(),
        )
    }
}
