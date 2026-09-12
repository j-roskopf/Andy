package app.andy.ui.agents

import androidx.compose.ui.text.input.TextFieldValue
import app.andy.model.AgentAttachment

data class ChatFollowUpDraft(
    val text: TextFieldValue = TextFieldValue(""),
    val imagePaths: List<String> = emptyList(),
    val attachments: List<AgentAttachment> = emptyList(),
)

/** In-memory follow-up composer drafts keyed by chat id. Survives switching chats without retaining full panes. */
class ChatFollowUpDraftMemory {
    private val drafts = mutableMapOf<String, ChatFollowUpDraft>()

    fun get(taskId: String): ChatFollowUpDraft? = drafts[taskId]

    fun save(taskId: String, draft: ChatFollowUpDraft) {
        if (draft.text.text.isBlank() && draft.imagePaths.isEmpty() && draft.attachments.isEmpty()) {
            drafts.remove(taskId)
        } else {
            drafts[taskId] = draft
        }
    }

    fun remove(taskId: String) {
        drafts.remove(taskId)
    }
}
