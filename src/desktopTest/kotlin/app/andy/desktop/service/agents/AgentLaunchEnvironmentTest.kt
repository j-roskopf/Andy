package app.andy.desktop.service.agents

import app.andy.model.AgentEvent
import app.andy.terminal.scrubInheritedTerminalEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AgentLaunchEnvironmentTest {
    @Test
    fun scrubsIdeAndProxyOverridesButPreservesAuthCredentials() {
        val env = mutableMapOf(
            "PATH" to "/usr/bin",
            "ANTHROPIC_BASE_URL" to "http://127.0.0.1:11434",
            "ANTHROPIC_API_KEY" to "sk-test",
            "ANTHROPIC_AUTH_TOKEN" to "token",
            "NODE_OPTIONS" to "--require /tmp/bootloader.js",
            "VSCODE_INSPECTOR_OPTIONS" to "ipc",
            "ELECTRON_RUN_AS_NODE" to "1",
            "HOME" to "/Users/test",
        )

        scrubInheritedAgentEnvironment(env)

        assertEquals("/usr/bin", env["PATH"])
        assertEquals("/Users/test", env["HOME"])
        assertNull(env["ANTHROPIC_BASE_URL"])
        assertEquals("sk-test", env["ANTHROPIC_API_KEY"])
        assertEquals("token", env["ANTHROPIC_AUTH_TOKEN"])
        assertNull(env["NODE_OPTIONS"])
        assertNull(env["VSCODE_INSPECTOR_OPTIONS"])
        assertNull(env["ELECTRON_RUN_AS_NODE"])
    }

    @Test
    fun scrubAfterMergeRemovesIdeVarsThatPutAllCannotDrop() {
        // Reproduces the historical bug: System.getenv() + putAll(scrubbed) left
        // NODE_OPTIONS in place because scrubbed maps omit keys instead of nulling them.
        val processEnv = mutableMapOf(
            "PATH" to "/usr/bin",
            "NODE_OPTIONS" to "--require /Applications/Cursor.app/bootloader.js",
            "VSCODE_INSPECTOR_OPTIONS" to "autoAttachMode=always",
            "HOME" to "/Users/test",
        )
        val scrubbed = processEnv.toMutableMap().also { scrubInheritedTerminalEnvironment(it) }
        assertFalse(scrubbed.containsKey("NODE_OPTIONS"))

        val buggy = HashMap(processEnv).apply { putAll(scrubbed) }
        assertEquals("--require /Applications/Cursor.app/bootloader.js", buggy["NODE_OPTIONS"])

        val fixed = HashMap(processEnv).apply {
            putAll(scrubbed)
            scrubInheritedTerminalEnvironment(this)
        }
        assertNull(fixed["NODE_OPTIONS"])
        assertNull(fixed["VSCODE_INSPECTOR_OPTIONS"])
        assertEquals("/usr/bin", fixed["PATH"])
    }

    @Test
    fun failureMessagePrefersStructuredErrorThenResultThenFallback() {
        assertEquals(
            "boom",
            agentFailureMessage(
                lastError = "boom",
                authHint = "Not logged in",
                result = AgentEvent.TaskResult(1, success = false, finalText = "result"),
                fallbackText = "raw",
                exitCode = 1,
            ),
        )
        assertEquals(
            "Not logged in — run `claude` in a terminal and sign in, then retry",
            agentFailureMessage(
                lastError = null,
                authHint = "Not logged in — run `claude` in a terminal and sign in, then retry",
                result = null,
                fallbackText = "Please run /login",
                exitCode = 1,
            ),
        )
        assertEquals(
            "provider said no",
            agentFailureMessage(
                lastError = null,
                authHint = null,
                result = AgentEvent.TaskResult(1, success = false, finalText = "provider said no"),
                fallbackText = "raw",
                exitCode = 1,
            ),
        )
        assertEquals(
            "Error: plain failure",
            agentFailureMessage(
                lastError = null,
                authHint = null,
                result = null,
                fallbackText = "Error: plain failure",
                exitCode = 1,
            ),
        )
        assertEquals(
            "exited with code 1",
            agentFailureMessage(
                lastError = null,
                authHint = null,
                result = AgentEvent.TaskResult(1, success = true, finalText = "ok"),
                fallbackText = null,
                exitCode = 1,
            ),
        )
        assertFalse(
            agentFailureMessage(
                lastError = null,
                authHint = null,
                result = null,
                fallbackText = null,
                exitCode = 2,
            ).contains("null"),
        )
    }
}
