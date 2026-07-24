package app.andy.terminal

import app.andy.model.TerminalAppearanceSnapshot
import app.andy.model.TerminalFontFamily
import app.andy.model.TerminalThemePreset
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalTheme
import java.awt.Font

/** Maps Andy appearance prefs onto KetraTerm [SwingSettings] / [TerminalTheme]. */
fun TerminalAppearanceSnapshot.toKetraTheme(): TerminalTheme =
    when (TerminalThemePreset.fromId(ketraThemeId)) {
        TerminalThemePreset.Campbell -> TerminalTheme.CAMPBELL
        TerminalThemePreset.OneDark -> TerminalTheme.ONE_DARK
        TerminalThemePreset.Nord -> TerminalTheme.NORD
        TerminalThemePreset.TokyoNight -> TerminalTheme.TOKYO_NIGHT
        TerminalThemePreset.Everforest -> TerminalTheme.EVERFOREST
    }

fun TerminalAppearanceSnapshot.toSwingSettings(
    columns: Int = 120,
    rows: Int = 32,
    scrollbackLines: Int = KetraTermBackend.DEFAULT_MAX_HISTORY,
): SwingSettings {
    val theme = toKetraTheme()
    val size = fontSize.toInt().coerceAtLeast(8)
    val font = resolveTerminalFont(fontFamily, size)
    return SwingSettings(
        font = font,
        palette = theme.createPalette(),
        columns = columns,
        rows = rows,
        scrollbackLines = scrollbackLines,
    )
}

/** Packed ARGB panel background matching the active KetraTerm theme. */
fun TerminalAppearanceSnapshot.panelBackgroundArgb(): Long {
    val bg = toKetraTheme().createPalette().defaultBackground
    return bg.toLong() and 0xFFFFFFFFL
}

fun TerminalAppearanceSnapshot.panelBackgroundAwt(): java.awt.Color {
    val argb = toKetraTheme().createPalette().defaultBackground
    return java.awt.Color(argb, true)
}

internal fun resolveTerminalFont(family: TerminalFontFamily, size: Int): Font {
    val name = family.awtName ?: return Font(Font.MONOSPACED, Font.PLAIN, size)
    val font = Font(name, Font.PLAIN, size)
    return if (font.family.equals(name, ignoreCase = true) || font.canDisplay('A')) {
        font
    } else {
        Font(Font.MONOSPACED, Font.PLAIN, size)
    }
}
