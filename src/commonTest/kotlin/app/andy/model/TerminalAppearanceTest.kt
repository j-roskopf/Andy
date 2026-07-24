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
    fun andyPresetMatchesWorkspaceDefaults() {
        val applied = TerminalThemePreset.Andy.applyTo(WorkspaceState())
        assertEquals(TerminalThemePreset.Andy.id, applied.terminalThemeId)
        assertEquals("#E4DED0", applied.terminalForegroundHex)
        assertEquals("#11100D", applied.terminalBackgroundHex)
        assertEquals("#526DA5", applied.terminalSelectionBgHex)
    }

    @Test
    fun lightPresetOverwritesColors() {
        val applied = TerminalThemePreset.Light.applyTo(WorkspaceState())
        assertEquals(TerminalThemePreset.Light.id, applied.terminalThemeId)
        assertEquals("#F7F4EC", applied.terminalBackgroundHex)
        assertEquals(false, applied.terminalUseInverseSelection)
    }

    @Test
    fun toTerminalAppearanceCoercesInvalidValues() {
        val snap = WorkspaceState(
            terminalForegroundHex = "not-a-color",
            terminalBackgroundHex = "#abc",
            terminalColorPaletteId = "nope",
            terminalFontFamilyId = "comic-sans",
            terminalFontSize = 12.4f,
        ).toTerminalAppearance()
        assertEquals(TerminalThemePreset.Andy.foregroundHex, snap.foregroundHex)
        assertEquals("#AABBCC", snap.backgroundHex)
        assertEquals(TerminalColorPaletteKind.Xterm, snap.colorPalette)
        assertEquals(TerminalFontFamily.Default, snap.fontFamily)
        assertEquals(12f, snap.fontSize)
    }

    @Test
    fun coerceFontSizeSnapsToAllowedList() {
        assertEquals(13f, TerminalThemePreset.coerceFontSize(13.2f))
        assertEquals(16f, TerminalThemePreset.coerceFontSize(15.6f))
        assertEquals(11f, TerminalThemePreset.coerceFontSize(10f))
    }
}
