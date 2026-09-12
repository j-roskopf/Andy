package app.andy.desktop.service.hub

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class McpHubOAuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val tokenType: String = "bearer",
    val expiresAtEpochMs: Long? = null,
    val scope: String? = null,
    val clientId: String? = null,
)

/**
 * Per-server OAuth / bearer vault under `~/.andy/mcp/oauth/<server>.json`.
 */
class McpHubOAuthStore(
    private val rootDir: File = File(System.getProperty("user.home"), ".andy/mcp"),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    },
) {
    private fun fileFor(serverId: String): File {
        val id = McpHubRegistryStore.normalizeId(serverId) ?: error("Invalid server id")
        return File(File(rootDir, "oauth"), "$id.json")
    }

    fun load(serverId: String): McpHubOAuthTokens? {
        val file = fileFor(serverId)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(McpHubOAuthTokens.serializer(), file.readText())
        }.getOrNull()
    }

    fun save(serverId: String, tokens: McpHubOAuthTokens) {
        val file = fileFor(serverId)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(McpHubOAuthTokens.serializer(), tokens))
    }

    fun clear(serverId: String) {
        fileFor(serverId).delete()
    }

    fun accessToken(serverId: String, nowMs: Long = System.currentTimeMillis()): String? {
        val tokens = load(serverId) ?: return null
        val expires = tokens.expiresAtEpochMs
        if (expires != null && expires <= nowMs + 30_000L) return null
        return tokens.accessToken.takeIf { it.isNotBlank() }
    }

    /**
     * Best-effort import of Cursor CLI tokens from project mcp-auth.json files.
     * Prefers `~/.cursor/projects/Users-<user>/mcp-auth.json` when present.
     */
    fun importFromCursorAuth(
        serverId: String,
        cursorProjectsDir: File = File(System.getProperty("user.home"), ".cursor/projects"),
    ): Boolean {
        val id = McpHubRegistryStore.normalizeId(serverId) ?: return false
        val candidates = mutableListOf<File>()
        val homeProject = File(cursorProjectsDir, "Users-${System.getProperty("user.name")}/mcp-auth.json")
        if (homeProject.isFile) candidates += homeProject
        if (cursorProjectsDir.isDirectory) {
            cursorProjectsDir.listFiles()
                ?.mapNotNull { dir -> File(dir, "mcp-auth.json").takeIf { it.isFile } }
                ?.sortedByDescending { it.lastModified() }
                ?.let { candidates += it }
        }
        for (file in candidates.distinctBy { it.absolutePath }) {
            val tokens = parseCursorMcpAuth(file, id) ?: continue
            save(id, tokens)
            return true
        }
        return false
    }

    companion object {
        private val cursorAuthJson = Json { ignoreUnknownKeys = true }

        internal fun parseCursorMcpAuth(file: File, serverId: String): McpHubOAuthTokens? {
            val root = runCatching {
                cursorAuthJson.parseToJsonElement(file.readText()).jsonObject
            }.getOrNull() ?: return null
            val serverObj = root[serverId]?.jsonObject ?: return null
            val tokens = serverObj["tokens"]?.jsonObject ?: return null
            val access = tokens["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return null
            val refresh = tokens["refresh_token"]?.jsonPrimitive?.contentOrNull
            val expiresIn = tokens["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            val clientId = serverObj["clientInfo"]?.jsonObject
                ?.get("client_id")?.jsonPrimitive?.contentOrNull
            return McpHubOAuthTokens(
                accessToken = access,
                refreshToken = refresh,
                tokenType = tokens["token_type"]?.jsonPrimitive?.contentOrNull ?: "bearer",
                expiresAtEpochMs = expiresIn?.let { System.currentTimeMillis() + it * 1000L },
                scope = tokens["scope"]?.jsonPrimitive?.contentOrNull,
                clientId = clientId,
            )
        }
    }
}
