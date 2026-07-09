package app.andy.model

enum class DeviceKind { Physical, Emulator, Unknown }
enum class DeviceConnectionState { Online, Offline, Unauthorized, Missing, Unknown }

data class AndroidDevice(
    val serial: String,
    val displayName: String,
    val kind: DeviceKind,
    val state: DeviceConnectionState,
    val apiLevel: String? = null,
    val abi: String? = null,
    val model: String? = null,
    val product: String? = null,
    val batteryPercent: Int? = null,
    val screenSize: String? = null,
    val storageSummary: String? = null,
)

data class SdkDiscovery(
    val sdkPath: String?,
    val adbPath: String?,
    val emulatorPath: String?,
    val sdkManagerPath: String?,
    val avdManagerPath: String?,
    val issues: List<String> = emptyList(),
) {
    val hasAdb: Boolean get() = adbPath != null
    val hasEmulatorTools: Boolean get() = emulatorPath != null && avdManagerPath != null
}

enum class SystemImageBadge(val label: String) { PlayStore("Play"), Wear("Wear"), Tv("TV"), Automotive("Auto") }

data class SystemImage(
    val packageId: String,
    val api: String,
    val variant: String,
    val abi: String,
    val displayName: String,
    val installed: Boolean,
    val sizeOnDisk: Long = 0L,
) {
    val apiLevel: Int get() = api.takeWhile { it.isDigit() }.toIntOrNull() ?: 0

    val badges: List<SystemImageBadge> get() {
        val v = variant.lowercase()
        return buildList {
            if (v.contains("playstore")) add(SystemImageBadge.PlayStore)
            if (v.contains("wear")) add(SystemImageBadge.Wear)
            if (v.contains("tv")) add(SystemImageBadge.Tv)
            if (v.contains("automotive")) add(SystemImageBadge.Automotive)
        }
    }
}

enum class AvdProfileCategory { Phone, Foldable, Tablet, Watch, Tv, Automotive, Desktop, Other }

data class AvdProfile(
    val id: String,
    val name: String,
    val oem: String?,
    val tag: String?,
    val resolution: String?,
    val density: String?,
    val category: AvdProfileCategory = AvdProfileCategory.Other,
)

enum class VirtualDeviceType { Phone, Foldable, Tablet, Watch, Tv, Automotive, Desktop, Unknown }

data class VirtualDevice(
    val name: String,
    val path: String?,
    val target: String?,
    val abi: String?,
    val running: Boolean,
    val apiLevel: Int? = null,
    val deviceType: VirtualDeviceType = VirtualDeviceType.Unknown,
    val config: Map<String, String> = emptyMap(),
)

enum class AvdCameraOption(val configValue: String) {
    None("none"),
    Emulated("emulated"),
    Webcam0("webcam0"),
}

data class AvdCreationConfig(
    val name: String,
    val profileId: String,
    val systemImagePackage: String,
    val orientation: String = "portrait",
    val ramMb: Int? = null,
    val storageMb: Int? = null,
    val cpuCores: Int? = null,
    val gpuMode: String = "auto",
    val backCamera: AvdCameraOption = AvdCameraOption.Emulated,
    val frontCamera: AvdCameraOption = AvdCameraOption.None,
    val locale: String = "",
    val hardwareKeyboard: Boolean = true,
    val startAfterCreate: Boolean = false,
)

data class EmulatorSnapshot(
    val name: String,
    val avdName: String,
    val source: String = "",
    val size: String? = null,
    val createdTime: String? = null,
    val screenshotPath: String? = null,
    val compatible: Boolean = true,
)

enum class LogLevel { Verbose, Debug, Info, Warn, Error, Fatal, Silent }

data class LogcatEntry(
    val time: String,
    val pid: String?,
    val tid: String?,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

enum class IntentMode { Activity, DeepLink, Service, Broadcast }
enum class ExtraType { StringValue, BooleanValue, IntValue, LongValue, FloatValue }

data class IntentExtra(
    val key: String,
    val type: ExtraType,
    val value: String,
)

data class IntentDraft(
    val mode: IntentMode = IntentMode.DeepLink,
    val action: String = "android.intent.action.VIEW",
    val component: String = "",
    val dataUri: String = "",
    val categories: List<String> = listOf("android.intent.category.DEFAULT"),
    val flags: List<String> = emptyList(),
    val extras: List<IntentExtra> = emptyList(),
)

data class DeviceFile(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val permissions: String?,
    val modified: String?,
)

data class HostFileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedMillis: Long,
    val extension: String = "",
    val languageHint: String = "",
)

data class HostFileDocument(
    val path: String,
    val content: String,
    val modifiedMillis: Long,
    val sizeBytes: Long,
    val charset: String = "UTF-8",
    val languageHint: String = "",
)

sealed interface HostFileSaveResult {
    data class Saved(val modifiedMillis: Long) : HostFileSaveResult
    data class Conflict(val currentModifiedMillis: Long) : HostFileSaveResult
    data class Failed(val message: String) : HostFileSaveResult
}

enum class HostSearchMode { FileName, Content, Combined }

enum class HostSearchMatchKind { FileName, Content }

data class HostSearchResult(
    val path: String,
    val root: String,
    val kind: HostSearchMatchKind,
    val lineNumber: Int? = null,
    val column: Int? = null,
    val preview: String = "",
)

data class HostIndexStatus(
    val root: String,
    val indexedFiles: Int,
    val indexedBytes: Long,
    val indexing: Boolean,
    val message: String,
    val updatedAtMillis: Long,
)

data class AndroidApp(
    val packageName: String,
    val label: String? = null,
    val system: Boolean = false,
    val enabled: Boolean = true,
    val versionName: String? = null,
    val versionCode: String? = null,
)

data class AndroidPermission(
    val name: String,
    val granted: Boolean?,
)

data class AndroidActivity(
    val name: String,
    val exported: Boolean?,
)

data class ProxyRule(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val urlPattern: String,
    val method: String? = null,
    val statusCode: Int? = null,
    val setHeaders: Map<String, String> = emptyMap(),
    val removeHeaders: List<String> = emptyList(),
    val responseBody: String? = null,
)

fun ProxyRule.matches(method: String, url: String): Boolean {
    if (!enabled) return false
    if (urlPattern.isNotBlank()) {
        if ("*" in urlPattern) {
            val regex = Regex.escape(urlPattern).replace("\\*", ".*")
            if (!Regex(regex, RegexOption.IGNORE_CASE).matches(url)) return false
        } else {
            if (!url.contains(urlPattern, ignoreCase = true)) return false
        }
    }
    if (!this.method.isNullOrBlank() && !this.method.equals(method, ignoreCase = true)) return false
    return true
}

data class NetworkExchange(
    val id: String,
    val startedAtMillis: Long,
    val completedAtMillis: Long?,
    val method: String,
    val url: String,
    val statusCode: Int?,
    val contentType: String?,
    val sizeBytes: Long?,
    val durationMillis: Long?,
    val requestHeaders: Map<String, String>,
    val responseHeaders: Map<String, String>,
    val requestBodyPreview: String?,
    val responseBodyPreview: String?,
    val error: String?,
    val tlsStatus: String?,
    val matchedRuleId: String?,
    val flowId: String,
)

data class NetworkRouteDiagnostics(
    val expectedProxy: String,
    val configuredProxy: String?,
    val proxyConfigured: Boolean,
    val vpnActive: Boolean,
    val vpnName: String? = null,
    val routeUsesVpn: Boolean = false,
    val routeSummary: String? = null,
    val hostProxyActive: Boolean = false,
    val hostProxySummary: String? = null,
    val hostUpstreamProxy: String? = null,
    val hostProxyBypassLooksSafe: Boolean = true,
    val hostVpnActive: Boolean = false,
    val hostVpnSummary: String? = null,
    val hostRouteSummary: String? = null,
    val issues: List<String> = emptyList(),
) {
    val hasBlockingIssue: Boolean get() = issues.isNotEmpty()
}

data class PerformanceSample(
    val timestampMillis: Long,
    val cpuPercent: Float?,
    val memoryMb: Float?,
    val fps: Float?,
    val batteryPercent: Int?,
    val thermalStatus: String?,
    val networkRxKbps: Float? = null,
    val networkTxKbps: Float? = null,
    val processes: List<ProcessMetric> = emptyList(),
    val frameRenderTimes: List<FrameRenderMetric> = emptyList(),
)

data class ProcessMetric(
    val pid: String,
    val name: String,
    val cpuPercent: Float?,
    val memoryMb: Float?,
)

data class FrameRenderMetric(
    val label: String,
    val millis: Float,
    val vsyncGapMillis: Float? = null,
)

data class AccessibilityNode(
    val id: String,
    val className: String?,
    val packageName: String? = null,
    val resourceId: String?,
    val text: String?,
    val contentDescription: String?,
    val hint: String? = null,
    val bounds: String?,
    val clickable: Boolean,
    val longClickable: Boolean = false,
    val focusable: Boolean,
    val focused: Boolean = false,
    val enabled: Boolean,
    val selected: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val scrollable: Boolean = false,
    val password: Boolean = false,
    val visible: Boolean = true,
    val attributes: Map<String, String> = emptyMap(),
    val children: List<AccessibilityNode> = emptyList(),
)

data class WorkspaceState(
    val selectedSdkPath: String? = null,
    val selectedDeviceSerial: String? = null,
    val savedIntents: List<IntentDraft> = emptyList(),
    val logSearch: String = "",
    val enabledLogLevels: Set<LogLevel> = setOf(LogLevel.Debug, LogLevel.Info, LogLevel.Warn, LogLevel.Error, LogLevel.Fatal),
    val proxyRules: List<ProxyRule> = emptyList(),
    val proxyPort: Int = 9099,
    val proxyStartOnLaunch: Boolean = false,
    val mcpServerEnabled: Boolean = false,
    val mcpServerPort: Int = 8565,
    val liveDevicePaneWidth: Float = 720f,
    val liveControlsPaneHeight: Float = 230f,
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
