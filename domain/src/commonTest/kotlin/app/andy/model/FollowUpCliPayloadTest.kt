package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FollowUpCliPayloadTest {
    private fun task(agent: AgentKind) = AgentTask(
        id = "task-1",
        title = "test",
        prompt = "do the thing",
        agent = agent,
        cwd = "/tmp/repo",
        originDir = "/tmp/repo",
        createdAtMillis = 0,
    )

    @Test
    fun textProvidersEmbedImagePathsInPrompt() {
        val payload = task(AgentKind.ClaudeCode).followUpCliPayload(
            text = "fix the layout",
            imagePaths = listOf("/tmp/mockup.png"),
        )
        assertEquals(emptyList(), payload.imagePaths)
        assertTrue(payload.prompt.contains("fix the layout"))
        assertTrue(payload.prompt.contains("/tmp/mockup.png"))
        assertTrue(payload.prompt.contains("Attached image file"))
    }

    @Test
    fun codexKeepsNativeImageArgvSeparate() {
        val payload = task(AgentKind.Codex).followUpCliPayload(
            text = "fix the layout",
            imagePaths = listOf("/tmp/mockup.png"),
        )
        assertEquals(listOf("/tmp/mockup.png"), payload.imagePaths)
        assertTrue(payload.prompt.contains("fix the layout"))
        assertTrue(!payload.prompt.contains("Attached image file"))
    }

    @Test
    fun liveTerminalPromptKeepsAttachedImagesInline() {
        val task = task(AgentKind.ClaudeCode)
        val prompt = task.followUpPromptForLiveTerminal(
            text = "fix this bug",
            imagePaths = listOf("/tmp/screenshot.png"),
        )
        assertTrue(prompt.contains("fix this bug"))
        assertTrue(prompt.contains("/tmp/screenshot.png"))
        assertTrue(!prompt.contains("\n\nAttached image file"))
    }
}
