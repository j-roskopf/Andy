package app.andy.desktop.service.hub

import app.andy.desktop.service.McpClientConfig
import app.andy.model.McpHubAuthKind
import app.andy.model.McpHubImports
import app.andy.model.McpHubRegistry
import app.andy.model.McpHubServerConfig
import app.andy.model.McpHubTransport
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Canonical hub registry at `~/.andy/mcp/servers.json`.
 */
class McpHubRegistryStore(
    private val rootDir: File = File(System.getProperty("user.home"), ".andy/mcp"),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    },
) {
    private val registryFile: File get() = File(rootDir, "servers.json")

    fun load(): McpHubRegistry {
        if (!registryFile.isFile) return McpHubRegistry()
        val text = registryFile.readText()
        if (text.isBlank()) return McpHubRegistry()
        return runCatching { json.decodeFromString(McpHubRegistry.serializer(), text) }
            .getOrElse { McpHubRegistry() }
    }

    fun save(registry: McpHubRegistry) {
        rootDir.mkdirs()
        registryFile.writeText(json.encodeToString(McpHubRegistry.serializer(), registry))
    }

    fun upsert(serverId: String, config: McpHubServerConfig): McpHubRegistry {
        val id = normalizeId(serverId) ?: error("Invalid server id")
        val current = load()
        val next = current.copy(servers = current.servers + (id to config))
        save(next)
        return next
    }

    fun setEnabled(serverId: String, enabled: Boolean): McpHubRegistry {
        val id = normalizeId(serverId) ?: error("Unknown server id")
        val current = load()
        val existing = current.servers[id] ?: error("Unknown server id: $id")
        val next = current.copy(servers = current.servers + (id to existing.copy(enabled = enabled)))
        save(next)
        return next
    }

    /** Labels shown in Settings → MCP Hub → Import from. */
    fun importSourceLabels(): List<String> = ImportSource.entries.map { it.label }

    /**
     * Merge upstream servers from a client provider config into the hub registry
     * (skips Andy's own `andy` / `emu` entries).
     */
    fun importFrom(sourceLabel: String, cwd: File? = null): Pair<McpHubRegistry, List<String>> {
        val source = ImportSource.fromLabel(sourceLabel)
            ?: error("Unknown import source: $sourceLabel")
        val file = resolveImportFile(source, cwd)
            ?: error("${source.label} MCP config not found")
        if (!file.isFile) {
            error("${source.label} MCP config not found at ${file.absolutePath}")
        }
        val parsed = when (source.format) {
            ImportFormat.JsonMcpServers -> parseJsonServerBlock(file, rootKey = "mcpServers")
            ImportFormat.JsonMcp -> parseJsonServerBlock(file, rootKey = "mcp")
            ImportFormat.TomlMcpServers -> parseTomlMcpServers(file.readText())
            ImportFormat.YamlMcpServers -> parseYamlServerBlock(file.readText(), rootKey = "mcp_servers")
            ImportFormat.YamlExtensions -> parseYamlServerBlock(file.readText(), rootKey = "extensions")
        }
        return mergeImported(source.label, parsed)
    }

    /** @deprecated Prefer [importFrom] with label `"Cursor"`. */
    fun importFromCursor(
        cursorMcpJson: File = File(System.getProperty("user.home"), ".cursor/mcp.json"),
    ): Pair<McpHubRegistry, List<String>> {
        if (!cursorMcpJson.isFile) {
            error("Cursor MCP config not found at ${cursorMcpJson.absolutePath}")
        }
        val parsed = parseJsonServerBlock(cursorMcpJson, rootKey = "mcpServers")
        return mergeImported("Cursor", parsed)
    }

    private fun mergeImported(
        sourceLabel: String,
        parsed: Map<String, McpHubServerConfig>,
    ): Pair<McpHubRegistry, List<String>> {
        val current = load()
        val merged = current.servers.toMutableMap()
        val imported = mutableListOf<String>()
        for ((id, config) in parsed) {
            if (id == "andy" || id == "emu") continue
            val existing = merged[id]
            merged[id] = if (existing != null) {
                config.copy(
                    enabled = existing.enabled,
                    auth = existing.auth.takeIf { it != McpHubAuthKind.None } ?: config.auth,
                    bearerToken = existing.bearerToken ?: config.bearerToken,
                )
            } else {
                config
            }
            imported += id
        }
        val importedFrom = (current.imports.importedFrom + sourceLabel).distinct()
        val next = McpHubRegistry(
            servers = merged,
            imports = McpHubImports(
                cursorGlobal = current.imports.cursorGlobal || sourceLabel == "Cursor",
                importedFrom = importedFrom,
            ),
        )
        save(next)
        return next to imported
    }

    private fun parseJsonServerBlock(file: File, rootKey: String): Map<String, McpHubServerConfig> {
        val root = runCatching {
            json.parseToJsonElement(file.readText()).jsonObject
        }.getOrElse { error("Invalid JSON in ${file.absolutePath}: ${it.message}") }
        val block = root[rootKey]?.jsonObject
            ?: error("${file.name} has no `$rootKey` object")
        val out = linkedMapOf<String, McpHubServerConfig>()
        for ((rawId, element) in block) {
            val id = normalizeId(rawId) ?: continue
            val obj = element as? JsonObject ?: continue
            val config = parseServerObject(obj) ?: continue
            out[id] = config
        }
        return out
    }

    companion object {
        fun normalizeId(raw: String): String? {
            val id = raw.trim().lowercase().replace(Regex("[^a-z0-9_-]+"), "-")
                .trim('-')
            return id.takeIf { it.isNotEmpty() && it.length <= 64 }
        }

        internal fun resolveImportFile(source: ImportSource, cwd: File?): File? {
            return when (source.client) {
                McpClientConfig.ClientType.OpenCode ->
                    McpClientConfig.getOpenCodeProjectConfig(cwd) ?: McpClientConfig.getConfigFile(source.client)
                McpClientConfig.ClientType.Pi ->
                    File(System.getProperty("user.home"), ".pi/mcp.json")
                else -> McpClientConfig.getConfigFile(source.client)
            }
        }

        internal fun parseServerObject(obj: JsonObject): McpHubServerConfig? {
            val command = obj["command"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: obj["uri"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val args = obj["args"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            val env = obj["env"]?.jsonObject?.mapNotNull { (k, v) ->
                v.jsonPrimitive.contentOrNull?.let { k to it }
            }?.toMap().orEmpty()
            val bearer = obj["headers"]?.jsonObject
                ?.entries
                ?.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
                ?.value?.jsonPrimitive?.contentOrNull
                ?.removePrefix("Bearer ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: obj["http_headers"]?.jsonObject
                    ?.entries
                    ?.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
                    ?.value?.jsonPrimitive?.contentOrNull
                    ?.removePrefix("Bearer ")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

            return when {
                command != null -> McpHubServerConfig(
                    transport = McpHubTransport.Stdio,
                    command = command,
                    args = args,
                    env = env,
                    enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                    auth = McpHubAuthKind.None,
                )
                url != null -> {
                    val needsOauth = url.startsWith("https://", ignoreCase = true) && bearer == null
                    McpHubServerConfig(
                        transport = McpHubTransport.Http,
                        url = url,
                        enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                        auth = when {
                            bearer != null -> McpHubAuthKind.Bearer
                            needsOauth -> McpHubAuthKind.Oauth
                            else -> McpHubAuthKind.None
                        },
                        bearerToken = bearer,
                    )
                }
                else -> null
            }
        }

        /** Parse Codex-style `[mcp_servers.name]` TOML blocks. */
        internal fun parseTomlMcpServers(content: String): Map<String, McpHubServerConfig> {
            val out = linkedMapOf<String, McpHubServerConfig>()
            val lines = content.lines()
            var i = 0
            while (i < lines.size) {
                val header = lines[i].trim()
                val match = Regex("""^\[mcp_servers\.([^\]]+)\]$""", RegexOption.IGNORE_CASE)
                    .matchEntire(header)
                if (match == null) {
                    i++
                    continue
                }
                val rawId = match.groupValues[1].trim().trim('"')
                val id = normalizeId(rawId)
                if (id == null) {
                    i++
                    continue
                }
                var end = i + 1
                while (end < lines.size && !lines[end].trim().startsWith("[")) end++
                val block = lines.subList(i + 1, end)
                val props = mutableMapOf<String, String>()
                for (line in block) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                    val eq = trimmed.indexOf('=')
                    if (eq <= 0) continue
                    val key = trimmed.substring(0, eq).trim()
                    var value = trimmed.substring(eq + 1).trim()
                    if (value.startsWith('"') && value.endsWith('"') && value.length >= 2) {
                        value = value.substring(1, value.length - 1)
                    }
                    props[key] = value
                }
                val url = props["url"] ?: props["uri"]
                val command = props["command"]
                val config = when {
                    command != null -> McpHubServerConfig(
                        transport = McpHubTransport.Stdio,
                        command = command,
                        auth = McpHubAuthKind.None,
                    )
                    url != null -> McpHubServerConfig(
                        transport = McpHubTransport.Http,
                        url = url,
                        auth = if (url.startsWith("https://", ignoreCase = true)) {
                            McpHubAuthKind.Oauth
                        } else {
                            McpHubAuthKind.None
                        },
                    )
                    else -> null
                }
                if (config != null) out[id] = config
                i = end
            }
            return out
        }

        /** Parse Hermes `mcp_servers:` / Goose `extensions:` YAML maps (2-space indent). */
        internal fun parseYamlServerBlock(content: String, rootKey: String): Map<String, McpHubServerConfig> {
            val lines = content.lines()
            val root = lines.indexOfFirst { it.trim() == "$rootKey:" }
            if (root < 0) return emptyMap()
            val out = linkedMapOf<String, McpHubServerConfig>()
            var i = root + 1
            while (i < lines.size) {
                val line = lines[i]
                if (line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("\t")) break
                val serverMatch = Regex("""^  ([A-Za-z0-9_.-]+):\s*$""").matchEntire(line)
                if (serverMatch == null) {
                    i++
                    continue
                }
                val rawId = serverMatch.groupValues[1]
                val id = normalizeId(rawId)
                if (id == null) {
                    i++
                    continue
                }
                var end = i + 1
                while (end < lines.size && (lines[end].isBlank() || lines[end].startsWith("    "))) {
                    end++
                }
                val props = mutableMapOf<String, String>()
                for (body in lines.subList(i + 1, end)) {
                    val trimmed = body.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                    val m = Regex("""^([A-Za-z0-9_]+):\s*(.*)$""").matchEntire(trimmed) ?: continue
                    var value = m.groupValues[2].trim()
                    if ((value.startsWith('"') && value.endsWith('"')) ||
                        (value.startsWith('\'') && value.endsWith('\''))
                    ) {
                        value = value.substring(1, value.length - 1)
                    }
                    props[m.groupValues[1]] = value
                }
                val url = props["url"] ?: props["uri"]
                val command = props["command"]
                val enabled = props["enabled"]?.equals("true", ignoreCase = true) ?: true
                val config = when {
                    command != null -> McpHubServerConfig(
                        transport = McpHubTransport.Stdio,
                        command = command,
                        enabled = enabled,
                        auth = McpHubAuthKind.None,
                    )
                    url != null -> McpHubServerConfig(
                        transport = McpHubTransport.Http,
                        url = url,
                        enabled = enabled,
                        auth = if (url.startsWith("https://", ignoreCase = true)) {
                            McpHubAuthKind.Oauth
                        } else {
                            McpHubAuthKind.None
                        },
                    )
                    else -> null
                }
                if (config != null) out[id] = config
                i = end
            }
            return out
        }
    }

    enum class ImportFormat {
        JsonMcpServers,
        JsonMcp,
        TomlMcpServers,
        YamlMcpServers,
        YamlExtensions,
    }

    enum class ImportSource(
        val label: String,
        val client: McpClientConfig.ClientType,
        val format: ImportFormat,
    ) {
        Cursor("Cursor", McpClientConfig.ClientType.Cursor, ImportFormat.JsonMcpServers),
        ClaudeCode("Claude Code", McpClientConfig.ClientType.ClaudeCode, ImportFormat.JsonMcpServers),
        ClaudeDesktop("Claude Desktop", McpClientConfig.ClientType.ClaudeDesktop, ImportFormat.JsonMcpServers),
        Antigravity("Antigravity", McpClientConfig.ClientType.Antigravity, ImportFormat.JsonMcpServers),
        Codex("Codex", McpClientConfig.ClientType.Codex, ImportFormat.TomlMcpServers),
        OpenCode("OpenCode", McpClientConfig.ClientType.OpenCode, ImportFormat.JsonMcp),
        OpenClaw("OpenClaw", McpClientConfig.ClientType.OpenClaw, ImportFormat.JsonMcp),
        Pi("Pi", McpClientConfig.ClientType.Pi, ImportFormat.JsonMcpServers),
        Hermes("Hermes", McpClientConfig.ClientType.Hermes, ImportFormat.YamlMcpServers),
        Goose("Goose", McpClientConfig.ClientType.Goose, ImportFormat.YamlExtensions),
        ;

        companion object {
            fun fromLabel(label: String): ImportSource? =
                entries.firstOrNull { it.label.equals(label, ignoreCase = true) }
        }
    }
}
