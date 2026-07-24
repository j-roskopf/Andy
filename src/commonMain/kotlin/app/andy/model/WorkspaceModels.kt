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
    /** KetraTerm built-in theme id (`one-dark`, `nord`, …). Legacy Andy hex themes coerce to One Dark. */
    val terminalThemeId: String = TerminalThemePreset.Default.id,
    /** Legacy per-role hex fields — retained for properties compatibility; ignored at runtime. */
    val terminalForegroundHex: String = "#ABB2BF",
    val terminalBackgroundHex: String = "#1E2127",
    val terminalSelectionFgHex: String = "#FFFFFF",
    val terminalSelectionBgHex: String = "#404859",
    val terminalFoundFgHex: String = "#1E2127",
    val terminalFoundBgHex: String = "#E5C07B",
    val terminalHyperlinkFgHex: String = "#61AFEF",
    val terminalHyperlinkBgHex: String = "#1E2127",
    val terminalUseInverseSelection: Boolean = false,
    val terminalColorPaletteId: String = "xterm",
    val terminalFontFamilyId: String = TerminalFontFamily.Default.id,
    val terminalFontSize: Float = TerminalThemePreset.DefaultFontSize,
    val workspaceSidebarExpanded: Boolean = true,
    val workspaceStatusExpanded: Boolean = false,
    val projectsIntroductionCompleted: Boolean = false,
    val liveDevicePaneWidth: Float = 720f,
    val liveControlsPaneHeight: Float = 320f,
    val appsListPaneWidth: Float = 520f,
    val appsDetailsPaneHeight: Float = 350f,
    val performanceProcessesPaneWidth: Float = 760f,
    val performanceLivePaneWidth: Float = 320f,
    val performanceTab: String = PerformanceTab.Metrics.name,
    val filesTab: String = FilesTab.Files.name,
    val tracingPresetId: String = "default",
    val tracingDurationSeconds: Int = 10,
    val tracingBufferSizeMb: Int = 64,
    val tracingPresetsPaneWidth: Float = 320f,
    val tracingLibraryPaneHeight: Float = 240f,
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
)
