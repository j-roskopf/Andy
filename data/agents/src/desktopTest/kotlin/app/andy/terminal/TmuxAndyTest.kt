package app.andy.terminal

import app.andy.desktop.service.agents.AgentScratchWorkspace
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.text.Regex
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

class TmuxAndyTest {
    @BeforeTest
    fun isolateFromLiveAndyTmux() {
        TmuxAndy.useIsolatedServerForTests()
    }

    @Test
    fun tmuxAvailableOrSkip() {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return
        }
        assertTrue(TmuxAndy.tmuxBinary().isNotBlank())
        assertTrue(
            TmuxAndy.SERVER == TmuxAndy.TEST_SERVER ||
                TmuxAndy.SERVER.startsWith("${TmuxAndy.TEST_SERVER}-w"),
            "expected isolated test socket, got ${TmuxAndy.SERVER}",
        )
        assertTrue(TmuxAndy.SERVER != TmuxAndy.PRODUCTION_SERVER)
    }

    @Test
    fun startServerStaysAliveWhenEmpty() {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return
        }
        TmuxAndy.startServer()
        assertTrue(TmuxAndy.serverResponds(), "andy tmux server should stay up with exit-empty off")
    }

    @Test
    fun serverEnablesMouseScrollbackWithBoundedHistory() {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return
        }
        TmuxAndy.startServer()

        fun globalOption(name: String): String {
            val process = ProcessBuilder(
                TmuxAndy.tmuxBinary(), "-L", TmuxAndy.SERVER,
                "show-options", "-gv", name,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, process.exitValue(), output)
            return output
        }

        assertEquals("on", globalOption("mouse"))
        assertEquals("10000", globalOption("history-limit"))

        val wheelBinding = ProcessBuilder(
            TmuxAndy.tmuxBinary(), "-L", TmuxAndy.SERVER,
            "list-keys", "-T", "root",
        ).redirectErrorStream(true).start().let { process ->
            val output = process.inputStream.bufferedReader().readText().trim()
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, process.exitValue(), output)
            output.lineSequence().firstOrNull { it.contains("WheelUpPane") }.orEmpty()
        }
        assertTrue(wheelBinding.contains("copy-mode -e"), wheelBinding)
        assertFalse(wheelBinding.contains("alternate_on"), wheelBinding)
    }

    @Test
    fun newSessionCaptureAndKill() {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return
        }
        val taskId = "test-" + UUID.randomUUID().toString().take(8)
        try {
            TmuxAndy.newSession(
                taskId = taskId,
                cwd = System.getProperty("user.dir"),
                argv = listOf("/bin/sh", "-c", "printf 'andy-tmux-ok\\n'; sleep 30"),
            )
            assertTrue(TmuxAndy.hasSession(taskId), "session should exist")
            assertTrue(
                TmuxAndy.listSessions().any { it == TmuxAndy.sessionName(taskId) },
                "list-sessions should include ${TmuxAndy.sessionName(taskId)}",
            )
            // Give the shell a moment to print.
            Thread.sleep(300)
            val pane = TmuxAndy.capturePane(taskId, historyLines = 50)
            assertTrue(
                pane.contains("andy-tmux-ok"),
                "capture-pane missing output: ${pane.take(200)}",
            )
            TmuxAndy.sendKeys(taskId, "ignored-while-sleeping")
        } finally {
            TmuxAndy.killSession(taskId)
            assertFalse(TmuxAndy.hasSession(taskId), "session should be gone after kill")
        }
    }

    @Test
    fun outputProducedWithoutViewerRemainsInPaneHistory() {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return
        }
        val taskId = "headless-history-" + UUID.randomUUID().toString().take(8)
        try {
            TmuxAndy.newSession(
                taskId = taskId,
                cwd = System.getProperty("user.dir"),
                argv = listOf(
                    "/bin/sh",
                    "-c",
                    "i=1; while [ \$i -le 200 ]; do printf 'history-line-%03d\\n' \$i; i=\$((i+1)); done; sleep 30",
                ),
            )
            Thread.sleep(400)

            val history = TmuxAndy.capturePane(taskId, historyLines = -1)
            assertTrue(history.contains("history-line-001"), "oldest output missing from tmux history")
            assertTrue(history.contains("history-line-200"), "newest output missing from tmux history")
        } finally {
            TmuxAndy.killSession(taskId)
        }
    }

    @Test
    fun sgrWheelInputEntersTmuxCopyMode() {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return
        }
        val taskId = "wheel-protocol-" + UUID.randomUUID().toString().take(8)
        val backend = TmuxAttachBackend(sessionId = taskId)
        try {
            TmuxAndy.newSession(
                taskId = taskId,
                cwd = System.getProperty("user.dir"),
                argv = listOf(
                    "/bin/sh",
                    "-c",
                    "i=1; while [ \$i -le 100 ]; do printf 'wheel-line-%03d\\n' \$i; i=\$((i+1)); done; sleep 30",
                ),
            )
            backend.attach()
            val rust = assertNotNull(backend.rustTerminal())
            val deadline = System.currentTimeMillis() + 5_000
            while ((rust.mouseFlags() and app.andy.terminal.rust.RustMouseFlags.REPORTING) == 0 &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(25)
            }
            assertTrue(
                (rust.mouseFlags() and app.andy.terminal.rust.RustMouseFlags.REPORTING) != 0,
                "tmux client did not enable mouse reporting",
            )

            val wheel = TmuxWheelInput { bytes -> rust.write(bytes) }
            assertTrue(wheel.onScroll(-1f))
            Thread.sleep(200)
            assertTrue(TmuxAndy.isPaneInCopyMode(taskId), "wheel-up should enter tmux copy mode")
        } finally {
            backend.close()
            TmuxAndy.killSession(taskId)
        }
    }

    @Test
    fun releaseViewerExitsCopyMode() {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return
        }
        val taskId = "release-copy-mode-" + UUID.randomUUID().toString().take(8)
        val backend = TmuxAttachBackend(sessionId = taskId)
        try {
            TmuxAndy.newSession(
                taskId = taskId,
                cwd = System.getProperty("user.dir"),
                argv = listOf("/bin/sh", "-c", "printf 'live\\n'; sleep 30"),
            )
            backend.attach()
            val enterCopyMode = ProcessBuilder(
                TmuxAndy.tmuxBinary(), "-L", TmuxAndy.SERVER,
                "copy-mode", "-e", "-t", TmuxAndy.sessionName(taskId),
            ).redirectErrorStream(true).start()
            assertTrue(enterCopyMode.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, enterCopyMode.exitValue())
            assertTrue(TmuxAndy.isPaneInCopyMode(taskId))

            backend.releaseViewer()
            assertFalse(TmuxAndy.isPaneInCopyMode(taskId), "leaving chat should exit copy mode")
        } finally {
            backend.close()
            TmuxAndy.killSession(taskId)
        }
    }

    @Test
    fun tmuxWheelInputAccumulatesTrackpadDeltasAndPreservesDirection() {
        val writes = mutableListOf<String>()
        val wheel = TmuxWheelInput { writes += it.decodeToString() }

        assertTrue(wheel.onScroll(-0.1f))
        assertTrue(wheel.onScroll(-0.1f))
        assertTrue(wheel.onScroll(-0.2f))
        assertEquals(listOf("\u001B[<64;1;1M"), writes)

        writes.clear()
        assertTrue(wheel.onScroll(1f))
        assertTrue(writes.joinToString("").contains("\u001B[<65;1;1M"))
    }

    @Test
    fun exitCopyModeIfActiveReturnsPaneToLiveView() {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return
        }
        val taskId = "copy-mode-exit-" + UUID.randomUUID().toString().take(8)
        try {
            TmuxAndy.newSession(
                taskId = taskId,
                cwd = System.getProperty("user.dir"),
                argv = listOf("/bin/sh", "-c", "printf 'live-line\\n'; sleep 30"),
            )
            val enterCopyMode = ProcessBuilder(
                TmuxAndy.tmuxBinary(), "-L", TmuxAndy.SERVER,
                "copy-mode", "-e", "-t", TmuxAndy.sessionName(taskId),
            ).redirectErrorStream(true).start()
            assertTrue(enterCopyMode.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, enterCopyMode.exitValue())
            assertTrue(TmuxAndy.isPaneInCopyMode(taskId))

            TmuxAndy.exitCopyModeIfActive(taskId)
            assertFalse(TmuxAndy.isPaneInCopyMode(taskId))
        } finally {
            TmuxAndy.killSession(taskId)
        }
    }

    @Test
    fun tmuxAgentBackendDetachLeavesSessionAlive() = runBlocking {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return@runBlocking
        }
        val taskId = "detach-" + UUID.randomUUID().toString().take(8)
        val session = TmuxAgentBackend(taskId)
        try {
            session.start(
                argv = listOf("/bin/sh", "-c", "printf 'still-alive\\n'; sleep 60"),
                cwd = System.getProperty("user.dir"),
                env = emptyMap(),
            )
            Thread.sleep(300)
            session.setKillOnClose(false)
            session.close()
            assertTrue(TmuxAndy.hasSession(taskId), "detach should leave tmux session running")
            val pane = TmuxAndy.capturePane(taskId, historyLines = 20)
            assertTrue(pane.contains("still-alive"), "pane=$pane")
        } finally {
            TmuxAndy.killSession(taskId)
        }
    }

    @Test
    fun tmuxAgentBackendKeepsSessionAliveAfterCommandFinishes() = runBlocking {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return@runBlocking
        }
        val taskId = "agent-" + UUID.randomUUID().toString().take(8)
        val session = TmuxAgentBackend(taskId)
        try {
            session.start(
                argv = listOf("/bin/echo", "tmux-agent-backend"),
                cwd = System.getProperty("user.dir"),
                env = emptyMap(),
            )
            withTimeout(15_000) {
                session.bufferSnapshots.first { it.contains("tmux-agent-backend") }
            }
            delay(500)
            assertTrue(TmuxAndy.hasSession(taskId), "tmux pane should stay open after agent exits")
            assertNull(session.exitCode.value, "headless backend should not exit while session lives")
        } finally {
            session.close()
            TmuxAndy.killSession(taskId)
        }
    }

    @Test
    fun tmuxAttachBackendScrapesFromTmuxAfterViewerReleased() = runBlocking {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return@runBlocking
        }
        val taskId = "attach-scrape-" + UUID.randomUUID().toString().take(8)
        val backend = TmuxAttachBackend(sessionId = taskId)
        try {
            TmuxAndy.newSession(
                taskId = taskId,
                cwd = System.getProperty("user.dir"),
                argv = listOf("/bin/sh", "-c", "printf 'andy-attach-scrape\\n'; sleep 60"),
            )
            backend.attach()
            Thread.sleep(400)
            backend.releaseViewer()
            assertFalse(backend.isViewerAlive, "viewer should be released")
            assertTrue(backend.isAlive, "tmux session should still be alive")
            val snap = backend.bufferSnapshot()
            assertTrue(snap.contains("andy-attach-scrape"), "expected tmux capture, got=${snap.take(200)}")
        } finally {
            backend.close()
            TmuxAndy.killSession(taskId)
        }
    }

    @Test
    fun tmuxAttachBackendReattachesViewerWithoutRecreatingObservers() = runBlocking {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return@runBlocking
        }
        val taskId = "attach-reattach-" + UUID.randomUUID().toString().take(8)
        val backend = TmuxAttachBackend(sessionId = taskId)
        try {
            TmuxAndy.newSession(
                taskId = taskId,
                cwd = System.getProperty("user.dir"),
                argv = listOf("/bin/sh", "-c", "printf 'andy-reattach\\n'; sleep 60"),
            )
            backend.attach()
            Thread.sleep(400)
            repeat(4) {
                backend.releaseViewer()
                assertFalse(backend.isViewerAlive, "viewer should be released on cycle $it")
                assertTrue(backend.isAlive, "tmux session should stay alive on cycle $it")
                backend.reattachViewer()
                assertTrue(backend.isViewerAlive, "viewer should be alive after reattach on cycle $it")
                assertNotNull(backend.rustTerminal(), "view should exist after reattach on cycle $it")
            }
            val snap = backend.bufferSnapshot()
            assertTrue(snap.contains("andy-reattach"), "expected tmux capture, got=${snap.take(200)}")
        } finally {
            backend.close()
            TmuxAndy.killSession(taskId)
        }
    }

    @Test
    fun paneContentLooksLikeFailedAttachDetectsNoSessions() {
        assertTrue(TmuxAndy.paneContentLooksLikeFailedAttach("no sessions"))
        assertTrue(TmuxAndy.paneContentLooksLikeFailedAttach("can't find session: andy-task-x"))
        assertFalse(TmuxAndy.paneContentLooksLikeFailedAttach("cursor agent ready"))
    }

    @Test
    fun paneContentLooksBrokenDetectsShellInitGetcwd() {
        val broken =
            "shell-init: error retrieving current directory: getcwd: cannot access parent directories"
        assertTrue(TmuxAndy.paneContentLooksBroken(broken))
        assertTrue(
            TmuxAndy.paneContentLooksBroken("Error: ENOENT: no such file or directory, uv_cwd"),
        )
        assertFalse(TmuxAndy.paneContentLooksBroken("cursor agent ready\n"))
    }

    /**
     * Spawn-level regression for the Cursor/Claude/Codex getcwd / uv_cwd crash:
     * a deleted task cwd must land in Andy scratch, not a broken shell pane.
     */
    @Test
    fun newSessionWithDeletedCwdUsesScratchAndIsNotBroken() {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return
        }
        val gone = File.createTempFile("andy-cwd-gone", null).also { it.delete(); it.mkdirs() }
        val deletedPath = gone.absolutePath
        assertTrue(gone.deleteRecursively(), "failed to delete $deletedPath")
        assertFalse(File(deletedPath).exists())

        val scratch = AgentScratchWorkspace.ensure().absolutePath
        val taskId = "cwd-gone-" + UUID.randomUUID().toString().take(8)
        try {
            TmuxAndy.newSession(
                taskId = taskId,
                cwd = deletedPath,
                argv = listOf(
                    "/bin/sh",
                    "-c",
                    "pwd; printf 'andy-cwd-ok\\n'; sleep 60",
                ),
            )
            assertTrue(TmuxAndy.hasSession(taskId), "session should exist")
            Thread.sleep(400)
            assertFalse(
                TmuxAndy.sessionLooksBroken(taskId),
                "deleted cwd must not produce a broken pane",
            )
            val pane = TmuxAndy.capturePane(taskId, historyLines = 50)
            assertFalse(
                pane.contains("shell-init: error retrieving current directory"),
                "pane still has shell-init getcwd: ${pane.take(300)}",
            )
            assertFalse(pane.contains("uv_cwd"), "pane still has uv_cwd: ${pane.take(300)}")
            assertTrue(
                pane.contains("andy-cwd-ok"),
                "command should run after cwd recovery, pane=${pane.take(300)}",
            )
            assertTrue(
                pane.contains(scratch) || pane.contains(".andy-tasks"),
                "pwd should be Andy scratch, pane=${pane.take(300)}",
            )
        } finally {
            TmuxAndy.killSession(taskId)
        }
    }

    /**
     * When the Andy tmux server process itself holds a deleted cwd, `new-session -c`
     * is ignored and every Node CLI dies on uv_cwd. newSession must recycle the server.
     */
    @Test
    fun newSessionRecoversFromPoisonedTmuxServerCwd() {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return
        }
        val poison = File.createTempFile("andy-tmux-poison", null).also { it.delete(); it.mkdirs() }
        val poisonPath = poison.absolutePath
        // Start the andy server from a directory we then delete (matches real failure mode).
        val starter = ProcessBuilder(
            TmuxAndy.tmuxBinary(), "-L", TmuxAndy.SERVER,
            "new-session", "-d", "-s", "andy-task-poison-seed",
            "--", "/bin/sh", "-c", "sleep 60",
        ).directory(poison).redirectErrorStream(true).start()
        starter.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(poison.deleteRecursively(), "failed to delete poison dir")
        assertFalse(File(poisonPath).exists())

        val scratch = AgentScratchWorkspace.ensure().absolutePath
        val taskId = "cwd-poison-" + UUID.randomUUID().toString().take(8)
        try {
            TmuxAndy.newSession(
                taskId = taskId,
                cwd = scratch,
                argv = listOf("/bin/sh", "-c", "pwd; printf 'andy-poison-ok\\n'; sleep 60"),
            )
            Thread.sleep(400)
            assertFalse(
                TmuxAndy.sessionLooksBroken(taskId),
                "poisoned server must be recycled before spawn",
            )
            val pane = TmuxAndy.capturePane(taskId, historyLines = 50)
            assertFalse(
                pane.contains("shell-init: error retrieving current directory"),
                "pane still poisoned: ${pane.take(300)}",
            )
            assertTrue(pane.contains("andy-poison-ok"), "pane=${pane.take(300)}")
            assertTrue(
                pane.contains(scratch) || pane.contains(".andy-tasks"),
                "pwd should be scratch after server recycle, pane=${pane.take(300)}",
            )
        } finally {
            TmuxAndy.killSession(taskId)
            runCatching { TmuxAndy.killSession("poison-seed") }
        }
    }

    /**
     * Recycle must not SIGTERM the shared server while a healthy sibling chat is live —
     * that is the `[server exited]` failure mode for every attached Andy agent pane.
     */
    @Test
    fun newSessionDoesNotRecycleServerWhenHealthySiblingExists() {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return
        }
        val healthyId = "healthy-" + UUID.randomUUID().toString().take(8)
        val brokenId = "broken-" + UUID.randomUUID().toString().take(8)
        try {
            TmuxAndy.newSession(
                taskId = healthyId,
                cwd = System.getProperty("user.dir"),
                argv = listOf("/bin/sh", "-c", "printf 'andy-healthy\\n'; sleep 60"),
            )
            assertTrue(TmuxAndy.hasSession(healthyId))
            // Fake getcwd chrome in a sibling pane — enough to trip recycle detection.
            val error = assertFailsWith<IllegalStateException> {
                TmuxAndy.newSession(
                    taskId = brokenId,
                    cwd = System.getProperty("user.dir"),
                    argv = listOf(
                        "/bin/sh",
                        "-c",
                        "printf 'shell-init: error retrieving current directory: getcwd: cannot access parent directories\\n'; sleep 60",
                    ),
                )
            }
            assertTrue(
                error.message.orEmpty().contains("poisoned"),
                "expected poisoned-server error, got=${error.message}",
            )
            assertTrue(TmuxAndy.hasSession(healthyId), "healthy sibling must survive refused recycle")
            assertFalse(TmuxAndy.hasSession(brokenId), "broken new session should be torn down")
            val pane = TmuxAndy.capturePane(healthyId, historyLines = 20)
            assertTrue(pane.contains("andy-healthy"), "healthy pane=${pane.take(300)}")
        } finally {
            runCatching { TmuxAndy.killSession(healthyId) }
            runCatching { TmuxAndy.killSession(brokenId) }
        }
    }

    @Test
    fun shellQuoteEscapesSingleQuotes() {
        assertEquals("'foo'", TmuxAndy.shellQuote("foo"))
        val quoted = TmuxAndy.shellQuote("it's")
        assertTrue(quoted.startsWith("'"), "quoted=$quoted")
        assertTrue(quoted.endsWith("'"), "quoted=$quoted")
        assertTrue("it" in quoted && "s" in quoted, "quoted=$quoted")
        val launch = TmuxAndy.buildLaunchCommand(listOf("echo", "hi"), mapOf("FOO" to "bar"))
        assertTrue("'FOO'=" in launch || "FOO=" in launch, "launch missing FOO: $launch")
        assertTrue(launch.startsWith("env -i"), "launch must use env -i: $launch")
        assertTrue(launch.endsWith("exec '/bin/sh'"), "launch must keep shell after agent: $launch")
        assertFalse(
            launch.startsWith("exec env -i"),
            "agent must not be exec'd — pane should survive agent exit: $launch",
        )
        assertTrue("'echo'" in launch || "echo" in launch)

        val dir = File.createTempFile("andy-launch-cwd", null).also { it.delete(); it.mkdirs() }
        try {
            val withCd = TmuxAndy.buildLaunchCommand(
                listOf("echo", "hi"),
                emptyMap(),
                cwd = dir.absolutePath,
            )
            assertTrue(withCd.startsWith("cd "), "launch must cd into cwd first: $withCd")
            assertTrue(dir.absolutePath in withCd || dir.canonicalPath in withCd, withCd)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun launchScriptIsOwnerOnlyWhileSessionRuns() {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return
        }
        if (!supportsPosixPermissions()) {
            println("SKIP: POSIX permissions unavailable")
            return
        }

        val taskId = "launch-script-" + UUID.randomUUID().toString().take(8)
        val script = File(
            System.getProperty("user.home"),
            ".andy/tmux-launch/${TmuxAndy.SERVER}/${TmuxAndy.sessionName(taskId)}.sh",
        )
        try {
            TmuxAndy.newSession(
                taskId = taskId,
                cwd = System.getProperty("user.dir"),
                argv = listOf("/bin/sh", "-c", "sleep 30"),
            )
            assertTrue(script.isFile, "launch script must exist while session is running at ${script.absolutePath}")
            val perms = java.nio.file.Files.getPosixFilePermissions(script.toPath())
            assertEquals(
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                ),
                perms,
            )
        } finally {
            TmuxAndy.killSession(taskId)
            assertFalse(script.isFile, "launch script must be deleted when session ends")
        }
    }

    private fun supportsPosixPermissions(): Boolean =
        runCatching {
            java.nio.file.Files.getPosixFilePermissions(File.createTempFile("posix-probe", null).toPath())
            true
        }.getOrDefault(false)
}
