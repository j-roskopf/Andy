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
    fun followUpPromptIncludesAttachmentHintsWithoutBodies() {
        val attachment = AgentAttachment(
            id = "att-1",
            displayName = "dump.txt",
            byteCount = 8_000_000,
            lineCount = 90_000,
            sha256 = "c".repeat(64),
            relativePath = ".andy/task-1/attachments/dump.txt",
        )
        val payload = task(AgentKind.ClaudeCode).followUpCliPayload(
            text = "summarize the dump",
            imagePaths = emptyList(),
            attachments = listOf(attachment),
        )
        assertTrue(payload.prompt.contains("summarize the dump"))
        assertTrue(payload.prompt.contains(".andy/task-1/attachments/dump.txt"))
        assertTrue(payload.prompt.contains(attachment.sha256))
        assertTrue(payload.prompt.length < 4_000)
    }
}
