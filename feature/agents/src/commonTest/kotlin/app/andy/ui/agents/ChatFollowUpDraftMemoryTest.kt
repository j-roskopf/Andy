package app.andy.ui.agents

import androidx.compose.ui.text.input.TextFieldValue
import app.andy.model.AgentAttachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatFollowUpDraftMemoryTest {
    @Test
    fun savesAndRestoresDraftPerTask() {
        val memory = ChatFollowUpDraftMemory()
        val draft = ChatFollowUpDraft(TextFieldValue("follow up"), listOf("/tmp/a.png"))
        memory.save("task-a", draft)
        assertEquals(draft, memory.get("task-a"))
        assertNull(memory.get("task-b"))
    }

    @Test
    fun clearsEmptyDrafts() {
        val memory = ChatFollowUpDraftMemory()
        memory.save("task-a", ChatFollowUpDraft(TextFieldValue("typing")))
        memory.save("task-a", ChatFollowUpDraft(TextFieldValue("")))
        assertNull(memory.get("task-a"))
    }

    @Test
    fun retainsDraftWithAttachmentsOnly() {
        val memory = ChatFollowUpDraftMemory()
        val attachment = AgentAttachment(
            id = "att-1",
            displayName = "pasted.txt",
            byteCount = 100,
            lineCount = 5,
            sha256 = "a".repeat(64),
        )
        memory.save("task-a", ChatFollowUpDraft(attachments = listOf(attachment)))
        assertEquals(listOf(attachment), memory.get("task-a")?.attachments)
    }

    @Test
    fun removeDropsDraft() {
        val memory = ChatFollowUpDraftMemory()
        memory.save("task-a", ChatFollowUpDraft(TextFieldValue("keep")))
        memory.remove("task-a")
        assertNull(memory.get("task-a"))
    }
}
