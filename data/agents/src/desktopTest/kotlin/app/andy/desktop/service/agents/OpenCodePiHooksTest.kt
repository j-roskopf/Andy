package app.andy.desktop.service.agents

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenCodePiHooksTest {
    @Test
    fun installsOpenCodePluginIntoProject() {
        val home = kotlin.io.path.createTempDirectory("andy-oc-home").toFile()
        val project = kotlin.io.path.createTempDirectory("andy-oc-project").toFile()
        val previousHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", home.absolutePath)
            val artifact = File(project, ".andy/task-1").also { it.mkdirs() }
            installOpenCodeStatusHooks(project, artifact)
            val plugin = File(project, ".opencode/plugins/andy-status.js")
            assertTrue(plugin.isFile, "expected plugin at ${plugin.path}")
            assertTrue(plugin.readText().contains("andy-status-hook"))
            assertTrue(AndyStatusHookInstaller.scriptFile(home).canExecute())
        } finally {
            System.setProperty("user.home", previousHome)
            home.deleteRecursively()
            project.deleteRecursively()
        }
    }

    @Test
    fun installsPiExtensionGlobally() {
        val home = kotlin.io.path.createTempDirectory("andy-pi-home").toFile()
        val project = kotlin.io.path.createTempDirectory("andy-pi-project").toFile()
        val previousHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", home.absolutePath)
            val artifact = File(project, ".andy/task-1").also { it.mkdirs() }
            installPiStatusHooks(project, artifact)
            val extension = AndyPiExtensionInstaller.extensionPath(home)
            assertTrue(extension.isFile)
            assertTrue(extension.readText().contains("andy-status-hook"))
            assertTrue(File(project, ".andy/active-task").isFile)
        } finally {
            System.setProperty("user.home", previousHome)
            home.deleteRecursively()
            project.deleteRecursively()
        }
    }

    @Test
    fun mergesOpenCodeMcpIntoConfig() {
        val merged = McpClientConfigCompat.merge(
            """
            {
              "model": "anthropic/claude-sonnet-5",
              "mcp": {
                "playwright": { "type": "local", "command": ["npx", "x"] }
              }
            }
            """.trimIndent(),
            port = 8565,
        )
        assertTrue(merged!!.contains("\"andy\""))
        assertTrue(merged.contains("http://127.0.0.1:8565/mcp-http"))
        assertTrue(merged.contains("playwright"))
    }

    @Test
    fun openCodePluginDoesNotOverwriteUnrelatedExistingFile() {
        val project = kotlin.io.path.createTempDirectory("andy-oc-project").toFile()
        try {
            val plugin = AndyOpenCodePluginInstaller.pluginFile(project)
            plugin.parentFile.mkdirs()
            plugin.writeText("// user custom plugin\n")
            AndyOpenCodePluginInstaller.ensureInstalled(project)
            assertEquals("// user custom plugin\n", plugin.readText())
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun openCodeMergeAbortsOnInvalidJson() {
        val merged = McpClientConfigCompat.merge("""{ "model": "x", // comment }""", port = 8565)
        assertNull(merged)
    }
}

/** Test-facing wrapper so we can call internal merge without widening production API. */
private object McpClientConfigCompat {
    fun merge(content: String, port: Int): String? =
        app.andy.desktop.service.McpClientConfig.mergeOpenCodeJson(content, port)
}
