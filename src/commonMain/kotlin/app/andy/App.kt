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
import app.andy.andy.generated.resources.hardware_bug
import app.andy.andy.generated.resources.hardware_capture
import app.andy.andy.generated.resources.hardware_clipboard
import app.andy.andy.generated.resources.hardware_pop_out
import app.andy.andy.generated.resources.hardware_power
import app.andy.andy.generated.resources.hardware_record
import app.andy.andy.generated.resources.hardware_rotate
import app.andy.andy.generated.resources.hardware_volume_down
import app.andy.andy.generated.resources.hardware_volume_up
import app.andy.andy.generated.resources.intellij_filetype_anyType_dark
import app.andy.andy.generated.resources.intellij_filetype_c_dark
import app.andy.andy.generated.resources.intellij_filetype_config_dark
import app.andy.andy.generated.resources.intellij_filetype_cpp_dark
import app.andy.andy.generated.resources.intellij_filetype_css_dark
import app.andy.andy.generated.resources.intellij_filetype_csv_dark
import app.andy.andy.generated.resources.intellij_filetype_docker_dark
import app.andy.andy.generated.resources.intellij_filetype_gitignore
import app.andy.andy.generated.resources.intellij_filetype_gradle_dark
import app.andy.andy.generated.resources.intellij_filetype_groovy_dark
import app.andy.andy.generated.resources.intellij_filetype_h_dark
import app.andy.andy.generated.resources.intellij_filetype_html_dark
import app.andy.andy.generated.resources.intellij_filetype_image_dark
import app.andy.andy.generated.resources.intellij_filetype_javaScript_dark
import app.andy.andy.generated.resources.intellij_filetype_java_dark
import app.andy.andy.generated.resources.intellij_filetype_json_dark
import app.andy.andy.generated.resources.intellij_filetype_kotlinScript_dark
import app.andy.andy.generated.resources.intellij_filetype_kotlin_dark
import app.andy.andy.generated.resources.intellij_filetype_markdown_dark
import app.andy.andy.generated.resources.intellij_filetype_modified_dark
import app.andy.andy.generated.resources.intellij_filetype_properties_dark
import app.andy.andy.generated.resources.intellij_filetype_shell_dark
import app.andy.andy.generated.resources.intellij_filetype_sql_dark
import app.andy.andy.generated.resources.intellij_filetype_text_dark
import app.andy.andy.generated.resources.intellij_filetype_toml_dark
import app.andy.andy.generated.resources.intellij_filetype_unknown_dark
import app.andy.andy.generated.resources.intellij_filetype_xml_dark
import app.andy.andy.generated.resources.intellij_filetype_yaml_dark
import app.andy.andy.generated.resources.intellij_node_folder_dark
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.DrawableResource

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

private data class SetupRequirement(
    val label: String,
    val ok: Boolean,
    val readyText: String,
    val missingText: String,
    val installCommand: String? = null,
)

@Composable
private fun rememberMirrorInputSender(
    services: AndyServices,
    serial: String?,
    enabled: Boolean = true,
    recordActions: Boolean = true,
): (MirrorInput) -> Unit {
    val currentSerial by rememberUpdatedState(serial)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentRecordActions by rememberUpdatedState(recordActions)
    var latestAccessibilityRoot by remember { mutableStateOf<AccessibilityNode?>(null) }
    val currentAccessibilityRoot by rememberUpdatedState(latestAccessibilityRoot)
    var touchGesture by remember { mutableStateOf<BugTouchGesture?>(null) }
    var tapAccessibilityLookup by remember { mutableStateOf<Deferred<AccessibilityNode?>?>(null) }
    val scope = rememberCoroutineScope()
    val channel = remember(services.mirror) { Channel<MirrorInput>(Channel.UNLIMITED) }
    LaunchedEffect(services.bugs, services.accessibility, serial) {
        services.bugs.status.collectLatest { status ->
            val activeSerial = serial
            if (!status.active || status.deviceSerial != activeSerial || activeSerial == null) {
                latestAccessibilityRoot = null
                return@collectLatest
            }
            while (true) {
                latestAccessibilityRoot = try {
                    services.accessibility.dump(activeSerial)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                delay(BugAccessibilitySnapshotMillis)
            }
        }
    }
    LaunchedEffect(channel, services.mirror) {
        for (input in channel) {
            if (currentEnabled && currentSerial != null) {
                services.mirror.sendInput(input)
            }
        }
    }
    DisposableEffect(channel) {
        onDispose { channel.close() }
    }
    return remember(channel) {
        { input ->
            if (currentEnabled && currentSerial != null && currentRecordActions) {
                when (input) {
                    is MirrorInput.Touch -> {
                        val now = System.currentTimeMillis()
                        when (input.action) {
                            MirrorTouchAction.Down -> {
                                touchGesture = BugTouchGesture(input.x, input.y, input.x, input.y, now)
                                tapAccessibilityLookup?.cancel()
                                tapAccessibilityLookup = scope.async {
                                    val activeSerial = currentSerial ?: return@async null
                                    services.accessibility.dump(activeSerial)
                                }
                            }
                            MirrorTouchAction.Move -> {
                                touchGesture = touchGesture?.copy(lastX = input.x, lastY = input.y, moved = true)
                            }
                            MirrorTouchAction.Up -> {
                                val gesture = touchGesture
                                touchGesture = null
                                val (label, detail) = if (gesture != null && gesture.isSwipeTo(input.x, input.y)) {
                                    tapAccessibilityLookup?.cancel()
                                    tapAccessibilityLookup = null
                                    mirrorSwipeBugText(
                                        startX = gesture.startX,
                                        startY = gesture.startY,
                                        endX = input.x,
                                        endY = input.y,
                                        durationMillis = (now - gesture.startedAtMillis).toInt().coerceAtLeast(0),
                                    )
                                } else {
                                    null to null
                                }
                                if (label != null) {
                                    services.bugs.recordAction("input", label, detail)
                                } else {
                                    val lookup = tapAccessibilityLookup
                                    tapAccessibilityLookup = null
                                    scope.launch {
                                        val root = lookup?.let {
                                            try {
                                                withTimeoutOrNull(BugTapAccessibilityLookupMillis) { it.await() }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                null
                                            }
                                        }
                                            ?: currentAccessibilityRoot
                                        val (tapLabel, tapDetail) = mirrorTapBugText(input.x, input.y, root)
                                        services.bugs.recordAction("input", tapLabel, tapDetail)
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        val (label, detail) = mirrorInputBugText(input, currentAccessibilityRoot)
                        services.bugs.recordAction("input", label, detail)
                    }
                }
            }
            if (channel.trySend(input).isFailure) Unit
        }
    }
}

private data class BugTouchGesture(
    val startX: Int,
    val startY: Int,
    val lastX: Int,
    val lastY: Int,
    val startedAtMillis: Long,
    val moved: Boolean = false,
) {
    fun isSwipeTo(endX: Int, endY: Int): Boolean {
        val dx = endX - startX
        val dy = endY - startY
        return moved && dx * dx + dy * dy >= BugTapMaxDistancePx * BugTapMaxDistancePx
    }
}

private const val BugAccessibilitySnapshotMillis = 1_500L
private const val BugTapAccessibilityLookupMillis = 350L
private const val BugTapMaxDistancePx = 24

@Composable
private fun MirrorFrameContent(mirror: MirrorEngine, resetKey: Any?, content: @Composable (Flow<MirrorFrame>, MirrorFrame?) -> Unit) {
    var frame by remember(mirror, resetKey) { mutableStateOf<MirrorFrame?>(null) }
    LaunchedEffect(mirror, resetKey) {
        mirror.frames.collectLatest { next ->
            if (next.width <= 1 || next.height <= 1) {
                // connect()/disconnect() push a 1x1 sentinel; clear the frame so the
                // surface releases its last image instead of freezing on it.
                frame = null
                return@collectLatest
            }
            val previous = frame
            if (
                previous == null ||
                previous.width != next.width ||
                previous.height != next.height ||
                next.frameNumber % MirrorMetadataFrameInterval == 0L
            ) {
                frame = next
            }
        }
    }
    content(mirror.frames, frame)
}

private const val MirrorMetadataFrameInterval = 30L

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

private fun actionIconMarker(icon: String): String = when (icon.trim().lowercase()) {
    "run" -> "|>"
    "test" -> "|="
    "debug" -> "|!"
    "build" -> "|#"
    "server" -> "|~"
    "deploy" -> "|^"
    else -> "|*"
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

@Composable
private fun DevicesScreen(
    services: AndyServices,
    devices: List<AndroidDevice>,
    sdk: SdkDiscovery,
    onRefresh: () -> Unit,
    onLive: (String) -> Unit,
    onEmulatorStarted: (Set<String>, String) -> Unit,
    onStopEmulator: (AndroidDevice) -> Unit,
    stoppingEmulatorSerial: String?,
    stopStatus: String,
    startingEmulatorName: String?,
    startStatus: String,
) {
    val scope = rememberCoroutineScope()
    var avds by remember { mutableStateOf<List<VirtualDevice>>(emptyList()) }
    var avdStatus by remember { mutableStateOf("") }
    var startingAvd by remember { mutableStateOf<String?>(null) }
    var deviceQuery by remember { mutableStateOf("") }
    var deviceFilter by remember { mutableStateOf(DeviceListFilter.All) }
    var showCreateWizard by remember { mutableStateOf(false) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    var cloneSource by remember { mutableStateOf<VirtualDevice?>(null) }

    fun refreshAvds() {
        scope.launch {
            avds = services.avd.listVirtualDevices()
        }
    }

    LaunchedEffect(Unit) {
        refreshAvds()
    }
    val filteredDevices = devices.filter { device ->
        val matchesQuery = deviceQuery.isBlank() ||
            device.displayName.contains(deviceQuery, true) ||
            device.serial.contains(deviceQuery, true) ||
            device.apiLevel.orEmpty().contains(deviceQuery, true)
        matchesQuery && device.matchesFilter(deviceFilter)
    }
    val filteredAvds = avds.filter { avd ->
        val matchesQuery = deviceQuery.isBlank() ||
            avd.name.contains(deviceQuery, true) ||
            avd.target.orEmpty().contains(deviceQuery, true) ||
            avd.abi.orEmpty().contains(deviceQuery, true)
        matchesQuery && avd.matchesFilter(deviceFilter)
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Toolbar("Devices", "${devices.count { it.kind == DeviceKind.Physical }} physical · ${devices.count { it.kind == DeviceKind.Emulator }} emulators online · ${avds.size} created", onPrimary = {
            onRefresh()
            refreshAvds()
        }, primaryLabel = "Refresh")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                deviceQuery,
                { deviceQuery = it },
                placeholder = { Text("Search devices", color = TextSecondary) },
                singleLine = true,
                modifier = Modifier.width(280.dp).height(54.dp),
                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace),
                colors = fieldColors(),
            )
            DeviceListFilter.entries.forEach { filter ->
                FilterPill(filter.label, deviceFilter == filter, if (deviceFilter == filter) Rust else Cyan) { deviceFilter = filter }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { showCreateWizard = true }, colors = primaryButtonColors()) { Text("Create virtual device") }
        }
        if (sdk.issues.isNotEmpty()) {
            PanelCard {
                Text("SDK setup", color = TextPrimary, fontWeight = FontWeight.Bold)
                sdk.issues.forEach { Text(it, color = TextSecondary, fontSize = 12.sp) }
                Text("SDK: ${sdk.sdkPath ?: "-"}", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
        PanelCard {
            Text("Created emulators", color = TextPrimary, fontWeight = FontWeight.Bold)
            if (startStatus.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (startingEmulatorName != null) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Rust)
                    Text(startStatus, color = if (startingEmulatorName != null) Rust else TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
            if (avdStatus.isNotBlank()) Text(avdStatus, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            if (stopStatus.isNotBlank()) Text(stopStatus, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            if (filteredAvds.isEmpty()) {
                Text("No AVDs found. Create one in Catalog or Android Studio, then refresh.", color = TextSecondary, fontSize = 12.sp)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    filteredAvds.forEach { avd ->
                        val runningDevice = devices.firstOrNull {
                            it.kind == DeviceKind.Emulator &&
                                it.state == DeviceConnectionState.Online &&
                                namesMatch(it.displayName, avd.name)
                        }
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .background(PanelSoft, RoundedCornerShape(AndyRadius.R4))
                                .border(1.dp, Border, RoundedCornerShape(AndyRadius.R4))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(avd.name, color = TextPrimary, fontWeight = FontWeight.Bold)
                                Text(listOfNotNull(avd.target, avd.abi, avd.path).joinToString(" · ").ifBlank { "AVD" }, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(if (runningDevice != null || avd.running) "running" else "stopped", color = if (runningDevice != null || avd.running) Green else TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text(avd.deviceType.name.lowercase(), color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.width(80.dp))
                            OutlinedButton(
                                onClick = {
                                    runningDevice?.let {
                                        onLive(it.serial)
                                        return@OutlinedButton
                                    }
                                    val before = devices.map { it.serial }.toSet()
                                    scope.launch {
                                        startingAvd = avd.name
                                        val result = services.avd.startVirtualDevice(avd.name)
                                        avdStatus = if (result.isSuccess) result.stdout else result.stderr.ifBlank { result.stdout }
                                        startingAvd = null
                                        refreshAvds()
                                        if (result.isSuccess) onEmulatorStarted(before, avd.name)
                                    }
                                },
                                enabled = startingAvd == null && startingEmulatorName == null,
                            ) {
                                Text(
                                    when {
                                        startingEmulatorName == avd.name -> "Booting"
                                        startingAvd == avd.name -> "Starting"
                                        runningDevice != null -> "Live"
                                        else -> "Start"
                                    },
                                )
                            }
                            if (runningDevice != null) {
                                OutlinedButton(
                                    onClick = { onStopEmulator(runningDevice) },
                                    enabled = stoppingEmulatorSerial != runningDevice.serial,
                                ) {
                                    Text(if (stoppingEmulatorSerial == runningDevice.serial) "Stopping" else "Stop")
                                }
                            }
                            AvdActionsMenu(
                                enabled = startingAvd == null && startingEmulatorName == null,
                                onColdBoot = {
                                    val before = devices.map { it.serial }.toSet()
                                    scope.launch {
                                        startingAvd = avd.name
                                        val result = services.avd.coldBootVirtualDevice(avd.name)
                                        avdStatus = if (result.isSuccess) result.stdout else result.stderr.ifBlank { result.stdout }
                                        startingAvd = null
                                        refreshAvds()
                                        if (result.isSuccess) onEmulatorStarted(before, avd.name)
                                    }
                                },
                                onWipe = {
                                    pendingConfirmation = PendingConfirmation("Wipe ${avd.name}?", "This erases user data for the virtual device.") {
                                        scope.launch {
                                            val result = services.avd.wipeVirtualDevice(avd.name)
                                            avdStatus = if (result.isSuccess) result.stdout else result.stderr.ifBlank { result.stdout }
                                            refreshAvds()
                                        }
                                    }
                                },
                                onClone = { cloneSource = avd },
                                onDelete = {
                                    pendingConfirmation = PendingConfirmation("Delete ${avd.name}?", "This removes the AVD from Android SDK device manager.") {
                                        scope.launch {
                                            val result = services.avd.deleteVirtualDevice(avd.name)
                                            avdStatus = if (result.isSuccess) result.stdout.ifBlank { "Deleted ${avd.name}" } else result.stderr.ifBlank { result.stdout }
                                            refreshAvds()
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filteredDevices) { device ->
                val online = device.state == DeviceConnectionState.Online
                val rowShape = RoundedCornerShape(AndyRadius.R4)
                Row(
                    Modifier.fillMaxWidth()
                        .height(64.dp)
                        .background(if (online) AndyColors.GreenSubtle.copy(alpha = 0.82f) else AndyColors.Neutral900.copy(alpha = 0.7f), rowShape)
                        .border(1.dp, if (online) Green.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.05f), rowShape)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(2.dp).fillMaxHeight().background(if (online) Green else TextSecondary, RoundedCornerShape(AndyRadius.R2)))
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.width(260.dp)) {
                        Text(device.displayName, color = TextPrimary, fontWeight = FontWeight.Bold)
                        Text(device.serial, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                    Text("API ${device.apiLevel ?: "-"}\n${device.abi ?: "-"}", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(170.dp))
                    Text(device.storageSummary ?: "-", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(150.dp))
                    StatusTag(device.state.name, if (online) Green else TextSecondary, Modifier.weight(1f))
                    OutlinedButton(onClick = { onLive(device.serial) }) { Text("Live") }
                    if (device.kind == DeviceKind.Emulator && online) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { onStopEmulator(device) },
                            enabled = stoppingEmulatorSerial != device.serial,
                        ) {
                            Text(if (stoppingEmulatorSerial == device.serial) "Stopping" else "Stop")
                        }
                    }
                }
            }
        }
        if (devices.isEmpty()) EmptyState("No connected Android devices. Connect USB debugging or start an emulator.")
        if (showCreateWizard) {
            CreateVirtualDeviceDialog(
                avd = services.avd,
                onDismiss = { showCreateWizard = false },
                onCreated = {
                    avdStatus = it
                    showCreateWizard = false
                    refreshAvds()
                    onRefresh()
                },
            )
        }
        cloneSource?.let { source ->
            CloneAvdDialog(
                source = source,
                onDismiss = { cloneSource = null },
                onClone = { newName ->
                    scope.launch {
                        val result = services.avd.cloneVirtualDevice(source.name, newName)
                        avdStatus = if (result.isSuccess) result.stdout else result.stderr.ifBlank { result.stdout }
                        cloneSource = null
                        refreshAvds()
                    }
                },
            )
        }
        pendingConfirmation?.let { confirmation ->
            ConfirmationDialog(confirmation, onDismiss = { pendingConfirmation = null }, onConfirm = {
                pendingConfirmation = null
                confirmation.onConfirm()
            })
        }
    }
}

private fun namesMatch(left: String, right: String): Boolean {
    return normalizeName(left) == normalizeName(right)
}

private fun normalizeName(value: String): String {
    return value.replace('_', ' ').trim().lowercase()
}

private enum class DeviceListFilter(val label: String) {
    All("All"),
    Running("Running"),
    Phone("Phone"),
    Foldable("Foldable"),
    Tablet("Tablet"),
    Watch("Watch"),
    Tv("TV"),
    Api33("API 33+"),
}

private fun AndroidDevice.matchesFilter(filter: DeviceListFilter): Boolean = when (filter) {
    DeviceListFilter.All -> true
    DeviceListFilter.Running -> state == DeviceConnectionState.Online
    DeviceListFilter.Phone -> kind == DeviceKind.Physical || model.orEmpty().contains("pixel", true) || model.orEmpty().contains("phone", true)
    DeviceListFilter.Foldable -> model.orEmpty().contains("fold", true)
    DeviceListFilter.Tablet -> model.orEmpty().contains("tablet", true)
    DeviceListFilter.Watch -> model.orEmpty().contains("watch", true) || product.orEmpty().contains("wear", true)
    DeviceListFilter.Tv -> model.orEmpty().contains("tv", true) || product.orEmpty().contains("tv", true)
    DeviceListFilter.Api33 -> apiLevel?.toIntOrNull()?.let { it >= 33 } == true
}

private fun VirtualDevice.matchesFilter(filter: DeviceListFilter): Boolean = when (filter) {
    DeviceListFilter.All -> true
    DeviceListFilter.Running -> running
    DeviceListFilter.Phone -> deviceType == VirtualDeviceType.Phone
    DeviceListFilter.Foldable -> deviceType == VirtualDeviceType.Foldable
    DeviceListFilter.Tablet -> deviceType == VirtualDeviceType.Tablet
    DeviceListFilter.Watch -> deviceType == VirtualDeviceType.Watch
    DeviceListFilter.Tv -> deviceType == VirtualDeviceType.Tv
    DeviceListFilter.Api33 -> apiLevel?.let { it >= 33 } == true ||
        Regex("""android-(\d+)""").find(target.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { it >= 33 } == true
}

private data class PendingConfirmation(
    val title: String,
    val message: String,
    val onConfirm: () -> Unit,
)

@Composable
private fun AvdActionsMenu(
    enabled: Boolean,
    onColdBoot: () -> Unit,
    onWipe: () -> Unit,
    onClone: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.width(42.dp), contentPadding = PaddingValues(0.dp)) {
            Text("...")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = PanelSoft) {
            DropdownMenuItem(text = { Text("Cold boot", color = TextPrimary) }, onClick = { expanded = false; onColdBoot() })
            DropdownMenuItem(text = { Text("Wipe data", color = TextPrimary) }, onClick = { expanded = false; onWipe() })
            DropdownMenuItem(text = { Text("Clone", color = TextPrimary) }, onClick = { expanded = false; onClone() })
            DropdownMenuItem(text = { Text("Delete", color = Red) }, onClick = { expanded = false; onDelete() })
        }
    }
}

@Composable
private fun CloneAvdDialog(source: VirtualDevice, onDismiss: () -> Unit, onClone: (String) -> Unit) {
    var name by remember(source.name) { mutableStateOf("${source.name}_Copy") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Clone ${source.name}", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            LabeledField("New name", name, { name = it.filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' } }, Modifier.fillMaxWidth())
        },
        confirmButton = {
            Button(onClick = { onClone(name) }, enabled = name.isNotBlank(), colors = primaryButtonColors()) { Text("Clone") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmationDialog(confirmation: PendingConfirmation, onDismiss: () -> Unit, onConfirm: () -> Unit) {
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
private fun CreateVirtualDeviceDialog(
    avd: AvdService,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf<List<AvdProfile>>(emptyList()) }
    var images by remember { mutableStateOf<List<SystemImage>>(emptyList()) }
    var step by remember { mutableStateOf(1) }
    var selectedProfile by remember { mutableStateOf<AvdProfile?>(null) }
    var selectedImage by remember { mutableStateOf<SystemImage?>(null) }
    var name by remember { mutableStateOf("Andy_Device") }
    var orientation by remember { mutableStateOf("portrait") }
    var ram by remember { mutableStateOf("2048") }
    var storage by remember { mutableStateOf("8192") }
    var cores by remember { mutableStateOf("4") }
    var gpuMode by remember { mutableStateOf("auto") }
    var backCamera by remember { mutableStateOf(AvdCameraOption.Emulated) }
    var frontCamera by remember { mutableStateOf(AvdCameraOption.None) }
    var locale by remember { mutableStateOf("en_US") }
    var keyboard by remember { mutableStateOf(true) }
    var startAfterCreate by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Loading catalog...") }

    LaunchedEffect(Unit) {
        profiles = avd.listProfiles()
        images = avd.listSystemImages()
        selectedProfile = profiles.firstOrNull()
        selectedImage = images.firstOrNull { it.installed } ?: images.firstOrNull()
        status = "${profiles.size} profiles · ${images.size} images"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Create virtual device", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.width(760.dp).heightIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill("Profile", step == 1, Rust) { step = 1 }
                    FilterPill("Image", step == 2, Rust) { step = 2 }
                    FilterPill("Configure", step == 3, Rust) { step = 3 }
                }
                Text(status, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                when (step) {
                    1 -> LazyColumn(Modifier.height(390.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        profiles.groupBy { it.category }.toSortedMap(compareBy { it.ordinal }).forEach { (category, rows) ->
                            item { Text(category.name, color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp) }
                            items(rows) { profile ->
                                TableRow(Modifier.clickable {
                                    selectedProfile = profile
                                    name = profile.name.replace(Regex("""\W+"""), "_")
                                }) {
                                    MonoCell(profile.name, 220.dp, if (profile == selectedProfile) Rust else TextPrimary)
                                    MonoCell(profile.resolution ?: "-", 150.dp, TextSecondary)
                                    MonoCell(profile.density ?: "-", 90.dp, TextSecondary)
                                    MonoCell(profile.id, 1.dp, TextSecondary, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    2 -> LazyColumn(Modifier.height(390.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(images.take(240)) { image ->
                            TableRow(Modifier.clickable { selectedImage = image }) {
                                MonoCell("API ${image.api}", 82.dp, if (image == selectedImage) Rust else TextPrimary)
                                MonoCell(image.variant, 220.dp, TextPrimary)
                                MonoCell(image.abi, 140.dp, TextSecondary)
                                MonoCell(if (image.installed) "Installed" else "Available", 110.dp, if (image.installed) Green else TextSecondary)
                                MonoCell(image.packageId, 1.dp, TextSecondary, Modifier.weight(1f))
                            }
                        }
                    }
                    else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LabeledField("Name", name, { name = it.filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' } }, Modifier.width(220.dp))
                            LabeledField("Locale", locale, { locale = it }, Modifier.width(120.dp))
                            LabeledField("GPU", gpuMode, { gpuMode = it }, Modifier.width(110.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterPill("Portrait", orientation == "portrait", Cyan) { orientation = "portrait" }
                            FilterPill("Landscape", orientation == "landscape", Cyan) { orientation = "landscape" }
                            FilterPill("Keyboard", keyboard, Green) { keyboard = !keyboard }
                            FilterPill("Start after create", startAfterCreate, Yellow) { startAfterCreate = !startAfterCreate }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LabeledField("RAM MB", ram, { ram = it.filter(Char::isDigit) }, Modifier.width(110.dp))
                            LabeledField("Storage MB", storage, { storage = it.filter(Char::isDigit) }, Modifier.width(130.dp))
                            LabeledField("CPU cores", cores, { cores = it.filter(Char::isDigit) }, Modifier.width(110.dp))
                        }
                        Text("Cameras", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AvdCameraOption.entries.forEach { option ->
                                FilterPill("Back ${option.name}", backCamera == option, Rust) { backCamera = option }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AvdCameraOption.entries.forEach { option ->
                                FilterPill("Front ${option.name}", frontCamera == option, Rust) { frontCamera = option }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val profile = selectedProfile ?: return@Button
                    val image = selectedImage ?: return@Button
                    scope.launch {
                        status = if (image.installed) "Creating $name..." else "Installing ${image.packageId}..."
                        if (!image.installed) {
                            val install = avd.installSystemImage(image.packageId)
                            if (!install.isSuccess) {
                                status = install.stderr.ifBlank { install.stdout }
                                return@launch
                            }
                        }
                        val result = avd.createVirtualDevice(
                            AvdCreationConfig(
                                name = name,
                                profileId = profile.id,
                                systemImagePackage = image.packageId,
                                orientation = orientation,
                                ramMb = ram.toIntOrNull(),
                                storageMb = storage.toIntOrNull(),
                                cpuCores = cores.toIntOrNull(),
                                gpuMode = gpuMode.ifBlank { "auto" },
                                backCamera = backCamera,
                                frontCamera = frontCamera,
                                locale = locale,
                                hardwareKeyboard = keyboard,
                                startAfterCreate = startAfterCreate,
                            ),
                        )
                        if (result.isSuccess) onCreated(result.stdout.ifBlank { "Created $name" }) else status = result.stderr.ifBlank { result.stdout }
                    }
                },
                enabled = selectedProfile != null && selectedImage != null && name.isNotBlank(),
                colors = primaryButtonColors(),
            ) {
                Text(if (step < 3) "Create" else "Create")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step > 1) OutlinedButton(onClick = { step-- }) { Text("Back") }
                if (step < 3) OutlinedButton(onClick = { step++ }) { Text("Next") }
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun CatalogScreen(avd: AvdService) {
    val scope = rememberCoroutineScope()
    var images by remember { mutableStateOf<List<SystemImage>>(emptyList()) }
    var avds by remember { mutableStateOf<List<VirtualDevice>>(emptyList()) }
    var profiles by remember { mutableStateOf<List<AvdProfile>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }

    var selectedVariants by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedAbis by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedStates by remember { mutableStateOf<Set<String>>(emptySet()) }
    var apiRange by remember { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            images = avd.listSystemImages()
            avds = avd.listVirtualDevices()
            profiles = avd.listProfiles()
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    val apiBounds = remember(images) {
        val levels = images.map { it.apiLevel }
        (levels.minOrNull() ?: 10)..(levels.maxOrNull() ?: 36)
    }
    val availableVariants = remember(images) { images.map { it.variant }.distinct().sorted() }
    val availableAbis = remember(images) { images.map { it.abi }.distinct().sorted() }
    val activeRange = apiRange ?: apiBounds.first.toFloat()..apiBounds.last.toFloat()

    val filtered = images.filter { image ->
        val inRange = image.apiLevel.toFloat() in activeRange
        val variantOk = selectedVariants.isEmpty() || image.variant in selectedVariants
        val abiOk = selectedAbis.isEmpty() || image.abi in selectedAbis
        val stateOk = selectedStates.isEmpty() || (if (image.installed) "Installed" in selectedStates else "Available" in selectedStates)
        val queryOk = query.isBlank() || image.packageId.contains(query, true) || image.variant.contains(query, true) || image.api.contains(query, true)
        inRange && variantOk && abiOk && stateOk && queryOk
    }

    fun resetFilters() {
        selectedVariants = emptySet()
        selectedAbis = emptySet()
        selectedStates = emptySet()
        apiRange = null
        query = ""
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Toolbar("System images", "${images.count { it.installed }} installed · ${avds.size} AVDs · ${profiles.size} profiles", onPrimary = { refresh() }, primaryLabel = if (loading) "Loading" else "Refresh catalog")
        if (status.isNotBlank()) Text(status, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CatalogFilterSidebar(
                apiBounds = apiBounds,
                activeRange = activeRange,
                onRangeChange = { apiRange = it },
                availableVariants = availableVariants,
                selectedVariants = selectedVariants,
                onVariantsChange = { selectedVariants = it },
                availableAbis = availableAbis,
                selectedAbis = selectedAbis,
                onAbisChange = { selectedAbis = it },
                selectedStates = selectedStates,
                onStatesChange = { selectedStates = it },
                onReset = { resetFilters() },
            )
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Search package, variant, api", color = TextSecondary) }, modifier = Modifier.fillMaxWidth().height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
                Text("${filtered.size} of ${images.size} images", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                TableHeader(listOf("API" to 70.dp, "Variant" to 340.dp, "ABI" to 130.dp, "State" to 120.dp, "Action" to 100.dp, "Package" to 1.dp))
                LazyColumn {
                    items(filtered.take(240)) { image ->
                        TableRow {
                            MonoCell(image.api, 70.dp, TextPrimary)
                            Row(Modifier.width(340.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(image.variant, color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                image.badges.forEach { badge -> SystemImageBadgeChip(badge) }
                            }
                            MonoCell(image.abi, 130.dp, TextSecondary)
                            MonoCell(if (image.installed) "Installed" else "Available", 120.dp, if (image.installed) Green else TextSecondary)
                            Box(Modifier.width(100.dp)) {
                                if (image.installed) {
                                    OutlinedButton(
                                        onClick = {
                                            val refs = avds.filter { it.referencesImage(image) }
                                            if (refs.isNotEmpty()) {
                                                status = "Blocked: used by ${refs.joinToString { it.name }}"
                                            } else {
                                                pendingConfirmation = PendingConfirmation("Delete system image?", image.packageId) {
                                                    scope.launch {
                                                        val result = avd.uninstallSystemImage(image.packageId)
                                                        status = if (result.isSuccess) result.stdout.ifBlank { "Deleted ${image.packageId}" } else result.stderr.ifBlank { result.stdout }
                                                        refresh()
                                                    }
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    ) { Text("Delete", fontSize = 11.sp) }
                                } else {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                status = "Downloading ${image.packageId}..."
                                                val result = avd.installSystemImage(image.packageId)
                                                status = if (result.isSuccess) result.stdout.ifBlank { "Installed ${image.packageId}" } else result.stderr.ifBlank { result.stdout }
                                                refresh()
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        colors = primaryButtonColors(),
                                    ) { Text("Download", fontSize = 11.sp) }
                                }
                            }
                            MonoCell(image.packageId, 1.dp, TextSecondary, Modifier.weight(1f))
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
}

@Composable
private fun SystemImageBadgeChip(badge: SystemImageBadge) {
    val color = when (badge) {
        SystemImageBadge.PlayStore, SystemImageBadge.Tv -> AndyColors.Blue
        SystemImageBadge.Wear, SystemImageBadge.Automotive -> AndyColors.Orange
    }
    Box(
        Modifier.height(18.dp)
            .background(color.copy(alpha = 0.22f), RoundedCornerShape(AndyRadius.Pill))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(AndyRadius.Pill))
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(badge.label, color = color, fontFamily = MonoFont, fontWeight = FontWeight.Medium, fontSize = 9.sp, lineHeight = 12.sp)
    }
}

@Composable
private fun CatalogFilterSidebar(
    apiBounds: IntRange,
    activeRange: ClosedFloatingPointRange<Float>,
    onRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    availableVariants: List<String>,
    selectedVariants: Set<String>,
    onVariantsChange: (Set<String>) -> Unit,
    availableAbis: List<String>,
    selectedAbis: Set<String>,
    onAbisChange: (Set<String>) -> Unit,
    selectedStates: Set<String>,
    onStatesChange: (Set<String>) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        Modifier.width(240.dp).fillMaxHeight()
            .background(Panel, RoundedCornerShape(AndyRadius.R3))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val isAll = activeRange.start <= apiBounds.first && activeRange.endInclusive >= apiBounds.last
            Text("API LEVEL${if (isAll) "  (all)" else "  (${activeRange.start.toInt()} – ${activeRange.endInclusive.toInt()})"}", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            if (apiBounds.first < apiBounds.last) {
                RangeSlider(
                    value = activeRange,
                    onValueChange = onRangeChange,
                    valueRange = apiBounds.first.toFloat()..apiBounds.last.toFloat(),
                    steps = (apiBounds.last - apiBounds.first - 1).coerceAtLeast(0),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${apiBounds.first}", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                    Text("${apiBounds.last}", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                }
            }
        }
        FilterCheckboxGroup("VARIANT", availableVariants, selectedVariants, onVariantsChange)
        FilterCheckboxGroup("ABI", availableAbis, selectedAbis, onAbisChange)
        FilterCheckboxGroup("STATE", listOf("Installed", "Available"), selectedStates, onStatesChange)
        TextButton(onClick = onReset) { Text("Reset filters", color = AndyColors.Orange, fontFamily = MonoFont, fontSize = 12.sp) }
    }
}

@Composable
private fun FilterCheckboxGroup(
    title: String,
    options: List<String>,
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        options.forEach { option ->
            Row(
                Modifier.fillMaxWidth().height(26.dp).clickable {
                    onChange(if (option in selected) selected - option else selected + option)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = option in selected, onCheckedChange = { checked -> onChange(if (checked) selected + option else selected - option) }, modifier = Modifier.size(28.dp))
                Text(option, color = TextPrimary, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp))
            }
        }
    }
}

private fun VirtualDevice.referencesImage(image: SystemImage): Boolean {
    val haystack = (listOfNotNull(target, abi, path) + config.values).joinToString(" ").lowercase()
    return image.packageId.lowercase() in haystack ||
        ("android-${image.api}" in haystack && image.variant.lowercase() in haystack && image.abi.lowercase() in haystack)
}

@Composable
private fun SnapshotsScreen(avd: AvdService) {
    val scope = rememberCoroutineScope()
    var avds by remember { mutableStateOf<List<VirtualDevice>>(emptyList()) }
    var selectedAvd by remember { mutableStateOf<VirtualDevice?>(null) }
    var snapshots by remember { mutableStateOf<List<EmulatorSnapshot>>(emptyList()) }
    var status by remember { mutableStateOf("Select an AVD") }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    var savingSnapshot by remember { mutableStateOf(false) }

    var showSaveDialog by remember { mutableStateOf(false) }
    var renameSnapshotTarget by remember { mutableStateOf<EmulatorSnapshot?>(null) }

    fun refresh() {
        scope.launch {
            avds = avd.listVirtualDevices()
            selectedAvd = selectedAvd?.let { current -> avds.firstOrNull { it.name == current.name } } ?: avds.firstOrNull()
            selectedAvd?.let {
                snapshots = avd.listSnapshots(it.name)
                status = "${snapshots.size} snapshots for ${it.name}"
            }
        }
    }

    fun refreshSnapshots(target: VirtualDevice?, updateStatus: Boolean = true) {
        if (target == null) return
        scope.launch {
            snapshots = avd.listSnapshots(target.name)
            if (updateStatus) status = "${snapshots.size} snapshots for ${target.name}"
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val target = selectedAvd
        val isRunning = target?.running == true
        Toolbar(
            title = "Snapshots",
            subtitle = status,
            onPrimary = if (isRunning && !savingSnapshot) { { showSaveDialog = true } } else null,
            primaryLabel = "+ Save snapshot"
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            avds.forEach { row ->
                FilterPill(row.name, selectedAvd?.name == row.name, if (row.running) Green else Rust) {
                    selectedAvd = row
                    refreshSnapshots(row)
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 220.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            items(snapshots) { snapshot ->
                SnapshotCard(
                    snapshot = snapshot,
                    selectedAvd = selectedAvd,
                    avd = avd,
                    scope = scope,
                    onStatusChange = { status = it },
                    onRefresh = { refresh() },
                    onDeleteClick = { snap ->
                        val targetAvd = selectedAvd ?: return@SnapshotCard
                        pendingConfirmation = PendingConfirmation("Delete snapshot?", "${targetAvd.name} / ${snap.name}") {
                            scope.launch {
                                val result = avd.deleteSnapshot(targetAvd.name, snap.name)
                                status = if (result.isSuccess) result.stdout.ifBlank { "Deleted ${snap.name}" } else result.stderr.ifBlank { result.stdout }
                                refreshSnapshots(targetAvd)
                            }
                        }
                    },
                    onRenameClick = { snap ->
                        renameSnapshotTarget = snap
                    }
                )
            }

        }

        pendingConfirmation?.let { confirmation ->
            ConfirmationDialog(confirmation, onDismiss = { pendingConfirmation = null }, onConfirm = {
                pendingConfirmation = null
                confirmation.onConfirm()
            })
        }

        if (showSaveDialog) {
            SaveSnapshotDialog(
                onDismiss = { showSaveDialog = false },
                onSave = { name ->
                    showSaveDialog = false
                    val activeAvd = selectedAvd ?: return@SaveSnapshotDialog
                    scope.launch {
                        savingSnapshot = true
                        status = "Saving snapshot $name..."
                        try {
                            val result = avd.saveSnapshot(activeAvd.name, name)
                            snapshots = avd.listSnapshots(activeAvd.name)
                            status = if (result.isSuccess) {
                                result.stdout.ifBlank { "Saved $name" }
                            } else {
                                result.stderr.ifBlank { result.stdout.ifBlank { "Failed to save $name" } }
                            }
                        } finally {
                            savingSnapshot = false
                        }
                    }
                }
            )
        }

        renameSnapshotTarget?.let { snapshot ->
            RenameSnapshotDialog(
                snapshotName = snapshot.name,
                onDismiss = { renameSnapshotTarget = null },
                onRename = { newName ->
                    renameSnapshotTarget = null
                    val activeAvd = selectedAvd ?: return@RenameSnapshotDialog
                    scope.launch {
                        status = "Renaming snapshot ${snapshot.name} to $newName..."
                        val result = avd.renameSnapshot(activeAvd.name, snapshot.name, newName)
                        status = if (result.isSuccess) result.stdout.ifBlank { "Renamed ${snapshot.name} to $newName" } else result.stderr.ifBlank { result.stdout }
                        refreshSnapshots(activeAvd)
                    }
                }
            )
        }
    }
}

@Composable
private fun SnapshotCard(
    snapshot: EmulatorSnapshot,
    selectedAvd: VirtualDevice?,
    avd: AvdService,
    scope: CoroutineScope,
    onStatusChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onDeleteClick: (EmulatorSnapshot) -> Unit,
    onRenameClick: (EmulatorSnapshot) -> Unit,
) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, snapshot.screenshotPath) {
        value = withContext(Dispatchers.IO) {
            snapshot.screenshotPath?.let { loadImageBitmap(it) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(AndyRadius.R3))
            .background(Panel, RoundedCornerShape(AndyRadius.R3))
            .clip(RoundedCornerShape(AndyRadius.R3))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let { screenshot ->
                Image(
                    bitmap = screenshot,
                    contentDescription = snapshot.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } ?: run {
                Image(
                    painter = painterResource(Res.drawable.hardware_capture),
                    contentDescription = "No screenshot",
                    modifier = Modifier.size(28.dp),
                    colorFilter = ColorFilter.tint(TextSecondary.copy(alpha = 0.4f))
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = snapshot.name,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { onRenameClick(snapshot) },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(12.dp)) {
                        drawLine(
                            color = TextSecondary,
                            start = Offset(2.dp.toPx(), 10.dp.toPx()),
                            end = Offset(10.dp.toPx(), 2.dp.toPx()),
                            strokeWidth = 2.dp.toPx()
                        )
                        drawCircle(
                            color = TextSecondary,
                            center = Offset(10.dp.toPx(), 2.dp.toPx()),
                            radius = 1.5.dp.toPx()
                        )
                    }
                }
            }

            val detailsText = listOfNotNull(snapshot.size, snapshot.createdTime).joinToString("  ·  ")
            Text(
                text = detailsText.ifBlank { "--" },
                color = TextSecondary,
                fontSize = 11.sp,
                fontFamily = MonoFont
            )

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (snapshot.compatible) {
                    Text(
                        text = "Restore",
                        color = Cyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            val target = selectedAvd ?: return@clickable
                            scope.launch {
                                onStatusChange("Restoring ${snapshot.name}...")
                                val result = avd.restoreSnapshot(target.name, snapshot.name)
                                onStatusChange(if (result.isSuccess) result.stdout.ifBlank { "Restored ${snapshot.name}" } else result.stderr.ifBlank { result.stdout })
                                onRefresh()
                            }
                        }
                    )
                } else {
                    Text(
                        text = "Incompatible",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }

                Text(
                    text = "Delete",
                    color = Red,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        onDeleteClick(snapshot)
                    }
                )
            }
        }
    }
}

@Composable
private fun SaveStateCard(
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(262.dp)
            .border(
                BorderStroke(1.dp, SolidColor(Color.White.copy(alpha = 0.08f))),
                shape = RoundedCornerShape(AndyRadius.R3)
            )
            .background(if (enabled) Panel else Panel.copy(alpha = 0.5f), RoundedCornerShape(AndyRadius.R3))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Canvas(modifier = Modifier.size(24.dp)) {
                val sizePx = size.width
                val strokePx = 2.dp.toPx()
                drawLine(
                    color = if (enabled) TextSecondary else TextSecondary.copy(alpha = 0.3f),
                    start = Offset(0f, sizePx / 2f),
                    end = Offset(sizePx, sizePx / 2f),
                    strokeWidth = strokePx
                )
                drawLine(
                    color = if (enabled) TextSecondary else TextSecondary.copy(alpha = 0.3f),
                    start = Offset(sizePx / 2f, 0f),
                    end = Offset(sizePx / 2f, sizePx),
                    strokeWidth = strokePx
                )
            }
            Text(
                text = "Save current state",
                color = if (enabled) TextSecondary else TextSecondary.copy(alpha = 0.3f),
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun SaveSnapshotDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf("manual") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Save snapshot", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter a name for the new snapshot:", color = TextSecondary, fontSize = 12.sp)
                TextField(
                    text,
                    { text = it.filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace),
                    colors = fieldColors(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(text) },
                enabled = text.isNotBlank(),
                colors = primaryButtonColors()
            ) { Text("Save") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RenameSnapshotDialog(
    snapshotName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var text by remember { mutableStateOf(snapshotName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Rename snapshot", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter a new name for the snapshot:", color = TextSecondary, fontSize = 12.sp)
                TextField(
                    text,
                    { text = it.filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace),
                    colors = fieldColors(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onRename(text) },
                enabled = text.isNotBlank() && text != snapshotName,
                colors = primaryButtonColors()
            ) { Text("Rename") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun LiveScreen(
    services: AndyServices,
    serial: String?,
    device: AndroidDevice?,
    devicePaneWidth: Float,
    controlsPaneHeight: Float,
    onStopEmulator: (AndroidDevice) -> Unit,
    stoppingEmulatorSerial: String?,
    stopStatus: String,
    onDevicePaneWidthChange: (Float) -> Unit,
    onControlsPaneHeightChange: (Float) -> Unit,
    onBugSaved: () -> Unit,
    logcatState: LogcatState,
    onPopOutMirror: () -> Unit,
    selectedPackage: String?,
    onSelectedPackageChange: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var mirrorStatus by remember { mutableStateOf("Disconnected") }
    var connectResult by remember { mutableStateOf("") }
    var maxSize by remember { mutableStateOf("720") }
    var bitRateMbps by remember { mutableStateOf("4") }
    var maxFps by remember { mutableStateOf("60") }
    var bugDialogVisible by remember { mutableStateOf(false) }
    var bugSaveStatus by remember { mutableStateOf("") }
    var liveActionStatus by remember { mutableStateOf("") }
    var clipDialogVisible by remember { mutableStateOf(false) }
    var localDevicePaneWidth by remember(devicePaneWidth) { mutableStateOf(devicePaneWidth.coerceAtLeast(680f)) }
    var localControlsPaneHeight by remember(controlsPaneHeight) { mutableStateOf(controlsPaneHeight.coerceIn(170f, 360f)) }
    val sendMirrorInput = rememberMirrorInputSender(services, serial)
    fun sendHardware(input: MirrorInput) {
        sendMirrorInput(input)
    }
    fun runLiveAction(label: String, block: suspend () -> CommandResult) {
        if (serial == null) {
            liveActionStatus = "Select an online device"
            return
        }
        scope.launch {
            val result = block()
            liveActionStatus = "$label: " + if (result.isSuccess) result.stdout.ifBlank { "ok" } else result.stderr.ifBlank { result.stdout }
        }
    }
    fun applyPreset(size: String, mbps: String, fps: String = "60") {
        maxSize = size
        bitRateMbps = mbps
        maxFps = fps
    }
    fun mirrorConfig(): MirrorVideoConfig = MirrorVideoConfig(
        maxSize = maxSize.toIntOrNull()?.coerceIn(240, 2160) ?: 720,
        bitRate = ((bitRateMbps.toFloatOrNull()?.coerceIn(0.5f, 80f) ?: 4f) * 1_000_000).toInt(),
        maxFps = maxFps.toIntOrNull()?.coerceIn(15, 120) ?: 60,
    )
    LaunchedEffect(Unit) {
        services.mirror.status.collectLatest { mirrorStatus = it }
    }
    LaunchedEffect(serial) {
        if (serial != null && device?.state == DeviceConnectionState.Online) {
            val result = services.mirror.connect(serial, mirrorConfig())
            connectResult = if (result.isSuccess) result.stdout else result.stderr
            services.bugs.startCapture(serial, device)
        }
    }
    DisposableEffect(serial) {
        onDispose {
            scope.launch { services.bugs.stopCapture() }
        }
    }
    Row(Modifier.fillMaxSize()) {
        MirrorFrameContent(services.mirror, serial) { frameFlow, frame ->
            val visibleFrame = frame.takeUnless { bugDialogVisible || clipDialogVisible }
            val visibleFrameFlow = frameFlow.takeUnless { bugDialogVisible || clipDialogVisible }
            LiveDevicePane(
                serial = serial,
                device = device,
                frame = visibleFrame,
                frameFlow = visibleFrameFlow,
                mirrorStatus = mirrorStatus,
                connectResult = connectResult,
                modifier = Modifier.width(localDevicePaneWidth.dp).fillMaxHeight().padding(end = 6.dp),
                onPower = { sendHardware(MirrorInput.Power) },
                onVolumeUp = { sendHardware(MirrorInput.Key(24)) },
                onVolumeDown = { sendHardware(MirrorInput.Key(25)) },
                onRotate = { runLiveAction("Rotate") { services.devices.shell(serial!!, listOf("settings", "put", "system", "user_rotation", "1")) } },
                onCaptureScreenshot = { runLiveAction("Screenshot") { services.artifacts.saveScreenshot(serial!!, "andy-${serial}.png") } },
                onBugReport = { bugDialogVisible = true },
                onClipText = { clipDialogVisible = true },
                onPopOut = onPopOutMirror,
                onInput = sendMirrorInput,
                onConnect = {
                    if (serial != null) {
                        scope.launch {
                            val result = services.mirror.connect(serial, mirrorConfig())
                            connectResult = if (result.isSuccess) result.stdout else result.stderr
                        }
                    }
                },
            )
        }
        PaneDivider(
            onDrag = { dragX -> localDevicePaneWidth = (localDevicePaneWidth + dragX).coerceIn(560f, 1800f) },
            onDragEnd = { onDevicePaneWidthChange(localDevicePaneWidth) },
        )
        Column(Modifier.fillMaxSize().padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PanelCard(Modifier.fillMaxWidth().height(localControlsPaneHeight.dp)) {
                Text("Controls", color = TextPrimary, fontWeight = FontWeight.Bold)
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill("SD", maxSize == "480", Cyan) { applyPreset("480", "2") }
                    FilterPill("HD", maxSize == "720", Green) { applyPreset("720", "4") }
                    FilterPill("FHD", maxSize == "1080", Yellow) { applyPreset("1080", "8") }
                    FilterPill("Max", maxSize == "1440", Rust) { applyPreset("1440", "16") }
                }
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    LabeledField("Max px", maxSize, { maxSize = it.filter(Char::isDigit) }, Modifier.width(96.dp))
                    LabeledField("Mbps", bitRateMbps, { bitRateMbps = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.width(88.dp))
                    LabeledField("FPS", maxFps, { maxFps = it.filter(Char::isDigit) }, Modifier.width(78.dp))
                    Box(Modifier.align(Alignment.Bottom).padding(bottom = 2.dp)) {
                        Button(onClick = {
                            if (serial != null) scope.launch {
                                val result = services.mirror.connect(serial, mirrorConfig())
                                connectResult = if (result.isSuccess) result.stdout else result.stderr
                            }
                        }) { Text("Apply") }
                    }
                }
                Text("Bug capture", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactHardwareButton("Save bug", serial) { bugDialogVisible = true }
                    if (liveActionStatus.isNotBlank()) {
                        Text(liveActionStatus, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { device?.let(onStopEmulator) },
                        enabled = device?.kind == DeviceKind.Emulator && serial != null && stoppingEmulatorSerial != serial,
                    ) {
                        Text(if (stoppingEmulatorSerial == serial) "Stopping" else "Stop emulator")
                    }
                    if (stopStatus.isNotBlank()) {
                        Text(stopStatus, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (bugSaveStatus.isNotBlank()) {
                    Text(bugSaveStatus, color = Rust, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalPaneDivider(
                onDrag = { dragY -> localControlsPaneHeight = (localControlsPaneHeight + dragY).coerceIn(170f, 520f) },
                onDragEnd = { onControlsPaneHeightChange(localControlsPaneHeight) },
            )
            LogcatPanel(
                logcat = services.logcat,
                appsService = services.apps,
                serial = serial,
                selectedPackage = selectedPackage,
                onSelectedPackageChange = onSelectedPackageChange,
                modifier = Modifier.fillMaxWidth().weight(0.55f),
                compact = true,
                state = logcatState
            )
        }
    }
    if (clipDialogVisible) {
        ClipTextDialog(
            onDismiss = { clipDialogVisible = false },
            onSend = { text ->
                sendHardware(MirrorInput.Text(text))
                liveActionStatus = "Clip text: sent"
                clipDialogVisible = false
            },
        )
    }
    if (bugDialogVisible) {
        BugCaptureDialog(
            onDismiss = { bugDialogVisible = false },
            onSave = { draft ->
                scope.launch {
                    runCatching { services.bugs.saveBug(draft, device) }
                        .onSuccess { report ->
                            bugSaveStatus = "Saved ${report.title}"
                            bugDialogVisible = false
                            onBugSaved()
                        }
                        .onFailure { error ->
                            bugSaveStatus = error.message ?: "Failed to save bug"
                        }
                }
            },
        )
    }
}

@Composable
private fun BugCaptureDialog(onDismiss: () -> Unit, onSave: (BugCaptureDraft) -> Unit) {
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Capture bug", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LabeledField("Title", title, { title = it }, Modifier.fillMaxWidth(), placeholder = "Crash opening playlist")
                LabeledField("Notes / repro steps", notes, { notes = it }, Modifier.fillMaxWidth(), singleLine = false, minHeight = 120.dp, placeholder = "What happened? What should have happened?")
                Text("Saves the last 30 seconds of Andy actions, live video, and logcat.", color = TextSecondary, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(BugCaptureDraft(title, notes)) },
                enabled = title.trim().isNotBlank(),
                colors = primaryButtonColors(),
            ) {
                Text("Save bug")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun NavIconBack(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * 0.85f, size.height * 0.1f)
            lineTo(size.width * 0.15f, size.height * 0.5f)
            lineTo(size.width * 0.85f, size.height * 0.9f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun NavIconHome(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        drawCircle(
            color = color,
            radius = size.minDimension / 2f * 0.85f
        )
    }
}

@Composable
private fun NavIconRecents(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        val side = size.minDimension * 0.75f
        val offset = (size.minDimension - side) / 2f
        drawRoundRect(
            color = color,
            topLeft = Offset(offset, offset),
            size = Size(side, side),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        )
    }
}

@Composable
private fun LiveDevicePane(
    serial: String?,
    device: AndroidDevice?,
    displayName: String? = device?.displayName,
    frame: MirrorFrame?,
    frameFlow: Flow<MirrorFrame>? = null,
    mirrorStatus: String,
    connectResult: String,
    modifier: Modifier = Modifier,
    highlightBounds: String? = null,
    showRuler: Boolean = false,
    rulerWidth: Float = 0.5f,
    rulerHeight: Float = 0.5f,
    rulerX: Float = 0.5f,
    rulerY: Float = 0.5f,
    gridSize: Float? = null,
    gridColor: Color = Color.White.copy(alpha = 0.14f),
    pickerColor: Color? = null,
    pickerHex: String? = null,
    zoom: Float = 1f,
    showDeviceHeader: Boolean = true,
    showChromeControls: Boolean = true,
    showHardwareControls: Boolean = showChromeControls,
    showContainerChrome: Boolean = true,
    deviceBorderWidth: Dp = 5.dp,
    deviceCornerRadius: Dp = 10.dp,
    onHoverColor: (String) -> Unit = {},
    passThroughInput: Boolean = true,
    onPickerClick: (String) -> Unit = {},
    onDevicePointClick: (Int, Int) -> Unit = { _, _ -> },
    onRulerResize: (Float, Float) -> Unit = { _, _ -> },
    onPower: () -> Unit = {},
    onVolumeUp: () -> Unit = {},
    onVolumeDown: () -> Unit = {},
    onRotate: () -> Unit = {},
    onCaptureScreenshot: () -> Unit = {},
    onBugReport: () -> Unit = {},
    onClipText: () -> Unit = {},
    onPopOut: () -> Unit = {},
    onInput: (MirrorInput) -> Unit,
    onConnect: () -> Unit,
) {
    val containerShape = RoundedCornerShape(if (showContainerChrome) 8.dp else 0.dp)
    val containerModifier = if (showContainerChrome) {
        modifier.background(PanelSoft, containerShape).padding(14.dp)
    } else {
        modifier.background(Color.Black)
    }
    Row(
        containerModifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showHardwareControls) {
            LiveHardwareToolbar(
                enabled = serial != null,
                onPower = onPower,
                onVolumeUp = onVolumeUp,
                onVolumeDown = onVolumeDown,
                onRotate = onRotate,
                onCaptureScreenshot = onCaptureScreenshot,
                onBugReport = onBugReport,
                onClipText = onClipText,
                onPopOut = onPopOut,
            )
        }

        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (showDeviceHeader && serial != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        displayName ?: device?.serial ?: serial,
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(device?.screenSize, frame?.decodedFps?.let { "%.1f fps".format(it) }).joinToString(" · ").ifBlank { "-" },
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 8.dp),
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(18.dp))
            }

            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val viewportWidth = maxWidth
                val zoomFactor = zoom.coerceIn(0.5f, 4f)
                val sourceWidth = (device?.screenSize?.substringBefore("x")?.toIntOrNull() ?: frame?.width ?: 1080).coerceAtLeast(1)
                val sourceHeight = (device?.screenSize?.substringAfter("x")?.toIntOrNull() ?: frame?.height ?: 2340).coerceAtLeast(1)
                val aspect = sourceWidth.toFloat() / sourceHeight.toFloat()
                val navHeight = if (showChromeControls) 60.dp else 0.dp
                val viewportHeight = (maxHeight - navHeight).coerceAtLeast(1.dp)
                val baseWidth = minOf(viewportWidth, viewportHeight * aspect)
                Box(
                    Modifier.fillMaxSize()
                        .horizontalScroll(rememberScrollState())
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier.width(baseWidth * zoomFactor),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(aspect)
                                .background(Color.Black, RoundedCornerShape(deviceCornerRadius))
                                .then(
                                    if (deviceBorderWidth > 0.dp) {
                                        Modifier.border(deviceBorderWidth, Color(0xFF111111), RoundedCornerShape(deviceCornerRadius))
                                    } else {
                                        Modifier
                                    }
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (frame != null) {
                                val surfaceOverlay = MirrorOverlay(
                                    highlightBounds = highlightBounds,
                                    sourceWidth = sourceWidth,
                                    sourceHeight = sourceHeight,
                                    showGrid = gridSize != null,
                                    gridSize = gridSize ?: 16f,
                                    gridColor = gridColor,
                                    showRuler = showRuler,
                                    rulerColor = Rust,
                                    rulerWidth = rulerWidth,
                                    rulerHeight = rulerHeight,
                                    rulerX = rulerX,
                                    rulerY = rulerY,
                                    pickerColor = pickerColor,
                                    pickerHex = pickerHex,
                                )
                                if (frameFlow != null) {
                                    MirrorVideoSurface(
                                        frames = frameFlow,
                                        resetKey = serial,
                                        modifier = Modifier.fillMaxSize(),
                                        onInput = onInput,
                                        onHoverColor = onHoverColor,
                                        passThroughInput = passThroughInput,
                                        onPickerClick = onPickerClick,
                                        onDevicePointClick = onDevicePointClick,
                                        onRulerResize = onRulerResize,
                                        overlay = surfaceOverlay,
                                    )
                                } else {
                                    MirrorVideoSurface(
                                        frame = frame,
                                        modifier = Modifier.fillMaxSize(),
                                        onInput = onInput,
                                        onHoverColor = onHoverColor,
                                        passThroughInput = passThroughInput,
                                        onPickerClick = onPickerClick,
                                        onDevicePointClick = onDevicePointClick,
                                        onRulerResize = onRulerResize,
                                        overlay = surfaceOverlay,
                                    )
                                }
                            }
                        }

                        if (showChromeControls) {
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable(enabled = serial != null) { onInput(MirrorInput.Back) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    NavIconBack(color = if (serial != null) TextPrimary else TextSecondary)
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable(enabled = serial != null) { onInput(MirrorInput.Home) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    NavIconHome(color = if (serial != null) TextPrimary else TextSecondary)
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable(enabled = serial != null) { onInput(MirrorInput.Recents) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    NavIconRecents(color = if (serial != null) TextPrimary else TextSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactHardwareButton(label: String, serial: String?, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = serial != null,
        modifier = Modifier.widthIn(min = 82.dp).height(34.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LiveHardwareToolbar(
    enabled: Boolean,
    onPower: () -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onRotate: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    onBugReport: () -> Unit,
    onClipText: () -> Unit,
    onPopOut: () -> Unit,
) {
    Box(
        Modifier.width(68.dp).fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(58.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(AndyColors.Neutral900.copy(alpha = 0.92f))
                .border(1.dp, Color.White.copy(alpha = 0.04f), RoundedCornerShape(15.dp))
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ToolbarButton(HardwareIcon.Power, "Power", enabled, onPower)
            ToolbarButton(HardwareIcon.VolumeUp, "Vol +", enabled, onVolumeUp)
            ToolbarButton(HardwareIcon.VolumeDown, "Vol -", enabled, onVolumeDown)
            ToolbarButton(HardwareIcon.Rotate, "Rotate", enabled, onRotate)
            ToolbarButton(HardwareIcon.Capture, "Capture", enabled, onCaptureScreenshot)
            ToolbarButton(HardwareIcon.Bug, "Bug", enabled, onBugReport)
            ToolbarButton(HardwareIcon.Clip, "Clip", enabled, onClipText)
            ToolbarButton(HardwareIcon.PopOut, "Pop Out", enabled, onPopOut)
            ToolbarButton(HardwareIcon.Record, "Record", false) {}
        }
    }
}

@Composable
private fun ToolbarButton(icon: HardwareIcon, label: String, enabled: Boolean, onClick: () -> Unit) {
    val contentColor = if (enabled) TextPrimary else TextSecondary.copy(alpha = 0.38f)
    Column(
        modifier = Modifier
            .width(54.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        HardwareControlIcon(icon, contentColor, Modifier.size(24.dp))
        Text(
            label,
            color = contentColor,
            fontSize = 10.sp,
            lineHeight = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private enum class HardwareIcon {
    Power,
    VolumeUp,
    VolumeDown,
    Rotate,
    Capture,
    Bug,
    Clip,
    PopOut,
    Record,
}

@Composable
private fun HardwareControlIcon(icon: HardwareIcon, color: Color, modifier: Modifier = Modifier) {
    val resource = when (icon) {
        HardwareIcon.Power -> Res.drawable.hardware_power
        HardwareIcon.VolumeUp -> Res.drawable.hardware_volume_up
        HardwareIcon.VolumeDown -> Res.drawable.hardware_volume_down
        HardwareIcon.Rotate -> Res.drawable.hardware_rotate
        HardwareIcon.Capture -> Res.drawable.hardware_capture
        HardwareIcon.Bug -> Res.drawable.hardware_bug
        HardwareIcon.Clip -> Res.drawable.hardware_clipboard
        HardwareIcon.PopOut -> Res.drawable.hardware_pop_out
        HardwareIcon.Record -> Res.drawable.hardware_record
    }
    Image(
        painter = painterResource(resource),
        contentDescription = null,
        modifier = modifier,
        colorFilter = ColorFilter.tint(color),
    )
}

@Composable
private fun ClipTextDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Clip text", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            TextField(
                text,
                { text = it },
                modifier = Modifier.fillMaxWidth().height(110.dp),
                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace),
                colors = fieldColors(),
            )
        },
        confirmButton = {
            Button(onClick = { onSend(text) }, enabled = text.isNotBlank(), colors = primaryButtonColors()) { Text("Send") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun DeviceOverlay(frame: MirrorFrame, highlightBounds: String?, showRuler: Boolean, gridSize: Float?, gridColor: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val rect = fittedRect(size.width, size.height, frame.width, frame.height)
        gridSize?.takeIf { it >= 2f }?.let { step ->
            var x = rect.left
            while (x <= rect.right) {
                drawLine(gridColor, Offset(x, rect.top), Offset(x, rect.bottom))
                x += step
            }
            var y = rect.top
            while (y <= rect.bottom) {
                drawLine(gridColor, Offset(rect.left, y), Offset(rect.right, y))
                y += step
            }
        }
        if (showRuler) {
            val tick = 20.dp.toPx()
            var x = rect.left
            var tickIndex = 0
            while (x <= rect.right) {
                drawLine(Yellow.copy(alpha = 0.65f), Offset(x, rect.top), Offset(x, rect.top + if (tickIndex % 5 == 0) 18.dp.toPx() else 9.dp.toPx()), 1f)
                x += tick
                tickIndex += 1
            }
            var y = rect.top
            tickIndex = 0
            while (y <= rect.bottom) {
                drawLine(Yellow.copy(alpha = 0.65f), Offset(rect.left, y), Offset(rect.left + if (tickIndex % 5 == 0) 18.dp.toPx() else 9.dp.toPx(), y), 1f)
                y += tick
                tickIndex += 1
            }
        }
        parseBounds(highlightBounds)?.let { (left, top, right, bottom) ->
            val scaleX = rect.width / frame.width.coerceAtLeast(1)
            val scaleY = rect.height / frame.height.coerceAtLeast(1)
            drawRect(
                color = Rust.copy(alpha = 0.28f),
                topLeft = Offset(rect.left + left * scaleX, rect.top + top * scaleY),
                size = androidx.compose.ui.geometry.Size((right - left) * scaleX, (bottom - top) * scaleY),
            )
            drawRect(
                color = Rust,
                topLeft = Offset(rect.left + left * scaleX, rect.top + top * scaleY),
                size = androidx.compose.ui.geometry.Size((right - left) * scaleX, (bottom - top) * scaleY),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

private data class FittedRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

private fun fittedRect(containerWidth: Float, containerHeight: Float, contentWidth: Int, contentHeight: Int): FittedRect {
    val safeWidth = contentWidth.coerceAtLeast(1)
    val safeHeight = contentHeight.coerceAtLeast(1)
    val scale = minOf(containerWidth / safeWidth, containerHeight / safeHeight)
    val width = safeWidth * scale
    val height = safeHeight * scale
    return FittedRect((containerWidth - width) / 2f, (containerHeight - height) / 2f, width, height)
}

class LogcatState {
    var entries by mutableStateOf<List<LogcatEntry>>(emptyList())
    var search by mutableStateOf("")
    var live by mutableStateOf(true)
    val levels = mutableStateMapOf<LogLevel, Boolean>().also { map -> LogLevel.entries.forEach { map[it] = it != LogLevel.Verbose && it != LogLevel.Silent } }
    var lastSerial by mutableStateOf<String?>(null)
    var lastSearch by mutableStateOf<String?>(null)
    var lastLevels by mutableStateOf<Set<LogLevel>?>(null)
    var lastLive by mutableStateOf(true)
    var lastPackage by mutableStateOf<String?>(null)
}

@Composable
private fun LogcatScreen(
    logcat: LogcatService,
    appsService: AppService,
    serial: String?,
    state: LogcatState,
    selectedPackage: String?,
    onSelectedPackageChange: (String?) -> Unit
) {
    LogcatPanel(
        logcat = logcat,
        appsService = appsService,
        serial = serial,
        selectedPackage = selectedPackage,
        onSelectedPackageChange = onSelectedPackageChange,
        modifier = Modifier.fillMaxSize(),
        compact = false,
        state = state
    )
}

@Composable
private fun LogcatPanel(
    logcat: LogcatService,
    appsService: AppService,
    serial: String?,
    selectedPackage: String?,
    onSelectedPackageChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean,
    state: LogcatState = remember { LogcatState() }
) {
    var streamJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun restart() {
        val currentLevels = state.levels.filterValues { it }.keys.toSet()
        val parametersChanged = serial != state.lastSerial ||
                state.search != state.lastSearch ||
                currentLevels != state.lastLevels ||
                state.live != state.lastLive ||
                selectedPackage != state.lastPackage

        if (parametersChanged) {
            streamJob?.cancel()
            streamJob = null
            val filtersChanged = serial != state.lastSerial ||
                    state.search != state.lastSearch ||
                    currentLevels != state.lastLevels ||
                    selectedPackage != state.lastPackage
            if (filtersChanged) {
                state.entries = emptyList()
            }
            state.lastSerial = serial
            state.lastSearch = state.search
            state.lastLevels = currentLevels
            state.lastLive = state.live
            state.lastPackage = selectedPackage

            if (serial == null || !state.live) return
            streamJob = scope.launch {
                logcat.stream(serial, LogcatFilter(state.search, currentLevels, packageName = selectedPackage)).collect { batch ->
                    state.entries = (state.entries + batch).takeLast(1200)
                }
            }
        } else {
            if (streamJob == null && serial != null && state.live) {
                state.lastSerial = serial
                state.lastSearch = state.search
                state.lastLevels = currentLevels
                state.lastLive = state.live
                state.lastPackage = selectedPackage
                streamJob = scope.launch {
                    logcat.stream(serial, LogcatFilter(state.search, currentLevels, packageName = selectedPackage)).collect { batch ->
                        state.entries = (state.entries + batch).takeLast(1200)
                    }
                }
            }
        }
    }
    LaunchedEffect(serial, state.live, state.search, state.levels.values.toList(), selectedPackage) { restart() }

    PanelCard(modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val toolbarWidth = maxWidth
            var overflowExpanded by remember { mutableStateOf(false) }

            val showLevelsOnToolbar = toolbarWidth >= 720.dp
            val showActionsOnToolbar = toolbarWidth >= 520.dp
            val showPackageOnToolbar = toolbarWidth >= 380.dp
            val showOverflowButton = !showLevelsOnToolbar || !showActionsOnToolbar || !showPackageOnToolbar

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (!compact && toolbarWidth >= 800.dp) {
                    Text("Logcat", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
                TextField(
                    value = state.search,
                    onValueChange = { state.search = it },
                    placeholder = { Text("filter or package:com.example", color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).height(54.dp),
                    textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace),
                    colors = fieldColors()
                )

                if (showPackageOnToolbar) {
                    PackageSelector(
                        appsService = appsService,
                        serial = serial,
                        selectedPackage = selectedPackage,
                        onSelectedPackageChange = onSelectedPackageChange,
                        modifier = if (compact) Modifier.widthIn(max = 180.dp) else Modifier.widthIn(max = 300.dp)
                    )
                }

                if (showLevelsOnToolbar) {
                    LogLevel.entries.filter { it != LogLevel.Silent }.forEach { level ->
                        FilterPill(
                            text = level.name.take(1),
                            selected = state.levels[level] == true,
                            color = levelColor(level)
                        ) {
                            state.levels[level] = !(state.levels[level] ?: false)
                        }
                    }
                }

                if (showActionsOnToolbar) {
                    Button(
                        onClick = { state.live = !state.live },
                        colors = ButtonDefaults.buttonColors(containerColor = if (state.live) Rust else PanelSoft)
                    ) {
                        Text(if (state.live) "Live" else "Paused")
                    }
                    OutlinedButton(onClick = {
                        state.entries = emptyList()
                        if (serial != null) {
                            scope.launch {
                                logcat.clear(serial)
                            }
                        }
                    }) {
                        Text("Clear")
                    }
                }

                if (showOverflowButton) {
                    Box {
                        OutlinedButton(
                            onClick = { overflowExpanded = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                            shape = RoundedCornerShape(AndyRadius.R2),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("More ▼", fontSize = 12.sp)
                        }

                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false },
                            containerColor = AndyColors.Neutral750,
                            modifier = Modifier.width(if (!showPackageOnToolbar) 260.dp else 220.dp)
                        ) {
                            if (!showPackageOnToolbar) {
                                Text("Package Filter", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                                PackageSelector(
                                    appsService = appsService,
                                    serial = serial,
                                    selectedPackage = selectedPackage,
                                    onSelectedPackageChange = onSelectedPackageChange,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                                Spacer(Modifier.height(4.dp))
                                Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
                                Spacer(Modifier.height(4.dp))
                            }

                            if (!showLevelsOnToolbar) {
                                Text("Log Levels", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    LogLevel.entries.filter { it != LogLevel.Silent }.forEach { level ->
                                        FilterPill(
                                            text = level.name.take(1),
                                            selected = state.levels[level] == true,
                                            color = levelColor(level)
                                        ) {
                                            state.levels[level] = !(state.levels[level] ?: false)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
                                Spacer(Modifier.height(4.dp))
                            }

                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(Modifier.size(8.dp).background(if (state.live) Green else Rust, RoundedCornerShape(4.dp)))
                                        Text(if (state.live) "Pause Stream" else "Resume Stream", color = TextPrimary, fontSize = 13.sp)
                                    }
                                },
                                onClick = {
                                    state.live = !state.live
                                    overflowExpanded = false
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Clear Logs", color = TextPrimary, fontSize = 13.sp)
                                    }
                                },
                                onClick = {
                                    state.entries = emptyList()
                                    if (serial != null) {
                                        scope.launch {
                                            logcat.clear(serial)
                                        }
                                    }
                                    overflowExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
        LogcatEntryList(state.entries, compact, Modifier.fillMaxSize())
    }
}

@Composable
private fun PackageSelector(
    appsService: AppService,
    serial: String?,
    selectedPackage: String?,
    onSelectedPackageChange: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var installedApps by remember(serial) { mutableStateOf<List<AndroidApp>>(emptyList()) }
    var searchAppQuery by remember { mutableStateOf("") }

    LaunchedEffect(serial, expanded) {
        if (expanded && serial != null) {
            runCatching { appsService.listApps(serial) }
                .onSuccess { apps ->
                    installedApps = apps.sortedWith(compareBy({ it.label?.lowercase() ?: "" }, { it.packageName }))
                }
        }
    }

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = TextPrimary
            ),
            shape = RoundedCornerShape(AndyRadius.R2),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            val selectedApp = installedApps.firstOrNull { it.packageName == selectedPackage }
            val label = selectedApp?.label ?: selectedPackage ?: "All"
            Text("Pkg: $label", color = TextPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(4.dp))
            Text("▼", color = TextSecondary, fontSize = 10.sp)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = AndyColors.Neutral750,
            modifier = Modifier.width(320.dp)
        ) {
            TextField(
                value = searchAppQuery,
                onValueChange = { searchAppQuery = it },
                placeholder = { Text("Search packages...", color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .height(48.dp),
                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontSize = 13.sp),
                colors = fieldColors()
            )

            Spacer(Modifier.height(4.dp))

            val filteredApps = installedApps.filter {
                searchAppQuery.isBlank() ||
                it.packageName.contains(searchAppQuery, true) ||
                it.label?.contains(searchAppQuery, true) == true
            }

            Box(
                modifier = Modifier
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Column {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "All Packages",
                                color = if (selectedPackage == null) Green else TextPrimary,
                                fontWeight = if (selectedPackage == null) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            onSelectedPackageChange(null)
                            expanded = false
                            searchAppQuery = ""
                        }
                    )

                    filteredApps.forEach { app ->
                        val isSelected = app.packageName == selectedPackage
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        app.label ?: app.packageName,
                                        color = if (isSelected) Green else TextPrimary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp
                                    )
                                    if (app.label != null) {
                                        Text(
                                            app.packageName,
                                            color = TextSecondary,
                                            fontSize = 10.sp,
                                            fontFamily = MonoFont
                                        )
                                    }
                                }
                            },
                            onClick = {
                                onSelectedPackageChange(app.packageName)
                                expanded = false
                                searchAppQuery = ""
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogcatEntryList(entries: List<LogcatEntry>, compact: Boolean, modifier: Modifier = Modifier) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    var stickToBottom by remember { mutableStateOf(true) }
    var timeWidth by remember { mutableStateOf(110f) }
    var levelWidth by remember { mutableStateOf(32f) }
    var tagWidth by remember { mutableStateOf(180f) }
    val isAtBottom by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            if (total == 0) {
                true
            } else {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                lastVisible >= total - 1
            }
        }
    }
    LaunchedEffect(entries.size, stickToBottom) {
        if (stickToBottom && entries.isNotEmpty()) {
            listState.scrollToItem(entries.lastIndex)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to isAtBottom }
            .distinctUntilChanged()
            .collect { (scrolling, atBottom) ->
                if (scrolling && !atBottom) stickToBottom = false
                if (atBottom) stickToBottom = true
            }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!compact) {
            ResizableLogcatHeader(
                timeWidth = timeWidth,
                levelWidth = levelWidth,
                tagWidth = tagWidth,
                onTimeWidth = { timeWidth = it.coerceIn(70f, 240f) },
                onLevelWidth = { levelWidth = it.coerceIn(24f, 90f) },
                onTagWidth = { tagWidth = it.coerceIn(80f, 420f) },
            )
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().padding(end = 8.dp), state = listState) {
                items(entries) { entry ->
                    if (compact) {
                        Text("${entry.time} ${entry.pid ?: "-"} ${entry.level.name.take(1)}/${entry.tag}: ${entry.message}", color = levelColor(entry.level), fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    } else {
                        Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.Top) {
                            MonoCell(entry.time, timeWidth.dp, TextSecondary)
                            MonoCell(entry.level.name.take(1), levelWidth.dp, levelColor(entry.level))
                            MonoCell(entry.tag, tagWidth.dp, levelColor(entry.level))
                            Text(entry.message, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            DraggableScrollbar(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                visibleItems = listState.layoutInfo.visibleItemsInfo.size,
                totalItems = listState.layoutInfo.totalItemsCount,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                onDragToIndex = { index ->
                    stickToBottom = index >= (listState.layoutInfo.totalItemsCount - listState.layoutInfo.visibleItemsInfo.size - 1)
                    scope.launch { listState.scrollToItem(index.coerceAtLeast(0)) }
                },
            )
        }
    }
}

@Composable
private fun ResizableLogcatHeader(
    timeWidth: Float,
    levelWidth: Float,
    tagWidth: Float,
    onTimeWidth: (Float) -> Unit,
    onLevelWidth: (Float) -> Unit,
    onTagWidth: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaderCell("TIME", timeWidth.dp, onTimeWidth)
        HeaderCell("L", levelWidth.dp, onLevelWidth)
        HeaderCell("TAG", tagWidth.dp, onTagWidth)
        Text("MESSAGE", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun HeaderCell(title: String, width: androidx.compose.ui.unit.Dp, onWidthChange: (Float) -> Unit) {
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
private fun DraggableScrollbar(
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

private data class HostEditorTab(
    val path: String,
    val content: String,
    val savedContent: String,
    val modifiedMillis: Long,
    val sizeBytes: Long,
    val languageHint: String,
    val message: String = "",
) {
    val dirty: Boolean get() = content != savedContent
}

private data class HostTreeRow(val entry: HostFileEntry, val depth: Int)

@Composable
private fun HostFilesScreen(
    service: HostFileService,
    workspaceState: WorkspaceState,
    onUpdateWorkspace: ((WorkspaceState) -> WorkspaceState) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedRoot by remember(workspaceState.hostFileRoots, workspaceState.lastHostFilePath) {
        mutableStateOf(resolveHostRootForPath(workspaceState.lastHostFilePath, workspaceState.hostFileRoots) ?: workspaceState.hostFileRoots.firstOrNull())
    }
    var selectedPath by remember(workspaceState.lastHostFilePath, selectedRoot) {
        val saved = workspaceState.lastHostFilePath
        val selected = selectedRoot
        mutableStateOf(if (saved != null && selected != null && hostPathStartsWith(saved, selected)) saved else selected.orEmpty())
    }
    var message by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var searchMode by remember { mutableStateOf(HostSearchMode.Combined) }
    var searchResults by remember { mutableStateOf<List<HostSearchResult>>(emptyList()) }
    var tabs by remember { mutableStateOf<List<HostEditorTab>>(emptyList()) }
    var activePath by remember { mutableStateOf<String?>(null) }
    var conflictTab by remember { mutableStateOf<HostEditorTab?>(null) }
    val statuses = remember { mutableStateMapOf<String, HostIndexStatus>() }
    val treeChildren = remember { mutableStateMapOf<String, List<HostFileEntry>>() }
    val expandedPaths = remember { mutableStateMapOf<String, Boolean>() }
    val searchFocusRequester = remember { FocusRequester() }
    val treeListState = rememberLazyListState()
    var pendingTreeScrollPath by remember { mutableStateOf<String?>(null) }
    var localHostFileTreePaneWidth by remember(workspaceState.hostFileTreePaneWidth) {
        mutableStateOf(workspaceState.hostFileTreePaneWidth.coerceIn(220f, 620f))
    }
    var localHostFileSearchPaneWidth by remember(workspaceState.hostFileSearchPaneWidth) {
        mutableStateOf(workspaceState.hostFileSearchPaneWidth.coerceIn(500f, 980f))
    }
    val activeTab = activePath?.let { path -> tabs.firstOrNull { it.path == path } }
    val dirtyPaths = remember(tabs) { tabs.filter { it.dirty }.map { it.path }.toSet() }
    val treeRows = remember(selectedRoot, treeChildren.toMap(), expandedPaths.toMap()) {
        selectedRoot?.let { root -> hostTreeRows(root, treeChildren, expandedPaths) }.orEmpty()
    }

    fun updateRecent(path: String) {
        onUpdateWorkspace {
            it.copy(
                lastHostFilePath = path,
                recentHostFiles = (listOf(path) + it.recentHostFiles.filterNot { recent -> recent == path }).take(10),
            )
        }
    }

    fun loadPath(path: String = selectedPath) {
        if (path.isBlank()) return
        scope.launch {
            runCatching { service.list(path) }
                .onSuccess {
                    selectedPath = path
                    treeChildren[path] = it
                    if (message.endsWith("entries")) message = ""
                    onUpdateWorkspace { state -> state.copy(lastHostFilePath = path) }
                }
                .onFailure { message = it.message ?: "Browse failed" }
        }
    }

    fun openFile(path: String) {
        scope.launch {
            runCatching { service.read(path) }
                .onSuccess { doc ->
                    val next = HostEditorTab(doc.path, doc.content, doc.content, doc.modifiedMillis, doc.sizeBytes, doc.languageHint)
                    tabs = (tabs.filterNot { it.path == doc.path } + next)
                    activePath = doc.path
                    updateRecent(doc.path)
                    if (message.startsWith("Opened ") || message.startsWith("Saved ")) message = ""
                }
                .onFailure { message = it.message ?: "Open failed" }
        }
    }

    fun revealFileInTree(path: String) {
        val root = resolveHostRootForPath(path, workspaceState.hostFileRoots) ?: return
        val parent = hostParentPath(path)
        selectedRoot = root
        selectedPath = parent
        searchQuery = ""
        pendingTreeScrollPath = path
        hostAncestorDirectories(path, root).forEach { directory ->
            expandedPaths[directory] = true
            loadPath(directory)
        }
    }

    fun saveTab(tab: HostEditorTab, overwrite: Boolean = false, visiblePath: String? = activePath) {
        if (visiblePath != tab.path) {
            val visibleName = visiblePath?.let(::hostFileName) ?: "no active file"
            message = "Save blocked: ${hostFileName(tab.path)} is not the visible editor file ($visibleName)."
            tabs = tabs.map { if (it.path == tab.path) it.copy(message = "Save blocked: not the visible editor file") else it }
            return
        }
        scope.launch {
            val currentTab = tabs.firstOrNull { it.path == tab.path }
            if (currentTab == null || activePath != tab.path) {
                message = "Save blocked: active editor changed before save."
                return@launch
            }
            when (val result = service.save(currentTab.path, currentTab.content, if (overwrite) 0L else currentTab.modifiedMillis)) {
                is HostFileSaveResult.Saved -> {
                    tabs = tabs.map { if (it.path == currentTab.path) it.copy(savedContent = currentTab.content, modifiedMillis = result.modifiedMillis, message = "") else it }
                    if (message.startsWith("Opened ") || message.startsWith("Saved ")) message = ""
                }
                is HostFileSaveResult.Conflict -> {
                    conflictTab = currentTab.copy(message = "Changed outside Andy at ${result.currentModifiedMillis}")
                }
                is HostFileSaveResult.Failed -> {
                    tabs = tabs.map { if (it.path == currentTab.path) it.copy(message = result.message) else it }
                    message = result.message
                }
            }
        }
    }

    fun updateEditorTextForPath(path: String, value: String) {
        if (path != activePath) {
            message = "Edit ignored: editor event did not match the visible file."
            return
        }
        if (tabs.none { it.path == path }) {
            message = "Edit ignored: file tab is no longer open."
            return
        }
        tabs = tabs.map { if (it.path == path) it.copy(content = value) else it }
    }

    fun saveEditorContentForPath(path: String, value: String) {
        if (path != activePath) {
            message = "Save blocked: editor event did not match the visible file."
            return
        }
        val currentTab = tabs.firstOrNull { it.path == path }
        if (currentTab == null) {
            message = "Save blocked: file tab is no longer open."
            return
        }
        val updated = currentTab.copy(content = value)
        tabs = tabs.map { if (it.path == path) updated else it }
        saveTab(updated, visiblePath = path)
    }

    fun closeActiveTab() {
        val path = activePath ?: return
        val nextTabs = tabs.filterNot { it.path == path }
        tabs = nextTabs
        activePath = nextTabs.lastOrNull()?.path
    }

    fun setSearchModeAndFocus(mode: HostSearchMode) {
        searchMode = mode
        scope.launch {
            delay(30)
            searchFocusRequester.requestFocus()
        }
    }

    fun toggleTreeDirectory(path: String) {
        val expanded = expandedPaths[path] == true
        if (expanded) {
            expandedPaths[path] = false
            return
        }
        expandedPaths[path] = true
        loadPath(path)
    }

    LaunchedEffect(workspaceState.hostFileRoots) {
        workspaceState.hostFileRoots.forEach { root ->
            expandedPaths[root] = true
            launch { service.indexRoot(root).collect { statuses[root] = it } }
        }
        selectedRoot?.let { root ->
            if (selectedPath.isBlank() || !hostPathStartsWith(selectedPath, root)) selectedPath = root
            loadPath(root)
            if (selectedPath != root) loadPath(selectedPath)
        }
    }

    LaunchedEffect(searchQuery, searchMode, selectedRoot) {
        delay(180)
        val root = selectedRoot
        searchResults = if (searchQuery.isBlank() || root.isNullOrBlank()) {
            emptyList()
        } else {
            service.search(searchQuery, searchMode, listOf(root), 200)
        }
    }

    LaunchedEffect(pendingTreeScrollPath, treeRows) {
        val target = pendingTreeScrollPath ?: return@LaunchedEffect
        val index = treeRows.indexOfFirst { it.entry.path == target }
        if (index >= 0) {
            delay(50)
            treeListState.animateScrollToItem(index)
            pendingTreeScrollPath = null
        }
    }

    Column(
        Modifier.fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val command = event.isMetaPressed || event.isCtrlPressed
                if (!command) return@onPreviewKeyEvent false
                when {
                    event.key == Key.S && activeTab != null -> {
                        tabs.firstOrNull { it.path == activeTab.path }?.let { saveTab(it) }
                        true
                    }
                    event.key == Key.W -> {
                        closeActiveTab()
                        true
                    }
                    event.key == Key.O -> {
                        scope.launch {
                            pickDirectory(selectedRoot)?.let { picked ->
                                onUpdateWorkspace { state -> state.copy(hostFileRoots = (state.hostFileRoots + picked).distinct(), lastHostFilePath = picked) }
                                selectedRoot = picked
                                selectedPath = picked
                                expandedPaths[picked] = true
                                loadPath(picked)
                            }
                        }
                        true
                    }
                    event.isShiftPressed && event.key == Key.A -> {
                        setSearchModeAndFocus(HostSearchMode.Combined)
                        true
                    }
                    event.isShiftPressed && event.key == Key.N -> {
                        setSearchModeAndFocus(HostSearchMode.FileName)
                        true
                    }
                    event.isShiftPressed && event.key == Key.F -> {
                        setSearchModeAndFocus(HostSearchMode.Content)
                        true
                    }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Toolbar("Computer Files", "${workspaceState.hostFileRoots.size} roots · ${statuses.values.sumOf { it.indexedFiles }} indexed", onPrimary = {
            scope.launch {
                pickDirectory(selectedRoot)?.let { picked ->
                    onUpdateWorkspace { state -> state.copy(hostFileRoots = (state.hostFileRoots + picked).distinct(), lastHostFilePath = picked) }
                    selectedRoot = picked
                    selectedPath = picked
                    expandedPaths[picked] = true
                    loadPath(picked)
                }
            }
        }, primaryLabel = "Add root")
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelCard(Modifier.width(localHostFileTreePaneWidth.dp).fillMaxHeight()) {
                Text("Roots", color = TextPrimary, fontWeight = FontWeight.Bold)
                if (workspaceState.hostFileRoots.isEmpty()) {
                    Text("Add a folder to start indexing files on this computer.", color = TextSecondary, fontSize = 12.sp)
                }
                workspaceState.hostFileRoots.forEach { root ->
                    val status = statuses[root]
                    Column(
                        Modifier.fillMaxWidth()
                            .background(if (root == selectedRoot) AndyColors.OrangeSubtle else PanelSoft, RoundedCornerShape(AndyRadius.R2))
                            .border(1.dp, if (root == selectedRoot) AndyColors.OrangeBorder.copy(alpha = 0.52f) else Border, RoundedCornerShape(AndyRadius.R2))
                            .clickable {
                                selectedRoot = root
                                selectedPath = root
                                expandedPaths[root] = true
                                loadPath(root)
                            }
                            .padding(8.dp),
                    ) {
                        Text(hostFileName(root).ifBlank { root }, color = if (root == selectedRoot) Rust else TextPrimary, fontFamily = MonoFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(status?.message ?: "Queued", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Divider(color = Border)
                Text("Recent", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                workspaceState.recentHostFiles.forEach { recent ->
                    val recentRoot = resolveHostRootForPath(recent, workspaceState.hostFileRoots)
                    val relativePath = recentRoot?.let { hostDisplayPath(recent, it) } ?: recent
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                revealFileInTree(recent)
                                openFile(recent)
                            }
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HostFileIcon(hostFileIconForPath(recent, isDirectory = false))
                        Column(Modifier.weight(1f)) {
                            Text(recentRoot?.let(::hostFileName) ?: "outside roots", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(relativePath, color = TextPrimary, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            PaneDivider(
                onDrag = { dragX ->
                    localHostFileTreePaneWidth = (localHostFileTreePaneWidth + dragX).coerceIn(220f, 620f)
                },
                onDragEnd = {
                    onUpdateWorkspace { state -> state.copy(hostFileTreePaneWidth = localHostFileTreePaneWidth) }
                },
            )
            PanelCard(Modifier.width(localHostFileSearchPaneWidth.dp).fillMaxHeight()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        selectedPath,
                        { selectedPath = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = MonoFont),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { loadPath() }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) { Text("Go") }
                        OutlinedButton(onClick = { loadPath(hostParentPath(selectedPath)) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) { Text("Up") }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            selectedRoot?.let { root ->
                                scope.launch {
                                    var sawIndexing = false
                                    service.indexRoot(root).first { status ->
                                        statuses[root] = status
                                        if (status.indexing) sawIndexing = true
                                        sawIndexing && !status.indexing
                                    }
                                }
                            }
                        }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                            Text("Refresh index")
                        }
                    }
                }
                TextField(
                    searchQuery,
                    { searchQuery = it },
                    placeholder = { Text("Search indexed files", color = TextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().height(48.dp).focusRequester(searchFocusRequester),
                    textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = MonoFont),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchModePill("All", "ctrl shift A", searchMode == HostSearchMode.Combined, Rust) { setSearchModeAndFocus(HostSearchMode.Combined) }
                    SearchModePill("Names", "ctrl shift N", searchMode == HostSearchMode.FileName, Cyan) { setSearchModeAndFocus(HostSearchMode.FileName) }
                    SearchModePill("Contents", "ctrl shift F", searchMode == HostSearchMode.Content, Green) { setSearchModeAndFocus(HostSearchMode.Content) }
                }
                if (message.isNotBlank()) Text(message, color = Rust, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (searchQuery.isNotBlank()) {
                    LazyColumn(Modifier.weight(1f)) {
                        items(searchResults) { result ->
                            val icon = hostFileIconForPath(result.path, isDirectory = false)
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        revealFileInTree(result.path)
                                        openFile(result.path)
                                    }
                                    .padding(vertical = 7.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    HostFileIcon(icon)
                                    Text(hostDisplayPath(result.path, result.root), color = if (result.kind == HostSearchMatchKind.FileName) Cyan else TextPrimary, fontFamily = MonoFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (dirtyPaths.contains(result.path)) {
                                        Image(
                                            painter = painterResource(Res.drawable.intellij_filetype_modified_dark),
                                            contentDescription = "Unsaved",
                                            modifier = Modifier.size(13.dp),
                                        )
                                    }
                                }
                                Text(listOfNotNull(result.kind.name.lowercase(), result.lineNumber?.let { "line $it" }, result.preview.takeIf { it.isNotBlank() }).joinToString(" · "), color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), state = treeListState) {
                        items(treeRows, key = { it.entry.path }) { row ->
                            HostTreeRowView(
                                row = row,
                                expanded = expandedPaths[row.entry.path] == true,
                                selected = !row.entry.isDirectory && activePath == row.entry.path,
                                dirty = dirtyPaths.contains(row.entry.path),
                                onClick = {
                                    if (row.entry.isDirectory) toggleTreeDirectory(row.entry.path) else openFile(row.entry.path)
                                },
                            )
                        }
                    }
                }
            }
            PaneDivider(
                onDrag = { dragX ->
                    localHostFileSearchPaneWidth = (localHostFileSearchPaneWidth + dragX).coerceIn(500f, 980f)
                },
                onDragEnd = {
                    onUpdateWorkspace { state -> state.copy(hostFileSearchPaneWidth = localHostFileSearchPaneWidth) }
                },
            )
            PanelCard(Modifier.weight(1f).fillMaxHeight()) {
                val tab = activeTab
                if (tab == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Open a file from the browser or indexed results.", color = TextSecondary, fontFamily = MonoFont)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HostFileIcon(hostFileIconForPath(tab.path, isDirectory = false))
                        Text(tab.path, color = if (tab.dirty) Rust else TextSecondary, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (tab.dirty) {
                            Image(
                                painter = painterResource(Res.drawable.intellij_filetype_modified_dark),
                                contentDescription = "Unsaved",
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    if (tab.message.isNotBlank()) Text(tab.message, color = Rust, fontFamily = MonoFont, fontSize = 11.sp)
                    HostCodeEditor(
                        path = tab.path,
                        text = tab.content,
                        languageHint = tab.languageHint,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onTextChange = ::updateEditorTextForPath,
                        onSave = ::saveEditorContentForPath,
                        onClose = { closeActiveTab() },
                        onSearchAll = { setSearchModeAndFocus(HostSearchMode.Combined) },
                        onSearchNames = { setSearchModeAndFocus(HostSearchMode.FileName) },
                        onSearchContents = { setSearchModeAndFocus(HostSearchMode.Content) },
                    )
                }
            }
        }
    }

    conflictTab?.let { tab ->
        ConfirmationDialog(
            confirmation = PendingConfirmation("Overwrite external changes?", "${tab.path}\nThe file changed since Andy opened it.") {
                saveTab(tab, overwrite = true)
                conflictTab = null
            },
            onDismiss = { conflictTab = null },
            onConfirm = {
                saveTab(tab, overwrite = true)
                conflictTab = null
            },
        )
    }
}

private fun hostFileName(path: String): String {
    val trimmed = trimHostTrailingSeparators(path)
    val index = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
    return if (index >= 0 && index < trimmed.lastIndex) trimmed.substring(index + 1) else trimmed.ifBlank { path }
}

private fun hostTreeRows(root: String, children: Map<String, List<HostFileEntry>>, expanded: Map<String, Boolean>): List<HostTreeRow> {
    val rootEntry = HostFileEntry(
        path = root,
        name = hostFileName(root).ifBlank { root },
        isDirectory = true,
        sizeBytes = 0L,
        modifiedMillis = 0L,
    )
    val rows = mutableListOf(HostTreeRow(rootEntry, 0))
    fun append(parent: HostFileEntry, depth: Int) {
        if (expanded[parent.path] != true) return
        children[parent.path].orEmpty().forEach { child ->
            rows += HostTreeRow(child, depth)
            if (child.isDirectory) append(child, depth + 1)
        }
    }
    append(rootEntry, 1)
    return rows
}

private data class HostFileIconSpec(val resource: DrawableResource?)

@Composable
private fun HostTreeRowView(row: HostTreeRow, expanded: Boolean, selected: Boolean, dirty: Boolean, onClick: () -> Unit) {
    val entry = row.entry
    val icon = hostFileIconForPath(entry.path, entry.isDirectory)
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 24.dp)
            .background(if (selected) AndyColors.OrangeSubtle else Color.Transparent, RoundedCornerShape(AndyRadius.R2))
            .then(if (selected) Modifier.border(1.dp, AndyColors.OrangeBorder.copy(alpha = 0.42f), RoundedCornerShape(AndyRadius.R2)) else Modifier)
            .clickable(onClick = onClick)
            .padding(start = (row.depth * 16).dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (entry.isDirectory) {
                if (expanded) "v" else ">"
            } else {
                " "
            },
            color = if (entry.isDirectory) Cyan else TextSecondary,
            fontFamily = MonoFont,
            fontSize = 12.sp,
            modifier = Modifier.width(16.dp),
        )
        HostFileIcon(icon)
        Spacer(Modifier.width(7.dp))
        Text(
            entry.name,
            color = when {
                dirty -> Rust
                selected -> Rust
                entry.isDirectory -> Cyan
                else -> TextPrimary
            },
            fontFamily = MonoFont,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (dirty) {
            Image(
                painter = painterResource(Res.drawable.intellij_filetype_modified_dark),
                contentDescription = "Unsaved",
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        if (!entry.isDirectory) {
            Text(entry.extension.takeIf { it.isNotBlank() } ?: entry.sizeBytes.toString(), color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun HostFileIcon(spec: HostFileIconSpec) {
    if (spec.resource == null) {
        Spacer(Modifier.size(18.dp))
    } else {
        Image(
            painter = painterResource(spec.resource),
            contentDescription = "File type",
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun hostFileIconForPath(path: String, isDirectory: Boolean): HostFileIconSpec {
    if (isDirectory) return HostFileIconSpec(Res.drawable.intellij_node_folder_dark)
    val name = hostFileName(path).lowercase()
    val ext = name.substringAfterLast('.', "")
    return when {
        name == ".gitignore" -> HostFileIconSpec(Res.drawable.intellij_filetype_gitignore)
        name == "build.gradle" || name == "settings.gradle" || name.endsWith(".gradle.kts") || name.endsWith(".gradle") -> HostFileIconSpec(Res.drawable.intellij_filetype_gradle_dark)
        name == "dockerfile" || name == "containerfile" -> HostFileIconSpec(Res.drawable.intellij_filetype_docker_dark)
        name == "makefile" -> HostFileIconSpec(Res.drawable.intellij_filetype_config_dark)
        ext == "kt" -> HostFileIconSpec(Res.drawable.intellij_filetype_kotlin_dark)
        ext == "kts" -> HostFileIconSpec(Res.drawable.intellij_filetype_kotlinScript_dark)
        ext == "java" -> HostFileIconSpec(Res.drawable.intellij_filetype_java_dark)
        ext == "groovy" -> HostFileIconSpec(Res.drawable.intellij_filetype_groovy_dark)
        ext == "json" -> HostFileIconSpec(Res.drawable.intellij_filetype_json_dark)
        ext == "xml" -> HostFileIconSpec(Res.drawable.intellij_filetype_xml_dark)
        ext == "html" || ext == "htm" -> HostFileIconSpec(Res.drawable.intellij_filetype_html_dark)
        ext == "css" || ext == "scss" || ext == "sass" -> HostFileIconSpec(Res.drawable.intellij_filetype_css_dark)
        ext == "js" || ext == "jsx" || ext == "mjs" || ext == "cjs" -> HostFileIconSpec(Res.drawable.intellij_filetype_javaScript_dark)
        ext == "ts" || ext == "tsx" -> HostFileIconSpec(Res.drawable.intellij_filetype_javaScript_dark)
        ext == "md" || ext == "markdown" -> HostFileIconSpec(Res.drawable.intellij_filetype_markdown_dark)
        ext == "c" -> HostFileIconSpec(Res.drawable.intellij_filetype_c_dark)
        ext == "cpp" || ext == "cc" || ext == "cxx" -> HostFileIconSpec(Res.drawable.intellij_filetype_cpp_dark)
        ext == "h" || ext == "hpp" -> HostFileIconSpec(Res.drawable.intellij_filetype_h_dark)
        ext == "png" || ext == "jpg" || ext == "jpeg" || ext == "gif" || ext == "webp" || ext == "svg" -> HostFileIconSpec(Res.drawable.intellij_filetype_image_dark)
        ext == "yml" || ext == "yaml" -> HostFileIconSpec(Res.drawable.intellij_filetype_yaml_dark)
        ext == "toml" -> HostFileIconSpec(Res.drawable.intellij_filetype_toml_dark)
        ext == "sh" || ext == "bash" || ext == "zsh" -> HostFileIconSpec(Res.drawable.intellij_filetype_shell_dark)
        ext == "sql" -> HostFileIconSpec(Res.drawable.intellij_filetype_sql_dark)
        ext == "csv" -> HostFileIconSpec(Res.drawable.intellij_filetype_csv_dark)
        ext == "properties" -> HostFileIconSpec(Res.drawable.intellij_filetype_properties_dark)
        ext == "conf" || ext == "cfg" || ext == "ini" -> HostFileIconSpec(Res.drawable.intellij_filetype_config_dark)
        ext == "txt" -> HostFileIconSpec(Res.drawable.intellij_filetype_text_dark)
        ext.isBlank() -> HostFileIconSpec(Res.drawable.intellij_filetype_unknown_dark)
        else -> HostFileIconSpec(Res.drawable.intellij_filetype_text_dark)
    }
}

@Composable
private fun SearchModePill(text: String, shortcut: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.height(32.dp)
            .background(if (selected) color.copy(alpha = 0.16f) else AndyColors.Neutral900.copy(alpha = 0.35f), RoundedCornerShape(AndyRadius.Pill))
            .border(1.dp, color.copy(alpha = if (selected) 0.52f else 0.22f), RoundedCornerShape(AndyRadius.Pill))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, color = if (selected) color else TextSecondary, fontFamily = MonoFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        if (shortcut.isNotBlank()) {
            Text(shortcut, color = TextSecondary, fontFamily = MonoFont, fontSize = 9.sp)
        }
    }
}

private fun hostParentPath(path: String): String {
    val trimmed = trimHostTrailingSeparators(path)
    if (trimmed == "/" || trimmed.matches(Regex("^[A-Za-z]:$"))) return trimmed
    val index = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
    return when {
        index < 0 -> trimmed.ifBlank { "/" }
        index == 0 -> trimmed.substring(0, 1)
        index == 2 && trimmed.getOrNull(1) == ':' -> trimmed.substring(0, 3)
        else -> trimmed.substring(0, index).ifBlank { "/" }
    }
}

private fun resolveHostRootForPath(path: String?, roots: List<String>): String? {
    val normalizedPath = path?.let(::normalizeHostPath) ?: return null
    return roots.sortedByDescending { normalizeHostPath(it).length }.firstOrNull { root ->
        hostPathStartsWith(normalizedPath, root)
    }
}

private fun hostPathStartsWith(path: String, root: String): Boolean {
    val normalizedPath = normalizeHostPath(path)
    val normalizedRoot = normalizeHostPath(root)
    return normalizedPath == normalizedRoot || normalizedPath.startsWith("$normalizedRoot/")
}

private fun normalizeHostPath(path: String): String = trimHostTrailingSeparators(path).replace('\\', '/').ifBlank { "/" }

private fun trimHostTrailingSeparators(path: String): String {
    var value = path.trim()
    while (value.length > 1 && (value.endsWith('/') || value.endsWith('\\'))) {
        if (value.length == 3 && value[1] == ':') break
        value = value.dropLast(1)
    }
    return value.ifBlank { "/" }
}

private fun hostAncestorDirectories(path: String, root: String): List<String> {
    val normalizedRoot = normalizeHostPath(root)
    val displayRoot = trimHostTrailingSeparators(root)
    val parent = hostParentPath(path)
    if (!hostPathStartsWith(parent, normalizedRoot)) return listOf(displayRoot)
    val relativeParent = normalizeHostPath(parent).removePrefix(normalizedRoot).trim('/')
    if (relativeParent.isBlank()) return listOf(displayRoot)
    val separator = if (root.contains('\\') && !root.contains('/')) "\\" else "/"
    val ancestors = mutableListOf(displayRoot)
    var current = displayRoot
    relativeParent.split('/').filter { it.isNotBlank() }.forEach { segment ->
        current = when {
            current == "/" -> "/$segment"
            current.matches(Regex("^[A-Za-z]:$")) -> "$current$separator$segment"
            else -> "$current$separator$segment"
        }
        ancestors += current
    }
    return ancestors
}

private fun hostDisplayPath(path: String, root: String): String {
    val normalizedPath = normalizeHostPath(path)
    val normalizedRoot = normalizeHostPath(root)
    return normalizedPath.removePrefix(normalizedRoot).trimStart('/').ifBlank { hostFileName(path) }
}

private val DebugNetworkSecurityConfigSnippet = """
res/xml/network_security_config.xml
<network-security-config>
  <debug-overrides>
    <trust-anchors>
      <certificates src="user" />
      <certificates src="system" />
    </trust-anchors>
  </debug-overrides>
</network-security-config>

AndroidManifest.xml
<application android:networkSecurityConfig="@xml/network_security_config" />
""".trimIndent()

private fun manualCertificateSteps(caPath: String, proxyHost: String, port: Int): List<String> = listOf(
    "Start the proxy so Andy creates ${caPath.ifBlank { "~/.andy/proxy/mitmproxy-ca-cert.cer" }}.",
    "Click Prepare phone CA, then finish the CA certificate install on the device from Downloads.",
    "Set the device Wi-Fi proxy to Manual with host ${proxyHost.ifBlank { "this Mac's LAN IP" }} and port $port, or click Configure.",
    "Run a debug build whose network security config trusts user certificates.",
    "Disable Private DNS and retry over HTTP/1.1 if a request is missing; pinned apps and QUIC/HTTP3 will not decrypt.",
)

private fun hostSetupSteps(engineReady: Boolean): List<String> {
    val mitmproxyStep = if (engineReady) {
        "mitmproxy is installed; Andy will start mitmdump automatically for Network."
    } else {
        "Install mitmproxy on the host: brew install mitmproxy. Andy uses mitmdump from that package."
    }
    return listOf(
        "Install Android Studio or Android command-line tools so Andy can find adb, emulator, sdkmanager, and avdmanager.",
        "Embedded mirroring uses Andy's bundled scrcpy-server. For local development only, SCRCPY_SERVER_PATH can point at another scrcpy-server file.",
        mitmproxyStep,
    )
}

@Composable
private fun NetworkScreen(
    services: AndyServices,
    sdk: SdkDiscovery,
    serial: String?,
    device: AndroidDevice?,
    port: Int,
    rules: List<ProxyRule>,
    rulesVisible: Boolean,
    liveVisible: Boolean,
    onPortChange: (Int) -> Unit,
    onRulesChange: (List<ProxyRule>) -> Unit,
    onRulesVisibleChange: (Boolean) -> Unit,
) {
    val proxy = services.proxy
    val scope = rememberCoroutineScope()
    var portText by remember(port) { mutableStateOf(port.toString()) }
    var status by remember { mutableStateOf("Proxy stopped") }
    var proxyStatus by remember { mutableStateOf("Proxy stopped") }
    var engineStatus by remember { mutableStateOf("Checking mitmdump") }
    var engineReady by remember { mutableStateOf(false) }
    var caPath by remember { mutableStateOf("") }
    var proxyHost by remember { mutableStateOf("") }
    var exchanges by remember { mutableStateOf<List<NetworkExchange>>(emptyList()) }
    var selectedFlowId by remember { mutableStateOf<String?>(null) }
    var setupExpanded by remember { mutableStateOf(false) }
    var setupManuallyToggled by remember { mutableStateOf(false) }
    var setupDefaultApplied by remember { mutableStateOf(false) }
    var selectedExpanded by remember { mutableStateOf(true) }
    var seenFlowIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val expandedTrafficKeys = remember { mutableStateMapOf<String, Boolean>() }
    val flashingTrafficKeys = remember { mutableStateMapOf<String, Long>() }
    var ruleName by remember { mutableStateOf("") }
    var rulePattern by remember { mutableStateOf("") }
    var ruleMethod by remember { mutableStateOf("") }
    var ruleStatus by remember { mutableStateOf("") }
    var ruleSetHeaders by remember { mutableStateOf("") }
    var ruleRemoveHeaders by remember { mutableStateOf("") }
    var ruleBody by remember { mutableStateOf("") }
    var editingRuleId by remember { mutableStateOf<String?>(null) }
    val selected = exchanges.firstOrNull { it.flowId == selectedFlowId } ?: exchanges.lastOrNull()
    var trafficWidth by remember { mutableStateOf(260f) }
    var statusWidth by remember { mutableStateOf(72f) }
    var typeWidth by remember { mutableStateOf(150f) }
    var sizeWidth by remember { mutableStateOf(80f) }
    var msWidth by remember { mutableStateOf(70f) }
    var focusedPath by remember { mutableStateOf<String?>(null) }
    var engineChecked by remember { mutableStateOf(false) }
    var deviceReadinessChecked by remember { mutableStateOf(false) }

    val currentPort = portText.toIntOrNull()?.coerceIn(1, 65535) ?: port
    val filteredExchanges = remember(exchanges, focusedPath) {
        if (focusedPath == null) {
            exchanges
        } else {
            exchanges.filter { exchange ->
                focusedPath in networkTrafficAncestorKeys(exchange)
            }
        }
    }
    val trafficTree = remember(filteredExchanges) { buildNetworkTrafficTree(filteredExchanges) }
    val visibleTrafficRows = remember(trafficTree, expandedTrafficKeys.toMap()) {
        flattenNetworkTrafficTree(trafficTree, expandedTrafficKeys)
    }
    var caInstalled by remember { mutableStateOf(false) }
    var proxyConfigured by remember { mutableStateOf(false) }
    var routeDiagnostics by remember { mutableStateOf<NetworkRouteDiagnostics?>(null) }
    var userCaVerifiedByTrafficForDevice by remember { mutableStateOf(false) }
    var proxyTrafficObservedForDevice by remember { mutableStateOf(false) }
    var trafficEvidenceSerial by remember { mutableStateOf<String?>(null) }
    var flowIdsAtSerialChange by remember { mutableStateOf(emptySet<String>()) }
    val latestRules by rememberUpdatedState(rules)

    LaunchedEffect(Unit) {
        caPath = proxy.certificateAuthorityPath()
        val engine = proxy.detectMitmproxy()
        engineReady = engine.isSuccess
        engineStatus = if (engine.isSuccess) {
            "mitmdump: ${engine.stdout.ifBlank { "ready" }}"
        } else {
            engine.stderr
        }
        engineChecked = true
    }
    LaunchedEffect(engineReady, currentPort) {
        if (!engineReady) return@LaunchedEffect
        val currentStatus = try {
            withTimeout(200) { proxy.status.first() }
        } catch (_: Exception) {
            proxyStatus
        }
        if (shouldAutoStartProxy(currentStatus, currentPort)) {
            proxy.ensureCertificateAuthority()
            val result = proxy.start(currentPort, latestRules)
            val message = if (result.isSuccess) result.stdout else result.stderr
            status = message
            if (result.isSuccess) proxyStatus = message
        }
    }
    LaunchedEffect(Unit) {
        proxy.exchanges.collectLatest { exchanges = it }
    }
    LaunchedEffect(Unit) {
        proxy.status.collectLatest {
            proxyStatus = it
            status = it
        }
    }
    LaunchedEffect(serial, exchanges) {
        if (serial != trafficEvidenceSerial) {
            trafficEvidenceSerial = serial
            flowIdsAtSerialChange = exchanges.map { it.flowId }.toSet()
            userCaVerifiedByTrafficForDevice = false
            proxyTrafficObservedForDevice = false
        } else {
            val newExchanges = exchanges.filter { it.flowId !in flowIdsAtSerialChange }
            if (newExchanges.isNotEmpty()) proxyTrafficObservedForDevice = true
            if (newExchanges.any { it.tlsStatus == "tls" && it.error == null }) {
                userCaVerifiedByTrafficForDevice = true
            }
        }
    }
    LaunchedEffect(serial, currentPort) {
        if (serial == null) {
            caInstalled = false
            proxyConfigured = false
            routeDiagnostics = null
            deviceReadinessChecked = true
            return@LaunchedEffect
        }
        deviceReadinessChecked = false
        while (true) {
            val isCaOk = proxy.isCertificateInstalled(serial)
            val host = proxy.resolveDeviceProxyHost(serial)
            val isProxyOk = proxy.isDeviceProxyConfigured(serial, host, currentPort)
            val route = proxy.diagnoseDeviceProxyRoute(serial, host, currentPort)
            caInstalled = isCaOk
            proxyConfigured = isProxyOk
            routeDiagnostics = route
            deviceReadinessChecked = true
            delay(3000)
        }
    }
    LaunchedEffect(
        engineChecked,
        deviceReadinessChecked,
        sdk.hasAdb,
        engineReady,
        proxyStatus,
        serial,
        caInstalled,
        proxyTrafficObservedForDevice,
        proxyConfigured,
        routeDiagnostics,
    ) {
        if (setupDefaultApplied || setupManuallyToggled || !engineChecked || !deviceReadinessChecked) return@LaunchedEffect
        val redCount = listOf(
            sdk.hasAdb,
            true,
            engineReady,
            proxyStatus.contains("listening on"),
            serial != null && (caInstalled || proxyTrafficObservedForDevice),
            serial != null && proxyConfigured,
            serial != null && routeDiagnostics?.vpnActive != true && routeDiagnostics?.routeUsesVpn != true,
        ).count { !it }
        setupExpanded = redCount > 2
        setupDefaultApplied = true
    }
    LaunchedEffect(serial) {
        proxyHost = serial?.let { selectedSerial ->
            val activation = proxy.activatePersistedCertificateAuthority(selectedSerial)
            if (activation.isSuccess && activation.stdout.isNotBlank()) {
                status = activation.stdout
            }
            proxy.resolveDeviceProxyHost(selectedSerial)
        }.orEmpty()
    }
    LaunchedEffect(rules) {
        proxy.updateRules(rules)
    }
    LaunchedEffect(exchanges.map { it.flowId }) {
        val currentIds = exchanges.map { it.flowId }.toSet()
        val added = exchanges.filter { it.flowId !in seenFlowIds }
        if (seenFlowIds.isNotEmpty() && added.isNotEmpty()) {
            added.flatMap(::networkTrafficAncestorKeys).distinct().forEach { key ->
                if (expandedTrafficKeys[key] != true && key !in flashingTrafficKeys) {
                    val flashToken = System.nanoTime()
                    flashingTrafficKeys[key] = flashToken
                    scope.launch {
                        delay(280)
                        if (flashingTrafficKeys[key] == flashToken) {
                            flashingTrafficKeys.remove(key)
                        }
                    }
                }
            }
        }
        seenFlowIds = currentIds
    }

    fun persistPort() {
        onPortChange(currentPort)
    }

    fun resetRuleForm() {
        ruleName = ""
        rulePattern = ""
        ruleMethod = ""
        ruleStatus = ""
        ruleSetHeaders = ""
        ruleRemoveHeaders = ""
        ruleBody = ""
        editingRuleId = null
    }

    fun addOrSaveRule() {
        val pattern = rulePattern.trim()
        if (pattern.isBlank()) {
            status = "Enter a URL match pattern before adding a rule"
            return
        }
        val editId = editingRuleId
        val editIdx = editId?.let { id -> rules.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
        val rule = ProxyRule(
            id = editId ?: "rule-${rules.size + 1}-${pattern.hashCode().toString().replace("-", "n")}",
            name = ruleName.ifBlank { pattern },
            enabled = editIdx?.let { rules[it].enabled } ?: true,
            urlPattern = pattern,
            method = ruleMethod.trim().uppercase().ifBlank { null },
            statusCode = ruleStatus.toIntOrNull(),
            setHeaders = parseHeaderLines(ruleSetHeaders),
            removeHeaders = ruleRemoveHeaders.split(',', '\n').map { it.trim() }.filter { it.isNotBlank() },
            responseBody = ruleBody.takeIf { it.isNotBlank() },
        )
        if (editIdx != null) {
            onRulesChange(rules.mapIndexed { i, existing -> if (i == editIdx) rule else existing })
        } else {
            onRulesChange(rules + rule)
        }
        resetRuleForm()
    }

    fun clearCapturedTraffic() {
        scope.launch {
            val result = proxy.clearTraffic()
            selectedFlowId = null
            seenFlowIds = emptySet()
            flashingTrafficKeys.clear()
            status = if (result.isSuccess) result.stdout else result.stderr
        }
    }

    fun editRule(ruleId: String) {
        val rule = rules.firstOrNull { it.id == ruleId } ?: return
        ruleName = rule.name
        rulePattern = rule.urlPattern
        ruleMethod = rule.method ?: ""
        ruleStatus = rule.statusCode?.toString() ?: ""
        ruleSetHeaders = rule.setHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        ruleRemoveHeaders = rule.removeHeaders.joinToString("\n")
        ruleBody = rule.responseBody ?: ""
        editingRuleId = rule.id
        onRulesVisibleChange(true)
    }

    val networkPageScrollState = rememberScrollState()

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier
                .weight(1.45f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(networkPageScrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PanelCard(Modifier.animateContentSize()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("Debug-app HTTPS proxy", color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextField(
                        portText,
                        {
                            portText = it.filter(Char::isDigit).take(5)
                            it.toIntOrNull()?.takeIf { value -> value in 1..65535 }?.let(onPortChange)
                        },
                        singleLine = true,
                        modifier = Modifier.width(86.dp).height(54.dp),
                        textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                        colors = fieldColors(),
                    )
                    Button(onClick = {
                        persistPort()
                        scope.launch {
                            proxy.ensureCertificateAuthority()
                            val result = proxy.start(currentPort, rules)
                            val message = if (result.isSuccess) result.stdout else result.stderr
                            status = message
                            if (result.isSuccess) proxyStatus = message
                        }
                    }) { Text("Start") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val result = proxy.stop()
                            status = result.stdout
                            proxyStatus = result.stdout
                        }
                    }) { Text("Stop") }
                    OutlinedButton(onClick = ::clearCapturedTraffic) { Text("Clear traffic") }
                    Text(
                        if (setupExpanded) "Hide setup" else "Show setup",
                        color = Rust,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable {
                            setupManuallyToggled = true
                            setupExpanded = !setupExpanded
                        },
                    )
                }
                val proxyStarted = proxyStatus.contains("listening on")
                val caText = when {
                    serial == null -> "Select a device first"
                    caInstalled -> "System CA installed"
                    userCaVerifiedByTrafficForDevice -> "User CA verified by HTTPS traffic"
                    proxyTrafficObservedForDevice -> "Traffic observed"
                    else -> "Use System CA or Prepare phone CA"
                }
                val configText = when {
                    serial == null -> "Select a device first"
                    proxyConfigured -> "Device is routed"
                    else -> "Click 'Configure' to route"
                }
                val routeText = when {
                    serial == null -> "Select a device first"
                    routeDiagnostics == null -> "Checking route"
                    routeDiagnostics?.hostProxyActive == true && routeDiagnostics?.hostUpstreamProxy != null -> "Mac proxy chained"
                    routeDiagnostics?.hostProxyActive == true -> "Mac proxy active"
                    routeDiagnostics?.vpnActive == true -> "VPN active (may cause issues)"
                    routeDiagnostics?.routeUsesVpn == true -> "Proxy route uses VPN"
                    routeDiagnostics?.proxyConfigured == false -> "Proxy route needs repair"
                    else -> "No VPN route issue detected"
                }
                val setupRequirements = listOf(
                    SetupRequirement(
                        label = "Android SDK platform-tools",
                        ok = sdk.hasAdb,
                        readyText = "ADB is available through Andy's SDK selection",
                        missingText = "Install Android Studio or command-line tools",
                    ),
                    SetupRequirement(
                        label = "scrcpy-server",
                        ok = true,
                        readyText = "Bundled with Andy for embedded mirroring",
                        missingText = "Packaged builds include scrcpy-server",
                    ),
                    SetupRequirement(
                        label = "mitmproxy",
                        ok = engineReady,
                        readyText = engineStatus,
                        missingText = "Required for Network capture and rewrite rules",
                        installCommand = "brew install mitmproxy",
                    ),
                )
                val networkStatusRequirements = listOf(
                    SetupRequirement(
                        label = "Proxy Status",
                        ok = proxyStarted,
                        readyText = "Listening on port $currentPort",
                        missingText = "Click Start to start",
                    ),
                    SetupRequirement(
                        label = "CA Trust",
                        ok = serial != null && (caInstalled || proxyTrafficObservedForDevice),
                        readyText = caText,
                        missingText = caText,
                    ),
                    SetupRequirement(
                        label = "Device Proxy Routing",
                        ok = serial != null && proxyConfigured,
                        readyText = configText,
                        missingText = configText,
                    ),
                    SetupRequirement(
                        label = "VPN / Route",
                        ok = serial != null &&
                            routeDiagnostics?.vpnActive != true &&
                            routeDiagnostics?.routeUsesVpn != true &&
                            routeDiagnostics?.hostProxyBypassLooksSafe != false,
                        readyText = routeText,
                        missingText = routeText,
                    ),
                )
                val showRouteWarning = routeDiagnostics?.hasBlockingIssue == true && proxyStarted && !proxyTrafficObservedForDevice
                AnimatedVisibility(setupExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        ) {
                            OutlinedButton(
                                enabled = serial != null,
                                onClick = {
                                    if (serial != null) scope.launch {
                                        val host = proxy.resolveDeviceProxyHost(serial)
                                        proxyHost = host
                                        val result = proxy.configureDeviceProxy(serial, host, currentPort)
                                        status = if (result.isSuccess) result.stdout else result.stderr
                                        proxyConfigured = proxy.isDeviceProxyConfigured(serial, host, currentPort)
                                        routeDiagnostics = proxy.diagnoseDeviceProxyRoute(serial, host, currentPort)
                                    }
                                },
                            ) { Text("Configure device proxy") }
                            OutlinedButton(
                                enabled = serial != null,
                                onClick = {
                                    if (serial != null) scope.launch {
                                        val host = proxy.resolveDeviceProxyHost(serial)
                                        proxyHost = host
                                        val configured = proxy.configureDeviceProxy(serial, host, currentPort)
                                        val route = proxy.diagnoseDeviceProxyRoute(serial, host, currentPort)
                                        routeDiagnostics = route
                                        proxyConfigured = route.proxyConfigured
                                        val restart = if (route.hostProxyActive) proxy.start(currentPort, rules) else null
                                        status = if (route.vpnActive) {
                                            "Proxy route repaired, but VPN is still active. Open VPN settings and disable or split-tunnel the test app."
                                        } else if (restart?.isSuccess == true) {
                                            "Proxy route repaired; Andy restarted mitmproxy through the Mac proxy."
                                        } else if (configured.isSuccess && !route.hasBlockingIssue) {
                                            "Proxy route repaired"
                                        } else {
                                            (route.issues.ifEmpty { listOf(restart?.stderr ?: configured.stderr) }).joinToString(" ")
                                        }
                                    }
                                },
                            ) { Text("Repair proxy route") }
                            OutlinedButton(
                                enabled = serial != null,
                                onClick = {
                                    if (serial != null) scope.launch {
                                        val result = proxy.openVpnSettings(serial)
                                        status = if (result.isSuccess) "Opened Android VPN settings" else result.stderr
                                    }
                                },
                            ) { Text("Open VPN settings") }
                            OutlinedButton(
                                enabled = serial != null,
                                onClick = {
                                    if (serial != null) scope.launch {
                                        val result = proxy.clearDeviceProxy(serial)
                                        status = if (result.isSuccess) result.stdout else result.stderr
                                        val host = proxy.resolveDeviceProxyHost(serial)
                                        proxyConfigured = proxy.isDeviceProxyConfigured(serial, host, currentPort)
                                    }
                                },
                            ) { Text("Clear proxy") }
                            OutlinedButton(
                                enabled = serial != null,
                                onClick = {
                                    if (serial != null) scope.launch {
                                        val result = proxy.installSystemCertificateAuthority(serial)
                                        status = if (result.isSuccess) result.stdout else result.stderr
                                        caInstalled = proxy.isCertificateInstalled(serial)
                                    }
                                },
                            ) { Text("System CA (root)") }
                            OutlinedButton(
                                enabled = serial != null,
                                onClick = {
                                    if (serial != null) scope.launch {
                                        proxy.ensureCertificateAuthority()
                                        val result = proxy.prepareUserCertificateInstall(serial)
                                        status = if (result.isSuccess) result.stdout else result.stderr
                                    }
                                },
                            ) { Text("Prepare phone CA") }
                        }
                        SetupChecklist(setupRequirements)
                        SetupChecklist(networkStatusRequirements)
                        AnimatedVisibility(showRouteWarning) {
                            NetworkRouteWarningCard(routeDiagnostics)
                        }
                        Text(status, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusTag(if (engineReady) "mitmproxy ready" else "mitmproxy missing", if (engineReady) Green else Red)
                            Text(
                                text = buildString {
                                    append(engineStatus)
                                    append("  ·  ")
                                    append("Endpoint: ")
                                    append(proxyHost.ifBlank { "select device" })
                                    append(":")
                                    append(currentPort)
                                },
                                color = TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        InstallStepsCard(
                            title = "Host prerequisites",
                            steps = hostSetupSteps(engineReady),
                        )
                        Text("CA: ${caPath.ifBlank { "~/.andy/proxy/mitmproxy-ca-cert.cer" }}", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ManualCertificateSetupCard(
                            steps = manualCertificateSteps(caPath, proxyHost, currentPort),
                        )
                        Text(
                            "Capture scope: physical devices can trust Andy as a user CA only after manual approval, and only debug apps that opt into user CAs will decrypt. Chrome and many third-party apps need system trust on a rooted device or rootable non-Play emulator; pinned apps, QUIC/HTTP3, private DNS, and direct UDP will not appear in v1.",
                            color = Yellow,
                            fontSize = 12.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("Debug app config", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Text(
                            DebugNetworkSecurityConfigSnippet,
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            maxLines = 12,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("Use this only in debug builds that trust user CAs. Certificate pinning and arbitrary third-party app bypass are out of scope.", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }
            if (focusedPath != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .background(AndyColors.OrangeSubtle, RoundedCornerShape(AndyRadius.R3))
                        .border(1.dp, AndyColors.OrangeBorder, RoundedCornerShape(AndyRadius.R3))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(AndyColors.Orange, RoundedCornerShape(AndyRadius.Pill))
                        )
                        Text(
                            text = "Focus mode: showing only ${focusedPath?.removePrefix("base:")}",
                            color = AndyColors.Neutral100,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        text = "Exit Focus",
                        color = AndyColors.Orange,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { focusedPath = null }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                HeaderCell("TRAFFIC", trafficWidth.dp) { trafficWidth = it.coerceIn(120f, 600f) }
                HeaderCell("STATUS", statusWidth.dp) { statusWidth = it.coerceIn(50f, 150f) }
                HeaderCell("TYPE", typeWidth.dp) { typeWidth = it.coerceIn(80f, 250f) }
                HeaderCell("SIZE", sizeWidth.dp) { sizeWidth = it.coerceIn(50f, 150f) }
                HeaderCell("MS", msWidth.dp) { msWidth = it.coerceIn(50f, 150f) }
                Text("RULE", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 520.dp)) {
                items(visibleTrafficRows, key = { row -> row.key }) { row ->
                    NetworkTrafficRowItem(
                        row = row,
                        expanded = expandedTrafficKeys[row.key] == true,
                        flashing = row.key in flashingTrafficKeys,
                        trafficWidth = trafficWidth,
                        statusWidth = statusWidth,
                        typeWidth = typeWidth,
                        sizeWidth = sizeWidth,
                        msWidth = msWidth,
                        onToggle = {
                            if (row.hasChildren) {
                                expandedTrafficKeys[row.key] = expandedTrafficKeys[row.key] != true
                                flashingTrafficKeys.remove(row.key)
                            }
                        },
                        onSelect = { exchange ->
                            selectedFlowId = exchange.flowId
                        },
                        onFocus = { path ->
                            focusedPath = path
                        },
                        onAddRule = { exchange ->
                            val pathSegment = exchange.url.substringAfterLast("/").substringBefore("?")
                            ruleName = if (pathSegment.isNotBlank()) "Mock $pathSegment" else "Mock response"
                            rulePattern = exchange.url
                            ruleMethod = exchange.method
                            ruleStatus = exchange.statusCode?.toString() ?: "200"
                            val excludedHeaders = setOf(
                                "content-length",
                                "content-encoding",
                                "transfer-encoding",
                                "connection",
                                "keep-alive",
                                "date",
                                "server",
                                "accept-ranges",
                                "content-range",
                                "age"
                            )
                            ruleSetHeaders = exchange.responseHeaders.entries
                                .filter { it.key.lowercase() !in excludedHeaders }
                                .joinToString("\n") { "${it.key}: ${it.value}" }
                            ruleRemoveHeaders = ""
                            ruleBody = exchange.responseBodyPreview ?: ""
                            onRulesVisibleChange(true)
                        }
                    )
                }
                if (visibleTrafficRows.isEmpty()) {
                    item {
                        EmptyState("No traffic yet. Start the proxy, configure a device, then make a request.")
                    }
                }
            }
            }
            SelectedFlowPanel(
                selected = selected,
                expanded = selectedExpanded,
                onToggle = { selectedExpanded = !selectedExpanded },
                modifier = Modifier.fillMaxWidth().then(if (selectedExpanded) Modifier.height(340.dp) else Modifier.heightIn(min = 54.dp)),
            )
        }
        if (rulesVisible || liveVisible) {
            Column(Modifier.width(420.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (liveVisible && rulesVisible) {
                    var livePanelHeight by remember { mutableStateOf(300f) }
                    DeviceLivePanel(
                        services = services,
                        serial = serial,
                        device = device,
                        modifier = Modifier.fillMaxWidth().height(livePanelHeight.dp),
                    )
                    HorizontalPaneDivider(
                        onDrag = { dragY -> livePanelHeight = (livePanelHeight + dragY).coerceIn(100f, 600f) }
                    )
                    RulesPaneContent(
                        rules = rules,
                        ruleName = ruleName,
                        onRuleNameChange = { ruleName = it },
                        rulePattern = rulePattern,
                        onRulePatternChange = { rulePattern = it },
                        ruleMethod = ruleMethod,
                        onRuleMethodChange = { ruleMethod = it },
                        ruleStatus = ruleStatus,
                        onRuleStatusChange = { ruleStatus = it },
                        ruleSetHeaders = ruleSetHeaders,
                        onRuleSetHeadersChange = { ruleSetHeaders = it },
                        ruleRemoveHeaders = ruleRemoveHeaders,
                        onRuleRemoveHeadersChange = { ruleRemoveHeaders = it },
                        ruleBody = ruleBody,
                        onRuleBodyChange = { ruleBody = it },
                        onRulesChange = onRulesChange,
                        onAddOrSaveRule = ::addOrSaveRule,
                        editingRuleId = editingRuleId,
                        onEditRule = ::editRule,
                        onCancelEdit = ::resetRuleForm,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                } else if (liveVisible) {
                    DeviceLivePanel(
                        services = services,
                        serial = serial,
                        device = device,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else if (rulesVisible) {
                    RulesPaneContent(
                        rules = rules,
                        ruleName = ruleName,
                        onRuleNameChange = { ruleName = it },
                        rulePattern = rulePattern,
                        onRulePatternChange = { rulePattern = it },
                        ruleMethod = ruleMethod,
                        onRuleMethodChange = { ruleMethod = it },
                        ruleStatus = ruleStatus,
                        onRuleStatusChange = { ruleStatus = it },
                        ruleSetHeaders = ruleSetHeaders,
                        onRuleSetHeadersChange = { ruleSetHeaders = it },
                        ruleRemoveHeaders = ruleRemoveHeaders,
                        onRuleRemoveHeadersChange = { ruleRemoveHeaders = it },
                        ruleBody = ruleBody,
                        onRuleBodyChange = { ruleBody = it },
                        onRulesChange = onRulesChange,
                        onAddOrSaveRule = ::addOrSaveRule,
                        editingRuleId = editingRuleId,
                        onEditRule = ::editRule,
                        onCancelEdit = ::resetRuleForm,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun GlowingDot(isGreen: Boolean, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by if (isGreen) {
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.6f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            )
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    val color = if (isGreen) Green else Red

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(16.dp)
    ) {
        if (isGreen) {
            Box(
                Modifier
                    .size(10.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                        alpha = (2f - pulseScale).coerceIn(0f, 1f)
                    }
                    .background(color.copy(alpha = 0.4f), CircleShape)
            )
        }
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape)
                .border(1.dp, color.copy(alpha = 0.8f), CircleShape)
        )
    }
}

@Composable
private fun NetworkRouteWarningCard(diagnostics: NetworkRouteDiagnostics?, modifier: Modifier = Modifier) {
    val issues = diagnostics?.issues.orEmpty()
    Column(
        modifier
            .fillMaxWidth()
            .background(Yellow.copy(alpha = 0.10f), RoundedCornerShape(AndyRadius.R3))
            .border(1.dp, Yellow.copy(alpha = 0.55f), RoundedCornerShape(AndyRadius.R3))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Traffic may be bypassing Andy", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        issues.take(3).forEach { issue ->
            Text(issue, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Text(
            "Repair proxy route reapplies Android's global proxy and restarts mitmproxy through the Mac proxy when one is configured. If traffic still disappears, add localhost, 127.0.0.1, and 10.0.2.2 to the Mac proxy bypass list or disable/split-tunnel the VPN.",
            color = Yellow,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun ManualCertificateSetupCard(steps: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R3))
            .border(1.dp, AndyColors.OrangeBorder.copy(alpha = 0.45f), RoundedCornerShape(AndyRadius.R3))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Physical device manual CA", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        steps.forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text("${index + 1}.", color = Rust, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(step, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun InstallStepsCard(title: String, steps: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R3))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        steps.forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text("${index + 1}.", color = Rust, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(step, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SetupChecklist(requirements: List<SetupRequirement>, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R3))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        requirements.forEach { requirement ->
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlowingDot(requirement.ok, Modifier.padding(top = 2.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(requirement.label, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (requirement.ok) requirement.readyText else requirement.missingText,
                        color = if (requirement.ok) Green else Red,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!requirement.ok && requirement.installCommand != null) {
                        Text(requirement.installCommand, color = Rust, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun RulesPaneContent(
    rules: List<ProxyRule>,
    ruleName: String,
    onRuleNameChange: (String) -> Unit,
    rulePattern: String,
    onRulePatternChange: (String) -> Unit,
    ruleMethod: String,
    onRuleMethodChange: (String) -> Unit,
    ruleStatus: String,
    onRuleStatusChange: (String) -> Unit,
    ruleSetHeaders: String,
    onRuleSetHeadersChange: (String) -> Unit,
    ruleRemoveHeaders: String,
    onRuleRemoveHeadersChange: (String) -> Unit,
    ruleBody: String,
    onRuleBodyChange: (String) -> Unit,
    onRulesChange: (List<ProxyRule>) -> Unit,
    onAddOrSaveRule: () -> Unit,
    editingRuleId: String?,
    onEditRule: (String) -> Unit,
    onCancelEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isEditing = editingRuleId != null
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            PanelCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (isEditing) "Edit rule" else "Rules", color = TextPrimary, fontWeight = FontWeight.Bold)
                    if (isEditing) {
                        Text(
                            "Cancel",
                            color = Rust,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { onCancelEdit() },
                        )
                    }
                }
                LabeledField("Name", ruleName, onRuleNameChange, Modifier.fillMaxWidth(), placeholder = "Mock response")
                LabeledField("URL pattern", rulePattern, onRulePatternChange, Modifier.fillMaxWidth(), placeholder = "https://api.example.com/v1/*/profile")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledField("Method", ruleMethod, { onRuleMethodChange(it.uppercase().take(8)) }, Modifier.width(110.dp))
                    LabeledField("Status", ruleStatus, { onRuleStatusChange(it.filter(Char::isDigit).take(3)) }, Modifier.width(100.dp), placeholder = "200")
                }
                LabeledField("Set headers", ruleSetHeaders, onRuleSetHeadersChange, Modifier.fillMaxWidth(), singleLine = false, minHeight = 92.dp, placeholder = "content-type: application/json\nx-debug: true")
                LabeledField("Remove headers", ruleRemoveHeaders, onRuleRemoveHeadersChange, Modifier.fillMaxWidth(), singleLine = false, minHeight = 76.dp, placeholder = "Server\netag\nx-powered-by")
                TextField(
                    ruleBody,
                    onRuleBodyChange,
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    colors = fieldColors(),
                    placeholder = { Text("{\"andy\":true}", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                )
                Button(onClick = onAddOrSaveRule, modifier = Modifier.fillMaxWidth()) { Text(if (isEditing) "Save rule" else "Add rule") }
            }
        }
        itemsIndexed(rules, key = { _, rule -> rule.id }) { index, rule ->
            val isBeingEdited = editingRuleId == rule.id
            PanelCard(
                modifier = if (isBeingEdited) Modifier.border(1.dp, Rust, RoundedCornerShape(AndyRadius.R3)) else Modifier
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(rule.enabled, { checked ->
                        onRulesChange(rules.mapIndexed { i, item -> if (i == index) item.copy(enabled = checked) else item })
                    })
                    Column(Modifier.weight(1f)) {
                        Text(rule.name, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${rule.method ?: "*"} ${rule.urlPattern}", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(rule.statusCode?.let { "status $it" }, rule.responseBody?.let { "body" }, rule.setHeaders.takeIf { it.isNotEmpty() }?.let { "${it.size} headers" }).joinToString(" · "), color = TextSecondary, fontSize = 11.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onEditRule(rule.id) }) { Text(if (isBeingEdited) "Editing" else "Edit") }
                    OutlinedButton(onClick = { if (index > 0) onRulesChange(rules.swapItems(index, index - 1)) }, enabled = index > 0) { Text("Up") }
                    OutlinedButton(onClick = { if (index < rules.lastIndex) onRulesChange(rules.swapItems(index, index + 1)) }, enabled = index < rules.lastIndex) { Text("Down") }
                    OutlinedButton(onClick = { onRulesChange(rules.filterIndexed { i, _ -> i != index }) }) { Text("Remove") }
                }
            }
        }
    }
}

private fun shouldAutoStartProxy(status: String, port: Int): Boolean {
    val normalized = status.trim()
    if (normalized == "Proxy stopped" || normalized == "mitmdump exited" || normalized.startsWith("Proxy failed")) {
        return true
    }
    val listeningPort = Regex("""listening on \S+:(\d{1,5})""")
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    return normalized.contains("listening on") && listeningPort != null && listeningPort != port
}


@Composable
private fun NetworkTrafficRowItem(
    row: NetworkTrafficRow,
    expanded: Boolean,
    flashing: Boolean,
    trafficWidth: Float,
    statusWidth: Float,
    typeWidth: Float,
    sizeWidth: Float,
    msWidth: Float,
    onToggle: () -> Unit,
    onSelect: (NetworkExchange) -> Unit,
    onFocus: (String) -> Unit,
    onAddRule: (NetworkExchange) -> Unit,
) {
    val latest = row.latest
    val flashColor by animateColorAsState(
        targetValue = if (flashing) Rust.copy(alpha = 0.24f) else AndyColors.Neutral900.copy(alpha = 0.65f),
    )
    val selectedColor = if (row.exchange != null) AndyColors.Neutral800.copy(alpha = 0.9f) else flashColor
    var showMenu by remember { mutableStateOf(false) }

    Box {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 32.dp)
                .background(selectedColor)
                .border(1.dp, if (flashing) Rust.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.05f))
                .clickable {
                    row.exchange?.let(onSelect) ?: onToggle()
                }
                .pointerInput(row.key) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press) {
                                if (event.buttons.isSecondaryPressed) {
                                    if (row.exchange == null) {
                                        onFocus(row.key)
                                    } else {
                                        onSelect(row.exchange)
                                        showMenu = true
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.width(trafficWidth.dp).padding(start = (row.depth * 16).dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        row.exchange != null -> "•"
                        row.hasChildren && expanded -> "v"
                        row.hasChildren -> ">"
                        else -> " "
                    },
                    color = if (row.exchange != null) Rust else TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.width(18.dp),
                )
                Text(
                    if (row.exchange != null) "${latest?.method ?: "-"}  ${row.label}" else "${row.label}  (${row.count})",
                    color = if (row.exchange != null) TextPrimary else AndyColors.Neutral100,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val response = row.exchange
            MonoCell(if (response != null) response.statusCode?.toString() ?: "-" else "", statusWidth.dp, if ((response?.statusCode ?: 200) >= 400) Red else TextSecondary)
            MonoCell(if (response != null) response.contentType?.substringBefore(';') ?: "-" else "", typeWidth.dp, TextSecondary)
            MonoCell(if (response != null) response.sizeBytes?.toString() ?: "-" else "", sizeWidth.dp, TextSecondary)
            MonoCell(if (response != null) response.durationMillis?.toString() ?: "-" else "", msWidth.dp, TextSecondary)
            Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    if (response != null) response.matchedRuleId ?: "-" else "",
                    color = if (response?.matchedRuleId != null) Green else TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (row.exchange != null) {
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                containerColor = PanelSoft
            ) {
                DropdownMenuItem(
                    text = { Text("Add rule", color = TextPrimary) },
                    onClick = {
                        showMenu = false
                        onAddRule(row.exchange)
                    }
                )
            }
        }
    }
}

@Composable
private fun SelectedFlowPanel(selected: NetworkExchange?, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    PanelCard(modifier.animateContentSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Selected flow", color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(
                selected?.let { "${it.method} ${it.statusCode ?: "-"} ${it.url}" } ?: "No flow selected",
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FilterPill(if (expanded) "Hide" else "Show", expanded, Rust, onToggle)
        }
        AnimatedVisibility(expanded) {
            if (selected == null) {
                EmptyState("Select a network call to inspect headers and body.")
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    FlowPreviewScrollable(
                        title = "Request",
                        headers = selected.requestHeaders,
                        body = selected.requestBodyPreview,
                        formatJson = true,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    FlowPreviewScrollable(
                        title = "Response",
                        headers = selected.responseHeaders,
                        body = selected.responseBodyPreview,
                        formatJson = true,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun FlowPreviewScrollable(
    title: String,
    headers: Map<String, String>,
    body: String?,
    formatJson: Boolean,
    modifier: Modifier = Modifier,
) {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val bodyValue = body?.takeIf { it.isNotBlank() }
    val jsonBody = remember(body, formatJson) { if (formatJson) parseJsonBodyPreview(body) else null }
    val expandedJsonKeys = remember(body) { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(jsonBody) {
        expandedJsonKeys.clear()
        jsonBody?.let { expandedJsonKeys[it.path] = true }
    }
    val headerText = remember(headers) {
        headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }.ifBlank { "No headers" }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Box(
            Modifier.fillMaxSize()
                .background(AndyColors.Neutral850, RoundedCornerShape(6.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(6.dp))
                .padding(10.dp)
                .horizontalScroll(horizontal)
                .verticalScroll(vertical),
        ) {
            SelectionContainer {
                Column {
                    Text("Headers", color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                    Text(headerText, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("Body", color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                    if (jsonBody != null) {
                        JsonTreeView(
                            node = jsonBody,
                            expandedKeys = expandedJsonKeys,
                        )
                    } else {
                        Text(
                            bodyValue ?: "No body preview",
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JsonTreeView(
    node: JsonPreviewNode,
    expandedKeys: MutableMap<String, Boolean>,
) {
    val rows = remember(node, expandedKeys.toMap()) { flattenJsonPreview(node, expandedKeys) }
    Column {
        rows.forEach { row ->
            JsonTreeRow(
                row = row,
                expanded = expandedKeys[row.node.path] == true,
                onToggle = {
                    if (row.node.isContainer) {
                        expandedKeys[row.node.path] = expandedKeys[row.node.path] != true
                    }
                },
            )
        }
    }
}

@Composable
private fun JsonTreeRow(row: JsonPreviewRow, expanded: Boolean, onToggle: () -> Unit) {
    val node = row.node
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.clickable(enabled = node.isContainer) { onToggle() },
    ) {
        Text(
            text = when {
                node.isContainer && expanded -> "v"
                node.isContainer -> ">"
                else -> " "
            },
            color = TextSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.width(14.dp),
        )
        Text(
            text = row.text,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(start = (row.depth * 14).dp),
        )
    }
}

@Composable
private fun DeviceLivePanel(services: AndyServices, serial: String?, device: AndroidDevice?, modifier: Modifier = Modifier, showChromeControls: Boolean = true) {
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

private fun parseHeaderLines(value: String): Map<String, String> {
    return value.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && ":" in it }
        .associate { line -> line.substringBefore(':').trim() to line.substringAfter(':').trim() }
}

private fun <T> List<T>.swapItems(first: Int, second: Int): List<T> {
    return toMutableList().also { items ->
        val temp = items[first]
        items[first] = items[second]
        items[second] = temp
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

class AccessibilityState {
    var root by mutableStateOf<AccessibilityNode?>(null)
    var status by mutableStateOf("No dump loaded")
    var hoveredBounds by mutableStateOf<String?>(null)
    var selectedNode by mutableStateOf<AccessibilityNode?>(null)
    var interactionMode by mutableStateOf(false)
    var isInitialDumpDone by mutableStateOf(false)
    var isLoading by mutableStateOf(false)
    var lastSerial by mutableStateOf<String?>(null)
    var layoutBounds by mutableStateOf(false)
    var interestingOnly by mutableStateOf(false)
    val collapsedNodes = mutableStateMapOf<String, Boolean>()
}

@Composable
private fun AccessibilityScreen(
    services: AndyServices,
    serial: String?,
    device: AndroidDevice?,
    treePaneWidth: Float,
    onTreePaneWidthChange: (Float) -> Unit,
    state: AccessibilityState = remember { AccessibilityState() }
) {
    val scope = rememberCoroutineScope()
    var localTreePaneWidth by remember(treePaneWidth) { mutableStateOf(treePaneWidth.coerceIn(420f, 1400f)) }
    var mirrorStatus by remember { mutableStateOf("Disconnected") }
    var connectResult by remember { mutableStateOf("") }
    val sendMirrorInput = rememberMirrorInputSender(services, serial, enabled = !state.interactionMode)
    val flattenedNodes = remember(state.root, state.collapsedNodes.toMap(), state.interestingOnly) {
        val rows = state.root?.flattenAccessibilityTree(state.collapsedNodes).orEmpty()
        if (state.interestingOnly) rows.filter { it.node.isInterestingAccessibilityNode() } else rows
    }
    val treeListState = rememberLazyListState()

    LaunchedEffect(serial) {
        if (serial != state.lastSerial) {
            state.root = null
            state.status = "No dump loaded"
            state.hoveredBounds = null
            state.selectedNode = null
            state.interactionMode = false
            state.isInitialDumpDone = false
            state.isLoading = false
            state.lastSerial = serial
            state.layoutBounds = false
            state.interestingOnly = false
            state.collapsedNodes.clear()
        }
        if (serial != null) {
            val result = services.devices.shell(serial, listOf("getprop", "debug.layout"))
            if (result.isSuccess) {
                state.layoutBounds = result.stdout.trim() == "true"
            }
        }
    }

    fun dump() {
        if (serial == null) return
        scope.launch {
            state.isLoading = true
            state.status = "Dumping tree..."
            val result = services.accessibility.dump(serial)
            state.root = result
            state.selectedNode = result
            state.status = if (result == null) "No hierarchy returned" else "Hierarchy loaded · ${result.countNodes()} nodes"
            state.isLoading = false
            state.isInitialDumpDone = true
        }
    }

    LaunchedEffect(serial, state.isInitialDumpDone) {
        if (serial != null && !state.isInitialDumpDone && !state.isLoading) {
            dump()
        }
    }

    LaunchedEffect(state.selectedNode?.id, flattenedNodes.size) {
        val selectedId = state.selectedNode?.id ?: return@LaunchedEffect
        val index = flattenedNodes.indexOfFirst { it.node.id == selectedId }
        if (index >= 0) treeListState.animateScrollToItem(index)
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

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Toolbar("Accessibility", state.status, onPrimary = { dump() }, primaryLabel = "Dump tree")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill("Inspect clicks", state.interactionMode, Rust) { state.interactionMode = !state.interactionMode }
            FilterPill("Interesting", state.interestingOnly, Green) { state.interestingOnly = !state.interestingOnly }
            FilterPill("Layout bounds", state.layoutBounds, Yellow) {
                val next = !state.layoutBounds
                state.layoutBounds = next
                if (serial != null) {
                    scope.launch {
                        services.devices.shell(serial, listOf("setprop", "debug.layout", next.toString()))
                        services.devices.shell(serial, listOf("service", "call", "activity", "1599295570"))
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.width(localTreePaneWidth.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.fillMaxWidth().weight(1f).background(PanelSoft, RoundedCornerShape(8.dp)).padding(10.dp)
                ) {
                    if (state.isLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Rust)
                        }
                    } else if (flattenedNodes.isNotEmpty()) {
                        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                            LazyColumn(state = treeListState, modifier = Modifier.widthIn(min = 980.dp).fillMaxHeight()) {
                                itemsIndexed(flattenedNodes, key = { _, row -> row.node.id }) { _, row ->
                                    AccessibilityNodeRow(
                                        row = row,
                                        hoveredBounds = state.hoveredBounds,
                                        selectedId = state.selectedNode?.id,
                                        isCollapsed = state.collapsedNodes[row.node.id] == true,
                                        onHover = { state.hoveredBounds = it },
                                        onSelect = {
                                            state.selectedNode = it
                                            state.hoveredBounds = it.bounds
                                        },
                                        onToggleCollapse = {
                                            val collapsed = state.collapsedNodes[row.node.id] == true
                                            state.collapsedNodes[row.node.id] = !collapsed
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        Text("Dump a tree to inspect nodes.", color = TextSecondary)
                    }
                }
                PanelCard(Modifier.fillMaxWidth().height(300.dp)) {
                    AccessibilityDetails(state.selectedNode)
                }
            }
            Spacer(Modifier.width(6.dp))
            PaneDivider(
                onDrag = { dragX -> localTreePaneWidth = (localTreePaneWidth + dragX).coerceIn(360f, 1600f) },
                onDragEnd = { onTreePaneWidthChange(localTreePaneWidth) },
            )
            MirrorFrameContent(services.mirror, serial) { frameFlow, frame ->
                LiveDevicePane(
                    serial = serial,
                    device = device,
                    frame = frame,
                    frameFlow = frameFlow,
                    mirrorStatus = mirrorStatus,
                    connectResult = connectResult,
                    modifier = Modifier.fillMaxSize().padding(start = 6.dp),
                    highlightBounds = state.hoveredBounds,
                    showHardwareControls = false,
                    passThroughInput = !state.interactionMode,
                    onDevicePointClick = { x, y ->
                        state.root?.findBestNodeAt(x, y)?.let {
                            state.selectedNode = it
                            state.hoveredBounds = it.bounds
                        }
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
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun AccessibilityNodeRow(
    row: AccessibilityTreeRow,
    hoveredBounds: String?,
    selectedId: String?,
    isCollapsed: Boolean,
    onHover: (String?) -> Unit,
    onSelect: (AccessibilityNode) -> Unit,
    onToggleCollapse: () -> Unit,
) {
    val node = row.node
    val active = node.bounds == hoveredBounds || node.id == selectedId
    val hasChildren = node.children.isNotEmpty()
    Row(
        Modifier.widthIn(min = 900.dp)
            .background(if (active) Rust.copy(alpha = 0.22f) else Color.Transparent, RoundedCornerShape(4.dp))
            .pointerMoveFilter(onEnter = { onHover(node.bounds); false }, onExit = { onHover(null); false })
            .clickable { onSelect(node) }
            .padding(start = (row.depth * 12).dp, top = 2.dp, bottom = 2.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clickable(
                    enabled = hasChildren,
                    onClick = onToggleCollapse
                ),
            contentAlignment = Alignment.Center
        ) {
            if (hasChildren) {
                Text(
                    text = if (isCollapsed) ">" else "v",
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text("${node.className?.substringAfterLast('.') ?: "node"}  ${node.bounds ?: ""}", color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val label = listOfNotNull(node.resourceId, node.text, node.contentDescription).joinToString(" · ")
            if (label.isNotBlank()) Text(label, color = TextSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private data class AccessibilityTreeRow(val node: AccessibilityNode, val depth: Int)

private fun AccessibilityNode.flattenAccessibilityTree(
    collapsedNodes: Map<String, Boolean>,
    depth: Int = 0
): List<AccessibilityTreeRow> {
    val row = AccessibilityTreeRow(this, depth)
    val isCollapsed = collapsedNodes[this.id] == true
    return if (isCollapsed) {
        listOf(row)
    } else {
        listOf(row) + children.flatMap { it.flattenAccessibilityTree(collapsedNodes, depth + 1) }
    }
}

private fun AccessibilityNode.countNodes(): Int = 1 + children.sumOf { it.countNodes() }

private fun AccessibilityNode.isInterestingAccessibilityNode(): Boolean {
    if (!visible || !enabled) return false
    if (packageName.isNullOrBlank() || packageName.startsWith("com.android.systemui")) return false
    val hasIdentity = !text.isNullOrBlank() || !contentDescription.isNullOrBlank() || !resourceId.isNullOrBlank()
    return hasIdentity || clickable || scrollable
}

@Composable
private fun AccessibilityDetails(node: AccessibilityNode?) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("Selected", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text(node?.className ?: "No node", color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(node?.id?.let { "node[$it]" } ?: "-", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        if (node == null) return@Column
        val issues = buildList {
            if (node.clickable && node.text.isNullOrBlank() && node.contentDescription.isNullOrBlank()) add("No accessibility label")
            if (!node.visible) add("Not visible to user")
            if (!node.enabled) add("Disabled")
        }
        if (issues.isNotEmpty()) {
            Text("${issues.size} issues", color = Rust, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            issues.forEach { issue ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.background(Red, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("NAF", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(issue, color = TextPrimary, fontSize = 12.sp)
                }
            }
        }
        DetailSection("Identity")
        DetailRow("resource-id", node.resourceId)
        DetailRow("class", node.className?.substringAfterLast('.'))
        DetailRow("class-full", node.className)
        DetailRow("package", node.packageName)
        DetailRow("node-id", node.id)
        DetailRow("children", node.children.size.toString())
        DetailSection("Content")
        DetailRow("text", node.text)
        DetailRow("content-desc", node.contentDescription)
        DetailRow("hint", node.hint)
        DetailSection("Geometry")
        DetailRow("bounds", node.bounds)
        DetailRow("size", parseBounds(node.bounds)?.let { "${it[2] - it[0]}x${it[3] - it[1]}" })
        DetailSection("State")
        DetailRow("clickable", node.clickable.toString())
        DetailRow("long-clickable", node.longClickable.toString())
        DetailRow("focusable", node.focusable.toString())
        DetailRow("focused", node.focused.toString())
        DetailRow("enabled", node.enabled.toString())
        DetailRow("selected", node.selected.toString())
        DetailRow("checkable", node.checkable.toString())
        DetailRow("checked", node.checked.toString())
        DetailRow("scrollable", node.scrollable.toString())
        DetailRow("password", node.password.toString())
        DetailRow("visible", node.visible.toString())
        DetailSection("Computed")
        DetailRow("contrast", "-")
        DetailRow("label", node.contentDescription ?: node.text ?: node.hint)
        if (node.attributes.isNotEmpty()) {
            DetailSection("Raw Dump")
            node.attributes.toSortedMap().forEach { (key, value) ->
                DetailRow(key, value)
            }
        }
    }
}

@Composable
private fun BugsScreen(bugs: BugService) {
    val scope = rememberCoroutineScope()
    var reports by remember { mutableStateOf<List<BugReport>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<BugReport?>(null) }
    var logcat by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf("Details") }
    var playbackFrame by remember { mutableStateOf<MirrorFrame?>(null) }
    var playbackRunId by remember { mutableStateOf(0) }
    var isReplaying by remember { mutableStateOf(false) }
    var playbackFrameCount by remember { mutableStateOf(0) }
    var playbackFrameIndex by remember { mutableStateOf(0) }
    var playbackStartFrameIndex by remember { mutableStateOf(0) }
    var isInspectingPlayback by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var stepsPaneWidth by remember { mutableStateOf(380f) }
    var bugDetailsPaneWidth by remember { mutableStateOf(320f) }
    val expandedStepIds = remember { mutableStateMapOf<String, Boolean>() }
    val stepsListState = rememberLazyListState()

    fun refreshReports() {
        scope.launch {
            reports = bugs.listBugs()
            if (selectedId == null || reports.none { it.id == selectedId }) {
                selectedId = reports.firstOrNull()?.id
            }
        }
    }

    LaunchedEffect(Unit) { refreshReports() }
    LaunchedEffect(selectedId, reports) {
        val id = selectedId
        selected = reports.firstOrNull { it.id == id } ?: id?.let { bugs.loadBug(it) }
        logcat = id?.let { bugs.loadBugLog(it) }.orEmpty()
        playbackFrame = null
        playbackFrameIndex = 0
        playbackStartFrameIndex = 0
        isInspectingPlayback = false
        playbackFrameCount = id?.let { bugs.bugVideoFrameCount(it) } ?: 0
        isReplaying = false
        expandedStepIds.clear()
    }
    LaunchedEffect(selectedId, playbackFrameCount, playbackFrameIndex, isReplaying) {
        val id = selectedId ?: return@LaunchedEffect
        if (isReplaying || playbackFrameCount <= 0) return@LaunchedEffect
        bugs.loadBugVideoFrame(id, playbackFrameIndex)?.let { frame ->
            playbackFrame = frame
        }
    }
    LaunchedEffect(selectedId, playbackRunId, isReplaying) {
        val id = selectedId ?: return@LaunchedEffect
        if (!isReplaying || playbackRunId == 0) return@LaunchedEffect
        val runId = playbackRunId
        playbackFrame = null
        try {
            bugs.playbackFrames(id, playbackStartFrameIndex).collect { frame ->
                playbackFrame = frame
                playbackFrameIndex = frame.frameNumber.toInt().coerceAtLeast(1) - 1
                isInspectingPlayback = true
            }
        } finally {
            if (playbackRunId == runId) {
                isReplaying = false
            }
        }
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelCard(Modifier.width(270.dp).fillMaxHeight()) {
            Toolbar("Bugs", "${reports.size} reports", onPrimary = { refreshReports() }, primaryLabel = "Refresh")
            if (reports.isEmpty()) {
                EmptyState("No bug reports yet")
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(reports) { report ->
                        val active = report.id == selectedId
                        Column(
                            Modifier.fillMaxWidth()
                                .background(if (active) PanelSoft else Panel, RoundedCornerShape(AndyRadius.R3))
                                .border(1.dp, if (active) Rust.copy(alpha = 0.45f) else Border, RoundedCornerShape(AndyRadius.R3))
                                .clickable { selectedId = report.id }
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(report.title, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${report.actions.size} actions · ${formatMillis(report.capturedAtMillis)}", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
                            Text(report.deviceSerial, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        val report = selected
        if (report == null) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text("Save a bug from Live to see its replay here.", color = TextSecondary)
            }
        } else {
            val playbackMillis = bugPlaybackMillis(report, playbackFrameIndex, playbackFrameCount)
            val showReplayAnnotations = isInspectingPlayback && playbackFrame != null
            val activeActionIndex = if (showReplayAnnotations) activeBugActionIndex(report.actions, playbackMillis) else -1
            val pointerEvent = if (showReplayAnnotations) activeBugPointerEvent(report.actions, playbackMillis) else null
            fun toggleBugReplay() {
                if (isReplaying) {
                    isReplaying = false
                } else {
                    isInspectingPlayback = true
                    playbackStartFrameIndex = playbackFrameIndex
                    isReplaying = true
                    playbackRunId++
                }
            }
            LaunchedEffect(report.id, activeActionIndex) {
                if (activeActionIndex >= 0) {
                    val targetIndex = activeActionIndex + 1
                    val isVisible = stepsListState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
                    if (!isVisible) {
                        stepsListState.scrollToItem(targetIndex)
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(report.title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(report.deviceModel, "API ${report.apiLevel ?: "-"}", report.abi, report.resolution, formatMillis(report.capturedAtMillis))
                                .filterNotNull()
                                .joinToString(" · "),
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(
                        onClick = { toggleBugReplay() },
                        colors = primaryButtonColors(),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text(if (isReplaying) "Pause" else "Reproduce") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        scope.launch {
                            status = bugs.exportBug(report.id)?.let { "Exported to $it" } ?: "Export failed"
                        }
                    }) { Text("Export") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                bugs.deleteBug(report.id)
                                status = "Deleted ${report.title}"
                                selectedId = null
                                refreshReports()
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Red),
                    ) { Text("Delete") }
                }
                if (status.isNotBlank()) Text(status, color = Rust, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PanelCard(Modifier.width(stepsPaneWidth.dp).fillMaxHeight()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("STEPS", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Text("${report.actions.size} events", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                        LazyColumn(Modifier.fillMaxSize(), state = stepsListState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            item {
                                Text("captured here", color = Rust, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                            }
                            itemsIndexed(report.actions) { index, action ->
                                val active = index == activeActionIndex
                                val expanded = expandedStepIds[action.id] == true
                                Column(
                                    Modifier.fillMaxWidth()
                                        .animateContentSize()
                                        .background(if (active) Rust.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(AndyRadius.R2))
                                        .border(1.dp, if (active) Rust.copy(alpha = 0.55f) else Color.Transparent, RoundedCornerShape(AndyRadius.R2))
                                        .clickable { expandedStepIds[action.id] = !expanded }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(if (expanded) "v" else ">", color = if (active) Rust else TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(10.dp))
                                        Text("${index + 1}", color = if (active) Rust else TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(22.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(action.label, color = if (active) AndyColors.Neutral100 else TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            action.detail?.let { Text(it, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                        }
                                        Text(relativeSeconds(action.timestampMillis, report.windowEndedAtMillis), color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                    }
                                    AnimatedVisibility(visible = expanded) {
                                        Column(
                                            Modifier.fillMaxWidth()
                                                .background(Color.Black.copy(alpha = 0.28f), RoundedCornerShape(AndyRadius.R2))
                                                .padding(horizontal = 8.dp, vertical = 7.dp),
                                            verticalArrangement = Arrangement.spacedBy(5.dp),
                                        ) {
                                            BugStepExpandedRow("label", action.label)
                                            action.detail?.takeIf { it.isNotBlank() }?.let { BugStepExpandedRow("detail", it) }
                                            BugStepExpandedRow("kind", action.kind)
                                            BugStepExpandedRow("time", "${formatMillis(action.timestampMillis)}  ${relativeSeconds(action.timestampMillis, report.windowEndedAtMillis)}")
                                            BugStepExpandedRow("id", action.id)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    PaneDivider(
                        onDrag = { dragX -> stepsPaneWidth = (stepsPaneWidth + dragX).coerceIn(260f, 1_400f) },
                    )
                    PanelCard(Modifier.weight(1f).widthIn(min = 96.dp).fillMaxHeight()) {
                        Text("VIDEO", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                Modifier.weight(1f).fillMaxWidth()
                                    .background(Color.Black, RoundedCornerShape(AndyRadius.R3))
                                    .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
                                    .clickable(enabled = playbackFrameCount > 0) { toggleBugReplay() },
                                contentAlignment = Alignment.Center,
                            ) {
                                val frame = playbackFrame
                                if (frame != null) {
                                    Box(Modifier.fillMaxSize()) {
                                        MirrorVideoSurface(
                                            frame = frame,
                                            modifier = Modifier.fillMaxSize(),
                                            onInput = {},
                                            passThroughInput = false,
                                            onDevicePointClick = { _, _ -> toggleBugReplay() },
                                        )
                                        pointerEvent?.let { event ->
                                            BugPointerOverlay(frame, event)
                                        }
                                    }
                                } else {
                                    Text("Press Reproduce to play capture.mp4", color = TextSecondary, fontSize = 12.sp)
                                }
                            }
                            if (playbackFrameCount > 0) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    val sliderMax = (playbackFrameCount - 1).coerceAtLeast(1).toFloat()
                                    Slider(
                                        value = playbackFrameIndex.toFloat().coerceIn(0f, sliderMax),
                                        onValueChange = { value ->
                                            val index = value.toInt().coerceIn(0, playbackFrameCount - 1)
                                            isReplaying = false
                                            isInspectingPlayback = true
                                            playbackFrameIndex = index
                                        },
                                        valueRange = 0f..sliderMax,
                                        enabled = playbackFrameCount > 1,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "${playbackFrameIndex + 1}/$playbackFrameCount",
                                        color = TextSecondary,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        modifier = Modifier.width(84.dp),
                                    )
                                }
                            } else {
                                Text("No video frames captured", color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                    PaneDivider(
                        onDrag = { dragX -> bugDetailsPaneWidth = (bugDetailsPaneWidth - dragX).coerceIn(220f, 900f) },
                    )
                    PanelCard(Modifier.width(bugDetailsPaneWidth.dp).fillMaxHeight()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterPill("Details", selectedTab == "Details", Rust) { selectedTab = "Details" }
                            FilterPill("Logcat", selectedTab == "Logcat", Rust) { selectedTab = "Logcat" }
                        }
                        if (selectedTab == "Details") {
                            DetailSection("DEVICE")
                            DetailRow("Model", report.deviceModel)
                            DetailRow("Serial", report.deviceSerial)
                            DetailRow("API Level", report.apiLevel)
                            DetailRow("ABI", report.abi)
                            DetailRow("Resolution", report.resolution)
                            DetailRow("Captured", formatMillis(report.capturedAtMillis))
                            DetailSection("ARTIFACT FILES")
                            report.artifacts.forEach { artifact ->
                                DetailRow(artifact.name, artifact.sizeBytes?.let(::formatBytes) ?: artifact.kind)
                            }
                            DetailSection("NOTES")
                            SelectionContainer {
                                Text(report.notes.ifBlank { "<none>" }, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().background(Color.Black, RoundedCornerShape(AndyRadius.R3)).padding(10.dp))
                            }
                        } else {
                            BugLogcatView(logcat, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BugLogcatView(logcat: String, modifier: Modifier = Modifier) {
    BugLogcatTextSurface(logcat, modifier.background(Color.Black, RoundedCornerShape(AndyRadius.R3)))
}

@Composable
private fun BugStepExpandedRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            color = TextSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.width(46.dp),
            maxLines = 1,
        )
        Text(
            value,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun formatMillis(value: Long): String = if (value <= 0L) "-" else value.toString()

@Composable
private fun BugPointerOverlay(frame: MirrorFrame, event: BugPointerEvent) {
    Canvas(Modifier.fillMaxSize()) {
        val rect = fittedRect(size.width, size.height, frame.width, frame.height)
        val scaleX = rect.width / frame.width.coerceAtLeast(1)
        val scaleY = rect.height / frame.height.coerceAtLeast(1)
        val center = Offset(rect.left + event.x * scaleX, rect.top + event.y * scaleY)
        val alpha = (1f - event.progress).coerceIn(0f, 1f)
        val radius = 14.dp.toPx() + 20.dp.toPx() * event.progress
        drawCircle(
            color = Rust.copy(alpha = 0.20f * alpha),
            radius = radius,
            center = center,
        )
        drawCircle(
            color = Rust.copy(alpha = 0.95f * alpha),
            radius = radius,
            center = center,
            style = Stroke(width = 2.dp.toPx()),
        )
        drawCircle(
            color = AndyColors.Neutral100.copy(alpha = 0.90f * alpha),
            radius = 4.dp.toPx(),
            center = center,
        )
        drawLine(
            color = Rust.copy(alpha = 0.75f * alpha),
            start = Offset(center.x - 18.dp.toPx(), center.y),
            end = Offset(center.x + 18.dp.toPx(), center.y),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = Rust.copy(alpha = 0.75f * alpha),
            start = Offset(center.x, center.y - 18.dp.toPx()),
            end = Offset(center.x, center.y + 18.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

private fun relativeSeconds(timestampMillis: Long, endMillis: Long): String {
    val seconds = ((timestampMillis - endMillis) / 1000.0)
    return "%.1fs".format(seconds)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    return "%.1f MB".format(kb / 1024.0)
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
private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.Verbose -> TextSecondary
    LogLevel.Debug -> Cyan
    LogLevel.Info -> Green
    LogLevel.Warn -> Yellow
    LogLevel.Error, LogLevel.Fatal -> Red
    LogLevel.Silent -> TextSecondary
}

private data class EditingProject(val project: ActionProject?)
private data class EditingAction(val projectId: String, val action: ProjectAction?)

@Composable
private fun ActionsScreen(
    services: AndyServices,
    config: ActionsConfig,
    running: List<RunningAction>,
    activeRunId: String?,
    onActiveRunIdChange: (String?) -> Unit,
    onConfigChange: (ActionsConfig) -> Unit,
) {
    var editingProject by remember { mutableStateOf<EditingProject?>(null) }
    var editingAction by remember { mutableStateOf<EditingAction?>(null) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    var catalogPaneWidth by remember { mutableStateOf(560f) }

    val runningIds = remember(running) { running.map { it.runId } }
    LaunchedEffect(runningIds) {
        if (activeRunId == null || running.none { it.runId == activeRunId }) {
            onActiveRunIdChange(running.lastOrNull()?.runId)
        }
    }

    fun upsertProject(project: ActionProject) {
        val exists = config.projects.any { it.id == project.id }
        onConfigChange(config.copy(projects = if (exists) config.projects.map { if (it.id == project.id) project.copy(actions = it.actions) else it } else config.projects + project))
    }

    fun upsertAction(projectId: String, previousProjectId: String?, action: ProjectAction) {
        onConfigChange(
            config.copy(
                projects = config.projects.map { project ->
                    when {
                        project.id == projectId -> project.copy(actions = project.actions.filterNot { it.id == action.id } + action)
                        previousProjectId != null && project.id == previousProjectId -> project.copy(actions = project.actions.filterNot { it.id == action.id })
                        else -> project
                    }
                },
            ),
        )
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(catalogPaneWidth.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Toolbar("Actions", "${config.projects.size} projects / ${config.projects.sumOf { it.actions.size }} actions", onPrimary = { editingProject = EditingProject(null) }, primaryLabel = "new project")
            if (config.projects.isEmpty()) {
                EmptyState("no action projects yet")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                    items(config.projects, key = { it.id }) { project ->
                        PanelCard {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(project.name, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(project.contextDir, color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                OutlinedButton(onClick = { editingProject = EditingProject(project) }) { Text("edit") }
                                OutlinedButton(onClick = { editingAction = EditingAction(project.id, null) }) { Text("+ action") }
                                OutlinedButton(onClick = {
                                    pendingConfirmation = PendingConfirmation("Delete project?", "${project.name} and ${project.actions.size} actions") {
                                        onConfigChange(config.copy(projects = config.projects.filterNot { it.id == project.id }))
                                    }
                                }) { Text("del") }
                            }
                            if (project.actions.isEmpty()) {
                                Text("no actions", color = TextSecondary, fontFamily = MonoFont, fontSize = 12.sp)
                            } else {
                                project.actions.forEach { action ->
                                    val liveRun = running.firstOrNull { it.actionId == action.id && it.status == ActionRunStatus.Running }
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(
                                            Modifier.heightIn(min = 32.dp)
                                                .background(AndyColors.Neutral900.copy(alpha = 0.72f))
                                                .border(1.dp, Color.White.copy(alpha = 0.05f))
                                                .padding(horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(actionIconMarker(action.icon), color = Rust, fontFamily = MonoFont, fontSize = 12.sp, modifier = Modifier.width(28.dp))
                                            Text(action.name, color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Spacer(Modifier.weight(1f))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = { editingAction = EditingAction(project.id, action) }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("edit", fontSize = 11.sp) }
                                            OutlinedButton(onClick = {
                                                pendingConfirmation = PendingConfirmation("Delete action?", action.name) {
                                                    onConfigChange(config.copy(projects = config.projects.map { if (it.id == project.id) it.copy(actions = it.actions.filterNot { row -> row.id == action.id }) else it }))
                                                }
                                            }, modifier = Modifier.height(30.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("del", fontSize = 11.sp) }
                                            Button(
                                                onClick = {
                                                    if (liveRun != null) {
                                                        services.actionRuns.stop(liveRun.runId)
                                                        onActiveRunIdChange(liveRun.runId)
                                                    } else {
                                                        onActiveRunIdChange(services.actionRuns.run(project, action))
                                                    }
                                                },
                                                colors = if (liveRun != null) ButtonDefaults.buttonColors(containerColor = Rust, contentColor = AndyColors.Neutral100) else primaryButtonColors(),
                                                modifier = Modifier.height(30.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                            ) { Text(if (liveRun != null) "stop" else "run", fontSize = 11.sp) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        PaneDivider(onDrag = { dragX -> catalogPaneWidth = (catalogPaneWidth + dragX).coerceIn(420f, 900f) })
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val activeRun = running.firstOrNull { it.runId == activeRunId }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running.isEmpty()) {
                    Text("no running actions", color = TextSecondary, fontFamily = MonoFont, fontSize = 12.sp)
                } else {
                    running.forEach { run ->
                        FilterPill("${actionIconMarker(run.icon)} ${run.actionName}", run.runId == activeRunId, actionStatusColor(run.status)) { onActiveRunIdChange(run.runId) }
                    }
                }
            }
            PanelCard(Modifier.fillMaxSize()) {
                if (activeRun == null) {
                    EmptyState("run an action to see output")
                } else {
                    val lines by services.actionRuns.output(activeRun.runId).collectAsState()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(activeRun.actionName, color = TextPrimary, fontWeight = FontWeight.Bold)
                            Text("${activeRun.cwd}  $ ${activeRun.command}", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        StatusTag(activeRun.status.name, actionStatusColor(activeRun.status))
                        if (activeRun.status == ActionRunStatus.Running) {
                            Button(onClick = { services.actionRuns.stop(activeRun.runId) }, colors = ButtonDefaults.buttonColors(containerColor = Rust)) { Text("Stop") }
                        }
                        OutlinedButton(onClick = {
                            services.actionRuns.clear(activeRun.runId)
                            onActiveRunIdChange(running.firstOrNull { it.runId != activeRun.runId }?.runId)
                        }) { Text("Clear") }
                    }
                    ActionConsole(lines, Modifier.fillMaxSize())
                }
            }
        }
    }

    editingProject?.let { editing ->
        ProjectDialog(editing.project, config.projects, onDismiss = { editingProject = null }) {
            editingProject = null
            upsertProject(it)
        }
    }
    editingAction?.let { editing ->
        ActionDialog(config.projects, editing.projectId, editing.action, onDismiss = { editingAction = null }) { targetProjectId, action ->
            editingAction = null
            upsertAction(targetProjectId, editing.projectId, action)
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
private fun actionStatusColor(status: ActionRunStatus): Color = when (status) {
    ActionRunStatus.Running -> Green
    ActionRunStatus.Exited -> Cyan
    ActionRunStatus.Failed -> Red
    ActionRunStatus.Stopped -> Rust
}

@Composable
private fun ActionConsole(lines: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var stickToBottom by remember { mutableStateOf(true) }
    val isAtBottom by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            if (total == 0) true else (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= total - 1
        }
    }
    LaunchedEffect(lines.size, stickToBottom) {
        if (stickToBottom && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to isAtBottom }
            .distinctUntilChanged()
            .collect { (scrolling, atBottom) ->
                if (scrolling && !atBottom) stickToBottom = false
                if (atBottom) stickToBottom = true
            }
    }
    Box(modifier.background(AndyColors.Neutral900.copy(alpha = 0.72f), RoundedCornerShape(AndyRadius.R3)).border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))) {
        LazyColumn(Modifier.fillMaxSize().padding(10.dp).padding(end = 8.dp), state = listState) {
            itemsIndexed(lines) { _, line ->
                Text(line, color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        DraggableScrollbar(
            firstVisibleItemIndex = listState.firstVisibleItemIndex,
            visibleItems = listState.layoutInfo.visibleItemsInfo.size,
            totalItems = listState.layoutInfo.totalItemsCount,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            onDragToIndex = { index ->
                stickToBottom = index >= (listState.layoutInfo.totalItemsCount - listState.layoutInfo.visibleItemsInfo.size - 1)
                scope.launch { listState.scrollToItem(index.coerceAtLeast(0)) }
            },
        )
    }
}

@Composable
private fun ProjectDialog(project: ActionProject?, existingProjects: List<ActionProject>, onDismiss: () -> Unit, onSave: (ActionProject) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember(project?.id) { mutableStateOf(project?.name.orEmpty()) }
    var contextDir by remember(project?.id) { mutableStateOf(project?.contextDir.orEmpty()) }
    var envText by remember(project?.id) { mutableStateOf(project?.env?.toEnvText().orEmpty()) }
    val nextId = remember(existingProjects.size, name) { nextActionId("proj", name, existingProjects.map { it.id }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(if (project == null) "New project" else "Edit project", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.width(660.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LabeledField("Name", name, { name = it }, Modifier.fillMaxWidth())
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Context directory", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextField(contextDir, { contextDir = it }, readOnly = true, singleLine = true, modifier = Modifier.weight(1f).height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = MonoFont), colors = fieldColors())
                        Button(onClick = { scope.launch { pickDirectory(contextDir.ifBlank { null })?.let { contextDir = it } } }, colors = primaryButtonColors()) { Text("browse") }
                    }
                }
                LabeledField("Env (KEY=VALUE)", envText, { envText = it }, Modifier.fillMaxWidth(), singleLine = false, minHeight = 120.dp)
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(ActionProject(project?.id ?: nextId, name.trim(), contextDir.trim(), parseEnvLines(envText), project?.actions.orEmpty())) },
                enabled = name.isNotBlank() && contextDir.isNotBlank(),
                colors = primaryButtonColors(),
            ) { Text("Save") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ActionDialog(projects: List<ActionProject>, initialProjectId: String, action: ProjectAction?, onDismiss: () -> Unit, onSave: (String, ProjectAction) -> Unit) {
    val scope = rememberCoroutineScope()
    var selectedProjectId by remember(action?.id, initialProjectId) { mutableStateOf(initialProjectId) }
    var name by remember(action?.id) { mutableStateOf(action?.name.orEmpty()) }
    var icon by remember(action?.id) { mutableStateOf(action?.icon ?: "run") }
    var command by remember(action?.id) { mutableStateOf(action?.command.orEmpty()) }
    var cwd by remember(action?.id) { mutableStateOf(action?.cwd.orEmpty()) }
    var envText by remember(action?.id) { mutableStateOf(action?.env?.toEnvText().orEmpty()) }
    val actionIds = projects.flatMap { it.actions }.map { it.id }.toSet()
    val nextId = remember(actionIds, name) { nextActionId("act", name, actionIds) }
    val iconOptions = listOf("run", "test", "debug", "build", "server", "deploy")
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(if (action == null) "New action" else "Edit action", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.width(720.dp).heightIn(max = 640.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Project", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    projects.forEach { project -> FilterPill(project.name, project.id == selectedProjectId, Rust) { selectedProjectId = project.id } }
                }
                LabeledField("Name", name, { name = it }, Modifier.fillMaxWidth())
                Text("Icon", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    iconOptions.forEach { option -> FilterPill("${actionIconMarker(option)} $option", icon == option, Rust) { icon = option } }
                }
                LabeledField("Command", command, { command = it }, Modifier.fillMaxWidth(), singleLine = false, minHeight = 130.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Cwd override", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            cwd,
                            { cwd = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f).height(54.dp),
                            textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = MonoFont),
                            colors = fieldColors(),
                            placeholder = { Text("blank uses project context dir", color = TextSecondary, fontFamily = MonoFont) },
                        )
                        Button(
                            onClick = {
                                val initial = cwd.ifBlank { projects.firstOrNull { it.id == selectedProjectId }?.contextDir.orEmpty() }
                                scope.launch { pickDirectory(initial.ifBlank { null })?.let { cwd = it } }
                            },
                            colors = primaryButtonColors(),
                        ) { Text("browse") }
                    }
                }
                LabeledField("Env (KEY=VALUE)", envText, { envText = it }, Modifier.fillMaxWidth(), singleLine = false, minHeight = 110.dp)
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(selectedProjectId, ProjectAction(action?.id ?: nextId, name.trim(), icon, command.trim(), cwd.trim().takeIf { it.isNotBlank() }, parseEnvLines(envText))) },
                enabled = projects.any { it.id == selectedProjectId } && name.isNotBlank() && command.isNotBlank(),
                colors = primaryButtonColors(),
            ) { Text("Save") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun Map<String, String>.toEnvText(): String = entries.joinToString("\n") { "${it.key}=${it.value}" }

private fun parseEnvLines(value: String): Map<String, String> = value.lines()
    .mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#")) return@mapNotNull null
        val index = trimmed.indexOf('=')
        if (index <= 0) null else trimmed.take(index).trim() to trimmed.drop(index + 1).trim()
    }
    .toMap()

private fun nextActionId(prefix: String, label: String, existing: Set<String>): String {
    val base = label.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-').ifBlank { prefix }
    var id = "$prefix-$base"
    var index = 2
    while (id in existing) {
        id = "$prefix-$base-$index"
        index++
    }
    return id
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
