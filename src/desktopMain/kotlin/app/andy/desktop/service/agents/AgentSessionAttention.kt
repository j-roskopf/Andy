package app.andy.desktop.service.agents

import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.model.ProjectWorkflowStage

/** True when a status transition needs an unread badge. */
internal fun statusNeedsUnread(
    task: AgentTask,
    previous: AgentStatus?,
    next: AgentStatus?,
    viewing: Boolean,
    terminalLive: Boolean = false,
): Boolean {
    if (viewing || next == null) return false
    if (task.workflowStage == ProjectWorkflowStage.Build && task.isActive) return false
    if (!task.isActive && !task.resumable && task.status != AgentStatus.Blocked) return false
    return when (next) {
        AgentStatus.Done ->
            !terminalLive &&
                (previous == AgentStatus.Working || previous == AgentStatus.Blocked)
        AgentStatus.Blocked ->
            previous != AgentStatus.Blocked
        AgentStatus.Error ->
            previous != AgentStatus.Error
        else -> false
    }
}

/**
 * True when a live-status scrape should not overwrite the task badge.
 *
 * Remounting a Done chat (switch away → back) often publishes unconfident Working from a
 * half-drawn idle screen. Confident Done/Error/Blocked must not flip on that noise.
 */
internal fun shouldIgnoreStatusSnapshot(
    task: AgentTask,
    snapshot: AgentStatusSnapshot,
): Boolean {
    if (task.status == AgentStatus.Blocked &&
        task.userInputRequest != null &&
        snapshot.status != AgentStatus.Blocked
    ) {
        return true
    }
    if (snapshot.status == AgentStatus.Working &&
        !snapshot.confident &&
        task.statusConfident &&
        task.status != null &&
        task.status != AgentStatus.Working
    ) {
        return true
    }
    // A finished turn must not flip back to Working until resume clears finishedAtMillis.
    if (snapshot.status == AgentStatus.Working &&
        task.finishedAtMillis != null &&
        task.status != AgentStatus.Working
    ) {
        return true
    }
    return false
}
