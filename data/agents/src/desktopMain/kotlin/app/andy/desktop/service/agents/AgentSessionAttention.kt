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
 * Working must be able to replace Done/Blocked when the turn continues (blocker
 * cleared, visible working chrome, or user send). Remount soft-Working from a
 * half-drawn idle screen must not demote confident Done/Error — but only when the
 * terminal is no longer live. A live interactive session keeps updating freely
 * even after [AgentTask.finishedAtMillis] was stamped (early turn-complete).
 *
 * Soft Working may still replace Blocked — once the blocker leaves the screen the
 * turn is in progress again (Herdr: blocked → working/idle, never stuck blocked).
 */
internal fun shouldIgnoreStatusSnapshot(
    task: AgentTask,
    snapshot: AgentStatusSnapshot,
    terminalLive: Boolean = false,
): Boolean {
    if (task.status == AgentStatus.Blocked &&
        task.userInputRequest != null &&
        snapshot.status != AgentStatus.Blocked
    ) {
        return true
    }
    // Live interactive sessions: always accept Working (soft or confident).
    if (terminalLive && snapshot.status == AgentStatus.Working) {
        return false
    }
    // Soft Working after confident Done/Error is remount / boot noise.
    // Soft Working after Blocked is a real turn continuation — allow it.
    if (snapshot.status == AgentStatus.Working &&
        !snapshot.confident &&
        task.statusConfident &&
        (task.status == AgentStatus.Done || task.status == AgentStatus.Error)
    ) {
        return true
    }
    // Soft Working after a finalized turn is remount noise. Confident Working
    // (user send / visible working chrome) clears the stamp via applyStatusSnapshot.
    if (snapshot.status == AgentStatus.Working &&
        !snapshot.confident &&
        task.finishedAtMillis != null &&
        task.status != AgentStatus.Working
    ) {
        return true
    }
    return false
}
