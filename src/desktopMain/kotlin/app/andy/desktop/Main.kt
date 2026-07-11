package app.andy.desktop

import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.kdroid.composetray.tray.api.Tray
import java.awt.Desktop
import java.awt.Taskbar
import java.awt.Color
import java.awt.desktop.AppReopenedListener
import java.awt.desktop.SystemEventListener
import javax.swing.JFrame
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.painterResource

fun main() {
    installRuntimeAppIcon()
    application {
        val services = remember { createDesktopServices() }
        val windowState = rememberWindowState(width = 1800.dp, height = 1040.dp)
        var visible by remember { mutableStateOf(true) }
        var requestedDestination by remember { mutableStateOf<AndyDestination?>(null) }
        var popOutSerial by remember { mutableStateOf<String?>(null) }
        var popOutDeviceName by remember { mutableStateOf<String?>(null) }
        var popOutControlsVisible by remember { mutableStateOf(false) }
        val popOutToggleShortcut = KeyShortcut(Key.D, ctrl = !isMacOs(), meta = isMacOs(), shift = true)
        val appIcon = painterResource(Res.drawable.andy_robot)
        fun open(destination: AndyDestination) {
            requestedDestination = destination
            visible = true
        }
        fun quitApp() {
            runBlocking { services.mirror.disconnect() }
            exitApplication()
        }
        DisposableEffect(Unit) {
            val listener = installDockReopenHandler { visible = true }
            onDispose { removeDockReopenHandler(listener) }
        }
        // Compose Multiplatform AWT Tray is broken on Wayland (white icon, dead menu).
        // ComposeNativeTray uses StatusNotifier / native backends instead.
        Tray(
            icon = Res.drawable.andy_robot,
            tooltip = "Andy",
            primaryAction = { visible = true },
        ) {
            Item(label = "Show Andy") { visible = true }
            Item(label = "Quit") {
                dispose()
                quitApp()
            }
            Divider()
            SubMenu(label = "Go") {
                AndyDestination.entries.filter { it != AndyDestination.Bugs }.forEach { destination ->
                    Item(label = destination.label) { open(destination) }
                }
            }
        }
        // Keep the Window composed while hidden. Removing it from composition when
        // closed would exit the application (ComposeNativeTray does not hold it open).
        Window(
            onCloseRequest = { visible = false },
            visible = visible,
            state = windowState,
            title = "Andy",
            icon = appIcon,
        ) {
            LaunchedEffect(window) {
                configureMacTitleBar(window)
            }
            MenuBar {
                Menu("Go") {
                    AndyDestination.entries.forEach { destination ->
                        Item(
                            destination.label,
                            shortcut = destination.menuShortcut(),
                            onClick = { open(destination) },
                        )
                    }
                }
            }
            AndyApp(
                services = services,
                requestedDestination = requestedDestination,
                onDestinationConsumed = { requestedDestination = null },
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
                MenuBar {
                    Menu("View") {
                        CheckboxItem(
                            text = "Show controls",
                            checked = popOutControlsVisible,
                            onCheckedChange = { popOutControlsVisible = it },
                            shortcut = popOutToggleShortcut,
                        )
                    }
                }
                AndyMirrorPopOut(
                    services = services,
                    serial = serial,
                    deviceName = popOutDeviceName,
                    controlsVisible = popOutControlsVisible,
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

private fun configureMacTitleBar(window: JFrame) {
    runCatching {
        window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
        window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
        window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
        window.rootPane.putClientProperty("apple.awt.noTitleBarSeparator", true)
        window.background = Color(0x14, 0x14, 0x16)
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

private fun isMacOs(): Boolean =
    System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)

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
        // Shift+D is already used by the mirror pop-out "Show controls" toggle.
        AndyDestination.Design -> KeyShortcut(Key.E, meta = meta, ctrl = ctrl, shift = true)
        AndyDestination.Accessibility -> KeyShortcut(Key.A, meta = meta, ctrl = ctrl, shift = true)
        AndyDestination.Bugs -> KeyShortcut(Key.B, meta = meta, ctrl = ctrl, shift = true)
        AndyDestination.Settings -> KeyShortcut(Key.Comma, meta = meta, ctrl = ctrl)
    }
}
