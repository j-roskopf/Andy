package app.andy.desktop

import androidx.compose.ui.graphics.Color
import app.andy.ui.theme.AndySurfaceMode
import app.andy.ui.theme.AndyTint
import app.andy.ui.theme.windowBackgroundForTint
import java.awt.Color as AwtColor
import java.awt.Window as AwtWindow
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.RootPaneContainer
import javax.swing.UIManager
import javax.swing.plaf.ColorUIResource

private const val MenuThemeKey = "andy.swingMenuTheme"
private const val MenuThemeWatcherKey = "andy.swingMenuThemeWatcher"

/**
 * Colors for the in-window Swing [JMenuBar] Compose Desktop uses on Linux/Windows.
 * macOS uses the screen menu bar, so this is a no-op there.
 */
internal data class SwingMenuColors(
    val background: AwtColor,
    val foreground: AwtColor,
    val selectionBackground: AwtColor,
    val popupBackground: AwtColor,
    val border: AwtColor,
    val accelerator: AwtColor,
)

internal fun swingMenuColors(background: Color): SwingMenuColors {
    val luminance =
        0.2126f * background.red + 0.7152f * background.green + 0.0722f * background.blue
    val light = luminance > 0.45f
    val foreground = if (light) Color(0xFF0A1317) else Color(0xFFDFE2E5)
    val accelerator = if (light) Color(0xFF4E606F) else Color(0xFFAAAFB5)
    val border = if (light) Color(0xFFCCD3DB) else Color(0xFF494D53)
    val selection = if (light) {
        blend(Color(0xFF053659), background, 0.10f)
    } else {
        blend(Color.White, background, 0.14f)
    }
    val popup = if (light) Color.White else blend(Color.White, background, 0.08f)
    return SwingMenuColors(
        background = background.toAwtColor(),
        foreground = foreground.toAwtColor(),
        selectionBackground = selection.toAwtColor(),
        popupBackground = popup.toAwtColor(),
        border = border.toAwtColor(),
        accelerator = accelerator.toAwtColor(),
    )
}

/** Call before the first Compose window so the Metal Ocean gradient never flashes white. */
fun installDefaultInWindowMenuBarTheme() {
    if (isMacOs()) return
    installSwingMenuUiDefaults(
        swingMenuColors(
            windowBackgroundForTint(AndyTint.Default.id, AndySurfaceMode.PitchBlack.id),
        ),
    )
}

internal fun applyInWindowMenuBarTheme(window: AwtWindow, background: Color) {
    if (isMacOs()) return
    val colors = swingMenuColors(background)
    installSwingMenuUiDefaults(colors)
    val root = (window as? RootPaneContainer)?.rootPane ?: return
    root.putClientProperty(MenuThemeKey, colors)
    ensureMenuBarWatcher(root)
    root.jMenuBar?.let { themeMenuBar(it, colors) }
}

internal fun installSwingMenuUiDefaults(colors: SwingMenuColors) {
    val bar = ColorUIResource(colors.background)
    val fg = ColorUIResource(colors.foreground)
    val popup = ColorUIResource(colors.popupBackground)
    val selection = ColorUIResource(colors.selectionBackground)
    val accelerator = ColorUIResource(colors.accelerator)
    val border = ColorUIResource(colors.border)
    // Metal Ocean paints MenuBar.gradient (white → gray) unless we flatten it.
    UIManager.put(
        "MenuBar.gradient",
        listOf(0.0f, 0.0f, bar, bar, bar),
    )
    putMenuColors("MenuBar", bar, fg, selection, accelerator)
    UIManager.put("MenuBar.borderColor", border)
    putMenuColors("Menu", bar, fg, selection, accelerator)
    UIManager.put("Menu.opaque", true)
    putMenuColors("MenuItem", popup, fg, selection, accelerator)
    putMenuColors("CheckBoxMenuItem", popup, fg, selection, accelerator)
    putMenuColors("RadioButtonMenuItem", popup, fg, selection, accelerator)
    UIManager.put("PopupMenu.background", popup)
    UIManager.put("PopupMenu.foreground", fg)
    UIManager.put("PopupMenu.border", BorderFactory.createLineBorder(border))
    UIManager.put("Separator.foreground", border)
    UIManager.put("Separator.background", popup)
}

internal fun themeMenuBar(bar: JMenuBar, colors: SwingMenuColors) {
    bar.putClientProperty(MenuThemeKey, colors)
    applyMenuComponent(bar, colors, onBar = true)
    for (index in 0 until bar.menuCount) {
        bar.getMenu(index)?.let { themeMenu(it, colors) }
    }
    ensureChildWatcher(bar)
}

private fun themeMenu(menu: JMenu, colors: SwingMenuColors) {
    applyMenuComponent(menu, colors, onBar = menu.parent is JMenuBar)
    val popup = menu.popupMenu
    applyMenuComponent(popup, colors, onBar = false)
    for (component in popup.components) {
        when (component) {
            is JMenu -> themeMenu(component, colors)
            is JMenuItem -> applyMenuComponent(component, colors, onBar = false)
        }
    }
}

private fun applyMenuComponent(component: JComponent, colors: SwingMenuColors, onBar: Boolean) {
    component.updateUI()
    val background = if (onBar) colors.background else colors.popupBackground
    component.background = background
    component.foreground = colors.foreground
    component.isOpaque = true
    if (component is JMenuBar) {
        component.border = BorderFactory.createMatteBorder(0, 0, 1, 0, colors.border)
    }
}

private fun ensureMenuBarWatcher(root: javax.swing.JRootPane) {
    if (root.getClientProperty(MenuThemeWatcherKey) == true) return
    root.putClientProperty(MenuThemeWatcherKey, true)
    root.addContainerListener(object : ContainerAdapter() {
        override fun componentAdded(event: ContainerEvent) {
            val bar = event.child as? JMenuBar ?: return
            val colors = root.getClientProperty(MenuThemeKey) as? SwingMenuColors ?: return
            themeMenuBar(bar, colors)
        }
    })
}

private fun ensureChildWatcher(bar: JMenuBar) {
    if (bar.getClientProperty(MenuThemeWatcherKey) == true) return
    bar.putClientProperty(MenuThemeWatcherKey, true)
    bar.addContainerListener(object : ContainerAdapter() {
        override fun componentAdded(event: ContainerEvent) {
            val colors = bar.getClientProperty(MenuThemeKey) as? SwingMenuColors ?: return
            when (val child = event.child) {
                is JMenu -> themeMenu(child, colors)
                is JMenuItem -> applyMenuComponent(child, colors, onBar = true)
            }
        }
    })
}

private fun putMenuColors(
    prefix: String,
    background: ColorUIResource,
    foreground: ColorUIResource,
    selection: ColorUIResource,
    accelerator: ColorUIResource,
) {
    UIManager.put("$prefix.background", background)
    UIManager.put("$prefix.foreground", foreground)
    UIManager.put("$prefix.selectionBackground", selection)
    UIManager.put("$prefix.selectionForeground", foreground)
    UIManager.put("$prefix.disabledForeground", accelerator)
    UIManager.put("$prefix.acceleratorForeground", accelerator)
    UIManager.put("$prefix.acceleratorSelectionForeground", foreground)
}

private fun blend(overlay: Color, base: Color, amount: Float): Color = Color(
    red = overlay.red * amount + base.red * (1f - amount),
    green = overlay.green * amount + base.green * (1f - amount),
    blue = overlay.blue * amount + base.blue * (1f - amount),
    alpha = 1f,
)

private fun Color.toAwtColor(): AwtColor = AwtColor(red, green, blue)
