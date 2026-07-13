package app.andy.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import app.andy.AndyDestination
import app.andy.service.AndyServices
import app.andy.ui.accessibility.AccessibilityScreen
import app.andy.ui.actions.ActionsScreen
import app.andy.ui.agents.AgentsScreen
import app.andy.ui.apps.AppsScreen
import app.andy.ui.bugs.BugsScreen
import app.andy.ui.catalog.CatalogScreen
import app.andy.ui.components.ConfirmationDialog
import app.andy.ui.components.FilterPill
import app.andy.ui.components.PendingConfirmation
import app.andy.ui.components.noiseGridOverlay
import app.andy.ui.controls.ControlsScreen
import app.andy.ui.design.DesignScreen
import app.andy.ui.devices.DevicesScreen
import app.andy.ui.files.FilesScreen
import app.andy.ui.hostfiles.HostFilesScreen
import app.andy.ui.intents.IntentsScreen
import app.andy.ui.live.LiveScreen
import app.andy.ui.logcat.LogcatScreen
import app.andy.ui.network.NetworkScreen
import app.andy.model.ProxyStartOptions
import app.andy.ui.network.shouldAutoStartProxy
import app.andy.ui.performance.PerformanceScreen
import app.andy.ui.settings.SettingsScreen
import app.andy.ui.settings.UpdateInstallConfirmationDialog
import app.andy.ui.snapshots.SnapshotsScreen
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Ink
import app.andy.ui.theme.Rust
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

@Composable
internal fun AndyShell(
    services: AndyServices,
    requestedDestination: AndyDestination?,
    onDestinationConsumed: () -> Unit,
    onPopOutMirror: (String?, String?) -> Unit,
    contentTopPadding: androidx.compose.ui.unit.Dp,
) {
    val state = rememberShellState(services)
    val runningActions by services.actionRuns.running.collectAsState()
    val agentTasks by services.agentRuns.tasks.collectAsState()
    val pendingUpdateInstallConfirmation by services.updates.pendingInstallConfirmation.collectAsState()

    LaunchedEffect(Unit) {
        state.initialize()
    }

    val runningActionIds = remember(runningActions) { runningActions.map { it.runId } }
    LaunchedEffect(runningActionIds) {
        state.syncActiveRun(runningActions)
    }

    LaunchedEffect(requestedDestination) {
        requestedDestination?.let {
            state.setDestination(it)
            onDestinationConsumed()
        }
    }

    LaunchedEffect(state.workspaceState.mcpServerEnabled, state.workspaceState.mcpServerPort) {
        if (state.workspaceState.mcpServerEnabled) {
            services.mcp.start(state.workspaceState.mcpServerPort)
        } else {
            services.mcp.stop()
        }
    }

    LaunchedEffect(state.workspaceLoaded, state.workspaceState.proxyStartOnLaunch, state.workspaceState.proxyPort, state.workspaceState.proxyRules, state.workspaceState.proxySslInsecure, state.workspaceState.proxyUpstreamTrustedCaPath) {
        if (!state.workspaceLoaded || !state.workspaceState.proxyStartOnLaunch) return@LaunchedEffect
        val currentStatus = try {
            withTimeout(200) { services.proxy.status.first() }
        } catch (_: Exception) {
            "Proxy stopped"
        }
        if (shouldAutoStartProxy(currentStatus, state.workspaceState.proxyPort)) {
            services.proxy.ensureCertificateAuthority()
            services.proxy.start(
                state.workspaceState.proxyPort,
                state.workspaceState.proxyRules,
                ProxyStartOptions(
                    sslInsecure = state.workspaceState.proxySslInsecure,
                    upstreamTrustedCaPath = state.workspaceState.proxyUpstreamTrustedCaPath,
                ),
            )
        }
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
                current = state.destination,
                deviceCount = state.devices.size,
                // Project chats are owned by Actions. Keep their unread state out of
                // the standalone Agent destination.
                hasUnreadAgentTasks = agentTasks.any { it.unread && it.projectId == null },
                hasUnreadProjectAgentTasks = agentTasks.any { it.unread && it.projectId != null },
                onSelect = state::setDestination,
                expanded = state.workspaceState.workspaceSidebarExpanded,
                onExpandedChange = { expanded -> state.updateWorkspace { it.copy(workspaceSidebarExpanded = expanded) } },
                sdk = state.sdk,
                updates = services.updates,
                mcpRunning = mcpRunning,
                mcpPort = state.workspaceState.mcpServerPort
            )
            Column(Modifier.fillMaxSize().padding(start = 10.dp)) {
                TopChrome(
                    destination = state.destination,
                    selectedDevice = state.devices.firstOrNull { it.serial == state.selectedSerial },
                    devices = state.devices,
                    onSelectDevice = { state.selectDevice(it) },
                    onRefresh = { state.refreshDevices() },
                    onStopEmulator = { state.stopEmulator(it) },
                    stoppingEmulatorSerial = state.stoppingEmulatorSerial,
                    actionConfig = state.actionsConfig,
                    onRunAction = { project, action -> state.runAction(project, action) },
                    proxyRunning = proxyRunning,
                    actions = {
                        if (state.destination == AndyDestination.Network) {
                            FilterPill("Rules", state.networkRulesVisible, Rust, toolbar = true) { state.toggleNetworkRulesVisible() }
                            Spacer(Modifier.width(8.dp))
                            FilterPill("Live", state.networkLiveVisible, Cyan, toolbar = true) { state.toggleNetworkLiveVisible() }
                            Spacer(Modifier.width(10.dp))
                        } else if (state.destination == AndyDestination.Performance) {
                            FilterPill("Live", state.performanceLiveVisible, Cyan, toolbar = true) { state.togglePerformanceLiveVisible() }
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
                    val actionsActive = state.destination == AndyDestination.Actions
                    val agentsActive = state.destination == AndyDestination.Agents
                    RetainedDestination(active = actionsActive) {
                        ActionsScreen(
                            services = services,
                            config = state.actionsConfig,
                            running = runningActions,
                            activeRunId = state.activeRunId,
                            terminalRunId = state.terminalRunId,
                            onActiveRunIdChange = { state.setActiveRunId(it) },
                            onConfigChange = { state.persistActionsConfig(it) },
                            agentTasks = agentTasks,
                            active = actionsActive,
                        )
                    }
                    RetainedDestination(active = agentsActive) {
                        AgentsScreen(services = services, active = agentsActive)
                    }
                    when (state.destination) {
                        AndyDestination.Devices -> DevicesScreen(
                            services,
                            state.devices,
                            state.sdk,
                            pairedWifiDevices = state.workspaceState.pairedWifiDevices,
                            onRefresh = { state.refreshDevices() },
                            onLive = { state.openLive(it) },
                            onEmulatorStarted = { previousSerials, avdName ->
                                state.openStartedEmulator(previousSerials, avdName)
                            },
                            onStopEmulator = { state.stopEmulator(it) },
                            stoppingEmulatorSerial = state.stoppingEmulatorSerial,
                            stopStatus = state.emulatorStopStatus,
                            startingEmulatorName = state.startingEmulatorName,
                            startStatus = state.emulatorStartStatus,
                            onSavePairedWifi = state::savePairedWifi,
                            onForgetPairedWifi = state::forgetPairedWifi,
                            onReconnectPairedWifi = state::reconnectPairedWifi,
                            onDisconnectWifi = state::disconnectWifi,
                        )
                        AndyDestination.Catalog -> CatalogScreen(services.avd)
                        AndyDestination.Live -> LiveScreen(
                            services = services,
                            serial = state.selectedSerial,
                            device = state.devices.firstOrNull { it.serial == state.selectedSerial },
                            devicePaneWidth = state.workspaceState.liveDevicePaneWidth,
                            controlsPaneHeight = state.workspaceState.liveControlsPaneHeight,
                            onStopEmulator = { state.stopEmulator(it) },
                            stoppingEmulatorSerial = state.stoppingEmulatorSerial,
                            stopStatus = state.emulatorStopStatus,
                            onDevicePaneWidthChange = { width -> state.updateWorkspace { it.copy(liveDevicePaneWidth = width) } },
                            onControlsPaneHeightChange = { height -> state.updateWorkspace { it.copy(liveControlsPaneHeight = height) } },
                            onBugSaved = { state.setDestination(AndyDestination.Bugs) },
                            logcatState = state.liveLogcatState,
                            onPopOutMirror = {
                                val selectedDevice = state.devices.firstOrNull { it.serial == state.selectedSerial }
                                onPopOutMirror(state.selectedSerial, selectedDevice?.displayName ?: state.selectedSerial)
                            },
                            selectedPackage = state.workspaceState.selectedPackage,
                            onSelectedPackageChange = { pkg -> state.updateWorkspace { it.copy(selectedPackage = pkg) } },
                            transfer = state.transfer,
                        )
                        AndyDestination.Apps -> AppsScreen(
                            services,
                            state.selectedSerial,
                            state.workspaceState.appsListPaneWidth,
                            state.workspaceState.appsDetailsPaneHeight,
                            onPaneChange = { listWidth, detailsHeight -> state.updateWorkspace { it.copy(appsListPaneWidth = listWidth, appsDetailsPaneHeight = detailsHeight) } },
                        )
                        AndyDestination.Logcat -> LogcatScreen(
                            logcat = services.logcat,
                            appsService = services.apps,
                            serial = state.selectedSerial,
                            state = state.logcatState,
                            selectedPackage = state.workspaceState.selectedPackage,
                            onSelectedPackageChange = { pkg -> state.updateWorkspace { it.copy(selectedPackage = pkg) } }
                        )
                        AndyDestination.Intents -> IntentsScreen(services, state.selectedSerial)
                        AndyDestination.Files -> FilesScreen(
                            files = services.files,
                            apps = services.apps,
                            serial = state.selectedSerial,
                            transfer = state.transfer,
                        )
                        AndyDestination.ComputerFiles -> HostFilesScreen(
                            service = services.hostFiles,
                            workspaceState = state.workspaceState,
                            onUpdateWorkspace = { state.updateWorkspace(it) },
                        )
                        AndyDestination.Network -> NetworkScreen(
                            services = services,
                            sdk = state.sdk,
                            serial = state.selectedSerial,
                            device = state.devices.firstOrNull { it.serial == state.selectedSerial },
                            port = state.workspaceState.proxyPort,
                            rules = state.workspaceState.proxyRules,
                            rulesVisible = state.networkRulesVisible,
                            liveVisible = state.networkLiveVisible,
                            sslInsecure = state.workspaceState.proxySslInsecure,
                            upstreamTrustedCaPath = state.workspaceState.proxyUpstreamTrustedCaPath.orEmpty(),
                            onPortChange = { value -> state.updateWorkspace { it.copy(proxyPort = value) } },
                            onRulesChange = { value -> state.updateWorkspace { it.copy(proxyRules = value) } },
                            onRulesVisibleChange = { state.setNetworkRulesVisible(it) },
                            onSslInsecureChange = { value -> state.updateWorkspace { it.copy(proxySslInsecure = value) } },
                            onUpstreamTrustedCaPathChange = { value ->
                                state.updateWorkspace { it.copy(proxyUpstreamTrustedCaPath = value.trim().takeIf { path -> path.isNotBlank() }) }
                            },
                        )
                        AndyDestination.Actions, AndyDestination.Agents -> Unit
                        AndyDestination.Snapshots -> SnapshotsScreen(services.avd)
                        AndyDestination.Controls -> ControlsScreen(services.devices, services.mirror, state.selectedSerial)
                        AndyDestination.Performance -> PerformanceScreen(
                            services = services,
                            serial = state.selectedSerial,
                            device = state.devices.firstOrNull { it.serial == state.selectedSerial },
                            processesPaneWidth = state.workspaceState.performanceProcessesPaneWidth,
                            onProcessesPaneWidthChange = { width -> state.updateWorkspace { it.copy(performanceProcessesPaneWidth = width) } },
                            liveVisible = state.performanceLiveVisible,
                            livePaneWidth = state.workspaceState.performanceLivePaneWidth,
                            onLivePaneWidthChange = { width -> state.updateWorkspace { it.copy(performanceLivePaneWidth = width) } },
                        )
                        AndyDestination.Design -> DesignScreen(
                            services,
                            state.selectedSerial,
                            state.devices.firstOrNull { it.serial == state.selectedSerial },
                            state.workspaceState.designDevicePaneWidth,
                            onDevicePaneWidthChange = { width -> state.updateWorkspace { it.copy(designDevicePaneWidth = width) } },
                        )
                        AndyDestination.Accessibility -> AccessibilityScreen(
                            services,
                            state.selectedSerial,
                            state.devices.firstOrNull { it.serial == state.selectedSerial },
                            state.workspaceState.accessibilityTreePaneWidth,
                            onTreePaneWidthChange = { width -> state.updateWorkspace { it.copy(accessibilityTreePaneWidth = width) } },
                            state = state.accessibilityState
                        )
                        AndyDestination.Bugs -> BugsScreen(services.bugs)
                        AndyDestination.Settings -> SettingsScreen(
                            workspaceState = state.workspaceState,
                            onUpdateWorkspace = { state.updateWorkspace(it) },
                            services = services
                        )
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
        state.transfer.confirmationTitle?.let { title ->
            ConfirmationDialog(
                confirmation = PendingConfirmation(
                    title = title,
                    message = state.transfer.confirmationMessage,
                    confirmLabel = "Replace",
                    onConfirm = { state.transfer.acceptConfirmation() },
                ),
                onDismiss = { state.transfer.dismissConfirmation() },
                onConfirm = { state.transfer.acceptConfirmation() },
            )
        }
    }
}
