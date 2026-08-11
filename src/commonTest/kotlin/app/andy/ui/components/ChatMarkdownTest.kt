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

    @Test
    fun providerStyleWrapsBecomeHardBreaksWhenPromotionEnabled() {
        val wrapped = "Monday, August\n**3,**"
        assertEquals("Monday, August  \n**3,**", wrapped.withChatLineBreaks())
    }

    @Test
    fun unwrapsWholeMessageMarkdownFenceWithNestedCodeFence() {
        val text = "```markdown\n# Title\n\n```kotlin\nfun main() {}\n```\n\n| A | B |\n|---|---|\n| 1 | 2 |\n```"
        assertEquals(
            "# Title\n\n```kotlin\nfun main() {}\n```\n\n| A | B |\n|---|---|\n| 1 | 2 |",
            text.unwrapOuterMarkdownFence(),
        )
    }

    @Test
    fun leavesNormalCodeFenceUntouched() {
        val text = "```kotlin\nfun main() {}\n```"
        assertEquals(text, text.unwrapOuterMarkdownFence())
    }

    @Test
    fun leavesProseWithTrailingFenceUntouched() {
        val text = "Some prose.\n\n```markdown\n# Title\n```"
        assertEquals(text, text.unwrapOuterMarkdownFence())
    }

    @Test
    fun leavesUnclosedMarkdownFenceUntouched() {
        val text = "```markdown\n# Title\nno closing fence here"
        assertEquals(text, text.unwrapOuterMarkdownFence())
    }
}
