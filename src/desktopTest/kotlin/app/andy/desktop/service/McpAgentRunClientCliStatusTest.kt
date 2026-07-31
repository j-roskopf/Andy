package app.andy.desktop.service

import app.andy.model.AgentCliStatus
import app.andy.model.AgentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpAgentRunClientCliStatusTest {
    @Test
    fun daemonUnknownAgentWithLocalInstallNeedsAndydRestart() {
        val status = statusForDaemonUnknownAgent(
            AgentKind.OpenCode,
            AgentCliStatus(AgentKind.OpenCode, binaryPath = "/Users/me/.opencode/bin/opencode", version = "1.0"),
        )
        assertFalse(status.ready)
        assertNotNull(status.issue)
        assertEquals("Restart andyd", status.issue?.title)
        assertTrue(status.issue?.detail.orEmpty().contains("runAndyd"))
    }

    @Test
    fun daemonUnknownAgentWithoutLocalInstallIsUnavailable() {
        val status = statusForDaemonUnknownAgent(AgentKind.Pi, null)
        assertFalse(status.available)
        assertNull(status.issue)
    }
}
