package app.andy.model

import kotlinx.serialization.Serializable

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
    val workspaceSidebarExpanded: Boolean = true,
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
)
