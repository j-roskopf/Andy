package app.andy.desktop

import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.andy_robot
import app.andy.AndyDestination
import app.andy.AndyApp
import app.andy.AndyMirrorPopOut
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import app.andy.desktop.service.ios.NativeIosDeviceJni
import app.andy.desktop.service.DesktopAgentAttentionCoordinator
import app.andy.desktop.service.DesktopOsNotificationService
import app.andy.desktop.service.DesktopWorkspaceStore
import app.andy.desktop.service.PendingAgentTaskOpen
import app.andy.desktop.service.createDesktopRuntime
import app.andy.desktop.service.mirror.GpuMirrorJni
import app.andy.desktop.service.mirror.NativeMirrorHostRegistry
import app.andy.model.IosTargetKind
import app.andy.service.IosTargetRegistry
import app.andy.service.MirrorEngine
import app.andy.service.OpenAgentTaskRequest
import app.andy.ui.theme.windowBackgroundForTint
import com.kdroid.composetray.tray.api.Tray
import java.awt.Desktop
import java.awt.Taskbar
import java.awt.desktop.AppReopenedListener
import java.awt.desktop.SystemEventListener
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

private data class MirrorPopOutWindow(
    val targetId: String,
    val displayName: String,
    val controlsVisible: Boolean = false,
    /** Reuse the Live mirror session (GPU) while it still targets this device. */
    val preferPrimaryMirror: Boolean = false,
)

fun main() {
    installRuntimeAppIcon()
    application {
        val runtime = remember { createDesktopRuntime() }
        val services = runtime.services
        val popOutMirrorPool = runtime.popOutMirrors
        val workspaceStore = services.workspaceStore as DesktopWorkspaceStore
        val workspaceState by workspaceStore.state.collectAsState()
        val agentTasks by services.agentRuns.tasks.collectAsState()
        // Dock/tray/title reflect every finished chat waiting for review.
        // In-app, unread still routes to Agents vs Actions by projectId.
        val unreadCount = agentTasks.count { it.unread }
        val windowState = rememberWindowState(width = 1800.dp, height = 1072.dp)
        var visible by remember { mutableStateOf(true) }
        var requestedDestination by remember { mutableStateOf<AndyDestination?>(null) }
        var requestedOpenAgentTask by remember { mutableStateOf<OpenAgentTaskRequest?>(null) }
        val appFocus = remember { AppFocusState() }
        val scope = rememberCoroutineScope()
        var requestPopOutMirror by remember { mutableStateOf(false) }
        var popOutWindows by remember { mutableStateOf(mapOf<String, MirrorPopOutWindow>()) }
        // iOS sims handed off to Simulator.app: Andy stops mirroring and defers to that window.
        // Cleared automatically when the Simulator device window closes (see reconcile below).
        var externalSimulatorMirrors by remember {
            mutableStateOf(mapOf<String, ExternalSimulatorMirrorWatch>())
        }
        val externallyMirrored = externalSimulatorMirrors.keys
        val popOutMirrorShortcut = KeyShortcut(Key.D, ctrl = !isMacOs(), meta = isMacOs(), shift = true)
        val appIcon = painterResource(Res.drawable.andy_robot)
        fun open(destination: AndyDestination) {
            requestedDestination = destination
            visible = true
        }
        fun consumePendingOpen() {
            PendingAgentTaskOpen.consume()?.let { requestedOpenAgentTask = it }
        }
        fun openFromNotification() {
            visible = true
            consumePendingOpen()
        }
        fun openPopOutMirror() {
            visible = true
            requestPopOutMirror = true
        }
        // iOS Simulator already has a host window (Simulator.app), so pop-out hands off to that
        // app and Andy stops mirroring. Android emulators are launched with -qt-hide-window, so
        // there is no usable native window to reveal — they (and physical devices) open a
        // dedicated Andy mirror window instead. Either way Live hands the device off so it never
        // renders in two places at once.
        fun usesExternalDeviceApp(targetId: String): Boolean =
            IosTargetRegistry.isIosTarget(targetId) &&
                IosTargetRegistry.target(targetId)?.kind == IosTargetKind.Simulator
        fun focusExternalDeviceApp(targetId: String) {
            // SimulatorKit capture is headless; bring Simulator.app forward when handing off.
            if (IosTargetRegistry.isIosTarget(targetId)) {
                runCatching { ProcessBuilder("open", "-a", "Simulator").start() }
            }
        }
        fun addPopOutWindow(targetId: String, displayName: String, preferPrimaryMirror: Boolean) {
            if (targetId in popOutWindows) return
            popOutWindows = popOutWindows + (
                targetId to MirrorPopOutWindow(
                    targetId = targetId,
                    displayName = displayName,
                    preferPrimaryMirror = preferPrimaryMirror,
                )
                )
        }
        fun startPopOut(targetId: String, displayName: String) {
            if (usesExternalDeviceApp(targetId)) {
                if (targetId !in externalSimulatorMirrors) {
                    externalSimulatorMirrors = externalSimulatorMirrors + (
                        targetId to ExternalSimulatorMirrorWatch(targetId, displayName)
                        )
                }
                focusExternalDeviceApp(targetId)
            } else {
                // Same device as Live → fan out a second GPU presenter from the primary session
                // (Android allows only one scrcpy). Other devices get a dedicated pool engine.
                val preferPrimary = services.mirror.session.value?.serial == targetId
                addPopOutWindow(targetId, displayName, preferPrimaryMirror = preferPrimary)
            }
        }
        // Toggle used by the main Live pop-out button / hand-off placeholder.
        fun togglePopOut(targetId: String, displayName: String) {
            when (targetId) {
                in externalSimulatorMirrors -> {
                    externalSimulatorMirrors = externalSimulatorMirrors - targetId
                    // Manual "Mirror in Andy again" — tuck Simulator away like auto-resume.
                    scope.launch(Dispatchers.IO) { services.iosDevices.hideSimulatorApp() }
                }
                in popOutWindows -> Unit // Managed by the pop-out window's own close.
                else -> startPopOut(targetId, displayName)
            }
        }
        fun closePopOutWindow(targetId: String, mirror: MirrorEngine) {
            popOutWindows = popOutWindows - targetId
            if (mirror !== services.mirror) {
                scope.launch { popOutMirrorPool.release(targetId) }
            }
            if (popOutWindows.isEmpty()) {
                NativeMirrorHostRegistry.clearPopOutPresentation()
            }
        }
        fun quitApp() {
            runBlocking {
                popOutMirrorPool.releaseAll()
                services.mirror.disconnect(immediate = true)
            }
            exitApplication()
        }
        DisposableEffect(Unit) {
            PendingAgentTaskOpen.setActivationHandler {
                // Native notification clicks arrive off the Compose clock.
                java.awt.EventQueue.invokeLater { openFromNotification() }
            }
            val listener = installDockReopenHandler { openFromNotification() }
            onDispose {
                PendingAgentTaskOpen.setActivationHandler(null)
                removeDockReopenHandler(listener)
            }
        }
        LaunchedEffect(Unit) {
            DesktopAgentAttentionCoordinator(
                scope = this, tasks = services.agentRuns.tasks,
                workspace = { workspaceStore.state.value }, isForeground = appFocus::isForeground,
                notifications = DesktopOsNotificationService(), sounds = services.notificationSounds,
            ).start()
        }
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                NativeIosDeviceJni.prepareForCapture()
            }
        }
        // After an iOS pop-out handoff, watch Simulator.app device windows. Closing the window
        // (without needing Quit) clears the handoff so Live reconnects automatically.
        LaunchedEffect(externalSimulatorMirrors.keys) {
            if (externalSimulatorMirrors.isEmpty()) return@LaunchedEffect
            while (true) {
                delay(500)
                val snapshot = externalSimulatorMirrors
                if (snapshot.isEmpty()) return@LaunchedEffect
                val reconciled = withContext(Dispatchers.IO) {
                    reconcileExternalSimulatorMirrors(snapshot) { displayName ->
                        services.iosDevices.hasVisibleSimulatorDeviceWindow(displayName)
                    }
                }
                val closed = snapshot.keys - reconciled.keys
                if (closed.isNotEmpty()) {
                    // Hide Simulator before Live remounts so windows aren't racing the Metal host.
                    withContext(Dispatchers.IO) {
                        services.iosDevices.hideSimulatorApp()
                    }
                    delay(150)
                    // Only remove windows that closed; keep targets added/cleared mid-poll.
                    externalSimulatorMirrors = externalSimulatorMirrors - closed
                    if (externalSimulatorMirrors.isEmpty()) return@LaunchedEffect
                } else if (reconciled != snapshot) {
                    // seenWindow flags advanced — merge without dropping newer entries.
                    externalSimulatorMirrors = externalSimulatorMirrors.mapValues { (id, watch) ->
                        reconciled[id] ?: watch
                    }
                }
            }
        }
        LaunchedEffect(unreadCount, workspaceState.agentIconBadgeEnabled) {
            updateDockBadge(if (workspaceState.agentIconBadgeEnabled) unreadCount else 0)
        }
        // Compose Multiplatform AWT Tray is broken on Wayland (white icon, dead menu).
        // ComposeNativeTray uses StatusNotifier / native backends instead.
        Tray(
            icon = Res.drawable.andy_robot,
            tooltip = if (unreadCount > 0) "Andy ($unreadCount unread)" else "Andy",
            primaryAction = { openFromNotification() },
        ) {
            Item(label = "Show Andy") { openFromNotification() }
            Item(label = "Quit") {
                dispose()
                quitApp()
            }
            Divider()
            SubMenu(label = "Go") {
                services.capabilities.destinations.filter { it != AndyDestination.Bugs }.forEach { destination ->
                    Item(label = destination.label) { open(destination) }
                }
                Item(label = "Tracing") { open(AndyDestination.Tracing) }
            }
        }
        // Keep the Window composed while hidden. Removing it from composition when
        // closed would exit the application (ComposeNativeTray does not hold it open).
        Window(
            onCloseRequest = { visible = false },
            visible = visible,
            state = windowState,
            title = if (unreadCount > 0) "Andy ($unreadCount)" else "Andy",
            icon = appIcon,
        ) {
            LaunchedEffect(visible) {
                appFocus.visible = visible
                if (visible) consumePendingOpen()
            }
            DisposableEffect(window) {
                val listener = object : java.awt.event.WindowAdapter() {
                    override fun windowActivated(event: java.awt.event.WindowEvent) {
                        appFocus.focused = true
                        consumePendingOpen()
                    }

                    override fun windowDeactivated(event: java.awt.event.WindowEvent) {
                        appFocus.focused = false
                    }
                }
                window.addWindowListener(listener)
                onDispose { window.removeWindowListener(listener) }
            }
            ApplyMacWindowChrome(
                windowBackgroundForTint(workspaceState.tintId, workspaceState.surfaceModeId),
            )
            MenuBar {
                Menu("Go") {
                    services.capabilities.destinations.forEach { destination ->
                        Item(
                            destination.label,
                            shortcut = destination.menuShortcut(),
                            onClick = { open(destination) },
                        )
                    }
                    Item(
                        "Tracing",
                        shortcut = AndyDestination.Tracing.menuShortcut(),
                        onClick = { open(AndyDestination.Tracing) },
                    )
                }
                Menu("View") {
                    Item(
                        "Pop Out Mirror",
                        shortcut = popOutMirrorShortcut,
                        onClick = { openPopOutMirror() },
                    )
                }
            }
            AndyApp(
                services = services,
                requestedDestination = requestedDestination,
                onDestinationConsumed = { requestedDestination = null },
                requestedOpenAgentTask = requestedOpenAgentTask,
                onOpenAgentTaskConsumed = { requestedOpenAgentTask = null },
                requestPopOutMirror = requestPopOutMirror,
                onPopOutMirrorRequestConsumed = { requestPopOutMirror = false },
                onPopOutMirror = { serial, name ->
                    if (serial != null && name != null) {
                        togglePopOut(serial, name)
                    }
                },
                onPopOutDevice = { targetId, displayName ->
                    startPopOut(targetId, displayName)
                },
                // Devices handed off (Andy pop-out window OR Simulator.app): main view shows a
                // placeholder. The Live mirror session stays warm so closing the handoff remounts
                // presenters instead of reconnecting into a black surface.
                poppedOutTargetIds = popOutWindows.keys + externallyMirrored,
                contentTopPadding = if (isMacOs()) 28.dp else 18.dp,
            )
        }
        val primaryMirrorSerial = services.mirror.session.collectAsState().value?.serial
        popOutWindows.values.forEach { popOut ->
            key(popOut.targetId) {
                val sharePrimary =
                    popOut.preferPrimaryMirror && primaryMirrorSerial == popOut.targetId
                val mirrorEngine: MirrorEngine = when {
                    sharePrimary -> services.mirror
                    else -> remember(popOut.targetId) { popOutMirrorPool.acquire(popOut.targetId) }
                }
                val gpuPresentation = GpuMirrorJni.isAvailable() ||
                    IosTargetRegistry.isIosTarget(popOut.targetId) ||
                    mirrorEngine === services.mirror
                Window(
                    onCloseRequest = { closePopOutWindow(popOut.targetId, mirrorEngine) },
                    state = rememberWindowState(width = 520.dp, height = 900.dp),
                    title = "Andy mirror - ${popOut.displayName}",
                    icon = appIcon,
                ) {
                    ApplyMacWindowChrome(
                        windowBackgroundForTint(workspaceState.tintId, workspaceState.surfaceModeId),
                    )
                    // The legacy single-overlay promotion (this effect) resurrects the shared
                    // NativeMirror overlay as a floating window on top of the multi-presenter GPU
                    // hub. Only let it run when the hub is unavailable (CPU fallback); with the hub
                    // active each pop-out renders through its own GpuMirrorPresenter.
                    PopOutMirrorPresentationEffect(
                        ownsMetalPresentation = !GpuMirrorJni.isAvailable() && sharePrimary,
                    )
                    MenuBar {
                        Menu("View") {
                            CheckboxItem(
                                text = "Show controls",
                                checked = popOut.controlsVisible,
                                onCheckedChange = { checked ->
                                    popOutWindows = popOutWindows + (
                                        popOut.targetId to popOut.copy(controlsVisible = checked)
                                        )
                                },
                                shortcut = popOutMirrorShortcut,
                            )
                        }
                    }
                    AndyMirrorPopOut(
                        services = services,
                        serial = popOut.targetId,
                        deviceName = popOut.displayName,
                        mirror = mirrorEngine,
                        gpuPresentation = gpuPresentation,
                        // WindowScope.window — not the forEach MirrorPopOutWindow data class.
                        mirrorHostWindow = window,
                        controlsVisible = popOut.controlsVisible,
                        contentTopPadding = macTitleBarContentInset,
                        tintId = workspaceState.tintId,
                        surfaceModeId = workspaceState.surfaceModeId,
                    )
                }
            }
        }
    }
}

private fun installRuntimeAppIcon() {
    System.setProperty("apple.awt.application.name", "Andy")
    System.setProperty("apple.awt.fullWindowContent", "true")
    System.setProperty("apple.awt.transparentTitleBar", "true")
    System.setProperty("apple.awt.windowTitleVisible", "false")
    System.setProperty("apple.awt.noTitleBarSeparator", "true")
    val iconFile = File("src/commonMain/composeResources/drawable/andy_robot.png")
    if (!iconFile.exists()) return
    runCatching {
        val image = ImageIO.read(iconFile)
        if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
            Taskbar.getTaskbar().iconImage = image
        }
    }
}

private fun updateDockBadge(count: Int) {
    if (!Taskbar.isTaskbarSupported()) return
    val taskbar = Taskbar.getTaskbar()
    if (!taskbar.isSupported(Taskbar.Feature.ICON_BADGE_NUMBER)) return
    runCatching {
        taskbar.setIconBadge(if (count > 0) count.toString() else null)
    }
}

private fun installDockReopenHandler(onReopen: () -> Unit): SystemEventListener? {
    if (!isMacOs() || !Desktop.isDesktopSupported()) return null
    val listener = AppReopenedListener { onReopen() }
    return runCatching {
        Desktop.getDesktop().addAppEventListener(listener)
        listener
    }.getOrNull()
}

private fun removeDockReopenHandler(listener: SystemEventListener?) {
    if (listener == null || !Desktop.isDesktopSupported()) return
    runCatching {
        Desktop.getDesktop().removeAppEventListener(listener)
    }
}

/** Thread-safe focus snapshot read by the background attention coordinator. */
private class AppFocusState {
    @Volatile var visible: Boolean = true
    @Volatile var focused: Boolean = true

    fun isForeground(): Boolean = visible && focused
}

/** Cmd/Ctrl+1–0 for the first ten pages; letter shortcuts for the rest. */
private fun AndyDestination.menuShortcut(): KeyShortcut {
    val meta = isMacOs()
    val ctrl = !isMacOs()
    return when (this) {
        AndyDestination.Devices -> KeyShortcut(Key.One, meta = meta, ctrl = ctrl)
        AndyDestination.Catalog -> KeyShortcut(Key.Two, meta = meta, ctrl = ctrl)
        AndyDestination.Live -> KeyShortcut(Key.Three, meta = meta, ctrl = ctrl)
        AndyDestination.Apps -> KeyShortcut(Key.Four, meta = meta, ctrl = ctrl)
        AndyDestination.Logcat -> KeyShortcut(Key.Five, meta = meta, ctrl = ctrl)
        AndyDestination.Intents -> KeyShortcut(Key.Six, meta = meta, ctrl = ctrl)
        AndyDestination.Files -> KeyShortcut(Key.Seven, meta = meta, ctrl = ctrl)
        AndyDestination.ComputerFiles -> KeyShortcut(Key.Eight, meta = meta, ctrl = ctrl)
        AndyDestination.Network -> KeyShortcut(Key.Nine, meta = meta, ctrl = ctrl)
        AndyDestination.Actions -> KeyShortcut(Key.Zero, meta = meta, ctrl = ctrl)
        AndyDestination.Agents -> KeyShortcut(Key.G, meta = meta, ctrl = ctrl, shift = true)
        AndyDestination.Snapshots -> KeyShortcut(Key.S, meta = meta, ctrl = ctrl, shift = true)
        AndyDestination.Controls -> KeyShortcut(Key.C, meta = meta, ctrl = ctrl, shift = true)
        AndyDestination.Performance -> KeyShortcut(Key.P, meta = meta, ctrl = ctrl, shift = true)
        AndyDestination.Tracing -> KeyShortcut(Key.T, meta = meta, ctrl = ctrl, shift = true)
        // Shift+D is already used by View → Pop Out Mirror / Show controls.
        AndyDestination.Design -> KeyShortcut(Key.E, meta = meta, ctrl = ctrl, shift = true)
        AndyDestination.Accessibility -> KeyShortcut(Key.A, meta = meta, ctrl = ctrl, shift = true)
        AndyDestination.Bugs -> KeyShortcut(Key.B, meta = meta, ctrl = ctrl, shift = true)
        AndyDestination.Recordings -> KeyShortcut(Key.R, meta = meta, ctrl = ctrl, shift = true)
        AndyDestination.Settings -> KeyShortcut(Key.Comma, meta = meta, ctrl = ctrl)
    }
}
