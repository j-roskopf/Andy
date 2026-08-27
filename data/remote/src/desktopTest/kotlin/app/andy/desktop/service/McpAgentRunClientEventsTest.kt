package app.andy.desktop.service

import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpAgentRunClientEventsTest {
    @Test
    fun pollsWhileLaunchingWithNullStatus() {
        val launching = AgentTask(
            id = "task-launch",
            title = "t",
            prompt = "hi",
            agent = AgentKind.Codex,
            status = null,
            createdAtMillis = 1L,
        )
        assertTrue(shouldPollRemoteAcpEvents(launching))
    }

    @Test
    fun pollsWhileWorking() {
        val working = AgentTask(
            id = "task-work",
            title = "t",
            prompt = "hi",
            agent = AgentKind.Codex,
            status = AgentStatus.Working,
            createdAtMillis = 1L,
        )
        assertTrue(shouldPollRemoteAcpEvents(working))
    }

    @Test
    fun stopsAfterFinishedTurn() {
        val done = AgentTask(
            id = "task-done",
            title = "t",
            prompt = "hi",
            agent = AgentKind.Codex,
            status = AgentStatus.Done,
            createdAtMillis = 1L,
            finishedAtMillis = 2L,
        )
        assertFalse(shouldPollRemoteAcpEvents(done))
    }
}
