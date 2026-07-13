package app.andy.ui.agents

import app.andy.model.AgentEvent
import androidx.compose.ui.text.LinkAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentTranscriptTest {
    @Test
    fun completionOwnsDuplicateFinalAssistantText() {
        val events = listOf(
            AgentEvent.AssistantText(atMillis = 1, text = "All set."),
            AgentEvent.TaskResult(atMillis = 2, success = true, finalText = "All set."),
        )

        assertEquals(listOf(events.last()), transcriptDisplayEvents(events))
    }

    @Test
    fun distinctAssistantTextRemainsVisibleBeforeCompletion() {
        val events = listOf(
            AgentEvent.AssistantText(atMillis = 1, text = "I checked the files."),
            AgentEvent.TaskResult(atMillis = 2, success = true, finalText = "All set."),
        )

        assertEquals(events, transcriptDisplayEvents(events))
    }

    @Test
    fun rendersHttpLinksAsClickableLinkAnnotations() {
        val markdown = parseChatMarkdown("Read [the docs](https://www.example.com/docs) first.")

        assertEquals("Read the docs first.", markdown.text)
        val link = markdown.getLinkAnnotations(0, markdown.length).single().item as LinkAnnotation.Url
        assertEquals("https://www.example.com/docs", link.url)
    }

    @Test
    fun leavesMalformedLinksUntouched() {
        val text = "Read [the docs](not a URL)."

        assertEquals(text, parseChatMarkdown(text).text)
    }

    @Test
    fun rendersUppercaseHttpLinksAsClickableLinkAnnotations() {
        val markdown = parseChatMarkdown("See [API docs](HTTPS://WWW.EXAMPLE.COM/API).")

        assertEquals("See API docs.", markdown.text)
        val link = markdown.getLinkAnnotations(0, markdown.length).single().item as LinkAnnotation.Url
        assertEquals("HTTPS://WWW.EXAMPLE.COM/API", link.url)
    }
}
