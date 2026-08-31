package app.andy.desktop.service.agents

import app.andy.model.AgentEvent
import app.andy.model.AgentKind
import app.andy.terminal.buildTerminalLaunchEnvironment
import app.andy.terminal.replaceProcessEnvironment
import app.andy.terminal.resolveTerminalWorkingDirectory
import app.andy.terminal.scrubInheritedTerminalEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.assertNull

class AgentLaunchEnvironmentTest {
    @Test
    fun resolveTerminalWorkingDirectoryFallsBackWhenMissing() {
        val home = File(System.getProperty("user.home")).absolutePath
        assertEquals(home, resolveTerminalWorkingDirectory(null))
        assertEquals(home, resolveTerminalWorkingDirectory(""))
        assertEquals(home, resolveTerminalWorkingDirectory("/definitely/not/a/real/andy/path"))
        val here = File(".").absoluteFile.normalize().absolutePath
        assertEquals(here, resolveTerminalWorkingDirectory(here))
    }

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
            "CURSOR_AGENT" to "1",
            "CURSOR_API_KEY" to "user-key",
            "CURSOR_AUTH_TOKEN" to "user-token",
            "TERM" to "dumb",
            "FORCE_COLOR" to "0",
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
        assertNull(env["CURSOR_AGENT"])
        assertEquals("user-key", env["CURSOR_API_KEY"])
        assertEquals("user-token", env["CURSOR_AUTH_TOKEN"])
        assertNull(env["FORCE_COLOR"])
        assertEquals("xterm-256color", env["TERM"])
    }

    @Test
    fun scrubsIdeSandboxXdgSoCursorAgentFindsUserAuth() {
        val env = mutableMapOf(
            "HOME" to "/home/test",
            "PATH" to "/usr/bin",
            "XDG_STATE_HOME" to "/tmp/cursor-sandbox/state",
            "XDG_CONFIG_HOME" to "/tmp/cursor-sandbox/config",
            "XDG_CACHE_HOME" to "/tmp/cursor-sandbox/cache",
            "XDG_DATA_HOME" to "/tmp/cursor-sandbox/data",
        )

        scrubInheritedAgentEnvironment(env)

        assertEquals("/home/test", env["HOME"])
        assertNull(env["XDG_STATE_HOME"])
        assertNull(env["XDG_CONFIG_HOME"])
        assertNull(env["XDG_CACHE_HOME"])
        assertNull(env["XDG_DATA_HOME"])
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
    fun replaceProcessEnvironmentDropsParentKeysOmittedFromDesired() {
        val current = mutableMapOf(
            "PATH" to "/from/parent",
            "CURSOR_AGENT" to "1",
            "NODE_OPTIONS" to "--require /tmp/bootloader.js",
            "HOME" to "/Users/test",
        )
        replaceProcessEnvironment(
            current,
            mapOf(
                "PATH" to "/usr/bin",
                "HOME" to "/Users/test",
                "CURSOR_API_KEY" to "user-key",
            ),
        )
        assertEquals("/usr/bin", current["PATH"])
        assertEquals("/Users/test", current["HOME"])
        assertEquals("user-key", current["CURSOR_API_KEY"])
        assertNull(current["CURSOR_AGENT"])
        assertNull(current["NODE_OPTIONS"])
    }

    @Test
    fun buildTerminalLaunchEnvironmentPrefersLoginShellEnvOverProcessEnvAndOverridesOverBoth() {
        val uniqueKey = "ANDY_TEST_MERGE_ORDER_${System.nanoTime()}"

        val withOverride = buildTerminalLaunchEnvironment(
            overrides = mapOf(uniqueKey to "override-value"),
            loginShellEnv = mapOf(uniqueKey to "shell-value"),
        )
        assertEquals("override-value", withOverride[uniqueKey])

        val withoutOverride = buildTerminalLaunchEnvironment(
            overrides = emptyMap(),
            loginShellEnv = mapOf(uniqueKey to "shell-value"),
        )
        assertEquals("shell-value", withoutOverride[uniqueKey])
    }

    @Test
    fun buildTerminalLaunchEnvironmentStillScrubsAfterMergingLoginShellEnv() {
        val env = buildTerminalLaunchEnvironment(
            loginShellEnv = mapOf(
                "NODE_OPTIONS" to "--require /tmp/bootloader.js",
                "PATH" to "/from/shell",
            ),
        )
        assertNull(env["NODE_OPTIONS"])
        assertEquals("/from/shell", env["PATH"])
    }

    @Test
    fun buildTerminalLaunchEnvironmentDropsJvmXdgUnlessLoginShellSetsIt() {
        val env = buildTerminalLaunchEnvironment(
            loginShellEnv = mapOf("PATH" to "/from/shell"),
        )
        assertNull(env["XDG_STATE_HOME"])
        assertNull(env["XDG_CONFIG_HOME"])
        assertEquals("/from/shell", env["PATH"])
    }

    @Test
    fun buildTerminalLaunchEnvironmentRestoresLoginShellXdgAfterScrub() {
        val env = buildTerminalLaunchEnvironment(
            loginShellEnv = mapOf(
                "PATH" to "/from/shell",
                "XDG_STATE_HOME" to "/home/test/.local/state",
            ),
        )
        assertEquals("/home/test/.local/state", env["XDG_STATE_HOME"])
        assertEquals("/from/shell", env["PATH"])
    }

    @Test
    fun buildTerminalLaunchEnvironmentPrefersOverrideXdgOverLoginShell() {
        val env = buildTerminalLaunchEnvironment(
            overrides = mapOf("XDG_STATE_HOME" to "/custom/state"),
            loginShellEnv = mapOf("XDG_STATE_HOME" to "/home/test/.local/state"),
        )
        assertEquals("/custom/state", env["XDG_STATE_HOME"])
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
            "Not logged in — run `claude` in a terminal and sign in (`/login`), then retry",
            agentFailureMessage(
                lastError = null,
                authHint = providerAuthFailureHint(AgentKind.ClaudeCode, "Please run /login"),
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
