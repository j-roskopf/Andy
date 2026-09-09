package app.andy.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How Andy connects to an upstream MCP server. */
@Serializable
enum class McpHubTransport {
    @SerialName("http")
    Http,

    @SerialName("stdio")
    Stdio,
}

/** How Andy authenticates to an upstream MCP server. */
@Serializable
enum class McpHubAuthKind {
    @SerialName("none")
    None,

    @SerialName("oauth")
    Oauth,

    @SerialName("bearer")
    Bearer,
}

@Serializable
data class McpHubServerConfig(
    val transport: McpHubTransport = McpHubTransport.Http,
    val url: String? = null,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val auth: McpHubAuthKind = McpHubAuthKind.None,
    /** Optional static bearer (auth=Bearer). Prefer OAuth vault when auth=Oauth. */
    val bearerToken: String? = null,
)

@Serializable
data class McpHubImports(
    val cursorGlobal: Boolean = false,
    /** Provider labels that have been imported into the hub (e.g. Cursor, Codex). */
    val importedFrom: List<String> = emptyList(),
)

@Serializable
data class McpHubRegistry(
    val servers: Map<String, McpHubServerConfig> = emptyMap(),
    val imports: McpHubImports = McpHubImports(),
)

enum class McpHubServerConnectionState {
    Disabled,
    NeedsAuth,
    Connecting,
    Connected,
    Error,
}

data class McpHubServerStatus(
    val id: String,
    val enabled: Boolean,
    val transport: McpHubTransport,
    val url: String? = null,
    val command: String? = null,
    val auth: McpHubAuthKind,
    val state: McpHubServerConnectionState,
    val toolCount: Int = 0,
    val message: String? = null,
)

data class McpHubStatus(
    val servers: List<McpHubServerStatus> = emptyList(),
    val federatedToolNames: List<String> = emptyList(),
)
