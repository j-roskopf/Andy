package app.andy.ui.components

import androidx.compose.ui.text.buildAnnotatedString
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun escapesHtmlTagsInsideInlineCode() {
        assertEquals(
            "Reused `&lt;audio&gt;` elements",
            "Reused `<audio>` elements".escapeHtmlTagsInInlineCode(),
        )
    }

    @Test
    fun leavesFencedCodeBlocksUntouched() {
        val fenced = "```html\n<audio></audio>\n```"
        assertEquals(fenced, fenced.escapeHtmlTagsInInlineCode())
    }

    @Test
    fun escapedInlineCodeRendersHtmlTagText() {
        val sample = "1. **Reused `<audio>` elements** — Chrome".escapeHtmlTagsInInlineCode()
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(sample)
        val settings = DefaultAnnotatorSettings(
            linkTextSpanStyle = androidx.compose.ui.text.TextLinkStyles(),
            codeSpanStyle = androidx.compose.ui.text.SpanStyle(),
            annotator = com.mikepenz.markdown.model.markdownAnnotator(),
            referenceLinkHandler = com.mikepenz.markdown.model.ReferenceLinkHandlerImpl(),
            linkInteractionListener = null,
        )
        val listItem = tree.children.single().children.single()
        val annotated = buildAnnotatedString {
            buildMarkdownAnnotatedString(sample, listItem, settings)
        }
        assertTrue(annotated.text.contains("audio"), "annotated=${annotated.text}")
    }
}
