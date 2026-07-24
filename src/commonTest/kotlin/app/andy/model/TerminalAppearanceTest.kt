package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalAppearanceTest {
    @Test
    fun parseTerminalHexAcceptsHashAndShorthand() {
        assertEquals("#AABBCC", parseTerminalHex("#aabbcc"))
        assertEquals("#AABBCC", parseTerminalHex("AABBCC"))
        assertEquals("#AABBCC", parseTerminalHex("#abc"))
        assertNull(parseTerminalHex(""))
        assertNull(parseTerminalHex("#gg0000"))
        assertNull(parseTerminalHex("#12345"))
    }

    @Test
    fun ketraThemesAreDefaults() {
        val state = WorkspaceState()
        assertEquals(TerminalThemePreset.OneDark.id, state.terminalThemeId)
        val snap = state.toTerminalAppearance()
        assertEquals(TerminalThemePreset.OneDark.id, snap.ketraThemeId)
        assertEquals(TerminalFontFamily.Default, snap.fontFamily)
    }

    @Test
    fun legacyThemeIdsCoerceToKetraThemes() {
        assertEquals(TerminalThemePreset.OneDark, TerminalThemePreset.fromId("andy"))
        assertEquals(TerminalThemePreset.OneDark, TerminalThemePreset.fromId("dracula"))
        assertEquals(TerminalThemePreset.Nord, TerminalThemePreset.fromId("nord"))
        assertEquals(TerminalThemePreset.OneDark, TerminalThemePreset.fromId("custom"))
        assertEquals(TerminalThemePreset.TokyoNight, TerminalThemePreset.fromId("tokyo-night"))
    }

    @Test
    fun themeApplyUpdatesWorkspaceThemeId() {
        val applied = TerminalThemePreset.Everforest.applyTo(WorkspaceState())
        assertEquals(TerminalThemePreset.Everforest.id, applied.terminalThemeId)
    }

    @Test
    fun toTerminalAppearanceIgnoresLegacyHexFields() {
        val snap = WorkspaceState(
            terminalThemeId = "nord",
            terminalForegroundHex = "not-a-color",
            terminalBackgroundHex = "#abc",
            terminalFontFamilyId = "comic-sans",
            terminalFontSize = 12.4f,
        ).toTerminalAppearance()
        assertEquals(TerminalThemePreset.Nord.id, snap.ketraThemeId)
        assertEquals(TerminalFontFamily.Default, snap.fontFamily)
        assertEquals(12f, snap.fontSize)
    }

    @Test
    fun coerceFontSizeSnapsToAllowedList() {
        assertEquals(14f, TerminalThemePreset.coerceFontSize(14.2f))
        assertEquals(16f, TerminalThemePreset.coerceFontSize(15.6f))
        assertEquals(11f, TerminalThemePreset.coerceFontSize(10f))
    }
}
