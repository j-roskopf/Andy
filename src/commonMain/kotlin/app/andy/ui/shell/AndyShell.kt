package app.andy.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.andy.AndyDestination
import app.andy.availableWithIosTarget
import app.andy.isToggleableInSidebar
import app.andy.model.DeviceConnectionState
import app.andy.service.AndyServices
import app.andy.service.IosTargetRegistry
import app.andy.service.OpenAgentTaskRequest
import app.andy.ui.actions.ActionsScreen
import app.andy.ui.agents.AgentsScreen
import app.andy.ui.apps.AppsScreen
import app.andy.ui.bugs.BugsScreen
import app.andy.ui.catalog.CatalogScreen
import app.andy.ui.components.ConfirmationDialog
import app.andy.ui.components.FilterPill
import app.andy.ui.components.PendingConfirmation
import app.andy.ui.controls.ControlsScreen
import app.andy.ui.design.DesignScreen
import app.andy.ui.devices.DevicesScreen
import app.andy.ui.files.FilesScreen
import app.andy.ui.hostfiles.HostFilesScreen
import app.andy.ui.inspector.InspectorScreen
import app.andy.ui.intents.IntentsScreen
import app.andy.ui.live.LiveScreen
import app.andy.ui.logcat.LogcatScreen
import app.andy.ui.network.NetworkScreen
import app.andy.model.FilesTab
import app.andy.model.PerformanceTab
import app.andy.model.ProxyStartOptions
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.model.RunningAction
import app.andy.ui.agents.isSessionWorking
import app.andy.service.AvailableUpdate
import app.andy.ui.network.shouldAutoStartProxy
import app.andy.ui.performance.PerformanceScreen
import app.andy.ui.settings.SettingsScreen
import app.andy.ui.settings.UpdateInstallConfirmationDialog
import app.andy.ui.snapshots.SnapshotsScreen
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyTheme
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Ink
import app.andy.ui.theme.Rust
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@Composable
internal fun AndyShell(
    services: AndyServices,
    requestedDestination: AndyDestination?,
    onDestinationConsumed: () -> Unit,
    requestedOpenAgentTask: OpenAgentTaskRequest?,
    onOpenAgentTaskConsumed: () -> Unit,
    requestPopOutMirror: Boolean,
    onPopOutMirrorRequestConsumed: () -> Unit,
    onPopOutMirror: (String?, String?) -> Unit,
    onPopOutDevice: (String, String) -> Unit,
    poppedOutTargetIds: Set<String> = emptySet(),
    contentTopPadding: androidx.compose.ui.unit.Dp,
    initialProjectTaskId: String?,
    initialProjectTab: String?,
) {
    val state = rememberShellState(services)
    val capabilities = services.capabilities
    // OS notification deep links and in-app contextual launches share one open-chat request.
    val effectiveOpenAgentTask = requestedOpenAgentTask ?: state.pendingAgentTaskOpen
    val consumeOpenAgentTask = {
        state.consumeAgentTaskOpen()
        onOpenAgentTaskConsumed()
    }
    val runningActions by if (capabilities.hostAutomation) {
        services.actionRuns.running.collectAsState()
    } else {
        remember { mutableStateOf(emptyList<RunningAction>()) }
    }
    val agentTasks by if (capabilities.hostAutomation) {
        services.agentRuns.tasks.collectAsState()
    } else {
        remember { mutableStateOf(emptyList<AgentTask>()) }
    }
    val pendingUpdateInstallConfirmation by if (capabilities.updates) {
        services.updates.pendingInstallConfirmation.collectAsState()
    } else {
        remember { mutableStateOf<AvailableUpdate?>(null) }
    }
    LaunchedEffect(state.activeTargetId, state.devices, state.iosTargets) {
        val targetId = state.activeTargetId
        val androidOnline = targetId != null && state.devices.any { it.serial == targetId && it.state == DeviceConnectionState.Online }
        val iosSelected = targetId != null && state.iosTargets.any { it.udid == targetId }
        val selectedAvailable = androidOnline || iosSelected
        if (!selectedAvailable) {
            withContext(NonCancellable) {
                services.mirror.disconnect()
            }
        }
    }

    LaunchedEffect(state.isIosSelection, state.destination) {
        if (state.isIosSelection && !state.destination.availableWithIosTarget()) {
            state.navigateTo(AndyDestination.Live)
        }
    }

    val visibleDestinations = remember(
        capabilities.destinations,
        state.workspaceState.disabledDestinations,
        state.isIosSelection,
    ) {
        capabilities.destinations.filter { destination ->
            (!destination.isToggleableInSidebar() || destination.name !in state.workspaceState.disabledDestinations) &&
                !(state.isIosSelection && destination == AndyDestination.Controls)
        }
    }
    LaunchedEffect(state.destination, visibleDestinations) {
        if (state.destination !in visibleDestinations) {
            state.navigateTo(visibleDestinations.firstOrNull() ?: AndyDestination.Settings)
        }
    }

    LaunchedEffect(Unit) {
        state.initialize()
    }

    val runningActionIds = remember(runningActions) { runningActions.map { it.runId } }
    LaunchedEffect(runningActionIds) {
        state.syncActiveRun(runningActions)
        state.pruneDockTerminalTabs(runningActions)
    }
    LaunchedEffect(state.terminalRunId, runningActionIds) {
        state.consumeTerminalRun(runningActions)
    }

    LaunchedEffect(requestedDestination) {
        requestedDestination?.let { requested ->
            val target = when {
                requested == AndyDestination.Tracing -> AndyDestination.Tracing
                requested in visibleDestinations -> requested
                else -> visibleDestinations.firstOrNull() ?: AndyDestination.Settings
            }
            state.navigateTo(target)
            onDestinationConsumed()
        }
    }
    LaunchedEffect(requestedOpenAgentTask) {
        requestedOpenAgentTask?.let { request ->
            state.navigateTo(if (request.projectId == null) AndyDestination.Agents else AndyDestination.Actions)
        }
    }

    LaunchedEffect(requestPopOutMirror) {
        if (!requestPopOutMirror) return@LaunchedEffect
        val serial = state.activeTargetId
        if (serial != null) {
            val selectedDevice = state.devices.firstOrNull { it.serial == serial }
            val iosName = state.iosTargets.firstOrNull { it.udid == serial }?.displayName
            onPopOutMirror(serial, selectedDevice?.displayName ?: iosName ?: serial)
        }
        onPopOutMirrorRequestConsumed()
    }

    LaunchedEffect(
        state.workspaceState.mcpServerEnabled,
        state.workspaceState.mcpServerPort,
        state.workspaceState.networkAccessEnabled,
        state.workspaceState.networkAccessTailscaleOnly,
        state.workspaceState.networkAccessToken,
    ) {
        if (!capabilities.mcp) return@LaunchedEffect
        if (state.workspaceState.mcpServerEnabled) {
            // Restart when bind host, port, or token changes so existing WS sessions
            // drop immediately (token regenerate must invalidate live connections).
            services.mcp.stop()
            services.mcp.start(state.workspaceState.mcpServerPort)
        } else {
            services.mcp.stop()
        }
    }

    LaunchedEffect(state.workspaceLoaded, state.workspaceState.proxyStartOnLaunch, state.workspaceState.proxyPort, state.workspaceState.proxyRules, state.workspaceState.proxySslInsecure, state.workspaceState.proxyUpstreamTrustedCaPath) {
        if (!capabilities.proxy) return@LaunchedEffect
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

    val mcpRunning by if (capabilities.mcp) {
        services.mcp.running.collectAsState(false)
    } else {
        remember { mutableStateOf(false) }
    }
    val proxyStatus by if (capabilities.proxy) {
        services.proxy.status.collectAsState("Proxy stopped")
    } else {
        remember { mutableStateOf("Proxy unavailable") }
    }
    val proxyRunning = proxyStatus.contains("listening on")

    AndyTheme(
        tintId = state.workspaceState.tintId,
        surfaceModeId = state.workspaceState.surfaceModeId,
    ) {
    CompositionLocalProvider(
        // Keep SwingPanel mounted during window resize; MirrorPresentationGuard blocks geometry
        // synchronously. Tearing heavyweight peers down mid-resize deadlocks on presenter remount.
        // Modal dialogs share the same rule as chrome menus: interop surfaces paint over them.
        LocalSuppressHeavyweightSurfaces provides (
            HeavyweightOverlayRegistry.anyActive ||
            state.docks.landingFor != null ||
            state.chromeMenuExpanded ||
            ModalDialogRegistry.anyOpen
        ),
        LocalOpenAgentTask provides state::openAgentTask,
        LocalOpenInvestigation provides state::openInvestigation,
    ) {
    Box(
        Modifier.fillMaxSize().background(Ink)
    ) {
        val knownProjectIds = remember(state.actionsConfig.projects) {
            state.actionsConfig.projects.mapTo(mutableSetOf()) { it.id }
        }
        Row(Modifier.fillMaxSize().padding(top = contentTopPadding)) {
            Sidebar(
                current = state.destination,
                destinations = visibleDestinations,
                deviceCount = state.devices.size + state.iosTargets.size,
                iosSelectionActive = state.isIosSelection,
                // Project chats are owned by Actions. Keep their unread state out of
                // the standalone Agent destination.
                hasUnreadAgentTasks = agentTasks.any { !it.archived && it.unread && it.projectId == null },
                hasUnreadProjectAgentTasks = agentTasks.any { task ->
                    !task.archived && task.unread && task.workflowTaskId == null &&
                        task.projectId != null && task.projectId in knownProjectIds
                },
                hasActiveProjectAgentTasks = agentTasks.any { task ->
                    task.projectId != null && isSessionWorking(task)
                },
                hasBlockedAgentTasks = agentTasks.any {
                    !it.archived && it.projectId == null && it.status == AgentStatus.Blocked
                },
                hasBlockedProjectAgentTasks = agentTasks.any { task ->
                    !task.archived && task.projectId != null && task.status == AgentStatus.Blocked
                },
                logcatLive = state.logcatState.live,
                onSelect = state::navigateTo,
                expanded = state.workspaceState.workspaceSidebarExpanded,
                onExpandedChange = { expanded -> state.updateWorkspace { it.copy(workspaceSidebarExpanded = expanded) } },
                statusExpanded = state.workspaceState.workspaceStatusExpanded,
                onStatusExpandedChange = { expanded -> state.updateWorkspace { it.copy(workspaceStatusExpanded = expanded) } },
                sdk = state.sdk,
                updates = services.updates.takeIf { capabilities.updates },
                mcpRunning = mcpRunning,
                mcpPort = state.workspaceState.mcpServerPort
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .background(AndyColors.ContentBg)
            ) {
                TopChrome(
                    destination = state.destination,
                    selectedDevice = state.devices.firstOrNull { it.serial == state.selectedSerial },
                    devices = state.devices,
                    iosTargets = state.iosTargets,
                    selectedIosTarget = state.iosTargets.firstOrNull { it.udid == state.selectedIosUdid },
                    deviceLabels = state.workspaceState.deviceLabels,
                    onSelectDevice = { state.selectDevice(it) },
                    onSelectIosTarget = { state.selectIosTarget(it) },
                    onRefresh = state::refreshDevices,
                    onStopEmulator = { state.stopEmulator(it) },
                    stoppingEmulatorSerial = state.stoppingEmulatorSerial,
                    showDevicePopOut = capabilities.platform != app.andy.service.AndyPlatform.Web &&
                        state.activeTargetId != null,
                    onPopOutDevice = { targetId, displayName ->
                        if (IosTargetRegistry.isIosTarget(targetId)) {
                            state.selectIosTarget(targetId)
                        } else {
                            state.selectDevice(targetId)
                        }
                        onPopOutDevice(targetId, displayName)
                    },
                    actionConfig = state.actionsConfig,
                    selectedActionProjectId = state.workspaceState.lastActionProjectId,
                    selectedActionId = state.workspaceState.lastActionId,
                    onActionSelectionChange = state::rememberActionSelection,
                    onRunAction = { project, action -> state.runAction(project, action) },
                    proxyRunning = proxyRunning,
                    rightPaneOpen = state.docks.right.visible,
                    bottomPaneOpen = state.docks.bottom.visible,
                    dockLandingFor = state.docks.landingFor,
                    onPlacementIconClick = state::onPlacementIconClick,
                    onDismissDockLanding = state::dismissDockLanding,
                    onOpenDockKind = state::openDockKind,
                    onMenuExpandedChange = state::updateChromeMenuExpanded,
                    onProxyClick = state::openProxySettings,
                    actions = {
                        if (state.destination == AndyDestination.Network) {
                            FilterPill("Rules", state.networkRulesVisible, Rust, toolbar = true) { state.toggleNetworkRulesVisible() }
                            Spacer(Modifier.width(10.dp))
                        } else if (
                            state.destination == AndyDestination.Performance &&
                            state.workspaceState.performanceTab == PerformanceTab.Metrics.name
                        ) {
                            FilterPill("Live", state.performanceLiveVisible, Cyan, toolbar = true) { state.togglePerformanceLiveVisible() }
                            Spacer(Modifier.width(10.dp))
                        }
                    },
                )
                val liveDockActive = state.destination != AndyDestination.Live &&
                    state.activeTargetId !in poppedOutTargetIds
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(AndyColors.ContentBg)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val actionsActive = state.destination == AndyDestination.Actions
                    val agentsActive = state.destination == AndyDestination.Agents
                    val computerFilesActive = state.destination == AndyDestination.ComputerFiles
                    val performanceActive = state.destination == AndyDestination.Performance
                    val performanceTab = PerformanceTab.entries
                        .firstOrNull { it.name == state.workspaceState.performanceTab }
                        ?: PerformanceTab.Metrics
                    RetainedDestination(active = actionsActive) {
                        ActionsScreen(
                            services = services,
                            config = state.actionsConfig,
                            onConfigChange = { state.persistActionsConfig(it) },
                            agentTasks = agentTasks,
                            showIntroduction = state.workspaceLoaded && !state.workspaceState.projectsIntroductionCompleted,
                            onIntroductionComplete = { state.updateWorkspace { it.copy(projectsIntroductionCompleted = true) } },
                            preferredProjectId = state.workspaceState.lastActionProjectId,
                            onPreferredProjectChange = state::rememberLastProject,
                            workspaceReady = state.workspaceLoaded,
                            active = actionsActive,
                            initialWorkflowTaskId = initialProjectTaskId,
                            initialCanvasLabel = initialProjectTab,
                            requestedAgentTaskId = effectiveOpenAgentTask?.takeIf { it.projectId != null }?.taskId,
                            requestedProjectId = effectiveOpenAgentTask?.projectId,
                            onRequestedAgentTaskConsumed = consumeOpenAgentTask,
                            onNotifyTerminalRun = state::notifyTerminalRun,
                            workspaceState = state.workspaceState,
                            onUpdateWorkspace = { state.updateWorkspace(it) },
                        )
                    }
                    RetainedDestination(active = agentsActive) {
                        AgentsScreen(
                            services = services, active = agentsActive,
                            requestedTaskId = effectiveOpenAgentTask?.takeIf { it.projectId == null }?.taskId,
                            onRequestedTaskConsumed = consumeOpenAgentTask,
                            workspaceState = state.workspaceState,
                        )
                    }
                    RetainedDestination(active = computerFilesActive) {
                        HostFilesScreen(
                            service = services.hostFiles,
                            workspaceState = state.workspaceState,
                            onUpdateWorkspace = { state.updateWorkspace(it) },
                        )
                    }
                    RetainedDestination(active = performanceActive) {
                        PerformanceScreen(
                            services = services,
                            serial = state.selectedSerial,
                            device = state.devices.firstOrNull { it.serial == state.selectedSerial },
                            active = performanceActive,
                            selectedTab = performanceTab,
                            onSelectedTabChange = { tab ->
                                state.updateWorkspace { it.copy(performanceTab = tab.name) }
                            },
                            processesPaneWidth = state.workspaceState.performanceProcessesPaneWidth,
                            onProcessesPaneWidthChange = { width -> state.updateWorkspace { it.copy(performanceProcessesPaneWidth = width) } },
                            liveVisible = state.performanceLiveVisible,
                            livePaneWidth = state.workspaceState.performanceLivePaneWidth,
                            onLivePaneWidthChange = { width -> state.updateWorkspace { it.copy(performanceLivePaneWidth = width) } },
                            tracingPresetId = state.workspaceState.tracingPresetId,
                            tracingDurationSeconds = state.workspaceState.tracingDurationSeconds,
                            tracingBufferSizeMb = state.workspaceState.tracingBufferSizeMb,
                            tracingPresetsPaneWidth = state.workspaceState.tracingPresetsPaneWidth,
                            tracingLibraryPaneHeight = state.workspaceState.tracingLibraryPaneHeight,
                            onTracingPresetIdChange = { value -> state.updateWorkspace { it.copy(tracingPresetId = value) } },
                            onTracingDurationSecondsChange = { value -> state.updateWorkspace { it.copy(tracingDurationSeconds = value) } },
                            onTracingBufferSizeMbChange = { value -> state.updateWorkspace { it.copy(tracingBufferSizeMb = value) } },
                            onTracingPresetsPaneWidthChange = { value -> state.updateWorkspace { it.copy(tracingPresetsPaneWidth = value) } },
                            onTracingLibraryPaneHeightChange = { value -> state.updateWorkspace { it.copy(tracingLibraryPaneHeight = value) } },
                        )
                    }
                    when (state.destination) {
                        AndyDestination.Devices -> DevicesScreen(
                            services,
                            state.devices,
                            state.sdk,
                            iosTargets = state.iosTargets,
                            pairedWifiDevices = state.workspaceState.pairedWifiDevices,
                            onRefresh = { state.refreshDevices() },
                            onLive = { state.openLive(it) },
                            onEmulatorStarted = { previousSerials, avdName ->
                                state.openStartedEmulator(previousSerials, avdName)
                            },
                            onBootIosSimulator = { state.bootIosSimulator(it) },
                            onShutdownIosSimulator = { state.shutdownIosSimulator(it) },
                            onOpenIosInSimulatorApp = { state.openIosInSimulatorApp(it) },
                            onStopEmulator = { state.stopEmulator(it) },
                            stoppingEmulatorSerial = state.stoppingEmulatorSerial,
                            stopStatus = state.emulatorStopStatus,
                            startingEmulatorName = state.startingEmulatorName,
                            startStatus = state.emulatorStartStatus,
                            onSavePairedWifi = state::savePairedWifi,
                            onForgetPairedWifi = state::forgetPairedWifi,
                            onReconnectPairedWifi = state::reconnectPairedWifi,
                            onDisconnectWifi = state::disconnectWifi,
                            allowAvdManagement = capabilities.avdManagement,
                            allowIosManagement = capabilities.iosDeviceManagement,
                            allowWifiPairing = capabilities.wifiPairing,
                            transfer = state.transfer,
                            deviceLabels = state.workspaceState.deviceLabels,
                            onSetDeviceLabel = { serial, label -> state.setDeviceLabel(serial, label) },
                        )
                        AndyDestination.Catalog -> CatalogScreen(services.avd)
                        AndyDestination.Live -> LiveScreen(
                            services = services,
                            serial = state.activeTargetId,
                            device = state.devices.firstOrNull { it.serial == state.selectedSerial },
                            iosTarget = state.iosTargets.firstOrNull { it.udid == state.selectedIosUdid },
                            mirroredElsewhere = state.activeTargetId != null && state.activeTargetId in poppedOutTargetIds,
                            devicePaneWidth = state.workspaceState.liveDevicePaneWidth,
                            onStopEmulator = { state.stopEmulator(it) },
                            stoppingEmulatorSerial = state.stoppingEmulatorSerial,
                            stopStatus = state.emulatorStopStatus,
                            onDevicePaneWidthChange = { width -> state.updateWorkspace { it.copy(liveDevicePaneWidth = width) } },
                            onBugSaved = { state.navigateTo(AndyDestination.Bugs) },
                            onRecordingSaved = { state.navigateTo(AndyDestination.Recordings) },
                            logcatState = state.liveLogcatState,
                            onPopOutMirror = {
                                val targetId = state.activeTargetId ?: return@LiveScreen
                                val selectedDevice = state.devices.firstOrNull { it.serial == targetId }
                                val iosName = state.iosTargets.firstOrNull { it.udid == targetId }?.displayName
                                onPopOutMirror(targetId, selectedDevice?.displayName ?: iosName ?: targetId)
                            },
                            selectedPackage = state.workspaceState.selectedPackage,
                            onSelectedPackageChange = { pkg -> state.updateWorkspace { it.copy(selectedPackage = pkg) } },
                            transfer = state.transfer,
                            foldableHingeAngle = state.foldableHingeAngle,
                            onFoldableHingeAngleChange = state::updateFoldableHingeAngle,
                        )
                        AndyDestination.Apps -> AppsScreen(
                            services,
                            state.selectedSerial,
                            state.workspaceState.appsListPaneWidth,
                            state.workspaceState.appsDetailsPaneHeight,
                            onPaneChange = { listWidth, detailsHeight -> state.updateWorkspace { it.copy(appsListPaneWidth = listWidth, appsDetailsPaneHeight = detailsHeight) } },
                        )
                        AndyDestination.Logcat -> LogcatScreen(
                            services = services,
                            serial = state.selectedSerial,
                            state = state.logcatState,
                            selectedPackage = state.workspaceState.selectedPackage,
                            onSelectedPackageChange = { pkg -> state.updateWorkspace { it.copy(selectedPackage = pkg) } },
                            workspaceState = state.workspaceState,
                            onUpdateWorkspace = { state.updateWorkspace(it) },
                        )
                        AndyDestination.Intents -> IntentsScreen(
                            services = services,
                            serial = state.selectedSerial,
                            workspaceState = state.workspaceState,
                            onUpdateWorkspace = { state.updateWorkspace(it) },
                        )
                        AndyDestination.Files -> FilesScreen(
                            files = services.files,
                            apps = services.apps,
                            sharedPrefs = services.sharedPrefs,
                            appDatabase = services.appDatabase,
                            serial = state.selectedSerial,
                            transfer = state.transfer,
                            selectedPackage = state.workspaceState.selectedPackage,
                            onSelectedPackageChange = { pkg ->
                                state.updateWorkspace { it.copy(selectedPackage = pkg) }
                            },
                            selectedTab = FilesTab.entries
                                .firstOrNull { it.name == state.workspaceState.filesTab }
                                ?: FilesTab.Files,
                            onSelectedTabChange = { tab ->
                                state.updateWorkspace { it.copy(filesTab = tab.name) }
                            },
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
                            onRulesVisibleChange = { state.updateNetworkRulesVisible(it) },
                            onSslInsecureChange = { value -> state.updateWorkspace { it.copy(proxySslInsecure = value) } },
                            onUpstreamTrustedCaPathChange = { value ->
                                state.updateWorkspace { it.copy(proxyUpstreamTrustedCaPath = value.trim().takeIf { path -> path.isNotBlank() }) }
                            },
                        )
                        AndyDestination.Actions, AndyDestination.Agents, AndyDestination.ComputerFiles, AndyDestination.Performance, AndyDestination.Tracing -> Unit
                        AndyDestination.Snapshots -> SnapshotsScreen(
                            avd = services.avd,
                            knownDeviceSerials = { state.devices.map { it.serial }.toSet() },
                            onEmulatorStarted = state::openStartedEmulator,
                            startingEmulatorName = state.startingEmulatorName,
                            startStatus = state.emulatorStartStatus,
                        )
                        AndyDestination.Controls -> ControlsScreen(
                            devices = services.devices,
                            mirror = services.mirror,
                            serial = state.selectedSerial,
                            device = state.devices.firstOrNull { it.serial == state.selectedSerial },
                            avd = services.avd,
                            apps = services.apps,
                            hostFiles = services.hostFiles,
                            hingeAngle = state.foldableHingeAngle,
                            onHingeAngleChange = state::updateFoldableHingeAngle,
                        )
                        AndyDestination.Design -> DesignScreen(
                            services,
                            state.selectedSerial,
                            state.devices.firstOrNull { it.serial == state.selectedSerial },
                            state.workspaceState.designDevicePaneWidth,
                            onDevicePaneWidthChange = { width -> state.updateWorkspace { it.copy(designDevicePaneWidth = width) } },
                        )
                        AndyDestination.Inspector -> {
                            InspectorScreen(
                                services,
                                state.selectedSerial,
                                state.devices.firstOrNull { it.serial == state.selectedSerial },
                                state.workspaceState.inspectorTreePaneWidth,
                                onTreePaneWidthChange = { width -> state.updateWorkspace { it.copy(inspectorTreePaneWidth = width) } },
                                state = state.inspectorState
                            )
                        }
                        AndyDestination.Bugs -> BugsScreen(
                            services = services,
                            pendingInvestigation = state.pendingInvestigationOpen,
                            onPendingInvestigationConsumed = state::consumeInvestigationOpen,
                        )
                        AndyDestination.Recordings -> BugsScreen(services, recordings = true)
                        AndyDestination.Settings -> SettingsScreen(
                            workspaceState = state.workspaceState,
                            onUpdateWorkspace = { state.updateWorkspace(it) },
                            services = services,
                            initialCategory = state.pendingSettingsCategory,
                            onInitialCategoryConsumed = { state.consumeSettingsCategory() },
                        )
                    }
                }
                if (state.docks.right.visible) {
                    Spacer(Modifier.width(12.dp))
                    ShellDockDrawer(
                        services = services,
                        pane = state.docks.right,
                        placement = DockPlacement.Right,
                        running = runningActions,
                        serial = state.activeTargetId,
                        device = state.devices.firstOrNull { it.serial == state.selectedSerial },
                        targetDisplayName = state.iosTargets.firstOrNull { it.udid == state.selectedIosUdid }?.displayName,
                        liveActive = liveDockActive,
                        logcat = services.logcat,
                        appsService = services.apps,
                        selectedPackage = state.workspaceState.selectedPackage,
                        onSelectedPackageChange = { pkg -> state.updateWorkspace { it.copy(selectedPackage = pkg) } },
                        logcatState = state.logcatState,
                        onSelectTab = { state.selectDockTab(DockPlacement.Right, it) },
                        onCloseTab = { state.closeDockTab(DockPlacement.Right, it) },
                        onOpenKind = { kind, newTerminal -> state.openDockKind(DockPlacement.Right, kind, newTerminal) },
                        onClose = { state.closeDock(DockPlacement.Right) },
                        modifier = Modifier.width(460.dp).fillMaxHeight(),
                    )
                }
                }
                if (state.docks.bottom.visible) {
                    ShellDockDrawer(
                        services = services,
                        pane = state.docks.bottom,
                        placement = DockPlacement.Bottom,
                        running = runningActions,
                        serial = state.activeTargetId,
                        device = state.devices.firstOrNull { it.serial == state.selectedSerial },
                        targetDisplayName = state.iosTargets.firstOrNull { it.udid == state.selectedIosUdid }?.displayName,
                        liveActive = liveDockActive,
                        logcat = services.logcat,
                        appsService = services.apps,
                        selectedPackage = state.workspaceState.selectedPackage,
                        onSelectedPackageChange = { pkg -> state.updateWorkspace { it.copy(selectedPackage = pkg) } },
                        logcatState = state.logcatState,
                        onSelectTab = { state.selectDockTab(DockPlacement.Bottom, it) },
                        onCloseTab = { state.closeDockTab(DockPlacement.Bottom, it) },
                        onOpenKind = { kind, newTerminal -> state.openDockKind(DockPlacement.Bottom, kind, newTerminal) },
                        onClose = { state.closeDock(DockPlacement.Bottom) },
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                    )
                }
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
}
