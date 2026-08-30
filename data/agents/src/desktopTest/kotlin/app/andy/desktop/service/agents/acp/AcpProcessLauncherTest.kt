package app.andy.desktop.service.agents.acp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
            acpNpxCommand(
                runtime,
                listOf("-y", "@agentclientprotocol/codex-acp@1.1.9"),
                isNodeScript = { true },
            ),
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
    fun npxCommandRunsNonJavaScriptShimsDirectly() {
        val runtime = NodeRuntime(
            node = "/Users/me/.asdf/shims/node",
            npx = "/Users/me/.asdf/shims/npx",
        )
        assertEquals(
            listOf("/Users/me/.asdf/shims/npx", "-y", "pi-acp@0.0.33"),
            acpNpxCommand(
                runtime,
                listOf("-y", "pi-acp@0.0.33"),
                isNodeScript = { false },
            ),
        )
    }

    @Test
    fun npxLooksLikeNodeScriptDetectsEnvNodeShebang() {
        val file = File.createTempFile("andy-npx", ".js")
        file.writeText("#!/usr/bin/env node\nconsole.log('ok')\n")
        try {
            assertTrue(npxLooksLikeNodeScript(file.absolutePath))
        } finally {
            file.delete()
        }
    }

    @Test
    fun npxLooksLikeNodeScriptRejectsShellShim() {
        val file = File.createTempFile("andy-npx-shim", "")
        file.writeText("#!/usr/bin/env bash\nexec real-npx \"$@\"\n")
        try {
            assertFalse(npxLooksLikeNodeScript(file.absolutePath))
        } finally {
            file.delete()
        }
    }

    @Test
    fun ensureNodeDirOnPathPrependsNodeDirectory() {
        val nodeDir = File("opt", "homebrew").resolve("bin").absoluteFile
        val node = File(nodeDir, "node").path
        val npx = File(nodeDir, "npx").path
        val existing = listOf(File("usr", "bin").absolutePath, File("bin").absolutePath)
            .joinToString(File.pathSeparator)
        val env = mutableMapOf("PATH" to existing)

        ensureNodeDirOnPath(env, listOf(node, npx, "--version"))

        assertEquals(nodeDir.path + File.pathSeparator + existing, pathValue(env))
    }

    @Test
    fun ensureNodeDirOnPathAcceptsExplicitNodeBinaryForShimLaunch() {
        val nodeDir = File("Users", "me")
            .resolve(".asdf")
            .resolve("installs")
            .resolve("nodejs")
            .resolve("22.0.0")
            .resolve("bin")
            .absoluteFile
        val node = File(nodeDir, "node").path
        val npx = File("Users", "me").resolve(".asdf").resolve("shims").resolve("npx").absolutePath
        val existing = File("usr", "bin").absolutePath
        val env = mutableMapOf("PATH" to existing)

        ensureNodeDirOnPath(env, listOf(npx, "-y", "pkg@1"), nodeBinary = node)

        assertEquals(nodeDir.path + File.pathSeparator + existing, pathValue(env))
    }

    @Test
    fun ensureNodeDirOnPathIsIdempotent() {
        val nodeDir = File("opt", "homebrew").resolve("bin").absoluteFile
        val node = File(nodeDir, "node").path
        val npx = File(nodeDir, "npx").path
        val existing = nodeDir.path + File.pathSeparator + File("usr", "bin").absolutePath
        val env = mutableMapOf("PATH" to existing)

        ensureNodeDirOnPath(env, listOf(node, npx))

        assertEquals(existing, pathValue(env))
    }

    @Test
    fun ensureNodeDirOnPathIgnoresNativeCommands() {
        val existing = File("usr", "bin").absolutePath
        val env = mutableMapOf("PATH" to existing)
        ensureNodeDirOnPath(env, listOf("cursor-agent", "acp"))
        assertEquals(existing, pathValue(env))
    }

    @Test
    fun ensureNodeDirOnPathCreatesPathWhenMissing() {
        val nodeDir = File("usr", "local").resolve("bin").absoluteFile
        val node = File(nodeDir, "node").path
        val npx = File(nodeDir, "npx").path
        val env = mutableMapOf<String, String>()

        ensureNodeDirOnPath(env, listOf(node, npx))

        assertEquals(nodeDir.path, pathValue(env))
    }

    @Test
    fun preflightDiscardsStdinSoAcpAdaptersThatIgnoreVersionCanExit() {
        val os = System.getProperty("os.name").orEmpty()
        if (os.startsWith("Windows", ignoreCase = true)) return
        val builder = ProcessBuilder("true").discardAcpPreflightStdin()
        assertEquals(File("/dev/null"), builder.redirectInput().file())
    }

    @Test
    fun applyLaunchEnvDropsCursorAgentHostMarkerFromParentJvm() {
        val builder = ProcessBuilder("true")
        builder.environment()["CURSOR_AGENT"] = "1"
        builder.environment()["CURSOR_CONVERSATION_ID"] = "nested"
        builder.environment()["NODE_OPTIONS"] = "--require /tmp/bootloader.js"
        builder.applyLaunchEnv(
            env = mapOf(
                "PATH" to "/usr/bin",
                "HOME" to "/tmp",
                "CURSOR_API_KEY" to "user-key",
            ),
            command = listOf("cursor-agent", "acp"),
        )
        assertNull(builder.environment()["CURSOR_AGENT"])
        assertNull(builder.environment()["CURSOR_CONVERSATION_ID"])
        assertNull(builder.environment()["NODE_OPTIONS"])
        assertEquals("user-key", builder.environment()["CURSOR_API_KEY"])
        assertEquals("/usr/bin", builder.environment()["PATH"])
    }

    private fun pathValue(env: Map<String, String>): String? =
        env.entries.firstOrNull { it.key.equals("PATH", ignoreCase = true) }?.value
}
