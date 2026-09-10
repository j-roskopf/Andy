package app.andy.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** OS identifiers accepted in `andy-plugin.toml`. */
@Serializable
enum class PluginPlatform {
    @SerialName("linux")
    Linux,

    @SerialName("macos")
    Macos,

    @SerialName("windows")
    Windows,
}

/** Where an action may appear in Andy UI. */
@Serializable
enum class PluginActionContext {
    @SerialName("global")
    Global,

    @SerialName("workspace")
    Workspace,

    @SerialName("tab")
    Tab,

    @SerialName("pane")
    Pane,

    @SerialName("selection")
    Selection,
}

/**
 * Terminal-dock placement for `[[panes]]`.
 * `popup` is intentionally absent until Andy has a modal terminal.
 */
@Serializable
enum class PluginPanePlacement {
    @SerialName("overlay")
    Overlay,

    @SerialName("split")
    Split,

    @SerialName("tab")
    Tab,

    @SerialName("zoomed")
    Zoomed,
}

@Serializable
enum class PluginSourceKind {
    @SerialName("local")
    Local,

    @SerialName("github")
    Github,
}

@Serializable
data class PluginManifestBuild(
    val command: List<String>,
    val platforms: List<PluginPlatform>? = null,
)

@Serializable
data class PluginManifestStartup(
    val command: List<String>,
    val platforms: List<PluginPlatform>? = null,
)

@Serializable
data class PluginManifestAction(
    val id: String,
    val title: String,
    val command: List<String>,
    val description: String? = null,
    val contexts: List<PluginActionContext> = emptyList(),
    val platforms: List<PluginPlatform>? = null,
)

@Serializable
data class PluginManifestEvent(
    val on: String,
    val command: List<String>,
    val platforms: List<PluginPlatform>? = null,
)

@Serializable
data class PluginManifestPane(
    val id: String,
    val title: String,
    val command: List<String>,
    val description: String? = null,
    val placement: PluginPanePlacement = PluginPanePlacement.Split,
    val platforms: List<PluginPlatform>? = null,
)

@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val minAndyVersion: String,
    val description: String? = null,
    val platforms: List<PluginPlatform>? = null,
    val build: List<PluginManifestBuild> = emptyList(),
    val startup: List<PluginManifestStartup> = emptyList(),
    val actions: List<PluginManifestAction> = emptyList(),
    val events: List<PluginManifestEvent> = emptyList(),
    val panes: List<PluginManifestPane> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class PluginSourceInfo(
    val kind: PluginSourceKind,
    val path: String,
    val githubRef: String? = null,
    val githubSpec: String? = null,
)

@Serializable
data class InstalledPluginRecord(
    val pluginId: String,
    val enabled: Boolean = true,
    val source: PluginSourceInfo,
    val manifestPath: String,
    val pluginRoot: String,
)

@Serializable
data class PluginRegistryFile(
    val plugins: List<InstalledPluginRecord> = emptyList(),
)

data class InstalledPluginInfo(
    val pluginId: String,
    val name: String,
    val version: String,
    val minAndyVersion: String,
    val description: String?,
    val enabled: Boolean,
    val platforms: List<PluginPlatform>?,
    val build: List<PluginManifestBuild>,
    val startup: List<PluginManifestStartup>,
    val actions: List<PluginManifestAction>,
    val events: List<PluginManifestEvent>,
    val panes: List<PluginManifestPane>,
    val source: PluginSourceInfo,
    val manifestPath: String,
    val pluginRoot: String,
    val warnings: List<String> = emptyList(),
    val configDir: String,
    val stateDir: String,
)

@Serializable
data class PluginInvocationContext(
    val workspaceId: String? = null,
    val workspaceLabel: String? = null,
    val workspaceCwd: String? = null,
    val worktreePath: String? = null,
    val worktreeBranch: String? = null,
    val worktreeLabel: String? = null,
    val tabId: String? = null,
    val tabLabel: String? = null,
    val focusedPaneId: String? = null,
    val focusedPaneCwd: String? = null,
    val focusedPaneAgent: String? = null,
    val focusedPaneStatus: String? = null,
    val selectedText: String? = null,
    val invocationSource: String? = null,
    val correlationId: String? = null,
    val clickedUrl: String? = null,
    val linkHandlerId: String? = null,
)

@Serializable
data class PluginEventEnvelope(
    val event: String,
    val data: Map<String, String> = emptyMap(),
)

data class PluginCommandLog(
    val id: String,
    val pluginId: String,
    val kind: String,
    val command: List<String>,
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
)

data class PluginPaneSession(
    val paneId: String,
    val pluginId: String,
    val entrypoint: String,
    val title: String,
    val runId: String,
    val placement: PluginPanePlacement,
)

/** Ask the GUI to reveal a plugin terminal run in a dock. */
data class PluginPaneOpenRequest(
    val runId: String,
    val title: String,
    val placement: PluginPanePlacement,
)

/**
 * Event names valid in `[[events]] on = "…"`.
 * High-volume events are intentionally excluded.
 */
object PluginHookEvents {
    val ALL: Set<String> = setOf(
        "workspace.created",
        "workspace.updated",
        "workspace.closed",
        "workspace.renamed",
        "workspace.moved",
        "workspace.reordered",
        "workspace.focused",
        "worktree.created",
        "worktree.opened",
        "worktree.removed",
        "tab.created",
        "tab.closed",
        "tab.renamed",
        "tab.moved",
        "tab.focused",
        "pane.created",
        "pane.closed",
        "pane.focused",
        "pane.moved",
        "pane.exited",
        "pane.agent_detected",
        "pane.agent_status_changed",
    )

    fun isKnown(name: String): Boolean = name in ALL
}

/** Map Andy [AgentStatus] (+ seen) onto the plugin wire enum. */
fun AgentStatus.toPluginWireStatus(seen: Boolean = false): String = when (this) {
    AgentStatus.Working -> "working"
    AgentStatus.Blocked -> "blocked"
    AgentStatus.Done -> if (seen) "idle" else "done"
    AgentStatus.Error -> "unknown"
}
