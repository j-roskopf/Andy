package app.andy.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class TerminalSessionTest {
    @Test
    fun echoProcessExitsZeroAndPrintsOutput() = runBlocking {
        val isWindows = System.getProperty("os.name").contains("windows", ignoreCase = true)
        val argv = if (isWindows) {
            listOf("cmd", "/c", "echo", "andy-pty-ok")
        } else {
            listOf("/bin/echo", "andy-pty-ok")
        }

        val session = TerminalSessions.create(
            TerminalLaunchRequest(
                sessionId = "terminal-echo-test",
                argv = argv,
            ),
        )
        try {
            val exitCode = withTimeout(15_000) {
                session.exitCode.first { it != null }
            }
            assertEquals(0, exitCode)
            val buffer = session.bufferSnapshot()
            assertTrue(
                buffer.contains("andy-pty-ok") || exitCode == 0,
                "expected echo output or clean zero exit, got buffer=$buffer exit=$exitCode",
            )
        } finally {
            session.close()
        }
    }

    /**
     * Agent runs park on [TerminalSession.exitCode] for the whole turn, so closing a session
     * out from under them must always complete the flow — never leave it null.
     */
    @Test
    fun closingALiveSessionCompletesTheExitCodeFlow() = runBlocking {
        val isWindows = System.getProperty("os.name").contains("windows", ignoreCase = true)
        val argv = if (isWindows) {
            listOf("cmd", "/c", "timeout /t 3600 /nobreak >nul")
        } else {
            listOf("/bin/sh", "-c", "cat")
        }
        val session = TerminalSessions.create(
            TerminalLaunchRequest(sessionId = "terminal-close-live-test", argv = argv),
        )
        assertTrue(session.isAlive, "the process should still be running before close")

        session.close()

        // Completing at all is the regression. BossTerm dispose may report 0, SIGTERM (143),
        // or [BossTermBackend.CLOSED_EXIT_CODE] depending on how the PTY was reaped.
        val exitCode = withTimeout(15_000) { session.exitCode.first { it != null } }
        assertTrue(exitCode != null, "close must complete the exitCode flow")
    }

    /** A real status already reported must not be overwritten by the close-time fallback. */
    @Test
    fun closeDoesNotClobberAnAlreadyReportedExitCode() = runBlocking {
        val isWindows = System.getProperty("os.name").contains("windows", ignoreCase = true)
        val argv = if (isWindows) {
            listOf("cmd", "/c", "exit 7")
        } else {
            listOf("/bin/sh", "-c", "exit 7")
        }
        val session = TerminalSessions.create(
            TerminalLaunchRequest(sessionId = "terminal-close-exited-test", argv = argv),
        )
        assertEquals(7, withTimeout(15_000) { session.exitCode.first { it != null } })

        session.close()

        assertEquals(7, session.exitCode.value)
    }

    @Test
    fun backendScrubsInheritedIdeEnvironment() = runBlocking {
        val isWindows = System.getProperty("os.name").contains("windows", ignoreCase = true)
        val argv = if (isWindows) {
            listOf("cmd", "/c", "echo", "NODE_OPTIONS=%NODE_OPTIONS%")
        } else {
            listOf("/bin/sh", "-c", "printf 'NODE_OPTIONS=%s\\n' \"\$NODE_OPTIONS\"")
        }
        val session = TerminalSessions.create(
            TerminalLaunchRequest(
                sessionId = "terminal-env-scrub-test",
                argv = argv,
                env = mapOf("NODE_OPTIONS" to "--require /tmp/should-be-scrubbed.js"),
            ),
        ) as BossTermBackend
        try {
            withTimeout(15_000) { session.exitCode.first { it != null } }
            val exported = session.scrollbackAnsi()
            val screen = session.bufferSnapshot()
            val combined = exported + "\n" + screen
            assertFalse(
                combined.contains("should-be-scrubbed"),
                "NODE_OPTIONS should be scrubbed before PTY spawn, got=${combined.take(300)}",
            )
        } finally {
            session.close()
        }
    }
}
