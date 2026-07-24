package app.andy.desktop.service

import app.andy.model.AgentKind
import app.andy.model.AgentNotificationTiming
import app.andy.model.AgentQueuedFollowUp
import app.andy.model.AgentTask
import app.andy.model.AgentTaskStatus
import app.andy.model.WorkspaceState
import app.andy.service.AgentAttentionEvent
import app.andy.service.NotificationSoundPlayer
import app.andy.service.OsNotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentAttentionCoordinatorTest {
    @Test
    fun seedsStartupThenNotifiesOnlyAttentionTransitions() {
        val fixture = Fixture()
        fixture.coordinator.onTasksChanged(listOf(task(AgentTaskStatus.Running)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentTaskStatus.Stopped)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentTaskStatus.Completed)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentTaskStatus.Completed)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentTaskStatus.Failed)))

        assertEquals(listOf("Done", "Failed"), fixture.notifications.events.map { it.kind.name })
        assertEquals(listOf("chime", "chime"), fixture.sounds.played)
    }

    @Test
    fun backgroundOnlySuppressesBothChannelsWhileForegroundButAlwaysDoesNot() {
        val fixture = Fixture(foreground = true)
        fixture.coordinator.onTasksChanged(listOf(task(AgentTaskStatus.Running)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentTaskStatus.WaitingForInput)))
        assertEquals(emptyList(), fixture.notifications.events)
        assertEquals(emptyList(), fixture.sounds.played)

        fixture.workspace = fixture.workspace.copy(agentNotificationTiming = AgentNotificationTiming.Always)
        fixture.coordinator.onTasksChanged(listOf(task(AgentTaskStatus.Running)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentTaskStatus.WaitingForInput)))
        assertEquals(listOf("Blocked"), fixture.notifications.events.map { it.kind.name })
        assertEquals(listOf("chime"), fixture.sounds.played)
    }

    @Test
    fun completedWithQueuedFollowUpIsSuppressedAndChannelsAreIndependentlyGated() {
        val fixture = Fixture(
            initialWorkspace = WorkspaceState(agentOsNotificationsEnabled = false, agentNotificationSoundEnabled = true),
        )
        fixture.coordinator.onTasksChanged(listOf(task(AgentTaskStatus.Running)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentTaskStatus.Completed, queued = true)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentTaskStatus.Running)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentTaskStatus.Failed)))

        assertEquals(emptyList(), fixture.notifications.events)
        assertEquals(listOf("chime"), fixture.sounds.played)
    }

    @Test
    fun doesNotNotifyForTerminalTaskThatFirstAppearsAfterStartup() {
        val fixture = Fixture()
        fixture.coordinator.onTasksChanged(emptyList())
        fixture.coordinator.onTasksChanged(listOf(task(AgentTaskStatus.Completed)))

        assertEquals(emptyList(), fixture.notifications.events)
        assertEquals(emptyList(), fixture.sounds.played)
    }

    private class Fixture(
        initialWorkspace: WorkspaceState = WorkspaceState(),
        foreground: Boolean = false,
    ) {
        var workspace = initialWorkspace
        val notifications = RecordingNotifications()
        val sounds = RecordingSounds()
        val coordinator = DesktopAgentAttentionCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            tasks = MutableStateFlow(emptyList()),
            workspace = { workspace },
            isForeground = { foreground },
            notifications = notifications,
            sounds = sounds,
        )
    }

    private class RecordingNotifications : OsNotificationService {
        val events = mutableListOf<AgentAttentionEvent>()
        override fun show(event: AgentAttentionEvent) { events += event }
    }

    private class RecordingSounds : NotificationSoundPlayer {
        val played = mutableListOf<String>()
        override fun play(soundId: String) { played += soundId }
    }

    private companion object {
        fun task(status: AgentTaskStatus, queued: Boolean = false) = AgentTask(
            id = "task", title = "Ship notifications", prompt = "", agent = AgentKind.Codex,
            status = status, createdAtMillis = 0,
            queuedFollowUps = if (queued) listOf(AgentQueuedFollowUp("next")) else emptyList(),
        )
    }
}
