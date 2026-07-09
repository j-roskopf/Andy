package app.andy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerMoveFilter
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectDragGestures
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.andy_robot
import app.andy.domain.*
import app.andy.model.*
import app.andy.service.*
import app.andy.ui.theme.*
import app.andy.ui.components.Button
import app.andy.ui.components.Button
import app.andy.ui.components.DetailRow
import app.andy.ui.components.DetailSection
import app.andy.ui.components.EmptyState
import app.andy.ui.components.FilterPill
import app.andy.ui.components.FormRow
import app.andy.ui.components.HorizontalPaneDivider
import app.andy.ui.components.LabeledField
import app.andy.ui.components.MonoCell
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PaneDivider
import app.andy.ui.components.PanelCard
import app.andy.ui.components.PlaceholderScreen
import app.andy.ui.components.StatusRow
import app.andy.ui.components.StatusTag
import app.andy.ui.components.TableHeader
import app.andy.ui.components.TableRow
import app.andy.ui.components.TextField
import app.andy.ui.components.Toolbar
import app.andy.ui.components.fieldColors
import app.andy.ui.components.noiseGridOverlay
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.components.secondaryButtonColors
import app.andy.ui.accessibility.AccessibilityScreen
import app.andy.ui.accessibility.AccessibilityState
import app.andy.ui.logcat.LogcatPanel
import app.andy.ui.logcat.LogcatScreen
import app.andy.ui.logcat.LogcatState
import app.andy.ui.devices.DevicesScreen
import app.andy.ui.catalog.CatalogScreen
import app.andy.ui.live.LiveDevicePane
import app.andy.ui.live.LiveScreen
import app.andy.ui.live.MirrorFrameContent
import app.andy.ui.live.fittedRect
import app.andy.ui.live.rememberMirrorInputSender
import app.andy.ui.snapshots.SnapshotsScreen
import app.andy.ui.network.GlowingDot
import app.andy.ui.network.NetworkScreen
import app.andy.ui.network.shouldAutoStartProxy
import app.andy.ui.actions.ActionsScreen
import app.andy.ui.actions.actionIconMarker
import app.andy.ui.bugs.BugsScreen
import app.andy.ui.hostfiles.HostFilesScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
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
                            onLiveVisibleChange = { performanceLiveVisible = it },
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

@Composable
private fun IntentsScreen(services: AndyServices, serial: String?) {
    val intentService = services.intents
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(IntentMode.DeepLink) }
    var action by remember { mutableStateOf("android.intent.action.VIEW") }
    var component by remember { mutableStateOf("") }
    var dataUri by remember { mutableStateOf("app://url") }
    var result by remember { mutableStateOf("") }
    val draft = IntentDraft(mode = mode, action = action, component = component, dataUri = dataUri)
    val command = intentService.buildCommand(draft).joinToString(" ")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PanelCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntentMode.entries.forEach { item -> FilterPill(item.name, item == mode, Rust) { mode = item } }
            }
            FormRow("Action") { TextField(action, { action = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors()) }
            FormRow("Component") { TextField(component, { component = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors()) }
            FormRow("Data URI") { TextField(dataUri, { dataUri = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors()) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("$ $command", color = Green, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                Button(onClick = {
                    if (serial != null) scope.launch {
                        services.bugs.recordAction("intent", "Send ${mode.name}", command)
                        result = intentService.send(serial, draft).let { if (it.isSuccess) it.stdout.ifBlank { "Sent" } else it.stderr }
                    }
                }) { Text("Send") }
            }
        }
        PanelCard {
            Text("Result", color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(result.ifBlank { "No intent sent yet." }, color = TextSecondary, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun AppsScreen(
    services: AndyServices,
    serial: String?,
    listPaneWidth: Float,
    detailsPaneHeight: Float,
    onPaneChange: (Float, Float) -> Unit,
) {
    val apps = services.apps
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<AndroidApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<AndroidApp?>(null) }
    var permissions by remember { mutableStateOf<List<AndroidPermission>>(emptyList()) }
    var activities by remember { mutableStateOf<List<AndroidActivity>>(emptyList()) }
    var status by remember { mutableStateOf("Select a device") }
    var localListPaneWidth by remember(listPaneWidth) { mutableStateOf(listPaneWidth) }
    var localDetailsPaneHeight by remember(detailsPaneHeight) { mutableStateOf(detailsPaneHeight) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    val iconCache = remember(serial) { mutableStateMapOf<String, ByteArray?>() }

    fun refresh() {
        if (serial == null) {
            status = "Select an online device"
            return
        }
        scope.launch {
            status = "Loading packages..."
            rows = apps.listApps(serial)
            selected = selected?.let { current -> rows.firstOrNull { it.packageName == current.packageName } }
            status = "${rows.size} apps"
        }
    }

    fun runAppAction(label: String, packageName: String? = selected?.packageName, appLabel: String? = selected?.label, block: suspend () -> CommandResult) {
        scope.launch {
            packageName?.let { services.bugs.recordAction("app", "$label $it", appLabel) }
            val result = block()
            status = "$label: " + if (result.isSuccess) result.stdout.ifBlank { "ok" } else result.stderr.ifBlank { result.stdout }
            if (label == "Uninstall" || label == "Clear data") refresh()
        }
    }

    LaunchedEffect(serial) { refresh() }
    val filtered = rows.filter { app -> query.isBlank() || app.packageName.contains(query, true) || app.label?.contains(query, true) == true }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(localListPaneWidth.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Toolbar("Apps", status, onPrimary = { refresh() }, primaryLabel = "Refresh")
            TextField(query, { query = it }, placeholder = { Text("Filter packages", color = TextSecondary) }, singleLine = true, modifier = Modifier.fillMaxWidth().height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
            TableHeader(listOf("" to 56.dp, "TYPE" to 70.dp, "STATE" to 80.dp, "VERSION" to 90.dp, "APP NAME" to 160.dp, "PACKAGE" to 1.dp))
            LazyColumn {
                items(filtered) { app ->
                    TableRow(Modifier.clickable {
                        selected = app
                        if (serial != null) scope.launch {
                            permissions = apps.listPermissions(serial, app.packageName)
                            activities = apps.listActivities(serial, app.packageName)
                        }
                    }) {
                        Box(Modifier.width(56.dp)) {
                            if (serial != null) AppIconCell(serial, app.packageName, apps, iconCache)
                        }
                        MonoCell(if (app.system) "system" else "user", 70.dp, if (app.system) TextSecondary else Green)
                        MonoCell(if (app.enabled) "enabled" else "disabled", 80.dp, if (app.enabled) TextPrimary else Rust)
                        MonoCell(app.versionCode ?: "-", 90.dp, TextSecondary)
                        MonoCell(app.label ?: "-", 160.dp, TextSecondary)
                        MonoCell(app.packageName, 1.dp, if (selected?.packageName == app.packageName) Rust else TextPrimary, Modifier.weight(1f))
                    }
                }
            }
        }
        PaneDivider(
            onDrag = { dragX -> localListPaneWidth = (localListPaneWidth + dragX).coerceIn(320f, 1100f) },
            onDragEnd = { onPaneChange(localListPaneWidth, localDetailsPaneHeight) },
        )
        Column(Modifier.fillMaxSize().padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelCard(Modifier.fillMaxWidth().height(localDetailsPaneHeight.dp)) {
                val app = selected
                Text(app?.packageName ?: "No app selected", color = TextPrimary, fontWeight = FontWeight.Bold)
                if (app != null && serial != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { runAppAction("Launch", app.packageName, app.label) { apps.launch(serial, app.packageName) } }) { Text("Launch") }
                        OutlinedButton(onClick = { runAppAction("Stop", app.packageName, app.label) { apps.stop(serial, app.packageName) } }) { Text("Stop") }
                        OutlinedButton(onClick = {
                            pendingConfirmation = PendingConfirmation("Clear app data?", app.packageName) {
                                runAppAction("Clear data", app.packageName, app.label) { apps.clearData(serial, app.packageName) }
                            }
                        }) { Text("Clear") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { runAppAction("Reset permissions", app.packageName, app.label) { apps.resetPermissions(serial, app.packageName) } }) { Text("Reset perms") }
                        OutlinedButton(onClick = {
                            pendingConfirmation = PendingConfirmation("Uninstall app?", app.packageName) {
                                runAppAction("Uninstall", app.packageName, app.label) { apps.uninstall(serial, app.packageName) }
                            }
                        }, enabled = !app.system) { Text("Uninstall") }
                    }
                    Text("Permissions", color = TextPrimary, fontWeight = FontWeight.Bold)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(permissions) { permission ->
                            Text("${permission.granted?.let { if (it) "granted" else "denied" } ?: "declared"}  ${permission.name}", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    Text("Choose an app to launch, stop, clear data, reset permissions, uninstall, or inspect permissions and activities.", color = TextSecondary)
                }
            }
            HorizontalPaneDivider(
                onDrag = { dragY -> localDetailsPaneHeight = (localDetailsPaneHeight + dragY).coerceIn(200f, 800f) },
                onDragEnd = { onPaneChange(localListPaneWidth, localDetailsPaneHeight) },
            )
            PanelCard(Modifier.fillMaxWidth().weight(1f)) {
                Text("Activities", color = TextPrimary, fontWeight = FontWeight.Bold)
                Text(selected?.packageName ?: "Select an app", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LazyColumn(Modifier.fillMaxSize()) {
                    items(activities) { activity ->
                        Text(activity.name, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    pendingConfirmation?.let { confirmation ->
        ConfirmationDialog(confirmation, onDismiss = { pendingConfirmation = null }, onConfirm = {
            pendingConfirmation = null
            confirmation.onConfirm()
        })
    }
}

@Composable
private fun ControlsScreen(devices: DeviceService, mirror: MirrorEngine, serial: String?) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Ready") }
    var fontScale by remember { mutableStateOf("1.0") }
    var animationScale by remember { mutableStateOf("1.0") }

    fun run(label: String, command: List<String>) {
        if (serial == null) {
            status = "Select an online device"
            return
        }
        scope.launch {
            val result = devices.shell(serial, command)
            status = "$label: " + if (result.isSuccess) result.stdout.ifBlank { "ok" } else result.stderr.ifBlank { result.stdout }
        }
    }

    fun key(label: String, input: MirrorInput) {
        scope.launch {
            val result = mirror.sendInput(input)
            status = "$label: " + if (result.isSuccess) result.stdout.ifBlank { "ok" } else result.stderr.ifBlank { result.stdout }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Toolbar("Controls", status)
        PanelCard {
            Text("Radios and display", color = TextPrimary, fontWeight = FontWeight.Bold)
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { run("Airplane on", listOf("cmd", "connectivity", "airplane-mode", "enable")) }) { Text("Airplane on") }
                OutlinedButton(onClick = { run("Airplane off", listOf("cmd", "connectivity", "airplane-mode", "disable")) }) { Text("Airplane off") }
                Button(onClick = { run("WiFi on", listOf("svc", "wifi", "enable")) }) { Text("WiFi on") }
                OutlinedButton(onClick = { run("WiFi off", listOf("svc", "wifi", "disable")) }) { Text("WiFi off") }
                Button(onClick = { run("Data on", listOf("svc", "data", "enable")) }) { Text("Data on") }
                OutlinedButton(onClick = { run("Data off", listOf("svc", "data", "disable")) }) { Text("Data off") }
            }
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { run("Bluetooth on", listOf("cmd", "bluetooth_manager", "enable")) }) { Text("Bluetooth on") }
                OutlinedButton(onClick = { run("Bluetooth off", listOf("cmd", "bluetooth_manager", "disable")) }) { Text("Bluetooth off") }
                Button(onClick = { run("Dark mode on", listOf("cmd", "uimode", "night", "yes")) }) { Text("Dark on") }
                OutlinedButton(onClick = { run("Dark mode off", listOf("cmd", "uimode", "night", "no")) }) { Text("Dark off") }
            }
            FormRow("Font scale") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(fontScale, { fontScale = it }, singleLine = true, modifier = Modifier.width(110.dp).height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
                    Button(onClick = { run("Font scale", listOf("settings", "put", "system", "font_scale", fontScale)) }) { Text("Apply") }
                }
            }
        }
        PanelCard {
            Text("Debug values", color = TextPrimary, fontWeight = FontWeight.Bold)
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { run("Show taps on", listOf("settings", "put", "system", "show_touches", "1")) }) { Text("Taps on") }
                OutlinedButton(onClick = { run("Show taps off", listOf("settings", "put", "system", "show_touches", "0")) }) { Text("Taps off") }
                Button(onClick = { run("Pointer on", listOf("settings", "put", "system", "pointer_location", "1")) }) { Text("Pointer on") }
                OutlinedButton(onClick = { run("Pointer off", listOf("settings", "put", "system", "pointer_location", "0")) }) { Text("Pointer off") }
                Button(onClick = { run("Bounds on", listOf("setprop", "debug.layout", "true")) }) { Text("Bounds on") }
                OutlinedButton(onClick = { run("Bounds off", listOf("setprop", "debug.layout", "false")) }) { Text("Bounds off") }
            }
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { run("Do not keep on", listOf("settings", "put", "global", "always_finish_activities", "1")) }) { Text("No keep on") }
                OutlinedButton(onClick = { run("Do not keep off", listOf("settings", "put", "global", "always_finish_activities", "0")) }) { Text("No keep off") }
                TextField(animationScale, { animationScale = it }, singleLine = true, modifier = Modifier.width(110.dp).height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
                Button(onClick = {
                    run("Animation scale", listOf("sh", "-c", "settings put global window_animation_scale $animationScale; settings put global transition_animation_scale $animationScale; settings put global animator_duration_scale $animationScale"))
                }) { Text("Apply anim") }
            }
        }
        PanelCard {
            Text("Hardware buttons", color = TextPrimary, fontWeight = FontWeight.Bold)
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { key("Power", MirrorInput.Power) }) { Text("Power") }
                Button(onClick = { key("Vol up", MirrorInput.Key(24)) }) { Text("Vol +") }
                Button(onClick = { key("Vol down", MirrorInput.Key(25)) }) { Text("Vol -") }
                Button(onClick = { key("Recents", MirrorInput.Recents) }) { Text("Recents") }
                Button(onClick = { key("Home", MirrorInput.Home) }) { Text("Home") }
                Button(onClick = { key("Back", MirrorInput.Back) }) { Text("Back") }
                Button(onClick = { run("Rotate", listOf("settings", "put", "system", "user_rotation", "1")) }) { Text("Rotate") }
            }
        }
    }
}

@Composable
private fun FilesScreen(files: FileService, serial: String?) {
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("/sdcard") }
    var rows by remember { mutableStateOf<List<DeviceFile>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    fun load(targetPath: String = path) {
        if (serial == null) return
        scope.launch {
            path = targetPath
            runCatching { files.list(serial, targetPath) }
                .onSuccess { rows = it; message = "${it.size} entries" }
                .onFailure { message = it.message ?: "Failed" }
        }
    }
    LaunchedEffect(serial) { load() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(path, { path = it }, singleLine = true, modifier = Modifier.weight(1f).height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
            Button(onClick = { load() }) { Text("Browse") }
            OutlinedButton(onClick = { load(parentPath(path)) }) { Text("Up") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("/", "/sdcard", "/data/local/tmp", "/storage/emulated/0").forEach { root ->
                FilterPill(root, path == root, Cyan) { load(root) }
            }
        }
        if (message.isNotBlank()) Text(message, color = Rust)
        TableHeader(listOf("MODE" to 120.dp, "SIZE" to 100.dp, "MODIFIED" to 190.dp, "NAME" to 1.dp))
        LazyColumn {
            items(rows) { file ->
                TableRow(modifier = Modifier.clickable {
                    if (file.isDirectory) {
                        load(file.path)
                    }
                }) {
                    MonoCell(file.permissions ?: "-", 120.dp, TextSecondary)
                    MonoCell(file.sizeBytes?.toString() ?: "-", 100.dp, TextSecondary)
                    MonoCell(file.modified ?: "-", 190.dp, TextSecondary)
                    MonoCell(if (file.isDirectory) "${file.name}/" else file.name, 1.dp, if (file.isDirectory) Cyan else TextPrimary, Modifier.weight(1f))
                }
            }
        }
    }
}

private fun parentPath(path: String): String {
    val trimmed = path.trimEnd('/').ifBlank { "/" }
    if (trimmed == "/") return "/"
    return trimmed.substringBeforeLast('/', "").ifBlank { "/" }
}


@Composable
internal fun DeviceLivePanel(services: AndyServices, serial: String?, device: AndroidDevice?, modifier: Modifier = Modifier, showChromeControls: Boolean = true) {
    val scope = rememberCoroutineScope()
    var mirrorStatus by remember { mutableStateOf("Disconnected") }
    var connectResult by remember { mutableStateOf("") }
    val sendMirrorInput = rememberMirrorInputSender(services, serial)
    LaunchedEffect(services.mirror) {
        services.mirror.status.collectLatest { mirrorStatus = it }
    }
    fun connect() {
        if (serial != null) {
            scope.launch {
                val result = services.mirror.connect(serial)
                connectResult = if (result.isSuccess) result.stdout.ifBlank { "Connected" } else result.stderr
            }
        }
    }
    LaunchedEffect(serial) {
        connectResult = ""
        connect()
    }
    MirrorFrameContent(services.mirror, serial) { frameFlow, frame ->
        LiveDevicePane(
            serial = serial,
            device = device,
            frame = frame,
            frameFlow = frameFlow,
            mirrorStatus = mirrorStatus,
            connectResult = connectResult,
            modifier = modifier,
            showChromeControls = showChromeControls,
            onInput = sendMirrorInput,
            onConnect = ::connect,
        )
    }
}

@Composable
private fun PerformanceScreen(
    services: AndyServices,
    serial: String?,
    device: AndroidDevice?,
    processesPaneWidth: Float,
    onProcessesPaneWidthChange: (Float) -> Unit,
    liveVisible: Boolean,
    onLiveVisibleChange: (Boolean) -> Unit,
    livePaneWidth: Float,
    onLivePaneWidthChange: (Float) -> Unit,
) {
    var samples by remember { mutableStateOf<List<PerformanceSample>>(emptyList()) }
    var localProcessesPaneWidth by remember(processesPaneWidth) { mutableStateOf(processesPaneWidth) }
    var localLivePaneWidth by remember(livePaneWidth) { mutableStateOf(livePaneWidth) }
    LaunchedEffect(serial) {
        samples = emptyList()
        if (serial != null) services.metrics.stream(serial, null).collectLatest { samples = (samples + it).takeLast(60) }
    }
    val latest = samples.lastOrNull()
    val recentFrames = samples.flatMap { it.frameRenderTimes }.takeLast(60)
    val cpuSeries = samples.map { it.cpuPercent ?: 0f }
    val memorySeries = samples.map { it.memoryMb ?: 0f }
    val networkSeries = samples.map { (it.networkRxKbps ?: 0f) + (it.networkTxKbps ?: 0f) }
    val fpsSeries = samples.map { it.fps ?: 0f }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Toolbar("Performance", "process CPU/memory · network · frame render time")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PerformanceChartCard(
                    title = "CPU",
                    valueText = latest?.cpuPercent?.let { "${it.toInt()}" } ?: "-",
                    unitText = "%",
                    caption = "4 cores · avg ${cpuSeries.takeIf { it.isNotEmpty() }?.average()?.toInt() ?: 0}%",
                    values = cpuSeries,
                    maxValue = 100f,
                    lineColor = Rust,
                    modifier = Modifier.weight(1f),
                )
                PerformanceChartCard(
                    title = "Memory",
                    valueText = latest?.memoryMb?.let { "${it.toInt()}" } ?: "-",
                    unitText = "MB",
                    caption = "peak ${memorySeries.maxOrNull()?.toInt() ?: 0} MB",
                    values = memorySeries,
                    maxValue = (memorySeries.maxOrNull() ?: 0f).coerceAtLeast(256f) * 1.15f,
                    lineColor = Cyan,
                    modifier = Modifier.weight(1f),
                )
                PerformanceChartCard(
                    title = "Network",
                    valueText = latest?.let { ((it.networkRxKbps ?: 0f) + (it.networkTxKbps ?: 0f)).toInt().toString() } ?: "-",
                    unitText = "KB/s",
                    caption = "down ${latest?.networkRxKbps?.toInt() ?: 0} · up ${latest?.networkTxKbps?.toInt() ?: 0} KB/s",
                    values = networkSeries,
                    maxValue = (networkSeries.maxOrNull() ?: 0f).coerceAtLeast(64f) * 1.15f,
                    lineColor = Green,
                    modifier = Modifier.weight(1f),
                )
                PerformanceChartCard(
                    title = "FPS",
                    valueText = latest?.fps?.toInt()?.toString() ?: "Idle",
                    unitText = if (latest?.fps != null) "fps" else "",
                    caption = recentFrames.takeIf { it.isNotEmpty() }?.let { frames -> "${frames.size} frames sampled · ${frames.count { it.millis <= 16.6f }} green" } ?: "No active rendering",
                    values = fpsSeries,
                    maxValue = (fpsSeries.maxOrNull() ?: 60f).coerceAtLeast(60f) * 1.1f,
                    lineColor = Yellow,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.width(localProcessesPaneWidth.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TableHeader(listOf("PID" to 80.dp, "CPU" to 70.dp, "MEM" to 90.dp, "PROCESS" to 1.dp))
                    LazyColumn {
                        items(latest?.processes.orEmpty()) { process ->
                            TableRow {
                                MonoCell(process.pid, 80.dp, TextSecondary)
                                MonoCell(process.cpuPercent?.let { "%.1f%%".format(it) } ?: "-", 70.dp, if ((process.cpuPercent ?: 0f) > 10f) Rust else TextPrimary)
                                MonoCell(process.memoryMb?.let { "%.1f".format(it) } ?: "-", 90.dp, TextSecondary)
                                MonoCell(process.name, 1.dp, TextPrimary, Modifier.weight(1f))
                            }
                        }
                    }
                }
                PaneDivider(
                    onDrag = { dragX -> localProcessesPaneWidth = (localProcessesPaneWidth + dragX).coerceIn(360f, 1300f) },
                    onDragEnd = { onProcessesPaneWidthChange(localProcessesPaneWidth) },
                )
                PanelCard(Modifier.fillMaxSize().padding(start = 6.dp).weight(1f)) {
                    Text("Frame rendering", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text("Green <= 16.6 ms, red is slower than 60 fps.", color = TextSecondary, fontSize = 12.sp)
                    Canvas(Modifier.fillMaxWidth().height(190.dp)) {
                        val frames = recentFrames
                        val barWidth = if (frames.isEmpty()) size.width else size.width / frames.size
                        frames.forEachIndexed { index, frame ->
                            val height = (frame.millis.coerceIn(0f, 50f) / 50f) * size.height
                            drawRect(
                                color = if (frame.millis <= 16.6f) Green else Red,
                                topLeft = Offset(index * barWidth, size.height - height),
                                size = androidx.compose.ui.geometry.Size((barWidth - 1f).coerceAtLeast(1f), height),
                            )
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(recentFrames) { frame ->
                            Text("${frame.label}  ${"%.2f".format(frame.millis)} ms", color = if (frame.millis <= 16.6f) Green else Red, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = liveVisible,
            modifier = Modifier.fillMaxHeight(),
            enter = expandHorizontally(animationSpec = tween(220)) + fadeIn(animationSpec = tween(220)),
            exit = shrinkHorizontally(animationSpec = tween(220)) + fadeOut(animationSpec = tween(160)),
        ) {
            Row(Modifier.fillMaxHeight()) {
                PaneDivider(
                    onDrag = { dragX -> localLivePaneWidth = (localLivePaneWidth - dragX).coerceIn(220f, 700f) },
                    onDragEnd = { onLivePaneWidthChange(localLivePaneWidth) },
                )
                DeviceLivePanel(
                    services = services,
                    serial = serial,
                    device = device,
                    modifier = Modifier.width(localLivePaneWidth.dp).fillMaxHeight(),
                    showChromeControls = false,
                )
            }
        }
    }
}

@Composable
private fun DesignScreen(services: AndyServices, serial: String?, device: AndroidDevice?, devicePaneWidth: Float, onDevicePaneWidthChange: (Float) -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Design overlays") }
    var mirrorStatus by remember { mutableStateOf("Disconnected") }
    var connectResult by remember { mutableStateOf("") }
    var grid by remember { mutableStateOf(false) }
    var ruler by remember { mutableStateOf(false) }
    var gridSize by remember { mutableStateOf("16") }
    var rulerX by remember { mutableStateOf("540") }
    var rulerY by remember { mutableStateOf("960") }
    var color by remember { mutableStateOf(Cyan) }
    var pickerEnabled by remember { mutableStateOf(false) }
    var pickedColor by remember { mutableStateOf("#------") }
    var pickerToast by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableStateOf("1.0") }
    var localDevicePaneWidth by remember(devicePaneWidth) { mutableStateOf(devicePaneWidth.coerceAtLeast(760f)) }
    val sendMirrorInput = rememberMirrorInputSender(services, serial)
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    LaunchedEffect(pickerToast) {
        if (pickerToast != null) {
            delay(1800)
            pickerToast = null
        }
    }
    LaunchedEffect(Unit) {
        services.mirror.status.collectLatest { mirrorStatus = it }
    }
    LaunchedEffect(serial) {
        if (serial != null) {
            val result = services.mirror.connect(serial)
            connectResult = if (result.isSuccess) result.stdout else result.stderr
        }
    }
    Row(Modifier.fillMaxSize()) {
        MirrorFrameContent(services.mirror, serial) { frameFlow, frame ->
            LiveDevicePane(
                serial = serial,
                device = device,
                frame = frame,
                frameFlow = frameFlow,
                mirrorStatus = mirrorStatus,
                connectResult = connectResult,
                modifier = Modifier.width(localDevicePaneWidth.dp).fillMaxHeight(),
                showRuler = ruler,
                rulerX = rulerX.toFloatOrNull()?.coerceIn(0f, 3000f) ?: 540f,
                rulerY = rulerY.toFloatOrNull()?.coerceIn(0f, 3000f) ?: 960f,
                gridSize = if (grid) gridSize.toFloatOrNull()?.coerceIn(2f, 120f) else null,
                gridColor = color.copy(alpha = 0.38f),
                pickerColor = color.takeIf { pickerEnabled },
                pickerHex = pickedColor,
                zoom = zoom.toFloatOrNull()?.coerceIn(0.5f, 4f) ?: 1f,
                onHoverColor = { hex ->
                    if (pickerEnabled) pickedColor = hex
                },
                passThroughInput = !pickerEnabled,
                onPickerClick = { hex ->
                    pickedColor = hex
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(hex))
                    pickerToast = "Copied $hex"
                },
                onRulerResize = { x, y ->
                    rulerX = x.toInt().toString()
                    rulerY = y.toInt().toString()
                },
                onInput = sendMirrorInput,
                onConnect = {
                    if (serial != null) scope.launch {
                        val result = services.mirror.connect(serial)
                        connectResult = if (result.isSuccess) result.stdout else result.stderr
                    }
                },
            )
        }
        Spacer(Modifier.width(6.dp))
        PaneDivider(
            onDrag = { dragX -> localDevicePaneWidth = (localDevicePaneWidth + dragX).coerceIn(640f, 1900f) },
            onDragEnd = { onDevicePaneWidthChange(localDevicePaneWidth) },
        )
        PanelCard(Modifier.fillMaxSize().padding(start = 6.dp)) {
            Text("Design overlay", color = TextPrimary, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPill("Ruler", ruler, Yellow) { ruler = !ruler }
                FilterPill("Grid", grid, Cyan) { grid = !grid }
                FilterPill("Picker", pickerEnabled, Rust) { pickerEnabled = !pickerEnabled }
            }
            FormRow("Grid size") {
                TextField(gridSize, { gridSize = it.filter { ch -> ch.isDigit() || ch == '.' } }, singleLine = true, modifier = Modifier.width(120.dp).height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
            }
            FormRow("Ruler X/Y") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(rulerX, { rulerX = it.filter { ch -> ch.isDigit() || ch == '.' } }, singleLine = true, modifier = Modifier.width(110.dp).height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
                    TextField(rulerY, { rulerY = it.filter { ch -> ch.isDigit() || ch == '.' } }, singleLine = true, modifier = Modifier.width(110.dp).height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
                }
            }
            FormRow("Zoom") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        val next = ((zoom.toFloatOrNull() ?: 1f) - 0.25f).coerceIn(0.5f, 4f)
                        zoom = "%.2f".format(next)
                    }) { Text("-") }
                    TextField(zoom, { zoom = it.filter { ch -> ch.isDigit() || ch == '.' } }, singleLine = true, modifier = Modifier.width(96.dp).height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
                    OutlinedButton(onClick = {
                        val next = ((zoom.toFloatOrNull() ?: 1f) + 0.25f).coerceIn(0.5f, 4f)
                        zoom = "%.2f".format(next)
                    }) { Text("+") }
                }
            }
            Text("Grid color", color = TextPrimary, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(Cyan, Rust, Green, Yellow, Red, Color.White).forEach { swatch ->
                    Box(
                        Modifier.size(34.dp)
                            .background(swatch, RoundedCornerShape(6.dp))
                            .border(if (swatch == color) 2.dp else 1.dp, if (swatch == color) TextPrimary else Border, RoundedCornerShape(6.dp))
                            .clickable { color = swatch },
                    )
                }
            }
            Text("Color picker", color = TextPrimary, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(18.dp)
                        .background(pickedColor.toColorOrNull() ?: Color.Transparent, RoundedCornerShape(4.dp))
                        .border(1.dp, Border, RoundedCornerShape(4.dp))
                )
                Text("Under pointer $pickedColor · swatch ${color.toHex()}", color = if (pickerEnabled) TextPrimary else TextSecondary)
            }
            AnimatedVisibility(
                visible = pickerToast != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(
                    Modifier
                        .background(Rust.copy(alpha = 0.18f), RoundedCornerShape(AndyRadius.Pill))
                        .border(1.dp, Rust.copy(alpha = 0.45f), RoundedCornerShape(AndyRadius.Pill))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(pickerToast.orEmpty(), color = Rust, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
            Text(status, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

private fun String.toColorOrNull(): Color? {
    if (!matches(Regex("""#[0-9A-Fa-f]{6}"""))) return null
    return Color(
        red = substring(1, 3).toInt(16),
        green = substring(3, 5).toInt(16),
        blue = substring(5, 7).toInt(16),
    )
}

private fun Color.toHex(): String {
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    return "#%02X%02X%02X".format(r, g, b)
}


@Composable
private fun AppIconCell(serial: String, packageName: String, apps: AppService, cache: MutableMap<String, ByteArray?>) {
    val icon = cache[packageName]
    LaunchedEffect(serial, packageName) {
        if (!cache.containsKey(packageName)) {
            val bytes = runCatching { apps.getIcon(serial, packageName) }.getOrNull()
            cache[packageName] = bytes
        }
    }
    val bitmap = remember(icon) { icon?.let { loadImageBitmap(it) } }
    Box(
        Modifier.padding(vertical = 4.dp).size(48.dp).clip(RoundedCornerShape(AndyRadius.R4)).background(AndyColors.Neutral750),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PerformanceChartCard(
    title: String,
    valueText: String,
    unitText: String,
    caption: String,
    values: List<Float>,
    maxValue: Float,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    PanelCard(modifier.height(190.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title.lowercase(), color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(valueText, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = MonoFont)
                if (unitText.isNotEmpty()) {
                    Text(" ${unitText.lowercase()}", color = TextSecondary, fontSize = 12.sp, fontFamily = MonoFont, modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            if (values.size < 2) return@Canvas
            val safeMax = maxValue.takeIf { it > 0f } ?: 1f
            val stepX = size.width / (values.size - 1)
            val points = values.mapIndexed { index, value ->
                Offset(index * stepX, size.height - (value.coerceIn(0f, safeMax) / safeMax) * size.height)
            }
            val linePath = androidx.compose.ui.graphics.Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            val fillPath = androidx.compose.ui.graphics.Path().apply {
                addPath(linePath)
                lineTo(points.last().x, size.height)
                lineTo(points.first().x, size.height)
                close()
            }
            drawPath(fillPath, brush = Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.32f), lineColor.copy(alpha = 0.02f))))
            drawPath(linePath, color = lineColor, style = Stroke(width = 2f))
        }
        Text(caption.lowercase(), color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
    }
}


@Composable
private fun UpdateInstallConfirmationDialog(
    update: AvailableUpdate,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Install Andy ${update.versionName}?",
                color = AndyColors.Neutral100,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The update has been downloaded and verified.",
                    color = AndyColors.Neutral200,
                    fontSize = 14.sp
                )
                Text(
                    "Andy will close and open the installer. After the installation is complete, you can relaunch the application.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = Rust)
            ) {
                Text("Close and install", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
            ) {
                Text("Later")
            }
        },
        containerColor = PanelSoft,
        titleContentColor = AndyColors.Neutral100,
        textContentColor = AndyColors.Neutral300
    )
}

@Composable
private fun SettingsScreen(
    workspaceState: WorkspaceState,
    onUpdateWorkspace: ((WorkspaceState) -> WorkspaceState) -> Unit,
    services: AndyServices
) {
    val scope = rememberCoroutineScope()
    var portText by remember(workspaceState.mcpServerPort) { mutableStateOf(workspaceState.mcpServerPort.toString()) }
    val clientOptions = remember { services.mcp.getClients() }
    val toolNames = remember { services.mcp.getToolNames() }
    var selectedClientLabel by remember { mutableStateOf(clientOptions.firstOrNull() ?: "Claude Code") }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var operationStatus by remember { mutableStateOf<String?>(null) }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    val mcpStatus by services.mcp.status.collectAsState("stopped")
    val mcpRunning by services.mcp.running.collectAsState(false)
    val proxyStatus by services.proxy.status.collectAsState("Proxy stopped")
    val proxyRunning = proxyStatus.contains("listening on")

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("settings", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp, fontFamily = MonoFont)

        PanelCard {
            Text("HTTP debug proxy", color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(
                "Start Andy's mitmdump capture proxy automatically when the app opens.",
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            Spacer(Modifier.height(4.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = workspaceState.proxyStartOnLaunch,
                        onCheckedChange = { checked ->
                            onUpdateWorkspace { it.copy(proxyStartOnLaunch = checked) }
                        }
                    )
                    Text("Start proxy on app launch", color = TextPrimary, fontSize = 13.sp)
                }

                Spacer(Modifier.width(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Proxy Status:", color = TextSecondary, fontSize = 12.sp)
                    GlowingDot(proxyRunning)
                    Text(proxyStatus, color = if (proxyRunning) Green else Rust, fontSize = 12.sp, fontFamily = MonoFont, fontWeight = FontWeight.Bold)
                }
            }
        }

        PanelCard {
            Text("Model Context Protocol (MCP) Server", color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(
                "Expose Andy's Android control automation capabilities as an MCP server. This allows external AI coding assistants (e.g. Claude Code, Codex, Cursor) to interact with connected emulators and devices.",
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            Spacer(Modifier.height(4.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        workspaceState.mcpServerEnabled,
                        { checked ->
                            onUpdateWorkspace { it.copy(mcpServerEnabled = checked) }
                        }
                    )
                    Text("Enable MCP Server", color = TextPrimary, fontSize = 13.sp)
                }

                Spacer(Modifier.width(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Port:", color = TextSecondary, fontSize = 13.sp)
                    TextField(
                        portText,
                        {
                            val filtered = it.filter(Char::isDigit).take(5)
                            portText = filtered
                            filtered.toIntOrNull()?.takeIf { value -> value in 1..65535 }?.let { newPort ->
                                onUpdateWorkspace { state -> state.copy(mcpServerPort = newPort) }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.width(96.dp).height(50.dp),
                        textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        colors = fieldColors(),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Server Status:", color = TextSecondary, fontSize = 12.sp)
                GlowingDot(mcpRunning)
                Text(mcpStatus, color = if (mcpRunning) Green else Rust, fontSize = 12.sp, fontFamily = MonoFont, fontWeight = FontWeight.Bold)
            }
        }

        PanelCard {
            Text("Available Tools", color = TextPrimary, fontWeight = FontWeight.Bold)
            Text("${toolNames.size} MCP tool calls exposed by Andy", color = TextSecondary, fontSize = 12.sp)
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                toolNames.sorted().forEach { tool ->
                    Box(
                        Modifier
                            .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.Pill))
                            .border(1.dp, Border, RoundedCornerShape(AndyRadius.Pill))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(tool, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
        }

        PanelCard {
            Text("Client Configurations", color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(
                "Configure your local AI coding tool to connect to Andy's MCP endpoint.",
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Client:", color = TextSecondary, fontSize = 13.sp)
                Box {
                    Button(
                        onClick = { dropdownExpanded = true },
                        colors = ButtonDefaults.buttonColors(containerColor = AndyColors.Neutral750)
                    ) {
                        Text(selectedClientLabel, color = TextPrimary)
                    }
                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                        containerColor = AndyColors.Neutral750
                    ) {
                        clientOptions.forEach { client ->
                            DropdownMenuItem(
                                text = { Text(client, color = TextPrimary) },
                                onClick = {
                                    selectedClientLabel = client
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isAutoWriteSupported = services.mcp.isAutoWriteSupported(selectedClientLabel)

                Button(
                    onClick = {
                        val success = services.mcp.writeConfig(selectedClientLabel, workspaceState.mcpServerPort)
                        operationStatus = if (success) {
                            "Successfully updated configuration for $selectedClientLabel (backed up original)."
                        } else {
                            "Failed to write configuration file."
                        }
                    },
                    enabled = isAutoWriteSupported
                ) {
                    Text("Add to config")
                }

                Button(
                    onClick = {
                        val snippet = services.mcp.getSnippet(selectedClientLabel, workspaceState.mcpServerPort)
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(snippet))
                        operationStatus = "Snippet copied to clipboard!"
                    }
                ) {
                    Text("Copy snippet")
                }
            }

            operationStatus?.let { status ->
                Text(status, color = Rust, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
            }

            Spacer(Modifier.height(4.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R3))
                    .border(1.dp, AndyColors.OrangeBorder.copy(alpha = 0.45f), RoundedCornerShape(AndyRadius.R3))
                    .padding(12.dp)
            ) {
                Text("Configuration Snippet ($selectedClientLabel)", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                val snippet = services.mcp.getSnippet(selectedClientLabel, workspaceState.mcpServerPort)
                SelectionContainer {
                    Text(
                        snippet,
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}
