package app.andy.desktop.service.agents.acp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AcpProcessLauncherTest {
    @Test
    fun npxCommandPrefersAbsoluteNodeOverEnvShebang() {
        val runtime = NodeRuntime(
            node = "/opt/homebrew/bin/node",
            npx = "/opt/homebrew/bin/npx",
        )
        assertEquals(
            listOf(
                "/opt/homebrew/bin/node",
                "/opt/homebrew/bin/npx",
                "-y",
                "@agentclientprotocol/codex-acp@1.1.9",
            ),
            acpNpxCommand(runtime, listOf("-y", "@agentclientprotocol/codex-acp@1.1.9")),
        )
    }

    @Test
    fun npxCommandKeepsWindowsCmdShimDirect() {
        val runtime = NodeRuntime(
            node = """C:\Program Files\nodejs\node.exe""",
            npx = """C:\Program Files\nodejs\npx.cmd""",
        )
        assertEquals(
            listOf("""C:\Program Files\nodejs\npx.cmd""", "-y", "pi-acp@0.0.33"),
            acpNpxCommand(runtime, listOf("-y", "pi-acp@0.0.33")),
        )
    }

    @Test
    fun ensureNodeDirOnPathPrependsNodeDirectory() {
        val env = mutableMapOf("PATH" to "/usr/bin:/bin")
        ensureNodeDirOnPath(env, listOf("/opt/homebrew/bin/node", "/opt/homebrew/bin/npx", "--version"))
        assertEquals("/opt/homebrew/bin" + File.pathSeparator + "/usr/bin:/bin", env["PATH"])
    }

    @Test
    fun ensureNodeDirOnPathIsIdempotent() {
        val env = mutableMapOf("PATH" to "/opt/homebrew/bin:/usr/bin")
        ensureNodeDirOnPath(env, listOf("/opt/homebrew/bin/node", "/opt/homebrew/bin/npx"))
        assertEquals("/opt/homebrew/bin:/usr/bin", env["PATH"])
    }

    @Test
    fun ensureNodeDirOnPathIgnoresNativeCommands() {
        val env = mutableMapOf("PATH" to "/usr/bin")
        ensureNodeDirOnPath(env, listOf("cursor-agent", "acp"))
        assertEquals("/usr/bin", env["PATH"])
    }

    @Test
    fun ensureNodeDirOnPathCreatesPathWhenMissing() {
        val env = mutableMapOf<String, String>()
        ensureNodeDirOnPath(env, listOf("/usr/local/bin/node", "/usr/local/bin/npx"))
        assertTrue(env["PATH"] == "/usr/local/bin" || env["Path"] == "/usr/local/bin")
    }
}
