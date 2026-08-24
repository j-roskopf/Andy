package app.andy.ui.devices
import app.andy.ui.components.Spinner
import app.andy.ui.components.SpinnerSize

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.andy.ui.components.ConfirmationDialog
import app.andy.ui.components.PendingConfirmation
import app.andy.model.AndroidDevice
import app.andy.model.AvdCameraOption
import app.andy.model.AvdCreationConfig
import app.andy.model.AvdProfile
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.DeviceTransport
import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.IosTargetState
import app.andy.model.IosTransport
import app.andy.model.PairedWifiDevice
import app.andy.model.SdkDiscovery
import app.andy.model.SystemImage
import app.andy.model.VirtualDevice
import app.andy.model.VirtualDeviceType
import app.andy.service.AndyServices
import app.andy.service.AvdService
import app.andy.service.CommandResult
import app.andy.service.IosDeviceService
import app.andy.transfer.DeviceTransferCoordinator
import app.andy.ui.components.Button
import app.andy.ui.components.EmptyState
import app.andy.ui.components.FilterPill
import app.andy.ui.components.TabBar
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
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun DevicesScreen(
    services: AndyServices,
    devices: List<AndroidDevice>,
    sdk: SdkDiscovery,
    iosTargets: List<IosTarget> = emptyList(),
    pairedWifiDevices: List<PairedWifiDevice>,
    onRefresh: () -> Unit,
    onLive: (String) -> Unit,
    onEmulatorStarted: (Set<String>, String) -> Unit,
    onBootIosSimulator: (IosTarget) -> Unit,
    onShutdownIosSimulator: (IosTarget) -> Unit,
    onOpenIosInSimulatorApp: (IosTarget) -> Unit,
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
    allowIosManagement: Boolean = false,
    allowWifiPairing: Boolean = true,
    transfer: DeviceTransferCoordinator? = null,
    deviceLabels: Map<String, String> = emptyMap(),
    onSetDeviceLabel: (String, String) -> Unit = { _, _ -> },
    iosDevices: IosDeviceService? = null,
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

    val onlineEmulatorCount = devices.count {
        it.kind == DeviceKind.Emulator && it.state == DeviceConnectionState.Online
    }
    // Re-list AVDs when emulator presence changes. `listVirtualDevices()` sets `running`
    // from adb; without this refresh a stopped emulator can stay `running=true` in local
    // state and disappear from the Created emulators list until a manual Refresh.
    LaunchedEffect(onlineEmulatorCount) {
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
    val filteredIosTargets = iosTargets.filter { target ->
        val matchesQuery = state.deviceQuery.isBlank() ||
            target.displayName.contains(state.deviceQuery, true) ||
            target.udid.contains(state.deviceQuery, true) ||
            target.runtime.orEmpty().contains(state.deviceQuery, true) ||
            target.model.orEmpty().contains(state.deviceQuery, true)
        matchesQuery && target.matchesFilter(state.deviceFilter)
    }
    val iosSimulators = filteredIosTargets.filter { it.kind == IosTargetKind.Simulator }
    val iosPhysicalDevices = filteredIosTargets.filter {
        it.kind == IosTargetKind.Physical && it.state != IosTargetState.Unavailable
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val deviceSummary = when {
            allowIosManagement && state.platformTab == DevicesPlatformTab.Ios -> {
                "${iosSimulators.count { it.state == IosTargetState.Booted }} booted · ${iosSimulators.size} simulators · ${iosPhysicalDevices.size} physical"
            }
            allowAvdManagement -> {
                "${devices.count { it.kind == DeviceKind.Physical }} physical · ${devices.count { it.kind == DeviceKind.Emulator }} emulators online · ${state.avds.size} created"
            }
            else -> "${devices.size} connected over Web ADB"
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
                modifier = Modifier.width(280.dp).defaultMinSize(minHeight = AndyLayout.FieldHeight),
                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace),
                colors = fieldColors(),
            )
            Spacer(Modifier.weight(1f))
            if (state.platformTab == DevicesPlatformTab.Android && allowWifiPairing) {
                OutlinedButton(onClick = { state.showPairDialog = true }) { Text("Pair over Wi‑Fi") }
            }
            if (state.platformTab == DevicesPlatformTab.Android && allowAvdManagement) {
                Button(onClick = { state.showCreateWizard = true }, colors = primaryButtonColors()) {
                    Text(
                        "Create virtual device",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        TabBar(
            tabs = if (allowIosManagement) DevicesPlatformTab.entries else emptyList(),
            selected = state.platformTab,
            onSelect = { state.platformTab = it },
            label = { it.label },
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DeviceListFilter.entries.forEach { filter ->
                        FilterPill(
                            filter.label,
                            state.deviceFilter == filter,
                            if (state.deviceFilter == filter) Rust else Cyan,
                        ) { state.deviceFilter = filter }
                    }
                }
            },
        )
        if (sdk.issues.isNotEmpty() && state.platformTab == DevicesPlatformTab.Android) {
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
        when {
            allowIosManagement && state.platformTab == DevicesPlatformTab.Ios -> IosDevicesTab(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                simulators = iosSimulators,
                physicalDevices = iosPhysicalDevices,
                startStatus = startStatus,
                startingName = startingEmulatorName,
                onBoot = onBootIosSimulator,
                onShutdown = onShutdownIosSimulator,
                onOpenInSimulatorApp = onOpenIosInSimulatorApp,
                onLive = onLive,
                iosDevices = iosDevices,
                state = state,
                scope = scope,
                onRefresh = onRefresh,
            )
            else -> AndroidDevicesTab(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                allowAvdManagement = allowAvdManagement,
                allowWifiPairing = allowWifiPairing,
                devices = devices,
                filteredDevices = filteredDevices,
                filteredAvds = filteredAvds,
                pairedWifiDevices = pairedWifiDevices,
                state = state,
                scope = scope,
                startStatus = startStatus,
                startingEmulatorName = startingEmulatorName,
                stopStatus = stopStatus,
                stoppingEmulatorSerial = stoppingEmulatorSerial,
                onLive = onLive,
                onEmulatorStarted = onEmulatorStarted,
                onStopEmulator = onStopEmulator,
                onReconnectPairedWifi = onReconnectPairedWifi,
                onForgetPairedWifi = onForgetPairedWifi,
                onDisconnectWifi = onDisconnectWifi,
                refreshAvds = ::refreshAvds,
                deviceLabels = deviceLabels,
                onSetDeviceLabel = onSetDeviceLabel,
            )
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

internal enum class DevicesPlatformTab(val label: String) {
    Android("Android"),
    Ios("iOS"),
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

private fun IosTarget.matchesFilter(filter: DeviceListFilter): Boolean = when (filter) {
    DeviceListFilter.All -> true
    DeviceListFilter.Running -> state == IosTargetState.Booted
    DeviceListFilter.Phone -> kind == IosTargetKind.Physical || model.orEmpty().contains("iphone", true)
    DeviceListFilter.Foldable -> model.orEmpty().contains("fold", true)
    DeviceListFilter.Tablet -> model.orEmpty().contains("ipad", true)
    DeviceListFilter.Watch -> model.orEmpty().contains("watch", true)
    DeviceListFilter.Tv -> model.orEmpty().contains("tv", true)
    DeviceListFilter.Api33 -> runtime.orEmpty().contains("26", ignoreCase = true) ||
        runtime.orEmpty().contains("18", ignoreCase = true) ||
        runtime.orEmpty().contains("17", ignoreCase = true)
}

@Composable
private fun AndroidDevicesTab(
    modifier: Modifier,
    allowAvdManagement: Boolean,
    allowWifiPairing: Boolean,
    devices: List<AndroidDevice>,
    filteredDevices: List<AndroidDevice>,
    filteredAvds: List<VirtualDevice>,
    pairedWifiDevices: List<PairedWifiDevice>,
    state: DevicesScreenState,
    scope: CoroutineScope,
    startStatus: String,
    startingEmulatorName: String?,
    stopStatus: String,
    stoppingEmulatorSerial: String?,
    onLive: (String) -> Unit,
    onEmulatorStarted: (Set<String>, String) -> Unit,
    onStopEmulator: (AndroidDevice) -> Unit,
    onReconnectPairedWifi: (PairedWifiDevice) -> Unit,
    onForgetPairedWifi: (String) -> Unit,
    onDisconnectWifi: (String) -> Unit,
    refreshAvds: () -> Unit,
    deviceLabels: Map<String, String> = emptyMap(),
    onSetDeviceLabel: (String, String) -> Unit = { _, _ -> },
) {
    fun avdRunningDevice(avd: VirtualDevice): AndroidDevice? =
        devices.firstOrNull {
            it.kind == DeviceKind.Emulator &&
                it.state == DeviceConnectionState.Online &&
                namesMatch(it.displayName, avd.name)
        }

    val stoppedAvds = filteredAvds.filter { avd ->
        val runningDevice = avdRunningDevice(avd)
        runningDevice == null && !avd.running
    }
    val disconnectedPairedWifi = pairedWifiDevices.filter { paired ->
        findLiveWifiDevice(devices, paired)?.state != DeviceConnectionState.Online
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (filteredDevices.isNotEmpty()) {
            item {
                Text(
                    "Connected devices",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        items(filteredDevices, key = { it.serial }) { device ->
            val online = device.state == DeviceConnectionState.Online
            var labelDialogOpen by remember(device.serial) { mutableStateOf(false) }
            ConnectedDeviceRow(
                title = deviceLabels[device.serial] ?: device.displayName,
                subtitle = device.serial,
                isActive = online,
                statusLabel = device.state.name,
                statusColor = if (online) Green else TextSecondary,
                compactDetails = listOfNotNull(
                    device.apiLevel?.let { "API $it" },
                    device.abi,
                    device.storageSummary,
                ).joinToString(" · ").ifBlank { null },
                apiLevel = device.apiLevel,
                abi = device.abi,
                storageSummary = device.storageSummary,
                titleTrailing = {
                    Text(
                        "· label",
                        color = Cyan,
                        fontSize = 10.sp,
                        modifier = Modifier.clickable { labelDialogOpen = true },
                    )
                },
                extraTags = if (device.transport == DeviceTransport.Wifi) {
                    { StatusTag("Wi‑Fi", Cyan) }
                } else {
                    null
                },
            ) {
                OutlinedButton(onClick = { onLive(device.serial) }) { Text("Live") }
                if (allowAvdManagement && device.kind == DeviceKind.Emulator && online) {
                    OutlinedButton(
                        onClick = { onStopEmulator(device) },
                        enabled = stoppingEmulatorSerial != device.serial,
                    ) {
                        Text(if (stoppingEmulatorSerial == device.serial) "Stopping" else "Stop")
                    }
                }
            }
            if (labelDialogOpen) {
                SetLabelDialog(
                    currentLabel = deviceLabels[device.serial].orEmpty(),
                    subtitle = device.serial,
                    onDismiss = { labelDialogOpen = false },
                    onSave = { label ->
                        onSetDeviceLabel(device.serial, label)
                        labelDialogOpen = false
                    },
                )
            }
        }
        if (allowAvdManagement) {
            item {
                Text(
                    "Created emulators",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = if (filteredDevices.isNotEmpty()) 8.dp else 0.dp),
                )
            }
            if (startStatus.isNotBlank()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (startingEmulatorName != null) {
                            Spinner(spinnerSize = SpinnerSize.Md)
                        }
                        Text(
                            startStatus,
                            color = if (startingEmulatorName != null) Rust else TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            if (state.avdStatus.isNotBlank()) {
                item {
                    Text(state.avdStatus, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
            if (stopStatus.isNotBlank()) {
                item {
                    Text(stopStatus, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
            if (filteredAvds.isEmpty()) {
                item {
                    Text(
                        "No AVDs found. Create one in Catalog or Android Studio, then refresh.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
            items(stoppedAvds, key = { it.name }) { avd ->
                val runningDevice = avdRunningDevice(avd)
                val isRunning = runningDevice != null || avd.running
                VirtualDeviceRow(
                    name = avd.name,
                    subtitle = listOfNotNull(avd.target, avd.abi, avd.path).joinToString(" · ").ifBlank { "AVD" },
                    detail = avd.graphicsBackend?.let { backend ->
                        listOfNotNull(
                            "Graphics: $backend",
                            avd.graphicsRenderer,
                            "software renderer".takeIf { avd.graphicsSoftwareRendered },
                        ).joinToString(" · ")
                    },
                    statusLabel = if (isRunning) "running" else "stopped",
                    statusColor = if (isRunning) Green else TextSecondary,
                    typeLabel = avd.deviceType.name.lowercase(),
                ) {
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
                            state.pendingConfirmation = PendingConfirmation("Delete ${avd.name}?", "This permanently removes the AVD and its files from disk.") {
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
        if (allowWifiPairing && disconnectedPairedWifi.isNotEmpty()) {
            item {
                Text(
                    "Wireless devices",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (state.wifiStatus.isNotBlank()) {
                item {
                    Text(state.wifiStatus, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
            items(disconnectedPairedWifi, key = { it.id }) { paired ->
                val live = findLiveWifiDevice(devices, paired)
                val online = live?.state == DeviceConnectionState.Online
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .background(PanelSoft, RoundedCornerShape(AndyRadius.Row))
                        .border(1.dp, Border, RoundedCornerShape(AndyRadius.Row))
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
        if (filteredDevices.isEmpty() && (!allowAvdManagement || filteredAvds.isEmpty()) && pairedWifiDevices.isEmpty()) {
            item {
                EmptyState(
                    if (allowAvdManagement) {
                        "No connected Android devices. Connect USB debugging, pair over Wi‑Fi, or start an emulator."
                    } else {
                        "No browser-authorized Android devices. Use ADB + WebSocket or WebUSB to connect."
                    },
                )
            }
        }
    }
}

@Composable
private fun IosDevicesTab(
    modifier: Modifier,
    simulators: List<IosTarget>,
    physicalDevices: List<IosTarget>,
    startStatus: String,
    startingName: String?,
    onBoot: (IosTarget) -> Unit,
    onShutdown: (IosTarget) -> Unit,
    onOpenInSimulatorApp: (IosTarget) -> Unit,
    onLive: (String) -> Unit,
    iosDevices: IosDeviceService? = null,
    state: DevicesScreenState? = null,
    scope: CoroutineScope? = null,
    onRefresh: () -> Unit = {},
) {
    val connectedTargets = buildList {
        addAll(physicalDevices)
        addAll(simulators.filter { it.state == IosTargetState.Booted })
    }
    val stoppedSimulators = simulators.filter { it.state != IosTargetState.Booted }
    var reclaiming by remember { mutableStateOf(false) }

    fun runIosAction(label: String, block: suspend () -> CommandResult) {
        val runScope = scope ?: return
        runScope.launch {
            val result = block()
            state?.iosStatus = if (result.isSuccess) result.stdout.ifBlank { label } else result.stderr.ifBlank { result.stdout }.ifBlank { "$label failed" }
            onRefresh()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (connectedTargets.isNotEmpty()) {
            item {
                Text(
                    "Connected devices",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(connectedTargets, key = { it.udid }) { target ->
                IosConnectedTargetRow(
                    target = target,
                    starting = startingName == target.displayName,
                    onBoot = { onBoot(target) },
                    onShutdown = { onShutdown(target) },
                    onOpenInSimulatorApp = { onOpenInSimulatorApp(target) },
                    onLive = { onLive(target.udid) },
                    iosDevices = iosDevices,
                    state = state,
                    onClone = { state?.iosCloneSource = target },
                    onRename = { state?.iosRenameSource = target },
                    onErase = {
                        state?.pendingConfirmation = PendingConfirmation("Erase ${target.displayName}?", "This resets the simulator to a factory state.") {
                            runIosAction("Erase") { iosDevices?.eraseSimulator(target.udid) ?: CommandResult.failure("iOS device service unavailable") }
                        }
                    },
                    onDelete = {
                        state?.pendingConfirmation = PendingConfirmation("Delete ${target.displayName}?", "This permanently removes the simulator and its files from disk.") {
                            runIosAction("Delete") { iosDevices?.deleteSimulator(target.udid) ?: CommandResult.failure("iOS device service unavailable") }
                        }
                    },
                )
            }
        }
        item {
            Text(
                "Simulators",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = if (connectedTargets.isNotEmpty()) 8.dp else 0.dp),
            )
        }
        if (startStatus.isNotBlank()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (startingName != null) {
                        Spinner(spinnerSize = SpinnerSize.Md)
                    }
                    Text(
                        startStatus,
                        color = if (startingName != null) Rust else TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        if (state != null && state.iosStatus.isNotBlank()) {
            item { Text(state.iosStatus, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        }
        if (stoppedSimulators.isEmpty()) {
            item {
                Text(
                    if (simulators.isEmpty()) {
                        "No iOS simulators found. Install an iOS runtime in Xcode, then refresh."
                    } else {
                        "All simulators are booted."
                    },
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
        } else {
            items(stoppedSimulators, key = { it.udid }) { target ->
                IosSimulatorRow(
                    target = target,
                    starting = startingName == target.displayName,
                    onBoot = { onBoot(target) },
                    onShutdown = { onShutdown(target) },
                    onOpenInSimulatorApp = { onOpenInSimulatorApp(target) },
                    onLive = { onLive(target.udid) },
                    iosDevices = iosDevices,
                    state = state,
                    onClone = { state?.iosCloneSource = target },
                    onRename = { state?.iosRenameSource = target },
                    onErase = {
                        state?.pendingConfirmation = PendingConfirmation("Erase ${target.displayName}?", "This resets the simulator to a factory state.") {
                            runIosAction("Erase") { iosDevices?.eraseSimulator(target.udid) ?: CommandResult.failure("iOS device service unavailable") }
                        }
                    },
                    onDelete = {
                        state?.pendingConfirmation = PendingConfirmation("Delete ${target.displayName}?", "This permanently removes the simulator and its files from disk.") {
                            runIosAction("Delete") { iosDevices?.deleteSimulator(target.udid) ?: CommandResult.failure("iOS device service unavailable") }
                        }
                    },
                )
            }
        }
        if (iosDevices != null) {
            item {
                PanelCard(modifier = Modifier.padding(top = 8.dp)) {
                    Text("Disk reclaim", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        "Simulator runtimes can eat tens of GB. Clean up unavailable simulators or runtimes you haven't booted recently.",
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                reclaiming = true
                                runIosAction("Delete unavailable simulators") {
                                    val result = iosDevices.deleteUnavailableSimulators()
                                    reclaiming = false
                                    result
                                }
                            },
                            enabled = !reclaiming,
                        ) { Text("Delete unavailable simulators") }
                        OutlinedButton(
                            onClick = {
                                reclaiming = true
                                runIosAction("Delete unused runtimes (30+ days)") {
                                    val result = iosDevices.deleteUnusedRuntimes(30)
                                    reclaiming = false
                                    result
                                }
                            },
                            enabled = !reclaiming,
                        ) { Text("Delete unused runtimes (30+ days)") }
                    }
                }
            }
        }
    }
    state?.iosCloneSource?.let { source ->
        CloneIosDialog(
            source = source,
            onDismiss = { state.iosCloneSource = null },
            onClone = { newName ->
                state.iosCloneSource = null
                runIosAction("Clone") { iosDevices?.cloneSimulator(source.udid, newName) ?: CommandResult.failure("iOS device service unavailable") }
            },
        )
    }
    state?.iosRenameSource?.let { source ->
        RenameIosDialog(
            source = source,
            onDismiss = { state.iosRenameSource = null },
            onRename = { newName ->
                state.iosRenameSource = null
                runIosAction("Rename") { iosDevices?.renameSimulator(source.udid, newName) ?: CommandResult.failure("iOS device service unavailable") }
            },
        )
    }
}

@Composable
private fun IosActionsMenu(
    enabled: Boolean,
    onClone: () -> Unit,
    onRename: () -> Unit,
    onErase: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.width(42.dp), contentPadding = PaddingValues(0.dp)) {
            Text("...")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = PanelSoft) {
            DropdownMenuItem(text = { Text("Clone", color = TextPrimary) }, onClick = { expanded = false; onClone() })
            DropdownMenuItem(text = { Text("Rename", color = TextPrimary) }, onClick = { expanded = false; onRename() })
            DropdownMenuItem(text = { Text("Erase", color = TextPrimary) }, onClick = { expanded = false; onErase() })
            DropdownMenuItem(text = { Text("Delete", color = Red) }, onClick = { expanded = false; onDelete() })
        }
    }
}

@Composable
private fun CloneIosDialog(source: IosTarget, onDismiss: () -> Unit, onClone: (String) -> Unit) {
    var name by remember(source.udid) { mutableStateOf("${source.displayName} Copy") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Clone ${source.displayName}", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            LabeledField("New name", name, { name = it }, Modifier.fillMaxWidth())
        },
        confirmButton = {
            Button(onClick = { onClone(name) }, enabled = name.isNotBlank(), colors = primaryButtonColors()) { Text("Clone") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameIosDialog(source: IosTarget, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember(source.udid) { mutableStateOf(source.displayName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Rename ${source.displayName}", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            LabeledField("New name", name, { name = it }, Modifier.fillMaxWidth())
        },
        confirmButton = {
            Button(onClick = { onRename(name) }, enabled = name.isNotBlank(), colors = primaryButtonColors()) { Text("Rename") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun IosConnectedTargetRow(
    target: IosTarget,
    starting: Boolean,
    onBoot: () -> Unit,
    onShutdown: () -> Unit,
    onOpenInSimulatorApp: () -> Unit,
    onLive: () -> Unit,
    iosDevices: IosDeviceService? = null,
    state: DevicesScreenState? = null,
    onClone: () -> Unit = {},
    onRename: () -> Unit = {},
    onErase: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val booted = target.state == IosTargetState.Booted
    val liveReady = target.isLiveReady
    val statusLabel = iosTargetStatusLabel(target)
    val statusColor = iosTargetStatusColor(target)
    ConnectedDeviceRow(
        title = target.displayName,
        subtitle = target.udid,
        isActive = liveReady,
        statusLabel = statusLabel,
        statusColor = statusColor,
        compactDetails = listOfNotNull(target.runtime, target.model).joinToString(" · ").ifBlank { null },
        apiLevel = target.runtime,
        abi = target.model,
    ) {
        if (target.kind == IosTargetKind.Simulator) {
            when {
                booted -> {
                    OutlinedButton(onClick = onLive, enabled = !starting) { Text("Live") }
                    OutlinedButton(onClick = onOpenInSimulatorApp, enabled = !starting) { Text("Simulator.app") }
                    OutlinedButton(onClick = onShutdown, enabled = !starting) { Text("Shutdown") }
                }
                target.state == IosTargetState.Shutdown -> {
                    OutlinedButton(onClick = onBoot, enabled = !starting) {
                        Text(if (starting) "Booting" else "Boot")
                    }
                }
            }
            if (iosDevices != null) {
                IosActionsMenu(enabled = !starting, onClone = onClone, onRename = onRename, onErase = onErase, onDelete = onDelete)
            }
        } else if (target.isMirrorable) {
            OutlinedButton(onClick = onLive) { Text("Live") }
        } else if (target.transport != IosTransport.Usb) {
            Text("USB required", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}

@Composable
private fun IosSimulatorRow(
    target: IosTarget,
    starting: Boolean,
    onBoot: () -> Unit,
    onShutdown: () -> Unit,
    onOpenInSimulatorApp: () -> Unit,
    onLive: () -> Unit,
    iosDevices: IosDeviceService? = null,
    state: DevicesScreenState? = null,
    onClone: () -> Unit = {},
    onRename: () -> Unit = {},
    onErase: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val booted = target.state == IosTargetState.Booted
    val statusLabel = iosTargetStatusLabel(target)
    val statusColor = iosTargetStatusColor(target)
    VirtualDeviceRow(
        name = target.displayName,
        subtitle = listOfNotNull(target.runtime, target.model, target.udid).joinToString(" · "),
        statusLabel = statusLabel,
        statusColor = statusColor,
        typeLabel = iosTargetTypeLabel(target),
    ) {
        when {
            booted -> {
                OutlinedButton(onClick = onLive, enabled = !starting) { Text("Live") }
                OutlinedButton(onClick = onOpenInSimulatorApp, enabled = !starting) { Text("Simulator.app") }
                OutlinedButton(onClick = onShutdown, enabled = !starting) { Text("Shutdown") }
            }
            target.state == IosTargetState.Shutdown -> {
                OutlinedButton(onClick = onBoot, enabled = !starting) {
                    Text(if (starting) "Booting" else "Boot")
                }
            }
        }
        if (iosDevices != null) {
            IosActionsMenu(enabled = !starting, onClone = onClone, onRename = onRename, onErase = onErase, onDelete = onDelete)
        }
    }
}

private fun iosTargetStatusLabel(target: IosTarget): String = when {
    target.kind == IosTargetKind.Physical && target.transport != IosTransport.Usb -> "network only"
    target.kind == IosTargetKind.Physical -> "online"
    target.state == IosTargetState.Unavailable -> "unavailable"
    target.state == IosTargetState.Booted -> "booted"
    target.state == IosTargetState.Shutdown -> "shutdown"
    else -> target.state.name.lowercase()
}

private fun iosTargetStatusColor(target: IosTarget): Color = when {
    target.isLiveReady -> Green
    target.state == IosTargetState.Unavailable -> TextSecondary
    target.kind == IosTargetKind.Physical && target.transport != IosTransport.Usb -> Yellow
    else -> TextSecondary
}

private fun iosTargetTypeLabel(target: IosTarget): String = when (target.kind) {
    IosTargetKind.Simulator -> "simulator"
    IosTargetKind.Physical -> target.model?.substringBefore(" ")?.lowercase() ?: "device"
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
private fun SetLabelDialog(currentLabel: String, subtitle: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var label by remember(currentLabel) { mutableStateOf(currentLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Set label", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(subtitle, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                LabeledField("Friendly name", label, { label = it }, Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = { onSave(label) }, colors = primaryButtonColors()) { Text("Save") }
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        PanelCard(
            modifier = Modifier.width(820.dp),
            contentPadding = PaddingValues(AndySpace.Space7),
            verticalArrangement = Arrangement.spacedBy(AndySpace.Space5),
        ) {
            Text("Create virtual device", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Column(Modifier.heightIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill("Profile", step == 1, Rust) { step = 1 }
                    FilterPill("Image", step == 2, Rust) { step = 2 }
                    FilterPill("Configure", step == 3, Rust) { step = 3 }
                }
                Text(status, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                when (step) {
                    1 -> LazyColumn(Modifier.height(420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        profiles.groupBy { it.category }.entries.sortedBy { it.key.ordinal }.forEach { (category, rows) ->
                            item { Text(category.name, color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp) }
                            items(rows) { profile ->
                                TableRow(Modifier.clickable {
                                    selectedProfile = profile
                                    name = profile.name.replace(Regex("""\W+"""), "_")
                                }) {
                                    MonoCell(profile.name, 180.dp, if (profile == selectedProfile) Rust else TextPrimary)
                                    MonoCell(profile.resolution ?: "-", 120.dp, TextSecondary)
                                    MonoCell(profile.density ?: "-", 72.dp, TextSecondary)
                                    MonoCell(profile.id, 1.dp, TextSecondary, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    2 -> LazyColumn(Modifier.height(420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(images.take(240)) { image ->
                            SystemImagePickerRow(
                                image = image,
                                selected = image == selectedImage,
                                onClick = { selectedImage = image },
                            )
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (step > 1) OutlinedButton(onClick = { step-- }) { Text("Back") }
                if (step < 3) OutlinedButton(onClick = { step++ }) { Text("Next") }
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
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
                    Text("Create")
                }
            }
        }
    }
}

@Composable
private fun SystemImagePickerRow(
    image: SystemImage,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(AndyColors.Neutral900.copy(alpha = 0.72f))
            .border(1.dp, Color.White.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "API ${image.api}",
                modifier = Modifier.width(72.dp),
                color = if (selected) Rust else TextPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                image.variant,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                color = TextPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                image.abi,
                modifier = Modifier.width(100.dp),
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (image.installed) "Installed" else "Available",
                modifier = Modifier.width(88.dp),
                color = if (image.installed) Green else TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        Text(
            image.packageId,
            color = TextSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
