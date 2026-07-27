package app.andy.ui.agents

import app.andy.model.AgentStatus
import app.andy.model.AgentKind
import app.andy.model.AgentTask
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
}
