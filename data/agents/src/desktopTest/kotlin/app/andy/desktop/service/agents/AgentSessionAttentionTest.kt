package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentLaneKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.model.ProjectWorkflowStage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentSessionAttentionTest {
  private fun task(status: AgentStatus = AgentStatus.Working) = AgentTask(
        id = "t",
        title = "t",
        prompt = "",
        agent = AgentKind.Codex,
        status = status,
        startedAtMillis = 1L,
        createdAtMillis = 0,
    )

    @Test
    fun doneFromWorkingNeedsUnreadWhenNotViewing() {
        assertTrue(
            statusNeedsUnread(
                task = task(),
                previous = AgentStatus.Working,
                next = AgentStatus.Done,
                viewing = false,
            ),
        )
    }

    @Test
    fun doneFromWorkingDoesNotNeedUnreadWhileTerminalLive() {
        assertFalse(
            statusNeedsUnread(
                task = task(),
                previous = AgentStatus.Working,
                next = AgentStatus.Done,
                viewing = false,
                terminalLive = true,
            ),
        )
    }

    @Test
    fun blockedNeedsUnreadOnTransition() {
        assertTrue(
            statusNeedsUnread(
                task = task(),
                previous = AgentStatus.Working,
                next = AgentStatus.Blocked,
                viewing = false,
            ),
        )
    }

    @Test
    fun viewingSuppressesUnread() {
        assertFalse(
            statusNeedsUnread(
                task = task(),
                previous = AgentStatus.Working,
                next = AgentStatus.Done,
                viewing = true,
            ),
        )
    }

    @Test
    fun buildWorkflowActiveSuppressesUnread() {
        assertFalse(
            statusNeedsUnread(
                task = task().copy(workflowStage = ProjectWorkflowStage.Build),
                previous = AgentStatus.Working,
                next = AgentStatus.Done,
                viewing = false,
            ),
        )
    }

    @Test
    fun remountUnconfidentWorkingDoesNotDemoteConfidentDone() {
        assertTrue(
            shouldIgnoreStatusSnapshot(
                task = task(AgentStatus.Done).copy(statusConfident = true),
                snapshot = AgentStatusSnapshot(AgentStatus.Working, confident = false),
            ),
        )
    }

    @Test
    fun liveTerminalAllowsSoftWorkingAfterDone() {
        assertFalse(
            shouldIgnoreStatusSnapshot(
                task = task(AgentStatus.Done).copy(
                    statusConfident = true,
                    finishedAtMillis = 5L,
                ),
                snapshot = AgentStatusSnapshot(AgentStatus.Working, confident = false),
                terminalLive = true,
            ),
        )
    }

    @Test
    fun softWorkingReplacesBlockedAfterPermissionClears() {
        assertFalse(
            shouldIgnoreStatusSnapshot(
                task = task(AgentStatus.Blocked).copy(statusConfident = true),
                snapshot = AgentStatusSnapshot(AgentStatus.Working, confident = false),
            ),
        )
    }

    @Test
    fun confidentWorkingCanReplaceDone() {
        assertFalse(
            shouldIgnoreStatusSnapshot(
                task = task(AgentStatus.Done).copy(statusConfident = true),
                snapshot = AgentStatusSnapshot(AgentStatus.Working, confident = true),
            ),
        )
    }

    @Test
    fun unconfidentWorkingStillAppliesWhileTaskIsWorking() {
        assertFalse(
            shouldIgnoreStatusSnapshot(
                task = task(AgentStatus.Working).copy(statusConfident = true),
                snapshot = AgentStatusSnapshot(AgentStatus.Working, confident = false),
            ),
        )
    }

    @Test
    fun softWorkingIgnoredWhileFinishedAtSet() {
        assertTrue(
            shouldIgnoreStatusSnapshot(
                task = task(AgentStatus.Done).copy(
                    statusConfident = true,
                    finishedAtMillis = 5L,
                ),
                snapshot = AgentStatusSnapshot(AgentStatus.Working, confident = false),
            ),
        )
    }

    @Test
    fun confidentWorkingReplacesDoneEvenWhenFinishedAtSet() {
        assertFalse(
            shouldIgnoreStatusSnapshot(
                task = task(AgentStatus.Done).copy(
                    statusConfident = true,
                    finishedAtMillis = 5L,
                ),
                snapshot = AgentStatusSnapshot(AgentStatus.Working, confident = true),
            ),
        )
    }

    @Test
    fun questionParkDefersOnlyWhileLiveStatusIsWorking() {
        assertTrue(shouldDeferQuestionPark(AgentStatus.Working))
        assertFalse(shouldDeferQuestionPark(AgentStatus.Blocked))
        assertFalse(shouldDeferQuestionPark(AgentStatus.Done))
        assertFalse(shouldDeferQuestionPark(AgentStatus.Error))
        assertFalse(shouldDeferQuestionPark(null))
    }

    @Test
    fun pendingGrillMeFollowUpSkipsPriorTurnFinish() {
        assertTrue(shouldSkipAcpFinishForPendingGrillMeFollowUp(pendingFollowUp = true))
        assertFalse(shouldSkipAcpFinishForPendingGrillMeFollowUp(pendingFollowUp = false))
    }

    /** task-0a981ba136: final plan streamed, hook wrote `done`, no stop reason ever arrived. */
    @Test
    fun hookDoneDuringStreamingAcpTurnLooksHung() {
        assertTrue(
            acpTurnLooksHung(
                task = acpTask(),
                hookAtMillis = TURN_STARTED_AT + 56_000L,
                turnStartedAt = TURN_STARTED_AT,
                stopRequested = false,
                hasPendingQuestion = false,
                hasPendingGrillMeFollowUp = false,
            ),
        )
    }

    @Test
    fun hookDoneFromAnEarlierTurnIsNotHung() {
        assertFalse(
            acpTurnLooksHung(
                task = acpTask(),
                hookAtMillis = TURN_STARTED_AT - 1L,
                turnStartedAt = TURN_STARTED_AT,
                stopRequested = false,
                hasPendingQuestion = false,
                hasPendingGrillMeFollowUp = false,
            ),
        )
    }

    @Test
    fun hookDoneWithNoTurnInFlightIsNotHung() {
        assertFalse(
            acpTurnLooksHung(
                task = acpTask(),
                hookAtMillis = TURN_STARTED_AT + 56_000L,
                turnStartedAt = null,
                stopRequested = false,
                hasPendingQuestion = false,
                hasPendingGrillMeFollowUp = false,
            ),
        )
    }

    /** Grill-me rounds write `blocked` then `done` while the turn is still settling into its park. */
    @Test
    fun hookDoneWithQuestionStillOnDiskIsNotHung() {
        assertFalse(
            acpTurnLooksHung(
                task = acpTask(),
                hookAtMillis = TURN_STARTED_AT + 8_000L,
                turnStartedAt = TURN_STARTED_AT,
                stopRequested = false,
                hasPendingQuestion = true,
                hasPendingGrillMeFollowUp = false,
            ),
        )
    }

    @Test
    fun hookDoneBehindAQueuedAnswerFollowUpIsNotHung() {
        assertFalse(
            acpTurnLooksHung(
                task = acpTask(),
                hookAtMillis = TURN_STARTED_AT + 8_000L,
                turnStartedAt = TURN_STARTED_AT,
                stopRequested = false,
                hasPendingQuestion = false,
                hasPendingGrillMeFollowUp = true,
            ),
        )
    }

    @Test
    fun hookDoneIsNotHungOnceStopRequestedOrTurnSettled() {
        assertFalse(
            acpTurnLooksHung(
                task = acpTask(),
                hookAtMillis = TURN_STARTED_AT + 56_000L,
                turnStartedAt = TURN_STARTED_AT,
                stopRequested = true,
                hasPendingQuestion = false,
                hasPendingGrillMeFollowUp = false,
            ),
        )
        for (settled in listOf(AgentStatus.Done, AgentStatus.Blocked, AgentStatus.Error)) {
            assertFalse(
                acpTurnLooksHung(
                    task = acpTask(status = settled),
                    hookAtMillis = TURN_STARTED_AT + 56_000L,
                    turnStartedAt = TURN_STARTED_AT,
                    stopRequested = false,
                    hasPendingQuestion = false,
                    hasPendingGrillMeFollowUp = false,
                ),
                "$settled is not a turn in flight",
            )
        }
    }

    @Test
    fun terminalLaneNeverUsesTheHookDoneFallback() {
        assertFalse(
            acpTurnLooksHung(
                task = acpTask().copy(lane = AgentLaneKind.Terminal),
                hookAtMillis = TURN_STARTED_AT + 56_000L,
                turnStartedAt = TURN_STARTED_AT,
                stopRequested = false,
                hasPendingQuestion = false,
                hasPendingGrillMeFollowUp = false,
            ),
        )
    }

    private fun acpTask(status: AgentStatus = AgentStatus.Working) =
        task(status).copy(agent = AgentKind.ClaudeCode, lane = AgentLaneKind.Acp)

    private companion object {
        const val TURN_STARTED_AT = 1789412458000L
    }
}
