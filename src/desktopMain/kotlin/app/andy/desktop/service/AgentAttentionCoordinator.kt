package app.andy.desktop.service

import app.andy.model.AgentNotificationTiming
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.service.AgentAttentionCoordinator
import app.andy.service.AgentAttentionEvent
import app.andy.service.AgentAttentionKind
import app.andy.service.NotificationSoundPlayer
import app.andy.service.OsNotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DesktopAgentAttentionCoordinator(
    private val scope: CoroutineScope,
    private val tasks: StateFlow<List<AgentTask>>,
    private val workspace: () -> app.andy.model.WorkspaceState,
    private val isForeground: () -> Boolean,
    private val notifications: OsNotificationService,
    private val sounds: NotificationSoundPlayer,
    private val isViewing: (String) -> Boolean = { false },
) : AgentAttentionCoordinator {
    private data class TrackedStatus(
        val status: AgentStatus?,
        val confident: Boolean,
    )

    private val previous = mutableMapOf<String, TrackedStatus>()
    private var seeded = false

    override fun start() { scope.launch { tasks.collect(::onTasksChanged) } }

    override fun onTasksChanged(tasks: List<AgentTask>) {
        if (!seeded) {
            tasks.forEach { previous[it.id] = TrackedStatus(it.status, it.statusConfident) }
            seeded = true
            return
        }
        tasks.forEach { task ->
            val prior = previous.put(task.id, TrackedStatus(task.status, task.statusConfident))
            if (prior == null) return@forEach
            val kind = when (task.status) {
                AgentStatus.Blocked -> AgentAttentionKind.Blocked
                AgentStatus.Done -> AgentAttentionKind.Done
                AgentStatus.Error -> AgentAttentionKind.Error
                else -> null
            }
            val statusChanged = prior.status != task.status
            val becameConfident = task.statusConfident && !prior.confident
            if (kind == null || (!statusChanged && !becameConfident) || !task.statusConfident) return@forEach
            if (isViewing(task.id)) return@forEach
            if (kind == AgentAttentionKind.Done && task.queuedFollowUps.isNotEmpty()) return@forEach
            val prefs = workspace()
            if (prefs.agentNotificationTiming == AgentNotificationTiming.BackgroundOnly && isForeground()) return@forEach
            if (!AgentNotificationDedup.tryMarkNotified(task.id, kind.name)) return@forEach
            val event = AgentAttentionEvent(task.id, task.projectId, task.notificationTitle, kind)
            if (prefs.agentOsNotificationsEnabled) notifications.show(event)
            if (prefs.agentNotificationSoundEnabled) sounds.play(prefs.agentNotificationSoundId)
        }
        previous.keys.retainAll(tasks.map { it.id }.toSet())
    }
}
