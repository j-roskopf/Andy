package app.andy.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.components.ConfirmationDialog
import app.andy.ui.components.PendingConfirmation
import app.andy.model.AndroidDevice
import app.andy.model.AvdCameraOption
import app.andy.model.AvdCreationConfig
import app.andy.model.AvdProfile
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.DeviceTransport
import app.andy.model.PairedWifiDevice
import app.andy.model.SdkDiscovery
import app.andy.model.SystemImage
import app.andy.model.VirtualDevice
import app.andy.model.VirtualDeviceType
import app.andy.service.AndyServices
import app.andy.service.AvdService
import app.andy.ui.components.Button
import app.andy.ui.components.EmptyState
import app.andy.ui.components.FilterPill
import app.andy.ui.components.LabeledField
import app.andy.ui.components.MonoCell
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PanelCard
import app.andy.ui.components.StatusTag
import app.andy.ui.components.TableRow
import app.andy.ui.components.TextField
import app.andy.ui.components.Toolbar
import app.andy.ui.components.fieldColors
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Green
import app.andy.ui.theme.Panel
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Yellow
import kotlinx.coroutines.launch

@Composable
internal fun DevicesScreen(
    services: AndyServices,
    devices: List<AndroidDevice>,
    sdk: SdkDiscovery,
    pairedWifiDevices: List<PairedWifiDevice>,
    onRefresh: () -> Unit,
    onLive: (String) -> Unit,
    onEmulatorStarted: (Set<String>, String) -> Unit,
    onStopEmulator: (AndroidDevice) -> Unit,
    stoppingEmulatorSerial: String?,
    stopStatus: String,
    startingEmulatorName: String?,
    startStatus: String,
    onSavePairedWifi: (PairedWifiDevice) -> Unit,
    onForgetPairedWifi: (String) -> Unit,
    onReconnectPairedWifi: (PairedWifiDevice) -> Unit,
    onDisconnectWifi: (String) -> Unit,
    allowAvdManagement: Boolean = true,
    allowWifiPairing: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val state = remember(services.avd) { DevicesScreenState(services.avd) }
    val webConnection = services.web?.connection?.state?.collectAsState()?.value

    fun refreshAvds() {
        if (!allowAvdManagement) return
        scope.launch {
            state.avds = state.avd.listVirtualDevices()
        }
    }

    LaunchedEffect(Unit) {
        refreshAvds()
    }
    val filteredDevices = devices.filter { device ->
        val matchesQuery = state.deviceQuery.isBlank() ||
            device.displayName.contains(state.deviceQuery, true) ||
            device.serial.contains(state.deviceQuery, true) ||
            device.apiLevel.orEmpty().contains(state.deviceQuery, true)
        matchesQuery && device.matchesFilter(state.deviceFilter)
    }
    val filteredAvds = state.avds.filter { avd ->
        val matchesQuery = state.deviceQuery.isBlank() ||
            avd.name.contains(state.deviceQuery, true) ||
            avd.target.orEmpty().contains(state.deviceQuery, true) ||
            avd.abi.orEmpty().contains(state.deviceQuery, true)
        matchesQuery && avd.matchesFilter(state.deviceFilter)
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val deviceSummary = if (allowAvdManagement) {
            "${devices.count { it.kind == DeviceKind.Physical }} physical · ${devices.count { it.kind == DeviceKind.Emulator }} emulators online · ${state.avds.size} created"
        } else {
            "${devices.size} connected over Web ADB"
        }
        Toolbar("Devices", deviceSummary, onPrimary = {
            onRefresh()
            refreshAvds()
        }, primaryLabel = "Refresh")
        if (services.web != null && webConnection != null) {
            PanelCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Web ADB connection", color = TextPrimary, fontWeight = FontWeight.Bold)
                        Text(webConnection.status, color = if (webConnection.connected) Green else TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                    Button(
                        onClick = { scope.launch { services.web.connection.connectWebSocket(); onRefresh() } },
                        enabled = !webConnection.connecting,
                    ) { Text("Use ADB + WebSocket") }
                    OutlinedButton(
                        onClick = { scope.launch { services.web.connection.requestWebUsb(); onRefresh() } },
                        enabled = !webConnection.connecting,
                    ) { Text("Use WebUSB") }
                    OutlinedButton(
                        onClick = { scope.launch { services.web.connection.retry(); onRefresh() } },
                        enabled = !webConnection.connecting,
                    ) { Text("Retry now") }
                }
                webConnection.error?.let { error ->
                    SelectionContainer {
                        Text(error, color = Rust, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                state.deviceQuery,
                { state.deviceQuery = it },
                placeholder = { Text("Search devices", color = TextSecondary) },
                singleLine = true,
                modifier = Modifier.width(280.dp).height(54.dp),
                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace),
                colors = fieldColors(),
            )
            DeviceListFilter.entries.forEach { filter ->
                FilterPill(filter.label, state.deviceFilter == filter, if (state.deviceFilter == filter) Rust else Cyan) { state.deviceFilter = filter }
            }
            Spacer(Modifier.weight(1f))
            if (allowWifiPairing) {
                OutlinedButton(onClick = { state.showPairDialog = true }) { Text("Pair over Wi‑Fi") }
            }
            if (allowAvdManagement) {
                Button(onClick = { state.showCreateWizard = true }, colors = primaryButtonColors()) { Text("Create virtual device") }
            }
        }
        if (sdk.issues.isNotEmpty()) {
            PanelCard {
                Text("SDK setup", color = TextPrimary, fontWeight = FontWeight.Bold)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        sdk.issues.forEach { Text(it, color = TextSecondary, fontSize = 12.sp) }
                        Text("SDK: ${sdk.sdkPath ?: "-"}", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }
        if (allowAvdManagement) PanelCard {
            Text("Created emulators", color = TextPrimary, fontWeight = FontWeight.Bold)
            if (startStatus.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (startingEmulatorName != null) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Rust)
                    Text(startStatus, color = if (startingEmulatorName != null) Rust else TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
            if (state.avdStatus.isNotBlank()) Text(state.avdStatus, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
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
                                        state.startingAvd = avd.name
                                        val result = state.avd.startVirtualDevice(avd.name)
                                        state.avdStatus = if (result.isSuccess) result.stdout else result.stderr.ifBlank { result.stdout }
                                        state.startingAvd = null
                                        refreshAvds()
                                        if (result.isSuccess) onEmulatorStarted(before, avd.name)
                                    }
                                },
                                enabled = state.startingAvd == null && startingEmulatorName == null,
                            ) {
                                Text(
                                    when {
                                        startingEmulatorName == avd.name -> "Booting"
                                        state.startingAvd == avd.name -> "Starting"
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
                                enabled = state.startingAvd == null && startingEmulatorName == null,
                                onColdBoot = {
                                    val before = devices.map { it.serial }.toSet()
                                    scope.launch {
                                        state.startingAvd = avd.name
                                        val result = state.avd.coldBootVirtualDevice(avd.name)
                                        state.avdStatus = if (result.isSuccess) result.stdout else result.stderr.ifBlank { result.stdout }
                                        state.startingAvd = null
                                        refreshAvds()
                                        if (result.isSuccess) onEmulatorStarted(before, avd.name)
                                    }
                                },
                                onWipe = {
                                    state.pendingConfirmation = PendingConfirmation("Wipe ${avd.name}?", "This erases user data for the virtual device.") {
                                        scope.launch {
                                            val result = state.avd.wipeVirtualDevice(avd.name)
                                            state.avdStatus = if (result.isSuccess) result.stdout else result.stderr.ifBlank { result.stdout }
                                            refreshAvds()
                                        }
                                    }
                                },
                                onClone = { state.cloneSource = avd },
                                onDelete = {
                                    state.pendingConfirmation = PendingConfirmation("Delete ${avd.name}?", "This removes the AVD from Android SDK device manager.") {
                                        scope.launch {
                                            val result = state.avd.deleteVirtualDevice(avd.name)
                                            state.avdStatus = if (result.isSuccess) result.stdout.ifBlank { "Deleted ${avd.name}" } else result.stderr.ifBlank { result.stdout }
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
        if (allowWifiPairing) PanelCard {
            Text("Wireless devices", color = TextPrimary, fontWeight = FontWeight.Bold)
            if (state.wifiStatus.isNotBlank()) {
                Text(state.wifiStatus, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            if (pairedWifiDevices.isEmpty()) {
                Text("No remembered Wi‑Fi devices. Use Pair over Wi‑Fi to add one.", color = TextSecondary, fontSize = 12.sp)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pairedWifiDevices.forEach { paired ->
                        val live = findLiveWifiDevice(devices, paired)
                        val online = live?.state == DeviceConnectionState.Online
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
                                Text(paired.displayName, color = TextPrimary, fontWeight = FontWeight.Bold)
                                Text(
                                    listOfNotNull(paired.mdnsInstanceName, paired.lastEndpoint, live?.serial)
                                        .distinct()
                                        .joinToString(" · "),
                                    color = TextSecondary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            StatusTag(if (online) "connected" else "disconnected", if (online) Green else TextSecondary)
                            if (live != null && online) {
                                OutlinedButton(onClick = { onLive(live.serial) }) { Text("Live") }
                                OutlinedButton(onClick = {
                                    state.wifiStatus = "Disconnecting ${live.serial}..."
                                    onDisconnectWifi(live.serial)
                                }) { Text("Disconnect") }
                            } else {
                                OutlinedButton(onClick = {
                                    state.wifiStatus = "Reconnecting ${paired.displayName}..."
                                    onReconnectPairedWifi(paired)
                                }) { Text("Reconnect") }
                            }
                            OutlinedButton(onClick = {
                                state.pendingConfirmation = PendingConfirmation(
                                    "Forget ${paired.displayName}?",
                                    "Removes this device from Andy's remembered Wi‑Fi list. It does not unpair on the phone.",
                                ) {
                                    onForgetPairedWifi(paired.id)
                                    state.wifiStatus = "Forgot ${paired.displayName}"
                                }
                            }) { Text("Forget") }
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
                        .height(IntrinsicSize.Min)
                        .heightIn(min = 76.dp)
                        .background(if (online) AndyColors.GreenSubtle.copy(alpha = 0.82f) else AndyColors.Neutral900.copy(alpha = 0.7f), rowShape)
                        .border(1.dp, if (online) Green.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.05f), rowShape)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
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
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusTag(device.state.name, if (online) Green else TextSecondary)
                        if (device.transport == DeviceTransport.Wifi) {
                            StatusTag("Wi‑Fi", Cyan)
                        }
                    }
                    OutlinedButton(onClick = { onLive(device.serial) }) { Text("Live") }
                    if (allowAvdManagement && device.kind == DeviceKind.Emulator && online) {
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
        if (devices.isEmpty()) {
            EmptyState(if (allowAvdManagement) "No connected Android devices. Connect USB debugging, pair over Wi‑Fi, or start an emulator." else "No browser-authorized Android devices. Use ADB + WebSocket or WebUSB to connect.")
        }
        if (allowAvdManagement && state.showCreateWizard) {
            CreateVirtualDeviceDialog(
                avd = state.avd,
                onDismiss = { state.showCreateWizard = false },
                onCreated = {
                    state.avdStatus = it
                    state.showCreateWizard = false
                    refreshAvds()
                    onRefresh()
                },
            )
        }
        if (allowWifiPairing && state.showPairDialog) {
            PairOverWifiDialog(
                devices = services.devices,
                onDismiss = { state.showPairDialog = false },
                onPaired = { paired, message ->
                    onSavePairedWifi(paired)
                    state.wifiStatus = message
                    state.showPairDialog = false
                    onRefresh()
                },
            )
        }
        state.cloneSource?.let { source ->
            CloneAvdDialog(
                source = source,
                onDismiss = { state.cloneSource = null },
                onClone = { newName ->
                    scope.launch {
                        val result = state.avd.cloneVirtualDevice(source.name, newName)
                        state.avdStatus = if (result.isSuccess) result.stdout else result.stderr.ifBlank { result.stdout }
                        state.cloneSource = null
                        refreshAvds()
                    }
                },
            )
        }
        state.pendingConfirmation?.let { confirmation ->
            ConfirmationDialog(confirmation, onDismiss = { state.pendingConfirmation = null }, onConfirm = {
                state.pendingConfirmation = null
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

internal enum class DeviceListFilter(val label: String) {
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
                        profiles.groupBy { it.category }.entries.sortedBy { it.key.ordinal }.forEach { (category, rows) ->
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
