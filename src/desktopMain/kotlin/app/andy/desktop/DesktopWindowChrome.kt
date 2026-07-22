package app.andy.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import java.awt.Color as AwtColor
import java.awt.Window as AwtWindow
import javax.swing.RootPaneContainer

fun isMacOs(): Boolean =
    System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)

val macTitleBarContentInset: Dp
    get() = if (isMacOs()) 28.dp else 0.dp

fun configureMacTitleBar(window: AwtWindow, background: Color) {
    val rootPane = (window as? RootPaneContainer)?.rootPane ?: return
    runCatching {
        rootPane.putClientProperty("apple.awt.fullWindowContent", true)
        rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
        rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
        rootPane.putClientProperty("apple.awt.noTitleBarSeparator", true)
        window.background = AwtColor(background.red, background.green, background.blue)
    }
}

@Composable
fun WindowScope.ApplyMacWindowChrome(background: Color) {
    LaunchedEffect(window, background) {
        configureMacTitleBar(window, background)
    }
}
