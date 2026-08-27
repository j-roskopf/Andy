package app.andy.desktop.service.remote

import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndydMcpClientTest {
    @Test
    fun parseShellToolResultExtractsExitStdoutStderr() {
        val text = "Exit Code: 0\nStdout:\nhello\nStderr:\n"
        val result = AndydMcpClient.parseShellToolResult(text)
        assertTrue(result.isSuccess)
        assertEquals("hello", result.stdout)
    }

    @Test
    fun parseCommandToolResultExtractsFields() {
        val text = "Result: 1\nStdout: done\nStderr: oops"
        val result = AndydMcpClient.parseCommandToolResult(text)
        assertEquals(1, result.exitCode)
        assertEquals("done", result.stdout)
        assertEquals("oops", result.stderr)
    }

    @Test
    fun remoteSdkDiscoveryMarksRemoteAdb() {
        val sdk = AndydMcpClient.remoteSdkDiscovery
        assertEquals("remote", sdk.adbPath)
        assertTrue(sdk.issues.any { it.contains("remote", ignoreCase = true) })
    }
}
