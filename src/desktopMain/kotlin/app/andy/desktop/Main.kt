package app.andy.desktop

import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.andy_robot
import app.andy.AndyDestination
import app.andy.AndyApp
import app.andy.AndyMirrorPopOut
import app.andy.desktop.service.createDesktopServices
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
        val appIcon = painterResource(Res.drawable.andy_robot)
        fun open(destination: AndyDestination) {
            requestedDestination = destination
            visible = true
        }
        DisposableEffect(Unit) {
            val listener = installDockReopenHandler { visible = true }
            onDispose { removeDockReopenHandler(listener) }
        }
        Tray(
            icon = appIcon,
            tooltip = "Andy",
            menu = {
                AndyDestination.entries.filter { it != AndyDestination.Bugs }.forEach { destination ->
                    Item(destination.label, onClick = { open(destination) })
                }
                Item("Show Andy", onClick = { visible = true })
                Item("Quit", onClick = {
                    runBlocking { services.mirror.disconnect() }
                    exitApplication()
                })
            },
        )
        if (visible) Window(
            onCloseRequest = {
                visible = false
            },
            state = windowState,
            title = "Andy",
            icon = appIcon,
        ) {
            LaunchedEffect(window) {
                configureMacTitleBar(window)
            }
            AndyApp(
                services = services,
                requestedDestination = requestedDestination,
                onDestinationConsumed = { requestedDestination = null },
                onPopOutMirror = { popOutSerial = it },
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
                AndyMirrorPopOut(services, serial)
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
