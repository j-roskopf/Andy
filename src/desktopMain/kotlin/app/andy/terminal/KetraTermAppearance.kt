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

/**
 * Settings for a read-only scrollback replay: the live terminal's look, minus the chrome
 * the normal buffer reserves for shell integration.
 *
 * Agent CLIs draw on the alternate screen, and their scrollback is saved as rows exactly as
 * wide as that grid. A replay re-renders those rows on the normal buffer, whose chrome is
 * wider by default — a 16px prompt-decoration gutter plus roomier padding — so the viewer
 * ends up a few columns narrower than the rows it is showing and wraps the tail of every one
 * onto the next line. Matching the alternate screen's insets keeps each saved row on its own
 * line; a replay has no shell-integration data to decorate anyway.
 */
fun TerminalAppearanceSnapshot.toScrollbackReplaySettings(
    columns: Int = 120,
    rows: Int = 32,
): SwingSettings {
    val base = toSwingSettings(columns = columns, rows = rows)
    return base.copy(
        padding = base.alternateScreenPadding,
        shellIntegrationDecorationGutterWidth = 0,
        shellIntegrationPromptDotsVisible = false,
        shellIntegrationFailedCommandRailsVisible = false,
    )
}

/**
 * Swing settings for live embedded agent CLIs (Cursor, Claude Code, …).
 *
 * Agent TUIs draw on the alternate screen; matching its insets keeps each row on one line
 * and stops the bottom chrome from reflowing on every redraw.
 */
fun TerminalAppearanceSnapshot.toAgentCliSwingSettings(
    columns: Int = 120,
    rows: Int = 32,
    scrollbackLines: Int = KetraTermBackend.DEFAULT_MAX_HISTORY,
): SwingSettings {
    val base = toSwingSettings(columns = columns, rows = rows, scrollbackLines = scrollbackLines)
    return base.copy(
        padding = base.alternateScreenPadding,
        shellIntegrationDecorationGutterWidth = 0,
        shellIntegrationPromptDotsVisible = false,
        shellIntegrationFailedCommandRailsVisible = false,
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
