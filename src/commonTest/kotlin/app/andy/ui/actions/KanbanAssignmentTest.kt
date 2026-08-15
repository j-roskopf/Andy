package app.andy.ui.actions

import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun queuedOrLaunchingChatCannotBeReassigned() {
        assertFalse(canReassignKanbanCard(task.copy(status = null, startedAtMillis = null, finishedAtMillis = null)))
        assertFalse(canReassignKanbanCard(task.copy(status = null, startedAtMillis = 2, finishedAtMillis = null)))
    }

    @Test
    fun finishedChatCanBeReassigned() {
        assertTrue(canReassignKanbanCard(task.copy(status = AgentStatus.Done)))
        assertTrue(canReassignKanbanCard(task.copy(status = AgentStatus.Error)))
    }

    @Test
    fun defaultPromptIncludesTitleAndDescription() {
        assertEquals("Retry network", defaultKanbanAssignPrompt("Retry network", ""))
        assertEquals(
            "Retry network\n\nthree attempts with exponential backoff",
            defaultKanbanAssignPrompt("Retry network", "three attempts with exponential backoff"),
        )
        assertEquals(
            "Retry network until the handshake succeeds",
            defaultKanbanAssignPrompt("Retry network", "Retry network until the handshake succeeds"),
        )
    }
}
