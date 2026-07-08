package app.andy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
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
import app.andy.model.*
import app.andy.service.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
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

private object AndyColors {
    val Neutral100 = Color(0xFFF4F1E8)
    val Neutral200 = Color(0xFFE4DED0)
    val Neutral300 = Color(0xFFC6BEAD)
    val Neutral400 = Color(0xFF8E8779)
    val Neutral500 = Color(0xFF514D44)
    val Neutral600 = Color(0xFF302D27)
    val Neutral700 = Color(0xFF24211C)
    val Neutral750 = Color(0xFF1D1A16)
    val Neutral800 = Color(0xFF171511)
    val Neutral850 = Color(0xFF11100D)
    val Neutral900 = Color(0xFF0A0908)

    val Orange = Color(0xFFD18A4B)
    val OrangeHover = Color(0xFFE0A56E)
    val OrangePressed = Color(0xFFB97138)
    val OrangeSubtle = Color(0xFF2C2117)
    val OrangeBorder = Color(0xFF8D6746)
    val Green = Color(0xFF94C17A)
    val GreenSoft = Color(0xFFB4D59E)
    val GreenSubtle = Color(0xFF172418)
    val Blue = Color(0xFF88AFC8)
    val Warning = Color(0xFFE3B05E)
    val Error = Color(0xFFE26F5C)
}

private object AndySpace {
    val S1 = 4.dp
    val S2 = 8.dp
    val S3 = 12.dp
    val S4 = 16.dp
    val S5 = 24.dp
}

private object AndyRadius {
    val R2 = 4.dp
    val R3 = 6.dp
    val R4 = 8.dp
    val R5 = 10.dp
    val Pill = 999.dp
}

private val MonoFont = FontFamily.Monospace
private val Ink = AndyColors.Neutral900
private val Panel = AndyColors.Neutral800
private val PanelSoft = AndyColors.Neutral700
private val Border = AndyColors.Neutral100.copy(alpha = 0.10f)
private val PaneDividerTint = AndyColors.OrangeBorder.copy(alpha = 0.72f)
private val TextPrimary = AndyColors.Neutral200
private val TextSecondary = AndyColors.Neutral400
private val Rust = AndyColors.Orange
private val Green = AndyColors.Green
private val Cyan = AndyColors.Blue
private val Yellow = AndyColors.Warning
private val Red = AndyColors.Error

private fun Modifier.rightBorder(color: Color): Modifier = drawBehind {
    val strokeWidth = 1.dp.toPx()
    val x = size.width - strokeWidth / 2f
    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth)
}

private fun Modifier.bottomBorder(color: Color): Modifier = drawBehind {
    val strokeWidth = 1.dp.toPx()
    val y = size.height - strokeWidth / 2f
    drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth)
}

private fun Modifier.noiseGridOverlay(alpha: Float = 0.07f): Modifier = drawBehind {
    val grid = 18.dp.toPx()
    var x = 0f
    while (x < size.width) {
        drawLine(AndyColors.Neutral100.copy(alpha = alpha), Offset(x, 0f), Offset(x, size.height), 1f)
        x += grid
    }
    var y = 0f
    while (y < size.height) {
        drawLine(AndyColors.Neutral100.copy(alpha = alpha * 0.6f), Offset(0f, y), Offset(size.width, y), 1f)
        y += grid
    }
}

@Composable
private fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(AndyRadius.R2),
    colors: ButtonColors = primaryButtonColors(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
private fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(AndyRadius.R2),
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary, disabledContentColor = AndyColors.Neutral500),
    border: BorderStroke? = BorderStroke(1.dp, AndyColors.Neutral100.copy(alpha = 0.16f)),
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        border = border,
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
private fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current.copy(fontFamily = MonoFont, color = TextPrimary),
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    placeholder: @Composable (() -> Unit)? = null,
    colors: TextFieldColors = fieldColors(),
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(AndyRadius.R2),
) {
    val enabledAlpha = if (enabled) 1f else 0.48f
    val effectiveTextStyle = textStyle.copy(fontFamily = MonoFont, color = if (textStyle.color == Color.Unspecified) TextPrimary else textStyle.color)
    val fieldShape = shape
    @Suppress("UNUSED_VARIABLE")
    val retainedColorsForCallSiteCompatibility = colors

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .background(AndyColors.Neutral900.copy(alpha = 0.62f * enabledAlpha), fieldShape)
            .border(1.dp, AndyColors.Neutral100.copy(alpha = 0.18f * enabledAlpha), fieldShape),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = effectiveTextStyle,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        cursorBrush = SolidColor(Rust),
        decorationBox = { innerTextField ->
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty() && placeholder != null) {
                    Box(Modifier.graphicsLayer(alpha = 0.62f)) {
                        placeholder()
                    }
                }
                innerTextField()
            }
        },
    )
}

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
    val channel = remember(services.mirror) { Channel<MirrorInput>(Channel.UNLIMITED) }
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
                val (label, detail) = mirrorInputBugText(input)
                services.bugs.recordAction("input", label, detail)
            }
            if (channel.trySend(input).isFailure) Unit
        }
    }
}

private fun mirrorInputBugText(input: MirrorInput): Pair<String, String?> = when (input) {
    is MirrorInput.Touch -> "${input.action.name} ${input.x},${input.y}" to null
    is MirrorInput.Tap -> "Tap ${input.x},${input.y}" to null
    is MirrorInput.Swipe -> "Swipe ${input.startX},${input.startY} -> ${input.endX},${input.endY}" to "${input.durationMillis}ms"
    is MirrorInput.Key -> "Key ${input.keyCode}" to androidKeyLabel(input.keyCode)
    is MirrorInput.Text -> "Text input" to input.value.take(80)
    MirrorInput.Back -> "Back" to null
    MirrorInput.Home -> "Home" to null
    MirrorInput.Recents -> "Recents" to null
    MirrorInput.Power -> "Power" to null
}

private fun androidKeyLabel(keyCode: Int): String? = when (keyCode) {
    24 -> "Volume up"
    25 -> "Volume down"
    else -> null
}

@Composable
private fun MirrorFrameContent(mirror: MirrorEngine, resetKey: Any?, content: @Composable (MirrorFrame?) -> Unit) {
    var frame by remember(mirror, resetKey) { mutableStateOf<MirrorFrame?>(null) }
    LaunchedEffect(mirror, resetKey) {
        mirror.frames.collectLatest { frame = it.takeIf { it.width > 1 && it.height > 1 } }
    }
    content(frame)
}

@Composable
fun AndyApp(
    services: AndyServices,
    requestedDestination: AndyDestination? = null,
    onDestinationConsumed: () -> Unit = {},
    onPopOutMirror: (String?) -> Unit = {},
    contentTopPadding: androidx.compose.ui.unit.Dp = 18.dp,
) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Ink,
            surface = Panel,
            surfaceVariant = PanelSoft,
            primary = Rust,
            secondary = Green,
            onBackground = TextPrimary,
            onSurface = TextPrimary,
            onSurfaceVariant = TextSecondary,
            outline = Border,
            error = Red,
        ),
        typography = Typography(
            displayLarge = LocalTextStyle.current.copy(fontFamily = MonoFont, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
            headlineLarge = LocalTextStyle.current.copy(fontFamily = MonoFont, fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = LocalTextStyle.current.copy(fontFamily = MonoFont, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            bodyMedium = LocalTextStyle.current.copy(fontFamily = MonoFont, fontSize = 13.sp, lineHeight = 19.sp),
            bodySmall = LocalTextStyle.current.copy(fontFamily = MonoFont, fontSize = 11.sp, lineHeight = 16.sp),
            labelMedium = LocalTextStyle.current.copy(fontFamily = MonoFont, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
            labelSmall = LocalTextStyle.current.copy(fontFamily = MonoFont, fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium),
        ),
    ) {
        AndyShell(services, requestedDestination, onDestinationConsumed, onPopOutMirror, contentTopPadding)
    }
}

@Composable
fun AndyMirrorPopOut(services: AndyServices, serial: String?) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Ink,
            surface = Panel,
            surfaceVariant = PanelSoft,
            primary = Rust,
            secondary = Green,
            onBackground = TextPrimary,
            onSurface = TextPrimary,
            onSurfaceVariant = TextSecondary,
            outline = Border,
            error = Red,
        ),
    ) {
        val scope = rememberCoroutineScope()
        var mirrorStatus by remember { mutableStateOf("Disconnected") }
        var connectResult by remember { mutableStateOf("") }
        val sendInput = rememberMirrorInputSender(services, serial)
        LaunchedEffect(Unit) {
            services.mirror.status.collectLatest { mirrorStatus = it }
        }
        LaunchedEffect(serial) {
            if (serial != null) {
                val result = services.mirror.connect(serial)
                connectResult = if (result.isSuccess) result.stdout else result.stderr
            }
        }
        Box(Modifier.fillMaxSize().background(Ink).noiseGridOverlay(0.04f).padding(12.dp)) {
            MirrorFrameContent(services.mirror, serial) { frame ->
                LiveDevicePane(
                    serial = serial,
                    device = null,
                    frame = frame,
                    mirrorStatus = mirrorStatus,
                    connectResult = connectResult,
                    modifier = Modifier.fillMaxSize(),
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
    onPopOutMirror: (String?) -> Unit,
    contentTopPadding: androidx.compose.ui.unit.Dp,
) {
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf(AndyDestination.Devices) }
    var devices by remember { mutableStateOf<List<AndroidDevice>>(emptyList()) }
    var sdk by remember { mutableStateOf(SdkDiscovery(null, null, null, null, null, listOf("SDK not scanned yet"))) }
    var selectedSerial by remember { mutableStateOf<String?>(null) }
    var workspaceState by remember { mutableStateOf(WorkspaceState()) }
    var networkRulesVisible by remember { mutableStateOf(false) }
    var networkLiveVisible by remember { mutableStateOf(false) }
    var stoppingEmulatorSerial by remember { mutableStateOf<String?>(null) }
    var emulatorStopStatus by remember { mutableStateOf("") }
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

    fun openStartedEmulator(previousSerials: Set<String>) {
        scope.launch {
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
                    return@launch
                }
                delay(1_000)
            }
        }
    }

    LaunchedEffect(Unit) {
        val saved = services.workspaceStore.load()
        workspaceState = saved
        selectedSerial = saved.selectedDeviceSerial
        actionsConfig = services.actionConfig.load()
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
                sdk = sdk,
                updates = services.updates,
                mcpRunning = mcpRunning,
                mcpPort = workspaceState.mcpServerPort
            )
            Column(Modifier.fillMaxSize().padding(start = 12.dp)) {
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
                    onOpenActions = { destination = AndyDestination.Actions },
                    actions = {
                        if (destination == AndyDestination.Network) {
                            FilterPill("Rules", networkRulesVisible, Rust) { networkRulesVisible = !networkRulesVisible }
                            Spacer(Modifier.width(8.dp))
                            FilterPill("Live", networkLiveVisible, Cyan) { networkLiveVisible = !networkLiveVisible }
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
                        AndyDestination.Devices -> DevicesScreen(services, devices, sdk, onRefresh = { refreshDevices() }, onLive = {
                            selectedSerial = it
                            destination = AndyDestination.Live
                        }, onEmulatorStarted = { previousSerials ->
                            openStartedEmulator(previousSerials)
                        }, onStopEmulator = { stopEmulator(it) }, stoppingEmulatorSerial = stoppingEmulatorSerial, stopStatus = emulatorStopStatus)
                        AndyDestination.Catalog -> CatalogScreen(services.avd)
                        AndyDestination.Live -> LiveScreen(
                            services,
                            selectedSerial,
                            devices.firstOrNull { it.serial == selectedSerial },
                            workspaceState.liveDevicePaneWidth,
                            workspaceState.liveControlsPaneHeight,
                            onStopEmulator = { stopEmulator(it) },
                            stoppingEmulatorSerial = stoppingEmulatorSerial,
                            stopStatus = emulatorStopStatus,
                            onDevicePaneWidthChange = { width -> updateWorkspace { it.copy(liveDevicePaneWidth = width) } },
                            onControlsPaneHeightChange = { height -> updateWorkspace { it.copy(liveControlsPaneHeight = height) } },
                            onBugSaved = { destination = AndyDestination.Bugs },
                            logcatState = liveLogcatState,
                            onPopOutMirror = { onPopOutMirror(selectedSerial) },
                        )
                        AndyDestination.Apps -> AppsScreen(
                            services,
                            selectedSerial,
                            workspaceState.appsListPaneWidth,
                            workspaceState.appsDetailsPaneHeight,
                            onPaneChange = { listWidth, detailsHeight -> updateWorkspace { it.copy(appsListPaneWidth = listWidth, appsDetailsPaneHeight = detailsHeight) } },
                        )
                        AndyDestination.Logcat -> LogcatScreen(services.logcat, selectedSerial, logcatState)
                        AndyDestination.Intents -> IntentsScreen(services, selectedSerial)
                        AndyDestination.Files -> FilesScreen(services.files, selectedSerial)
                        AndyDestination.Network -> NetworkScreen(
                            services = services,
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
                            services.metrics,
                            selectedSerial,
                            workspaceState.performanceProcessesPaneWidth,
                            onProcessesPaneWidthChange = { width -> updateWorkspace { it.copy(performanceProcessesPaneWidth = width) } },
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
    sdk: SdkDiscovery,
    updates: AppUpdateService,
    mcpRunning: Boolean,
    mcpPort: Int
) {
    val updateState by updates.state.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        Modifier.width(246.dp).fillMaxHeight()
            .background(Brush.verticalGradient(listOf(AndyColors.Neutral750, AndyColors.Neutral850)), RoundedCornerShape(AndyRadius.R4))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R4))
            .padding(AndySpace.S3),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(AndySpace.S1, AndySpace.S2, AndySpace.S1, AndySpace.S4),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AndySpace.S2),
            ) {
                AndyRobotIcon(Modifier.size(28.dp))
                Column {
                    Text("andy", color = AndyColors.Neutral100, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("workspace", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.Medium, fontSize = 10.sp)
                }
            }
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
                ) {
                    Text(navMark(item), color = if (active) Rust else TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(item.label.lowercase(), color = if (active) AndyColors.Neutral100 else AndyColors.Neutral300, fontFamily = MonoFont, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (item == AndyDestination.Devices) Text("$deviceCount", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
                    if (item == AndyDestination.Logcat) Text("live", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                }
            }
        }
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
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label.lowercase(), color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
        Text(value.lowercase(), color = if (ok) Green else Rust, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
    }
}

@Composable
private fun StatusTag(label: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.heightIn(min = 22.dp)
                .background(color.copy(alpha = 0.10f), RoundedCornerShape(AndyRadius.Pill))
                .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(AndyRadius.Pill))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(6.dp).background(color, RoundedCornerShape(AndyRadius.Pill)))
            Text(label, color = color, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
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
    onOpenActions: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
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
        ActionRunnerSelector(
            config = actionConfig,
            running = runningActions,
            onRunAction = onRunAction,
            onStopAction = onStopAction,
            onOpenActions = onOpenActions,
        )
        Spacer(Modifier.width(10.dp))
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
private fun ActionRunnerSelector(
    config: ActionsConfig,
    running: List<RunningAction>,
    onRunAction: (ActionProject, ProjectAction) -> Unit,
    onStopAction: (RunningAction) -> Unit,
    onOpenActions: () -> Unit,
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
        if (config.projects.isEmpty()) {
            OutlinedButton(
                onClick = onOpenActions,
                shape = RoundedCornerShape(AndyRadius.R2),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("actions setup", color = TextPrimary, fontSize = 12.sp)
            }
            return@Row
        }

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
    onEmulatorStarted: (Set<String>) -> Unit,
    onStopEmulator: (AndroidDevice) -> Unit,
    stoppingEmulatorSerial: String?,
    stopStatus: String,
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
                                        if (result.isSuccess) onEmulatorStarted(before)
                                    }
                                },
                                enabled = startingAvd == null,
                            ) {
                                Text(
                                    when {
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
                                enabled = startingAvd == null,
                                onColdBoot = {
                                    scope.launch {
                                        startingAvd = avd.name
                                        val result = services.avd.coldBootVirtualDevice(avd.name)
                                        avdStatus = if (result.isSuccess) result.stdout else result.stderr.ifBlank { result.stdout }
                                        startingAvd = null
                                        refreshAvds()
                                        if (result.isSuccess) onEmulatorStarted(devices.map { it.serial }.toSet())
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
    var query by remember { mutableStateOf("api:36 variant:google") }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }

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
    val filtered = images.filter { image ->
        query.split(" ").filter { it.isNotBlank() }.all { term ->
            image.packageId.contains(term.removePrefix("api:"), ignoreCase = true) ||
                image.variant.contains(term.removePrefix("variant:"), ignoreCase = true) ||
                image.api.contains(term.removePrefix("api:"), ignoreCase = true)
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Toolbar("System images", "${images.count { it.installed }} installed · ${avds.size} AVDs · ${profiles.size} profiles", onPrimary = { refresh() }, primaryLabel = if (loading) "Loading" else "Refresh catalog")
        if (status.isNotBlank()) Text(status, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        TextField(value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth().height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
        TableHeader(listOf("API" to 90.dp, "Variant" to 300.dp, "ABI" to 150.dp, "State" to 120.dp, "Action" to 100.dp, "Package" to 1.dp))
        LazyColumn {
            items(filtered.take(240)) { image ->
                TableRow {
                    MonoCell(image.api, 90.dp, TextPrimary)
                    MonoCell(image.variant, 300.dp, TextPrimary)
                    MonoCell(image.abi, 150.dp, TextSecondary)
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
        pendingConfirmation?.let { confirmation ->
            ConfirmationDialog(confirmation, onDismiss = { pendingConfirmation = null }, onConfirm = {
                pendingConfirmation = null
                confirmation.onConfirm()
            })
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
    var snapshotName by remember { mutableStateOf("manual") }
    var status by remember { mutableStateOf("Select an AVD") }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    var savingSnapshot by remember { mutableStateOf(false) }

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
        Toolbar("Snapshots", status, onPrimary = { refresh() }, primaryLabel = "Refresh")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            avds.forEach { row ->
                FilterPill(row.name, selectedAvd?.name == row.name, if (row.running) Green else Rust) {
                    selectedAvd = row
                    refreshSnapshots(row)
                }
            }
        }
        PanelCard {
            val avdRow = selectedAvd
            Text(avdRow?.name ?: "No AVD", color = TextPrimary, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(snapshotName, { snapshotName = it.filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' } }, singleLine = true, modifier = Modifier.width(180.dp).height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
                Button(
                    onClick = {
                        val target = avdRow ?: return@Button
                        scope.launch {
                            savingSnapshot = true
                            val name = snapshotName
                            status = "Saving snapshot $name..."
                            try {
                                val result = avd.saveSnapshot(target.name, name)
                                snapshots = avd.listSnapshots(target.name)
                                status = if (result.isSuccess) {
                                    result.stdout.ifBlank { "Saved $name" }
                                } else {
                                    result.stderr.ifBlank { result.stdout.ifBlank { "Failed to save $name" } }
                                }
                            } finally {
                                savingSnapshot = false
                            }
                        }
                    },
                    enabled = avdRow?.running == true && snapshotName.isNotBlank() && !savingSnapshot,
                    colors = primaryButtonColors(),
                ) { Text(if (savingSnapshot) "Saving..." else "Save current state") }
            }
        }
        TableHeader(listOf("NAME" to 1.dp, "SOURCE" to 120.dp, "ACTIONS" to 220.dp))
        LazyColumn {
            items(snapshots) { snapshot ->
                TableRow {
                    MonoCell(snapshot.name, 1.dp, TextPrimary, Modifier.weight(1f))
                    MonoCell(snapshot.source, 120.dp, TextSecondary)
                    Row(Modifier.width(220.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val target = selectedAvd ?: return@OutlinedButton
                                scope.launch {
                                    val result = avd.restoreSnapshot(target.name, snapshot.name)
                                    status = if (result.isSuccess) result.stdout.ifBlank { "Restored ${snapshot.name}" } else result.stderr.ifBlank { result.stdout }
                                    refresh()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) { Text("Restore", fontSize = 11.sp) }
                        OutlinedButton(
                            onClick = {
                                val target = selectedAvd ?: return@OutlinedButton
                                pendingConfirmation = PendingConfirmation("Delete snapshot?", "${target.name} / ${snapshot.name}") {
                                    scope.launch {
                                        val result = avd.deleteSnapshot(target.name, snapshot.name)
                                        status = if (result.isSuccess) result.stdout.ifBlank { "Deleted ${snapshot.name}" } else result.stderr.ifBlank { result.stdout }
                                        refreshSnapshots(target)
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) { Text("Delete", fontSize = 11.sp) }
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
    val bugCaptureStatus by services.bugs.status.collectAsState(BugCaptureStatus())
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
        MirrorFrameContent(services.mirror, serial) { frame ->
            val visibleFrame = frame.takeUnless { bugDialogVisible || clipDialogVisible }
            LiveDevicePane(
                serial = serial,
                device = device,
                frame = visibleFrame,
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill("SD", maxSize == "480", Cyan) { applyPreset("480", "2") }
                    FilterPill("HD", maxSize == "720", Green) { applyPreset("720", "4") }
                    FilterPill("FHD", maxSize == "1080", Yellow) { applyPreset("1080", "8") }
                    FilterPill("Max", maxSize == "1440", Rust) { applyPreset("1440", "16") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    LabeledField("Max px", maxSize, { maxSize = it.filter(Char::isDigit) }, Modifier.width(96.dp))
                    LabeledField("Mbps", bitRateMbps, { bitRateMbps = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.width(88.dp))
                    LabeledField("FPS", maxFps, { maxFps = it.filter(Char::isDigit) }, Modifier.width(78.dp))
                    Box(Modifier.padding(bottom = 2.dp)) {
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
                Text(
                    "Bug buffer: ${bugCaptureStatus.videoFrameCount} frames · ${bugCaptureStatus.actionCount} actions · ${bugCaptureStatus.logCount} logs",
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (bugSaveStatus.isNotBlank()) {
                    Text(bugSaveStatus, color = Rust, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalPaneDivider(
                onDrag = { dragY -> localControlsPaneHeight = (localControlsPaneHeight + dragY).coerceIn(170f, 520f) },
                onDragEnd = { onControlsPaneHeightChange(localControlsPaneHeight) },
            )
            LogcatPanel(services.logcat, serial, Modifier.fillMaxWidth().weight(0.55f), compact = true, state = logcatState)
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
    frame: MirrorFrame?,
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
    onHoverColor: (String) -> Unit = {},
    passThroughInput: Boolean = true,
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
    Row(modifier.background(PanelSoft, RoundedCornerShape(8.dp)).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(device?.serial ?: serial ?: "No device", color = TextPrimary, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(device?.screenSize, frame?.decodedFps?.let { "%.1f fps".format(it) }).joinToString(" · ").ifBlank { "-" },
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(18.dp))
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val viewportWidth = maxWidth
            val viewportHeight = maxHeight
            val zoomFactor = zoom.coerceIn(0.5f, 4f)
            val sourceWidth = (device?.screenSize?.substringBefore("x")?.toIntOrNull() ?: frame?.width ?: 1080).coerceAtLeast(1)
            val sourceHeight = (device?.screenSize?.substringAfter("x")?.toIntOrNull() ?: frame?.height ?: 2340).coerceAtLeast(1)
            val aspect = sourceWidth.toFloat() / sourceHeight.toFloat()
            Box(
                Modifier.fillMaxSize()
                    .horizontalScroll(rememberScrollState())
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center,
            ) {
                val baseWidth = minOf(viewportWidth, viewportHeight * aspect)
                Box(
                    Modifier
                        .width(baseWidth * zoomFactor)
                        .aspectRatio(aspect)
                        .background(Color.Black, RoundedCornerShape(10.dp))
                        .border(5.dp, Color(0xFF111111), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (frame != null) {
                        MirrorVideoSurface(
                            frame = frame,
                            modifier = Modifier.fillMaxSize(),
                            onInput = onInput,
                            onHoverColor = onHoverColor,
                            passThroughInput = passThroughInput,
                            onDevicePointClick = onDevicePointClick,
                            onRulerResize = onRulerResize,
                            overlay = MirrorOverlay(
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
                            ),
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                            Text("embedded mirror", color = TextPrimary, fontWeight = FontWeight.Bold)
                            Text(mirrorStatus, color = TextSecondary, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(connectResult.ifBlank { if (serial == null) "Select an online device" else "Connect streams H.264 in-app" }, color = TextSecondary, fontSize = 11.sp)
                            if (serial != null) {
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = onConnect,
                                    colors = primaryButtonColors(),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    Text("Connect", color = TextPrimary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { onInput(MirrorInput.Back) },
                enabled = serial != null
            ) {
                NavIconBack(color = if (serial != null) TextPrimary else TextSecondary)
            }
            IconButton(
                onClick = { onInput(MirrorInput.Home) },
                enabled = serial != null
            ) {
                NavIconHome(color = if (serial != null) TextPrimary else TextSecondary)
            }
            IconButton(
                onClick = { onInput(MirrorInput.Recents) },
                enabled = serial != null
            ) {
                NavIconRecents(color = if (serial != null) TextPrimary else TextSecondary)
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

private fun parseBounds(bounds: String?): List<Int>? {
    if (bounds.isNullOrBlank()) return null
    val values = Regex("""\d+""").findAll(bounds).map { it.value.toInt() }.toList()
    return values.takeIf { it.size == 4 }
}

internal fun AccessibilityNode.findBestNodeAt(x: Int, y: Int): AccessibilityNode? {
    val candidates = proximityCandidatesAt(x, y)
    val interactiveCandidates = candidates.filter { it.isActionable }
    val selectableCandidates = interactiveCandidates.ifEmpty {
        candidates.filter { it.depth > 0 && !it.isFullScreenContainer }
    }.ifEmpty { candidates }
    return selectableCandidates
        .sortedWith(
            compareBy<AccessibilityHitCandidate> { it.selectionScore }
                .thenByDescending { it.labelScore }
                .thenByDescending { it.depth }
                .thenByDescending { it.drawingOrder },
        )
        .firstOrNull()
        ?.node
}

private data class AccessibilityHitCandidate(
    val node: AccessibilityNode,
    val depth: Int,
    val area: Int,
    val drawingOrder: Int,
    val distanceSquared: Int,
    val labelScore: Int,
    val isFullScreenContainer: Boolean,
) {
    val isActionable: Boolean get() = node.clickable || node.focusable || !node.contentDescription.isNullOrBlank() ||
        !node.text.isNullOrBlank() || !node.resourceId.isNullOrBlank()
    val selectionScore: Int get() = distanceSquared * 16 +
        area / 35 -
        labelScore * 12_000 -
        depth * 450 +
        if (isFullScreenContainer) 1_000_000 else 0
}

private fun AccessibilityNode.proximityCandidatesAt(x: Int, y: Int, depth: Int = 0): List<AccessibilityHitCandidate> {
    val childHits = children.flatMap { it.proximityCandidatesAt(x, y, depth + 1) }
    val bounds = parseBounds(bounds) ?: return childHits
    val distanceSquared = distanceSquaredToBounds(x, y, bounds)
    if (distanceSquared > 180 * 180) return childHits
    val area = ((bounds[2] - bounds[0]).coerceAtLeast(1)) * ((bounds[3] - bounds[1]).coerceAtLeast(1))
    return childHits + AccessibilityHitCandidate(
        node = this,
        depth = depth,
        area = area,
        drawingOrder = attributes["drawing-order"]?.toIntOrNull() ?: 0,
        distanceSquared = distanceSquared,
        labelScore = listOf(text, contentDescription, hint, resourceId).count { !it.isNullOrBlank() } +
            if (!contentDescription.isNullOrBlank()) 3 else 0 +
            if (clickable) 3 else 0 +
            if (focusable) 1 else 0,
        isFullScreenContainer = depth <= 2 && area > 1_200_000 && text.isNullOrBlank() &&
            contentDescription.isNullOrBlank() && resourceId.isNullOrBlank(),
    )
}

private fun distanceSquaredToBounds(x: Int, y: Int, bounds: List<Int>): Int {
    val dx = when {
        x < bounds[0] -> bounds[0] - x
        x > bounds[2] -> x - bounds[2]
        else -> 0
    }
    val dy = when {
        y < bounds[1] -> bounds[1] - y
        y > bounds[3] -> y - bounds[3]
        else -> 0
    }
    return dx * dx + dy * dy
}

@Composable
private fun PaneDivider(onDrag: (Float) -> Unit, onDragEnd: () -> Unit = {}) {
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val density = LocalDensity.current.density
    Box(
        Modifier.width(14.dp)
            .fillMaxHeight()
            .horizontalResizeCursor()
            .background(PaneDividerTint.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { latestOnDragEnd() },
                    onDragCancel = { latestOnDragEnd() },
                ) { _, drag -> latestOnDrag(drag.x / density) }
            },
    ) {
        Box(Modifier.align(Alignment.Center).width(3.dp).fillMaxHeight().background(PaneDividerTint))
    }
}

@Composable
private fun HorizontalPaneDivider(onDrag: (Float) -> Unit, onDragEnd: () -> Unit = {}) {
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val density = LocalDensity.current.density
    Box(
        Modifier.fillMaxWidth()
            .height(18.dp)
            .verticalResizeCursor()
            .background(PaneDividerTint.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { latestOnDragEnd() },
                    onDragCancel = { latestOnDragEnd() },
                ) { _, drag -> latestOnDrag(drag.y / density) }
            },
    ) {
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(4.dp).background(PaneDividerTint, RoundedCornerShape(2.dp)))
    }
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
}

@Composable
private fun LogcatScreen(logcat: LogcatService, serial: String?, state: LogcatState) {
    LogcatPanel(logcat, serial, Modifier.fillMaxSize(), compact = false, state = state)
}

@Composable
private fun LogcatPanel(
    logcat: LogcatService,
    serial: String?,
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
                state.live != state.lastLive

        if (parametersChanged) {
            streamJob?.cancel()
            streamJob = null
            val filtersChanged = serial != state.lastSerial ||
                    state.search != state.lastSearch ||
                    currentLevels != state.lastLevels
            if (filtersChanged) {
                state.entries = emptyList()
            }
            state.lastSerial = serial
            state.lastSearch = state.search
            state.lastLevels = currentLevels
            state.lastLive = state.live

            if (serial == null || !state.live) return
            streamJob = scope.launch {
                logcat.stream(serial, LogcatFilter(state.search, currentLevels)).collect { batch ->
                    state.entries = (state.entries + batch).takeLast(1200)
                }
            }
        } else {
            if (streamJob == null && serial != null && state.live) {
                state.lastSerial = serial
                state.lastSearch = state.search
                state.lastLevels = currentLevels
                state.lastLive = state.live
                streamJob = scope.launch {
                    logcat.stream(serial, LogcatFilter(state.search, currentLevels)).collect { batch ->
                        state.entries = (state.entries + batch).takeLast(1200)
                    }
                }
            }
        }
    }
    LaunchedEffect(serial, state.live, state.search, state.levels.values.toList()) { restart() }

    PanelCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (!compact) {
                Text("Logcat", color = TextPrimary, fontWeight = FontWeight.Bold)
            }
            TextField(value = state.search, onValueChange = { state.search = it }, placeholder = { Text("filter or package:com.example", color = TextSecondary) }, singleLine = true, modifier = Modifier.weight(1f).height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
            LogLevel.entries.filter { it != LogLevel.Silent }.forEach { level ->
                FilterPill(level.name.take(1), state.levels[level] == true, levelColor(level)) { state.levels[level] = !(state.levels[level] ?: false) }
            }
            Button(onClick = { state.live = !state.live }, colors = ButtonDefaults.buttonColors(containerColor = if (state.live) Rust else PanelSoft)) { Text(if (state.live) "Live" else "Paused") }
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
        LogcatEntryList(state.entries, compact, Modifier.fillMaxSize())
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

    fun runAppAction(label: String, packageName: String? = selected?.packageName, block: suspend () -> CommandResult) {
        scope.launch {
            packageName?.let { services.bugs.recordAction("app", label, it) }
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
            TableHeader(listOf("TYPE" to 70.dp, "STATE" to 80.dp, "VERSION" to 90.dp, "APP NAME" to 160.dp, "PACKAGE" to 1.dp))
            LazyColumn {
                items(filtered) { app ->
                    TableRow(Modifier.clickable {
                        selected = app
                        if (serial != null) scope.launch {
                            permissions = apps.listPermissions(serial, app.packageName)
                            activities = apps.listActivities(serial, app.packageName)
                        }
                    }) {
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
                        Button(onClick = { runAppAction("Launch", app.packageName) { apps.launch(serial, app.packageName) } }) { Text("Launch") }
                        OutlinedButton(onClick = { runAppAction("Stop", app.packageName) { apps.stop(serial, app.packageName) } }) { Text("Stop") }
                        OutlinedButton(onClick = {
                            pendingConfirmation = PendingConfirmation("Clear app data?", app.packageName) {
                                runAppAction("Clear data", app.packageName) { apps.clearData(serial, app.packageName) }
                            }
                        }) { Text("Clear") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { runAppAction("Reset permissions", app.packageName) { apps.resetPermissions(serial, app.packageName) } }) { Text("Reset perms") }
                        OutlinedButton(onClick = {
                            pendingConfirmation = PendingConfirmation("Uninstall app?", app.packageName) {
                                runAppAction("Uninstall", app.packageName) { apps.uninstall(serial, app.packageName) }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { run("Airplane on", listOf("cmd", "connectivity", "airplane-mode", "enable")) }) { Text("Airplane on") }
                OutlinedButton(onClick = { run("Airplane off", listOf("cmd", "connectivity", "airplane-mode", "disable")) }) { Text("Airplane off") }
                Button(onClick = { run("WiFi on", listOf("svc", "wifi", "enable")) }) { Text("WiFi on") }
                OutlinedButton(onClick = { run("WiFi off", listOf("svc", "wifi", "disable")) }) { Text("WiFi off") }
                Button(onClick = { run("Data on", listOf("svc", "data", "enable")) }) { Text("Data on") }
                OutlinedButton(onClick = { run("Data off", listOf("svc", "data", "disable")) }) { Text("Data off") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { run("Show taps on", listOf("settings", "put", "system", "show_touches", "1")) }) { Text("Taps on") }
                OutlinedButton(onClick = { run("Show taps off", listOf("settings", "put", "system", "show_touches", "0")) }) { Text("Taps off") }
                Button(onClick = { run("Pointer on", listOf("settings", "put", "system", "pointer_location", "1")) }) { Text("Pointer on") }
                OutlinedButton(onClick = { run("Pointer off", listOf("settings", "put", "system", "pointer_location", "0")) }) { Text("Pointer off") }
                Button(onClick = { run("Bounds on", listOf("setprop", "debug.layout", "true")) }) { Text("Bounds on") }
                OutlinedButton(onClick = { run("Bounds off", listOf("setprop", "debug.layout", "false")) }) { Text("Bounds off") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
private fun NetworkScreen(
    services: AndyServices,
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
            return@LaunchedEffect
        }
        while (true) {
            val isCaOk = proxy.isCertificateInstalled(serial)
            val host = proxy.resolveDeviceProxyHost(serial)
            val isProxyOk = proxy.isDeviceProxyConfigured(serial, host, currentPort)
            caInstalled = isCaOk
            proxyConfigured = isProxyOk
            delay(3000)
        }
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

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1.45f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    Text(
                        if (setupExpanded) "Hide setup" else "Show setup",
                        color = Rust,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { setupExpanded = !setupExpanded },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        enabled = serial != null,
                        onClick = {
                            if (serial != null) scope.launch {
                                val host = proxy.resolveDeviceProxyHost(serial)
                                proxyHost = host
                                val result = proxy.configureDeviceProxy(serial, host, currentPort)
                                status = if (result.isSuccess) "Device proxy set to $host:$currentPort" else result.stderr
                                proxyConfigured = proxy.isDeviceProxyConfigured(serial, host, currentPort)
                            }
                        },
                    ) { Text("Configure device proxy") }
                    OutlinedButton(
                        enabled = serial != null,
                        onClick = {
                            if (serial != null) scope.launch {
                                val result = proxy.clearDeviceProxy(serial)
                                status = if (result.isSuccess) "Device proxy cleared" else result.stderr
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
                    OutlinedButton(onClick = {
                        scope.launch {
                            val result = proxy.clearTraffic()
                            selectedFlowId = null
                            seenFlowIds = emptySet()
                            flashingTrafficKeys.clear()
                            status = if (result.isSuccess) result.stdout else result.stderr
                        }
                    }) { Text("Clear traffic") }
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R3))
                        .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusIndicator(
                        isOk = proxyStarted,
                        label = "Proxy Status",
                        hint = if (proxyStarted) "Listening on port $currentPort" else "Click 'Start' to start"
                    )
                    StatusIndicator(
                        isOk = serial != null && (caInstalled || proxyTrafficObservedForDevice),
                        label = "CA Trust",
                        hint = caText
                    )
                    StatusIndicator(
                        isOk = serial != null && proxyConfigured,
                        label = "Device Proxy Routing",
                        hint = configText
                    )
                }
                Text(status, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusTag(if (engineReady) "mitmdump ready" else "mitmdump missing", if (engineReady) Green else Red)
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
                AnimatedVisibility(setupExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            LazyColumn(Modifier.weight(1f)) {
                items(visibleTrafficRows, key = { row -> row.key }) { row ->
                    NetworkTrafficRow(
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
                    NetworkLivePanel(
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
                    NetworkLivePanel(
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
private fun StatusIndicator(
    isOk: Boolean,
    label: String,
    hint: String,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        GlowingDot(isOk)
        Column {
            Text(label, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(
                text = hint,
                color = if (isOk) Green else Red,
                fontSize = 11.sp
            )
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
}

private fun shouldAutoStartProxy(status: String, port: Int): Boolean {
    val normalized = status.trim()
    if (normalized == "Proxy stopped" || normalized == "mitmdump exited" || normalized.startsWith("Proxy failed")) {
        return true
    }
    val listeningPort = Regex(""":(\d{1,5})(?:\D*)?$""")
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    return normalized.contains("listening on") && listeningPort != null && listeningPort != port
}


private class MutableNetworkTrafficNode(
    val key: String,
    val label: String,
    val depth: Int,
) {
    val children = linkedMapOf<String, MutableNetworkTrafficNode>()
    val exchanges = mutableListOf<NetworkExchange>()
}

private data class NetworkTrafficNode(
    val key: String,
    val label: String,
    val depth: Int,
    val exchanges: List<NetworkExchange>,
    val children: List<NetworkTrafficNode>,
) {
    val count: Int = exchanges.size + children.sumOf { it.count }
    val latest: NetworkExchange? = (exchanges + children.mapNotNull { it.latest }).maxByOrNull { it.completedAtMillis ?: it.startedAtMillis }
}

private data class NetworkTrafficRow(
    val key: String,
    val label: String,
    val depth: Int,
    val hasChildren: Boolean,
    val count: Int,
    val latest: NetworkExchange?,
    val exchange: NetworkExchange?,
)

private data class NetworkUrlParts(
    val baseUrl: String,
    val pathSegments: List<String>,
)

private fun buildNetworkTrafficTree(exchanges: List<NetworkExchange>): List<NetworkTrafficNode> {
    val roots = linkedMapOf<String, MutableNetworkTrafficNode>()
    exchanges.forEach { exchange ->
        val parts = networkUrlParts(exchange.url)
        val baseKey = "base:${parts.baseUrl}"
        var current = roots.getOrPut(baseKey) { MutableNetworkTrafficNode(baseKey, parts.baseUrl, 0) }
        var pathKey = baseKey
        parts.pathSegments.forEachIndexed { index, segment ->
            pathKey += "/$segment"
            current = current.children.getOrPut(pathKey) {
                MutableNetworkTrafficNode(pathKey, segment, index + 1)
            }
        }
        current.exchanges += exchange
    }
    return roots.values.map { it.toImmutableNode() }.sortedBy { it.label.lowercase() }
}

private fun MutableNetworkTrafficNode.toImmutableNode(): NetworkTrafficNode {
    return NetworkTrafficNode(
        key = key,
        label = label,
        depth = depth,
        exchanges = exchanges.sortedByDescending { it.completedAtMillis ?: it.startedAtMillis },
        children = children.values.map { it.toImmutableNode() }.sortedBy { it.label.lowercase() },
    )
}

private fun flattenNetworkTrafficTree(nodes: List<NetworkTrafficNode>, expandedKeys: Map<String, Boolean>): List<NetworkTrafficRow> {
    val rows = mutableListOf<NetworkTrafficRow>()
    fun addNode(node: NetworkTrafficNode) {
        rows += NetworkTrafficRow(
            key = node.key,
            label = node.label,
            depth = node.depth,
            hasChildren = node.children.isNotEmpty() || node.exchanges.isNotEmpty(),
            count = node.count,
            latest = node.latest,
            exchange = null,
        )
        if (expandedKeys[node.key] == true) {
            node.children.forEach(::addNode)
            node.exchanges.forEach { exchange ->
                rows += NetworkTrafficRow(
                    key = "call:${exchange.flowId}",
                    label = exchange.url.substringAfterLast('/').substringBefore('?').ifBlank { "/" },
                    depth = node.depth + 1,
                    hasChildren = false,
                    count = 1,
                    latest = exchange,
                    exchange = exchange,
                )
            }
        }
    }
    nodes.forEach(::addNode)
    return rows
}

private fun networkTrafficAncestorKeys(exchange: NetworkExchange): List<String> {
    val parts = networkUrlParts(exchange.url)
    val keys = mutableListOf("base:${parts.baseUrl}")
    var key = keys.first()
    parts.pathSegments.forEach { segment ->
        key += "/$segment"
        keys += key
    }
    return keys
}

private fun networkUrlParts(url: String): NetworkUrlParts {
    val withoutFragment = url.substringBefore('#')
    val schemeSplit = withoutFragment.indexOf("://")
    val afterAuthorityStart = if (schemeSplit >= 0) schemeSplit + 3 else 0
    val firstPathIndex = withoutFragment.indexOf('/', startIndex = afterAuthorityStart).takeIf { it >= 0 }
    val firstQueryIndex = withoutFragment.indexOf('?', startIndex = afterAuthorityStart).takeIf { it >= 0 }
    val authorityEnd = listOfNotNull(firstPathIndex, firstQueryIndex).minOrNull() ?: withoutFragment.length
    val authority = withoutFragment.substring(0, authorityEnd).ifBlank { "unknown" }
    val pathStart = firstPathIndex ?: withoutFragment.length
    val rawPath = withoutFragment.substring(pathStart).substringBefore('?')
    val segments = rawPath.split('/').filter { it.isNotBlank() }
    return NetworkUrlParts(authority, segments.ifEmpty { listOf("/") })
}

@Composable
private fun NetworkTrafficRow(
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

private sealed class JsonPreviewNode(
    open val path: String,
    open val label: String?,
) {
    val isContainer: Boolean
        get() = this is ObjectNode || this is ArrayNode

    data class ObjectNode(
        override val path: String,
        override val label: String?,
        val children: List<JsonPreviewNode>,
    ) : JsonPreviewNode(path, label)

    data class ArrayNode(
        override val path: String,
        override val label: String?,
        val children: List<JsonPreviewNode>,
    ) : JsonPreviewNode(path, label)

    data class ValueNode(
        override val path: String,
        override val label: String?,
        val value: String,
    ) : JsonPreviewNode(path, label)
}

private data class JsonPreviewRow(
    val node: JsonPreviewNode,
    val depth: Int,
    val text: String,
)

private fun parseJsonBodyPreview(body: String?): JsonPreviewNode? {
    val value = body?.trim().orEmpty()
    if (value.isBlank()) return null
    if (value.firstOrNull() !in setOf('{', '[')) return null
    return runCatching { JsonPreviewParser(value).parse() }.getOrNull()
}

private fun flattenJsonPreview(root: JsonPreviewNode, expandedKeys: Map<String, Boolean>): List<JsonPreviewRow> {
    val rows = mutableListOf<JsonPreviewRow>()
    fun add(node: JsonPreviewNode, depth: Int) {
        rows += JsonPreviewRow(node, depth, jsonPreviewRowText(node, expandedKeys[node.path] == true))
        if (expandedKeys[node.path] == true) {
            when (node) {
                is JsonPreviewNode.ObjectNode -> node.children.forEach { add(it, depth + 1) }
                is JsonPreviewNode.ArrayNode -> node.children.forEach { add(it, depth + 1) }
                is JsonPreviewNode.ValueNode -> Unit
            }
        }
    }
    add(root, 0)
    return rows
}

private fun jsonPreviewRowText(node: JsonPreviewNode, expanded: Boolean): String {
    val label = node.label?.let { "$it: " }.orEmpty()
    return when (node) {
        is JsonPreviewNode.ObjectNode -> {
            if (expanded) "$label{${node.children.size} keys}" else "$label{...}  ${node.children.size} keys"
        }
        is JsonPreviewNode.ArrayNode -> {
            if (expanded) "$label[${node.children.size} items]" else "$label[...]  ${node.children.size} items"
        }
        is JsonPreviewNode.ValueNode -> "$label${node.value}"
    }
}

private class JsonPreviewParser(private val source: String) {
    private var index = 0

    fun parse(): JsonPreviewNode {
        val node = parseValue("$", null)
        skipWhitespace()
        require(index == source.length)
        return node
    }

    private fun parseValue(path: String, label: String?): JsonPreviewNode {
        skipWhitespace()
        return when (peek()) {
            '{' -> parseObject(path, label)
            '[' -> parseArray(path, label)
            '"' -> JsonPreviewNode.ValueNode(path, label, quoteJsonPreview(parseString()))
            else -> JsonPreviewNode.ValueNode(path, label, parseLiteral())
        }
    }

    private fun parseObject(path: String, label: String?): JsonPreviewNode.ObjectNode {
        expect('{')
        skipWhitespace()
        val children = mutableListOf<JsonPreviewNode>()
        if (consume('}')) return JsonPreviewNode.ObjectNode(path, label, children)
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            children += parseValue("$path.${escapePathSegment(key)}", quoteJsonPreview(key))
            skipWhitespace()
            if (consume('}')) break
            expect(',')
        }
        return JsonPreviewNode.ObjectNode(path, label, children)
    }

    private fun parseArray(path: String, label: String?): JsonPreviewNode.ArrayNode {
        expect('[')
        skipWhitespace()
        val children = mutableListOf<JsonPreviewNode>()
        if (consume(']')) return JsonPreviewNode.ArrayNode(path, label, children)
        var childIndex = 0
        while (true) {
            children += parseValue("$path[$childIndex]", "[$childIndex]")
            childIndex += 1
            skipWhitespace()
            if (consume(']')) break
            expect(',')
        }
        return JsonPreviewNode.ArrayNode(path, label, children)
    }

    private fun parseString(): String {
        expect('"')
        val builder = StringBuilder()
        while (index < source.length) {
            val char = source[index++]
            when (char) {
                '"' -> return builder.toString()
                '\\' -> {
                    require(index < source.length)
                    val escaped = source[index++]
                    builder.append('\\')
                    builder.append(escaped)
                    if (escaped == 'u') {
                        repeat(4) {
                            require(index < source.length)
                            builder.append(source[index++])
                        }
                    }
                }
                else -> builder.append(char)
            }
        }
        error("Unterminated string")
    }

    private fun parseLiteral(): String {
        val start = index
        while (index < source.length && source[index] !in charArrayOf(',', '}', ']', ' ', '\n', '\r', '\t')) {
            index += 1
        }
        require(index > start)
        return source.substring(start, index)
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index].isWhitespace()) {
            index += 1
        }
    }

    private fun peek(): Char {
        require(index < source.length)
        return source[index]
    }

    private fun consume(char: Char): Boolean {
        if (index < source.length && source[index] == char) {
            index += 1
            return true
        }
        return false
    }

    private fun expect(char: Char) {
        require(consume(char))
    }
}

private fun escapePathSegment(value: String): String {
    return value.replace("\\", "\\\\").replace(".", "\\.")
}

private fun quoteJsonPreview(value: String): String {
    return buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }
}

@Composable
private fun NetworkLivePanel(services: AndyServices, serial: String?, device: AndroidDevice?, modifier: Modifier = Modifier) {
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
    MirrorFrameContent(services.mirror, serial) { frame ->
        LiveDevicePane(
            serial = serial,
            device = device,
            frame = frame,
            mirrorStatus = mirrorStatus,
            connectResult = connectResult,
            modifier = modifier,
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
private fun PerformanceScreen(metrics: MetricsService, serial: String?, processesPaneWidth: Float, onProcessesPaneWidthChange: (Float) -> Unit) {
    var samples by remember { mutableStateOf<List<PerformanceSample>>(emptyList()) }
    var localProcessesPaneWidth by remember(processesPaneWidth) { mutableStateOf(processesPaneWidth) }
    LaunchedEffect(serial) {
        samples = emptyList()
        if (serial != null) metrics.stream(serial, null).collectLatest { samples = (samples + it).takeLast(60) }
    }
    val latest = samples.lastOrNull()
    val recentFrames = samples.flatMap { it.frameRenderTimes }.takeLast(60)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Toolbar("Performance", "process CPU/memory · frame render time · battery")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("CPU", latest?.cpuPercent?.let { "${it.toInt()}%" } ?: "-")
            MetricCard("Memory", latest?.memoryMb?.let { "${it.toInt()} MB" } ?: "-")
            MetricCard("Frames green", recentFrames.takeIf { it.isNotEmpty() }?.let { frames -> "${frames.count { it.millis <= 16.6f }}/${frames.size}" } ?: "-")
            MetricCard("Battery", latest?.batteryPercent?.let { "$it%" } ?: "-")
        }
        Row(Modifier.fillMaxSize()) {
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
            PanelCard(Modifier.fillMaxSize().padding(start = 6.dp)) {
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
}

@Composable
private fun DesignScreen(services: AndyServices, serial: String?, device: AndroidDevice?, devicePaneWidth: Float, onDevicePaneWidthChange: (Float) -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Design overlays") }
    var mirrorStatus by remember { mutableStateOf("Disconnected") }
    var connectResult by remember { mutableStateOf("") }
    var grid by remember { mutableStateOf(true) }
    var ruler by remember { mutableStateOf(true) }
    var gridSize by remember { mutableStateOf("16") }
    var rulerX by remember { mutableStateOf("540") }
    var rulerY by remember { mutableStateOf("960") }
    var color by remember { mutableStateOf(Cyan) }
    var pickerEnabled by remember { mutableStateOf(true) }
    var pickedColor by remember { mutableStateOf("#------") }
    var zoom by remember { mutableStateOf("1.0") }
    var localDevicePaneWidth by remember(devicePaneWidth) { mutableStateOf(devicePaneWidth.coerceAtLeast(760f)) }
    val sendMirrorInput = rememberMirrorInputSender(services, serial)
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
        MirrorFrameContent(services.mirror, serial) { frame ->
            LiveDevicePane(
                serial = serial,
                device = device,
                frame = frame,
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
                passThroughInput = true,
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
            Text("Under pointer $pickedColor · swatch ${color.toHex()}", color = if (pickerEnabled) TextPrimary else TextSecondary)
            Text(status, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
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
    var localTreePaneWidth by remember(treePaneWidth) { mutableStateOf(treePaneWidth.coerceIn(420f, 760f)) }
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
            PaneDivider(
                onDrag = { dragX -> localTreePaneWidth = (localTreePaneWidth + dragX).coerceIn(360f, 920f) },
                onDragEnd = { onTreePaneWidthChange(localTreePaneWidth) },
            )
            MirrorFrameContent(services.mirror, serial) { frame ->
                LiveDevicePane(
                    serial = serial,
                    device = device,
                    frame = frame,
                    mirrorStatus = mirrorStatus,
                    connectResult = connectResult,
                    modifier = Modifier.fillMaxSize().padding(start = 6.dp),
                    highlightBounds = state.hoveredBounds,
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
private fun DetailSection(title: String) {
    Text(title, color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().background(PanelSoft).padding(horizontal = 4.dp, vertical = 2.dp))
}

@Composable
private fun DetailRow(label: String, value: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(116.dp))
        Text(
            value?.takeIf { it.isNotBlank() } ?: "<not set>",
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
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
                    PanelCard(Modifier.width(380.dp).fillMaxHeight()) {
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
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(if (active) Rust.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(AndyRadius.R2))
                                        .border(1.dp, if (active) Rust.copy(alpha = 0.55f) else Color.Transparent, RoundedCornerShape(AndyRadius.R2))
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("${index + 1}", color = if (active) Rust else TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(22.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(action.label, color = if (active) AndyColors.Neutral100 else TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        action.detail?.let { Text(it, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    }
                                    Text(relativeSeconds(action.timestampMillis, report.windowEndedAtMillis), color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    PanelCard(Modifier.weight(1f).fillMaxHeight()) {
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
                    PanelCard(Modifier.width(320.dp).fillMaxHeight()) {
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
                            SelectionContainer {
                                Text(
                                    logcat.ifBlank { "<no logcat captured>" },
                                    color = TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    modifier = Modifier.fillMaxSize().background(Color.Black, RoundedCornerShape(AndyRadius.R3)).padding(10.dp).verticalScroll(rememberScrollState()),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatMillis(value: Long): String = if (value <= 0L) "-" else value.toString()

private const val BugReplayFps = 15.0
private const val BugActionHighlightWindowMillis = 1_200L
private const val BugPointerHighlightMillis = 900L

private data class BugPointerEvent(
    val x: Int,
    val y: Int,
    val progress: Float,
)

private fun bugPlaybackMillis(report: BugReport, frameIndex: Int, frameCount: Int): Long {
    val safeFrameCount = frameCount.coerceAtLeast(1)
    val clampedFrameIndex = frameIndex.coerceIn(0, safeFrameCount - 1)
    report.videoFrameTimestampsMillis.getOrNull(clampedFrameIndex)?.let { return it }
    val videoStart = report.videoStartedAtMillis
    val videoEnd = report.videoEndedAtMillis
    if (videoStart != null && videoEnd != null && videoEnd >= videoStart) {
        if (safeFrameCount == 1) return videoStart
        val progress = clampedFrameIndex.toDouble() / (safeFrameCount - 1).coerceAtLeast(1)
        return videoStart + ((videoEnd - videoStart) * progress).toLong()
    }
    val end = report.windowEndedAtMillis.takeIf { it > 0L } ?: report.capturedAtMillis
    val frameRate = report.videoFrameRate?.takeIf { it > 0.0 } ?: BugReplayFps
    val millisBeforeEnd = (((safeFrameCount - 1 - clampedFrameIndex) * 1000.0) / frameRate).toLong()
    return end - millisBeforeEnd
}

private fun activeBugActionIndex(actions: List<BugAction>, playbackMillis: Long): Int {
    return actions
        .mapIndexed { index, action -> index to kotlin.math.abs(action.timestampMillis - playbackMillis) }
        .filter { (_, distance) -> distance <= BugActionHighlightWindowMillis }
        .minByOrNull { (_, distance) -> distance }
        ?.first
        ?: -1
}

private fun activeBugPointerEvent(actions: List<BugAction>, playbackMillis: Long): BugPointerEvent? {
    val action = actions
        .filter { parseBugActionPoint(it) != null }
        .minByOrNull { kotlin.math.abs(it.timestampMillis - playbackMillis) }
        ?.takeIf { kotlin.math.abs(it.timestampMillis - playbackMillis) <= BugPointerHighlightMillis }
        ?: return null
    val point = parseBugActionPoint(action) ?: return null
    val age = kotlin.math.abs(playbackMillis - action.timestampMillis)
    return BugPointerEvent(
        x = point.first,
        y = point.second,
        progress = (age.toFloat() / BugPointerHighlightMillis).coerceIn(0f, 1f),
    )
}

private fun parseBugActionPoint(action: BugAction): Pair<Int, Int>? {
    if (action.kind != "input") return null
    val match = Regex("""(\d+),(\d+)""").find(action.label) ?: return null
    val x = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
    val y = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
    return x to y
}

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
private fun PlaceholderScreen(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("$name subsystem is represented in navigation and service contracts for v1 expansion.", color = TextSecondary)
    }
}

@Composable
private fun Toolbar(title: String, subtitle: String, onPrimary: (() -> Unit)? = null, primaryLabel: String = "Run") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title.lowercase(), color = AndyColors.Neutral100, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp)
            Text(subtitle.lowercase(), color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
        }
        if (onPrimary != null) Button(onClick = onPrimary, colors = primaryButtonColors(), shape = RoundedCornerShape(AndyRadius.R2), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { Text(primaryLabel.lowercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun PanelCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(AndyRadius.R3)
    Column(
        modifier
            .background(AndyColors.Neutral800.copy(alpha = 0.82f), shape)
            .border(1.dp, Border, shape)
            .noiseGridOverlay(0.025f)
            .padding(AndySpace.S4),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxWidth().height(150.dp).background(AndyColors.Neutral800, RoundedCornerShape(AndyRadius.R3)).border(1.dp, Border, RoundedCornerShape(AndyRadius.R3)), contentAlignment = Alignment.Center) {
        Text(text.lowercase(), color = TextSecondary, fontFamily = MonoFont)
    }
}

@Composable
private fun FilterPill(text: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(AndyRadius.R2)
    Box(
        Modifier.height(28.dp)
            .background(if (selected) color.copy(alpha = 0.26f) else AndyColors.Neutral850, shape)
            .border(1.dp, if (selected) color.copy(alpha = 0.70f) else Border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.lowercase(), color = if (selected) AndyColors.Neutral100 else AndyColors.Neutral300, fontFamily = MonoFont, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun TableHeader(columns: List<Pair<String, androidx.compose.ui.unit.Dp>>) {
    Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        columns.forEach { (title, width) ->
            Text(title.lowercase(), color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.Medium, fontSize = 10.sp, modifier = if (width.value == 1f) Modifier.weight(1f) else Modifier.width(width))
        }
    }
}

@Composable
private fun TableRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = 32.dp)
            .background(AndyColors.Neutral900.copy(alpha = 0.72f))
            .border(1.dp, Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun MonoCell(text: String, width: androidx.compose.ui.unit.Dp, color: Color, modifier: Modifier = Modifier) {
    Text(text, color = color, fontFamily = MonoFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = if (modifier != Modifier) modifier else Modifier.width(width))
}

@Composable
private fun FormRow(label: String, field: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label.lowercase(), color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(110.dp))
        field()
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minHeight: androidx.compose.ui.unit.Dp = 54.dp,
    placeholder: String? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.lowercase(), color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            modifier = Modifier.fillMaxWidth().heightIn(min = minHeight),
            textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = MonoFont),
            colors = fieldColors(),
            placeholder = placeholder?.let { hint ->
                { Text(hint.lowercase(), color = TextSecondary, fontFamily = MonoFont) }
            },
        )
    }
}

@Composable
private fun ControlRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label.lowercase(), color = TextSecondary, fontFamily = MonoFont)
        Text(value, color = TextPrimary, fontFamily = MonoFont)
    }
}

@Composable
private fun MetricCard(label: String, value: String) {
    PanelCard(Modifier.width(170.dp).height(96.dp)) {
        Text(label.lowercase(), color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold)
        Text(value, color = TextPrimary, fontSize = 26.sp, fontFamily = MonoFont)
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

@Composable
private fun fieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedContainerColor = AndyColors.Neutral900.copy(alpha = 0.72f),
    unfocusedContainerColor = AndyColors.Neutral850,
    disabledContainerColor = AndyColors.Neutral800,
    focusedIndicatorColor = AndyColors.OrangeBorder,
    unfocusedIndicatorColor = Border,
    disabledIndicatorColor = Border.copy(alpha = 0.45f),
    cursorColor = Rust,
    focusedPlaceholderColor = TextSecondary,
    unfocusedPlaceholderColor = TextSecondary,
)

@Composable
private fun primaryButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = AndyColors.OrangeSubtle,
    contentColor = AndyColors.Neutral100,
    disabledContainerColor = AndyColors.Neutral600,
    disabledContentColor = AndyColors.Neutral400,
)

@Composable
private fun secondaryButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = AndyColors.Neutral850,
    contentColor = TextPrimary,
    disabledContainerColor = AndyColors.Neutral700,
    disabledContentColor = AndyColors.Neutral500,
)

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
    var selectedClientLabel by remember { mutableStateOf(clientOptions.firstOrNull() ?: "Claude Code") }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var operationStatus by remember { mutableStateOf<String?>(null) }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    val mcpStatus by services.mcp.status.collectAsState("stopped")
    val mcpRunning by services.mcp.running.collectAsState(false)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("mcp settings", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp, fontFamily = MonoFont)

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
