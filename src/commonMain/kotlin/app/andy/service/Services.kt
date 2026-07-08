package app.andy.service

import app.andy.model.*
import kotlinx.coroutines.flow.Flow

interface DeviceService {
    suspend fun discoverSdk(): SdkDiscovery
    suspend fun listDevices(): List<AndroidDevice>
    suspend fun shell(serial: String, command: List<String>): CommandResult
}

interface AvdService {
    suspend fun listSystemImages(): List<SystemImage>
    suspend fun listProfiles(): List<AvdProfile>
    suspend fun listVirtualDevices(): List<VirtualDevice>
    suspend fun createVirtualDevice(name: String, profileId: String, systemImagePackage: String): CommandResult
    suspend fun startVirtualDevice(name: String): CommandResult
    suspend fun stopVirtualDevice(name: String): CommandResult
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
    suspend fun listPermissions(serial: String, packageName: String): List<AndroidPermission>
    suspend fun listActivities(serial: String, packageName: String): List<AndroidActivity>
}

interface FileService {
    suspend fun list(serial: String, path: String): List<DeviceFile>
    suspend fun pull(serial: String, remotePath: String, localPath: String): CommandResult
    suspend fun push(serial: String, localPath: String, remotePath: String): CommandResult
    suspend fun delete(serial: String, remotePath: String): CommandResult
}

interface ProxyService {
    val exchanges: Flow<List<NetworkExchange>>
    val status: Flow<String>
    suspend fun detectMitmproxy(): CommandResult
    suspend fun ensureCertificateAuthority(): CommandResult
    suspend fun certificateAuthorityPath(): String
    suspend fun start(port: Int, rules: List<ProxyRule>): CommandResult
    suspend fun updateRules(rules: List<ProxyRule>): CommandResult
    suspend fun clearTraffic(): CommandResult
    suspend fun stop(): CommandResult
    suspend fun resolveDeviceProxyHost(serial: String): String
    suspend fun configureDeviceProxy(serial: String, host: String, port: Int): CommandResult
    suspend fun clearDeviceProxy(serial: String): CommandResult
    suspend fun installSystemCertificateAuthority(serial: String): CommandResult
    suspend fun activatePersistedCertificateAuthority(serial: String): CommandResult
}

interface MetricsService {
    fun stream(serial: String, packageName: String?): Flow<PerformanceSample>
}

interface AccessibilityService {
    suspend fun dump(serial: String): AccessibilityNode?
}

interface WorkspaceStore {
    suspend fun load(): WorkspaceState
    suspend fun save(state: WorkspaceState)
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

enum class MirrorTouchAction { Down, Move, Up }

data class AndyServices(
    val devices: DeviceService,
    val avd: AvdService,
    val mirror: MirrorEngine,
    val logcat: LogcatService,
    val intents: IntentService,
    val apps: AppService,
    val files: FileService,
    val proxy: ProxyService,
    val metrics: MetricsService,
    val accessibility: AccessibilityService,
    val workspaceStore: WorkspaceStore,
)
