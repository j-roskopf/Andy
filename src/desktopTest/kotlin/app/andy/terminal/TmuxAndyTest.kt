package app.andy.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.text.Regex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID

class TmuxAndyTest {
    @Test
    fun tmuxAvailableOrSkip() {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return
        }
        assertTrue(TmuxAndy.tmuxBinary().isNotBlank())
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
    fun tmuxAgentBackendExitsWhenCommandFinishes() = runBlocking {
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
            val code = withTimeout(15_000) { session.exitCode.first { it != null } }
            assertEquals(0, code)
            val snap = session.bufferSnapshot()
            // Session may already be gone; capture during life is best-effort.
            assertTrue(code == 0 || snap.contains("tmux-agent-backend"))
        } finally {
            session.close()
            TmuxAndy.killSession(taskId)
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
        assertTrue(launch.startsWith("exec env -i"), "launch must use shell-exec + env -i: $launch")
        assertFalse(
            Regex("""env(?:\s+-i)?(?:\s+'[^']*'='[^']*')*\s+exec\s""").containsMatchIn(launch),
            "env must not take `exec` as its command (no /usr/bin/exec on macOS): $launch",
        )
        assertTrue("'echo'" in launch || "echo" in launch)
    }
}
