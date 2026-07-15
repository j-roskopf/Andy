package app.andy.desktop.service

import app.andy.model.AgentNotificationTiming
import app.andy.model.AgentTask
import app.andy.model.AgentTaskStatus
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
) : AgentAttentionCoordinator {
    private val previousStatuses = mutableMapOf<String, AgentTaskStatus>()
    private var seeded = false
    override fun start() { scope.launch { tasks.collect(::onTasksChanged) } }
    override fun onTasksChanged(tasks: List<AgentTask>) {
        if (!seeded) { tasks.forEach { previousStatuses[it.id] = it.status }; seeded = true; return }
        tasks.forEach { task ->
            val previous = previousStatuses.put(task.id, task.status)
            val kind = when (task.status) {
                AgentTaskStatus.Completed -> AgentAttentionKind.Completed
                AgentTaskStatus.WaitingForInput -> AgentAttentionKind.NeedsInput
                AgentTaskStatus.Failed -> AgentAttentionKind.Failed
                else -> null
            }
            // A task that first appears already terminal is not an observed status
            // transition. This also keeps restored/imported tasks quiet.
            if (previous == null || previous == task.status || kind == null ||
                (kind == AgentAttentionKind.Completed && task.queuedFollowUps.isNotEmpty())
            ) return@forEach
            val prefs = workspace()
            if (prefs.agentNotificationTiming == AgentNotificationTiming.BackgroundOnly && isForeground()) return@forEach
            val event = AgentAttentionEvent(task.id, task.projectId, task.title, kind)
            if (prefs.agentOsNotificationsEnabled) notifications.show(event)
            if (prefs.agentNotificationSoundEnabled) sounds.play(prefs.agentNotificationSoundId)
        }
    }
}
