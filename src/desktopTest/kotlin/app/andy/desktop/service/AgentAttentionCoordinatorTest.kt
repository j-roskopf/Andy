package app.andy.desktop.service

import app.andy.model.AgentKind
import app.andy.model.AgentNotificationTiming
import app.andy.model.AgentQueuedFollowUp
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
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
    fun seedsStartupThenNotifiesOnlyConfidentAttentionTransitions() {
        val fixture = Fixture()
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Working)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Done, stoppedByUser = true)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Working)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Done, confident = true)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Done, confident = true)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Error, confident = true)))

        assertEquals(listOf("Done", "Error"), fixture.notifications.events.map { it.kind.name })
        assertEquals(listOf("chime", "chime"), fixture.sounds.played)
    }

    @Test
    fun backgroundOnlySuppressesBothChannelsWhileForegroundButAlwaysDoesNot() {
        val fixture = Fixture(foreground = true)
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Working)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Blocked, confident = true)))
        assertEquals(emptyList(), fixture.notifications.events)
        assertEquals(emptyList(), fixture.sounds.played)

        fixture.workspace = fixture.workspace.copy(agentNotificationTiming = AgentNotificationTiming.Always)
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Working)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Blocked, confident = true)))
        assertEquals(listOf("Blocked"), fixture.notifications.events.map { it.kind.name })
        assertEquals(listOf("chime"), fixture.sounds.played)
    }

    @Test
    fun doneWhileTerminalLiveStillNotifiesWhenNotViewing() {
        val fixture = Fixture()
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Working)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Done, confident = true)))

        assertEquals(listOf("Done"), fixture.notifications.events.map { it.kind.name })
        assertEquals(listOf("chime"), fixture.sounds.played)
    }

    @Test
    fun doneWhileViewingPlaysSoundButSkipsOsBanner() {
        val fixture = Fixture(viewing = { it == "task" })
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Working)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Done, confident = true)))

        assertEquals(emptyList(), fixture.notifications.events)
        assertEquals(listOf("chime"), fixture.sounds.played)
    }

    @Test
    fun blockedWhileViewingPlaysSoundButSkipsOsBanner() {
        val fixture = Fixture(
            foreground = true,
            viewing = { it == "task" },
            initialWorkspace = WorkspaceState(agentNotificationTiming = AgentNotificationTiming.Always),
        )
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Working)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Blocked, confident = true)))

        assertEquals(emptyList(), fixture.notifications.events)
        assertEquals(listOf("chime"), fixture.sounds.played)
    }

    @Test
    fun blockedWhileNotViewingShowsOsAndSound() {
        val fixture = Fixture(
            initialWorkspace = WorkspaceState(agentNotificationTiming = AgentNotificationTiming.Always),
        )
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Working)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Blocked, confident = true)))

        assertEquals(listOf("Blocked"), fixture.notifications.events.map { it.kind.name })
        assertEquals(listOf("chime"), fixture.sounds.played)
    }

    @Test
    fun notifiesWhenDoneBecomesConfidentWithoutStatusChange() {
        val fixture = Fixture()
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Working)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Done, confident = false)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Done, confident = true)))

        assertEquals(listOf("Done"), fixture.notifications.events.map { it.kind.name })
        assertEquals(listOf("chime"), fixture.sounds.played)
    }

    @Test
    fun doneWithQueuedFollowUpIsSuppressedAndChannelsAreIndependentlyGated() {
        val fixture = Fixture(
            initialWorkspace = WorkspaceState(agentOsNotificationsEnabled = false, agentNotificationSoundEnabled = true),
        )
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Working)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Done, confident = true, queued = true)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Working)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Error, confident = true)))

        assertEquals(emptyList(), fixture.notifications.events)
        assertEquals(listOf("chime"), fixture.sounds.played)
    }

    @Test
    fun goingIdleSoundsOnlyWhileTheSoundPreferenceIsOn() {
        val fixture = Fixture(
            initialWorkspace = WorkspaceState(agentNotificationSoundEnabled = false),
        )
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Working)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Done, confident = true)))
        assertEquals(listOf("Done"), fixture.notifications.events.map { it.kind.name })
        assertEquals(emptyList(), fixture.sounds.played)

        AgentNotificationDedup.clearForTests()
        fixture.workspace = fixture.workspace.copy(
            agentNotificationSoundEnabled = true,
            agentNotificationSoundId = "ping",
        )
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Working)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Done, confident = true)))
        assertEquals(listOf("ping"), fixture.sounds.played)
    }

    @Test
    fun ignoresScrapeOnlyBadgeMoves() {
        val fixture = Fixture()
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Working)))
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Done, confident = false)))
        assertEquals(emptyList(), fixture.notifications.events)
    }

    @Test
    fun doesNotNotifyForTerminalTaskThatFirstAppearsAfterStartup() {
        val fixture = Fixture()
        fixture.coordinator.onTasksChanged(emptyList())
        fixture.coordinator.onTasksChanged(listOf(task(AgentStatus.Done, confident = true)))

        assertEquals(emptyList(), fixture.notifications.events)
        assertEquals(emptyList(), fixture.sounds.played)
    }

    @Test
    fun usesLatestPromptForNotificationTitleWhenPresent() {
        val fixture = Fixture()
        val originalTask = task(AgentStatus.Working).copy(title = "Original task prompt")
        fixture.coordinator.onTasksChanged(listOf(originalTask))

        val followUpTask = originalTask.copy(status = AgentStatus.Done, statusConfident = true, latestPrompt = "Follow-up prompt text")
        fixture.coordinator.onTasksChanged(listOf(followUpTask))

        assertEquals(1, fixture.notifications.events.size)
        assertEquals("Follow-up prompt text", fixture.notifications.events.first().title)
    }

    @Test
    fun usesOriginalTitleForNotificationTitleWhenOnlyOnePrompt() {
        val fixture = Fixture()
        val originalTask = task(AgentStatus.Working).copy(title = "Original task prompt", latestPrompt = null)
        fixture.coordinator.onTasksChanged(listOf(originalTask))

        val doneTask = originalTask.copy(status = AgentStatus.Done, statusConfident = true)
        fixture.coordinator.onTasksChanged(listOf(doneTask))

        assertEquals(1, fixture.notifications.events.size)
        assertEquals("Original task prompt", fixture.notifications.events.first().title)
    }

    private class Fixture(
        initialWorkspace: WorkspaceState = WorkspaceState(),
        foreground: Boolean = false,
        viewing: (String) -> Boolean = { false },
    ) {
        init {
            AgentNotificationDedup.clearForTests()
        }
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
            isViewing = viewing,
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
        fun task(
            status: AgentStatus,
            confident: Boolean = false,
            stoppedByUser: Boolean = false,
            queued: Boolean = false,
        ) = AgentTask(
            id = "task",
            title = "Ship notifications",
            prompt = "",
            agent = AgentKind.Codex,
            status = status,
            stoppedByUser = stoppedByUser,
            statusConfident = confident,
            startedAtMillis = 1L,
            createdAtMillis = 0,
            queuedFollowUps = if (queued) listOf(AgentQueuedFollowUp("next")) else emptyList(),
        )
    }
}
