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
    val detectFilePaths = resolvedDetectFilePaths()
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
        performanceMode = resolvedPerformanceMode(agentCliMode),
        detectFilePaths = detectFilePaths,
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
    val detectFilePaths = resolvedDetectFilePaths()
    val base = TerminalSettings(
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
        performanceMode = resolvedPerformanceMode(agentCliMode),
        enableMouseReporting = forwardMouseToApplication || !agentCliMode,
        forceActionOnMouseReporting = agentCliMode && !forwardMouseToApplication,
        mouseScrollThreshold = if (forwardMouseToApplication) 0f else 1f,
        simulateMouseScrollInAlternateScreen = !forwardMouseToApplication,
    )
    // Only override when the system property is set, so BossTerm's own default is preserved.
    return if (detectFilePaths == null) base else base.copy(detectFilePaths = detectFilePaths)
}

/**
 * `-Dandy.terminal.performanceMode=latency|balanced|throughput` overrides the agent/default
 * mapping. Used by [BossTermPipelineBenchmark] and available as an escape hatch in the field.
 * BossTerm's [ai.rever.bossterm.compose.terminal.BlockingTerminalDataStream] uses this at
 * init: latency=`take()`, balanced=`poll(10ms)`, throughput=`poll(100ms)`.
 */
private fun resolvedPerformanceMode(agentCliMode: Boolean): String =
    System.getProperty("andy.terminal.performanceMode")?.takeIf { it.isNotBlank() }
        ?: if (agentCliMode) "latency" else "balanced"

/**
 * `-Dandy.terminal.detectFilePaths=true|false` overrides BossTerm's per-frame path/URL
 * hyperlink scan. `null` means "leave Override unset / Settings default".
 */
private fun resolvedDetectFilePaths(): Boolean? =
    System.getProperty("andy.terminal.detectFilePaths")?.toBooleanStrictOrNull()

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
