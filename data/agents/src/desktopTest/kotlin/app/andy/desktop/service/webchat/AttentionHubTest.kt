package app.andy.desktop.service.webchat

import app.andy.model.AgentKind
import app.andy.model.AgentLaneKind
import app.andy.model.AgentQueuedFollowUp
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.service.AgentAttentionEvent
import app.andy.service.AgentAttentionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttentionHubTest {
    @Test
    fun seedsThenEmitsConfidentDoneAndError() {
        val hub = AttentionHub(nowMillis = { 1_000L })
        assertTrue(hub.onTasksChanged(listOf(task(AgentStatus.Working))).isEmpty())
        // ACP: soft Done (no confidence) still notifies.
        assertEquals(
            listOf("Done"),
            hub.onTasksChanged(listOf(task(AgentStatus.Done, confident = false))).map { it.kind.name },
        )
        assertEquals(
            listOf("Error"),
            hub.onTasksChanged(listOf(task(AgentStatus.Error, confident = true))).map { it.kind.name },
        )
    }

    @Test
    fun suppressesQueuedFollowUpDoneAndAutomationNotifyFlags() {
        val hub = AttentionHub(nowMillis = { 1_000L })
        hub.onTasksChanged(listOf(task(AgentStatus.Working)))
        assertTrue(hub.onTasksChanged(listOf(task(AgentStatus.Done, confident = true, queued = true))).isEmpty())
        hub.onTasksChanged(listOf(task(AgentStatus.Working)))
        assertTrue(
            hub.onTasksChanged(
                listOf(task(AgentStatus.Done, confident = true).copy(automationSuppressOsNotify = true)),
            ).isEmpty(),
        )
        hub.onTasksChanged(listOf(task(AgentStatus.Working)))
        assertTrue(
            hub.onTasksChanged(
                listOf(task(AgentStatus.Done, confident = true).copy(automationNotifyFailedOnly = true)),
            ).isEmpty(),
        )
        hub.onTasksChanged(listOf(task(AgentStatus.Working)))
        assertEquals(
            listOf("Error"),
            hub.onTasksChanged(listOf(task(AgentStatus.Error, confident = true))).map { it.kind.name },
        )
    }

    @Test
    fun dedupesSameKindWithinWindow() {
        var now = 1_000L
        val hub = AttentionHub(nowMillis = { now }, sameKindWindowMs = 5_000L)
        hub.onTasksChanged(listOf(task(AgentStatus.Working)))
        assertEquals(1, hub.onTasksChanged(listOf(task(AgentStatus.Done, confident = true))).size)
        hub.onTasksChanged(listOf(task(AgentStatus.Working)))
        now = 2_000L
        assertTrue(hub.onTasksChanged(listOf(task(AgentStatus.Done, confident = true))).isEmpty())
        now = 7_000L
        hub.onTasksChanged(listOf(task(AgentStatus.Working)))
        assertEquals(1, hub.onTasksChanged(listOf(task(AgentStatus.Done, confident = true))).size)
    }

    @Test
    fun wireJsonIncludesSubtitle() {
        val json = AttentionHub.toWireJson(
            AgentAttentionEvent(
                taskId = "t1",
                projectId = "p1",
                title = "hello",
                kind = AgentAttentionKind.Blocked,
            ),
        )
        assertTrue(json.contains("\"kind\":\"Blocked\""))
        assertTrue(json.contains("\"taskId\":\"t1\""))
        assertTrue(json.contains("\"subtitle\":\"Needs your input\""))
    }

    @Test
    fun acpStatusChangeNotifiesWithoutConfidence() {
        val hub = AttentionHub(nowMillis = { 1_000L })
        hub.onTasksChanged(listOf(task(AgentStatus.Working, lane = AgentLaneKind.Acp)))
        assertEquals(
            listOf("Done"),
            hub.onTasksChanged(
                listOf(task(AgentStatus.Done, confident = false, lane = AgentLaneKind.Acp)),
            ).map { it.kind.name },
        )
    }

    @Test
    fun nonAcpStillRequiresConfidence() {
        val hub = AttentionHub(nowMillis = { 1_000L })
        hub.onTasksChanged(listOf(task(AgentStatus.Working, lane = AgentLaneKind.Terminal)))
        assertTrue(
            hub.onTasksChanged(
                listOf(task(AgentStatus.Done, confident = false, lane = AgentLaneKind.Terminal)),
            ).isEmpty(),
        )
        assertEquals(
            listOf("Done"),
            hub.onTasksChanged(
                listOf(task(AgentStatus.Done, confident = true, lane = AgentLaneKind.Terminal)),
            ).map { it.kind.name },
        )
    }

    private fun task(
        status: AgentStatus,
        confident: Boolean = false,
        queued: Boolean = false,
        lane: AgentLaneKind = AgentLaneKind.Acp,
    ) = AgentTask(
        id = "task",
        title = "Ship notifications",
        prompt = "",
        agent = AgentKind.Codex,
        status = status,
        statusConfident = confident,
        startedAtMillis = 1L,
        createdAtMillis = 0,
        lane = lane,
        queuedFollowUps = if (queued) listOf(AgentQueuedFollowUp("next")) else emptyList(),
    )
}
