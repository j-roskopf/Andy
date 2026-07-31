package app.andy.terminal

import ai.rever.bossterm.compose.settings.TerminalSettings
import ai.rever.bossterm.compose.settings.TerminalSettingsOverride
import ai.rever.bossterm.compose.settings.theme.BuiltinThemes
import ai.rever.bossterm.compose.settings.theme.Theme
import app.andy.model.TerminalAppearanceSnapshot
import app.andy.model.TerminalFontFamily
import app.andy.model.TerminalThemePreset

/** Maps Andy appearance prefs onto BossTerm theme / settings overrides. */
fun TerminalAppearanceSnapshot.toBossTermTheme(): Theme {
    val id = when (TerminalThemePreset.fromId(ketraThemeId)) {
        TerminalThemePreset.Campbell -> "default"
        TerminalThemePreset.OneDark -> "one-dark"
        TerminalThemePreset.Nord -> "nord"
        TerminalThemePreset.TokyoNight -> "tokyo-night"
        // Closest built-in analogue; BossTerm has no Everforest preset.
        TerminalThemePreset.Everforest -> "gruvbox-dark"
    }
    return BuiltinThemes.getById(id) ?: BuiltinThemes.ONE_DARK
}

fun TerminalAppearanceSnapshot.toBossTermSettingsOverride(
    scrollbackLines: Int = BossTermBackend.DEFAULT_MAX_HISTORY,
    agentCliMode: Boolean = false,
    forwardMouseToApplication: Boolean = false,
): TerminalSettingsOverride {
    val theme = toBossTermTheme()
    return TerminalSettingsOverride(
        fontSize = fontSize,
        fontName = fontFamily.bossTermFontName(),
        activeThemeId = theme.id,
        defaultForeground = theme.foreground,
        defaultBackground = theme.background,
        bufferMaxLines = scrollbackLines,
        showScrollbar = true,
        scrollbarAlwaysVisible = agentCliMode,
        // Agent TUIs own the alternate screen; disable BossTerm AI chrome.
        aiAssistantsEnabled = false,
        disableLineSpacingInAlternateBuffer = true,
        performanceMode = if (agentCliMode) "latency" else "balanced",
        // A tmux attach client owns the alternate screen and its pane scrollback. Let it
        // receive wheel events directly instead of asking BossTerm to scroll an empty outer
        // alternate buffer or translating the wheel into cursor keys.
        enableMouseReporting = forwardMouseToApplication || !agentCliMode,
        forceActionOnMouseReporting = agentCliMode && !forwardMouseToApplication,
        // BossTerm 1.2.143 accumulates fractional deltas only on its local-history path.
        // Its remote-mouse path compares each event to this threshold independently,
        // which drops normal macOS trackpad (and some wheel) deltas before tmux sees them.
        mouseScrollThreshold = if (forwardMouseToApplication) 0f else null,
        simulateMouseScrollInAlternateScreen = !forwardMouseToApplication,
    )
}

fun TerminalAppearanceSnapshot.toBossTermSettings(
    scrollbackLines: Int = BossTermBackend.DEFAULT_MAX_HISTORY,
    agentCliMode: Boolean = false,
    forwardMouseToApplication: Boolean = false,
): TerminalSettings {
    val theme = toBossTermTheme()
    return TerminalSettings(
        fontSize = fontSize,
        fontName = fontFamily.bossTermFontName(),
        activeThemeId = theme.id,
        defaultForeground = theme.foreground,
        defaultBackground = theme.background,
        bufferMaxLines = scrollbackLines,
        showScrollbar = true,
        scrollbarAlwaysVisible = agentCliMode,
        aiAssistantsEnabled = false,
        autoInjectShellIntegration = false,
        disableLineSpacingInAlternateBuffer = true,
        performanceMode = if (agentCliMode) "latency" else "balanced",
        enableMouseReporting = forwardMouseToApplication || !agentCliMode,
        forceActionOnMouseReporting = agentCliMode && !forwardMouseToApplication,
        mouseScrollThreshold = if (forwardMouseToApplication) 0f else 1f,
        simulateMouseScrollInAlternateScreen = !forwardMouseToApplication,
    )
}

/** ARGB packed as Compose Color expects (`0xAARRGGBB`). */
fun TerminalAppearanceSnapshot.panelBackgroundArgb(): Long {
    val theme = toBossTermTheme()
    val raw = theme.background.removePrefix("0x").removePrefix("0X")
    return raw.toLongOrNull(16)?.and(0xFFFFFFFFL) ?: 0xFF0E1217L
}

private fun TerminalFontFamily.bossTermFontName(): String? = when (this) {
    TerminalFontFamily.Default -> null
    TerminalFontFamily.Menlo -> "Menlo"
    TerminalFontFamily.SfMono -> "SF Mono"
    TerminalFontFamily.JetBrainsMono -> "JetBrains Mono"
    TerminalFontFamily.Consolas -> "Consolas"
    TerminalFontFamily.Monaco -> "Monaco"
    TerminalFontFamily.CourierNew -> "Courier New"
    TerminalFontFamily.Monospaced -> "Monospaced"
}
