package app.andy.desktop.service

import app.andy.desktop.service.agents.AgentCliLocator
import app.andy.desktop.service.agents.AgentTerminalMode
import app.andy.desktop.service.agents.AntigravityAdapter
import app.andy.desktop.service.agents.ClaudeCodeAdapter
import app.andy.desktop.service.agents.CodexAdapter
import app.andy.desktop.service.agents.CursorAdapter
import app.andy.desktop.service.agents.DesktopAgentRunService
import app.andy.desktop.service.agents.DesktopAgentRetentionService
import app.andy.desktop.service.agents.DesktopAgentTaskStore
import app.andy.desktop.service.agents.DesktopOrchestrationPreferencesService
import app.andy.desktop.service.agents.defaultAndyAgentArtifactsDir
import app.andy.desktop.service.agents.registerArchiveViewShutdownHook
import app.andy.desktop.service.agents.OpenCodeAdapter
import app.andy.desktop.service.agents.PiAdapter
import app.andy.desktop.service.agents.HermesAdapter
import app.andy.desktop.service.agents.OpenClawAdapter
import app.andy.desktop.service.agents.GooseAdapter
import app.andy.desktop.service.agents.WorktreeManager
import app.andy.desktop.service.automations.DesktopAutomationService
import app.andy.desktop.service.inspector.DesktopAppDatabaseService
import app.andy.desktop.service.inspector.DesktopSharedPrefsService
import app.andy.desktop.service.ios.DesktopIosDeviceService
import app.andy.desktop.service.ios.DesktopIosMirrorEngine
import app.andy.desktop.service.mirror.DesktopMirrorEngine
import app.andy.desktop.service.mirror.DesktopPopOutMirrorPool
import app.andy.service.RoutingMirrorEngine
import app.andy.desktop.service.mirror.NativeMirrorJni
import app.andy.desktop.service.proxy.DesktopProxyService
import app.andy.desktop.service.dhu.DesktopDhuService
import app.andy.desktop.service.tracing.DesktopTraceViewerService
import app.andy.desktop.service.tracing.DesktopTracingService
import app.andy.desktop.service.voice.DesktopVoiceDictationService
import app.andy.desktop.service.voice.DesktopVoiceSetupService
import app.andy.desktop.service.webchat.NetworkAccessHttpReconciler
import app.andy.desktop.service.webchat.resolveHost
import app.andy.desktop.service.webchat.toNetworkAccessBindConfig
import app.andy.model.AgentKind
import app.andy.model.toTerminalAppearance
import app.andy.desktop.updates.DesktopAppUpdateService
import app.andy.desktop.updates.DesktopCliUpdateCheckService
import app.andy.desktop.updates.DesktopRuntimeBundleService
import app.andy.service.AndyServices
import app.andy.service.CommandResult
import app.andy.service.PlatformCapabilities
import app.andy.service.UnavailableKanbanService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

fun createDesktopServices(): AndyServices = createDesktopRuntime().services

private fun createDesktopVoicePair(): Pair<DesktopVoiceSetupService, DesktopVoiceDictationService> {
    val setup = DesktopVoiceSetupService()
    return setup to DesktopVoiceDictationService(setup)
}

data class DesktopRuntime(
    val services: AndyServices,
    val popOutMirrors: DesktopPopOutMirrorPool,
)

/** How the Compose GUI obtains agent/project services. */
enum class RuntimeMode {
    /** Talk to a running `andyd` over `~/.andy/andyd.sock`. */
    DaemonClient,

    /** Host the daemon graph in-process (fallback when no socket is present). */
    EmbeddedDaemon,
}

/** Pick how the GUI hosts agent/project services. */
fun resolveRuntimeMode(): RuntimeMode {
    AndydProcess.removeStaleArtifacts()
    return if (AndydProcess.isExternalDaemonLive()) {
        RuntimeMode.DaemonClient
    } else {
        RuntimeMode.EmbeddedDaemon
    }
}

/** @deprecated Use [resolveRuntimeMode]; kept for tests. */
fun detectRuntimeMode(): RuntimeMode = resolveRuntimeMode()

data class DaemonRuntime(
    val services: AndyServices,
    val socketPath: File,
    val mcp: DesktopMcpServerService,
    private val onShutdown: () -> Unit,
) {
    fun shutdown() = onShutdown()
}

/**
 * Headless daemon service graph: SQLite store, tmux agent executor, MCP on unix socket
 * (+ loopback HTTP so vendor agent CLIs can still attach Andy device tools).
 */
fun createDaemonRuntime(
    socketPath: File = File(System.getProperty("user.home"), ".andy/andyd.sock"),
): DaemonRuntime {
    registerArchiveViewShutdownHook()
    val runner = CommandRunner()
    val locator = SdkLocator()
    val store = DesktopWorkspaceStore()
    runBlocking { store.load() }
    val devices = DesktopDeviceService(runner, locator, store)
    val iosDevices = DesktopIosDeviceService(runner)
    val androidMirror = DesktopMirrorEngine(runner, devices)
    val iosMirror = DesktopIosMirrorEngine(iosDevices)
    val mirror = RoutingMirrorEngine(androidMirror, iosMirror)
    val logcat = DesktopLogcatService(runner, devices)
    val actionConfig = DesktopActionConfigStore(discoveryRootsProvider = {
        val ws = store.state.value
        (listOf(System.getProperty("user.dir")) + ws.hostFileRoots +
         ws.recentHostFiles.mapNotNull { File(it).parent } +
         listOfNotNull(ws.lastHostFilePath?.let { File(it).parent }))
            .distinct()
    })
    val actionRuns = DesktopActionRunService(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        terminalAppearance = { store.state.value.toTerminalAppearance() },
    )
    val avd = DesktopAvdService(runner, locator) { store.load().selectedSdkPath }
    val intents = DesktopIntentService(runner, devices)
    val apps = DesktopAppService(runner, devices)
    val files = DesktopFileService(runner, devices)
    val hostFiles = DesktopHostFileService(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
    val proxy = DesktopProxyService(runner, devices)
    val accessibility = DesktopAccessibilityService(runner, devices)
    val viewHierarchy = DesktopViewHierarchyService(runner, devices)
    val tracing = DesktopTracingService(runner, devices, files)
    val traceViewer = DesktopTraceViewerService()
    val sharedPrefs = DesktopSharedPrefsService(runner, devices)
    val appDatabase = DesktopAppDatabaseService(runner, devices)
    val dhu = DesktopDhuService(devices = devices, runner = runner)
    val metrics = DesktopMetricsService(runner, devices)
    val crashInspector = DesktopCrashInspectorService(devices)
    val heapDump = DesktopHeapDumpService(runner, devices, files)

    val bugService = DesktopBugService(
        mirror, logcat,
        devices = devices,
        accessibility = accessibility,
        proxy = proxy,
        metrics = metrics,
        crashInspector = crashInspector,
        viewHierarchy = viewHierarchy,
        apps = apps,
        workspaceStore = store,
        actionConfig = actionConfig,
    )
    val recordingExportService = DesktopRecordingExportService(bugService)
    val evidenceService = DesktopInvestigationEvidenceService(bugService)

    val mcp = DesktopMcpServerService(
        devices = devices,
        avd = avd,
        mirror = mirror,
        logcat = logcat,
        intents = intents,
        apps = apps,
        files = files,
        proxy = proxy,
        accessibility = accessibility,
        viewHierarchy = viewHierarchy,
        workspaceStore = store,
        metrics = metrics,
        crashInspector = crashInspector,
        heapDump = heapDump,
        bugs = bugService,
        recordingExport = recordingExportService,
        actionConfig = actionConfig,
    )

    val agentTaskStore = DesktopAgentTaskStore()
    val agentRuns = DesktopAgentRunService(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        store = agentTaskStore,
        locator = AgentCliLocator(),
        adapters = mapOf(
            AgentKind.ClaudeCode to ClaudeCodeAdapter(),
            AgentKind.Codex to CodexAdapter(),
            AgentKind.Cursor to CursorAdapter(),
            AgentKind.Antigravity to AntigravityAdapter(),
            AgentKind.OpenCode to OpenCodeAdapter(),
            AgentKind.Pi to PiAdapter(),
            AgentKind.Hermes to HermesAdapter(),
            AgentKind.OpenClaw to OpenClawAdapter(),
            AgentKind.Goose to GooseAdapter(),
        ),
        worktrees = WorktreeManager(),
        mcp = mcp,
        workspaceStore = store,
        actionConfig = actionConfig,
        terminalMode = AgentTerminalMode.TmuxHeadless,
    )
    val automations = DesktopAutomationService(
        store = agentTaskStore,
        agentRuns = agentRuns,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        startScheduler = true,
    )
    mcp.bindAgentServices(agentRuns, agentRuns, automations)
    val agentRetention = DesktopAgentRetentionService(
        runService = agentRuns,
        store = agentTaskStore,
        actionConfigStore = actionConfig,
        workspace = store.state,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ).also { it.start() }
    val kanban = DesktopKanbanService(agentTaskStore)
    val (voiceSetup, voiceDictation) = createDesktopVoicePair()
    val orchestrationPreferences = DesktopOrchestrationPreferencesService()

    val localServers = DesktopLocalServerService(
        runner = runner,
        agentRuns = agentRuns,
        actionRuns = actionRuns,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    val services = AndyServices(
        devices = devices,
        iosDevices = iosDevices,
        avd = avd,
        mirror = mirror,
        logcat = logcat,
        intents = intents,
        apps = apps,
        files = files,
        hostFiles = hostFiles,
        proxy = proxy,
        metrics = metrics,
        accessibility = accessibility,
        viewHierarchy = viewHierarchy,
        bugs = bugService,
        artifacts = DesktopArtifactService(runner, devices, mirror),
        recordingExport = recordingExportService,
        tracing = tracing,
        traceViewer = traceViewer,
        sharedPrefs = sharedPrefs,
        appDatabase = appDatabase,
        dhu = dhu,
        crashInspector = crashInspector,
        heapDump = heapDump,
        evidence = evidenceService,
        workspaceStore = store,
        updates = DesktopAppUpdateService(CoroutineScope(SupervisorJob() + Dispatchers.Default)),
        runtimeBundle = DesktopRuntimeBundleService(),
        cliUpdates = DesktopCliUpdateCheckService(
            agentRuns = agentRuns,
            actionRuns = actionRuns,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ),
        mcp = mcp,
        actionConfig = actionConfig,
        actionRuns = actionRuns,
        agentRuns = agentRuns,
        agentRetention = agentRetention,
        projectWorkflows = agentRuns,
        kanban = kanban,
        automations = automations,
        notificationSounds = DesktopNotificationSoundPlayer(),
        voiceSetup = voiceSetup,
        voiceDictation = voiceDictation,
        orchestrationPreferences = orchestrationPreferences,
        localServers = localServers,
        capabilities = PlatformCapabilities.Desktop.copy(
            acceleratedMirror = NativeMirrorJni.isEmbeddedPresentationSupported(),
        ),
    )

    // Unix socket first (blocking, no nested runBlocking/IO), then HTTP for agent CLIs.
    System.err.println("andyd: binding unix socket ${socketPath.absolutePath}")
    val unix = mcp.startUnixSocketBlocking(socketPath)
    check(unix.isSuccess && socketPath.exists()) {
        "MCP unix socket failed: ${unix.stderr.ifBlank { unix.stdout }} (path=${socketPath.absolutePath})"
    }
    System.err.println("andyd: MCP unix socket ready at ${socketPath.absolutePath}")

    // HTTP is optional for agent CLIs; unix socket is the andyd control plane.
    // Use a blocking bind (no nested runBlocking / Dispatchers.IO) — that path hung
    // under Gradle JavaExec and killed the daemon via a 30s TimeoutException.
    // Bind host follows Network Access (0.0.0.0 only when enabled with Tailscale-only
    // off; otherwise loopback — Tailscale-only mode requires `tailscale serve`).
    val initialWorkspace = runBlocking { store.load() }
    val bindConfig = initialWorkspace.toNetworkAccessBindConfig()
    val bindHost = bindConfig.resolveHost()
    System.err.println("andyd: binding HTTP MCP on $bindHost:${bindConfig.port}")
    val httpResult = runCatching { mcp.startHttpBlocking(bindConfig.port) }
        .getOrElse { error ->
            error.printStackTrace()
            CommandResult.failure(error.message ?: "HTTP start failed")
        }
    if (httpResult.isSuccess) {
        System.err.println("andyd: MCP HTTP on $bindHost:${bindConfig.port}")
    } else {
        System.err.println(
            "andyd: WARNING HTTP MCP failed (${httpResult.stderr.ifBlank { httpResult.stdout }}); " +
                "continuing with unix socket only",
        )
    }

    // GUI Settings writes ~/.andy/workspace.properties; in daemon-client mode the
    // Compose process only restarts its own MCP. Watch the file so standalone andyd
    // rebinds host/port and drops WS sessions when the token is regenerated.
    val daemonScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val networkAccessReconciler = NetworkAccessHttpReconciler(
        workspaceStore = store,
        mcp = mcp,
        scope = daemonScope,
        onApplied = { next, result ->
            val host = next.resolveHost()
            if (result.isSuccess) {
                System.err.println("andyd: MCP HTTP rebound on $host:${next.port}")
            }
        },
    ).also { it.start(bindConfig) }

    return DaemonRuntime(
        services = services,
        socketPath = socketPath,
        mcp = mcp,
        onShutdown = {
            networkAccessReconciler.stop()
            daemonScope.cancel()
            runCatching { automations.stop() }
            runCatching { agentRuns.shutdownForProcessExit() }
            runCatching { mcp.stopUnixSocketBlocking() }
            runBlocking {
                runCatching { mcp.stop() }
                runCatching { traceViewer.shutdown() }
            }
        },
    )
}

fun createDesktopRuntime(mode: RuntimeMode = resolveRuntimeMode()): DesktopRuntime {
    if (mode == RuntimeMode.DaemonClient) {
        System.err.println("andy: daemon-client mode (${AndydProcess.socketPath().absolutePath})")
        return createDesktopClientRuntime()
    }
    System.err.println("andy: embedded daemon mode (in-process agents)")
    return createEmbeddedDesktopRuntime()
}

/** GUI as MCP client of a running `andyd` — local attach terminals only. */
private fun createDesktopClientRuntime(): DesktopRuntime {
    registerArchiveViewShutdownHook()
    val runner = CommandRunner()
    val locator = SdkLocator()
    val store = DesktopWorkspaceStore()
    runBlocking { store.load() }
    val devices = DesktopDeviceService(runner, locator, store)
    val iosDevices = DesktopIosDeviceService(runner)
    val androidMirror = DesktopMirrorEngine(runner, devices)
    val iosMirror = DesktopIosMirrorEngine(iosDevices)
    val mirror = RoutingMirrorEngine(androidMirror, iosMirror)
    val popOutMirrors = DesktopPopOutMirrorPool(
        primary = mirror,
        newAndroid = { DesktopMirrorEngine(runner, devices) },
        newIos = { DesktopIosMirrorEngine(iosDevices) },
    )
    val logcat = DesktopLogcatService(runner, devices)
    val updatesScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val updates = DesktopAppUpdateService(updatesScope)
    val runtimeBundle = DesktopRuntimeBundleService()
    val actionConfig = DesktopActionConfigStore(discoveryRootsProvider = {
        val ws = store.state.value
        (listOf(System.getProperty("user.dir")) + ws.hostFileRoots +
         ws.recentHostFiles.mapNotNull { File(it).parent } +
         listOfNotNull(ws.lastHostFilePath?.let { File(it).parent }))
            .distinct()
    })
    val actionRuns = DesktopActionRunService(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        terminalAppearance = { store.state.value.toTerminalAppearance() },
    )
    val avd = DesktopAvdService(runner, locator) { store.load().selectedSdkPath }
    val intents = DesktopIntentService(runner, devices)
    val apps = DesktopAppService(runner, devices)
    val files = DesktopFileService(runner, devices)
    val hostFiles = DesktopHostFileService(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
    val proxy = DesktopProxyService(runner, devices)
    val accessibility = DesktopAccessibilityService(runner, devices)
    val viewHierarchy = DesktopViewHierarchyService(runner, devices)
    val tracing = DesktopTracingService(runner, devices, files)
    val traceViewer = DesktopTraceViewerService()
    val sharedPrefs = DesktopSharedPrefsService(runner, devices)
    val appDatabase = DesktopAppDatabaseService(runner, devices)
    val dhu = DesktopDhuService(devices = devices, runner = runner)
    val metrics = DesktopMetricsService(runner, devices)
    val crashInspector = DesktopCrashInspectorService(devices)
    val heapDump = DesktopHeapDumpService(runner, devices, files)
    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { traceViewer.shutdown() }
    })

    val bugService = DesktopBugService(
        mirror, logcat,
        devices = devices,
        accessibility = accessibility,
        proxy = proxy,
        metrics = metrics,
        crashInspector = crashInspector,
        viewHierarchy = viewHierarchy,
        apps = apps,
        workspaceStore = store,
        actionConfig = actionConfig,
    )
    val recordingExportService = DesktopRecordingExportService(bugService)
    val evidenceService = DesktopInvestigationEvidenceService(bugService)

    val mcp = DesktopMcpServerService(
        devices = devices,
        avd = avd,
        mirror = mirror,
        logcat = logcat,
        intents = intents,
        apps = apps,
        files = files,
        proxy = proxy,
        accessibility = accessibility,
        viewHierarchy = viewHierarchy,
        workspaceStore = store,
        metrics = metrics,
        crashInspector = crashInspector,
        heapDump = heapDump,
        bugs = bugService,
        recordingExport = recordingExportService,
        actionConfig = actionConfig,
    )

    val socket = File(System.getProperty("user.home"), ".andy/andyd.sock")
    val remoteAgents = McpAgentRunClient(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        socketPath = socket,
    )
    // Attach-only terminal host: must not open ~/.andy/agents.db. andyd owns that
    // store; a second writer silently reverts chats / unread badges on GUI quit.
    val attachStoreDir = File(System.getProperty("java.io.tmpdir"), "andy-gui-attach").also { it.mkdirs() }
    val sharedAgentArtifactsDir = defaultAndyAgentArtifactsDir()
    val localAttach = DesktopAgentRunService(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        store = DesktopAgentTaskStore(
            databaseFile = File(attachStoreDir, "agents-${ProcessHandle.current().pid()}.db"),
            // andyd owns agents.db, but scrollback/transcript files live here for replay.
            transcriptsDir = sharedAgentArtifactsDir,
        ),
        locator = AgentCliLocator(),
        adapters = mapOf(
            AgentKind.ClaudeCode to ClaudeCodeAdapter(),
            AgentKind.Codex to CodexAdapter(),
            AgentKind.Cursor to CursorAdapter(),
            AgentKind.Antigravity to AntigravityAdapter(),
            AgentKind.OpenCode to OpenCodeAdapter(),
            AgentKind.Pi to PiAdapter(),
            AgentKind.Hermes to HermesAdapter(),
            AgentKind.OpenClaw to OpenClawAdapter(),
            AgentKind.Goose to GooseAdapter(),
        ),
        worktrees = WorktreeManager(),
        mcp = mcp,
        workspaceStore = store,
        actionConfig = actionConfig,
        enableProbes = false,
        terminalMode = AgentTerminalMode.TmuxWithAttach,
        ownsAgentSessions = false,
    )
    remoteAgents.attachLocalTerminalBridge(localAttach)

    // Kanban persistence lives in ~/.andy/agents.db, which andyd owns in this mode.
    // Do not open a second writer here — use UnavailableKanbanService until the daemon
    // exposes kanban over the socket (same constraint as localAttach's per-pid DB).
    val kanban = UnavailableKanbanService
    val (voiceSetup, voiceDictation) = createDesktopVoicePair()
    val orchestrationPreferences = DesktopOrchestrationPreferencesService()

    updatesScope.launch {
        store.state
            .map { Triple(it.terminalThemeId, it.terminalFontFamilyId, it.terminalFontSize) }
            .distinctUntilChanged()
            .collect {
                actionRuns.reloadAppearance()
                localAttach.reloadTerminalAppearance()
            }
    }

    val localServers = DesktopLocalServerService(
        runner = runner,
        agentRuns = remoteAgents,
        actionRuns = actionRuns,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    val services = AndyServices(
        devices = devices,
        iosDevices = iosDevices,
        avd = avd,
        mirror = mirror,
        logcat = logcat,
        intents = intents,
        apps = apps,
        files = files,
        hostFiles = hostFiles,
        proxy = proxy,
        metrics = metrics,
        accessibility = accessibility,
        viewHierarchy = viewHierarchy,
        bugs = bugService,
        artifacts = DesktopArtifactService(runner, devices, mirror),
        recordingExport = recordingExportService,
        tracing = tracing,
        traceViewer = traceViewer,
        sharedPrefs = sharedPrefs,
        appDatabase = appDatabase,
        dhu = dhu,
        crashInspector = crashInspector,
        heapDump = heapDump,
        evidence = evidenceService,
        workspaceStore = store,
        updates = updates,
        runtimeBundle = runtimeBundle,
        cliUpdates = DesktopCliUpdateCheckService(
            agentRuns = remoteAgents,
            actionRuns = actionRuns,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ),
        mcp = mcp,
        actionConfig = actionConfig,
        actionRuns = actionRuns,
        agentRuns = remoteAgents,
        projectWorkflows = remoteAgents,
        kanban = kanban,
        automations = remoteAgents,
        notificationSounds = DesktopNotificationSoundPlayer(),
        voiceSetup = voiceSetup,
        voiceDictation = voiceDictation,
        orchestrationPreferences = orchestrationPreferences,
        localServers = localServers,
        capabilities = PlatformCapabilities.Desktop.copy(
            acceleratedMirror = NativeMirrorJni.isEmbeddedPresentationSupported(),
        ),
    )
    return DesktopRuntime(services, popOutMirrors)
}

/** In-process daemon (current monolithic path + optional unix socket for CLI). */
private fun createEmbeddedDesktopRuntime(): DesktopRuntime {
    registerArchiveViewShutdownHook()
    val runner = CommandRunner()
    val locator = SdkLocator()
    val store = DesktopWorkspaceStore()
    runBlocking { store.load() }
    val devices = DesktopDeviceService(runner, locator, store)
    val iosDevices = DesktopIosDeviceService(runner)
    val androidMirror = DesktopMirrorEngine(runner, devices)
    val iosMirror = DesktopIosMirrorEngine(iosDevices)
    val mirror = RoutingMirrorEngine(androidMirror, iosMirror)
    val popOutMirrors = DesktopPopOutMirrorPool(
        primary = mirror,
        newAndroid = { DesktopMirrorEngine(runner, devices) },
        newIos = { DesktopIosMirrorEngine(iosDevices) },
    )
    val logcat = DesktopLogcatService(runner, devices)
    val updatesScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val updates = DesktopAppUpdateService(updatesScope)
    val runtimeBundle = DesktopRuntimeBundleService()
    val actionConfig = DesktopActionConfigStore(discoveryRootsProvider = {
        val ws = store.state.value
        (listOf(System.getProperty("user.dir")) + ws.hostFileRoots +
         ws.recentHostFiles.mapNotNull { File(it).parent } +
         listOfNotNull(ws.lastHostFilePath?.let { File(it).parent }))
            .distinct()
    })
    val actionRuns = DesktopActionRunService(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        terminalAppearance = { store.state.value.toTerminalAppearance() },
    )

    val avd = DesktopAvdService(runner, locator) { store.load().selectedSdkPath }
    val intents = DesktopIntentService(runner, devices)
    val apps = DesktopAppService(runner, devices)
    val files = DesktopFileService(runner, devices)
    val hostFiles = DesktopHostFileService(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
    val proxy = DesktopProxyService(runner, devices)
    val accessibility = DesktopAccessibilityService(runner, devices)
    val viewHierarchy = DesktopViewHierarchyService(runner, devices)
    val tracing = DesktopTracingService(runner, devices, files)
    val traceViewer = DesktopTraceViewerService()
    val sharedPrefs = DesktopSharedPrefsService(runner, devices)
    val appDatabase = DesktopAppDatabaseService(runner, devices)
    val dhu = DesktopDhuService(devices = devices, runner = runner)
    val metrics = DesktopMetricsService(runner, devices)
    val crashInspector = DesktopCrashInspectorService(devices)
    val heapDump = DesktopHeapDumpService(runner, devices, files)
    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { traceViewer.shutdown() }
    })

    val bugService = DesktopBugService(
        mirror, logcat,
        devices = devices,
        accessibility = accessibility,
        proxy = proxy,
        metrics = metrics,
        crashInspector = crashInspector,
        viewHierarchy = viewHierarchy,
        apps = apps,
        workspaceStore = store,
        actionConfig = actionConfig,
    )
    val recordingExportService = DesktopRecordingExportService(bugService)
    val evidenceService = DesktopInvestigationEvidenceService(bugService)

    val mcp = DesktopMcpServerService(
        devices = devices,
        avd = avd,
        mirror = mirror,
        logcat = logcat,
        intents = intents,
        apps = apps,
        files = files,
        proxy = proxy,
        accessibility = accessibility,
        viewHierarchy = viewHierarchy,
        workspaceStore = store,
        metrics = metrics,
        crashInspector = crashInspector,
        heapDump = heapDump,
        bugs = bugService,
        recordingExport = recordingExportService,
        actionConfig = actionConfig,
    )

    val agentTaskStore = DesktopAgentTaskStore()
    val agentRuns = DesktopAgentRunService(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        store = agentTaskStore,
        locator = AgentCliLocator(),
        adapters = mapOf(
            AgentKind.ClaudeCode to ClaudeCodeAdapter(),
            AgentKind.Codex to CodexAdapter(),
            AgentKind.Cursor to CursorAdapter(),
            AgentKind.Antigravity to AntigravityAdapter(),
            AgentKind.OpenCode to OpenCodeAdapter(),
            AgentKind.Pi to PiAdapter(),
            AgentKind.Hermes to HermesAdapter(),
            AgentKind.OpenClaw to OpenClawAdapter(),
            AgentKind.Goose to GooseAdapter(),
        ),
        worktrees = WorktreeManager(),
        mcp = mcp,
        workspaceStore = store,
        actionConfig = actionConfig,
    )
    val automations = DesktopAutomationService(
        store = agentTaskStore,
        agentRuns = agentRuns,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        startScheduler = true,
    )
    mcp.bindAgentServices(agentRuns, agentRuns, automations)
    val agentRetention = DesktopAgentRetentionService(
        runService = agentRuns,
        store = agentTaskStore,
        actionConfigStore = actionConfig,
        workspace = store.state,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ).also { it.start() }
    val kanban = DesktopKanbanService(agentTaskStore)
    val (voiceSetup, voiceDictation) = createDesktopVoicePair()
    val orchestrationPreferences = DesktopOrchestrationPreferencesService()

    // Live sessions pick up terminal theme/font changes from Settings.
    updatesScope.launch {
        store.state
            .map { Triple(it.terminalThemeId, it.terminalFontFamilyId, it.terminalFontSize) }
            .distinctUntilChanged()
            .collect {
                actionRuns.reloadAppearance()
                agentRuns.reloadTerminalAppearance()
            }
    }

    // Expose unix socket while GUI embeds the daemon so CLI can attach —
    // but never steal a socket already owned by a standalone `andyd`.
    updatesScope.launch {
        val sock = AndydProcess.socketPath()
        deleteSocketIfStale(sock)
        if (isAndydSocketLive(sock)) {
            System.out.println("andy: embedded mode skipped unix socket; ${sock.absolutePath} already live")
            System.out.flush()
            return@launch
        }
        val result = mcp.startUnixSocket(sock)
        if (!result.isSuccess) {
            System.err.println("andy: embedded unix MCP socket failed: ${result.stderr.ifBlank { result.stdout }}")
        }
    }

    val localServers = DesktopLocalServerService(
        runner = runner,
        agentRuns = agentRuns,
        actionRuns = actionRuns,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    val services = AndyServices(
        devices = devices,
        iosDevices = iosDevices,
        avd = avd,
        mirror = mirror,
        logcat = logcat,
        intents = intents,
        apps = apps,
        files = files,
        hostFiles = hostFiles,
        proxy = proxy,
        metrics = metrics,
        accessibility = accessibility,
        viewHierarchy = viewHierarchy,
        bugs = bugService,
        artifacts = DesktopArtifactService(runner, devices, mirror),
        recordingExport = recordingExportService,
        tracing = tracing,
        traceViewer = traceViewer,
        sharedPrefs = sharedPrefs,
        appDatabase = appDatabase,
        dhu = dhu,
        crashInspector = crashInspector,
        heapDump = heapDump,
        evidence = evidenceService,
        workspaceStore = store,
        updates = updates,
        runtimeBundle = runtimeBundle,
        cliUpdates = DesktopCliUpdateCheckService(
            agentRuns = agentRuns,
            actionRuns = actionRuns,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ),
        mcp = mcp,
        actionConfig = actionConfig,
        actionRuns = actionRuns,
        agentRuns = agentRuns,
        agentRetention = agentRetention,
        projectWorkflows = agentRuns,
        kanban = kanban,
        automations = automations,
        notificationSounds = DesktopNotificationSoundPlayer(),
        voiceSetup = voiceSetup,
        voiceDictation = voiceDictation,
        orchestrationPreferences = orchestrationPreferences,
        localServers = localServers,
        capabilities = PlatformCapabilities.Desktop.copy(
            acceleratedMirror = NativeMirrorJni.isEmbeddedPresentationSupported(),
        ),
    )
    return DesktopRuntime(services, popOutMirrors)
}
