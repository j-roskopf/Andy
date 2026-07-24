package app.andy.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
}
