package app.andy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.andy_robot
import app.andy.model.ActionProject
import app.andy.model.ActionRunStatus
import app.andy.model.ActionsConfig
import app.andy.model.AndroidDevice
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.ProjectAction
import app.andy.model.RunningAction
import app.andy.model.SdkDiscovery
import app.andy.model.WorkspaceState
import app.andy.service.AndyServices
import app.andy.service.AppUpdateService
import app.andy.service.AppUpdateState
import app.andy.service.AvailableUpdate
import app.andy.service.MirrorInput
import app.andy.ui.accessibility.AccessibilityScreen
import app.andy.ui.accessibility.AccessibilityState
import app.andy.ui.actions.ActionsScreen
import app.andy.ui.actions.actionIconMarker
import app.andy.ui.apps.AppsScreen
import app.andy.ui.bugs.BugsScreen
import app.andy.ui.catalog.CatalogScreen
import app.andy.ui.components.Button
import app.andy.ui.components.FilterPill
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PlaceholderScreen
import app.andy.ui.components.StatusRow
import app.andy.ui.components.noiseGridOverlay
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.components.secondaryButtonColors
import app.andy.ui.controls.ControlsScreen
import app.andy.ui.design.DesignScreen
import app.andy.ui.devices.DevicesScreen
import app.andy.ui.files.FilesScreen
import app.andy.ui.hostfiles.HostFilesScreen
import app.andy.ui.intents.IntentsScreen
import app.andy.ui.live.LiveDevicePane
import app.andy.ui.live.LiveScreen
import app.andy.ui.live.MirrorFrameContent
import app.andy.ui.live.rememberMirrorInputSender
import app.andy.ui.logcat.LogcatScreen
import app.andy.ui.logcat.LogcatState
import app.andy.ui.network.GlowingDot
import app.andy.ui.network.NetworkScreen
import app.andy.ui.network.shouldAutoStartProxy
import app.andy.ui.performance.PerformanceScreen
import app.andy.ui.settings.SettingsScreen
import app.andy.ui.settings.UpdateInstallConfirmationDialog
import app.andy.ui.snapshots.SnapshotsScreen
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.AndyTheme
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Green
import app.andy.ui.theme.Ink
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Panel
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.painterResource

enum class AndyDestination(val label: String) {
    Devices("Devices"),
    Catalog("Catalog"),
    Live("Live"),
    Apps("Apps"),
    Logcat("Logcat"),
    Intents("Intents"),
    Files("Files & data"),
    ComputerFiles("Computer Files"),
    Network("Network"),
    Actions("Actions"),
    Snapshots("Snapshots"),
    Controls("Controls"),
    Performance("Performance"),
    Design("Design"),
    Accessibility("Accessibility"),
    Bugs("Bugs"),
    Settings("Settings"),
}

@Composable
fun AndyApp(
    services: AndyServices,
    requestedDestination: AndyDestination? = null,
    onDestinationConsumed: () -> Unit = {},
    onPopOutMirror: (String?, String?) -> Unit = { _, _ -> },
    contentTopPadding: androidx.compose.ui.unit.Dp = 18.dp,
) {
    AndyTheme {
        AndyShell(services, requestedDestination, onDestinationConsumed, onPopOutMirror, contentTopPadding)
    }
}

@Composable
fun AndyMirrorPopOut(
    services: AndyServices,
    serial: String?,
    deviceName: String? = null,
    controlsVisible: Boolean = false,
) {
    AndyTheme {
        val scope = rememberCoroutineScope()
        var mirrorStatus by remember { mutableStateOf("Disconnected") }
        var connectResult by remember { mutableStateOf("") }
        val sendInput = rememberMirrorInputSender(services, serial)
        val popOutPadding = if (controlsVisible) 12.dp else 0.dp
        LaunchedEffect(Unit) {
            services.mirror.status.collectLatest { mirrorStatus = it }
        }
        LaunchedEffect(serial) {
            if (serial != null) {
                val result = services.mirror.connect(serial)
                connectResult = if (result.isSuccess) result.stdout else result.stderr
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black).padding(popOutPadding)) {
            MirrorFrameContent(services.mirror, serial) { frameFlow, frame ->
                LiveDevicePane(
                    serial = serial,
                    device = null,
                    displayName = deviceName,
                    frame = frame,
                    frameFlow = frameFlow,
                    mirrorStatus = mirrorStatus,
                    connectResult = connectResult,
                    modifier = Modifier.fillMaxSize(),
                    showDeviceHeader = controlsVisible,
                    showChromeControls = controlsVisible,
                    showContainerChrome = controlsVisible,
                    deviceBorderWidth = if (controlsVisible) 5.dp else 0.dp,
                    deviceCornerRadius = if (controlsVisible) 10.dp else 0.dp,
                    onPower = { sendInput(MirrorInput.Power) },
                    onVolumeUp = { sendInput(MirrorInput.Key(24)) },
                    onVolumeDown = { sendInput(MirrorInput.Key(25)) },
                    onRotate = {
                        if (serial != null) scope.launch { services.devices.shell(serial, listOf("settings", "put", "system", "user_rotation", "1")) }
                    },
                    onCaptureScreenshot = {
                        if (serial != null) scope.launch { services.artifacts.saveScreenshot(serial, "andy-${serial}.png") }
                    },
                    onBugReport = {
                        if (serial != null) scope.launch { services.artifacts.saveBugReport(serial, "andy-bugreport-${serial}.zip") }
                    },
                    onClipText = {},
                    onPopOut = {},
                    onInput = sendInput,
                    onConnect = {
                        if (serial != null) scope.launch {
                            val result = services.mirror.connect(serial)
                            connectResult = if (result.isSuccess) result.stdout else result.stderr
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AndyShell(
    services: AndyServices,
    requestedDestination: AndyDestination?,
    onDestinationConsumed: () -> Unit,
    onPopOutMirror: (String?, String?) -> Unit,
    contentTopPadding: androidx.compose.ui.unit.Dp,
) {
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf(AndyDestination.Devices) }
    var devices by remember { mutableStateOf<List<AndroidDevice>>(emptyList()) }
    var sdk by remember { mutableStateOf(SdkDiscovery(null, null, null, null, null, listOf("SDK not scanned yet"))) }
    var selectedSerial by remember { mutableStateOf<String?>(null) }
    var workspaceState by remember { mutableStateOf(WorkspaceState()) }
    var workspaceLoaded by remember { mutableStateOf(false) }
    var networkRulesVisible by remember { mutableStateOf(false) }
    var networkLiveVisible by remember { mutableStateOf(false) }
    var performanceLiveVisible by remember { mutableStateOf(false) }
    var stoppingEmulatorSerial by remember { mutableStateOf<String?>(null) }
    var emulatorStopStatus by remember { mutableStateOf("") }
    var startingEmulatorName by remember { mutableStateOf<String?>(null) }
    var emulatorStartStatus by remember { mutableStateOf("") }
    var actionsConfig by remember { mutableStateOf(ActionsConfig()) }
    var activeRunId by remember { mutableStateOf<String?>(null) }
    val runningActions by services.actionRuns.running.collectAsState()
    val logcatState = remember { LogcatState() }
    val liveLogcatState = remember { LogcatState() }
    val accessibilityState = remember { AccessibilityState() }
    val pendingUpdateInstallConfirmation by services.updates.pendingInstallConfirmation.collectAsState()

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

    LaunchedEffect(Unit) {
        val saved = services.workspaceStore.load()
        workspaceState = saved
        selectedSerial = saved.selectedDeviceSerial
        actionsConfig = services.actionConfig.load()
        workspaceLoaded = true
        refreshDevices()
    }

    val runningActionIds = remember(runningActions) { runningActions.map { it.runId } }
    LaunchedEffect(runningActionIds) {
        if (activeRunId == null || runningActions.none { it.runId == activeRunId }) {
            activeRunId = runningActions.lastOrNull()?.runId
        }
    }

    LaunchedEffect(requestedDestination) {
        requestedDestination?.let {
            destination = it
            onDestinationConsumed()
        }
    }

    LaunchedEffect(workspaceState.mcpServerEnabled, workspaceState.mcpServerPort) {
        if (workspaceState.mcpServerEnabled) {
            services.mcp.start(workspaceState.mcpServerPort)
        } else {
            services.mcp.stop()
        }
    }

    LaunchedEffect(workspaceLoaded, workspaceState.proxyStartOnLaunch, workspaceState.proxyPort, workspaceState.proxyRules) {
        if (!workspaceLoaded || !workspaceState.proxyStartOnLaunch) return@LaunchedEffect
        val currentStatus = try {
            withTimeout(200) { services.proxy.status.first() }
        } catch (_: Exception) {
            "Proxy stopped"
        }
        if (shouldAutoStartProxy(currentStatus, workspaceState.proxyPort)) {
            services.proxy.ensureCertificateAuthority()
            services.proxy.start(workspaceState.proxyPort, workspaceState.proxyRules)
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

    val mcpRunning by services.mcp.running.collectAsState(false)
    val proxyStatus by services.proxy.status.collectAsState("Proxy stopped")
    val proxyRunning = proxyStatus.contains("listening on")

    Box(
        Modifier.fillMaxSize()
            .background(Brush.radialGradient(listOf(AndyColors.Neutral700, Ink), center = Offset(0f, 0f), radius = 1400f))
            .noiseGridOverlay(0.035f)
    ) {
        Row(Modifier.fillMaxSize().padding(top = contentTopPadding, start = 14.dp, end = 14.dp, bottom = 14.dp)) {
            Sidebar(
                current = destination,
                deviceCount = devices.size,
                onSelect = { destination = it },
                expanded = workspaceState.workspaceSidebarExpanded,
                onExpandedChange = { expanded -> updateWorkspace { it.copy(workspaceSidebarExpanded = expanded) } },
                sdk = sdk,
                updates = services.updates,
                mcpRunning = mcpRunning,
                mcpPort = workspaceState.mcpServerPort
            )
            Column(Modifier.fillMaxSize().padding(start = 10.dp)) {
                TopChrome(
                    destination = destination,
                    selectedDevice = devices.firstOrNull { it.serial == selectedSerial },
                    devices = devices,
                    onSelectDevice = { selectedSerial = it },
                    onRefresh = { refreshDevices() },
                    onStopEmulator = { stopEmulator(it) },
                    stoppingEmulatorSerial = stoppingEmulatorSerial,
                    actionConfig = actionsConfig,
                    runningActions = runningActions,
                    onRunAction = { project, action ->
                        activeRunId = services.actionRuns.run(project, action)
                    },
                    onStopAction = { run ->
                        services.actionRuns.stop(run.runId)
                        activeRunId = run.runId
                    },
                    proxyRunning = proxyRunning,
                    actions = {
                        if (destination == AndyDestination.Network) {
                            FilterPill("Rules", networkRulesVisible, Rust) { networkRulesVisible = !networkRulesVisible }
                            Spacer(Modifier.width(8.dp))
                            FilterPill("Live", networkLiveVisible, Cyan) { networkLiveVisible = !networkLiveVisible }
                            Spacer(Modifier.width(10.dp))
                        } else if (destination == AndyDestination.Performance) {
                            FilterPill("Live", performanceLiveVisible, Cyan) { performanceLiveVisible = !performanceLiveVisible }
                            Spacer(Modifier.width(10.dp))
                        }
                    },
                )
                Box(
                    Modifier.fillMaxSize()
                        .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R4))
                        .border(1.dp, Border, RoundedCornerShape(AndyRadius.R4))
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                ) {
                    when (destination) {
                        AndyDestination.Devices -> DevicesScreen(
                            services,
                            devices,
                            sdk,
                            onRefresh = { refreshDevices() },
                            onLive = {
                                selectedSerial = it
                                destination = AndyDestination.Live
                            },
                            onEmulatorStarted = { previousSerials, avdName ->
                                openStartedEmulator(previousSerials, avdName)
                            },
                            onStopEmulator = { stopEmulator(it) },
                            stoppingEmulatorSerial = stoppingEmulatorSerial,
                            stopStatus = emulatorStopStatus,
                            startingEmulatorName = startingEmulatorName,
                            startStatus = emulatorStartStatus,
                        )
                        AndyDestination.Catalog -> CatalogScreen(services.avd)
                        AndyDestination.Live -> LiveScreen(
                            services = services,
                            serial = selectedSerial,
                            device = devices.firstOrNull { it.serial == selectedSerial },
                            devicePaneWidth = workspaceState.liveDevicePaneWidth,
                            controlsPaneHeight = workspaceState.liveControlsPaneHeight,
                            onStopEmulator = { stopEmulator(it) },
                            stoppingEmulatorSerial = stoppingEmulatorSerial,
                            stopStatus = emulatorStopStatus,
                            onDevicePaneWidthChange = { width -> updateWorkspace { it.copy(liveDevicePaneWidth = width) } },
                            onControlsPaneHeightChange = { height -> updateWorkspace { it.copy(liveControlsPaneHeight = height) } },
                            onBugSaved = { destination = AndyDestination.Bugs },
                            logcatState = liveLogcatState,
                            onPopOutMirror = {
                                val selectedDevice = devices.firstOrNull { it.serial == selectedSerial }
                                onPopOutMirror(selectedSerial, selectedDevice?.displayName ?: selectedSerial)
                            },
                            selectedPackage = workspaceState.selectedPackage,
                            onSelectedPackageChange = { pkg -> updateWorkspace { it.copy(selectedPackage = pkg) } }
                        )
                        AndyDestination.Apps -> AppsScreen(
                            services,
                            selectedSerial,
                            workspaceState.appsListPaneWidth,
                            workspaceState.appsDetailsPaneHeight,
                            onPaneChange = { listWidth, detailsHeight -> updateWorkspace { it.copy(appsListPaneWidth = listWidth, appsDetailsPaneHeight = detailsHeight) } },
                        )
                        AndyDestination.Logcat -> LogcatScreen(
                            logcat = services.logcat,
                            appsService = services.apps,
                            serial = selectedSerial,
                            state = logcatState,
                            selectedPackage = workspaceState.selectedPackage,
                            onSelectedPackageChange = { pkg -> updateWorkspace { it.copy(selectedPackage = pkg) } }
                        )
                        AndyDestination.Intents -> IntentsScreen(services, selectedSerial)
                        AndyDestination.Files -> FilesScreen(services.files, selectedSerial)
                        AndyDestination.ComputerFiles -> HostFilesScreen(
                            service = services.hostFiles,
                            workspaceState = workspaceState,
                            onUpdateWorkspace = { updateWorkspace(it) },
                        )
                        AndyDestination.Network -> NetworkScreen(
                            services = services,
                            sdk = sdk,
                            serial = selectedSerial,
                            device = devices.firstOrNull { it.serial == selectedSerial },
                            port = workspaceState.proxyPort,
                            rules = workspaceState.proxyRules,
                            rulesVisible = networkRulesVisible,
                            liveVisible = networkLiveVisible,
                            onPortChange = { value -> updateWorkspace { it.copy(proxyPort = value) } },
                            onRulesChange = { value -> updateWorkspace { it.copy(proxyRules = value) } },
                            onRulesVisibleChange = { networkRulesVisible = it },
                        )
                        AndyDestination.Actions -> ActionsScreen(
                            services = services,
                            config = actionsConfig,
                            running = runningActions,
                            activeRunId = activeRunId,
                            onActiveRunIdChange = { activeRunId = it },
                            onConfigChange = { persistActionsConfig(it) },
                        )
                        AndyDestination.Snapshots -> SnapshotsScreen(services.avd)
                        AndyDestination.Controls -> ControlsScreen(services.devices, services.mirror, selectedSerial)
                        AndyDestination.Performance -> PerformanceScreen(
                            services = services,
                            serial = selectedSerial,
                            device = devices.firstOrNull { it.serial == selectedSerial },
                            processesPaneWidth = workspaceState.performanceProcessesPaneWidth,
                            onProcessesPaneWidthChange = { width -> updateWorkspace { it.copy(performanceProcessesPaneWidth = width) } },
                            liveVisible = performanceLiveVisible,
                            livePaneWidth = workspaceState.performanceLivePaneWidth,
                            onLivePaneWidthChange = { width -> updateWorkspace { it.copy(performanceLivePaneWidth = width) } },
                        )
                        AndyDestination.Design -> DesignScreen(
                            services,
                            selectedSerial,
                            devices.firstOrNull { it.serial == selectedSerial },
                            workspaceState.designDevicePaneWidth,
                            onDevicePaneWidthChange = { width -> updateWorkspace { it.copy(designDevicePaneWidth = width) } },
                        )
                        AndyDestination.Accessibility -> AccessibilityScreen(
                            services,
                            selectedSerial,
                            devices.firstOrNull { it.serial == selectedSerial },
                            workspaceState.accessibilityTreePaneWidth,
                            onTreePaneWidthChange = { width -> updateWorkspace { it.copy(accessibilityTreePaneWidth = width) } },
                            state = accessibilityState
                        )
                        AndyDestination.Bugs -> BugsScreen(services.bugs)
                        AndyDestination.Settings -> SettingsScreen(
                            workspaceState = workspaceState,
                            onUpdateWorkspace = { updateWorkspace(it) },
                            services = services
                        )
                        else -> PlaceholderScreen(destination.label)
                    }
                }
            }
        }
        pendingUpdateInstallConfirmation?.let { update ->
            UpdateInstallConfirmationDialog(
                update = update,
                onDismiss = { services.updates.respondToInstallConfirmation(false) },
                onConfirm = { services.updates.respondToInstallConfirmation(true) }
            )
        }
    }
}

@Composable
private fun Sidebar(
    current: AndyDestination,
    deviceCount: Int,
    onSelect: (AndyDestination) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    sdk: SdkDiscovery,
    updates: AppUpdateService,
    mcpRunning: Boolean,
    mcpPort: Int
) {
    val updateState by updates.state.collectAsState()
    val scope = rememberCoroutineScope()
    val sidebarWidth by animateDpAsState(
        targetValue = if (expanded) 246.dp else 64.dp,
        animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
        label = "workspaceSidebarWidth",
    )
    val horizontalPadding by animateDpAsState(
        targetValue = if (expanded) AndySpace.S3 else AndySpace.S2,
        animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
        label = "workspaceSidebarPadding",
    )
    val labelAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = 130, easing = FastOutSlowInEasing),
        label = "workspaceSidebarLabelAlpha",
    )
    val labelGap by animateDpAsState(
        targetValue = if (expanded) 8.dp else 0.dp,
        animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
        label = "workspaceSidebarLabelGap",
    )

    Column(
        Modifier.width(sidebarWidth).fillMaxHeight()
            .background(Brush.verticalGradient(listOf(AndyColors.Neutral750, AndyColors.Neutral850)), RoundedCornerShape(AndyRadius.R4))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R4))
            .padding(horizontal = horizontalPadding, vertical = AndySpace.S3),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(AndySpace.S1, AndySpace.S2, AndySpace.S1, AndySpace.S4),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (labelAlpha > 0.01f) Arrangement.spacedBy(AndySpace.S2) else Arrangement.Center,
            ) {
                AndyRobotIcon(Modifier.size(28.dp))
                if (labelAlpha > 0.01f) {
                    Column {
                        Text("andy", color = AndyColors.Neutral100.copy(alpha = labelAlpha), fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text("workspace", color = TextSecondary.copy(alpha = labelAlpha), fontFamily = MonoFont, fontWeight = FontWeight.Medium, fontSize = 10.sp)
                    }
                }
            }
            WorkspaceSidebarToggle(expanded = expanded, onClick = { onExpandedChange(!expanded) })
            AndyDestination.entries.forEach { item ->
                val active = item == current
                Row(
                    Modifier.fillMaxWidth()
                        .height(34.dp)
                        .background(if (active) AndyColors.OrangeSubtle else Color.Transparent, RoundedCornerShape(AndyRadius.R2))
                        .then(if (active) Modifier.border(1.dp, AndyColors.OrangeBorder.copy(alpha = 0.52f), RoundedCornerShape(AndyRadius.R2)) else Modifier)
                        .clickable { onSelect(item) }
                        .padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (labelAlpha > 0.01f) Arrangement.Start else Arrangement.Center,
                ) {
                    Text(navMark(item), color = if (active) Rust else TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
                    if (labelAlpha > 0.01f) {
                        Spacer(Modifier.width(labelGap))
                        Text(
                            item.label.lowercase(),
                            color = (if (active) AndyColors.Neutral100 else AndyColors.Neutral300).copy(alpha = labelAlpha),
                            fontFamily = MonoFont,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item == AndyDestination.Devices) Text("$deviceCount", color = TextSecondary.copy(alpha = labelAlpha), fontFamily = MonoFont, fontSize = 11.sp)
                        if (item == AndyDestination.Logcat) Text("live", color = TextSecondary.copy(alpha = labelAlpha), fontFamily = MonoFont, fontSize = 10.sp)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(
            visible = expanded,
            enter = expandHorizontally(animationSpec = tween(170, easing = FastOutSlowInEasing)) + fadeIn(tween(120)),
            exit = shrinkHorizontally(animationSpec = tween(140, easing = FastOutSlowInEasing)) + fadeOut(tween(90)),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .background(AndyColors.Neutral900.copy(alpha = 0.56f), RoundedCornerShape(AndyRadius.R3))
                    .border(1.dp, Border, RoundedCornerShape(AndyRadius.R4))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("v${app.andy.updates.AndyBuildInfo.versionName}  h.264 embedded", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
                StatusRow("ADB server", if (sdk.hasAdb) "ready" else "missing", sdk.hasAdb)
                StatusRow("AVD tools", if (sdk.hasEmulatorTools) "ready" else "install cmdline-tools", sdk.hasEmulatorTools)
                StatusRow("Proxy CA", "local", true)
                StatusRow("MCP server", if (mcpRunning) "running :$mcpPort" else "stopped", mcpRunning)

                Divider(color = Border, thickness = 1.dp, modifier = Modifier.padding(vertical = 2.dp))

                val updateText = when (updateState) {
                    AppUpdateState.Idle -> "Check for updates"
                    AppUpdateState.Checking -> "Checking for updates..."
                    AppUpdateState.Current -> "Andy is up to date"
                    is AppUpdateState.Available -> "Update to v${(updateState as AppUpdateState.Available).update.versionName}"
                    is AppUpdateState.Installing -> (updateState as AppUpdateState.Installing).let {
                        val pct = it.progress?.let { p -> " ${(p * 100).toInt()}%" } ?: ""
                        "${it.message}$pct"
                    }
                    is AppUpdateState.Failed -> (updateState as AppUpdateState.Failed).message
                }

                val isActionable = updateState is AppUpdateState.Idle || updateState is AppUpdateState.Available || updateState is AppUpdateState.Failed
                val updateColor = when (updateState) {
                    is AppUpdateState.Available -> Rust
                    is AppUpdateState.Failed -> Red
                    else -> TextSecondary
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (isActionable) Modifier.clickable {
                            scope.launch {
                                if (updateState is AppUpdateState.Available) {
                                    updates.installAvailableUpdate()
                                } else {
                                    updates.checkForUpdates()
                                }
                            }
                        } else Modifier)
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = updateText,
                        color = updateColor,
                        fontSize = 11.sp,
                        fontFamily = MonoFont,
                        fontWeight = if (updateState is AppUpdateState.Available) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkspaceSidebarToggle(expanded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .height(30.dp)
            .padding(bottom = 6.dp),
        horizontalArrangement = if (expanded) Arrangement.End else Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(24.dp)
                .background(AndyColors.Neutral900.copy(alpha = 0.50f), RoundedCornerShape(AndyRadius.R2))
                .border(1.dp, Border, RoundedCornerShape(AndyRadius.R2))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (expanded) "<<" else ">>",
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun AndyRobotIcon(modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(
                Brush.verticalGradient(listOf(AndyColors.Neutral600, AndyColors.Neutral850)),
                RoundedCornerShape(AndyRadius.R3),
            )
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(AndyRadius.R3))
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(Res.drawable.andy_robot),
            contentDescription = "Andy",
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun navMark(item: AndyDestination): String = when (item) {
    AndyDestination.Devices -> "[]"
    AndyDestination.Catalog -> "<>"
    AndyDestination.Live -> ">>"
    AndyDestination.Apps -> "::"
    AndyDestination.Logcat -> "##"
    AndyDestination.Intents -> "->"
    AndyDestination.Files -> "/_"
    AndyDestination.ComputerFiles -> "//"
    AndyDestination.Network -> "~~"
    AndyDestination.Actions -> "|>"
    AndyDestination.Snapshots -> "[]"
    AndyDestination.Controls -> "+-"
    AndyDestination.Performance -> "/^"
    AndyDestination.Design -> "%%"
    AndyDestination.Accessibility -> "13"
    AndyDestination.Bugs -> "!!"
    AndyDestination.Settings -> "*:"
}


@Composable
private fun TopChrome(
    destination: AndyDestination,
    selectedDevice: AndroidDevice?,
    devices: List<AndroidDevice>,
    onSelectDevice: (String) -> Unit,
    onRefresh: () -> Unit,
    onStopEmulator: (AndroidDevice) -> Unit,
    stoppingEmulatorSerial: String?,
    actionConfig: ActionsConfig,
    runningActions: List<RunningAction>,
    onRunAction: (ActionProject, ProjectAction) -> Unit,
    onStopAction: (RunningAction) -> Unit,
    proxyRunning: Boolean,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val hasActionRunnerControls = actionConfig.projects.any { it.actions.isNotEmpty() }

    Row(
        Modifier.fillMaxWidth().height(62.dp)
            .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R3))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(260.dp)) {
            Text(destination.label.lowercase(), color = AndyColors.Neutral100, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp)
            Text(selectedDevice?.let { "${it.displayName} / api ${it.apiLevel ?: "-"} / ${it.abi ?: "-"}" } ?: "no device selected", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        actions()
        if (destination != AndyDestination.Network && proxyRunning) {
            ProxyToolbarIndicator()
            Spacer(Modifier.width(10.dp))
        }
        if (hasActionRunnerControls) {
            ActionRunnerSelector(
                config = actionConfig,
                running = runningActions,
                onRunAction = onRunAction,
                onStopAction = onStopAction,
            )
            Spacer(Modifier.width(10.dp))
        }
        if (selectedDevice?.kind == DeviceKind.Emulator && selectedDevice.state == DeviceConnectionState.Online) {
            OutlinedButton(
                onClick = { onStopEmulator(selectedDevice) },
                enabled = stoppingEmulatorSerial != selectedDevice.serial,
                shape = RoundedCornerShape(AndyRadius.R2),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(if (stoppingEmulatorSerial == selectedDevice.serial) "Stopping" else "Stop emulator", fontSize = 12.sp)
            }
            Spacer(Modifier.width(10.dp))
        }
        Button(onClick = onRefresh, colors = primaryButtonColors(), shape = RoundedCornerShape(AndyRadius.R2), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Refresh", color = TextPrimary, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        DevicePicker(devices, selectedDevice?.serial, onSelectDevice)
    }
}

@Composable
private fun ProxyToolbarIndicator() {
    Row(
        Modifier.height(30.dp)
            .background(Green.copy(alpha = 0.12f), RoundedCornerShape(AndyRadius.Pill))
            .border(1.dp, Green.copy(alpha = 0.42f), RoundedCornerShape(AndyRadius.Pill))
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        GlowingDot(isGreen = true, modifier = Modifier.size(14.dp))
        Text("proxy", color = AndyColors.GreenSoft, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
    }
}

@Composable
private fun ActionRunnerSelector(
    config: ActionsConfig,
    running: List<RunningAction>,
    onRunAction: (ActionProject, ProjectAction) -> Unit,
    onStopAction: (RunningAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var projectExpanded by remember { mutableStateOf(false) }
    var actionExpanded by remember { mutableStateOf(false) }
    var selectedProjectId by remember { mutableStateOf<String?>(null) }
    var selectedActionId by remember { mutableStateOf<String?>(null) }

    val project = remember(config.projects, selectedProjectId) {
        config.projects.firstOrNull { it.id == selectedProjectId } ?: config.projects.firstOrNull()
    }
    val action = remember(project?.actions, selectedActionId) {
        project?.actions?.firstOrNull { it.id == selectedActionId } ?: project?.actions?.firstOrNull()
    }
    val liveRun = running.firstOrNull { run ->
        project?.id == run.projectId && action?.id == run.actionId && run.status == ActionRunStatus.Running
    }

    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            Button(
                onClick = { projectExpanded = true },
                colors = secondaryButtonColors(),
                shape = RoundedCornerShape(AndyRadius.R2),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.widthIn(min = 132.dp, max = 210.dp),
            ) {
                Text("prj", color = Rust, fontFamily = MonoFont, fontSize = 10.sp)
                Spacer(Modifier.width(6.dp))
                Text(project?.name ?: "project", color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = projectExpanded, onDismissRequest = { projectExpanded = false }, containerColor = AndyColors.Neutral750) {
                config.projects.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.name, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            selectedProjectId = item.id
                            selectedActionId = item.actions.firstOrNull()?.id
                            projectExpanded = false
                        },
                    )
                }
            }
        }

        Box {
            Button(
                onClick = { actionExpanded = true },
                enabled = project?.actions?.isNotEmpty() == true,
                colors = secondaryButtonColors(),
                shape = RoundedCornerShape(AndyRadius.R2),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.widthIn(min = 142.dp, max = 230.dp),
            ) {
                Text(action?.let { actionIconMarker(it.icon) } ?: "--", color = Rust, fontFamily = MonoFont, fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
                Text(action?.name ?: "no actions", color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = actionExpanded, onDismissRequest = { actionExpanded = false }, containerColor = AndyColors.Neutral750) {
                project?.actions.orEmpty().forEach { item ->
                    DropdownMenuItem(
                        text = { Text("${actionIconMarker(item.icon)}  ${item.name}", color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            selectedActionId = item.id
                            actionExpanded = false
                        },
                    )
                }
            }
        }

        Button(
            onClick = {
                val selectedProject = project
                val selectedAction = action
                if (liveRun != null) {
                    onStopAction(liveRun)
                } else if (selectedProject != null && selectedAction != null) {
                    onRunAction(selectedProject, selectedAction)
                }
            },
            enabled = liveRun != null || (project != null && action != null),
            colors = if (liveRun != null) ButtonDefaults.buttonColors(containerColor = Rust, contentColor = AndyColors.Neutral100) else primaryButtonColors(),
            shape = RoundedCornerShape(AndyRadius.R2),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(if (liveRun != null) "stop" else "run", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DevicePicker(devices: List<AndroidDevice>, selectedSerial: String?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { expanded = true }, colors = secondaryButtonColors(), shape = RoundedCornerShape(AndyRadius.R2), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
            Text("•", color = Green, fontSize = 18.sp)
            Spacer(Modifier.width(6.dp))
            Text(selectedSerial ?: "no device", color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = AndyColors.Neutral750) {
            devices.forEach { device ->
                DropdownMenuItem(text = { Text("${device.serial}  ${device.displayName}", color = TextPrimary) }, onClick = {
                    onSelect(device.serial)
                    expanded = false
                })
            }
        }
    }
}


internal data class PendingConfirmation(
    val title: String,
    val message: String,
    val onConfirm: () -> Unit,
)


@Composable
internal fun ConfirmationDialog(confirmation: PendingConfirmation, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(confirmation.title, color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = { Text(confirmation.message, color = TextSecondary) },
        confirmButton = { Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("Confirm") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


@Composable
internal fun HeaderCell(title: String, width: androidx.compose.ui.unit.Dp, onWidthChange: (Float) -> Unit) {
    val latestOnWidthChange by rememberUpdatedState(onWidthChange)
    val latestWidthValue by rememberUpdatedState(width.value)
    val density = LocalDensity.current.density
    var dragStartWidth by remember { mutableStateOf(0f) }
    var dragDelta by remember { mutableStateOf(0f) }
    Row(
        Modifier.width(width).fillMaxHeight()
            .horizontalResizeCursor()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        dragStartWidth = latestWidthValue
                        dragDelta = 0f
                    },
                ) { _, drag ->
                    dragDelta += drag.x / density
                    latestOnWidthChange(dragStartWidth + dragDelta)
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier.width(14.dp).fillMaxHeight(),
        ) {
            Box(Modifier.align(Alignment.Center).width(3.dp).fillMaxHeight().background(Rust.copy(alpha = 0.75f), RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
internal fun DraggableScrollbar(
    firstVisibleItemIndex: Int,
    visibleItems: Int,
    totalItems: Int,
    modifier: Modifier = Modifier,
    onDragToIndex: (Int) -> Unit,
) {
    var dragTop by remember { mutableStateOf<Float?>(null) }
    Canvas(
        modifier
            .width(10.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(totalItems, visibleItems) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragTop = offset.y
                        val maxFirst = (totalItems - visibleItems).coerceAtLeast(0)
                        val index = if (size.height <= 0) 0 else ((offset.y / size.height) * maxFirst).toInt()
                        onDragToIndex(index)
                    },
                    onDragEnd = { dragTop = null },
                    onDragCancel = { dragTop = null },
                ) { change, drag ->
                    val nextTop = ((dragTop ?: change.position.y) + drag.y).coerceIn(0f, size.height.toFloat())
                    dragTop = nextTop
                    val maxFirst = (totalItems - visibleItems).coerceAtLeast(0)
                    val index = if (size.height <= 0) 0 else ((nextTop / size.height) * maxFirst).toInt()
                    onDragToIndex(index)
                }
            },
    ) {
        drawRoundRect(
            color = Border.copy(alpha = 0.75f),
            size = androidx.compose.ui.geometry.Size(size.width, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2f, size.width / 2f),
        )
        if (totalItems > 0 && visibleItems > 0) {
            val fractionVisible = (visibleItems.toFloat() / totalItems).coerceIn(0.06f, 1f)
            val thumbHeight = size.height * fractionVisible
            val maxFirst = (totalItems - visibleItems).coerceAtLeast(1)
            val top = (firstVisibleItemIndex.toFloat() / maxFirst) * (size.height - thumbHeight)
            drawRoundRect(
                color = Rust,
                topLeft = Offset(0f, top),
                size = androidx.compose.ui.geometry.Size(size.width, thumbHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2f, size.width / 2f),
            )
        }
    }
}
