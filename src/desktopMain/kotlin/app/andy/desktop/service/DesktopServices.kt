package app.andy.desktop.service

import app.andy.desktop.service.agents.AgentCliLocator
import app.andy.desktop.service.agents.AgentTerminalMode
import app.andy.desktop.service.agents.AntigravityAdapter
import app.andy.desktop.service.agents.ClaudeCodeAdapter
import app.andy.desktop.service.agents.CodexAdapter
import app.andy.desktop.service.agents.CursorAdapter
import app.andy.desktop.service.agents.DesktopAgentRunService
import app.andy.desktop.service.agents.DesktopAgentTaskStore
import app.andy.desktop.service.agents.WorktreeManager
import app.andy.desktop.service.inspector.DesktopAppDatabaseService
import app.andy.desktop.service.inspector.DesktopSharedPrefsService
import app.andy.desktop.service.ios.DesktopIosDeviceService
import app.andy.desktop.service.ios.DesktopIosMirrorEngine
import app.andy.desktop.service.mirror.DesktopMirrorEngine
import app.andy.desktop.service.mirror.DesktopPopOutMirrorPool
import app.andy.service.RoutingMirrorEngine
import app.andy.desktop.service.mirror.NativeMirrorJni
import app.andy.desktop.service.proxy.DesktopProxyService
import app.andy.desktop.service.tracing.DesktopTraceViewerService
import app.andy.desktop.service.tracing.DesktopTracingService
import app.andy.model.AgentKind
import app.andy.model.toTerminalAppearance
import app.andy.desktop.updates.DesktopAppUpdateService
import app.andy.service.AndyServices
import app.andy.service.CommandResult
import app.andy.service.PlatformCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

fun createDesktopServices(): AndyServices = createDesktopRuntime().services

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

fun detectRuntimeMode(): RuntimeMode {
    val sock = File(System.getProperty("user.home"), ".andy/andyd.sock")
    return if (isAndydSocketLive(sock)) RuntimeMode.DaemonClient else RuntimeMode.EmbeddedDaemon
}

/**
 * True only when [socketPath] accepts a Unix-domain connection.
 * A leftover `andyd.sock` file after a crash must not force DaemonClient mode
 * (that leaves the GUI with an empty chat list).
 */
internal fun isAndydSocketLive(socketPath: File): Boolean {
    if (!socketPath.exists()) return false
    return runCatching {
        java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { channel ->
            channel.configureBlocking(true)
            channel.connect(java.net.UnixDomainSocketAddress.of(socketPath.toPath()))
            true
        }
    }.getOrDefault(false)
}

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
    val runner = CommandRunner()
    val locator = SdkLocator()
    val store = DesktopWorkspaceStore()
    val devices = DesktopDeviceService(runner, locator, store)
    val iosDevices = DesktopIosDeviceService(runner)
    val androidMirror = DesktopMirrorEngine(runner, devices)
    val iosMirror = DesktopIosMirrorEngine(iosDevices)
    val mirror = RoutingMirrorEngine(androidMirror, iosMirror)
    val logcat = DesktopLogcatService(runner, devices)
    val actionConfig = DesktopActionConfigStore()
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
    val tracing = DesktopTracingService(runner, devices, files)
    val traceViewer = DesktopTraceViewerService()
    val sharedPrefs = DesktopSharedPrefsService(runner, devices)
    val appDatabase = DesktopAppDatabaseService(runner, devices)

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
        workspaceStore = store,
    )

    val agentRuns = DesktopAgentRunService(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        store = DesktopAgentTaskStore(),
        locator = AgentCliLocator(),
        adapters = mapOf(
            AgentKind.ClaudeCode to ClaudeCodeAdapter(),
            AgentKind.Codex to CodexAdapter(),
            AgentKind.Cursor to CursorAdapter(),
            AgentKind.Antigravity to AntigravityAdapter(),
        ),
        worktrees = WorktreeManager(),
        mcp = mcp,
        workspaceStore = store,
        actionConfig = actionConfig,
        terminalMode = AgentTerminalMode.TmuxHeadless,
    )
    mcp.bindAgentServices(agentRuns, agentRuns)

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
        metrics = DesktopMetricsService(runner, devices),
        accessibility = accessibility,
        bugs = DesktopBugService(mirror, logcat, devices = devices, accessibility = accessibility),
        artifacts = DesktopArtifactService(runner, devices, mirror),
        tracing = tracing,
        traceViewer = traceViewer,
        sharedPrefs = sharedPrefs,
        appDatabase = appDatabase,
        workspaceStore = store,
        updates = DesktopAppUpdateService(CoroutineScope(SupervisorJob() + Dispatchers.Default)),
        mcp = mcp,
        actionConfig = actionConfig,
        actionRuns = actionRuns,
        agentRuns = agentRuns,
        projectWorkflows = agentRuns,
        notificationSounds = DesktopNotificationSoundPlayer(),
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
    val port = runBlocking { store.load() }.mcpServerPort
    System.err.println("andyd: binding HTTP MCP on 127.0.0.1:$port")
    val httpResult = runCatching { mcp.startHttpBlocking(port) }
        .getOrElse { error ->
            error.printStackTrace()
            CommandResult.failure(error.message ?: "HTTP start failed")
        }
    if (httpResult.isSuccess) {
        System.err.println("andyd: MCP HTTP on 127.0.0.1:$port")
    } else {
        System.err.println(
            "andyd: WARNING HTTP MCP failed (${httpResult.stderr.ifBlank { httpResult.stdout }}); " +
                "continuing with unix socket only",
        )
    }

    return DaemonRuntime(
        services = services,
        socketPath = socketPath,
        mcp = mcp,
        onShutdown = {
            runCatching { mcp.stopUnixSocketBlocking() }
            runBlocking {
                runCatching { mcp.stop() }
                runCatching { traceViewer.shutdown() }
            }
        },
    )
}

fun createDesktopRuntime(mode: RuntimeMode = detectRuntimeMode()): DesktopRuntime {
    if (mode == RuntimeMode.DaemonClient) {
        return createDesktopClientRuntime()
    }
    return createEmbeddedDesktopRuntime()
}

/** GUI as MCP client of a running `andyd` — local attach terminals only. */
private fun createDesktopClientRuntime(): DesktopRuntime {
    val runner = CommandRunner()
    val locator = SdkLocator()
    val store = DesktopWorkspaceStore()
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
    val actionConfig = DesktopActionConfigStore()
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
    val tracing = DesktopTracingService(runner, devices, files)
    val traceViewer = DesktopTraceViewerService()
    val sharedPrefs = DesktopSharedPrefsService(runner, devices)
    val appDatabase = DesktopAppDatabaseService(runner, devices)
    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { traceViewer.shutdown() }
    })

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
        workspaceStore = store,
    )

    val socket = File(System.getProperty("user.home"), ".andy/andyd.sock")
    val remoteAgents = McpAgentRunClient(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        socketPath = socket,
    )
    // Local attach-only terminal surface for live tmux sessions owned by andyd.
    val localAttach = DesktopAgentRunService(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        store = DesktopAgentTaskStore(),
        locator = AgentCliLocator(),
        adapters = mapOf(
            AgentKind.ClaudeCode to ClaudeCodeAdapter(),
            AgentKind.Codex to CodexAdapter(),
            AgentKind.Cursor to CursorAdapter(),
            AgentKind.Antigravity to AntigravityAdapter(),
        ),
        worktrees = WorktreeManager(),
        mcp = mcp,
        workspaceStore = store,
        actionConfig = actionConfig,
        enableProbes = false,
        terminalMode = AgentTerminalMode.TmuxWithAttach,
    )
    remoteAgents.attachLocalTerminalBridge(localAttach)

    updatesScope.launch {
        store.state
            .map { Triple(it.terminalThemeId, it.terminalFontFamilyId, it.terminalFontSize) }
            .distinctUntilChanged()
            .collect {
                actionRuns.reloadAppearance()
                localAttach.reloadTerminalAppearance()
            }
    }

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
        metrics = DesktopMetricsService(runner, devices),
        accessibility = accessibility,
        bugs = DesktopBugService(mirror, logcat, devices = devices, accessibility = accessibility),
        artifacts = DesktopArtifactService(runner, devices, mirror),
        tracing = tracing,
        traceViewer = traceViewer,
        sharedPrefs = sharedPrefs,
        appDatabase = appDatabase,
        workspaceStore = store,
        updates = updates,
        mcp = mcp,
        actionConfig = actionConfig,
        actionRuns = actionRuns,
        agentRuns = remoteAgents,
        projectWorkflows = remoteAgents,
        notificationSounds = DesktopNotificationSoundPlayer(),
        capabilities = PlatformCapabilities.Desktop.copy(
            acceleratedMirror = NativeMirrorJni.isEmbeddedPresentationSupported(),
        ),
    )
    return DesktopRuntime(services, popOutMirrors)
}

/** In-process daemon (current monolithic path + optional unix socket for CLI). */
private fun createEmbeddedDesktopRuntime(): DesktopRuntime {
    val runner = CommandRunner()
    val locator = SdkLocator()
    val store = DesktopWorkspaceStore()
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
    val actionConfig = DesktopActionConfigStore()
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
    val tracing = DesktopTracingService(runner, devices, files)
    val traceViewer = DesktopTraceViewerService()
    val sharedPrefs = DesktopSharedPrefsService(runner, devices)
    val appDatabase = DesktopAppDatabaseService(runner, devices)
    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { traceViewer.shutdown() }
    })

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
        workspaceStore = store
    )

    val agentRuns = DesktopAgentRunService(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        store = DesktopAgentTaskStore(),
        locator = AgentCliLocator(),
        adapters = mapOf(
            AgentKind.ClaudeCode to ClaudeCodeAdapter(),
            AgentKind.Codex to CodexAdapter(),
            AgentKind.Cursor to CursorAdapter(),
            AgentKind.Antigravity to AntigravityAdapter(),
        ),
        worktrees = WorktreeManager(),
        mcp = mcp,
        workspaceStore = store,
        actionConfig = actionConfig,
    )
    mcp.bindAgentServices(agentRuns, agentRuns)

    // Live sessions pick up KetraTerm theme/font changes from Settings.
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
    // but never steal a socket already owned by a standalone andyd.
    updatesScope.launch {
        val sock = File(System.getProperty("user.home"), ".andy/andyd.sock")
        if (sock.exists()) {
            System.out.println("andy: embedded mode skipped unix socket; ${sock.absolutePath} already present")
            System.out.flush()
            return@launch
        }
        val result = mcp.startUnixSocket(sock)
        if (!result.isSuccess) {
            System.err.println("andy: embedded unix MCP socket failed: ${result.stderr.ifBlank { result.stdout }}")
        }
    }

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
        metrics = DesktopMetricsService(runner, devices),
        accessibility = accessibility,
        bugs = DesktopBugService(mirror, logcat, devices = devices, accessibility = accessibility),
        artifacts = DesktopArtifactService(runner, devices, mirror),
        tracing = tracing,
        traceViewer = traceViewer,
        sharedPrefs = sharedPrefs,
        appDatabase = appDatabase,
        workspaceStore = store,
        updates = updates,
        mcp = mcp,
        actionConfig = actionConfig,
        actionRuns = actionRuns,
        agentRuns = agentRuns,
        projectWorkflows = agentRuns,
        notificationSounds = DesktopNotificationSoundPlayer(),
        capabilities = PlatformCapabilities.Desktop.copy(
            acceleratedMirror = NativeMirrorJni.isEmbeddedPresentationSupported(),
        ),
    )
    return DesktopRuntime(services, popOutMirrors)
}
