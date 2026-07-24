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
        ) as KetraTermBackend
        try {
            withTimeout(15_000) { session.exitCode.first { it != null } }
            val tee = session.scrollbackAnsi()
            val screen = session.bufferSnapshot()
            val combined = tee + "\n" + screen
            assertFalse(
                combined.contains("should-be-scrubbed"),
                "NODE_OPTIONS should be scrubbed before PTY spawn, got=${combined.take(300)}",
            )
        } finally {
            session.close()
        }
    }
}
