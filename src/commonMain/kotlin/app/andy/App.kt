package app.andy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerMoveFilter
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectDragGestures
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.andy_robot
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
    Snapshots("Snapshots"),
    Controls("Controls"),
    Performance("Performance"),
    Design("Design"),
    Accessibility("Accessibility"),
    Bugs("Bugs"),
}

private object AndyColors {
    val Neutral100 = Color(0xFFF2F4F7)
    val Neutral200 = Color(0xFFE1E4E8)
    val Neutral300 = Color(0xFFB7BBC3)
    val Neutral400 = Color(0xFF8A8F98)
    val Neutral500 = Color(0xFF3A3D42)
    val Neutral600 = Color(0xFF2A2C30)
    val Neutral700 = Color(0xFF1E1F22)
    val Neutral750 = Color(0xFF191A1C)
    val Neutral800 = Color(0xFF141416)
    val Neutral850 = Color(0xFF101011)
    val Neutral900 = Color(0xFF0B0B0C)

    val Orange = Color(0xFFFF8A3D)
    val OrangeHover = Color(0xFFFF9E5C)
    val OrangePressed = Color(0xFFE87429)
    val OrangeSubtle = Color(0xFF3A2214)
    val OrangeBorder = Color(0xFF7A431F)
    val Green = Color(0xFF00E676)
    val GreenSoft = Color(0xFF42F59E)
    val GreenSubtle = Color(0xFF0E2A1F)
    val Blue = Color(0xFF4DA3FF)
    val Warning = Color(0xFFFFB020)
    val Error = Color(0xFFFF5252)
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
    val R3 = 8.dp
    val R4 = 12.dp
    val R5 = 16.dp
    val Pill = 999.dp
}

private val Ink = AndyColors.Neutral900
private val Panel = AndyColors.Neutral800
private val PanelSoft = AndyColors.Neutral700
private val Border = Color.White.copy(alpha = 0.08f)
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

@Composable
private fun rememberMirrorInputSender(services: AndyServices, serial: String?, enabled: Boolean = true): (MirrorInput) -> Unit {
    val currentSerial by rememberUpdatedState(serial)
    val currentEnabled by rememberUpdatedState(enabled)
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
            if (channel.trySend(input).isFailure) Unit
        }
    }
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
fun AndyApp(services: AndyServices, requestedDestination: AndyDestination? = null, onDestinationConsumed: () -> Unit = {}) {
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
            displayLarge = LocalTextStyle.current.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
            headlineLarge = LocalTextStyle.current.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
            titleMedium = LocalTextStyle.current.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            bodyMedium = LocalTextStyle.current.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
            bodySmall = LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
            labelMedium = LocalTextStyle.current.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
            labelSmall = LocalTextStyle.current.copy(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
        ),
    ) {
        AndyShell(services, requestedDestination, onDestinationConsumed)
    }
}

@Composable
private fun AndyShell(services: AndyServices, requestedDestination: AndyDestination?, onDestinationConsumed: () -> Unit) {
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
        refreshDevices()
    }

    LaunchedEffect(requestedDestination) {
        requestedDestination?.let {
            destination = it
            onDestinationConsumed()
        }
    }

    fun updateWorkspace(transform: (WorkspaceState) -> WorkspaceState) {
        val updated = transform(workspaceState).copy(selectedDeviceSerial = selectedSerial)
        workspaceState = updated
        scope.launch { services.workspaceStore.save(updated) }
    }

    Box(Modifier.fillMaxSize().background(Panel)) {
        Row(Modifier.fillMaxSize().padding(top = 24.dp)) {
            Sidebar(destination, devices.size, onSelect = { destination = it }, sdk = sdk)
            Column(Modifier.fillMaxSize()) {
                TopChrome(
                    destination = destination,
                    selectedDevice = devices.firstOrNull { it.serial == selectedSerial },
                    devices = devices,
                    onSelectDevice = { selectedSerial = it },
                    onRefresh = { refreshDevices() },
                    onStopEmulator = { stopEmulator(it) },
                    stoppingEmulatorSerial = stoppingEmulatorSerial,
                    actions = {
                        if (destination == AndyDestination.Network) {
                            FilterPill("Rules", networkRulesVisible, Rust) { networkRulesVisible = !networkRulesVisible }
                            Spacer(Modifier.width(8.dp))
                            FilterPill("Live", networkLiveVisible, Cyan) { networkLiveVisible = !networkLiveVisible }
                            Spacer(Modifier.width(10.dp))
                        }
                    },
                )
                Box(Modifier.fillMaxSize().background(Ink).padding(horizontal = 20.dp, vertical = 16.dp)) {
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
                        )
                        AndyDestination.Apps -> AppsScreen(
                            services.apps,
                            selectedSerial,
                            workspaceState.appsListPaneWidth,
                            workspaceState.appsDetailsPaneWidth,
                            onPaneChange = { listWidth, detailsWidth -> updateWorkspace { it.copy(appsListPaneWidth = listWidth, appsDetailsPaneWidth = detailsWidth) } },
                        )
                        AndyDestination.Logcat -> LogcatScreen(services.logcat, selectedSerial)
                        AndyDestination.Intents -> IntentsScreen(services.intents, selectedSerial)
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
                        )
                        else -> PlaceholderScreen(destination.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun Sidebar(current: AndyDestination, deviceCount: Int, onSelect: (AndyDestination) -> Unit, sdk: SdkDiscovery) {
    Column(
        Modifier.width(238.dp).fillMaxHeight().background(Panel).rightBorder(Border).padding(AndySpace.S3),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(AndySpace.S1, AndySpace.S2, AndySpace.S1, AndySpace.S3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AndySpace.S2),
            ) {
                AndyRobotIcon(Modifier.size(28.dp))
                Column {
                    Text("ANDY", color = AndyColors.Neutral100, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("WORKSPACE", color = TextSecondary, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.8.sp)
                }
            }
            AndyDestination.entries.forEach { item ->
                val active = item == current
                Row(
                    Modifier.fillMaxWidth()
                        .height(32.dp)
                        .background(if (active) PanelSoft else Color.Transparent, RoundedCornerShape(AndyRadius.R3))
                        .then(if (active) Modifier.border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(AndyRadius.R3)) else Modifier)
                        .clickable { onSelect(item) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(navMark(item), color = if (active) Rust else TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(item.label, color = if (active) AndyColors.Neutral100 else AndyColors.Neutral300, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (item == AndyDestination.Devices) Text("$deviceCount", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    if (item == AndyDestination.Logcat) Text("live", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
        Column(
            Modifier.fillMaxWidth()
                .background(AndyColors.Neutral700, RoundedCornerShape(AndyRadius.R4))
                .border(1.dp, Border, RoundedCornerShape(AndyRadius.R4))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("v0.1  H.264 embedded", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            StatusRow("ADB server", if (sdk.hasAdb) "ready" else "missing", sdk.hasAdb)
            StatusRow("AVD tools", if (sdk.hasEmulatorTools) "ready" else "install cmdline-tools", sdk.hasEmulatorTools)
            StatusRow("Proxy CA", "local", true)
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
    AndyDestination.Snapshots -> "[]"
    AndyDestination.Controls -> "+-"
    AndyDestination.Performance -> "/^"
    AndyDestination.Design -> "%%"
    AndyDestination.Accessibility -> "13"
    AndyDestination.Bugs -> "!!"
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Text(value, color = if (ok) Green else Rust, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(Panel).bottomBorder(Border).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(260.dp)) {
            Text(destination.label, color = AndyColors.Neutral100, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp)
            Text(selectedDevice?.let { "${it.displayName} · API ${it.apiLevel ?: "-"} · ${it.abi ?: "-"}" } ?: "No device selected", color = TextSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        actions()
        if (selectedDevice?.kind == DeviceKind.Emulator && selectedDevice.state == DeviceConnectionState.Online) {
            OutlinedButton(
                onClick = { onStopEmulator(selectedDevice) },
                enabled = stoppingEmulatorSerial != selectedDevice.serial,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(if (stoppingEmulatorSerial == selectedDevice.serial) "Stopping" else "Stop emulator", fontSize = 12.sp)
            }
            Spacer(Modifier.width(10.dp))
        }
        Button(onClick = onRefresh, colors = primaryButtonColors(), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Refresh", color = TextPrimary, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        DevicePicker(devices, selectedDevice?.serial, onSelectDevice)
    }
}

@Composable
private fun DevicePicker(devices: List<AndroidDevice>, selectedSerial: String?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { expanded = true }, colors = secondaryButtonColors(), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
            Text("•", color = Green, fontSize = 18.sp)
            Spacer(Modifier.width(6.dp))
            Text(selectedSerial ?: "No device", color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = PanelSoft) {
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

    fun refreshAvds() {
        scope.launch {
            avds = services.avd.listVirtualDevices()
        }
    }

    LaunchedEffect(Unit) {
        refreshAvds()
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Toolbar("Devices", "${devices.count { it.kind == DeviceKind.Physical }} physical · ${devices.count { it.kind == DeviceKind.Emulator }} emulators online · ${avds.size} created", onPrimary = {
            onRefresh()
            refreshAvds()
        }, primaryLabel = "Refresh")
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
            if (avds.isEmpty()) {
                Text("No AVDs found. Create one in Catalog or Android Studio, then refresh.", color = TextSecondary, fontSize = 12.sp)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    avds.forEach { avd ->
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
                        }
                    }
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices) { device ->
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
    }
}

private fun namesMatch(left: String, right: String): Boolean {
    return normalizeName(left) == normalizeName(right)
}

private fun normalizeName(value: String): String {
    return value.replace('_', ' ').trim().lowercase()
}

@Composable
private fun CatalogScreen(avd: AvdService) {
    val scope = rememberCoroutineScope()
    var images by remember { mutableStateOf<List<SystemImage>>(emptyList()) }
    var avds by remember { mutableStateOf<List<VirtualDevice>>(emptyList()) }
    var profiles by remember { mutableStateOf<List<AvdProfile>>(emptyList()) }
    var query by remember { mutableStateOf("api:36 variant:google") }
    var loading by remember { mutableStateOf(false) }

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
        TextField(value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth().height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
        TableHeader(listOf("API" to 90.dp, "Variant" to 360.dp, "ABI" to 170.dp, "State" to 160.dp, "Package" to 1.dp))
        LazyColumn {
            items(filtered.take(240)) { image ->
                TableRow {
                    MonoCell(image.api, 90.dp, TextPrimary)
                    MonoCell(image.variant, 360.dp, TextPrimary)
                    MonoCell(image.abi, 170.dp, TextSecondary)
                    MonoCell(if (image.installed) "Installed" else "Available", 160.dp, if (image.installed) Green else TextSecondary)
                    MonoCell(image.packageId, 1.dp, TextSecondary, Modifier.weight(1f))
                }
            }
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
) {
    val scope = rememberCoroutineScope()
    var mirrorStatus by remember { mutableStateOf("Disconnected") }
    var connectResult by remember { mutableStateOf("") }
    var maxSize by remember { mutableStateOf("720") }
    var bitRateMbps by remember { mutableStateOf("4") }
    var maxFps by remember { mutableStateOf("60") }
    var localDevicePaneWidth by remember(devicePaneWidth) { mutableStateOf(devicePaneWidth.coerceAtLeast(680f)) }
    var localControlsPaneHeight by remember(controlsPaneHeight) { mutableStateOf(controlsPaneHeight.coerceIn(170f, 360f)) }
    val sendMirrorInput = rememberMirrorInputSender(services, serial)
    fun sendHardware(input: MirrorInput) {
        sendMirrorInput(input)
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
                Text("Hardware", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactHardwareButton("Power", serial) { sendHardware(MirrorInput.Power) }
                    CompactHardwareButton("Vol +", serial) { sendHardware(MirrorInput.Key(24)) }
                    CompactHardwareButton("Vol -", serial) { sendHardware(MirrorInput.Key(25)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactHardwareButton("Recents", serial) { sendHardware(MirrorInput.Recents) }
                    CompactHardwareButton("Home", serial) { sendHardware(MirrorInput.Home) }
                    CompactHardwareButton("Back", serial) { sendHardware(MirrorInput.Back) }
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
            }
            HorizontalPaneDivider(
                onDrag = { dragY -> localControlsPaneHeight = (localControlsPaneHeight + dragY).coerceIn(170f, 520f) },
                onDragEnd = { onControlsPaneHeightChange(localControlsPaneHeight) },
            )
            LogcatPanel(services.logcat, serial, Modifier.fillMaxWidth().weight(0.55f), compact = true)
        }
    }
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
    gridSize: Float? = null,
    gridColor: Color = Color.White.copy(alpha = 0.14f),
    pickerColor: Color? = null,
    pickerHex: String? = null,
    zoom: Float = 1f,
    onHoverColor: (String) -> Unit = {},
    passThroughInput: Boolean = true,
    onDevicePointClick: (Int, Int) -> Unit = { _, _ -> },
    onRulerResize: (Float, Float) -> Unit = { _, _ -> },
    onInput: (MirrorInput) -> Unit,
    onConnect: () -> Unit,
) {
    Column(modifier.background(PanelSoft, RoundedCornerShape(8.dp)).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
    Box(
        Modifier.width(14.dp)
            .fillMaxHeight()
            .horizontalResizeCursor()
            .background(Border.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { latestOnDragEnd() },
                    onDragCancel = { latestOnDragEnd() },
                ) { _, drag -> latestOnDrag(drag.x) }
            },
    ) {
        Box(Modifier.align(Alignment.Center).width(3.dp).fillMaxHeight().background(Border))
    }
}

@Composable
private fun HorizontalPaneDivider(onDrag: (Float) -> Unit, onDragEnd: () -> Unit = {}) {
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    Box(
        Modifier.fillMaxWidth()
            .height(18.dp)
            .verticalResizeCursor()
            .background(Border.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { latestOnDragEnd() },
                    onDragCancel = { latestOnDragEnd() },
                ) { _, drag -> latestOnDrag(drag.y) }
            },
    ) {
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(4.dp).background(Rust.copy(alpha = 0.65f), RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun LogcatScreen(logcat: LogcatService, serial: String?) {
    LogcatPanel(logcat, serial, Modifier.fillMaxSize(), compact = false)
}

@Composable
private fun LogcatPanel(logcat: LogcatService, serial: String?, modifier: Modifier = Modifier, compact: Boolean) {
    var entries by remember { mutableStateOf<List<LogcatEntry>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var live by remember { mutableStateOf(true) }
    val levels = remember { mutableStateMapOf<LogLevel, Boolean>().also { map -> LogLevel.entries.forEach { map[it] = it != LogLevel.Verbose && it != LogLevel.Silent } } }
    var streamJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun restart() {
        streamJob?.cancel()
        entries = emptyList()
        if (serial == null || !live) return
        streamJob = scope.launch {
            logcat.stream(serial, LogcatFilter(search, levels.filterValues { it }.keys)).collect { batch ->
                entries = (entries + batch).takeLast(1200)
            }
        }
    }
    LaunchedEffect(serial, live, search, levels.values.toList()) { restart() }

    PanelCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text(if (compact) "Logcat" else "Logcat", color = TextPrimary, fontWeight = FontWeight.Bold)
            TextField(value = search, onValueChange = { search = it }, placeholder = { Text("filter or package:com.example", color = TextSecondary) }, singleLine = true, modifier = Modifier.weight(1f).height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
            LogLevel.entries.filter { it != LogLevel.Silent }.forEach { level ->
                FilterPill(level.name.take(1), levels[level] == true, levelColor(level)) { levels[level] = !(levels[level] ?: false) }
            }
            Button(onClick = { live = !live }, colors = ButtonDefaults.buttonColors(containerColor = if (live) Rust else PanelSoft)) { Text(if (live) "Live" else "Paused") }
        }
        LogcatEntryList(entries, compact, Modifier.fillMaxSize())
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
                    dragDelta += drag.x
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
private fun IntentsScreen(intentService: IntentService, serial: String?) {
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
                Button(onClick = { if (serial != null) scope.launch { result = intentService.send(serial, draft).let { if (it.isSuccess) it.stdout.ifBlank { "Sent" } else it.stderr } } }) { Text("Send") }
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
    apps: AppService,
    serial: String?,
    listPaneWidth: Float,
    detailsPaneWidth: Float,
    onPaneChange: (Float, Float) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<AndroidApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<AndroidApp?>(null) }
    var permissions by remember { mutableStateOf<List<AndroidPermission>>(emptyList()) }
    var activities by remember { mutableStateOf<List<AndroidActivity>>(emptyList()) }
    var status by remember { mutableStateOf("Select a device") }
    var localListPaneWidth by remember(listPaneWidth) { mutableStateOf(listPaneWidth) }
    var localDetailsPaneWidth by remember(detailsPaneWidth) { mutableStateOf(detailsPaneWidth) }

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

    fun runAppAction(label: String, block: suspend () -> CommandResult) {
        scope.launch {
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
            TableHeader(listOf("TYPE" to 70.dp, "STATE" to 80.dp, "VERSION" to 110.dp, "PACKAGE" to 1.dp))
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
                        MonoCell(app.versionCode ?: "-", 110.dp, TextSecondary)
                        MonoCell(app.packageName, 1.dp, if (selected?.packageName == app.packageName) Rust else TextPrimary, Modifier.weight(1f))
                    }
                }
            }
        }
        PaneDivider(
            onDrag = { dragX -> localListPaneWidth = (localListPaneWidth + dragX).coerceIn(320f, 1100f) },
            onDragEnd = { onPaneChange(localListPaneWidth, localDetailsPaneWidth) },
        )
        PanelCard(Modifier.width(localDetailsPaneWidth.dp).fillMaxHeight().padding(start = 6.dp)) {
            val app = selected
            Text(app?.packageName ?: "No app selected", color = TextPrimary, fontWeight = FontWeight.Bold)
            if (app != null && serial != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { runAppAction("Launch") { apps.launch(serial, app.packageName) } }) { Text("Launch") }
                    OutlinedButton(onClick = { runAppAction("Stop") { apps.stop(serial, app.packageName) } }) { Text("Stop") }
                    OutlinedButton(onClick = { runAppAction("Clear data") { apps.clearData(serial, app.packageName) } }) { Text("Clear") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { runAppAction("Reset permissions") { apps.resetPermissions(serial, app.packageName) } }) { Text("Reset perms") }
                    OutlinedButton(onClick = { runAppAction("Uninstall") { apps.uninstall(serial, app.packageName) } }, enabled = !app.system) { Text("Uninstall") }
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
        PaneDivider(
            onDrag = { dragX -> localDetailsPaneWidth = (localDetailsPaneWidth + dragX).coerceIn(280f, 900f) },
            onDragEnd = { onPaneChange(localListPaneWidth, localDetailsPaneWidth) },
        )
        PanelCard(Modifier.fillMaxSize().padding(start = 6.dp)) {
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
    var ruleName by remember { mutableStateOf("Mock response") }
    var rulePattern by remember { mutableStateOf("") }
    var ruleMethod by remember { mutableStateOf("") }
    var ruleStatus by remember { mutableStateOf("200") }
    var ruleSetHeaders by remember { mutableStateOf("content-type: application/json") }
    var ruleRemoveHeaders by remember { mutableStateOf("") }
    var ruleBody by remember { mutableStateOf("{\"andy\":true}") }
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

    LaunchedEffect(Unit) {
        caPath = proxy.certificateAuthorityPath()
        val engine = proxy.detectMitmproxy()
        engineReady = engine.isSuccess
        engineStatus = if (engine.isSuccess) {
            "mitmdump: ${engine.stdout.ifBlank { "ready" }}"
        } else {
            engine.stderr
        }
        proxy.exchanges.collectLatest { exchanges = it }
    }
    LaunchedEffect(Unit) {
        proxy.status.collectLatest { status = it }
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

    fun addRule() {
        val pattern = rulePattern.trim()
        if (pattern.isBlank()) {
            status = "Enter a URL match pattern before adding a rule"
            return
        }
        val rule = ProxyRule(
            id = "rule-${rules.size + 1}-${pattern.hashCode().toString().replace("-", "n")}",
            name = ruleName.ifBlank { pattern },
            enabled = true,
            urlPattern = pattern,
            method = ruleMethod.trim().uppercase().ifBlank { null },
            statusCode = ruleStatus.toIntOrNull(),
            setHeaders = parseHeaderLines(ruleSetHeaders),
            removeHeaders = ruleRemoveHeaders.split(',', '\n').map { it.trim() }.filter { it.isNotBlank() },
            responseBody = ruleBody.takeIf { it.isNotBlank() },
        )
        onRulesChange(rules + rule)
        rulePattern = ""
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
                            status = if (result.isSuccess) result.stdout else result.stderr
                        }
                    }) { Text("Start") }
                    OutlinedButton(onClick = { scope.launch { status = proxy.stop().stdout } }) { Text("Stop") }
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
                            }
                        },
                    ) { Text("Configure") }
                    OutlinedButton(
                        enabled = serial != null,
                        onClick = {
                            if (serial != null) scope.launch {
                                val result = proxy.clearDeviceProxy(serial)
                                status = if (result.isSuccess) "Device proxy cleared" else result.stderr
                            }
                        },
                    ) { Text("Clear proxy") }
                    OutlinedButton(
                        enabled = serial != null,
                        onClick = {
                            if (serial != null) scope.launch {
                                val result = proxy.installSystemCertificateAuthority(serial)
                                status = if (result.isSuccess) result.stdout else result.stderr
                            }
                        },
                    ) { Text("Install CA") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val result = proxy.clearTraffic()
                            selectedFlowId = null
                            seenFlowIds = emptySet()
                            flashingTrafficKeys.clear()
                            status = if (result.isSuccess) result.stdout else result.stderr
                        }
                    }) { Text("Clear traffic") }
                    Spacer(Modifier.weight(1f))
                    Text("Endpoint ${proxyHost.ifBlank { "select device" }}:$currentPort", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(status, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusTag(if (engineReady) "mitmdump ready" else "mitmdump missing", if (engineReady) Green else Red)
                    Text(engineStatus, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                AnimatedVisibility(setupExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("CA: ${caPath.ifBlank { "~/.andy/proxy/mitmproxy-ca-cert.cer" }}", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Capture scope: user-CA mode captures debug apps that honor HTTP(S) proxy settings. Chrome and many third-party apps need the CA installed as a system root on a rootable, non-Play emulator; pinned apps, QUIC/HTTP3, private DNS, and direct UDP will not appear in v1.",
                            color = Yellow,
                            fontSize = 12.sp,
                            maxLines = 3,
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
                if (liveVisible) {
                    NetworkLivePanel(
                        services = services,
                        serial = serial,
                        device = device,
                        modifier = Modifier.fillMaxWidth().weight(if (rulesVisible) 0.45f else 1f),
                    )
                }
                if (rulesVisible) {
                    PanelCard {
                        Text("Rules", color = TextPrimary, fontWeight = FontWeight.Bold)
                        LabeledField("Name", ruleName, { ruleName = it }, Modifier.fillMaxWidth())
                        LabeledField("URL contains", rulePattern, { rulePattern = it }, Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LabeledField("Method", ruleMethod, { ruleMethod = it.uppercase().take(8) }, Modifier.width(110.dp))
                            LabeledField("Status", ruleStatus, { ruleStatus = it.filter(Char::isDigit).take(3) }, Modifier.width(100.dp))
                        }
                        LabeledField("Set headers", ruleSetHeaders, { ruleSetHeaders = it }, Modifier.fillMaxWidth())
                        LabeledField("Remove headers", ruleRemoveHeaders, { ruleRemoveHeaders = it }, Modifier.fillMaxWidth())
                        TextField(ruleBody, { ruleBody = it }, modifier = Modifier.fillMaxWidth().height(120.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp), colors = fieldColors())
                        Button(onClick = { addRule() }, modifier = Modifier.fillMaxWidth()) { Text("Add rule") }
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(rules) { index, rule ->
                            PanelCard {
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
                                    OutlinedButton(onClick = { if (index > 0) onRulesChange(rules.swapItems(index, index - 1)) }, enabled = index > 0) { Text("Up") }
                                    OutlinedButton(onClick = { if (index < rules.lastIndex) onRulesChange(rules.swapItems(index, index + 1)) }, enabled = index < rules.lastIndex) { Text("Down") }
                                    OutlinedButton(onClick = { onRulesChange(rules.filterIndexed { i, _ -> i != index }) }) { Text("Remove") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
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
                        formatJson = false,
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
    val bodyText = remember(body, formatJson) {
        if (formatJson) formatJsonBodyPreview(body) else body?.takeIf { it.isNotBlank() } ?: "No body preview"
    }
    val content = buildString {
        appendLine("Headers")
        appendLine(headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }.ifBlank { "No headers" })
        appendLine()
        appendLine("Body")
        append(bodyText)
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
            Text(
                content,
                color = TextPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

private fun formatJsonBodyPreview(body: String?): String {
    val value = body?.trim().orEmpty()
    if (value.isBlank()) return "No body preview"
    return prettyJson(value) ?: value
}

private fun prettyJson(value: String): String? {
    val first = value.firstOrNull() ?: return null
    if (first != '{' && first != '[') return null
    val builder = StringBuilder()
    var indent = 0
    var inString = false
    var escaped = false
    value.forEach { char ->
        when {
            escaped -> {
                builder.append(char)
                escaped = false
            }
            char == '\\' && inString -> {
                builder.append(char)
                escaped = true
            }
            char == '"' -> {
                builder.append(char)
                inString = !inString
            }
            inString -> builder.append(char)
            char == '{' || char == '[' -> {
                builder.append(char)
                builder.append('\n')
                indent += 1
                builder.append("  ".repeat(indent))
            }
            char == '}' || char == ']' -> {
                builder.append('\n')
                indent = (indent - 1).coerceAtLeast(0)
                builder.append("  ".repeat(indent))
                builder.append(char)
            }
            char == ',' -> {
                builder.append(char)
                builder.append('\n')
                builder.append("  ".repeat(indent))
            }
            char == ':' -> builder.append(": ")
            char.isWhitespace() -> Unit
            else -> builder.append(char)
        }
    }
    return builder.toString()
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
    var rulerWidth by remember { mutableStateOf("260") }
    var rulerHeight by remember { mutableStateOf("120") }
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
                rulerWidth = rulerWidth.toFloatOrNull()?.coerceIn(20f, 3000f) ?: 260f,
                rulerHeight = rulerHeight.toFloatOrNull()?.coerceIn(20f, 3000f) ?: 120f,
                gridSize = if (grid) gridSize.toFloatOrNull()?.coerceIn(2f, 120f) else null,
                gridColor = color.copy(alpha = 0.38f),
                pickerColor = color.takeIf { pickerEnabled },
                pickerHex = pickedColor,
                zoom = zoom.toFloatOrNull()?.coerceIn(0.5f, 4f) ?: 1f,
                onHoverColor = { hex ->
                    if (pickerEnabled) pickedColor = hex
                },
                passThroughInput = true,
                onRulerResize = { width, height ->
                    rulerWidth = width.toInt().toString()
                    rulerHeight = height.toInt().toString()
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
            FormRow("Ruler W/H") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(rulerWidth, { rulerWidth = it.filter { ch -> ch.isDigit() || ch == '.' } }, singleLine = true, modifier = Modifier.width(110.dp).height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
                    TextField(rulerHeight, { rulerHeight = it.filter { ch -> ch.isDigit() || ch == '.' } }, singleLine = true, modifier = Modifier.width(110.dp).height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
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

@Composable
private fun AccessibilityScreen(
    services: AndyServices,
    serial: String?,
    device: AndroidDevice?,
    treePaneWidth: Float,
    onTreePaneWidthChange: (Float) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var root by remember { mutableStateOf<AccessibilityNode?>(null) }
    var status by remember { mutableStateOf("No dump loaded") }
    var hoveredBounds by remember { mutableStateOf<String?>(null) }
    var selectedNode by remember { mutableStateOf<AccessibilityNode?>(null) }
    var interactionMode by remember { mutableStateOf(false) }
    var localTreePaneWidth by remember(treePaneWidth) { mutableStateOf(treePaneWidth.coerceIn(420f, 760f)) }
    var mirrorStatus by remember { mutableStateOf("Disconnected") }
    var connectResult by remember { mutableStateOf("") }
    val sendMirrorInput = rememberMirrorInputSender(services, serial, enabled = !interactionMode)
    val flattenedNodes = remember(root) { root?.flattenAccessibilityTree().orEmpty() }
    val treeListState = rememberLazyListState()
    fun dump() {
        if (serial == null) return
        scope.launch {
            root = services.accessibility.dump(serial)
            selectedNode = root
            status = if (root == null) "No hierarchy returned" else "Hierarchy loaded · ${root?.countNodes() ?: 0} nodes"
        }
    }
    LaunchedEffect(selectedNode?.id, flattenedNodes.size) {
        val selectedId = selectedNode?.id ?: return@LaunchedEffect
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
        Toolbar("Accessibility", status, onPrimary = { dump() }, primaryLabel = "Dump tree")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill("Inspect clicks", interactionMode, Rust) { interactionMode = !interactionMode }
        }
        Row(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.width(localTreePaneWidth.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.fillMaxWidth().weight(1f).background(PanelSoft, RoundedCornerShape(8.dp)).padding(10.dp)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    if (flattenedNodes.isNotEmpty()) {
                        LazyColumn(state = treeListState, modifier = Modifier.widthIn(min = 980.dp).fillMaxHeight()) {
                            itemsIndexed(flattenedNodes, key = { _, row -> row.node.id }) { _, row ->
                                AccessibilityNodeRow(
                                    row = row,
                                    hoveredBounds = hoveredBounds,
                                    selectedId = selectedNode?.id,
                                    onHover = { hoveredBounds = it },
                                    onSelect = {
                                        selectedNode = it
                                        hoveredBounds = it.bounds
                                    },
                                )
                            }
                        }
                    } else {
                        Text("Dump a tree to inspect nodes.", color = TextSecondary)
                    }
                }
                PanelCard(Modifier.fillMaxWidth().height(300.dp)) {
                    AccessibilityDetails(selectedNode)
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
                    highlightBounds = hoveredBounds,
                    passThroughInput = !interactionMode,
                    onDevicePointClick = { x, y ->
                        root?.findBestNodeAt(x, y)?.let {
                            selectedNode = it
                            hoveredBounds = it.bounds
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
    onHover: (String?) -> Unit,
    onSelect: (AccessibilityNode) -> Unit,
) {
    val node = row.node
    val active = node.bounds == hoveredBounds || node.id == selectedId
    Column(
        Modifier.widthIn(min = 900.dp)
            .background(if (active) Rust.copy(alpha = 0.22f) else Color.Transparent, RoundedCornerShape(4.dp))
            .pointerMoveFilter(onEnter = { onHover(node.bounds); false }, onExit = { onHover(null); false })
            .clickable { onSelect(node) }
            .padding(start = (row.depth * 16).dp, top = 3.dp, bottom = 3.dp, end = 8.dp),
    ) {
        Text("${node.className ?: "node"}  ${node.bounds ?: ""}", color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
        val label = listOfNotNull(node.resourceId, node.text, node.contentDescription).joinToString(" · ")
        if (label.isNotBlank()) Text(label, color = TextSecondary, fontSize = 11.sp, maxLines = 1)
    }
}

private data class AccessibilityTreeRow(val node: AccessibilityNode, val depth: Int)

private fun AccessibilityNode.flattenAccessibilityTree(depth: Int = 0): List<AccessibilityTreeRow> {
    return listOf(AccessibilityTreeRow(this, depth)) + children.flatMap { it.flattenAccessibilityTree(depth + 1) }
}

private fun AccessibilityNode.countNodes(): Int = 1 + children.sumOf { it.countNodes() }

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
private fun PlaceholderScreen(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("$name subsystem is represented in navigation and service contracts for v1 expansion.", color = TextSecondary)
    }
}

@Composable
private fun Toolbar(title: String, subtitle: String, onPrimary: (() -> Unit)? = null, primaryLabel: String = "Run") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = AndyColors.Neutral100, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp)
            Text(subtitle, color = TextSecondary, fontSize = 12.sp)
        }
        if (onPrimary != null) Button(onClick = onPrimary, colors = primaryButtonColors(), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { Text(primaryLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun PanelCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(AndyRadius.R5)
    Column(
        modifier
            .background(Brush.verticalGradient(listOf(AndyColors.Neutral750, AndyColors.Neutral850)), shape)
            .border(1.dp, Border, shape)
            .padding(AndySpace.S4),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxWidth().height(150.dp).background(Panel, RoundedCornerShape(AndyRadius.R5)).border(1.dp, Border, RoundedCornerShape(AndyRadius.R5)), contentAlignment = Alignment.Center) {
        Text(text, color = TextSecondary)
    }
}

@Composable
private fun FilterPill(text: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(AndyRadius.Pill)
    Box(
        Modifier.height(28.dp)
            .background(if (selected) color else PanelSoft, shape)
            .border(if (selected) 0.dp else 1.dp, if (selected) Color.Transparent else Color.White.copy(alpha = 0.05f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) AndyColors.Neutral100 else AndyColors.Neutral300, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp)
    }
}

@Composable
private fun TableHeader(columns: List<Pair<String, androidx.compose.ui.unit.Dp>>) {
    Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        columns.forEach { (title, width) ->
            Text(title, color = TextSecondary, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.8.sp, modifier = if (width.value == 1f) Modifier.weight(1f) else Modifier.width(width))
        }
    }
}

@Composable
private fun TableRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = 32.dp)
            .background(AndyColors.Neutral900.copy(alpha = 0.65f))
            .border(1.dp, Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun MonoCell(text: String, width: androidx.compose.ui.unit.Dp, color: Color, modifier: Modifier = Modifier) {
    Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = if (modifier != Modifier) modifier else Modifier.width(width))
}

@Composable
private fun FormRow(label: String, field: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextSecondary, fontWeight = FontWeight.Bold, modifier = Modifier.width(110.dp))
        field()
    }
}

@Composable
private fun LabeledField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace),
            colors = fieldColors(),
        )
    }
}

@Composable
private fun ControlRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSecondary)
        Text(value, color = TextPrimary, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MetricCard(label: String, value: String) {
    PanelCard(Modifier.width(170.dp).height(96.dp)) {
        Text(label, color = TextSecondary, fontWeight = FontWeight.Bold)
        Text(value, color = TextPrimary, fontSize = 26.sp, fontFamily = FontFamily.Monospace)
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
    focusedContainerColor = AndyColors.Neutral800,
    unfocusedContainerColor = AndyColors.Neutral800,
    focusedIndicatorColor = AndyColors.OrangeBorder,
    unfocusedIndicatorColor = Border,
    cursorColor = Rust,
)

@Composable
private fun primaryButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = AndyColors.Orange,
    contentColor = Color.White,
    disabledContainerColor = AndyColors.Neutral600,
    disabledContentColor = AndyColors.Neutral400,
)

@Composable
private fun secondaryButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = AndyColors.Neutral800,
    contentColor = TextPrimary,
    disabledContainerColor = AndyColors.Neutral700,
    disabledContentColor = AndyColors.Neutral500,
)
