package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentSessionStatus
import app.andy.model.AgentTask
import app.andy.model.AgentTaskStatus
import app.andy.model.ProjectWorkflowStage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentSessionAttentionTest {
    private val runningTask = AgentTask(
        id = "task-1",
        title = "Checkout",
        prompt = "Fix validation",
        agent = AgentKind.Codex,
        projectId = null,
        cwd = "/tmp",
        originDir = "/tmp",
        status = AgentTaskStatus.Running,
        createdAtMillis = 0L,
        startedAtMillis = 0L,
    )

    @Test
    fun marksUnreadWhenSessionBecomesIdleWhileNotViewing() {
        assertTrue(
            sessionStatusNeedsUnread(
                task = runningTask,
                previous = AgentSessionStatus.Working,
                next = AgentSessionStatus.Idle,
                viewing = false,
            ),
        )
    }

    @Test
    fun suppressesUnreadWhileChatIsOnScreen() {
        assertFalse(
            sessionStatusNeedsUnread(
                task = runningTask,
                previous = AgentSessionStatus.Working,
                next = AgentSessionStatus.Idle,
                viewing = true,
            ),
        )
    }

    @Test
    fun marksUnreadForDoneAfterWorking() {
        assertTrue(
            sessionStatusNeedsUnread(
                task = runningTask,
                previous = AgentSessionStatus.Working,
                next = AgentSessionStatus.Done,
                viewing = false,
            ),
        )
    }

    @Test
    fun ignoresWorkflowBuildRuns() {
        assertFalse(
            sessionStatusNeedsUnread(
                task = runningTask.copy(workflowStage = ProjectWorkflowStage.Build),
                previous = AgentSessionStatus.Working,
                next = AgentSessionStatus.Idle,
                viewing = false,
            ),
        )
    }
}
