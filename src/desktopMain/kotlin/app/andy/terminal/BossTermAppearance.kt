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
        performanceMode = resolvedPerformanceMode(agentCliMode),
        detectFilePaths = resolvedDetectFilePaths(agentCliMode),
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
        performanceMode = resolvedPerformanceMode(agentCliMode),
        detectFilePaths = resolvedDetectFilePaths(agentCliMode),
        enableMouseReporting = forwardMouseToApplication || !agentCliMode,
        forceActionOnMouseReporting = agentCliMode && !forwardMouseToApplication,
        mouseScrollThreshold = if (forwardMouseToApplication) 0f else 1f,
        simulateMouseScrollInAlternateScreen = !forwardMouseToApplication,
    )
}

/**
 * `-Dandy.terminal.performanceMode=latency|balanced|throughput` overrides the default.
 * BossTerm's [ai.rever.bossterm.compose.terminal.BlockingTerminalDataStream] uses this at
 * init: latency=`take()`, balanced=`poll(10ms)`, throughput=`poll(100ms)`.
 *
 * Default **throughput** for every Andy-embedded terminal (agent chat + Actions dock): the
 * 2026-08 BossTerm Compose bench measured 15fps+throughput at 20.8% process CPU vs 28.9%
 * for latency under agent-like streaming (see `docs/terminal-performance-investigation.md`).
 * Poll still returns as soon as a chunk arrives, so keystroke echo is not delayed by the
 * 100ms timeout.
 */
private fun resolvedPerformanceMode(@Suppress("UNUSED_PARAMETER") agentCliMode: Boolean): String =
    System.getProperty("andy.terminal.performanceMode")?.takeIf { it.isNotBlank() }
        ?: "throughput"

/**
 * `-Dandy.terminal.detectFilePaths=true|false` overrides the default.
 *
 * Default **false**: `TerminalCanvasRenderer.detectAllHyperlinks` runs on the paint path when
 * enabled. The same bench: 15fps+noHyperlinks = 21.3%, 15fps+both = **20.2%** (the only
 * configuration that lands at the top of the 10–20% active-CPU band). Both terminal surfaces
 * share [BossTermBackend], so this applies to chat and Actions alike; re-enable with
 * `-Dandy.terminal.detectFilePaths=true` if clickable paths are needed.
 */
private fun resolvedDetectFilePaths(@Suppress("UNUSED_PARAMETER") agentCliMode: Boolean): Boolean {
    return System.getProperty("andy.terminal.detectFilePaths")?.toBooleanStrictOrNull() ?: false
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
