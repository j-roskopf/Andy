package app.andy.desktop.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpClientConfigTest {
    @Test
    fun claudeDesktopConfigPathUsesWindowsAppDataLocation() {
        val originalOsName = System.getProperty("os.name")
        val originalHome = System.getProperty("user.home")
        val testHome = kotlin.io.path.createTempDirectory("andy-mcp-home").toFile()
        try {
            System.setProperty("os.name", "Windows 11")
            System.setProperty("user.home", testHome.absolutePath)

            val file = McpClientConfig.getConfigFile(McpClientConfig.ClientType.ClaudeDesktop)
            val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                ?: File(testHome, "AppData/Roaming").absolutePath

            assertEquals(
                File(appData, "Claude/claude_desktop_config.json").absolutePath,
                file?.absolutePath,
            )
        } finally {
            System.setProperty("os.name", originalOsName)
            System.setProperty("user.home", originalHome)
            testHome.deleteRecursively()
        }
    }

    @Test
    fun jsonMergeReplacesInvalidMcpServersValue() {
        val originalHome = System.getProperty("user.home")
        val testHome = kotlin.io.path.createTempDirectory("andy-mcp-home").toFile()
        try {
            System.setProperty("user.home", testHome.absolutePath)
            val file = File(testHome, ".claude.json")
            file.writeText("""{"mcpServers": false, "keep": "value"}""")

            val written = McpClientConfig.writeConfig(McpClientConfig.ClientType.ClaudeCode, 4987)

            assertTrue(written)
            val content = file.readText()
            assertTrue(content.contains(""""keep": "value""""))
            assertTrue(content.contains(""""andy""""))
            assertTrue(content.contains(""""url": "http://127.0.0.1:4987/mcp-http""""))
        } finally {
            System.setProperty("user.home", originalHome)
            testHome.deleteRecursively()
        }
    }
}
