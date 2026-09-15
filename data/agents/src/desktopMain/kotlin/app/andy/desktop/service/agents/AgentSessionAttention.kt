package app.andy.desktop.service.agents

import app.andy.model.AgentLaneKind
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
 * True when a `question.json` decision card should wait for the live turn to leave
 * [AgentStatus.Working] before parking as Blocked.
 *
 * Agents often write the artifact mid-turn (tool call) and keep streaming text.
 * Surfacing choices during that window lets the user answer early and races the
 * original turn's finish path (loading orb disappears).
 */
internal fun shouldDeferQuestionPark(liveStatus: AgentStatus?): Boolean =
    liveStatus == AgentStatus.Working

/** Max wait after mid-stream question.json before parking even if status is still Working. */
internal const val QUESTION_PARK_SETTLE_MS = 1_500L

/**
 * When the user already answered a grill-me card and a follow-up prompt is in flight,
 * the prior turn must not call [finishTask] — that would stamp Done and hide Working.
 */
internal fun shouldSkipAcpFinishForPendingGrillMeFollowUp(pendingFollowUp: Boolean): Boolean =
    pendingFollowUp

/**
 * True when an ACP turn looks stuck: still streaming with no stop reason, while the provider's
 * status hook has already written `done`.
 *
 * The ACP lane leaves Working only when `session/prompt` returns a stop reason. If that response
 * is dropped the chat sits on Working behind a session that still looks live, and nothing recovers
 * it — so the hook line becomes a second, independent witness that the turn ended. It is a
 * fallback, not authority: the caller still waits out a grace period first, because on a healthy
 * turn the hook fires a beat *before* the stop reason arrives.
 *
 * @param turnStartedAt start of the in-flight turn, or null when no turn is running.
 * @param hookAtMillis the hook's own `at` stamp, which is why a stale line can be told apart.
 */
internal fun acpTurnLooksHung(
    task: AgentTask,
    hookAtMillis: Long,
    turnStartedAt: Long?,
    stopRequested: Boolean,
    hasPendingQuestion: Boolean,
    hasPendingGrillMeFollowUp: Boolean,
): Boolean {
    if (task.lane != AgentLaneKind.Acp) return false
    if (task.status != AgentStatus.Working || !task.isActive) return false
    if (stopRequested) return false
    // No turn in flight, so a `done` left on disk by the last one is just history.
    if (turnStartedAt == null) return false
    // Anything the hook wrote before this turn began describes the previous one.
    if (hookAtMillis < turnStartedAt) return false
    // Grill-me writes `blocked` then `done` around its question, but the park only happens once
    // the turn settles — a question still on disk means this turn is ending the normal way.
    if (hasPendingQuestion) return false
    // An answer follow-up queues behind the prior turn's prompt mutex, so that turn's `done` can
    // land after this one is already counted as started.
    if (hasPendingGrillMeFollowUp) return false
    return true
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
