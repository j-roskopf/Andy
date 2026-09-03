package app.andy.ui.agents

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentTaskComposerTest {
    @Test
    fun orchestrationSkillsForceMcpAttach() {
        assertTrue(isOrchestrationSkillName("andy-handoff"))
        assertTrue(isOrchestrationSkillName("Andy-Loop"))
        assertTrue(isOrchestrationSkillName("andy-advisor"))
        assertTrue(isOrchestrationSkillName("andy-committee"))
        assertFalse(isOrchestrationSkillName("andy-orchestration"))
        assertFalse(isOrchestrationSkillName("grill-me"))
        assertFalse(isOrchestrationSkillName("babysit"))

        assertTrue(attachMcpAfterSkillSelection("andy-handoff", currentAttachMcp = false))
        assertTrue(attachMcpAfterSkillSelection("andy-loop", currentAttachMcp = false))
        assertTrue(attachMcpAfterSkillSelection("andy-advisor", currentAttachMcp = true))
        assertFalse(attachMcpAfterSkillSelection("babysit", currentAttachMcp = false))
        assertTrue(attachMcpAfterSkillSelection("unrelated", currentAttachMcp = true))
    }

    @Test
    fun parsesOnlyFiniteNonNegativeBudgets() {
        assertEquals(2.5, " 2.50 ".toMaxBudgetUsd())
        assertEquals(0.0, "0".toMaxBudgetUsd())
        assertNull("-1".toMaxBudgetUsd())
        assertNull("NaN".toMaxBudgetUsd())
        assertNull("Infinity".toMaxBudgetUsd())
        assertNull("not a number".toMaxBudgetUsd())
    }

    @Test
    fun tintsRecognizedSkillAndCommandTokens() {
        val skill = Color(0xFF3B82F6)
        val command = Color(0xFF72C5A2)
        val annotated = annotateComposerSlashTokens(
            text = "/babysit /goal keep shipping /unknown and prose",
            skillNames = setOf("babysit"),
            commandNames = setOf("goal"),
            skillColor = skill,
            commandColor = command,
        )

        assertEquals(skill, annotated.spanStyles.single { it.start == 0 }.item.color)
        assertEquals(command, annotated.spanStyles.single { it.start == annotated.text.indexOf("/goal") }.item.color)
        assertTrue(annotated.spanStyles.none { it.start == annotated.text.indexOf("/unknown") })
        assertTrue(annotated.spanStyles.all { it.item.background.alpha > 0f })
    }

    @Test
    fun ignoresPartialSlashTokensUntilComplete() {
        val annotated = annotateComposerSlashTokens(
            text = "/baby",
            skillNames = setOf("babysit"),
            commandNames = emptySet(),
            skillColor = Color.Cyan,
            commandColor = Color.Green,
        )
        assertTrue(annotated.spanStyles.isEmpty())
    }

    @Test
    fun stylesAutolinksAndInlineMarkdownInComposer() {
        val link = Color(0xFF458FFF)
        val code = Color(0xFFE8E8E8)
        val codeBg = Color(0x33458FFF)
        val text = "see https://example.com/docs, try `foo`, **bold**, *italic*, ~~old~~ and [label](https://andy.app)."
        val annotated = annotateComposerMarkdown(
            text = text,
            styles = ComposerMarkdownStyles(
                linkColor = link,
                codeColor = code,
                codeBackground = codeBg,
            ),
        )

        assertEquals(text, annotated.text)

        val urlStart = text.indexOf("https://example.com/docs")
        val urlSpan = annotated.spanStyles.single { it.start == urlStart }
        assertEquals(link, urlSpan.item.color)
        assertEquals(urlStart + "https://example.com/docs".length, urlSpan.end)

        val codeStart = text.indexOf("`foo`")
        assertTrue(
            annotated.spanStyles.any {
                it.start == codeStart && it.end == codeStart + 5 && it.item.background == codeBg
            },
        )

        val boldStart = text.indexOf("**bold**")
        assertTrue(annotated.spanStyles.any { it.start == boldStart && it.item.fontWeight != null })

        val italicStart = text.indexOf("*italic*")
        assertTrue(annotated.spanStyles.any { it.start == italicStart && it.item.fontStyle != null })

        val strikeStart = text.indexOf("~~old~~")
        assertTrue(annotated.spanStyles.any { it.start == strikeStart && it.item.textDecoration != null })

        val labelStart = text.indexOf("label")
        assertTrue(
            annotated.spanStyles.any {
                it.start == labelStart && it.end == labelStart + 5 && it.item.color == link
            },
        )
    }

    @Test
    fun doesNotStyleMarkdownInsideInlineCode() {
        val annotated = annotateComposerMarkdown(
            text = "use `**not bold**` please",
            styles = ComposerMarkdownStyles(
                linkColor = Color.Blue,
                codeColor = Color.White,
                codeBackground = Color.DarkGray,
            ),
        )
        assertTrue(annotated.spanStyles.none { it.item.fontWeight != null })
        assertTrue(annotated.spanStyles.any { it.start == "use ".length && it.item.background == Color.DarkGray })
    }

    @Test
    fun trimsTrailingPunctuationFromAutolinks() {
        assertEquals("https://a.co/x".length, trimAutolinkLength("https://a.co/x."))
        assertEquals("https://a.co/x".length, trimAutolinkLength("https://a.co/x)"))
        assertEquals("https://a.co/x".length, trimAutolinkLength("https://a.co/x"))
    }

    @Test
    fun combinesMarkdownWithSlashHighlights() {
        val skill = Color(0xFF3B82F6)
        val link = Color(0xFF458FFF)
        val text = "/babysit check https://example.com"
        val annotated = annotateComposerPrompt(
            text = text,
            skillNames = setOf("babysit"),
            commandNames = emptySet(),
            skillColor = skill,
            commandColor = Color.Green,
            markdown = ComposerMarkdownStyles(
                linkColor = link,
                codeColor = Color.White,
                codeBackground = Color.DarkGray,
            ),
        )
        assertEquals(skill, annotated.spanStyles.single { it.start == 0 }.item.color)
        assertEquals(
            link,
            annotated.spanStyles.single { it.start == text.indexOf("https://") }.item.color,
        )
    }

    @Test
    fun findsClickableComposerLinksSkippingCode() {
        val text = "see https://example.com and [docs](https://andy.app) plus `https://nope.test`"
        val links = findComposerLinks(text)
        assertEquals(2, links.size)
        assertEquals("https://example.com", links[0].url)
        assertEquals("https://andy.app", links[1].url)
        assertEquals(text.indexOf("docs"), links[1].start)
        assertNull(composerLinkAt(text, text.indexOf("nope")))
        assertEquals("https://example.com", composerLinkAt(text, text.indexOf("example"))?.url)
    }
}
