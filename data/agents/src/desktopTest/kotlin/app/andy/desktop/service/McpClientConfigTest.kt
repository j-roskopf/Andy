package app.andy.desktop.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun codexConfigUsesStreamableHttpNotLegacySse() {
        val originalHome = System.getProperty("user.home")
        val testHome = kotlin.io.path.createTempDirectory("andy-mcp-home").toFile()
        try {
            System.setProperty("user.home", testHome.absolutePath)
            val file = File(testHome, ".codex/config.toml")
            file.parentFile.mkdirs()
            file.writeText(
                """
                [mcp_servers.andy]
                url = "http://127.0.0.1:1/mcp"
                type = "sse"
                """.trimIndent(),
            )

            val written = McpClientConfig.writeConfig(McpClientConfig.ClientType.Codex, 8565)

            assertTrue(written)
            val content = file.readText()
            assertTrue(content.contains("""url = "http://127.0.0.1:8565/mcp-http""""))
            assertFalse(content.contains("type = \"sse\""))
            assertFalse(content.contains("""url = "http://127.0.0.1:8565/mcp""""))
            assertEquals(
                """
                [mcp_servers.andy]
                url = "http://127.0.0.1:8565/mcp-http"
                """.trimIndent(),
                McpClientConfig.getSnippet(McpClientConfig.ClientType.Codex, 8565),
            )
        } finally {
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

    @Test
    fun hermesYamlIncludesBearerHeadersWhenNetworkAccessTokenProvided() {
        val merged = McpClientConfig.mergeHermesYaml("", 8565, bearerToken = "secret-token")
        assertTrue(merged.contains("""url: "http://127.0.0.1:8565/mcp-http""""))
        assertTrue(merged.contains("headers:"))
        assertTrue(merged.contains("""Authorization: "Bearer secret-token""""))
        assertTrue(
            McpClientConfig.getSnippet(McpClientConfig.ClientType.Hermes, 8565, bearerToken = "secret-token")
                .contains("""Authorization: "Bearer secret-token""""),
        )
    }

    @Test
    fun snippetIncludesBearerHeadersWhenTokenProvided() {
        val snippet = McpClientConfig.getSnippet(
            McpClientConfig.ClientType.Codex,
            8565,
            bearerToken = "tok",
        )
        assertTrue(snippet.contains("""http_headers = { Authorization = "Bearer tok" }"""))
    }

    @Test
    fun gooseYamlMergesStreamableHttpExtensionAndBearerHeaders() {
        val merged = McpClientConfig.mergeGooseYaml(
            """
            GOOSE_PROVIDER: anthropic
            extensions:
              developer:
                enabled: true
                type: builtin
            """.trimIndent(),
            8565,
            bearerToken = "secret-token",
        )
        assertTrue(merged.contains("GOOSE_PROVIDER: anthropic"))
        assertTrue(merged.contains("type: builtin"))
        assertTrue(merged.contains("  andy:"))
        assertTrue(merged.contains("type: streamable_http"))
        assertTrue(merged.contains("""uri: "http://127.0.0.1:8565/mcp-http""""))
        assertTrue(merged.contains("""Authorization: "Bearer secret-token""""))
        assertTrue(
            McpClientConfig.getSnippet(McpClientConfig.ClientType.Goose, 8565, bearerToken = "secret-token")
                .contains("""Authorization: "Bearer secret-token""""),
        )

        val replaced = McpClientConfig.mergeGooseYaml(merged, 9001)
        assertEquals(1, Regex("""(?m)^  andy:""").findAll(replaced).count())
        assertTrue(replaced.contains("""uri: "http://127.0.0.1:9001/mcp-http""""))
        assertFalse(replaced.contains("8565"))
    }

    @Test
    fun gooseConfigFilesIncludeUnixAndMacPaths() {
        val files = app.andy.desktop.service.agents.gooseConfigFiles(File("/test/home"))
            .map { it.invariantSeparatorsPath }
        assertTrue(files.any { it.endsWith(".config/goose/config.yaml") })
        assertTrue(files.any { it.endsWith("Library/Application Support/Block/goose/config.yaml") })
    }
}
