package app.andy.desktop.service.remote

import app.andy.desktop.service.CommandRunner
import app.andy.desktop.service.DesktopAppService
import app.andy.desktop.service.DesktopDeviceService
import app.andy.desktop.service.DesktopFileService
import app.andy.desktop.service.DesktopIntentService
import app.andy.desktop.service.DesktopLogcatService
import app.andy.desktop.service.SdkLocator
import app.andy.desktop.service.mirror.DesktopMirrorEngine
import app.andy.model.AndroidDevice
import app.andy.model.MdnsService
import app.andy.model.SdkDiscovery
import app.andy.service.AppService
import app.andy.service.AvdService
import app.andy.service.CommandResult
import app.andy.service.DeviceService
import app.andy.service.FileService
import app.andy.service.IntentService
import app.andy.service.LogcatService
import app.andy.service.MirrorEngine
import app.andy.service.RoutingAppService
import app.andy.service.RoutingFileService
import app.andy.service.RoutingIntentService
import app.andy.service.RoutingLogcatService
import app.andy.service.RoutingMirrorEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

/**
 * Swaps Android automation onto a real adb/scrcpy stack pointed at a remote adb server
 * (SSH-tunneled). Prefer this over MCP screenshot polling for Live.
 */
class AndroidBackendSwitcher(
    private val scope: CoroutineScope,
    private val devices: SwappableDeviceService,
    private val avd: SwappableAvdService,
    private val mirror: RoutingMirrorEngine,
    private val logcat: RoutingLogcatService,
    private val apps: RoutingAppService,
    private val files: RoutingFileService,
    private val intents: RoutingIntentService,
    private val localDevices: DeviceService,
    private val localAvd: AvdService,
    private val localAndroidMirror: MirrorEngine,
    private val localAndroidLogcat: LogcatService,
    private val localAndroidApps: AppService,
    private val localAndroidFiles: FileService,
    private val localAndroidIntents: IntentService,
    private val baseRunner: CommandRunner,
    private val sdkLocator: SdkLocator,
    private val workspaceStore: app.andy.service.WorkspaceStore,
    private val selectedSdkPath: () -> String?,
) {
    private var adbTunnel: SshAdbTunnel? = null
    private var savedLocalMirror: MirrorEngine? = null

    /**
     * Point device/mirror/logcat/apps/files/intents at the remote host's adb via [tunnel].
     * AVD stays on local for now (emulator binaries live on the remote host).
     */
    fun activateRemote(tunnel: SshAdbTunnel) {
        adbTunnel?.closeAllScrcpyForwards()
        adbTunnel = tunnel

        runBlocking { runCatching { mirror.disconnect(immediate = true) } }

        val start = tunnel.ensureRemoteAdbServer()
        if (!start.isSuccess) {
            error(
                "Could not start adb on ${tunnel.target}: " +
                    start.stderr.ifBlank { start.stdout }.ifBlank { "unknown error" },
            )
        }

        val remoteRunner = tunnel.adbRunner(baseRunner)
        val localAdbPath = runCatching {
            sdkLocator.discover(selectedSdkPath()).adbPath
        }.getOrNull()
        val remoteSdk = tunnel.discoverRemoteSdk(localAdbPath)
        if (remoteSdk.adbPath == null) {
            error("Local adb client not found — install platform-tools on this Mac to use remote devices.")
        }

        val remoteDevices = TunneledRemoteDeviceService(
            inner = DesktopDeviceService(remoteRunner, sdkLocator, workspaceStore),
            remoteSdk = remoteSdk,
        )
        val bridge = object : AdbForwardBridge {
            override fun afterForwardOpened(port: Int): Boolean = tunnel.openLocalTcpForward(port)
            override fun beforeForwardClosed(port: Int) = tunnel.closeLocalTcpForward(port)
        }
        val remoteMirror = DesktopMirrorEngine(
            remoteRunner,
            remoteDevices.desktop,
            forwardBridge = bridge,
            rewriteAdbCommand = tunnel::injectAdbServerPort,
        )
        val remoteLogcat = DesktopLogcatService(remoteRunner, remoteDevices.desktop)
        val remoteApps = DesktopAppService(remoteRunner, remoteDevices.desktop)
        val remoteFiles = DesktopFileService(remoteRunner, remoteDevices.desktop)
        val remoteIntents = DesktopIntentService(remoteRunner, remoteDevices.desktop)

        devices.switchTo(remoteDevices)
        // Keep local AVD service for now — starting remoteside emulators needs more than adb.
        // Devices list still shows remote physical/emulator devices via tunneled adb.
        logcat.replaceAndroid(remoteLogcat)
        apps.replaceAndroid(remoteApps)
        files.replaceAndroid(remoteFiles)
        intents.replaceAndroid(remoteIntents)
        savedLocalMirror = mirror.replaceAndroidEngine(remoteMirror)
    }

    suspend fun deactivateRemote() {
        adbTunnel?.closeAllScrcpyForwards()
        adbTunnel = null
        runCatching { mirror.disconnect(immediate = true) }
        devices.switchTo(localDevices)
        avd.switchTo(localAvd)
        logcat.replaceAndroid(localAndroidLogcat)
        apps.replaceAndroid(localAndroidApps)
        files.replaceAndroid(localAndroidFiles)
        intents.replaceAndroid(localAndroidIntents)
        val previous = savedLocalMirror
        savedLocalMirror = null
        if (previous != null) {
            val remoteMirror = mirror.replaceAndroidEngine(previous)
            runCatching { remoteMirror.disconnect(immediate = true) }
        }
    }
}

/**
 * [DesktopDeviceService] that reports the remote host's SDK paths while still using a local
 * adb *client* binary pointed at the tunneled server.
 */
class TunneledRemoteDeviceService(
    private val inner: DesktopDeviceService,
    private val remoteSdk: SdkDiscovery,
) : DeviceService {
    val desktop: DesktopDeviceService get() = inner

    override suspend fun discoverSdk(): SdkDiscovery = remoteSdk
    override suspend fun listDevices(): List<AndroidDevice> = inner.listDevices()
    override suspend fun shell(serial: String, command: List<String>): CommandResult =
        inner.shell(serial, command)
    override suspend fun emu(serial: String, command: List<String>): CommandResult =
        inner.emu(serial, command)
    override suspend fun applyEmulatorDisplayRotation(serial: String, quarterTurn: Int): CommandResult =
        inner.applyEmulatorDisplayRotation(serial, quarterTurn)
    override suspend fun readEmulatorDisplayRotation(serial: String): Int? =
        inner.readEmulatorDisplayRotation(serial)
    override suspend fun pair(host: String, port: Int, code: String): CommandResult =
        CommandResult.failure("Wi‑Fi pairing must be done on the remote host")
    override suspend fun connect(host: String, port: Int): CommandResult =
        CommandResult.failure("Wi‑Fi connect must be done on the remote host")
    override suspend fun disconnect(serial: String): CommandResult =
        CommandResult.failure("Wi‑Fi disconnect must be done on the remote host")
    override suspend fun listMdnsServices(): List<MdnsService> = emptyList()
    override suspend fun mdnsAvailable(): Boolean = false
    override suspend fun generatePairingQr(content: String): ByteArray? = null
}
