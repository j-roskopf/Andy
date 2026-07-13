package app.andy.desktop.service

import java.io.File
import kotlinx.serialization.json.*

object McpClientConfig {
    enum class ClientType(val label: String) {
        ClaudeCode("Claude Code"),
        Cursor("Cursor"),
        Codex("Codex"),
        ClaudeDesktop("Claude Desktop"),
        Antigravity("Antigravity"),
        VSCode("VS Code"),
        Windsurf("Windsurf")
    }

    fun getSnippet(client: ClientType, port: Int): String {
        return when (client) {
            ClientType.ClaudeCode, ClientType.Cursor, ClientType.Antigravity -> {
                """
                {
                  "mcpServers": {
                    "andy": {
                      "type": "http",
                      "url": "http://127.0.0.1:$port/mcp-http"
                    }
                  }
                }
                """.trimIndent()
            }
            ClientType.Codex -> {
                """
                [mcp_servers.andy]
                url = "http://127.0.0.1:$port/mcp"
                type = "sse"
                """.trimIndent()
            }
            ClientType.ClaudeDesktop -> {
                """
                {
                  "mcpServers": {
                    "andy": {
                      "type": "sse",
                      "url": "http://127.0.0.1:$port/mcp"
                    }
                  }
                }
                """.trimIndent()
            }
            ClientType.VSCode, ClientType.Windsurf -> {
                """
                {
                  "mcpServers": {
                    "andy": {
                      "type": "http",
                      "url": "http://127.0.0.1:$port/mcp-http"
                    }
                  }
                }
                """.trimIndent()
            }
        }
    }

    fun getConfigFile(client: ClientType): File? {
        val home = System.getProperty("user.home")
        return when (client) {
            ClientType.ClaudeCode -> File(home, ".claude.json")
            ClientType.Cursor -> File(home, ".cursor/mcp.json")
            ClientType.Codex -> File(home, ".codex/config.toml")
            // Antigravity (IDE and agy CLI) reads MCP servers from this file.
            ClientType.Antigravity -> File(home, ".gemini/config/mcp_config.json")
            ClientType.ClaudeDesktop -> {
                val osName = System.getProperty("os.name")?.lowercase().orEmpty()
                if (osName.contains("win")) {
                    val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                        ?: File(home, "AppData/Roaming").absolutePath
                    File(appData, "Claude/claude_desktop_config.json")
                } else {
                    File(home, "Library/Application Support/Claude/claude_desktop_config.json")
                }
            }
            else -> null
        }
    }

    fun writeConfig(client: ClientType, port: Int): Boolean {
        val file = getConfigFile(client) ?: return false
        try {
            file.parentFile?.mkdirs()
            val currentContent = if (file.exists()) file.readText() else ""

            // Backup
            if (file.exists() && currentContent.isNotBlank()) {
                val backupFile = File(file.absolutePath + ".bak")
                backupFile.writeText(currentContent)
            }

            val newContent = when (client) {
                ClientType.ClaudeCode, ClientType.Cursor, ClientType.ClaudeDesktop, ClientType.Antigravity -> {
                    mergeJson(client, currentContent, port)
                }
                ClientType.Codex -> {
                    mergeToml(currentContent, port)
                }
                else -> return false
            }

            file.writeText(newContent)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun mergeJson(client: ClientType, content: String, port: Int): String {
        val json = runCatching { Json.parseToJsonElement(content).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val mcpServers = (json["mcpServers"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val usesLegacySse = client == ClientType.ClaudeDesktop
        mcpServers["andy"] = buildJsonObject {
            put("type", if (usesLegacySse) "sse" else "http")
            put("url", "http://127.0.0.1:$port/${if (usesLegacySse) "mcp" else "mcp-http"}")
        }
        val updated = json.toMutableMap().apply {
            this["mcpServers"] = JsonObject(mcpServers)
        }
        val prettyJson = Json { prettyPrint = true }
        return prettyJson.encodeToString(JsonObject.serializer(), JsonObject(updated))
    }

    private fun mergeToml(content: String, port: Int): String {
        val lines = content.lines().toMutableList()
        val targetHeader = "[mcp_servers.andy]"
        val index = lines.indexOfFirst { it.trim() == targetHeader }
        val newBlock = listOf(
            "[mcp_servers.andy]",
            "url = \"http://127.0.0.1:$port/mcp\"",
            "type = \"sse\""
        )
        if (index != -1) {
            var lastIdx = index + 1
            while (lastIdx < lines.size && !lines[lastIdx].trim().startsWith("[")) {
                lastIdx++
            }
            lines.subList(index, lastIdx).clear()
            lines.addAll(index, newBlock)
        } else {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) {
                lines.add("")
            }
            lines.addAll(newBlock)
        }
        return lines.joinToString("\n")
    }
}
