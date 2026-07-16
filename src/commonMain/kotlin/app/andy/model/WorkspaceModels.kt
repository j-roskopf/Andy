package app.andy.model

import kotlinx.serialization.Serializable

enum class AgentNotificationTiming { Always, BackgroundOnly }

enum class AgentNotificationSound(val id: String, val label: String) {
    Chime("chime", "Chime"),
    Ping("ping", "Ping"),
    Soft("soft", "Soft"),
}

/** Desktop host-file editor highlighting schemes (RSyntaxTextArea). */
enum class EditorSyntaxTheme(val id: String, val label: String) {
    Andy("andy", "Andy"),
    Dark("dark", "Dark"),
    Monokai("monokai", "Monokai"),
    Druid("druid", "Druid"),
    Idea("idea", "IntelliJ"),
    Eclipse("eclipse", "Eclipse"),
    Vs("vs", "Visual Studio"),
    Default("default", "Default"),
    DefaultAlt("default-alt", "Default alt");

    companion object {
        fun fromId(id: String): EditorSyntaxTheme = entries.firstOrNull { it.id == id } ?: Andy
    }
}

@Serializable
data class WorkspaceState(
    val selectedSdkPath: String? = null,
    val selectedDeviceSerial: String? = null,
    val savedIntents: List<IntentDraft> = emptyList(),
    val logSearch: String = "",
    val enabledLogLevels: Set<LogLevel> = setOf(LogLevel.Debug, LogLevel.Info, LogLevel.Warn, LogLevel.Error, LogLevel.Fatal),
    val proxyRules: List<ProxyRule> = emptyList(),
    val pairedWifiDevices: List<PairedWifiDevice> = emptyList(),
    val proxyPort: Int = 9099,
    val proxyStartOnLaunch: Boolean = false,
    val proxySslInsecure: Boolean = false,
    val proxyUpstreamTrustedCaPath: String? = null,
    val mcpServerEnabled: Boolean = false,
    val mcpServerPort: Int = 8565,
    val tintId: String = "andy-blue",
    val surfaceModeId: String = "tinted",
    val editorSyntaxThemeId: String = EditorSyntaxTheme.Andy.id,
    val workspaceSidebarExpanded: Boolean = true,
    val projectsIntroductionCompleted: Boolean = false,
    val liveDevicePaneWidth: Float = 720f,
    val liveControlsPaneHeight: Float = 320f,
    val appsListPaneWidth: Float = 520f,
    val appsDetailsPaneHeight: Float = 350f,
    val performanceProcessesPaneWidth: Float = 760f,
    val performanceLivePaneWidth: Float = 320f,
    val designDevicePaneWidth: Float = 820f,
    val accessibilityTreePaneWidth: Float = 560f,
    val hostFileRoots: List<String> = emptyList(),
    val lastHostFilePath: String? = null,
    val recentHostFiles: List<String> = emptyList(),
    val hostFileTreePaneWidth: Float = 320f,
    val hostFileSearchPaneWidth: Float = 430f,
    val selectedPackage: String? = null,
    val agentOsNotificationsEnabled: Boolean = true,
    val agentNotificationSoundEnabled: Boolean = true,
    val agentIconBadgeEnabled: Boolean = true,
    val agentNotificationTiming: AgentNotificationTiming = AgentNotificationTiming.BackgroundOnly,
    val agentNotificationSoundId: String = AgentNotificationSound.Chime.id,
    /** Collapse consecutive tool calls into one expandable line in agent transcripts. */
    val compactToolCalls: Boolean = true,
)
