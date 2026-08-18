package app.andy.model

import kotlinx.serialization.Serializable

enum class AgentNotificationTiming { Always, BackgroundOnly }

/** How chat follow-ups are delivered while an agent run is in progress. */
enum class AgentMessageDeliveryMode(val label: String) {
    Immediate("Send immediately"),
    Queue("Queue messages"),
}

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
    /** Friendly display names keyed by serial/udid, shown in device lists and target pickers (§C.5). */
    val deviceLabels: Map<String, String> = emptyMap(),
    /** Freeform notes keyed by serial/udid (§C.5). */
    val deviceNotes: Map<String, String> = emptyMap(),
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
    /** When true, MCP/HTTP binds to 0.0.0.0 so other devices on the user's network can reach Andy. */
    val networkAccessEnabled: Boolean = false,
    /**
     * When Network Access is on, only accept Tailscale CGNAT peers (`100.64/10`) plus loopback
     * (for Tailscale Serve / local vendor CLIs). Default on — blocks random LAN devices.
     */
    val networkAccessTailscaleOnly: Boolean = true,
    /** Shared bearer token required for network (and loopback reverse-proxy) access when enabled. */
    val networkAccessToken: String = "",
    /** Web Push VAPID public key (URL-safe base64); empty until first push use. */
    val vapidPublicKey: String = "",
    /** Web Push VAPID private key (URL-safe base64); empty until first push use. */
    val vapidPrivateKey: String = "",
    val tintId: String = "andy-blue",
    val surfaceModeId: String = "tinted",
    val editorSyntaxThemeId: String = EditorSyntaxTheme.Andy.id,
    /** Terminal theme id (`one-dark`, `nord`, …). Legacy Andy hex themes coerce to One Dark. */
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
    val projectListPaneVisible: Boolean = true,
    val projectListPaneWidth: Float = 300f,
    val liveDevicePaneWidth: Float = 720f,
    val liveControlsPaneHeight: Float = 320f,
    val appsListPaneWidth: Float = 520f,
    val appsDetailsPaneHeight: Float = 350f,
    val performanceProcessesPaneWidth: Float = 760f,
    val performanceLivePaneWidth: Float = 320f,
    val rightDockPaneWidth: Float = 460f,
    val bottomDockPaneHeight: Float = 300f,
    val performanceTab: String = PerformanceTab.Metrics.name,
    val filesTab: String = FilesTab.Files.name,
    val logcatTab: String = LogcatTab.Stream.name,
    val tracingPresetId: String = "default",
    val tracingDurationSeconds: Int = 10,
    val tracingBufferSizeMb: Int = 64,
    val retentionCleanupEnabled: Boolean = true,
    val retentionCompressArchiveAfterDays: Int = 30,
    val retentionPermanentDeleteAfterDays: Int = 90,
    val tracingPresetsPaneWidth: Float = 320f,
    val tracingLibraryPaneHeight: Float = 240f,
    val designDevicePaneWidth: Float = 820f,
    val inspectorTreePaneWidth: Float = 560f,
    val hostFileRoots: List<String> = emptyList(),
    val lastHostFilePath: String? = null,
    val recentHostFiles: List<String> = emptyList(),
    val hostFileTreePaneWidth: Float = 320f,
    val hostFileSearchPaneWidth: Float = 430f,
    val selectedPackage: String? = null,
    /** Last project shown in the header action runner / Projects page. */
    val lastActionProjectId: String? = null,
    /** Last action shown in the header action runner. */
    val lastActionId: String? = null,
    val agentOsNotificationsEnabled: Boolean = true,
    val agentNotificationSoundEnabled: Boolean = true,
    val agentIconBadgeEnabled: Boolean = true,
    /** Encoded [app.andy.ui.components.KeyCombo] that toggles voice dictation from any chat composer. */
    val voiceDictationShortcut: String? = null,
    /** When false (default), quitting Andy kills all `tmux -L andy` agent sessions. */
    val keepAgentSessionsOnShutdown: Boolean = false,
    val agentNotificationTiming: AgentNotificationTiming = AgentNotificationTiming.BackgroundOnly,
    val agentNotificationSoundId: String = AgentNotificationSound.Chime.id,
    /** Expand tool, thinking, and grouped activity rows when a transcript opens. */
    val agentTranscriptAutoExpandActivity: Boolean = false,
    /** Merge consecutive thinking/tool steps into one block between user/assistant messages. */
    val agentTranscriptCollapseActivityBlocks: Boolean = false,
    /** When [AgentMessageDeliveryMode.Queue], follow-ups wait in a queue until the current run finishes. */
    val agentMessageDeliveryMode: AgentMessageDeliveryMode = AgentMessageDeliveryMode.Immediate,
    /** Names of [app.andy.AndyDestination] entries hidden from the sidebar. Settings is never included. */
    val disabledDestinations: Set<String> = emptySet(),
    /** Project ids whose chat lists are collapsed in the Projects sidebar. */
    val collapsedProjectChatIds: Set<String> = emptySet(),
    val ollamaBaseUrl: String = DefaultOllamaBaseUrl,
    val ollamaBearerToken: String = "",
    val lmStudioBaseUrl: String = DefaultLmStudioBaseUrl,
    val lmStudioBearerToken: String = "",
)
