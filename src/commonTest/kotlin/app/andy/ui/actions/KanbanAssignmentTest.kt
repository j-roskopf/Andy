package app.andy.ui.actions

import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KanbanAssignmentTest {
    private val task = AgentTask(
        id = "chat-1",
        title = "Assigned card",
        prompt = "Implement it",
        agent = AgentKind.Codex,
        createdAtMillis = 1,
    )

    @Test
    fun activeChatCannotBeReassigned() {
        assertFalse(canReassignKanbanCard(task.copy(status = AgentStatus.Working)))
        assertFalse(canReassignKanbanCard(task.copy(status = AgentStatus.Blocked)))
    }

    @Test
    fun finishedChatCanBeReassigned() {
        assertTrue(canReassignKanbanCard(task.copy(status = AgentStatus.Done)))
    }
}
