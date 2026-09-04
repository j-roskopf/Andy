package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnreadBadgeFilteringTest {
    private fun createTask(
        id: String,
        projectId: String? = null,
        unread: Boolean = true,
        archived: Boolean = false,
        status: AgentStatus = AgentStatus.Done,
        workflowTaskId: String? = null,
    ) = AgentTask(
        id = id,
        title = "Task $id",
        prompt = "Prompt $id",
        agent = AgentKind.Codex,
        status = status,
        createdAtMillis = 1000L,
        unread = unread,
        archived = archived,
        projectId = projectId,
        workflowTaskId = workflowTaskId,
    )

    @Test
    fun testHasUnreadProjectAgentTasksFilter() {
        val knownProjectIds = setOf("proj-1", "proj-2")

        fun isUnreadProjectTask(task: AgentTask): Boolean {
            return !task.archived && task.unread &&
                task.projectId != null && task.projectId in knownProjectIds
        }

        // Active unread chat for known project -> true
        assertTrue(isUnreadProjectTask(createTask("1", projectId = "proj-1")))

        // Archived unread chat -> false
        assertFalse(isUnreadProjectTask(createTask("2", projectId = "proj-1", archived = true)))

        // Workflow stage completion also lights Projects / Tasks-tab chrome
        assertTrue(isUnreadProjectTask(createTask("3", projectId = "proj-1", workflowTaskId = "wf-1")))

        // Unknown project ID -> false
        assertFalse(isUnreadProjectTask(createTask("4", projectId = "deleted-proj")))

        // Standalone task (no project) -> false
        assertFalse(isUnreadProjectTask(createTask("5", projectId = null)))
    }

    @Test
    fun testUnreadWorkflowProjectIdsFilter() {
        val knownProjectIds = setOf("proj-1")

        fun unreadWorkflowProjectId(task: AgentTask): String? =
            task.projectId?.takeIf {
                task.unread && !task.archived && task.workflowTaskId != null && it in knownProjectIds
            }

        assertEquals("proj-1", unreadWorkflowProjectId(createTask("1", projectId = "proj-1", workflowTaskId = "wf-1")))
        assertEquals(null, unreadWorkflowProjectId(createTask("2", projectId = "proj-1")))
        assertEquals(null, unreadWorkflowProjectId(createTask("3", projectId = "proj-1", workflowTaskId = "wf-1", unread = false)))
        assertEquals(null, unreadWorkflowProjectId(createTask("4", projectId = "proj-1", workflowTaskId = "wf-1", archived = true)))
    }

    @Test
    fun testHasUnreadStandaloneAgentTasksFilter() {
        fun isUnreadStandaloneTask(task: AgentTask): Boolean {
            return !task.archived && task.unread && task.projectId == null
        }

        assertTrue(isUnreadStandaloneTask(createTask("1", projectId = null)))
        assertFalse(isUnreadStandaloneTask(createTask("2", projectId = null, archived = true)))
        assertFalse(isUnreadStandaloneTask(createTask("3", projectId = "proj-1")))
    }

    @Test
    fun testArchivedBlockedTasksDoNotContributeAttentionBadges() {
        fun isBlockedStandaloneTask(task: AgentTask): Boolean =
            !task.archived && task.projectId == null && task.status == AgentStatus.Blocked
        fun isBlockedProjectTask(task: AgentTask): Boolean =
            !task.archived && task.projectId != null && task.status == AgentStatus.Blocked

        assertFalse(isBlockedStandaloneTask(createTask("1", status = AgentStatus.Blocked, archived = true)))
        assertFalse(isBlockedProjectTask(createTask("2", projectId = "proj-1", status = AgentStatus.Blocked, archived = true)))
        assertTrue(isBlockedStandaloneTask(createTask("3", status = AgentStatus.Blocked)))
        assertTrue(isBlockedProjectTask(createTask("4", projectId = "proj-1", status = AgentStatus.Blocked)))
    }
}
