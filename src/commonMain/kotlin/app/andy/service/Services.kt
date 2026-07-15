package app.andy.service

import app.andy.AndyDestination
import app.andy.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

interface DeviceService {
    suspend fun discoverSdk(): SdkDiscovery
    suspend fun listDevices(): List<AndroidDevice>
    suspend fun shell(serial: String, command: List<String>): CommandResult
    suspend fun pair(host: String, port: Int, code: String): CommandResult
    suspend fun connect(host: String, port: Int): CommandResult
    suspend fun disconnect(serial: String): CommandResult
    suspend fun listMdnsServices(): List<MdnsService>
    suspend fun mdnsAvailable(): Boolean
    suspend fun generatePairingQr(content: String): ByteArray?
}

interface AvdService {
    suspend fun listSystemImages(): List<SystemImage>
    suspend fun listProfiles(): List<AvdProfile>
    suspend fun listVirtualDevices(): List<VirtualDevice>
    suspend fun createVirtualDevice(name: String, profileId: String, systemImagePackage: String): CommandResult
    suspend fun createVirtualDevice(config: AvdCreationConfig): CommandResult
    suspend fun startVirtualDevice(name: String): CommandResult
    suspend fun coldBootVirtualDevice(name: String): CommandResult
    suspend fun stopVirtualDevice(name: String): CommandResult
    suspend fun wipeVirtualDevice(name: String): CommandResult
    suspend fun deleteVirtualDevice(name: String): CommandResult
    suspend fun cloneVirtualDevice(sourceName: String, newName: String): CommandResult
    suspend fun installSystemImage(packageId: String): CommandResult
    suspend fun uninstallSystemImage(packageId: String): CommandResult
    suspend fun listSnapshots(avdName: String): List<EmulatorSnapshot>
    suspend fun saveSnapshot(avdName: String, snapshotName: String): CommandResult
    suspend fun restoreSnapshot(avdName: String, snapshotName: String): CommandResult
    suspend fun deleteSnapshot(avdName: String, snapshotName: String): CommandResult
    suspend fun renameSnapshot(avdName: String, oldName: String, newName: String): CommandResult
}

interface MirrorEngine {
    /**
     * The presentation session. This is deliberately low-frequency state: native and web
     * renderers own video frames, while Compose observes only their verified capabilities
     * and telemetry.
     */
    val session: StateFlow<MirrorSession?>

    /**
     * Legacy CPU frames. These remain available for screenshots, bug capture and deterministic
     * tests, but must not be used as the normal accelerated presentation path.
     */
    val frames: Flow<MirrorFrame>

    /**
     * Annex-B H.264 access units from the live stream when available. Bug capture prefers this
     * bitstream (full stream FPS, no re-encode) and falls back to [frames] when empty.
     */
    val encodedVideo: Flow<EncodedVideoAccessUnit>
        get() = emptyFlow()

    val status: Flow<String>
    suspend fun connect(serial: String, config: MirrorVideoConfig = MirrorVideoConfig()): CommandResult
    suspend fun disconnect()
    suspend fun sendInput(input: MirrorInput): CommandResult
    suspend fun screenshot(serial: String): ByteArray?
}

interface LogcatService {
    fun stream(serial: String, filter: LogcatFilter): Flow<List<LogcatEntry>>
    suspend fun snapshot(serial: String, filter: LogcatFilter, limit: Int): List<LogcatEntry>
    suspend fun clear(serial: String)
}

interface IntentService {
    fun buildCommand(draft: IntentDraft): List<String>
    suspend fun send(serial: String, draft: IntentDraft): CommandResult
}

interface AppService {
    suspend fun listApps(serial: String): List<AndroidApp>
    suspend fun getAppDetails(serial: String, packageName: String): AndroidAppDetails
    suspend fun launch(serial: String, packageName: String): CommandResult
    suspend fun launchActivity(serial: String, packageName: String, activityName: String): CommandResult
    suspend fun stop(serial: String, packageName: String): CommandResult
    suspend fun clearData(serial: String, packageName: String): CommandResult
    suspend fun resetPermissions(serial: String, packageName: String): CommandResult
    suspend fun uninstall(serial: String, packageName: String): CommandResult
    suspend fun install(serial: String, apkPath: String, replace: Boolean = false): CommandResult
    suspend fun listPermissions(serial: String, packageName: String): List<AndroidPermission>
    suspend fun listActivities(serial: String, packageName: String): List<AndroidActivity>
    suspend fun getIcon(serial: String, packageName: String): ByteArray?
}

interface FileService {
    suspend fun list(serial: String, path: String): List<DeviceFile>
    suspend fun pull(serial: String, remotePath: String, localPath: String): CommandResult
    suspend fun push(serial: String, localPath: String, remotePath: String): CommandResult
    suspend fun delete(serial: String, remotePath: String): CommandResult
}

interface HostFileService {
    suspend fun list(path: String): List<HostFileEntry>
    suspend fun read(path: String): HostFileDocument
    suspend fun save(path: String, content: String, expectedModifiedMillis: Long): HostFileSaveResult
    suspend fun indexStatus(root: String): HostIndexStatus
    fun indexRoot(root: String): Flow<HostIndexStatus>
    suspend fun search(query: String, mode: HostSearchMode, roots: List<String>, limit: Int = 200): List<HostSearchResult>
}

interface ProxyService {
    val exchanges: Flow<List<NetworkExchange>>
    val status: Flow<String>
    val warnings: Flow<List<ProxyWarning>>
    val clientConnectionCount: Flow<Int>
    suspend fun detectMitmproxy(): CommandResult
    suspend fun ensureCertificateAuthority(): CommandResult
    suspend fun certificateAuthorityPath(): String
    suspend fun start(port: Int, rules: List<ProxyRule>, options: ProxyStartOptions = ProxyStartOptions()): CommandResult
    suspend fun updateRules(rules: List<ProxyRule>): CommandResult
    suspend fun clearTraffic(): CommandResult
    suspend fun stop(): CommandResult
    suspend fun resolveDeviceProxyHost(serial: String): String
    suspend fun configureDeviceProxy(serial: String, host: String, port: Int): CommandResult
    suspend fun clearDeviceProxy(serial: String): CommandResult
    suspend fun diagnoseDeviceProxyRoute(serial: String, host: String, port: Int): NetworkRouteDiagnostics
    suspend fun openVpnSettings(serial: String): CommandResult
    suspend fun prepareUserCertificateInstall(serial: String): CommandResult
    suspend fun installSystemCertificateAuthority(serial: String): CommandResult
    suspend fun activatePersistedCertificateAuthority(serial: String): CommandResult
    suspend fun isCertificateInstalled(serial: String): Boolean
    suspend fun isDeviceProxyConfigured(serial: String, host: String, port: Int): Boolean
}

interface MetricsService {
    fun stream(serial: String, packageName: String?): Flow<PerformanceSample>
}

interface AccessibilityService {
    suspend fun dump(serial: String): AccessibilityNode?
}

interface BugService {
    val status: Flow<BugCaptureStatus>
    suspend fun startCapture(serial: String, device: AndroidDevice?)
    suspend fun stopCapture()
    fun recordAction(kind: String, label: String, detail: String? = null)
    suspend fun saveBug(draft: BugCaptureDraft, device: AndroidDevice?): BugReport
    suspend fun listBugs(): List<BugReport>
    suspend fun loadBug(id: String): BugReport?
    suspend fun loadBugLog(id: String): String
    suspend fun deleteBug(id: String): Boolean
    suspend fun exportBug(id: String): String?
    fun playbackFrames(id: String, startFrameIndex: Int = 0): Flow<MirrorFrame>
    suspend fun bugVideoFrameCount(id: String): Int
    suspend fun loadBugVideoFrame(id: String, frameIndex: Int): MirrorFrame?
}

interface ArtifactService {
    suspend fun saveScreenshot(serial: String, suggestedName: String): CommandResult
    suspend fun saveBugReport(serial: String, suggestedName: String): CommandResult
}

interface WorkspaceStore {
    suspend fun load(): WorkspaceState
    suspend fun save(state: WorkspaceState)
}

enum class AgentAttentionKind { Completed, NeedsInput, Failed }

data class AgentAttentionEvent(val taskId: String, val projectId: String?, val title: String, val kind: AgentAttentionKind)
data class OpenAgentTaskRequest(val taskId: String, val projectId: String?)

interface OsNotificationService { fun show(event: AgentAttentionEvent) }
interface NotificationSoundPlayer { fun play(soundId: String) }
interface AgentAttentionCoordinator {
    fun start()
    fun onTasksChanged(tasks: List<AgentTask>)
}

object NoopOsNotificationService : OsNotificationService { override fun show(event: AgentAttentionEvent) = Unit }
object NoopNotificationSoundPlayer : NotificationSoundPlayer { override fun play(soundId: String) = Unit }

interface ActionConfigStore {
    suspend fun load(): ActionsConfig
    suspend fun save(config: ActionsConfig)
}

interface ActionRunService {
    val running: StateFlow<List<RunningAction>>
    /** Opens an interactive login shell rooted at the project's context directory. */
    fun openShell(project: ActionProject): String
    fun run(project: ActionProject, action: ProjectAction): String
    fun stop(runId: String)
    fun clear(runId: String)
}

interface AgentRunService {
    val tasks: StateFlow<List<AgentTask>>
    val cliStatuses: StateFlow<List<AgentCliStatus>>
    /** Most recent provider-reported account limits, keyed by provider. */
    val providerQuotas: StateFlow<Map<AgentKind, AgentProviderQuota>>
    /** Explicit consent for provider-local account sources; disabled by default. */
    val quotaAccess: StateFlow<AgentQuotaAccess>
    /** Reads account information from installed provider CLIs without starting an agent task. */
    suspend fun refreshProviderQuotas()
    fun setQuotaAccess(agent: AgentKind, enabled: Boolean)
    /** Last-used launch settings for each provider, used to prefill the new-task composer. */
    val providerDefaults: StateFlow<Map<AgentKind, AgentProviderDefaults>>
    /** Provider used most recently for a chat, used as the next composer selection. */
    val lastUsedAgent: StateFlow<AgentKind?>
    /**
     * Skills this provider will load for a task rooted at [directory]. The provider's
     * native global and workspace skill locations are discovered independently, so
     * slash completion never offers skills from a different provider's convention.
     */
    fun skills(agent: AgentKind, directory: String?): StateFlow<List<AgentSkill>>
    /** Re-scans the provider's skill locations after an external installation. */
    fun refreshSkills(agent: AgentKind, directory: String?)
    suspend fun createAndStart(draft: AgentTaskDraft): AgentTask
    /** Starts a fresh writable provider run from a completed plan-mode task. */
    suspend fun startImplementation(taskId: String)
    fun stop(taskId: String)
    /** Starts the failed task over with its original prompt and configuration. */
    suspend fun retry(taskId: String)
    fun resume(
        taskId: String,
        followUp: String,
        imagePaths: List<String> = emptyList(),
        skills: List<AgentSkill> = emptyList(),
    )
    /** Supplies an answer to an agent-issued decision checkpoint and continues the task. */
    fun respondToUserInput(taskId: String, requestId: String, answers: Map<String, String>)
    /** Holds a follow-up until the active run completes successfully. */
    fun queueFollowUp(
        taskId: String,
        followUp: String,
        imagePaths: List<String> = emptyList(),
        skills: List<AgentSkill> = emptyList(),
    )
    /** Removes an unsent follow-up at [queueIndex]. */
    fun removeQueuedFollowUp(taskId: String, queueIndex: Int)
    /** Updates Andy's persisted task goal; providers receive it with subsequent prompts. */
    fun updateGoal(taskId: String, goal: String?)
    suspend fun delete(taskId: String, removeWorktree: Boolean)
    /** Clears the unread indicator for a finished chat (e.g. when opened). */
    fun markRead(taskId: String)
    /** Marks a chat unread so list/dock badges show again. */
    fun markUnread(taskId: String)
    fun events(taskId: String): StateFlow<List<AgentEvent>>
    fun interactiveResumeCommand(taskId: String): String?
    suspend fun openInTerminal(taskId: String): CommandResult
    suspend fun openSkill(path: String): CommandResult
    suspend fun worktreeDiffSummary(taskId: String): String?
    suspend fun changeSummary(taskId: String): AgentChangeSummary?
    suspend fun fileDiff(taskId: String, relativePath: String): AgentFileDiff?
    suspend fun refreshCliStatuses()
    suspend fun isGitRepo(dir: String): Boolean
}

interface ProjectWorkflowService {
    val projects: StateFlow<Map<String, ProjectWorkflowState>>
    suspend fun ensureProject(projectId: String)
    suspend fun updateScratchpad(projectId: String, text: String)
    suspend fun updateProfile(projectId: String, kind: ProjectTaskKind, profile: ProjectAgentProfile)
    suspend fun saveSpec(draft: ProjectSpecDraft): String
    suspend fun runSpec(taskId: String, revisionRequest: String? = null)
    suspend fun saveBuildPair(draft: ProjectBuildPairDraft): String
    suspend fun startBuildPair(buildTaskId: String)
    fun pauseBuildPair(buildTaskId: String)
    fun stopBuildPair(buildTaskId: String)
    suspend fun resumeBuildPair(buildTaskId: String)
    /** Adds a freeform fix thread to a completed workflow without auto-running its gates. */
    suspend fun startRecoveryFollowUp(buildTaskId: String, followUp: String): String?
    /** Runs one explicit cumulative review after manual recovery testing is finished. */
    suspend fun startRecoveryReview(buildTaskId: String): String?
    suspend fun deleteTask(taskId: String, cascade: Boolean = false)
    suspend fun deleteProject(projectId: String)
}

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0

    companion object {
        fun success(stdout: String = "") = CommandResult(0, stdout, "")
        fun failure(message: String, exitCode: Int = 1) = CommandResult(exitCode, "", message)
    }
}

data class LogcatFilter(
    val search: String = "",
    val levels: Set<LogLevel> = setOf(LogLevel.Debug, LogLevel.Info, LogLevel.Warn, LogLevel.Error, LogLevel.Fatal),
    val packageName: String? = null,
    val buffers: Set<String> = setOf("main", "system", "crash"),
)

data class MirrorFrame(
    val width: Int,
    val height: Int,
    val argb: IntArray,
    val frameNumber: Long = 0,
    val decodedFps: Float? = null,
    val displayedFps: Float? = null,
)

data class MirrorVideoConfig(
    val maxSize: Int = 1080,
    val bitRate: Int = 8_000_000,
    val maxFps: Int = 60,
    val codec: String = "h264",
    val rendererMode: MirrorRendererMode = MirrorRendererMode.Auto,
)

/** One Annex-B H.264 access unit from the device stream, for bug capture remux. */
data class EncodedVideoAccessUnit(
    val timestampMillis: Long,
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedVideoAccessUnit) return false
        return timestampMillis == other.timestampMillis &&
            width == other.width &&
            height == other.height &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = timestampMillis.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}

/** User-visible renderer policy. Accelerated never silently falls back. */
enum class MirrorRendererMode {
    Auto,
    Accelerated,
    Legacy,
}

/** The verified class of the currently active decoder/presenter pair. */
enum class MirrorBackendKind {
    NativeHardware,
    BrowserHardware,
    LegacyCpu,
    Unavailable,
}

data class MirrorBackend(
    val kind: MirrorBackendKind = MirrorBackendKind.Unavailable,
    val decoder: String = "Unavailable",
    val renderer: String = "Unavailable",
    val fallbackReason: String? = null,
) {
    val isHardwareBacked: Boolean
        get() = kind == MirrorBackendKind.NativeHardware || kind == MirrorBackendKind.BrowserHardware
}

/**
 * Presentation telemetry produced by the renderer. Latency is host-input to present and does
 * not require clock synchronization with Android.
 */
data class MirrorStats(
    val displayedFps: Float = 0f,
    val decodedFps: Float = 0f,
    val droppedFrames: Long = 0,
    val framesPresented: Long = 0,
    val p95InputToPresentMillis: Float? = null,
)

/**
 * The cross-platform contract for a live mirror. Surface ownership stays with the renderer;
 * Kotlin receives this state only, never a continuous GPU frame stream.
 */
data class MirrorSession(
    val serial: String,
    val requestedMode: MirrorRendererMode,
    val backend: MirrorBackend,
    val stats: MirrorStats = MirrorStats(),
    val width: Int = 0,
    val height: Int = 0,
    val failureReason: String? = null,
)

sealed interface MirrorInput {
    data class Touch(val action: MirrorTouchAction, val x: Int, val y: Int) : MirrorInput
    data class Tap(val x: Int, val y: Int) : MirrorInput
    data class Swipe(val startX: Int, val startY: Int, val endX: Int, val endY: Int, val durationMillis: Int) : MirrorInput
    data class Key(val keyCode: Int) : MirrorInput
    data class Text(val value: String) : MirrorInput
    data object Back : MirrorInput
    data object Home : MirrorInput
    data object Recents : MirrorInput
    data object Power : MirrorInput
}

interface McpServerService {
    val status: Flow<String>            // "stopped" | "running on 127.0.0.1:8565" | "error: ..."
    val running: Flow<Boolean>
    suspend fun start(port: Int): CommandResult
    suspend fun stop(): CommandResult

    fun getSnippet(clientName: String, port: Int): String
    fun getClients(): List<String>
    fun isAutoWriteSupported(clientName: String): Boolean
    fun writeConfig(clientName: String, port: Int): Boolean
    fun getToolNames(): List<String>
}

enum class MirrorTouchAction { Down, Move, Up }

enum class AndyPlatform { Desktop, Web }

enum class WebConnectionTransport { None, WebSocket, WebUsb }

data class WebConnectionState(
    val transport: WebConnectionTransport = WebConnectionTransport.None,
    val status: String = "Disconnected",
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null,
)

internal fun WebConnectionState.shouldAutoConnectWebSocket(): Boolean =
    transport == WebConnectionTransport.None && !connecting && !connected

interface WebConnectionService {
    val state: StateFlow<WebConnectionState>
    suspend fun connectWebSocket(): CommandResult
    suspend fun requestWebUsb(): CommandResult
    suspend fun retry(): CommandResult
    suspend fun forgetWebUsbAuthorization(): CommandResult
}

data class WebStorageState(
    val persisted: Boolean = false,
    val usageBytes: Long = 0,
    val quotaBytes: Long = 0,
    val resourceOrigins: List<String> = emptyList(),
)

interface WebStorageService {
    val state: StateFlow<WebStorageState>
    suspend fun refresh(): WebStorageState
    suspend fun requestPersistence(): Boolean
    suspend fun clearAll(): CommandResult
}

data class WebServices(
    val connection: WebConnectionService,
    val storage: WebStorageService,
)

data class PlatformCapabilities(
    val platform: AndyPlatform,
    val destinations: List<AndyDestination>,
    val avdManagement: Boolean,
    val wifiPairing: Boolean,
    val hostAutomation: Boolean,
    val proxy: Boolean,
    val mcp: Boolean,
    val updates: Boolean,
    /** True when Live may offer Auto/GPU renderer controls (Mac Metal or browser WebCodecs). */
    val acceleratedMirror: Boolean = false,
) {
    companion object {
        val Desktop = PlatformCapabilities(
            platform = AndyPlatform.Desktop,
            destinations = AndyDestination.entries,
            avdManagement = true,
            wifiPairing = true,
            hostAutomation = true,
            proxy = true,
            mcp = true,
            updates = true,
            // Overridden at service creation from the packaged native bridge (Mac only today).
            acceleratedMirror = false,
        )

        val Web = PlatformCapabilities(
            platform = AndyPlatform.Web,
            destinations = listOf(
                AndyDestination.Devices,
                AndyDestination.Live,
                AndyDestination.Apps,
                AndyDestination.Logcat,
                AndyDestination.Intents,
                AndyDestination.Files,
                AndyDestination.Controls,
                AndyDestination.Performance,
                AndyDestination.Design,
                AndyDestination.Accessibility,
                AndyDestination.Bugs,
                AndyDestination.Settings,
            ),
            avdManagement = false,
            wifiPairing = false,
            hostAutomation = false,
            proxy = false,
            mcp = false,
            updates = false,
            // Browser path: WebCodecs + WebGL when the runtime verifies hardware.
            acceleratedMirror = true,
        )
    }
}

data class AndyServices(
    val devices: DeviceService,
    val avd: AvdService,
    val mirror: MirrorEngine,
    val logcat: LogcatService,
    val intents: IntentService,
    val apps: AppService,
    val files: FileService,
    val hostFiles: HostFileService,
    val proxy: ProxyService,
    val metrics: MetricsService,
    val accessibility: AccessibilityService,
    val bugs: BugService,
    val artifacts: ArtifactService,
    val workspaceStore: WorkspaceStore,
    val updates: AppUpdateService,
    val mcp: McpServerService,
    val actionConfig: ActionConfigStore,
    val actionRuns: ActionRunService,
    val agentRuns: AgentRunService,
    val projectWorkflows: ProjectWorkflowService,
    val notificationSounds: NotificationSoundPlayer = NoopNotificationSoundPlayer,
    val capabilities: PlatformCapabilities = PlatformCapabilities.Desktop,
    val web: WebServices? = null,
)
