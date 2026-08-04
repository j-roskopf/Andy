package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentTaskDraftTest {
    @Test
    fun fallbackTitleUsesPromptWhenPresent() {
        val draft = AgentTaskDraft(
            title = "",
            prompt = "Ship the PR",
            agent = AgentKind.Codex,
            projectId = null,
        )
        assertEquals("Ship the PR", draft.fallbackTitle())
    }

    @Test
    fun fallbackTitleUsesImageFilenameWhenPromptBlank() {
        val draft = AgentTaskDraft(
            title = "",
            prompt = "",
            agent = AgentKind.Codex,
            projectId = null,
            imagePaths = listOf("/tmp/screenshots/ui-bug.png"),
        )
        assertEquals("ui-bug.png", draft.fallbackTitle())
    }

    @Test
    fun fallbackTitleCountsAdditionalImages() {
        val draft = AgentTaskDraft(
            title = "",
            prompt = "",
            agent = AgentKind.Codex,
            projectId = null,
            imagePaths = listOf("C:\\shots\\first.jpg", "/tmp/second.jpg"),
        )
        assertEquals("first.jpg (+1)", draft.fallbackTitle())
    }
}
