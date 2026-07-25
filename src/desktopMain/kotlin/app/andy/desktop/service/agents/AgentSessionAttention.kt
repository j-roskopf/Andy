package app.andy.desktop.service.agents

import app.andy.model.AgentSessionStatus
import app.andy.model.AgentTask
import app.andy.model.AgentTaskStatus
import app.andy.model.ProjectWorkflowStage

/** True when a live embedded session needs an unread badge after [next] replaces [previous]. */
internal fun sessionStatusNeedsUnread(
    task: AgentTask,
    previous: AgentSessionStatus?,
    next: AgentSessionStatus,
    viewing: Boolean,
): Boolean {
    if (viewing) return false
    if (task.workflowStage == ProjectWorkflowStage.Build && task.isActive) return false
    if (!task.isActive && task.status != AgentTaskStatus.Paused) return false
    return when (next) {
        AgentSessionStatus.Done ->
            previous == AgentSessionStatus.Working || previous == AgentSessionStatus.Blocked
        AgentSessionStatus.Blocked ->
            previous != AgentSessionStatus.Blocked
        AgentSessionStatus.Idle ->
            previous == AgentSessionStatus.Working || previous == AgentSessionStatus.Blocked
        else -> false
    }
}
