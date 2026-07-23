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
import app.andy.desktop.service.createDesktopServices
import app.andy.desktop.service.DesktopAgentAttentionCoordinator
import app.andy.desktop.service.DesktopOsNotificationService
import app.andy.desktop.service.DesktopWorkspaceStore
import app.andy.desktop.service.PendingAgentTaskOpen
import app.andy.desktop.service.ios.NativeIosDeviceJni
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
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

fun main() {
    installRuntimeAppIcon()
    application {
        val services = remember { createDesktopServices() }
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
        var requestPopOutMirror by remember { mutableStateOf(false) }
        var popOutSerial by remember { mutableStateOf<String?>(null) }
        var popOutDeviceName by remember { mutableStateOf<String?>(null) }
        var popOutControlsVisible by remember { mutableStateOf(false) }
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
        fun quitApp() {
            runBlocking { services.mirror.disconnect(immediate = true) }
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
                    popOutSerial = serial
                    popOutDeviceName = name
                    popOutControlsVisible = false
                },
                contentTopPadding = if (isMacOs()) 28.dp else 18.dp,
            )
        }
        popOutSerial?.let { serial ->
            Window(
                onCloseRequest = { popOutSerial = null },
                state = rememberWindowState(width = 520.dp, height = 900.dp),
                title = "Andy mirror - $serial",
                icon = appIcon,
            ) {
                ApplyMacWindowChrome(
                    windowBackgroundForTint(workspaceState.tintId, workspaceState.surfaceModeId),
                )
                MenuBar {
                    Menu("View") {
                        CheckboxItem(
                            text = "Show controls",
                            checked = popOutControlsVisible,
                            onCheckedChange = { popOutControlsVisible = it },
                            shortcut = popOutMirrorShortcut,
                        )
                    }
                }
                AndyMirrorPopOut(
                    services = services,
                    serial = serial,
                    deviceName = popOutDeviceName,
                    controlsVisible = popOutControlsVisible,
                    tintId = workspaceState.tintId,
                    surfaceModeId = workspaceState.surfaceModeId,
                )
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
