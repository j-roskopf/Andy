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
import app.andy.model.IosTarget
import app.andy.model.IosTargetState
import app.andy.model.PairedWifiDevice
import app.andy.service.IosTargetRegistry
import app.andy.model.ProjectAction
import app.andy.model.RunningAction
import app.andy.model.SdkDiscovery
import app.andy.model.WorkspaceState
import app.andy.model.rememberedActionId
import app.andy.service.AndyServices
import app.andy.service.OpenAgentTaskRequest
import app.andy.service.OpenInvestigationRequest
import app.andy.transfer.DeviceTransferCoordinator
import app.andy.ui.inspector.InspectorState
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
    var iosTargets by mutableStateOf<List<IosTarget>>(emptyList())
        private set
    var sdk by mutableStateOf(SdkDiscovery(null, null, null, null, null, listOf("SDK not scanned yet")))
        private set
    var selectedSerial by mutableStateOf<String?>(null)
        private set
    var selectedIosUdid by mutableStateOf<String?>(null)
        private set
    /** Shared Live/Controls hinge angle for foldable emulator previews (degrees, 0–180). */
    var foldableHingeAngle by mutableStateOf(180f)
        private set
    val activeTargetId: String?
        get() = selectedIosUdid ?: selectedSerial

    val isIosSelection: Boolean
        get() = selectedIosUdid != null

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
    var chromeMenuExpanded by mutableStateOf(false)
        private set
    var docks by mutableStateOf(ShellDocks())
        private set
    var lastTerminalPlacement by mutableStateOf(DockPlacement.Right)
        private set

    /** In-app deep links for contextual agent actions (§5), separate from OS-level requests. */
    var pendingAgentTaskOpen by mutableStateOf<OpenAgentTaskRequest?>(null)
        private set
    var pendingInvestigationOpen by mutableStateOf<OpenInvestigationRequest?>(null)
        private set
    /** Settings category label to select on next Settings entry (e.g. "Proxy"). */
    var pendingSettingsCategory by mutableStateOf<String?>(null)
        private set
    private var startupTargetId: String? = null
    private var startupSelectionResolved = false
    private var handledTerminalRunId: String? = null

    val logcatState = LogcatState()
    val liveLogcatState = LogcatState()
    val inspectorState = InspectorState()
    val transfer = DeviceTransferCoordinator()

    fun navigateTo(value: AndyDestination) {
        if (value == AndyDestination.Tracing) {
            destination = AndyDestination.Performance
            updateWorkspace { it.copy(performanceTab = app.andy.model.PerformanceTab.Tracing.name) }
        } else {
            destination = value
        }
    }

    fun openProxySettings() {
        pendingSettingsCategory = "Proxy"
        navigateTo(AndyDestination.Settings)
    }

    fun consumeSettingsCategory(): String? {
        val value = pendingSettingsCategory
        pendingSettingsCategory = null
        return value
    }

    /** Opens the chat a contextual action just launched, in whichever destination owns it. */
    fun openAgentTask(request: OpenAgentTaskRequest) {
        pendingAgentTaskOpen = request
        navigateTo(if (request.projectId == null) AndyDestination.Agents else AndyDestination.Actions)
    }

    fun consumeAgentTaskOpen() {
        pendingAgentTaskOpen = null
    }

    /** Returns from a chat to the investigation, event, and playback position behind it. */
    fun openInvestigation(request: OpenInvestigationRequest) {
        pendingInvestigationOpen = request
        navigateTo(AndyDestination.Bugs)
    }

    fun consumeInvestigationOpen() {
        pendingInvestigationOpen = null
    }

    fun selectDevice(serial: String?) {
        if (selectedSerial != serial) foldableHingeAngle = 180f
        selectedSerial = serial
        if (serial != null) selectedIosUdid = null
        persistSelectedTarget()
    }

    fun selectIosTarget(udid: String?) {
        selectedIosUdid = udid
        if (udid != null) {
            selectedSerial = null
        }
        persistSelectedTarget()
    }

    fun updateFoldableHingeAngle(angle: Float) {
        foldableHingeAngle = if (angle.coerceIn(0f, 180f) < 90f) 0f else 180f
    }

    fun setDeviceLabel(targetId: String, label: String) {
        updateWorkspace { state ->
            state.copy(
                deviceLabels = if (label.isBlank()) {
                    state.deviceLabels - targetId
                } else {
                    state.deviceLabels + (targetId to label.trim())
                },
            )
        }
    }

    fun setDeviceNote(targetId: String, note: String) {
        updateWorkspace { state ->
            state.copy(
                deviceNotes = if (note.isBlank()) {
                    state.deviceNotes - targetId
                } else {
                    state.deviceNotes + (targetId to note)
                },
            )
        }
    }

    private fun persistSelectedTarget() {
        val targetId = activeTargetId
        if (workspaceState.selectedDeviceSerial == targetId) return
        updateWorkspace { it.copy(selectedDeviceSerial = targetId) }
    }

    private fun isTargetAvailable(targetId: String): Boolean {
        if (iosTargets.any { it.udid == targetId && it.isLiveReady }) return true
        return devices.any { it.serial == targetId && it.state == DeviceConnectionState.Online }
    }

    private fun applyTarget(targetId: String) {
        if (iosTargets.any { it.udid == targetId && it.isLiveReady }) {
            selectedIosUdid = targetId
            selectedSerial = null
        } else {
            if (selectedSerial != targetId) foldableHingeAngle = 180f
            selectedSerial = targetId
            selectedIosUdid = null
        }
    }

    private fun defaultOnlineAndroidSerial(): String? =
        devices.firstOrNull { it.state == DeviceConnectionState.Online }?.serial

    fun bootIosSimulator(target: IosTarget) {
        scope.launch {
            startingEmulatorName = target.displayName
            emulatorStartStatus = "Booting ${target.displayName}..."
            val result = services.iosDevices.boot(target.udid)
            if (!result.isSuccess) {
                emulatorStartStatus = result.stderr.ifBlank { result.stdout }
                startingEmulatorName = null
                return@launch
            }
            repeat(90) {
                refreshDevicesNow()
                val booted = iosTargets.firstOrNull { it.udid == target.udid }?.state == IosTargetState.Booted
                if (booted) {
                    emulatorStartStatus = "${target.displayName} is ready"
                    selectIosTarget(target.udid)
                    startingEmulatorName = null
                    return@launch
                }
                emulatorStartStatus = "${target.displayName} booting..."
                delay(1_000)
            }
            emulatorStartStatus = "${target.displayName} boot timed out — try Refresh"
            startingEmulatorName = null
        }
    }

    fun shutdownIosSimulator(target: IosTarget) {
        scope.launch {
            services.iosDevices.shutdown(target.udid)
            refreshDevicesNow()
        }
    }

    fun openIosInSimulatorApp(target: IosTarget) {
        scope.launch { services.iosDevices.openInSimulatorApp(target.udid) }
    }

    fun updateNetworkRulesVisible(value: Boolean) {
        networkRulesVisible = value
    }

    fun toggleNetworkRulesVisible() {
        networkRulesVisible = !networkRulesVisible
    }

    fun updateNetworkLiveVisible(value: Boolean) {
        networkLiveVisible = value
    }

    fun toggleNetworkLiveVisible() {
        networkLiveVisible = !networkLiveVisible
    }

    fun updatePerformanceLiveVisible(value: Boolean) {
        performanceLiveVisible = value
    }

    fun togglePerformanceLiveVisible() {
        performanceLiveVisible = !performanceLiveVisible
    }

    fun updateActiveRunId(value: String?) {
        activeRunId = value
    }

    fun updateChromeMenuExpanded(value: Boolean) {
        chromeMenuExpanded = value
    }

    /** Placement icon: close if open, otherwise show the landing menu. */
    fun onPlacementIconClick(placement: DockPlacement) {
        val pane = docks.pane(placement)
        docks = if (pane.visible) {
            docks.update(placement) { it.hide() }.copy(landingFor = null)
        } else {
            docks.copy(landingFor = placement)
        }
    }

    fun dismissDockLanding() {
        docks = docks.copy(landingFor = null)
    }

    fun openDockKind(placement: DockPlacement, kind: DockTabKind, newTerminal: Boolean = false) {
        when (kind) {
            DockTabKind.Live -> docks = docks.withLiveExclusive(placement)
            DockTabKind.Logs -> docks = docks.update(placement) { it.withTab(DockTab.logs()) }
            DockTabKind.Terminal -> if (newTerminal) openNewTerminalTab(placement) else openOrFocusTerminal(placement)
        }
    }

    fun openOrFocusTerminal(placement: DockPlacement = lastTerminalPlacement) {
        val project = actionsConfig.projects.firstOrNull { it.id == workspaceState.lastActionProjectId }
            ?: actionsConfig.projects.firstOrNull()
        if (project == null) {
            docks = docks.copy(landingFor = null)
            return
        }
        val runId = activeRunId?.takeIf { activeId ->
            // Prefer keeping the focused shell when it belongs to this project.
            services.actionRuns.running.value.any { it.runId == activeId && it.projectId == project.id }
        } ?: services.actionRuns.openShell(project)
        focusTerminalRun(runId, placement)
    }

    /** Always spawns a fresh interactive shell tab, even when other terminals are open. */
    fun openNewTerminalTab(placement: DockPlacement = lastTerminalPlacement) {
        val project = actionsConfig.projects.firstOrNull { it.id == workspaceState.lastActionProjectId }
            ?: actionsConfig.projects.firstOrNull()
        if (project == null) {
            docks = docks.copy(landingFor = null)
            return
        }
        val runId = services.actionRuns.openShell(project)
        focusTerminalRun(runId, placement)
    }

    fun focusTerminalRun(runId: String, placement: DockPlacement = lastTerminalPlacement) {
        if (runId.isBlank()) return
        lastTerminalPlacement = placement
        activeRunId = runId
        terminalRunId = runId
        handledTerminalRunId = runId
        docks = docks.withTerminalExclusive(placement, runId)
    }

    fun notifyTerminalRun(runId: String) {
        if (runId.isBlank()) return
        focusTerminalRun(runId, lastTerminalPlacement)
    }

    fun selectDockTab(placement: DockPlacement, tabId: String) {
        docks = docks.update(placement) { it.selectTab(tabId) }
        val tab = docks.pane(placement).tabs.firstOrNull { it.id == tabId }
        if (tab?.kind == DockTabKind.Terminal && tab.runId != null) {
            activeRunId = tab.runId
        }
    }

    fun closeDockTab(placement: DockPlacement, tabId: String) {
        val tab = docks.pane(placement).tabs.firstOrNull { it.id == tabId }
        if (tab?.kind == DockTabKind.Terminal && tab.runId != null) {
            services.actionRuns.stop(tab.runId)
            if (activeRunId == tab.runId) {
                val remaining = docks.pane(placement).tabs
                    .filter { it.id != tabId && it.kind == DockTabKind.Terminal }
                    .mapNotNull { it.runId }
                activeRunId = remaining.lastOrNull()
            }
        }
        docks = docks.update(placement) { it.closeTab(tabId) }
    }

    fun closeDock(placement: DockPlacement) {
        docks = docks.update(placement) { it.hide() }
    }

    fun consumeTerminalRun(runningActions: List<RunningAction>) {
        val runId = terminalRunId ?: return
        if (runId == handledTerminalRunId) return
        val run = runningActions.firstOrNull { it.runId == runId } ?: return
        handledTerminalRunId = runId
        activeRunId = runId
        rememberLastProject(run.projectId)
        docks = docks.withTerminalExclusive(lastTerminalPlacement, runId)
    }

    fun pruneDockTerminalTabs(runningActions: List<RunningAction>) {
        val alive = runningActions.mapTo(mutableSetOf()) { it.runId }
        val nextRight = docks.right.withoutTerminalRuns(alive)
        val nextBottom = docks.bottom.withoutTerminalRuns(alive)
        if (nextRight == docks.right && nextBottom == docks.bottom) return
        docks = docks.copy(right = nextRight, bottom = nextBottom)
    }

    suspend fun refreshDevicesNow(): List<AndroidDevice> {
        sdk = services.devices.discoverSdk()
        devices = services.devices.listDevices()
        iosTargets = services.iosDevices.listTargets()
        IosTargetRegistry.update(iosTargets)
        if (!startupSelectionResolved) {
            startupSelectionResolved = true
            val saved = startupTargetId
            startupTargetId = null
            when {
                saved != null && isTargetAvailable(saved) -> applyTarget(saved)
                else -> {
                    selectedIosUdid = null
                    selectedSerial = defaultOnlineAndroidSerial()
                }
            }
            return devices
        }

        val current = activeTargetId
        if (current != null && isTargetAvailable(current)) {
            return devices
        }
        if (selectedIosUdid != null) {
            selectedIosUdid = null
            selectedSerial = defaultOnlineAndroidSerial()
        } else if (selectedSerial != null) {
            selectedSerial = defaultOnlineAndroidSerial()
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
            services.mirror.disconnect(immediate = true)
            val result = services.avd.stopVirtualDevice(device.displayName)
            emulatorStopStatus = if (result.isSuccess) {
                result.stdout.ifBlank { "Stopped ${device.displayName}" }
            } else {
                result.stderr.ifBlank { result.stdout }
            }
            val refreshed = refreshDevicesNow()
            if (result.isSuccess && selectedSerial == device.serial) {
                selectDevice(
                    refreshed.firstOrNull {
                        it.serial != device.serial && it.state == DeviceConnectionState.Online
                    }?.serial,
                )
            }
            stoppingEmulatorSerial = null
        }
    }

    fun openStartedEmulator(previousSerials: Set<String>, avdName: String) {
        scope.launch {
            startingEmulatorName = avdName
            emulatorStartStatus = "Starting $avdName..."
            repeat(90) {
                val currentDevices = refreshDevicesNow()
                val started = currentDevices.firstOrNull {
                    it.kind == DeviceKind.Emulator &&
                        it.state == DeviceConnectionState.Online &&
                        it.serial !in previousSerials
                }
                if (started != null) {
                    emulatorStartStatus = "${started.displayName} online — waiting for boot…"
                    // adb Online is not boot_completed. Opening Live too early leaves a black,
                    // too-wide mirror until a manual reconnect.
                    val booted = awaitDeviceBootCompleted(started.serial)
                    val refreshed = refreshDevicesNow()
                    val ready = refreshed.firstOrNull { it.serial == started.serial } ?: started
                    selectDevice(ready.serial)
                    destination = AndyDestination.Live
                    emulatorStartStatus = if (booted) {
                        "${ready.displayName} is ready"
                    } else {
                        "${ready.displayName} is online (boot still finishing)"
                    }
                    startingEmulatorName = null
                    return@launch
                }
                emulatorStartStatus = "Starting $avdName... waiting for boot (${it + 1}/90)"
                delay(1_000)
            }
            emulatorStartStatus = "$avdName is still starting. Refresh devices when it finishes booting."
            startingEmulatorName = null
        }
    }

    private suspend fun awaitDeviceBootCompleted(serial: String, attempts: Int = 120): Boolean {
        repeat(attempts) {
            val result = runCatching {
                services.devices.shell(serial, listOf("getprop", "sys.boot_completed"))
            }.getOrNull()
            if (result?.stdout?.trim() == "1") {
                val size = runCatching {
                    services.devices.shell(serial, listOf("wm", "size"))
                }.getOrNull()
                if (size?.isSuccess == true && size.stdout.contains(Regex("""\d+x\d+"""))) {
                    return true
                }
            }
            delay(500)
        }
        return false
    }

    suspend fun initialize() {
        val saved = services.workspaceStore.load()
        workspaceState = saved
        startupTargetId = saved.selectedDeviceSerial
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
        val updated = transform(workspaceState).copy(selectedDeviceSerial = activeTargetId)
        workspaceState = updated
        scope.launch { services.workspaceStore.save(updated) }
    }

    fun persistActionsConfig(next: ActionsConfig) {
        actionsConfig = next
        scope.launch { services.actionConfig.save(next) }
    }

    fun openLive(serial: String) {
        if (iosTargets.any { it.udid == serial }) {
            selectIosTarget(serial)
        } else {
            selectDevice(serial)
        }
        destination = AndyDestination.Live
    }

    fun runAction(project: ActionProject, action: ProjectAction) {
        rememberActionSelection(project.id, action.id)
        val runId = services.actionRuns.run(project, action)
        activeRunId = runId
        terminalRunId = runId
        destination = AndyDestination.Actions
    }

    fun rememberActionSelection(projectId: String, actionId: String?) {
        if (
            workspaceState.lastActionProjectId == projectId &&
            workspaceState.lastActionId == actionId
        ) {
            return
        }
        updateWorkspace {
            it.copy(
                lastActionProjectId = projectId,
                lastActionId = actionId,
                lastActionIdByProject = actionId?.let { id -> it.lastActionIdByProject + (projectId to id) }
                    ?: it.lastActionIdByProject,
            )
        }
    }

    fun rememberLastProject(projectId: String) {
        if (workspaceState.lastActionProjectId == projectId) return
        val project = actionsConfig.projects.firstOrNull { it.id == projectId } ?: return
        rememberActionSelection(projectId, project.rememberedActionId(workspaceState.lastActionIdByProject))
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
