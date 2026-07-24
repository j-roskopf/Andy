package app.andy.desktop.service

import app.andy.desktop.service.agents.AgentCliLocator
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
import app.andy.service.PlatformCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun createDesktopServices(): AndyServices = createDesktopRuntime().services

data class DesktopRuntime(
    val services: AndyServices,
    val popOutMirrors: DesktopPopOutMirrorPool,
)

fun createDesktopRuntime(): DesktopRuntime {
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
