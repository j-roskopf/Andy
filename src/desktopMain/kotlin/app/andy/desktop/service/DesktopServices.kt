package app.andy.desktop.service

import app.andy.desktop.service.mirror.DesktopMirrorEngine
import app.andy.desktop.service.proxy.DesktopProxyService
import app.andy.desktop.updates.DesktopAppUpdateService
import app.andy.service.AndyServices
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
    )
}
