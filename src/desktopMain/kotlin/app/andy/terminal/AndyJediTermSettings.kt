package app.andy.terminal

import app.andy.applyAndyScrollTheme
import app.andy.model.TerminalAppearanceSnapshot
import app.andy.model.TerminalColorPaletteKind
import app.andy.model.terminalHexRgb
import com.jediterm.terminal.HyperlinkStyle
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.emulator.ColorPalette
import com.jediterm.terminal.emulator.ColorPaletteImpl
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import java.awt.Component
import java.awt.Container
import java.awt.Font
import javax.swing.JScrollBar

/** Builds a JediTerm widget with Andy appearance + scrollbar chrome. */
fun createAndyJediTermWidget(
    cols: Int,
    rows: Int,
    appearance: TerminalAppearanceSnapshot = TerminalAppearanceSnapshot(),
): JediTermWidget {
    val widget = JediTermWidget(cols, rows, AndyJediTermSettings(appearance))
    val track = appearance.backgroundAwtColor()
    widget.background = track
    widget.applyAndyTerminalScrollbars(track)
    return widget
}

internal fun JediTermWidget.applyAndyTerminalScrollbars(track: java.awt.Color) {
    findScrollBars(this).forEach { bar -> bar.applyAndyScrollTheme(track) }
}

private fun findScrollBars(root: Component): List<JScrollBar> {
    val found = mutableListOf<JScrollBar>()
    fun walk(component: Component) {
        if (component is JScrollBar) found += component
        if (component is Container) {
            component.components.forEach(::walk)
        }
    }
    walk(root)
    return found
}

internal class AndyJediTermSettings(
    private val appearance: TerminalAppearanceSnapshot,
) : DefaultSettingsProvider() {
    @Suppress("OVERRIDE_DEPRECATION")
    @Deprecated("JediTerm still reads this method when it creates the terminal style.")
    override fun getDefaultStyle(): TextStyle = TextStyle(
        appearance.foregroundTerminalColor(),
        appearance.backgroundTerminalColor(),
    )

    override fun getDefaultForeground(): TerminalColor = appearance.foregroundTerminalColor()

    override fun getDefaultBackground(): TerminalColor = appearance.backgroundTerminalColor()

    override fun getSelectionColor(): TextStyle = TextStyle(
        appearance.selectionFgTerminalColor(),
        appearance.selectionBgTerminalColor(),
    )

    override fun getFoundPatternColor(): TextStyle = TextStyle(
        appearance.foundFgTerminalColor(),
        appearance.foundBgTerminalColor(),
    )

    override fun getHyperlinkColor(): TextStyle = TextStyle(
        appearance.hyperlinkFgTerminalColor(),
        appearance.hyperlinkBgTerminalColor(),
    )

    override fun getHyperlinkHighlightingMode(): HyperlinkStyle.HighlightMode =
        HyperlinkStyle.HighlightMode.HOVER

    override fun useInverseSelectionColor(): Boolean = appearance.useInverseSelection

    override fun getTerminalColorPalette(): ColorPalette = when (appearance.colorPalette) {
        TerminalColorPaletteKind.Windows -> ColorPaletteImpl.WINDOWS_PALETTE
        TerminalColorPaletteKind.Xterm -> ColorPaletteImpl.XTERM_PALETTE
    }

    override fun getTerminalFontSize(): Float = appearance.fontSize

    override fun getTerminalFont(): Font {
        val size = appearance.fontSize.toInt().coerceAtLeast(8)
        val family = appearance.fontFamily.awtName ?: return super.getTerminalFont()
        return Font(family, Font.PLAIN, size)
    }
}

private fun TerminalAppearanceSnapshot.foregroundTerminalColor(): TerminalColor =
    terminalColor(foregroundHex)

private fun TerminalAppearanceSnapshot.backgroundTerminalColor(): TerminalColor =
    terminalColor(backgroundHex)

private fun TerminalAppearanceSnapshot.selectionFgTerminalColor(): TerminalColor =
    terminalColor(selectionFgHex)

private fun TerminalAppearanceSnapshot.selectionBgTerminalColor(): TerminalColor =
    terminalColor(selectionBgHex)

private fun TerminalAppearanceSnapshot.foundFgTerminalColor(): TerminalColor =
    terminalColor(foundFgHex)

private fun TerminalAppearanceSnapshot.foundBgTerminalColor(): TerminalColor =
    terminalColor(foundBgHex)

private fun TerminalAppearanceSnapshot.hyperlinkFgTerminalColor(): TerminalColor =
    terminalColor(hyperlinkFgHex)

private fun TerminalAppearanceSnapshot.hyperlinkBgTerminalColor(): TerminalColor =
    terminalColor(hyperlinkBgHex)

internal fun TerminalAppearanceSnapshot.backgroundAwtColor(): java.awt.Color {
    val (r, g, b) = terminalHexRgb(backgroundHex)
    return java.awt.Color(r, g, b)
}

private fun terminalColor(hex: String): TerminalColor {
    val (r, g, b) = terminalHexRgb(hex)
    return TerminalColor.rgb(r, g, b)
}
