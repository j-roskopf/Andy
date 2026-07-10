package app.andy.service

import app.andy.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

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
    val frames: Flow<MirrorFrame>
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
    suspend fun launch(serial: String, packageName: String): CommandResult
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

interface ActionConfigStore {
    suspend fun load(): ActionsConfig
    suspend fun save(config: ActionsConfig)
}

interface ActionRunService {
    val running: StateFlow<List<RunningAction>>
    fun run(project: ActionProject, action: ProjectAction): String
    fun stop(runId: String)
    fun clear(runId: String)
    fun output(runId: String): StateFlow<List<String>>
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
)

data class MirrorVideoConfig(
    val maxSize: Int = 720,
    val bitRate: Int = 4_000_000,
    val maxFps: Int = 60,
    val codec: String = "h264",
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
)
