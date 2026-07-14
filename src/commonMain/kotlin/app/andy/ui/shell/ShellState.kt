package app.andy.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.andy.AndyDestination
import app.andy.model.ActionProject
import app.andy.model.ActionsConfig
import app.andy.model.AndroidDevice
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.PairedWifiDevice
import app.andy.model.ProjectAction
import app.andy.model.RunningAction
import app.andy.model.SdkDiscovery
import app.andy.model.WorkspaceState
import app.andy.service.AndyServices
import app.andy.transfer.DeviceTransferCoordinator
import app.andy.ui.accessibility.AccessibilityState
import app.andy.ui.devices.reconnectPairedWifiDevice
import app.andy.ui.logcat.LogcatState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class ShellState(
    private val services: AndyServices,
    private val scope: CoroutineScope,
) {
    var destination by mutableStateOf(AndyDestination.Devices)
        private set
    var devices by mutableStateOf<List<AndroidDevice>>(emptyList())
        private set
    var sdk by mutableStateOf(SdkDiscovery(null, null, null, null, null, listOf("SDK not scanned yet")))
        private set
    var selectedSerial by mutableStateOf<String?>(null)
        private set
    var workspaceState by mutableStateOf(WorkspaceState())
        private set
    var workspaceLoaded by mutableStateOf(false)
        private set
    var networkRulesVisible by mutableStateOf(false)
        private set
    var networkLiveVisible by mutableStateOf(false)
        private set
    var performanceLiveVisible by mutableStateOf(false)
        private set
    var stoppingEmulatorSerial by mutableStateOf<String?>(null)
        private set
    var emulatorStopStatus by mutableStateOf("")
        private set
    var startingEmulatorName by mutableStateOf<String?>(null)
        private set
    var emulatorStartStatus by mutableStateOf("")
        private set
    var actionsConfig by mutableStateOf(ActionsConfig())
        private set
    var activeRunId by mutableStateOf<String?>(null)
        private set
    var terminalRunId by mutableStateOf<String?>(null)
        private set

    val logcatState = LogcatState()
    val liveLogcatState = LogcatState()
    val accessibilityState = AccessibilityState()
    val transfer = DeviceTransferCoordinator()

    fun navigateTo(value: AndyDestination) {
        destination = value
    }

    fun selectDevice(serial: String?) {
        selectedSerial = serial
    }

    fun updateNetworkRulesVisible(value: Boolean) {
        networkRulesVisible = value
    }

    fun toggleNetworkRulesVisible() {
        networkRulesVisible = !networkRulesVisible
    }

    fun toggleNetworkLiveVisible() {
        networkLiveVisible = !networkLiveVisible
    }

    fun togglePerformanceLiveVisible() {
        performanceLiveVisible = !performanceLiveVisible
    }

    fun updateActiveRunId(value: String?) {
        activeRunId = value
    }

    suspend fun refreshDevicesNow(): List<AndroidDevice> {
        sdk = services.devices.discoverSdk()
        devices = services.devices.listDevices()
        val selectedStillPresent = devices.any { it.serial == selectedSerial && it.state == DeviceConnectionState.Online }
        if (!selectedStillPresent) {
            selectedSerial = devices.firstOrNull { it.state == DeviceConnectionState.Online }?.serial
        }
        return devices
    }

    fun refreshDevices() {
        scope.launch {
            refreshDevicesNow()
        }
    }

    fun stopEmulator(device: AndroidDevice) {
        if (device.kind != DeviceKind.Emulator || device.state != DeviceConnectionState.Online) return
        scope.launch {
            stoppingEmulatorSerial = device.serial
            services.mirror.disconnect()
            val result = services.avd.stopVirtualDevice(device.displayName)
            emulatorStopStatus = if (result.isSuccess) {
                result.stdout.ifBlank { "Stopped ${device.displayName}" }
            } else {
                result.stderr.ifBlank { result.stdout }
            }
            val refreshed = refreshDevicesNow()
            if (result.isSuccess && selectedSerial == device.serial) {
                selectedSerial = refreshed.firstOrNull {
                    it.serial != device.serial && it.state == DeviceConnectionState.Online
                }?.serial
            }
            stoppingEmulatorSerial = null
        }
    }

    fun openStartedEmulator(previousSerials: Set<String>, avdName: String) {
        scope.launch {
            startingEmulatorName = avdName
            emulatorStartStatus = "Starting $avdName..."
            repeat(60) {
                val currentDevices = refreshDevicesNow()
                val started = currentDevices.firstOrNull {
                    it.kind == DeviceKind.Emulator &&
                        it.state == DeviceConnectionState.Online &&
                        it.serial !in previousSerials
                } ?: currentDevices.firstOrNull {
                    it.kind == DeviceKind.Emulator && it.state == DeviceConnectionState.Online
                }
                if (started != null) {
                    selectedSerial = started.serial
                    destination = AndyDestination.Live
                    emulatorStartStatus = "${started.displayName} is online"
                    startingEmulatorName = null
                    return@launch
                }
                emulatorStartStatus = "Starting $avdName... waiting for boot (${it + 1}/60)"
                delay(1_000)
            }
            emulatorStartStatus = "$avdName is still starting. Refresh devices when it finishes booting."
            startingEmulatorName = null
        }
    }

    suspend fun initialize() {
        val saved = services.workspaceStore.load()
        workspaceState = saved
        selectedSerial = saved.selectedDeviceSerial
        if (services.capabilities.hostAutomation) {
            actionsConfig = services.actionConfig.load()
        }
        workspaceLoaded = true
        if (services.capabilities.wifiPairing && saved.pairedWifiDevices.isNotEmpty()) {
            // Reconnect in the background so workspace load / first device refresh are not blocked.
            scope.launch {
                val discovery = services.devices.discoverSdk()
                if (!discovery.hasAdb) return@launch
                val mdnsReady = runCatching { services.devices.mdnsAvailable() }.getOrDefault(false)
                val mdnsServices = if (mdnsReady) {
                    runCatching { services.devices.listMdnsServices() }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                saved.pairedWifiDevices.forEach { paired ->
                    launch {
                        runCatching {
                            withTimeout(8_000) {
                                val (result, endpoint) = reconnectPairedWifiDevice(services.devices, paired, mdnsServices)
                                if (result.isSuccess && endpoint != null && endpoint != paired.lastEndpoint) {
                                    updateWorkspace { state ->
                                        state.copy(
                                            pairedWifiDevices = state.pairedWifiDevices.map {
                                                if (it.id == paired.id) it.copy(lastEndpoint = endpoint) else it
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        refreshDevicesNow()
                    }
                }
            }
        }
        refreshDevices()
    }

    fun savePairedWifi(device: PairedWifiDevice) {
        updateWorkspace { state ->
            val without = state.pairedWifiDevices.filterNot {
                it.id == device.id ||
                    (device.mdnsInstanceName != null && it.mdnsInstanceName == device.mdnsInstanceName) ||
                    (device.lastEndpoint != null && it.lastEndpoint == device.lastEndpoint)
            }
            state.copy(pairedWifiDevices = without + device)
        }
    }

    fun forgetPairedWifi(id: String) {
        updateWorkspace { state ->
            state.copy(pairedWifiDevices = state.pairedWifiDevices.filterNot { it.id == id })
        }
    }

    fun reconnectPairedWifi(paired: PairedWifiDevice) {
        scope.launch {
            val (result, endpoint) = reconnectPairedWifiDevice(services.devices, paired)
            if (result.isSuccess && endpoint != null && endpoint != paired.lastEndpoint) {
                updateWorkspace { state ->
                    state.copy(
                        pairedWifiDevices = state.pairedWifiDevices.map {
                            if (it.id == paired.id) it.copy(lastEndpoint = endpoint) else it
                        },
                    )
                }
            }
            refreshDevicesNow()
        }
    }

    fun disconnectWifi(serial: String) {
        scope.launch {
            services.devices.disconnect(serial)
            refreshDevicesNow()
        }
    }

    fun syncActiveRun(runningActions: List<RunningAction>) {
        if (activeRunId == null || runningActions.none { it.runId == activeRunId }) {
            activeRunId = runningActions.lastOrNull()?.runId
        }
    }

    fun updateWorkspace(transform: (WorkspaceState) -> WorkspaceState) {
        val updated = transform(workspaceState).copy(selectedDeviceSerial = selectedSerial)
        workspaceState = updated
        scope.launch { services.workspaceStore.save(updated) }
    }

    fun persistActionsConfig(next: ActionsConfig) {
        actionsConfig = next
        scope.launch { services.actionConfig.save(next) }
    }

    fun openLive(serial: String) {
        selectedSerial = serial
        destination = AndyDestination.Live
    }

    fun runAction(project: ActionProject, action: ProjectAction) {
        val runId = services.actionRuns.run(project, action)
        activeRunId = runId
        terminalRunId = runId
        destination = AndyDestination.Actions
    }

    fun stopAction(run: RunningAction) {
        services.actionRuns.stop(run.runId)
        activeRunId = run.runId
    }
}

@Composable
internal fun rememberShellState(services: AndyServices): ShellState {
    val scope = rememberCoroutineScope()
    return remember(services) { ShellState(services, scope) }
}
