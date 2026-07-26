package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.model.ProjectWorkflowStage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentSessionAttentionTest {
  private fun task(status: AgentStatus = AgentStatus.Working) = AgentTask(
        id = "t",
        title = "t",
        prompt = "",
        agent = AgentKind.Codex,
        status = status,
        startedAtMillis = 1L,
        createdAtMillis = 0,
    )

    @Test
    fun doneFromWorkingNeedsUnreadWhenNotViewing() {
        assertTrue(
            statusNeedsUnread(
                task = task(),
                previous = AgentStatus.Working,
                next = AgentStatus.Done,
                viewing = false,
            ),
        )
    }

    @Test
    fun doneFromWorkingDoesNotNeedUnreadWhileTerminalLive() {
        assertFalse(
            statusNeedsUnread(
                task = task(),
                previous = AgentStatus.Working,
                next = AgentStatus.Done,
                viewing = false,
                terminalLive = true,
            ),
        )
    }

    @Test
    fun blockedNeedsUnreadOnTransition() {
        assertTrue(
            statusNeedsUnread(
                task = task(),
                previous = AgentStatus.Working,
                next = AgentStatus.Blocked,
                viewing = false,
            ),
        )
    }

    @Test
    fun viewingSuppressesUnread() {
        assertFalse(
            statusNeedsUnread(
                task = task(),
                previous = AgentStatus.Working,
                next = AgentStatus.Done,
                viewing = true,
            ),
        )
    }

    @Test
    fun buildWorkflowActiveSuppressesUnread() {
        assertFalse(
            statusNeedsUnread(
                task = task().copy(workflowStage = ProjectWorkflowStage.Build),
                previous = AgentStatus.Working,
                next = AgentStatus.Done,
                viewing = false,
            ),
        )
    }

    @Test
    fun remountUnconfidentWorkingDoesNotDemoteConfidentDone() {
        assertTrue(
            shouldIgnoreStatusSnapshot(
                task = task(AgentStatus.Done).copy(statusConfident = true),
                snapshot = AgentStatusSnapshot(AgentStatus.Working, confident = false),
            ),
        )
    }

    @Test
    fun confidentWorkingCanReplaceDone() {
        assertFalse(
            shouldIgnoreStatusSnapshot(
                task = task(AgentStatus.Done).copy(statusConfident = true),
                snapshot = AgentStatusSnapshot(AgentStatus.Working, confident = true),
            ),
        )
    }

    @Test
    fun unconfidentWorkingStillAppliesWhileTaskIsWorking() {
        assertFalse(
            shouldIgnoreStatusSnapshot(
                task = task(AgentStatus.Working).copy(statusConfident = true),
                snapshot = AgentStatusSnapshot(AgentStatus.Working, confident = false),
            ),
        )
    }
}
