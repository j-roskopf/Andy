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

    fun getSnippet(client: ClientType, port: Int, bearerToken: String? = null): String {
        val token = bearerToken?.trim()?.takeIf { it.isNotEmpty() }
        val headersJson = token?.let {
            """,
                      "headers": {
                        "Authorization": "Bearer $it"
                      }"""
        }.orEmpty()
        val headersYaml = token?.let {
            """
                    headers:
                      Authorization: "Bearer $it""""
        }.orEmpty()
        val headersToml = token?.let {
            """
                http_headers = { Authorization = "Bearer $it" }"""
        }.orEmpty()
        val piTokenHint = token?.let {
            "\n                # With Network Access on, pass Authorization: Bearer <token> to /mcp-http"
        }.orEmpty()
        return when (client) {
            ClientType.ClaudeCode, ClientType.Cursor, ClientType.Antigravity -> {
                """
                {
                  "mcpServers": {
                    "andy": {
                      "type": "http",
                      "url": "http://127.0.0.1:$port/mcp-http"$headersJson
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
                      "url": "http://127.0.0.1:$port/mcp-http"$headersJson
                    }
                  }
                }
                """.trimIndent()
            }
            ClientType.Pi -> {
                """
                # Pi has no native MCP config file. Andy sets ANDY_MCP_URL and loads
                # ~/.andy/pi/andy-extension.ts via `pi -e` when MCP attach is enabled.
                ANDY_MCP_URL=http://127.0.0.1:$port/mcp-http$piTokenHint
                """.trimIndent()
            }
            ClientType.Hermes -> """
                mcp_servers:
                  andy:
                    url: "http://127.0.0.1:$port/mcp-http"$headersYaml
            """.trimIndent()
            ClientType.OpenClaw -> {
                val headersObj = token?.let {
                    """, "headers": { "Authorization": "Bearer $it" }"""
                }.orEmpty()
                """
                { "mcp": { "andy": { "transport": "streamable-http", "url": "http://127.0.0.1:$port/mcp-http"$headersObj } } }
            """.trimIndent()
            }
            ClientType.Codex -> {
                """
                [mcp_servers.andy]
                url = "http://127.0.0.1:$port/mcp-http"$headersToml
                """.trimIndent()
            }
            ClientType.ClaudeDesktop -> {
                """
                {
                  "mcpServers": {
                    "andy": {
                      "type": "sse",
                      "url": "http://127.0.0.1:$port/mcp"$headersJson
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
                      "url": "http://127.0.0.1:$port/mcp-http"$headersJson
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

    fun writeConfig(client: ClientType, port: Int, cwd: File? = null, bearerToken: String? = null): Boolean {
        if (client == ClientType.Pi) {
            AndyPiExtensionInstaller.ensureInstalled()
            return writePiMcpConfig(port, bearerToken)
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
                    mergeJson(client, currentContent, port, bearerToken)
                }
                ClientType.OpenCode -> mergeOpenCodeJson(currentContent, port, bearerToken)
                    ?: return false
                ClientType.OpenClaw -> mergeOpenClawJson(currentContent, port, bearerToken)
                ClientType.Hermes -> mergeHermesYaml(currentContent, port, bearerToken)
                ClientType.Codex -> {
                    mergeToml(currentContent, port, bearerToken)
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

    private fun mergeJson(client: ClientType, content: String, port: Int, bearerToken: String? = null): String {
        val json = runCatching { Json.parseToJsonElement(content).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val mcpServers = (json["mcpServers"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val usesLegacySse = client == ClientType.ClaudeDesktop
        mcpServers["andy"] = buildJsonObject {
            put("type", if (usesLegacySse) "sse" else "http")
            put("url", "http://127.0.0.1:$port/${if (usesLegacySse) "mcp" else "mcp-http"}")
            putMcpAuthHeaders(bearerToken)
        }
        val updated = json.toMutableMap().apply {
            this["mcpServers"] = JsonObject(mcpServers)
        }
        val prettyJson = Json { prettyPrint = true }
        return prettyJson.encodeToString(JsonObject.serializer(), JsonObject(updated))
    }

    internal fun mergeOpenCodeJson(content: String, port: Int, bearerToken: String? = null): String? {
        val json = if (content.isBlank()) {
            JsonObject(emptyMap())
        } else {
            runCatching { Json.parseToJsonElement(content).jsonObject }.getOrNull() ?: return null
        }
        val mcp = (json["mcp"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        mcp["andy"] = buildJsonObject {
            put("type", "remote")
            put("url", "http://127.0.0.1:$port/mcp-http")
            putMcpAuthHeaders(bearerToken)
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

    internal fun writePiMcpConfig(port: Int, bearerToken: String? = null): Boolean {
        val file = File(System.getProperty("user.home"), ".pi/mcp.json")
        return try {
            file.parentFile?.mkdirs()
            val currentContent = if (file.exists()) file.readText() else ""
            if (file.exists() && currentContent.isNotBlank()) {
                File(file.absolutePath + ".bak").writeText(currentContent)
            }
            val merged = mergePiMcpJson(currentContent, port, bearerToken) ?: return false
            file.writeText(merged)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    internal fun mergePiMcpJson(content: String, port: Int, bearerToken: String? = null): String? {
        val json = if (content.isBlank()) {
            JsonObject(emptyMap())
        } else {
            runCatching { Json.parseToJsonElement(content).jsonObject }.getOrNull() ?: return null
        }
        val servers = (json["mcpServers"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        servers["andy"] = buildJsonObject {
            put("type", "streamable-http")
            put("url", "http://127.0.0.1:$port/mcp-http")
            put("lifecycle", "eager")
            putMcpAuthHeaders(bearerToken)
        }
        val updated = json.toMutableMap().apply {
            this["mcpServers"] = JsonObject(servers)
        }
        val prettyJson = Json { prettyPrint = true }
        return prettyJson.encodeToString(JsonObject.serializer(), JsonObject(updated))
    }

    internal fun mergeOpenClawJson(content: String, port: Int, bearerToken: String? = null): String {
        val json = runCatching { Json.parseToJsonElement(content).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val mcp = (json["mcp"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        mcp["andy"] = buildJsonObject {
            put("transport", "streamable-http")
            put("url", "http://127.0.0.1:$port/mcp-http")
            putMcpAuthHeaders(bearerToken)
        }
        val pretty = Json { prettyPrint = true }
        return pretty.encodeToString(JsonObject.serializer(), JsonObject(json.toMutableMap().apply { this["mcp"] = JsonObject(mcp) }))
    }

    internal fun mergeHermesYaml(content: String, port: Int, bearerToken: String? = null): String {
        val lines = content.lines().toMutableList()
        val token = bearerToken?.trim()?.takeIf { it.isNotEmpty() }
        val block = buildList {
            add("  andy:")
            add("    url: \"http://127.0.0.1:$port/mcp-http\"")
            if (token != null) {
                add("    headers:")
                add("      Authorization: \"Bearer $token\"")
            }
        }
        val root = lines.indexOfFirst { it.trim() == "mcp_servers:" }
        if (root < 0) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add("mcp_servers:")
            lines.addAll(block)
            return lines.joinToString("\n").trimEnd() + "\n"
        }
        val andy = (root + 1 until lines.size).firstOrNull { lines[it].trim() == "andy:" }
        if (andy != null) {
            var end = andy + 1
            while (end < lines.size && (lines[end].isBlank() || lines[end].startsWith("    ") || lines[end].startsWith("      "))) {
                end++
            }
            // Also consume continuation lines that are more indented under andy
            // (headers block uses 4–6 spaces). Stop at next top-level mcp server key.
            lines.subList(andy, end).clear()
        }
        val insert = (andy ?: (root + 1)).coerceAtMost(lines.size)
        lines.addAll(insert, block)
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    private fun mergeToml(content: String, port: Int, bearerToken: String? = null): String {
        val lines = content.lines().toMutableList()
        val targetHeader = "[mcp_servers.andy]"
        val index = lines.indexOfFirst { it.trim() == targetHeader }
        val newBlock = buildList {
            add("[mcp_servers.andy]")
            add("url = \"http://127.0.0.1:$port/mcp-http\"")
            bearerToken?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
                add("http_headers = { Authorization = \"Bearer $token\" }")
            }
        }
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

    private fun kotlinx.serialization.json.JsonObjectBuilder.putMcpAuthHeaders(bearerToken: String?) {
        val token = bearerToken?.trim()?.takeIf { it.isNotEmpty() } ?: return
        put("headers", buildJsonObject {
            put("Authorization", "Bearer $token")
        })
    }
}
