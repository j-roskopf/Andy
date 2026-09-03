package app.andy.desktop.service.agents

import app.andy.desktop.test.OptInGates
import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.terminal.TerminalSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.yield
import java.io.File

/**
 * Herdr-parity scenario harness: drive screen buffers only (no status.json hooks)
 * and assert badge sequences. CI gate for wrong-status regressions.
 */
class AgentStatusScenarioTest {
    @Test
    fun claudePermissionThenWorkingThenIdleDoneWithoutHooks() = runScenario(AgentKind.ClaudeCode) {
        screen(
            """
            Bash(gh pr list)
            Do you want to proceed?
            ❯ 1. Yes
            2. No
            """.trimIndent(),
        ).expect(AgentStatus.Blocked)

        screen("✨ Perambulating... (12s · ↓ 80 tokens · thinking more)\n")
            .expect(AgentStatus.Working)

        screen("There are 3 open PRs.\n✻ Cooked for 4s\n> ")
            .expect(AgentStatus.Done)
    }

    @Test
    fun claudeAskClearsToTextThenIdleDone() = runScenario(AgentKind.ClaudeCode) {
        screen(
            """
            ────────────────────────────────────────
            What should I focus on?
            ❯ 1. PRs
            2. Tests
            enter to select · esc to cancel · ↑/↓ to navigate
            """.trimIndent(),
        ).expect(AgentStatus.Blocked)

        // Text-only continuation without working chrome → idle fallback / Done (Herdr).
        screen("You picked PRs. Checking the repo next…\n")
            .expect(AgentStatus.Done)

        screen("There are 3 open PRs.\n> ")
            .expect(AgentStatus.Done)
    }

    @Test
    fun doneIdleChromeRedrawStaysDone() = runScenario(AgentKind.ClaudeCode) {
        screen("Here is the answer.\n✻ Cooked for 2s\n> ")
            .expect(AgentStatus.Done)

        repeat(4) { i ->
            screen("Here is the answer.\n✻ Cooked for 2s\n> \nstatus redraw $i\n")
                .expect(AgentStatus.Done)
        }
        assertTrue(statuses.none { it == AgentStatus.Working })
    }

    @Test
    fun streamingProseAloneDoesNotFlipDoneToWorking() = runScenario(AgentKind.ClaudeCode) {
        screen("Implemented the fix.\n> ")
            .expect(AgentStatus.Done)

        screen("Implemented the fix.\nSome leftover prose without spinner chrome.\n")
            .expect(AgentStatus.Done)
    }

    @Test
    fun cursorStopHintThenFollowUpIdle() = runScenario(AgentKind.Cursor) {
        screen(
            "Reading files…\n" +
                "            ctrl+c to stop\n" +
                " ▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄\n" +
                "  Cursor Grok 4.5 High Fast · 22%\n",
        ).expect(AgentStatus.Working)

        screen(
            "Done with that.\n" +
                "  → Add a follow-up\n" +
                "  Cursor Grok 4.5 High Fast · 23%\n",
        ).expect(AgentStatus.Done)
    }

    @Test
    fun antigravityPermissionAndIdlePrompt() = runScenario(AgentKind.Antigravity) {
        screen(
            """
            Requesting permission for:
            do you want to proceed?
            """.trimIndent(),
        ).expect(AgentStatus.Blocked)

        screen("Antigravity agent ready\n> ")
            .expect(AgentStatus.Done)
    }

    @Test
    fun antigravityBootIdlePromptStaysWorkingUntilTurnArmed() = runScenario(AgentKind.Antigravity, suppressPrematureIdle = true) {
        tracker.markUserWorking()
        screen("Antigravity agent ready\n> ")
            .expect(AgentStatus.Working)

        osc(title = "agy andy:idle")
        screen("Antigravity agent ready\n> ")
            .expect(AgentStatus.Working)

        osc(title = "agy andy:working")
        screen("Antigravity agent ready\n> ")
            .expect(AgentStatus.Working)

        osc(title = "agy andy:idle")
        screen("Antigravity agent ready\n> ")
            .expect(AgentStatus.Done)
    }

    @Test
    fun codexActionRequiredThenWorkingThenIdleTitle() = runScenario(AgentKind.Codex) {
        osc(title = "Action Required — allow network")
        screen("› ")
            .expect(AgentStatus.Blocked)

        osc(title = "⠋ working")
        screen("› ")
            .expect(AgentStatus.Working)

        osc(title = "codex")
        screen("› ")
            .expect(AgentStatus.Done)
    }

    @Test
    fun bootIdlePromptStaysWorkingUntilChrome() = runScenario(AgentKind.ClaudeCode, suppressPrematureIdle = true) {
        // argv prompt is still being digested — splash already shows an idle `>` prompt.
        screen("> ")
            .expect(AgentStatus.Working)

        screen("✨ Perambulating... (3s · ↓ 10 tokens · thinking more)\n")
            .expect(AgentStatus.Working)

        screen("The weather in Fond du Lac is sunny.\n✻ Cooked for 4s\n> ")
            .expect(AgentStatus.Done)
    }

    @Test
    fun cursorBootFollowUpChromeStaysWorkingUntilSpinner() = runScenario(AgentKind.Cursor, suppressPrematureIdle = true) {
        screen(
            "  → Add a follow-up\n" +
                "  Cursor Grok 4.5 High Fast · 5%\n",
        ).expect(AgentStatus.Working)

        screen(
            "Reading files…\n" +
                "            ctrl+c to stop\n" +
                "  Cursor Grok 4.5 High Fast · 22%\n",
        ).expect(AgentStatus.Working)

        screen(
            "Done with that.\n" +
                "  → Add a follow-up\n",
        ).expect(AgentStatus.Done)
    }

    @Test
    fun claudeBlockedThenWorkingChromeThenIdleDone() = runScenario(AgentKind.ClaudeCode) {
        screen(
            """
            Do you want to proceed?
            ❯ 1. Yes
              2. No
            esc to cancel
            """.trimIndent(),
        ).expect(AgentStatus.Blocked)

        screen("✨ Perambulating... (3s · ↓ 10 tokens · thinking more)\n")
            .expect(AgentStatus.Working)

        screen("Here are the PR counts.\n> ")
            .expect(AgentStatus.Done)
    }

    @Test
    fun codexIdleAtPlaceholderPromptIsDone() = runScenario(AgentKind.Codex) {
        osc(title = "⠋ working")
        screen("• Working (esc to interrupt)\n")
            .expect(AgentStatus.Working)

        osc(title = "Andy")
        screen(
            """
            • As of now, Andy has 59 PRs total.

            ─ Worked for 1m 21s ─────────────────────────────────────────────────────────────


            › Find and fix a bug in @filename

              gpt-5.6-terra high · ~/Code/Andy/Andy
            """.trimIndent(),
        ).expect(AgentStatus.Done)
    }

    @Test
    fun userSendBumpsWorkingThenScrapeOwnsTurn() = runScenario(AgentKind.ClaudeCode) {
        screen("Ready.\n> ").expect(AgentStatus.Done)
        tracker.markUserWorking()
        await(AgentStatus.Working)
        screen("✨ Perambulating... (3s · ↓ 10 tokens · thinking more)\n")
            .expect(AgentStatus.Working)
        screen("All done.\n> ")
            .expect(AgentStatus.Done)
    }

    private class ScenarioRobot(
        val tracker: AgentStatusTracker,
        val session: ScenarioSession,
        val statuses: MutableList<AgentStatus>,
    ) {
        suspend fun screen(text: String): Expect {
            session.emitBuffer(text)
            // Tracker collects bufferSnapshots asynchronously. Yield so the scrape runs before
            // awaitStatus can short-circuit on a stale status that already matches (e.g.
            // suppressPrematureIdle Working → Perambulating Working must arm the turn).
            yield()
            delay(30)
            return Expect(this)
        }
        suspend fun osc(title: String = "", progress: String = "") {
            session.setOsc(title, progress)
        }
        suspend fun await(expected: AgentStatus) = tracker.awaitStatus(expected)
        suspend fun Expect.expect(expected: AgentStatus) {
            tracker.awaitStatus(expected)
        }
    }

    private class Expect(val robot: ScenarioRobot)

    private fun runScenario(
        agent: AgentKind,
        suppressPrematureIdle: Boolean = false,
        block: suspend ScenarioRobot.() -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val artifactDir = File.createTempFile("andy-scenario", null).also {
                it.delete()
                it.mkdirs()
            }
            val session = ScenarioSession()
            val statuses = mutableListOf<AgentStatus>()
            val tracker = AgentStatusTracker(
                scope = scope,
                taskId = "scenario",
                agent = agent,
                artifactDir = artifactDir,
                session = session,
                onSnapshot = { statuses += it.status },
                suppressPrematureIdle = suppressPrematureIdle,
            )
            tracker.start()
            ScenarioRobot(tracker, session, statuses).block()
            tracker.close()
        } finally {
            scope.cancel()
        }
    }
}

private suspend fun AgentStatusTracker.awaitStatus(
    expected: AgentStatus,
    timeoutMillis: Long = OptInGates.harnessTimeoutMillis(10_000, 20_000, 45_000),
) {
    try {
        withTimeout(timeoutMillis) {
            while (status.value.status != expected) delay(20)
        }
    } catch (_: TimeoutCancellationException) {
        assertEquals(expected, status.value.status)
    }
}

private class ScenarioSession : TerminalSession {
    override val sessionId: String = "scenario"
    override val isAlive: Boolean = true
    override val exitCode: StateFlow<Int?> = MutableStateFlow(null)
    override val pid: Long? = null

    private val snapshots = MutableSharedFlow<String>(extraBufferCapacity = 8, replay = 1)
    override val bufferSnapshots: SharedFlow<String> = snapshots
    private val title = MutableStateFlow("")
    private val progress = MutableStateFlow("")
    override val windowTitle: StateFlow<String> = title
    override val oscProgress: StateFlow<String> = progress

    @Volatile var screenBuffer: String = ""

    override fun start(argv: List<String>, cwd: String?, env: Map<String, String>) = Unit
    override fun write(bytes: ByteArray) = Unit
    override fun resize(cols: Int, rows: Int) = Unit
    override fun bufferSnapshot(): String = screenBuffer
    override fun close() = Unit

    suspend fun emitBuffer(text: String) {
        screenBuffer = text
        snapshots.emit(text)
    }

    fun setOsc(titleValue: String, progressValue: String) {
        title.value = titleValue
        progress.value = progressValue
    }
}
