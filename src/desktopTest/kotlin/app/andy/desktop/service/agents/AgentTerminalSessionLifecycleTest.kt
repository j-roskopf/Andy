package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.terminal.TmuxAndy
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Interactive-vs-read-only ownership: a chat is typeable for as long as *this* process
 * owns its live session, and reopens read-only once that ends.
 */
class AgentTerminalSessionLifecycleTest {
    private val isWindows = System.getProperty("os.name").contains("windows", ignoreCase = true)

    @BeforeTest
    fun isolateFromLiveAndyTmux() {
        TmuxAndy.useIsolatedServerForTests()
    }

    @Test
    fun sessionStaysInteractiveUntilStoppedThenReplaysReadOnlyAfterRestart() = runBlocking {
        if (isWindows) return@runBlocking // DirectPty scrollback lifecycle is covered on Unix CI
        val dir = tempDir()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scrollbackFile = { id: String -> File(dir, "$id/scrollback.ansi") }
        try {
            val manager = AgentTerminalManager(
                scope = scope,
                scrollbackFile = scrollbackFile,
                mode = AgentTerminalMode.DirectPty,
            )
            val taskId = "lifecycle-task"
            manager.start(task(taskId, dir), longRunningArgv(), emptyMap())

            assertTrue(manager.isInteractive(taskId), "a freshly started chat is interactive")
            assertEquals(setOf(taskId), manager.interactiveTaskIds.value)
            withTimeout(15_000) {
                while (!manager.hasScrollback(taskId)) delay(50)
            }

            // Unmounting the Compose surface drops the viewer, not the session.
            manager.releaseViewerOnly(taskId)
            assertTrue(manager.isInteractive(taskId), "releasing the viewer must not end interactivity")

            manager.stop(taskId)
            assertFalse(manager.isInteractive(taskId), "a stopped chat is read-only")
            assertEquals(emptySet(), manager.interactiveTaskIds.value)

            // A new manager stands in for the next app launch: no session is owned, but the
            // chat still has history to replay in the same terminal UI.
            val afterRestart = AgentTerminalManager(
                scope = scope,
                scrollbackFile = scrollbackFile,
                mode = AgentTerminalMode.DirectPty,
            )
            assertFalse(afterRestart.isInteractive(taskId), "sessions do not survive an app restart")
            assertEquals(emptySet(), afterRestart.interactiveTaskIds.value)
            assertTrue(afterRestart.hasScrollback(taskId), "history should be on disk for replay")
            val replay = assertNotNull(afterRestart.scrollbackReplayText(taskId))
            assertTrue(replay.contains(MARKER), "replay should hold the chat output, got=${replay.take(200)}")
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun reattachAfterReleasingViewerDoesNotKillTmuxSession() = runBlocking {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return@runBlocking
        }
        val dir = tempDir()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scrollbackFile = { id: String -> File(dir, "$id/scrollback.ansi") }
        val taskId = "reattach-task"
        try {
            val manager = AgentTerminalManager(
                scope = scope,
                scrollbackFile = scrollbackFile,
                mode = AgentTerminalMode.TmuxWithAttach,
            )
            manager.start(task(taskId, dir), longRunningArgv(), emptyMap())

            assertTrue(manager.isInteractive(taskId))
            assertTrue(TmuxAndy.hasSession(taskId))

            manager.releaseViewerOnly(taskId)
            assertFalse(manager.isViewerAlive(taskId))
            assertTrue(TmuxAndy.hasSession(taskId), "release viewer must not kill tmux")

            val reattached = manager.attachExisting(taskId, cwd = dir.absolutePath)
            assertNotNull(reattached)
            assertTrue(TmuxAndy.hasSession(taskId), "reattach must not kill tmux")
            assertTrue(manager.isInteractive(taskId))
            assertNotNull(manager.terminalView(taskId))

            // Simulate switching chats several times: release viewer, then reopen.
            repeat(4) {
                manager.releaseViewerOnly(taskId)
                assertFalse(manager.isViewerAlive(taskId), "viewer should drop on switch $it")
                assertTrue(manager.isInteractive(taskId), "session stays interactive on switch $it")
                val again = manager.attachExisting(taskId, cwd = dir.absolutePath)
                assertNotNull(again, "reattach should succeed on switch $it")
                assertSame(reattached, again, "handle should be reused on switch $it")
                assertNotNull(manager.terminalView(taskId), "widget should mount on switch $it")
            }
        } finally {
            runCatching { TmuxAndy.killSession(taskId) }
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    /**
     * Regression for orphaned `andy-task-*` tmux sessions: a session reattached after
     * losing its handle (app/daemon restart, or any other drop from [handles]) is built
     * with `killTmuxOnClose = false` so background release doesn't tear down a chat the
     * user isn't actively stopping. But [AgentTerminalManager.stop] always means
     * "terminate" - if it trusted that same flag, stopping (or deleting, which routes
     * through stop) a chat that had been reattached would silently leave its tmux
     * session and CLI process running forever with no handle referencing it.
     */
    @Test
    fun stoppingAReattachedSessionStillKillsTmux() = runBlocking {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return@runBlocking
        }
        val dir = tempDir()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val taskId = "reattach-then-stop-task"
        try {
            val manager = AgentTerminalManager(
                scope = scope,
                scrollbackFile = { id -> File(dir, "$id/scrollback.ansi") },
                mode = AgentTerminalMode.TmuxWithAttach,
            )
            manager.start(task(taskId, dir), longRunningArgv(), emptyMap())
            assertTrue(TmuxAndy.hasSession(taskId))

            // Simulate a dropped handle (e.g. app/daemon restart) followed by a GUI
            // reattach: this is the only path that constructs a fresh TmuxAttachBackend
            // pinned to killTmuxOnClose = false.
            manager.stop(taskId)
            assertFalse(TmuxAndy.hasSession(taskId), "sanity: stop on a live handle kills tmux")

            TmuxAndy.newSession(taskId = taskId, cwd = dir.absolutePath, argv = longRunningArgv())
            assertTrue(TmuxAndy.hasSession(taskId))
            val reattached = manager.attachExisting(taskId, cwd = dir.absolutePath)
            assertNotNull(reattached, "reattach should succeed")
            assertTrue(TmuxAndy.hasSession(taskId))

            manager.stop(taskId)
            assertFalse(
                TmuxAndy.hasSession(taskId),
                "stopping a reattached chat must kill its tmux session, not just drop the handle",
            )
        } finally {
            runCatching { TmuxAndy.killSession(taskId) }
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    /**
     * Product-path regression: AgentTerminalManager → TerminalSessions → tmux must
     * recover a deleted task cwd into scratch instead of a getcwd / uv_cwd broken pane.
     */
    @Test
    fun startWithDeletedCwdRecoversToScratchAndStaysHealthy() = runBlocking {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return@runBlocking
        }
        val gone = tempDir()
        val deletedPath = gone.absolutePath
        assertTrue(gone.deleteRecursively())
        assertFalse(File(deletedPath).exists())

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scrollRoot = tempDir()
        val taskId = "deleted-cwd-task"
        try {
            val manager = AgentTerminalManager(
                scope = scope,
                scrollbackFile = { id -> File(scrollRoot, "$id/scrollback.ansi") },
                mode = AgentTerminalMode.TmuxHeadless,
            )
            manager.start(
                task(taskId, File(deletedPath)),
                listOf("/bin/sh", "-c", "pwd; printf 'andy-deleted-cwd-ok\\n'; sleep 60"),
                emptyMap(),
            )
            assertTrue(manager.isInteractive(taskId))
            assertTrue(TmuxAndy.hasSession(taskId))
            withTimeout(5_000) {
                while (TmuxAndy.capturePane(taskId, historyLines = 50).let { pane ->
                        !pane.contains("andy-deleted-cwd-ok")
                    }
                ) {
                    kotlinx.coroutines.delay(50)
                }
            }
            assertFalse(
                TmuxAndy.sessionLooksBroken(taskId),
                "manager.start with deleted cwd must not leave a broken pane",
            )
            val pane = TmuxAndy.capturePane(taskId, historyLines = 50)
            assertFalse(pane.contains("shell-init: error retrieving current directory"), pane.take(300))
            assertFalse(pane.contains("uv_cwd"), pane.take(300))
            assertTrue(
                pane.contains(".andy-tasks") || pane.contains("andy-scratch"),
                "expected scratch cwd in pane=${pane.take(300)}",
            )
        } finally {
            runCatching { TmuxAndy.killSession(taskId) }
            scope.cancel()
            scrollRoot.deleteRecursively()
        }
    }

    @Test
    fun attachExistingRefusesBrokenSession() = runBlocking {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return@runBlocking
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scrollRoot = tempDir()
        val taskId = "broken-attach-task"
        try {
            // Create a session whose visible pane looks like the getcwd failure mode.
            TmuxAndy.newSession(
                taskId = taskId,
                cwd = System.getProperty("user.dir"),
                argv = listOf(
                    "/bin/sh",
                    "-c",
                    "printf 'shell-init: error retrieving current directory: getcwd: cannot access parent directories\\n'; sleep 60",
                ),
            )
            withTimeout(5_000) {
                while (!TmuxAndy.sessionLooksBroken(taskId)) {
                    kotlinx.coroutines.delay(50)
                }
            }
            val manager = AgentTerminalManager(
                scope = scope,
                scrollbackFile = { id -> File(scrollRoot, "$id/scrollback.ansi") },
                mode = AgentTerminalMode.TmuxWithAttach,
            )
            val attached = manager.attachExisting(taskId, cwd = System.getProperty("user.dir"))
            assertEquals(null, attached, "broken panes must not remount")
            assertFalse(TmuxAndy.hasSession(taskId), "broken session should be killed on attach")
        } finally {
            runCatching { TmuxAndy.killSession(taskId) }
            scope.cancel()
            scrollRoot.deleteRecursively()
        }
    }

    @Test
    fun exitingTheProcessEndsInteractivity() = runBlocking {
        val dir = tempDir()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = AgentTerminalManager(
                scope = scope,
                scrollbackFile = { id -> File(dir, "$id/scrollback.ansi") },
                mode = AgentTerminalMode.DirectPty,
            )
            val taskId = "exiting-task"
            val argv = if (isWindows) {
                listOf("cmd", "/c", "echo", MARKER)
            } else {
                listOf("/bin/echo", MARKER)
            }
            manager.start(task(taskId, dir), argv, emptyMap())
            withTimeout(15_000) { manager.awaitExit(taskId) }

            assertFalse(
                manager.isInteractive(taskId),
                "a chat whose CLI exited is read-only even though Andy is still running",
            )
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    /**
     * Attaching must be idempotent under concurrency and under repeated chat switches.
     *
     * The Compose surface attaches from an effect that used to be keyed on the sessions
     * revision — which a successful attach bumps — so attaching cancelled and restarted its
     * own coroutine while `attach()` was already spawning a tmux client. Overlapping calls
     * then each built a viewer, only the last of which was reachable through the manager;
     * the rest kept a tmux client, a BossTerm emulator and a render worker alive for the
     * life of the process. tmux's own client list is the check that catches that.
     */
    @Test
    fun overlappingAttachesDoNotStrandViewers() = runBlocking {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return@runBlocking
        }
        val dir = tempDir()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val taskId = "overlap-attach-task"
        try {
            val manager = AgentTerminalManager(
                scope = scope,
                scrollbackFile = { id -> File(dir, "$id/scrollback.ansi") },
                mode = AgentTerminalMode.TmuxWithAttach,
            )
            manager.start(task(taskId, dir), longRunningArgv(), emptyMap())
            assertTrue(TmuxAndy.hasSession(taskId))

            repeat(3) { round ->
                manager.releaseViewerOnly(taskId)
                // Eight callers race for a chat with no viewer; exactly one may win.
                val handles = (1..8).map {
                    async { manager.attachExisting(taskId, cwd = dir.absolutePath) }
                }.awaitAll()
                val attached = handles.filterNotNull()
                assertTrue(attached.isNotEmpty(), "at least one attach must succeed in round $round")
                attached.forEach {
                    assertSame(attached.first(), it, "every caller must get the same handle in round $round")
                }
                delay(150)
                assertEquals(
                    1,
                    tmuxClientCount(taskId),
                    "round $round left more than one tmux client attached to the session",
                )
            }
        } finally {
            runCatching { TmuxAndy.killSession(taskId) }
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    /**
     * `start` attaches a tmux client before registering into `handles`. Without sharing
     * `attachLock` with `attachExisting`, a UI attach that raced through that window
     * spawned a second client; only the last writer stayed reachable, orphaning the other
     * for the life of the process.
     */
    @Test
    fun startRacingAttachExistingDoesNotStrandViewers() = runBlocking {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return@runBlocking
        }
        val dir = tempDir()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val taskId = "start-attach-race-task"
        try {
            val manager = AgentTerminalManager(
                scope = scope,
                scrollbackFile = { id -> File(dir, "$id/scrollback.ansi") },
                mode = AgentTerminalMode.TmuxWithAttach,
            )
            val agentTask = task(taskId, dir)
            val start = async { manager.start(agentTask, longRunningArgv(), emptyMap()) }
            val attaches = (1..4).map {
                async { manager.attachExisting(taskId, cwd = dir.absolutePath) }
            }
            val started = start.await()
            val attached = attaches.awaitAll().filterNotNull()
            assertTrue(attached.isNotEmpty(), "at least one attach must succeed")
            attached.forEach {
                assertSame(started, it, "attach must reuse the start handle, not spawn another")
            }
            assertTrue(TmuxAndy.hasSession(taskId))
            assertEquals(
                1,
                tmuxClientCount(taskId),
                "start racing attachExisting left more than one tmux client",
            )
        } finally {
            runCatching { TmuxAndy.killSession(taskId) }
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    /**
     * Stopping a live chat must wake whoever is waiting on its exit.
     *
     * The run pipeline parks in [AgentTerminalManager.awaitExit] for the whole turn, inside
     * the concurrency permit its run holds in `DesktopAgentRunService`. Anything that leaves
     * that wait unresolved stalls the workflow stage with nothing in the log, so the contract
     * is pinned here rather than left to whichever teardown path happens to report a code.
     */
    @Test
    fun stoppingALiveSessionWakesAnInFlightAwaitExit() = runBlocking {
        val dir = tempDir()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val taskId = "await-exit-stop-task"
        try {
            val manager = AgentTerminalManager(
                scope = scope,
                scrollbackFile = { id -> File(dir, "$id/scrollback.ansi") },
                mode = AgentTerminalMode.DirectPty,
            )
            manager.start(task(taskId, dir), longRunningArgv(), emptyMap())
            assertTrue(manager.isInteractive(taskId), "the session should be live before stopping")

            val waiter = async { manager.awaitExit(taskId) }
            // Let the waiter capture the handle and park on the exit flow — stopping before
            // that would take the "no handle" path and prove nothing.
            delay(250)
            assertTrue(waiter.isActive, "awaitExit should still be waiting on a live session")

            manager.stop(taskId)
            // Returning at all is the regression. The code is the process' real termination
            // status when the stop collected one (143 = SIGTERM on Unix), otherwise
            // [AgentTerminalManager.UNKNOWN_EXIT_CODE] — never a clean success.
            val code = withTimeout(15_000) { waiter.await() }
            assertNotEquals(0, code, "a session killed mid-turn must not report a clean exit")
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    /**
     * The bounded wait is a backstop, not a replacement: a process that exits on its own
     * still reports its real status rather than the unknown-exit fallback.
     */
    @Test
    fun awaitExitReportsTheRealProcessExitCode() = runBlocking {
        if (isWindows) return@runBlocking // /bin/sh exit codes; Unix CI covers this path
        val dir = tempDir()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val taskId = "await-exit-code-task"
        try {
            val manager = AgentTerminalManager(
                scope = scope,
                scrollbackFile = { id -> File(dir, "$id/scrollback.ansi") },
                mode = AgentTerminalMode.DirectPty,
            )
            manager.start(task(taskId, dir), listOf("/bin/sh", "-c", "exit 7"), emptyMap())
            assertEquals(7, withTimeout(15_000) { manager.awaitExit(taskId) })
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun setOnlyForegroundReleasesBackgroundViewerAndExitsCopyMode() = runBlocking {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return@runBlocking
        }
        val dir = tempDir()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val foregroundId = "foreground-chat"
        val backgroundId = "background-chat"
        try {
            val manager = AgentTerminalManager(
                scope = scope,
                scrollbackFile = { id -> File(dir, "$id/scrollback.ansi") },
                mode = AgentTerminalMode.TmuxWithAttach,
            )
            manager.start(task(foregroundId, dir), longRunningArgv(), emptyMap())
            manager.start(task(backgroundId, dir), longRunningArgv(), emptyMap())
            manager.attachExisting(foregroundId, cwd = dir.absolutePath)
            manager.attachExisting(backgroundId, cwd = dir.absolutePath)
            assertTrue(manager.isViewerAlive(foregroundId))
            assertTrue(manager.isViewerAlive(backgroundId))

            val enterCopyMode = ProcessBuilder(
                TmuxAndy.tmuxBinary(), "-L", TmuxAndy.SERVER,
                "copy-mode", "-e", "-t", TmuxAndy.sessionName(backgroundId),
            ).redirectErrorStream(true).start()
            assertTrue(enterCopyMode.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, enterCopyMode.exitValue())
            assertTrue(TmuxAndy.isPaneInCopyMode(backgroundId))

            manager.setOnlyForeground(foregroundId)

            assertTrue(manager.isViewerAlive(foregroundId), "foreground viewer should stay mounted")
            assertFalse(manager.isViewerAlive(backgroundId), "background viewer should detach")
            assertFalse(TmuxAndy.isPaneInCopyMode(backgroundId), "background copy mode should exit on detach")
        } finally {
            runCatching { TmuxAndy.killSession(foregroundId) }
            runCatching { TmuxAndy.killSession(backgroundId) }
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    /** Clients tmux itself reports for the session — orphaned viewers show up here. */
    private fun tmuxClientCount(taskId: String): Int {
        val process = ProcessBuilder(
            TmuxAndy.tmuxBinary(), "-L", TmuxAndy.SERVER,
            "list-clients", "-t", TmuxAndy.sessionName(taskId), "-F", "#{client_pid}",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output.lines().count { line -> line.trim().toLongOrNull() != null }
    }

    private fun longRunningArgv(): List<String> = if (isWindows) {
        listOf("cmd", "/c", "echo $MARKER && timeout /t 3600 /nobreak >nul")
    } else {
        listOf("/bin/sh", "-c", "echo $MARKER; cat")
    }

    private fun tempDir(): File = File.createTempFile("andy-terminal-lifecycle", null).also {
        it.delete()
        it.mkdirs()
    }

    private fun task(taskId: String, dir: File) = AgentTask(
        id = taskId,
        title = "lifecycle",
        prompt = "test",
        agent = AgentKind.Codex,
        status = AgentStatus.Working,
        cwd = dir.absolutePath,
        createdAtMillis = System.currentTimeMillis(),
    )

    private companion object {
        const val MARKER = "andy-lifecycle-ok"
    }
}
