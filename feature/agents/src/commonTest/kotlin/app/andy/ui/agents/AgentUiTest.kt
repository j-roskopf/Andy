package app.andy.ui.agents

import app.andy.model.AgentStatus
import app.andy.model.AgentKind
import app.andy.model.AgentTask
import app.andy.model.AgentUserInputOrigin
import app.andy.model.AgentUserInputQuestion
import app.andy.model.AgentUserInputRequest
import app.andy.model.ProjectWorkflowStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentUiTest {
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
    fun launchingChatIsInteractiveBeforeTheTerminalExists() {
        // A brand new chat has no status and no session yet — it must show the terminal
        // coming up, never the read-only "session ended" state.
        val queued = task().copy(status = null, startedAtMillis = null, finishedAtMillis = null)
        assertTrue(isChatTerminalInteractive(queued, terminalLive = false))
        // Resume/retry relaunches keep the original start time but clear the status.
        assertTrue(isChatTerminalInteractive(queued.copy(startedAtMillis = 1L), terminalLive = false))
    }

    @Test
    fun runningChatIsInteractive() {
        assertTrue(isChatTerminalInteractive(task(AgentStatus.Working), terminalLive = false))
        assertTrue(isChatTerminalInteractive(task(AgentStatus.Blocked), terminalLive = false))
    }

    @Test
    fun doneChatStaysInteractiveWhileTmuxSessionIsAlive() {
        // Turn finished but tmux is still at its prompt — keep the live CLI mounted.
        val idle = task(AgentStatus.Done).copy(finishedAtMillis = 5L, statusConfident = true)
        assertTrue(isChatTerminalInteractive(idle, terminalLive = true))
        assertFalse(isChatTerminalInteractive(idle, terminalLive = false))
    }

    @Test
    fun endedChatsOpenReadOnly() {
        // Stopped, exited, or interrupted by an app restart — Andy owns no session for
        // any of these, so the pane replays scrollback until a follow-up resumes it.
        val finished = task(AgentStatus.Done).copy(finishedAtMillis = 5L)
        assertFalse(isChatTerminalInteractive(finished, terminalLive = false))
        assertFalse(isChatTerminalInteractive(finished.copy(stoppedByUser = true), terminalLive = false))
        assertFalse(isChatTerminalInteractive(finished.copy(stoppedByUser = true), terminalLive = true))
        assertFalse(isChatTerminalInteractive(finished.copy(resumable = true), terminalLive = false))
        assertFalse(
            isChatTerminalInteractive(
                task(AgentStatus.Error).copy(finishedAtMillis = 5L, interrupted = true),
                terminalLive = false,
            ),
        )
    }

    @Test
    fun relaunchingChatShowsLaunchingBadge() {
        val relaunching = task(AgentStatus.Done).copy(status = null, finishedAtMillis = null)
        assertTrue(isChatRelaunching(relaunching))
        assertEquals("launching", agentStatusLabel(relaunching))
        assertTrue(isChatTerminalInteractive(relaunching, terminalLive = false))
    }

    @Test
    fun statusLabelUsesFourAgentStatuses() {
        assertEquals("done", agentStatusLabel(task(AgentStatus.Done).copy(resumable = true)))
        assertEquals("working", agentStatusLabel(task(AgentStatus.Working).copy(resumable = true)))
        assertEquals("blocked", agentStatusLabel(task(AgentStatus.Blocked)))
        assertEquals("error", agentStatusLabel(task(AgentStatus.Error).copy(interrupted = true)))
        assertEquals("done", agentStatusLabel(task(AgentStatus.Done).copy(stoppedByUser = true)))
    }

    @Test
    fun donePlanModeShowsPlanReady() {
        val planDone = task(AgentStatus.Done).copy(planMode = true)
        assertTrue(isAwaitingPlanConfirmation(planDone))
        assertEquals("plan ready", agentStatusLabel(planDone))
        assertEquals(
            "plan ready",
            agentStatusLabel(planDone.copy(workflowStage = ProjectWorkflowStage.Spec)),
        )
    }

    @Test
    fun awaitingPlanConfirmationRequiresIdlePlanModeWithoutInput() {
        assertFalse(isAwaitingPlanConfirmation(task(AgentStatus.Done)))
        assertFalse(isAwaitingPlanConfirmation(task(AgentStatus.Working).copy(planMode = true)))
        assertFalse(
            isAwaitingPlanConfirmation(
                task(AgentStatus.Done).copy(
                    planMode = true,
                    userInputRequest = AgentUserInputRequest(
                        id = "q1",
                        questions = listOf(
                            AgentUserInputQuestion(
                                id = "q",
                                question = "Continue?",
                                options = emptyList(),
                            ),
                        ),
                        origin = AgentUserInputOrigin.Artifact,
                    ),
                ),
            ),
        )
        assertTrue(isAwaitingPlanConfirmation(task(AgentStatus.Done), planModeActive = true))
        assertEquals("done", agentStatusLabel(task(AgentStatus.Done)))
    }

    @Test
    fun doneWithPendingPlanEntriesShowsPlanReadyWithoutPlanMode() {
        // Cursor Create Plan can end_turn with pending plan rows while Andy planMode stays off.
        val done = task(AgentStatus.Done)
        assertTrue(
            isAwaitingPlanConfirmation(
                task = done,
                planModeActive = false,
                hasPendingPlanEntries = true,
            ),
        )
        assertEquals(
            "plan ready",
            agentStatusLabel(done, planModeActive = false, hasPendingPlanEntries = true),
        )
        assertFalse(
            isAwaitingPlanConfirmation(
                task = task(AgentStatus.Working),
                planModeActive = false,
                hasPendingPlanEntries = true,
            ),
        )
    }

    @Test
    fun statusLabelPrefersLifecycleStatusWhenStartedAtIsMissing() {
        // Thin MCP list payloads used to omit startedAtMillis; never show "queued" for Done/Working.
        assertEquals(
            "done",
            agentStatusLabel(task(AgentStatus.Done).copy(startedAtMillis = null, finishedAtMillis = 5L)),
        )
        assertEquals(
            "working",
            agentStatusLabel(task(AgentStatus.Working).copy(startedAtMillis = null)),
        )
        assertEquals(
            "queued",
            agentStatusLabel(task().copy(status = null, startedAtMillis = null, finishedAtMillis = null)),
        )
    }

    @Test
    fun composerAppearsInReadOnlyModeOrWhenImagesAreStaged() {
        assertTrue(showsChatFollowUpComposer(interactive = false, hasStagedImages = false))
        assertFalse(showsChatFollowUpComposer(interactive = true, hasStagedImages = false))
        assertTrue(showsChatFollowUpComposer(interactive = true, hasStagedImages = true))
    }

    @Test
    fun interactiveSessionHidesComposerBeforeAttachCompletes() {
        // Resume/reattach used to wait on attachedTerminalIds before hiding the composer.
        // That briefly stole terminal height — hide as soon as the session is interactive,
        // even while the viewer is still mounting.
        val working = task(AgentStatus.Working)
        assertTrue(isChatTerminalInteractive(working, terminalLive = false))
        assertFalse(
            showsChatFollowUpComposer(
                interactive = isChatTerminalInteractive(working, terminalLive = false),
                hasStagedImages = false,
            ),
        )
        val relaunching = task(AgentStatus.Done).copy(status = null, finishedAtMillis = null)
        assertTrue(isChatTerminalInteractive(relaunching, terminalLive = false))
        assertFalse(
            showsChatFollowUpComposer(
                interactive = isChatTerminalInteractive(relaunching, terminalLive = false),
                hasStagedImages = false,
            ),
        )
    }

    @Test
    fun oldTerminalThreadHidesComposerWhileReconnectingUntilFailure() {
        val done = task(AgentStatus.Done)
        assertFalse(isChatTerminalInteractive(done, terminalLive = false))

        // When the terminal UI can reconnect, suppress the composer to prevent the text field from flashing
        assertFalse(
            showsChatFollowUpComposer(
                interactive = isChatTerminalInteractive(done, terminalLive = false),
                hasStagedImages = false,
                canReconnect = true,
            ),
        )

        // If the user has staged images, composer still appears even if reconnecting
        assertTrue(
            showsChatFollowUpComposer(
                interactive = isChatTerminalInteractive(done, terminalLive = false),
                hasStagedImages = true,
                canReconnect = true,
            ),
        )

        // Once reattach fails (or if reconnect is impossible), composer appears for read-only replay
        assertTrue(
            showsChatFollowUpComposer(
                interactive = isChatTerminalInteractive(done, terminalLive = false),
                hasStagedImages = false,
                canReconnect = false,
            ),
        )
    }

    @Test
    fun isElapsedLiveOnlyWhileWorking() {
        assertTrue(isElapsedLive(task()))
        assertFalse(isElapsedLive(task(AgentStatus.Done)))
        assertFalse(isElapsedLive(task(AgentStatus.Blocked)))
        assertFalse(isElapsedLive(task().copy(startedAtMillis = null, status = null)))
    }

    @Test
    fun isSessionWorkingOnlyWhileWorking() {
        assertTrue(isSessionWorking(task()))
        assertFalse(isSessionWorking(task(AgentStatus.Done)))
        assertFalse(isSessionWorking(task(AgentStatus.Blocked)))
    }

    @Test
    fun formatWorkedClockUsesMinuteSecondClock() {
        assertEquals("0:09", formatWorkedClock(9_000))
        assertEquals("2:05", formatWorkedClock(125_000))
        assertEquals("1:02:03", formatWorkedClock(3_723_000))
        assertEquals("Worked for 2:05", workedHeadline(125_000, success = true))
        assertEquals("Failed after 0:09", workedHeadline(9_000, success = false))
    }

    @Test
    fun formatChatAgeUsesCompactRepositoryRailLabels() {
        val now = 1_000_000L
        assertEquals("now", formatChatAge(now, now))
        assertEquals("2m", formatChatAge(now - 2 * 60_000L, now))
        assertEquals("3h", formatChatAge(now - 3 * 60 * 60_000L, now))
        assertEquals("4d", formatChatAge(now - 4 * 24 * 60 * 60_000L, now))
        assertEquals("2mo", formatChatAge(now - 2 * 30 * 24 * 60 * 60_000L, now))
    }

    @Test
    fun completedTurnChromeShowsAfterWorkingEnds() {
        assertFalse(showsCompletedTurnChrome(task(AgentStatus.Working)))
        assertTrue(showsCompletedTurnChrome(task(AgentStatus.Done).copy(finishedAtMillis = 5L)))
        assertTrue(showsCompletedTurnChrome(task(AgentStatus.Error).copy(finishedAtMillis = 5L)))
        assertFalse(showsCompletedTurnChrome(task().copy(status = null, startedAtMillis = null, finishedAtMillis = null)))
    }
}
