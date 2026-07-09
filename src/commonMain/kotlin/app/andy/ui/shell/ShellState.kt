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
import app.andy.model.ProjectAction
import app.andy.model.RunningAction
import app.andy.model.SdkDiscovery
import app.andy.model.WorkspaceState
import app.andy.service.AndyServices
import app.andy.ui.accessibility.AccessibilityState
import app.andy.ui.logcat.LogcatState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ShellState(
    private val services: AndyServices,
    private val scope: CoroutineScope,
) {
    @set:JvmName("writeDestination")
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
    @set:JvmName("writeNetworkRulesVisible")
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
    @set:JvmName("writeActiveRunId")
    var activeRunId by mutableStateOf<String?>(null)
        private set

    val logcatState = LogcatState()
    val liveLogcatState = LogcatState()
    val accessibilityState = AccessibilityState()

    fun setDestination(value: AndyDestination) {
        destination = value
    }

    fun selectDevice(serial: String?) {
        selectedSerial = serial
    }

    fun setNetworkRulesVisible(value: Boolean) {
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

    fun setActiveRunId(value: String?) {
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
        actionsConfig = services.actionConfig.load()
        workspaceLoaded = true
        refreshDevices()
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
        activeRunId = services.actionRuns.run(project, action)
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
