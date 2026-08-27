package app.andy

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.andy.ui.agents.ChatComposerAttachment
import app.andy.ui.agents.ChatComposerInbox
import app.andy.ui.agents.applyChatComposerAttachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserElementAnnotationTest {
    @Test
    fun formatIncludesCommentAndElementFacts() {
        val text = formatBrowserElementAnnotation(
            BrowserElementAnnotation(
                comment = "Make this primary",
                tag = "center",
                selector = "form > center",
                url = "https://www.google.com/",
                pageTitle = "Google",
                width = 517,
                height = 58,
                color = "#e8e8e8",
                font = "14px Roboto, Arial, sans-serif",
                innerText = "Google Search I'm Feeling Lucky",
            ),
        )
        assertTrue(text.startsWith("Make this primary"))
        assertTrue(text.contains("[Browser element]"))
        assertTrue(text.contains("URL: https://www.google.com/"))
        assertTrue(text.contains("Element: <center> (form > center)"))
        assertTrue(text.contains("Size: 517×58"))
        assertTrue(text.contains("color: #e8e8e8"))
        assertTrue(text.contains("font: 14px Roboto, Arial, sans-serif"))
        assertTrue(text.contains("Google Search"))
    }

    @Test
    fun composerInboxDeliversToLatestSinkAndQueuesWhenIdle() {
        val inbox = ChatComposerInbox()
        val queued = ChatComposerAttachment(text = "queued")
        inbox.offer(queued)

        val first = mutableListOf<ChatComposerAttachment>()
        val unregisterFirst = inbox.register { first += it }
        assertEquals(listOf(queued), first)

        val second = mutableListOf<ChatComposerAttachment>()
        val unregisterSecond = inbox.register { second += it }
        inbox.offer(ChatComposerAttachment(text = "live"))
        assertEquals(listOf(ChatComposerAttachment(text = "live")), second)
        assertEquals(1, first.size)

        unregisterSecond()
        inbox.offer(ChatComposerAttachment(text = "after-second"))
        assertEquals(listOf(queued, ChatComposerAttachment(text = "after-second")), first)

        unregisterFirst()
    }

    @Test
    fun applyAttachmentAppendsTextAndMergesImages() {
        val (text, images) = applyChatComposerAttachment(
            currentText = TextFieldValue("existing", TextRange(0)),
            currentImages = listOf("/tmp/a.png"),
            item = ChatComposerAttachment(
                imagePaths = listOf("/tmp/b.png", "/tmp/a.png"),
                text = "note",
            ),
        )
        assertEquals("existing\n\nnote", text.text)
        assertEquals(listOf("/tmp/a.png", "/tmp/b.png"), images)
    }
}
