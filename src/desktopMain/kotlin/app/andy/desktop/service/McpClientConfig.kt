package app.andy.desktop.service

import app.andy.desktop.service.agents.AndyPiExtensionInstaller
import java.io.File
import kotlinx.serialization.json.*

object McpClientConfig {
    enum class ClientType(val label: String) {
        ClaudeCode("Claude Code"),
        Cursor("Cursor"),
        Codex("Codex"),
        ClaudeDesktop("Claude Desktop"),
        Antigravity("Antigravity"),
        OpenCode("OpenCode"),
        Pi("Pi"),
        Hermes("Hermes"),
        OpenClaw("OpenClaw"),
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
            ClientType.OpenCode -> {
                """
                {
                  "mcp": {
                    "andy": {
                      "type": "remote",
                      "url": "http://127.0.0.1:$port/mcp-http"
                    }
                  }
                }
                """.trimIndent()
            }
            ClientType.Pi -> {
                """
                # Pi has no native MCP config file. Andy sets ANDY_MCP_URL and loads
                # ~/.andy/pi/andy-extension.ts via `pi -e` when MCP attach is enabled.
                ANDY_MCP_URL=http://127.0.0.1:$port/mcp-http
                """.trimIndent()
            }
            ClientType.Hermes -> """
                mcp_servers:
                  andy:
                    url: "http://127.0.0.1:$port/mcp-http"
            """.trimIndent()
            ClientType.OpenClaw -> """
                { "mcp": { "andy": { "transport": "streamable-http", "url": "http://127.0.0.1:$port/mcp-http" } } }
            """.trimIndent()
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
            ClientType.OpenCode -> File(home, ".config/opencode/opencode.json")
            ClientType.Hermes -> File(home, ".hermes/config.yaml")
            ClientType.OpenClaw -> File(home, ".openclaw/openclaw.json")
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

    /** Project-local OpenCode config, preferred over the user-global file when launching in a repo. */
    fun getOpenCodeProjectConfig(cwd: File?): File? =
        cwd?.takeIf { it.isDirectory }?.let { File(it, "opencode.json") }

    fun writeConfig(client: ClientType, port: Int, cwd: File? = null): Boolean {
        if (client == ClientType.Pi) {
            AndyPiExtensionInstaller.ensureInstalled()
            return true
        }
        val file = when (client) {
            ClientType.OpenCode -> getOpenCodeProjectConfig(cwd) ?: getConfigFile(client)
            else -> getConfigFile(client)
        } ?: return false
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
                ClientType.OpenCode -> mergeOpenCodeJson(currentContent, port)
                ClientType.OpenClaw -> mergeOpenClawJson(currentContent, port)
                ClientType.Hermes -> mergeHermesYaml(currentContent, port)
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

    internal fun mergeOpenCodeJson(content: String, port: Int): String {
        val json = runCatching { Json.parseToJsonElement(content).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val mcp = (json["mcp"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        mcp["andy"] = buildJsonObject {
            put("type", "remote")
            put("url", "http://127.0.0.1:$port/mcp-http")
        }
        val updated = json.toMutableMap().apply {
            this["mcp"] = JsonObject(mcp)
            if (!containsKey("${'$'}schema")) {
                this["${'$'}schema"] = JsonPrimitive("https://opencode.ai/config.json")
            }
        }
        val prettyJson = Json { prettyPrint = true }
        return prettyJson.encodeToString(JsonObject.serializer(), JsonObject(updated))
    }

    internal fun mergeOpenClawJson(content: String, port: Int): String {
        val json = runCatching { Json.parseToJsonElement(content).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val mcp = (json["mcp"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        mcp["andy"] = buildJsonObject {
            put("transport", "streamable-http")
            put("url", "http://127.0.0.1:$port/mcp-http")
        }
        val pretty = Json { prettyPrint = true }
        return pretty.encodeToString(JsonObject.serializer(), JsonObject(json.toMutableMap().apply { this["mcp"] = JsonObject(mcp) }))
    }

    internal fun mergeHermesYaml(content: String, port: Int): String {
        val lines = content.lines().toMutableList()
        val url = "    url: \"http://127.0.0.1:$port/mcp-http\""
        val root = lines.indexOfFirst { it.trim() == "mcp_servers:" }
        if (root < 0) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.addAll(listOf("mcp_servers:", "  andy:", url))
            return lines.joinToString("\n").trimEnd() + "\n"
        }
        val andy = (root + 1 until lines.size).firstOrNull { lines[it].trim() == "andy:" }
        if (andy != null) {
            var end = andy + 1
            while (end < lines.size && (lines[end].isBlank() || lines[end].startsWith("    "))) end++
            lines.subList(andy, end).clear()
        }
        val insert = (andy ?: (root + 1)).coerceAtMost(lines.size)
        lines.addAll(insert, listOf("  andy:", url))
        return lines.joinToString("\n").trimEnd() + "\n"
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
