package app.andy.desktop.service

import app.andy.desktop.service.agents.AgentCliLocator
import app.andy.desktop.service.agents.AntigravityAdapter
import app.andy.desktop.service.agents.ClaudeCodeAdapter
import app.andy.desktop.service.agents.CodexAdapter
import app.andy.desktop.service.agents.CursorAdapter
import app.andy.desktop.service.agents.DesktopAgentRunService
import app.andy.desktop.service.agents.DesktopAgentTaskStore
import app.andy.desktop.service.agents.WorktreeManager
import app.andy.desktop.service.mirror.DesktopMirrorEngine
import app.andy.desktop.service.mirror.NativeMirrorJni
import app.andy.desktop.service.proxy.DesktopProxyService
import app.andy.model.AgentKind
import app.andy.desktop.updates.DesktopAppUpdateService
import app.andy.service.AndyServices
import app.andy.service.PlatformCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun createDesktopServices(): AndyServices {
    val runner = CommandRunner()
    val locator = SdkLocator()
    val store = DesktopWorkspaceStore()
    val devices = DesktopDeviceService(runner, locator, store)
    val mirror = DesktopMirrorEngine(runner, devices)
    val logcat = DesktopLogcatService(runner, devices)
    val updatesScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val updates = DesktopAppUpdateService(updatesScope)
    val actionConfig = DesktopActionConfigStore()
    val actionRuns = DesktopActionRunService(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    val avd = DesktopAvdService(runner, locator) { store.load().selectedSdkPath }
    val intents = DesktopIntentService(runner, devices)
    val apps = DesktopAppService(runner, devices)
    val files = DesktopFileService(runner, devices)
    val hostFiles = DesktopHostFileService(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
    val proxy = DesktopProxyService(runner, devices)
    val accessibility = DesktopAccessibilityService(runner, devices)

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

    return AndyServices(
        devices = devices,
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
        workspaceStore = store,
        updates = updates,
        mcp = mcp,
        actionConfig = actionConfig,
        actionRuns = actionRuns,
        agentRuns = agentRuns,
        capabilities = PlatformCapabilities.Desktop.copy(
            acceleratedMirror = NativeMirrorJni.isEmbeddedPresentationSupported(),
        ),
    )
}
