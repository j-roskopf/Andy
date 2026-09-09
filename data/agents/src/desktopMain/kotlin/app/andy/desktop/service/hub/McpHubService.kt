package app.andy.desktop.service.hub

import app.andy.model.McpHubAuthKind
import app.andy.model.McpHubServerConfig
import app.andy.model.McpHubServerConnectionState
import app.andy.model.McpHubServerStatus
import app.andy.model.McpHubStatus
import app.andy.model.McpHubTransport
import app.andy.service.CommandResult
import app.andy.desktop.service.McpClientConfig
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Andy MCP Hub: registry + OAuth vault + upstream sessions + tool federation.
 */
class McpHubService(
    private val registryStore: McpHubRegistryStore = McpHubRegistryStore(),
    private val oauthStore: McpHubOAuthStore = McpHubOAuthStore(),
) {
    private val mutex = Mutex()
    private val sessions = ConcurrentHashMap<String, McpUpstreamHttpSession>()
    private val toolCache = ConcurrentHashMap<String, List<McpUpstreamTool>>()
    private val statusMessages = ConcurrentHashMap<String, Pair<McpHubServerConnectionState, String?>>()

    fun status(): McpHubStatus {
        val registry = registryStore.load()
        val federated = mutableListOf<String>()
        val servers = registry.servers.map { (id, config) ->
            val tools = toolCache[id].orEmpty()
            federated += tools.map { federatedToolName(id, it.name) }
            val (state, message) = resolveState(id, config)
            McpHubServerStatus(
                id = id,
                enabled = config.enabled,
                transport = config.transport,
                url = config.url,
                command = config.command,
                auth = config.auth,
                state = state,
                toolCount = tools.size,
                message = message,
            )
        }.sortedBy { it.id }
        return McpHubStatus(servers = servers, federatedToolNames = federated.sorted())
    }

    fun federatedToolNames(): List<String> = status().federatedToolNames

    /**
     * Only enabled HTTP upstreams are actually federated through Andy (stdio is imported but
     * not yet proxied). Requiring attach only when one of these is present avoids rewriting
     * provider configs to a hub that cannot serve a disabled/stdio/failed upstream.
     */
    private fun federatableServers(): List<McpHubServerStatus> =
        status().servers.filter { it.enabled && it.transport == McpHubTransport.Http }

    /** Ids of upstreams Andy actually federates; used to strip duplicates from client configs. */
    fun federatedServerIds(): List<String> = federatableServers().map { it.id }

    fun requiresClientAttach(): Boolean = federatableServers().isNotEmpty()

    /**
     * Point primary agent providers at Andy and strip hub upstream ids from their configs
     * so Cursor/Codex/Claude/Antigravity use the hub instead of talking to upstreams directly.
     */
    fun syncProviderClients(port: Int, bearerToken: String? = null, cwd: java.io.File? = null): CommandResult {
        if (!requiresClientAttach()) {
            return CommandResult.success("Hub has no enabled HTTP upstreams; skipped client sync")
        }
        val stripIds = federatedServerIds()
        val results = linkedMapOf<String, Boolean>()
        for (client in HubSyncedClients) {
            results[client.label] = McpClientConfig.writeConfigAsSoleEntry(
                client = client,
                port = port,
                stripServerIds = stripIds,
                cwd = cwd,
                bearerToken = bearerToken,
            )
        }
        val failed = results.filterValues { !it }.keys
        val ok = results.filterValues { it }.keys
        return if (failed.isEmpty()) {
            CommandResult.success(
                "Synced Andy hub into ${ok.joinToString(", ")} (stripped: ${stripIds.joinToString(", ")})",
            )
        } else {
            CommandResult(
                exitCode = if (ok.isEmpty()) 1 else 0,
                stdout = "Synced: ${ok.joinToString(", ").ifBlank { "(none)" }}",
                stderr = "Failed: ${failed.joinToString(", ")}",
            )
        }
    }

    suspend fun importFromCursor(): CommandResult = importFrom("Cursor")

    suspend fun importFrom(sourceLabel: String): CommandResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val (_, imported) = registryStore.importFrom(sourceLabel)
                refreshAllLocked()
                CommandResult.success(
                    "Imported ${imported.size} server(s) from $sourceLabel: " +
                        imported.joinToString(", ").ifBlank { "(none new)" },
                )
            }.getOrElse {
                CommandResult(exitCode = 1, stdout = "", stderr = it.message ?: it.toString())
            }
        }
    }

    fun importSources(): List<String> = registryStore.importSourceLabels()

    suspend fun setServerEnabled(serverId: String, enabled: Boolean): CommandResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                registryStore.setEnabled(serverId, enabled)
                if (!enabled) {
                    sessions.remove(serverId)?.close()
                    toolCache.remove(serverId)
                    statusMessages[serverId] = McpHubServerConnectionState.Disabled to null
                } else {
                    connectLocked(serverId)
                }
                CommandResult.success(if (enabled) "Enabled $serverId" else "Disabled $serverId")
            }.getOrElse {
                CommandResult(exitCode = 1, stdout = "", stderr = it.message ?: it.toString())
            }
        }
    }

    suspend fun upsertHttpServer(
        serverId: String,
        url: String,
        auth: McpHubAuthKind = McpHubAuthKind.Oauth,
        enabled: Boolean = true,
        bearerToken: String? = null,
    ): CommandResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val id = McpHubRegistryStore.normalizeId(serverId) ?: error("Invalid server id")
                val trimmedUrl = url.trim()
                require(trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://")) {
                    "URL must start with http:// or https://"
                }
                registryStore.upsert(
                    id,
                    McpHubServerConfig(
                        transport = McpHubTransport.Http,
                        url = trimmedUrl,
                        enabled = enabled,
                        auth = auth,
                        bearerToken = bearerToken?.trim()?.takeIf { it.isNotEmpty() },
                    ),
                )
                if (enabled) connectLocked(id)
                CommandResult.success("Saved $id")
            }.getOrElse {
                CommandResult(exitCode = 1, stdout = "", stderr = it.message ?: it.toString())
            }
        }
    }

    suspend fun signIn(serverId: String): CommandResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val id = McpHubRegistryStore.normalizeId(serverId) ?: error("Invalid server id")
                val config = registryStore.load().servers[id] ?: error("Unknown server: $id")
                require(config.transport == McpHubTransport.Http) { "OAuth sign-in is only for HTTP upstreams" }
                val url = config.url?.trim().orEmpty()
                require(url.isNotEmpty()) { "Server $id has no URL" }

                // Prefer reusing Cursor CLI tokens when present.
                if (oauthStore.importFromCursorAuth(id)) {
                    connectLocked(id)
                    return@runCatching CommandResult.success("Imported Cursor auth for $id and connected")
                }

                statusMessages[id] = McpHubServerConnectionState.NeedsAuth to "Waiting for browser sign-in…"
                val existing = oauthStore.load(id)
                val tokens = McpHubOAuthLogin.login(
                    mcpUrl = url,
                    existingClientId = existing?.clientId,
                )
                oauthStore.save(id, tokens)
                connectLocked(id)
                CommandResult.success("Signed in to $id")
            }.getOrElse {
                val id = McpHubRegistryStore.normalizeId(serverId)
                if (id != null) {
                    statusMessages[id] = McpHubServerConnectionState.NeedsAuth to (it.message ?: it.toString())
                }
                CommandResult(exitCode = 1, stdout = "", stderr = it.message ?: it.toString())
            }
        }
    }

    suspend fun reconnect(serverId: String? = null): CommandResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                if (serverId.isNullOrBlank()) {
                    refreshAllLocked()
                    CommandResult.success("Reconnected enabled hub upstreams")
                } else {
                    val id = McpHubRegistryStore.normalizeId(serverId) ?: error("Invalid server id")
                    connectLocked(id)
                    CommandResult.success("Reconnected $id")
                }
            }.getOrElse {
                CommandResult(exitCode = 1, stdout = "", stderr = it.message ?: it.toString())
            }
        }
    }

    /** Best-effort connect on MCP server start. */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        mutex.withLock { refreshAllLocked() }
    }

    fun registerFederatedTools(mcpServer: Server) {
        // hub.status is always available even with zero upstreams.
        mcpServer.addTool(
            name = "hub_status",
            description = "Show Andy MCP Hub upstream servers and federated tool counts",
            inputSchema = ToolSchema(),
        ) { _ ->
            val snap = status()
            val text = buildString {
                if (snap.servers.isEmpty()) {
                    appendLine("No upstream MCP servers in hub registry. Import from a provider or add one in Settings → MCP → Hub.")
                } else {
                    snap.servers.forEach { s ->
                        appendLine(
                            "${s.id}: ${s.state.name.lowercase()}" +
                                (s.toolCount.takeIf { it > 0 }?.let { " ($it tools)" } ?: "") +
                                (s.message?.let { " — $it" } ?: ""),
                        )
                    }
                }
                if (snap.federatedToolNames.isNotEmpty()) {
                    appendLine()
                    appendLine("Federated tools:")
                    snap.federatedToolNames.forEach { appendLine("  $it") }
                }
            }
            CallToolResult(content = listOf(TextContent(text = text.trim())))
        }

        for ((serverId, tools) in toolCache) {
            for (tool in tools) {
                val federatedName = federatedToolName(serverId, tool.name)
                val schema = tool.inputSchema.toToolSchema()
                val description = buildString {
                    append("[hub:$serverId] ")
                    append(tool.description?.takeIf { it.isNotBlank() } ?: tool.name)
                }
                mcpServer.addTool(federatedName, description, schema) { request ->
                    callFederated(serverId, tool.name, request.arguments.orEmpty())
                }
            }
        }
    }

    private suspend fun callFederated(
        serverId: String,
        toolName: String,
        arguments: Map<String, JsonElement>,
    ): CallToolResult {
        return try {
            val session = sessions[serverId]
                ?: run {
                    mutex.withLock { connectLocked(serverId) }
                    sessions[serverId]
                }
                ?: return CallToolResult(
                    content = listOf(TextContent(text = "Hub upstream '$serverId' is not connected")),
                    isError = true,
                )
            val result = withContext(Dispatchers.IO) {
                session.callTool(toolName, arguments)
            }
            val isError = result["isError"]?.jsonPrimitive?.contentOrNull == "true" ||
                result.containsKey("code") && result.containsKey("message")
            val content = result["content"]?.jsonArray
            if (content != null) {
                val texts = content.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                        "text" -> obj["text"]?.jsonPrimitive?.contentOrNull
                        else -> obj.toString()
                    }
                }
                CallToolResult(
                    content = listOf(TextContent(text = texts.joinToString("\n").ifBlank { result.toString() })),
                    isError = isError,
                )
            } else {
                CallToolResult(
                    content = listOf(TextContent(text = result.toString())),
                    isError = isError,
                )
            }
        } catch (e: Exception) {
            CallToolResult(
                content = listOf(TextContent(text = "Hub call failed ($serverId/$toolName): ${e.message ?: e}")),
                isError = true,
            )
        }
    }

    private fun refreshAllLocked() {
        val registry = registryStore.load()
        val enabledIds = registry.servers.filter { it.value.enabled }.keys
        sessions.keys.filter { it !in enabledIds }.forEach { id ->
            sessions.remove(id)?.close()
            toolCache.remove(id)
        }
        for (id in enabledIds) {
            runCatching { connectLocked(id) }
        }
    }

    private fun connectLocked(serverId: String) {
        val id = McpHubRegistryStore.normalizeId(serverId) ?: error("Invalid server id")
        val config = registryStore.load().servers[id] ?: error("Unknown server: $id")
        if (!config.enabled) {
            statusMessages[id] = McpHubServerConnectionState.Disabled to null
            sessions.remove(id)?.close()
            toolCache.remove(id)
            return
        }
        if (config.transport != McpHubTransport.Http) {
            statusMessages[id] = McpHubServerConnectionState.Error to
                "stdio upstreams are not federated yet; configure HTTP MCP or wait for a later Andy release"
            return
        }
        val url = config.url?.trim().orEmpty()
        if (url.isEmpty()) {
            statusMessages[id] = McpHubServerConnectionState.Error to "Missing URL"
            return
        }

        statusMessages[id] = McpHubServerConnectionState.Connecting to null
        sessions.remove(id)?.close()

        try {
            val token = resolveAccessToken(id, config, url)
            if (config.auth == McpHubAuthKind.Oauth && token == null) {
                statusMessages[id] = McpHubServerConnectionState.NeedsAuth to "Sign in required"
                toolCache.remove(id)
                return
            }
            val session = McpUpstreamHttpSession(
                url = url,
                accessTokenProvider = { resolveAccessToken(id, config, url) },
            )
            val tools = session.listTools()
            sessions[id] = session
            toolCache[id] = tools
            statusMessages[id] = McpHubServerConnectionState.Connected to null
        } catch (e: Exception) {
            sessions.remove(id)?.close()
            toolCache.remove(id)
            val msg = e.message ?: e.toString()
            val needsAuth = msg.contains("401") || msg.contains("403") ||
                msg.contains("auth", ignoreCase = true) ||
                msg.contains("Unauthorized", ignoreCase = true)
            statusMessages[id] = if (needsAuth && config.auth == McpHubAuthKind.Oauth) {
                McpHubServerConnectionState.NeedsAuth to msg
            } else {
                McpHubServerConnectionState.Error to msg
            }
        }
    }

    private fun resolveAccessToken(serverId: String, config: McpHubServerConfig, url: String): String? {
        when (config.auth) {
            McpHubAuthKind.None -> return null
            McpHubAuthKind.Bearer -> return config.bearerToken?.trim()?.takeIf { it.isNotEmpty() }
            McpHubAuthKind.Oauth -> {
                oauthStore.accessToken(serverId)?.let { return it }
                val stored = oauthStore.load(serverId) ?: return null
                // Try refresh once if we have a refresh token.
                if (!stored.refreshToken.isNullOrBlank() && !stored.clientId.isNullOrBlank()) {
                    return runCatching {
                        val refreshed = McpHubOAuthLogin.refresh(url, stored)
                        oauthStore.save(serverId, refreshed)
                        refreshed.accessToken
                    }.getOrNull()
                }
                return stored.accessToken.takeIf { it.isNotBlank() }
            }
        }
    }

    private fun resolveState(
        id: String,
        config: McpHubServerConfig,
    ): Pair<McpHubServerConnectionState, String?> {
        if (!config.enabled) return McpHubServerConnectionState.Disabled to null
        statusMessages[id]?.let { return it }
        if (config.transport != McpHubTransport.Http) {
            return McpHubServerConnectionState.Error to "stdio not federated yet"
        }
        if (config.auth == McpHubAuthKind.Oauth && oauthStore.load(id) == null && config.bearerToken.isNullOrBlank()) {
            return McpHubServerConnectionState.NeedsAuth to "Sign in required"
        }
        return McpHubServerConnectionState.Connecting to "Not connected yet — reconnect from Settings"
    }

    companion object {
        /** Providers expected to consume hub-federated tools via Andy. */
        val HubSyncedClients: List<McpClientConfig.ClientType> = listOf(
            McpClientConfig.ClientType.Cursor,
            McpClientConfig.ClientType.Codex,
            McpClientConfig.ClientType.ClaudeCode,
            McpClientConfig.ClientType.ClaudeDesktop,
            McpClientConfig.ClientType.Antigravity,
        )

        fun federatedToolName(serverId: String, toolName: String): String = "${serverId}__$toolName"

        private fun JsonObject?.toToolSchema(): ToolSchema {
            if (this == null) return ToolSchema()
            val properties = this["properties"] as? JsonObject
            val required = this["required"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            return ToolSchema(
                properties = properties,
                required = required,
            )
        }
    }
}
