package app.andy.desktop.service.agents

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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import java.io.File

class AgentStatusTrackerTest {
    @Test
    fun parseStatusJsonMapsBlockedDoneWorkingAndError() {
        assertEquals(AgentStatus.Blocked, parseStatusJson("""{"status":"blocked"}"""))
        assertEquals(AgentStatus.Done, parseStatusJson("""{"status":"done"}"""))
        assertEquals(AgentStatus.Working, parseStatusJson("""{"status":"working"}"""))
        assertEquals(AgentStatus.Error, parseStatusJson("""{"status":"error"}"""))
    }

    @Test
    fun claudeManifestMatchesPermissionPrompt() {
        val screen = """
            Bash(ls)
            Do you want to proceed?
            ❯ 1. Yes
            2. No
        """.trimIndent()
        val match = evaluateScreenManifest(AgentKind.ClaudeCode, DetectionInput(screen = screen))
        assertEquals(ScreenState.Blocked, match.state)
        assertTrue(match.visibleBlocker)
        assertTrue(bufferLooksBlocked(AgentKind.ClaudeCode, screen))
    }

    @Test
    fun claudeIdleFallbackRequiresPromptLikeTailForRecoveryHelpers() {
        val prose = "some freeform agent prose without a prompt"
        val match = evaluateScreenManifest(AgentKind.ClaudeCode, DetectionInput(screen = prose))
        assertTrue(match.idleFallback)
        assertEquals(ScreenState.Idle, match.state)
        // Recovery/at-prompt helpers still require a prompt-like tail.
        assertFalse(bufferLooksIdle(AgentKind.ClaudeCode, prose))

        val atPrompt = "$prose\n> "
        assertTrue(bufferLooksIdle(AgentKind.ClaudeCode, atPrompt))
    }

    @Test
    fun claudeOscTitleBrailleMarksWorking() {
        val braille = "\u2801 working on it"
        val match = evaluateScreenManifest(
            AgentKind.ClaudeCode,
            DetectionInput(screen = "stale body\n> ", oscTitle = braille),
        )
        assertEquals(ScreenState.Working, match.state)
        assertTrue(match.visibleWorking)
    }

    @Test
    fun codexOscTitleActionRequiredMarksBlocked() {
        val match = evaluateScreenManifest(
            AgentKind.Codex,
            DetectionInput(screen = "› ", oscTitle = "Action Required — allow network"),
        )
        assertEquals(ScreenState.Blocked, match.state)
        assertTrue(match.visibleBlocker)
    }

    @Test
    fun cursorBareYnIsNotBlocked() {
        assertFalse(bufferLooksBlocked(AgentKind.Cursor, "Pick a color (y/n)\n> "))
    }

    @Test
    fun cursorApprovalPromptIsBlocked() {
        val screen = """
            Waiting for approval
            Run this command?
            Run (once) (y)
            Skip (esc or n)
        """.trimIndent()
        assertTrue(bufferLooksBlocked(AgentKind.Cursor, screen))
    }

    @Test
    fun regionBottomNonEmptyLinesTakesLastNonBlank() {
        val content = "a\n\nb\n\nc\n"
        assertEquals("b\n\nc\n", bottomNonEmptyLines(content, 2))
    }

    @Test
    fun extractOscTitleAndProgressFromAnsi() {
        val raw = "\u001B]2;\u2801 Claude\u0007hello\u001B]9;4;0\u0007"
        assertEquals("\u2801 Claude", app.andy.terminal.extractLatestOscTitle(raw))
        assertEquals("4;0", app.andy.terminal.extractLatestOscProgress(raw))
    }

    @Test
    fun perambulatingBufferStaysWorking() {
        val scrape = ScrapeStatusSource(AgentKind.ClaudeCode)
        scrape.onBuffer("✨ Perambulating... (33s · ↓ 547 tokens · thinking more)\n> ")
        scrape.tick()
        assertEquals(AgentStatus.Working, scrape.badgeHint())
        assertFalse(scrape.isQuiescentAtPrompt())
    }

    @Test
    fun antigravityVisibleIdlePromptIsDone() {
        val scrape = ScrapeStatusSource(AgentKind.Antigravity)
        scrape.onBuffer("Antigravity agent ready\n> ")
        scrape.tick()
        assertEquals(AgentStatus.Done, scrape.badgeHint())
        assertTrue(scrape.isQuiescentAtPrompt())
    }

    @Test
    fun ansiCursorBlinkDoesNotChangeIdleHint() {
        val scrape = ScrapeStatusSource(AgentKind.Antigravity)
        scrape.onBuffer("Antigravity ready\n> \u001b[?25h")
        scrape.tick()
        assertEquals(AgentStatus.Done, scrape.badgeHint())
        scrape.onBuffer("Antigravity ready\n> \u001b[?25l")
        assertTrue(scrape.isQuiescentAtPrompt())
    }

    @Test
    fun readLatestHookStatusUsesLastLineOnly() {
        val dir = File.createTempFile("andy-hook", null).also { it.delete(); it.mkdirs() }
        try {
            File(dir, "status.json").writeText(
                """
                {"status":"blocked","at":1}
                {"status":"working","at":2}
                """.trimIndent(),
            )
            assertEquals(AgentStatus.Working, readLatestHookStatus(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readLatestHookStatusIgnoresNotificationBlockedAfterDone() {
        val dir = File.createTempFile("andy-hook", null).also { it.delete(); it.mkdirs() }
        try {
            File(dir, "status.json").writeText(
                """
                {"status":"done","at":1}
                {"status":"blocked","at":2}
                """.trimIndent() + "\n",
            )
            assertEquals(AgentStatus.Done, readLatestHookStatus(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun staleHookDoneDoesNotOverridePerambulatingScrape() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val artifactDir = File.createTempFile("andy-status", null).also { it.delete(); it.mkdirs() }
            File(artifactDir, "status.json").writeText("""{"status":"done","at":1}""")
            val session = FakeTerminalSession()
            val tracker = AgentStatusTracker(
                scope = scope,
                taskId = "task-status",
                agent = AgentKind.ClaudeCode,
                artifactDir = artifactDir,
                session = session,
                onSnapshot = {},
            )
            tracker.start()
            session.emitBuffer("✨ Perambulating... (33s · ↓ 547 tokens · thinking more)\n> ")
            kotlinx.coroutines.delay(600)
            assertEquals(AgentStatus.Working, tracker.status.value.status)
            tracker.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun claudeVisibleIdlePromptHintsDone() {
        val scrape = ScrapeStatusSource(AgentKind.ClaudeCode)
        scrape.onBuffer("Here is the answer.\n✻ Cooked for 8s\n> ")
        scrape.tick()
        assertEquals(AgentStatus.Done, scrape.badgeHint())
    }

    @Test
    fun cursorBodyTextWithoutWorkingRuleIsIdleFallbackDone() {
        // Herdr: no churn→Working. Streaming prose with no spinner/stop hint → idle fallback hint.
        val scrape = ScrapeStatusSource(AgentKind.Cursor)
        scrape.onBuffer("Thinking about the change…\nreading AgentStatusTracker.kt\n")
        scrape.tick()
        assertEquals(AgentStatus.Done, scrape.badgeHint())
        assertFalse(scrape.isDoneConfident())
        assertFalse(scrape.indicatesWorking())
    }

    @Test
    fun streamingGenericsAndOldPromptAreNotDoneConfident() {
        val scrape = ScrapeStatusSource(AgentKind.Cursor)
        scrape.onBuffer(
            ">\n" +
                "Thinking about the change…\n" +
                "I'll update the signature to return List<String>\n" +
                "and map Optional<Foo> next.\n",
        )
        scrape.tick()
        assertEquals(AgentStatus.Done, scrape.badgeHint())
        assertFalse(scrape.isDoneConfident(), "code generics / stale prompt must not make Done confident")
    }

    @Test
    fun trailingExactPromptIsDoneConfident() {
        val scrape = ScrapeStatusSource(AgentKind.Cursor)
        scrape.onBuffer("Implemented the fix.\n> ")
        scrape.tick()
        assertEquals(AgentStatus.Done, scrape.badgeHint())
        assertTrue(scrape.isDoneConfident())
        assertTrue(scrape.isQuiescentAtPrompt())
    }

    @Test
    fun cursorStreamingProseKeepsTrackerWorking() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val artifactDir = File.createTempFile("andy-status", null).also { it.delete(); it.mkdirs() }
            val session = FakeTerminalSession()
            val tracker = AgentStatusTracker(
                scope = scope,
                taskId = "task-cursor-stream",
                agent = AgentKind.Cursor,
                artifactDir = artifactDir,
                session = session,
                onSnapshot = {},
            )
            tracker.start()
            session.emitBuffer("Thinking about the change…\nreading AgentStatusTracker.kt\n")
            kotlinx.coroutines.delay(800)
            assertEquals(AgentStatus.Working, tracker.status.value.status)
            assertFalse(tracker.status.value.confident)
            tracker.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cursorIdleFallbackAtPromptIsDone() {
        val scrape = ScrapeStatusSource(AgentKind.Cursor)
        scrape.onBuffer("Implemented the fix.\n> ")
        scrape.tick()
        assertEquals(AgentStatus.Done, scrape.badgeHint())
        assertTrue(scrape.isQuiescentAtPrompt())
        assertFalse(scrape.indicatesWorking())
    }

    @Test
    fun cursorAltScreenChromeDoesNotFlipIdleToWorking() {
        val scrape = ScrapeStatusSource(AgentKind.Cursor)
        val answer = "Want me to extend the noise filter for Cursor block bars?\n"
        scrape.onBuffer(
            answer +
                " ▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄\n" +
                "  Cursor Grok 4.5 High Fast · 21.1%\n",
        )
        scrape.tick()
        assertEquals(AgentStatus.Done, scrape.badgeHint())

        scrape.onBuffer(
            answer +
                " ▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀\n" +
                "  └─────────────────────────┴───────────────────────────────────┘\n" +
                "  Cursor Grok 4.5 High Fast · 22%\n" +
                "  →\n" +
                """[andy-task0:node*    "Cursor Chat History" 08:36 25-Jul-26]""" + "\n",
        )
        scrape.tick()
        assertEquals(AgentStatus.Done, scrape.badgeHint())
        assertTrue(scrape.isQuiescentAtPrompt())
        assertFalse(scrape.indicatesWorking())
    }

    /**
     * The tracker reads the screen only from [TerminalSession.bufferSnapshots]; it never
     * polls [TerminalSession.bufferSnapshot] (that fetch is a tmux fork on the tmux
     * backends). So a single push of an idle screen has to reach Done on its own — the
     * poll loop contributes `tick()`, which is what walks pending-idle to confirmation.
     */
    @Test
    fun pollLoopConfirmsPendingIdleFromASinglePushedBuffer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val artifactDir = File.createTempFile("andy-status", null).also { it.delete(); it.mkdirs() }
            val session = FakeTerminalSession()
            val tracker = AgentStatusTracker(
                scope = scope,
                taskId = "task-poll-snapshot",
                agent = AgentKind.Cursor,
                artifactDir = artifactDir,
                session = session,
                onSnapshot = {},
            )
            tracker.start()
            session.emitBuffer(
                "Reading files…\n" +
                    "            ctrl+c to stop\n" +
                    " ▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄\n" +
                    "  Cursor Grok 4.5 High Fast · 22%\n",
            )
            kotlinx.coroutines.delay(800)
            assertEquals(AgentStatus.Working, tracker.status.value.status)

            session.emitBuffer(
                "Done with that.\n" +
                    "  → Add a follow-up\n" +
                    "  Cursor Grok 4.5 High Fast · 23%\n",
            )
            kotlinx.coroutines.delay(1_200)
            assertEquals(AgentStatus.Done, tracker.status.value.status)
            tracker.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cursorCtrlCToStopMarksWorking() {
        val scrape = ScrapeStatusSource(AgentKind.Cursor)
        scrape.onBuffer(
            "Reading files…\n" +
                "            ctrl+c to stop\n" +
                " ▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄\n" +
                "  Cursor Grok 4.5 High Fast · 22%\n",
        )
        scrape.tick()
        assertEquals(AgentStatus.Working, scrape.badgeHint())
        assertFalse(scrape.isQuiescentAtPrompt())
        assertTrue(scrape.showsWorkingIndicator())
    }

    @Test
    fun pendingIdleHoldsWorkingToPlainIdleUntilConfirmed() {
        val pending = PendingIdleConfirmation()
        val working = DetectionPublishState(AgentStatus.Working, visibleWorking = true)
        val plainIdle = DetectionPublishState(AgentStatus.Done) // idle fallback, no visibleIdle
        val t0 = 1_000L

        assertTrue(pending.shouldHoldWorkingToIdle(working, plainIdle, now = t0))
        assertTrue(pending.active)
        repeat(PENDING_IDLE_CONFIRMATIONS - 1) { i ->
            assertTrue(
                pending.shouldHoldWorkingToIdle(working, plainIdle, now = t0 + 100L * (i + 1)),
                "confirmation ${i + 1} should still hold",
            )
        }
        // Nth confirmation releases the hold.
        assertFalse(
            pending.shouldHoldWorkingToIdle(
                working,
                plainIdle,
                now = t0 + 100L * PENDING_IDLE_CONFIRMATIONS,
            ),
        )
        assertFalse(pending.active)
    }

    @Test
    fun visibleIdleBypassesPendingIdleHold() {
        val pending = PendingIdleConfirmation()
        val working = DetectionPublishState(AgentStatus.Working, visibleWorking = true)
        val visibleIdle = DetectionPublishState(AgentStatus.Done, visibleIdle = true)
        assertFalse(pending.shouldHoldWorkingToIdle(working, visibleIdle, now = 1_000L))
        assertFalse(pending.active)
    }

    @Test
    fun newWorkingIndicatorAfterDoneFlipsCursorBackToWorking() {
        val scrape = ScrapeStatusSource(AgentKind.Cursor)
        scrape.onBuffer("Done with that.\n> ")
        scrape.tick()
        assertEquals(AgentStatus.Done, scrape.badgeHint())
        scrape.onBuffer(
            "Done with that.\n" +
                "Reading follow-up files…\n" +
                "            ctrl+c to stop\n" +
                " ▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄\n",
        )
        assertEquals(AgentStatus.Working, scrape.badgeHint())
        assertTrue(scrape.indicatesWorking())
        assertTrue(scrape.showsWorkingIndicator())
    }

    @Test
    fun cursorAltScreenRedrawAtIdleDoesNotFlipWorking() {
        val scrape = ScrapeStatusSource(AgentKind.Cursor)
        val answer = "READ-ONLY was a badge only — the history viewer was still a live Swing terminal.\n"
        val idleFooter =
            "  → Add a follow-up\n" +
                " ▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄\n" +
                "  Cursor Grok 4.5 High Fast · 27.5% · 5 files edited\n"
        scrape.onBuffer(answer + idleFooter)
        scrape.tick()
        assertEquals(AgentStatus.Done, scrape.badgeHint())

        scrape.onBuffer(
            "    ▎              assertFalse(widget.isFocusable)\n" +
                "    ✔ Skip live attach for inactive (READ-ONLY) chats\n" +
                answer +
                " ▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀\n" +
                "  → Add a follow-up\n" +
                "  Cursor Grok 4.5 High Fast · 27.7% · 5 files edited\n" +
                """[andy-task0:node*    "Read Only Terminal" 08:51 25-Jul-26]""" + "\n",
        )
        scrape.tick()
        assertEquals(AgentStatus.Done, scrape.badgeHint())
        assertTrue(scrape.isQuiescentAtPrompt())
        assertFalse(scrape.indicatesWorking())
        assertFalse(scrape.showsWorkingIndicator())
    }

    @Test
    fun cursorIdleChromeRedrawDoesNotFlipTracker() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val artifactDir = File.createTempFile("andy-status", null).also { it.delete(); it.mkdirs() }
            val session = FakeTerminalSession()
            val statuses = mutableListOf<AgentStatus>()
            val tracker = AgentStatusTracker(
                scope = scope,
                taskId = "task-cursor-idle-flip",
                agent = AgentKind.Cursor,
                artifactDir = artifactDir,
                session = session,
                onSnapshot = { statuses += it.status },
            )
            tracker.start()
            val answer = "Restart/reload the desktop app to pick this up.\n"
            session.emitBuffer(
                answer +
                    "  → Add a follow-up\n" +
                    " ▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄\n" +
                    "  Cursor Grok 4.5 High Fast · 21.1%\n",
            )
            kotlinx.coroutines.delay(800)
            assertEquals(AgentStatus.Done, tracker.status.value.status)

            repeat(6) { i ->
                session.emitBuffer(
                    "    ▎              scope.cancel()\n" +
                        "    ✔ Block key/paste input on scrollback replay\n" +
                        answer +
                        "  → Add a follow-up\n" +
                        " ▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀\n" +
                        "  Cursor Grok 4.5 High Fast · ${22 + i}%\n" +
                        """[andy-task0:node*    "Read Only Terminal" 08:5$i 25-Jul-26]""" + "\n",
                )
                kotlinx.coroutines.delay(400)
            }
            assertEquals(AgentStatus.Done, tracker.status.value.status)
            assertTrue(
                statuses.none { it == AgentStatus.Working },
                "idle chrome must never enter Working without a working rule; saw $statuses",
            )
            tracker.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cursorIdleFallbackBecomesConfidentWithoutStatusFile() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val artifactDir = File.createTempFile("andy-status", null).also { it.delete(); it.mkdirs() }
            val session = FakeTerminalSession()
            val tracker = AgentStatusTracker(
                scope = scope,
                taskId = "task-cursor-idle",
                agent = AgentKind.Cursor,
                artifactDir = artifactDir,
                session = session,
                onSnapshot = {},
            )
            tracker.start()
            session.emitBuffer("Implemented the fix.\n> ")
            kotlinx.coroutines.delay(800)
            val snap = tracker.status.value
            assertEquals(AgentStatus.Done, snap.status)
            assertTrue(snap.confident, "idle-fallback Done should notify without status.json")
            assertFalse(File(artifactDir, "status.json").exists())
            tracker.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun workingToPlainIdleUsesPendingConfirmation() {
        val scrape = ScrapeStatusSource(AgentKind.Cursor)
        scrape.onBuffer("Reading…\n            ctrl+c to stop\n")
        assertEquals(AgentStatus.Working, scrape.badgeHint())

        // Drop the stop hint → plain idle fallback. First observation holds Working.
        scrape.onBuffer("Reading…\n→ Add a follow-up\n")
        assertEquals(AgentStatus.Working, scrape.badgeHint())
        assertTrue(scrape.hasPendingIdle())

        // Confirm until released.
        repeat(PENDING_IDLE_CONFIRMATIONS) { scrape.tick() }
        assertEquals(AgentStatus.Done, scrape.badgeHint())
        assertFalse(scrape.hasPendingIdle())
    }

    @Test
    fun markPhaseFinishedEmitsConfidentDone() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var last: AgentStatusSnapshot? = null
        val tracker = AgentStatusTracker(
            scope = scope,
            taskId = "task-status",
            agent = AgentKind.Codex,
            artifactDir = File.createTempFile("andy-status", null).also { it.delete(); it.mkdirs() },
            session = FakeTerminalSession(),
            onSnapshot = { last = it },
        )
        tracker.start()
        tracker.markPhaseFinished()
        kotlinx.coroutines.delay(200)
        assertEquals(AgentStatus.Done, last?.status)
        assertTrue(last?.confident == true)
        scope.cancel()
    }

    @Test
    fun reattachSeedKeepsDoneInsteadOfDefaultWorking() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val artifactDir = File.createTempFile("andy-status", null).also { it.delete(); it.mkdirs() }
            File(artifactDir, "status.json").writeText("""{"status":"done","at":1}""")
            val session = FakeTerminalSession()
            val emitted = mutableListOf<AgentStatus>()
            val tracker = AgentStatusTracker(
                scope = scope,
                taskId = "task-reattach",
                agent = AgentKind.ClaudeCode,
                artifactDir = artifactDir,
                session = session,
                onSnapshot = { emitted += it.status },
                initialSnapshot = AgentStatusSnapshot(AgentStatus.Done, confident = true),
            )
            assertEquals(AgentStatus.Done, tracker.status.value.status)
            assertTrue(tracker.status.value.confident)
            tracker.start()
            // Collecting the StateFlow (as attachExisting's waitJob does) must not
            // observe Working before the first real publish.
            kotlinx.coroutines.delay(500)
            session.emitBuffer("Here is the answer.\n✻ Cooked for 2s\n> ")
            kotlinx.coroutines.delay(600)
            assertEquals(AgentStatus.Done, tracker.status.value.status)
            assertFalse(
                emitted.any { it == AgentStatus.Working },
                "reattach must not publish Working for an already-done chat; saw $emitted",
            )
            tracker.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun remountSoftIdleWithoutHooksKeepsSeededDone() = runBlocking {
        // Switching chat windows rebuilds the tracker with a Done seed. If the Done latch
        // is cleared before the first confident idle scrape, soft idle must not invent
        // Working the way a fresh boot would.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val artifactDir = File.createTempFile("andy-status-remount", null).also {
                it.delete()
                it.mkdirs()
            }
            val session = FakeTerminalSession()
            val emitted = mutableListOf<AgentStatus>()
            val tracker = AgentStatusTracker(
                scope = scope,
                taskId = "task-remount",
                agent = AgentKind.ClaudeCode,
                artifactDir = artifactDir,
                session = session,
                onSnapshot = { emitted += it.status },
                initialSnapshot = AgentStatusSnapshot(AgentStatus.Done, confident = true),
            )
            tracker.start()
            tracker.clearLatch()
            session.emitBuffer("partial redraw while attaching…")
            kotlinx.coroutines.delay(700)
            assertEquals(AgentStatus.Done, tracker.status.value.status)
            assertFalse(
                emitted.any { it == AgentStatus.Working },
                "chat-window remount must keep Done through soft idle; saw $emitted",
            )
            tracker.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun clearLatchAloneDoesNotFlipHookDoneWithoutWorkingCue() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val artifactDir = File.createTempFile("andy-status", null).also { it.delete(); it.mkdirs() }
            File(artifactDir, "status.json").writeText("""{"status":"done","at":1}""")
            val session = FakeTerminalSession()
            val tracker = AgentStatusTracker(
                scope = scope,
                taskId = "task-latch",
                agent = AgentKind.ClaudeCode,
                artifactDir = artifactDir,
                session = session,
                onSnapshot = {},
                initialSnapshot = AgentStatusSnapshot(AgentStatus.Done, confident = true),
            )
            tracker.start()
            kotlinx.coroutines.delay(500)
            session.emitBuffer("Here is the answer.\n✻ Cooked for 2s\n> ")
            kotlinx.coroutines.delay(400)
            tracker.clearLatch()
            kotlinx.coroutines.delay(600)
            assertEquals(
                AgentStatus.Done,
                tracker.status.value.status,
                "opening a chat (formerly clearLatch via markRead) must keep Done at an idle prompt",
            )
            tracker.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun strayByteDoesNotFlipDoneToWorking() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val artifactDir = File.createTempFile("andy-status", null).also { it.delete(); it.mkdirs() }
            File(artifactDir, "status.json").writeText("""{"status":"done","at":1}""")
            val session = FakeTerminalSession()
            val tracker = AgentStatusTracker(
                scope = scope,
                taskId = "task-status",
                agent = AgentKind.ClaudeCode,
                artifactDir = artifactDir,
                session = session,
                onSnapshot = {},
            )
            tracker.start()
            kotlinx.coroutines.delay(500)
            session.emitBuffer("x")
            kotlinx.coroutines.delay(600)
            assertEquals(AgentStatus.Done, tracker.status.value.status)
            tracker.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun installClaudeStatusHooksWritesWorkingDoneBlockedMapping() {
        val home = File.createTempFile("andy-home", null).also { it.delete(); it.mkdirs() }
        val previousHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", home.absolutePath)
            val cwd = File(home, "project").also { it.mkdirs() }
            val artifacts = File(cwd, ".andy/task-hooks").also { it.mkdirs() }
            installClaudeStatusHooks(cwd, artifacts)

            val settings = File(cwd, ".claude/settings.json")
            assertTrue(settings.isFile)
            val text = settings.readText()
            val hooks = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject["hooks"]!!.jsonObject
            assertTrue(hooks.containsKey("UserPromptSubmit"))
            assertTrue(hooks.containsKey("Stop"))
            assertTrue(hooks.containsKey("PermissionRequest"))
            assertTrue(hooks.containsKey("Notification"))
            assertFalse(hooks.containsKey("SubagentStop"), "SubagentStop must not mark parent Done")
            assertTrue("working" in text)
            assertTrue("idle_prompt|agent_completed" in text)
            assertTrue("permission_prompt|agent_needs_input|elicitation_dialog" in text)
            assertTrue(
                "\$HOME/.andy/bin/andy-status-hook.sh" in text,
                "hooks must use stable \$HOME helper path",
            )
            assertTrue(AndyStatusHookInstaller.scriptFile(home).canExecute())
            assertEquals("task-hooks", File(cwd, ".andy/active-task").readText().trim())
            assertTrue(!File(home, ".claude/settings.json").exists())
        } finally {
            System.setProperty("user.home", previousHome)
            home.deleteRecursively()
        }
    }

    @Test
    fun installClaudeStatusHooksMergesWithoutClobberingUserHooks() {
        val home = File.createTempFile("andy-home", null).also { it.delete(); it.mkdirs() }
        val previousHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", home.absolutePath)
            val cwd = File(home, "project").also { it.mkdirs() }
            val settingsDir = File(cwd, ".claude").also { it.mkdirs() }
            File(settingsDir, "settings.json").writeText(
                """
                {
                  "hooks": {
                    "PreToolUse": [
                      {
                        "hooks": [
                          { "type": "command", "command": "echo user-hook" }
                        ]
                      }
                    ],
                    "SubagentStop": [
                      {
                        "hooks": [
                          { "type": "command", "command": "'/tmp/andy-status-hook.sh' done" }
                        ]
                      }
                    ]
                  }
                }
                """.trimIndent(),
            )
            val artifacts = File(cwd, ".andy/task-hooks").also { it.mkdirs() }
            installClaudeStatusHooks(cwd, artifacts)

            val hooks = kotlinx.serialization.json.Json
                .parseToJsonElement(File(settingsDir, "settings.json").readText())
                .jsonObject["hooks"]!!
                .jsonObject
            assertTrue(hooks.containsKey("PreToolUse"), "user PreToolUse must be preserved")
            assertTrue("echo user-hook" in hooks.toString())
            assertFalse(hooks.containsKey("SubagentStop"), "legacy Andy SubagentStop entry removed")
            assertTrue(hooks.containsKey("Stop"))
        } finally {
            System.setProperty("user.home", previousHome)
            home.deleteRecursively()
        }
    }

    @Test
    fun installCursorStatusHooksWritesBeforeSubmitAndStop() {
        val home = File.createTempFile("andy-home", null).also { it.delete(); it.mkdirs() }
        val previousHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", home.absolutePath)
            val cwd = File(home, "project").also { it.mkdirs() }
            val artifacts = File(cwd, ".andy/task-hooks").also { it.mkdirs() }
            installStatusSignals(AgentKind.Cursor, cwd, artifacts)

            val hooksFile = File(cwd, ".cursor/hooks.json")
            assertTrue(hooksFile.isFile)
            val root = kotlinx.serialization.json.Json.parseToJsonElement(hooksFile.readText()).jsonObject
            val hooks = root["hooks"]!!.jsonObject
            assertTrue(hooks.containsKey("beforeSubmitPrompt"))
            assertTrue(hooks.containsKey("stop"))
            val text = hooksFile.readText()
            assertTrue("working" in text)
            assertTrue("done" in text)
            assertTrue("\$HOME/.andy/bin/andy-status-hook.sh" in text)
            assertEquals("task-hooks", File(cwd, ".andy/active-task").readText().trim())
        } finally {
            System.setProperty("user.home", previousHome)
            home.deleteRecursively()
        }
    }

    @Test
    fun installCodexStatusHooksWritesWorkingDoneBlocked() {
        val home = File.createTempFile("andy-home", null).also { it.delete(); it.mkdirs() }
        val previousHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", home.absolutePath)
            val cwd = File(home, "project").also { it.mkdirs() }
            val artifacts = File(cwd, ".andy/task-hooks").also { it.mkdirs() }
            installStatusSignals(AgentKind.Codex, cwd, artifacts)

            val hooksFile = File(cwd, ".codex/hooks.json")
            assertTrue(hooksFile.isFile)
            val text = hooksFile.readText()
            val hooks = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject["hooks"]!!.jsonObject
            assertTrue(hooks.containsKey("UserPromptSubmit"))
            assertTrue(hooks.containsKey("Stop"))
            assertTrue(hooks.containsKey("PermissionRequest"))
            assertTrue(
                Regex("""andy-status-hook\.sh\\?" (working|done|blocked) empty""")
                    .containsMatchIn(text) ||
                    Regex("""andy-status-hook\.sh" (working|done|blocked) empty""")
                        .containsMatchIn(text),
                "Codex hooks must use empty respond mode for JSON stdout",
            )
            assertTrue("\$HOME/.andy/bin/andy-status-hook.sh" in text)
            assertTrue(" empty" in text)
        } finally {
            System.setProperty("user.home", previousHome)
            home.deleteRecursively()
        }
    }

    @Test
    fun installAntigravityStatusHooksWritesNamedAndyStatusHook() {
        val home = File.createTempFile("andy-home", null).also { it.delete(); it.mkdirs() }
        val previousHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", home.absolutePath)
            val cwd = File(home, "project").also { it.mkdirs() }
            val artifacts = File(cwd, ".andy/task-hooks").also { it.mkdirs() }
            installStatusSignals(AgentKind.Antigravity, cwd, artifacts)

            val hooksFile = File(cwd, ".agents/hooks.json")
            assertTrue(hooksFile.isFile)
            val root = kotlinx.serialization.json.Json.parseToJsonElement(hooksFile.readText()).jsonObject
            assertTrue(root.containsKey("andy-status"))
            val andy = root["andy-status"]!!.jsonObject
            assertTrue(andy.containsKey("PreInvocation"))
            assertTrue(andy.containsKey("Stop"))
            assertTrue(andy.containsKey("PreToolUse"))
            assertTrue("ask_question|ask_permission" in hooksFile.readText())
        } finally {
            System.setProperty("user.home", previousHome)
            home.deleteRecursively()
        }
    }

    @Test
    fun statusHookScriptWritesStatusAndOptionalRespondPayload() {
        val home = File.createTempFile("andy-home", null).also { it.delete(); it.mkdirs() }
        val previousHome = System.getProperty("user.home")
        val project = File(home, "project").also { it.mkdirs() }
        val artifacts = File(project, ".andy/task-hooks").also { it.mkdirs() }
        try {
            System.setProperty("user.home", home.absolutePath)
            installGenericStatusHookScript(artifacts)
            val script = AndyStatusHookInstaller.scriptFile(home)
            assertTrue(script.canExecute())
            assertEquals("task-hooks", File(project, ".andy/active-task").readText().trim())

            val working = ProcessBuilder("sh", script.absolutePath, "working", "empty")
                .directory(project)
                .redirectErrorStream(true)
                .start()
            val workingOut = working.inputStream.bufferedReader().readText().trim()
            assertEquals(0, working.waitFor())
            assertEquals("{}", workingOut)
            assertTrue(
                File(artifacts, "status.json").readText().contains("\"status\":\"working\""),
            )

            val done = ProcessBuilder("sh", script.absolutePath, "done", "stop")
                .directory(project)
                .redirectErrorStream(true)
                .start()
            val doneOut = done.inputStream.bufferedReader().readText().trim()
            assertEquals(0, done.waitFor())
            assertEquals("""{"decision":"stop"}""", doneOut)
            assertTrue(File(artifacts, "status.json").readText().contains("\"status\":\"done\""))
        } finally {
            System.setProperty("user.home", previousHome)
            home.deleteRecursively()
        }
    }

    @Test
    fun statusHookScriptContentMatchesRepoScript() {
        val repoScript = File("scripts/andy-status-hook.sh")
        if (!repoScript.isFile) return
        assertEquals(
            repoScript.readText().replace("\r\n", "\n"),
            AndyStatusHookInstaller.scriptContent,
            "Keep AndyStatusHookInstaller.scriptContent in sync with scripts/andy-status-hook.sh",
        )
    }

    @Test
    fun statusHookScriptNoOpsWithoutActiveTask() {
        val home = File.createTempFile("andy-home", null).also { it.delete(); it.mkdirs() }
        val previousHome = System.getProperty("user.home")
        val project = File(home, "project").also { it.mkdirs() }
        try {
            System.setProperty("user.home", home.absolutePath)
            val script = AndyStatusHookInstaller.ensureInstalled(home)
            val proc = ProcessBuilder("sh", script.absolutePath, "working", "empty")
                .directory(project)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            assertEquals(0, proc.waitFor())
            assertEquals("{}", out)
            assertFalse(File(project, ".andy").exists())
        } finally {
            System.setProperty("user.home", previousHome)
            home.deleteRecursively()
        }
    }

    @Test
    fun installCursorStatusHooksSkipsRewriteWhenUnchanged() {
        val home = File.createTempFile("andy-home", null).also { it.delete(); it.mkdirs() }
        val previousHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", home.absolutePath)
            val cwd = File(home, "project").also { it.mkdirs() }
            val artifacts = File(cwd, ".andy/task-a").also { it.mkdirs() }
            installStatusSignals(AgentKind.Cursor, cwd, artifacts)
            val hooksFile = File(cwd, ".cursor/hooks.json")
            val first = hooksFile.readText()
            val firstModified = hooksFile.lastModified()
            Thread.sleep(20)
            val artifactsB = File(cwd, ".andy/task-b").also { it.mkdirs() }
            installStatusSignals(AgentKind.Cursor, cwd, artifactsB)
            assertEquals(first, hooksFile.readText(), "stable hooks must not rewrite per task")
            assertEquals("task-b", File(cwd, ".andy/active-task").readText().trim())
            // File content identical; mtime may or may not change — content is the contract.
            assertTrue(firstModified > 0)
        } finally {
            System.setProperty("user.home", previousHome)
            home.deleteRecursively()
        }
    }

    @Test
    fun installStatusSignalsSkipsHomeDirectory() {
        val home = File.createTempFile("andy-home", null).also { it.delete(); it.mkdirs() }
        val previousHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", home.absolutePath)
            val artifacts = File(home, ".andy/task-hooks").also { it.mkdirs() }
            installStatusSignals(AgentKind.Cursor, home, artifacts)
            assertFalse(File(home, ".cursor/hooks.json").exists())
            installStatusSignals(AgentKind.Codex, home, artifacts)
            assertFalse(File(home, ".codex/hooks.json").exists())
            installStatusSignals(AgentKind.Antigravity, home, artifacts)
            assertFalse(File(home, ".agents/hooks.json").exists())
        } finally {
            System.setProperty("user.home", previousHome)
            home.deleteRecursively()
        }
    }
}

private class FakeTerminalSession : TerminalSession {
    override val sessionId: String = "fake"
    override val isAlive: Boolean = true
    override val exitCode: StateFlow<Int?> = MutableStateFlow(null)
    override val pid: Long? = null

    private val snapshots = MutableSharedFlow<String>(extraBufferCapacity = 8, replay = 1)
    override val bufferSnapshots: SharedFlow<String> = snapshots
    override val windowTitle: StateFlow<String> = MutableStateFlow("")
    override val oscProgress: StateFlow<String> = MutableStateFlow("")

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
}
