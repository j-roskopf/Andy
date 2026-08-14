package app.andy.model

/**
 * Full terminal palette for the Rust VT engine: fg/bg/cursor + ANSI 0–15 + selection.
 * Values are opaque ARGB (`0xAARRGGBB`).
 */
data class TerminalPalette(
    val foreground: Int,
    val background: Int,
    val cursor: Int,
    val selection: Int,
    val selectionText: Int,
    val ansi16: IntArray,
) {
    init {
        require(ansi16.size == 16) { "ansi16 must have 16 entries" }
    }

    /** Packed for [app.andy.terminal.rust.RustTerminalEngine.setPalette]: fg,bg,cursor,ansi0..15. */
    fun toEngineArgb(): IntArray {
        val out = IntArray(19)
        out[0] = foreground
        out[1] = background
        out[2] = cursor
        for (i in 0 until 16) out[3 + i] = ansi16[i]
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TerminalPalette) return false
        return foreground == other.foreground &&
            background == other.background &&
            cursor == other.cursor &&
            selection == other.selection &&
            selectionText == other.selectionText &&
            ansi16.contentEquals(other.ansi16)
    }

    override fun hashCode(): Int {
        var result = foreground
        result = 31 * result + background
        result = 31 * result + cursor
        result = 31 * result + selection
        result = 31 * result + selectionText
        result = 31 * result + ansi16.contentHashCode()
        return result
    }
}

private fun argb(rgb: Long): Int = (0xFF000000L or (rgb and 0xFFFFFFL)).toInt()

fun TerminalThemePreset.palette(): TerminalPalette = when (this) {
    TerminalThemePreset.OneDark -> TerminalPalette(
        foreground = argb(0xABB2BF),
        background = argb(0x282C34),
        cursor = argb(0xABB2BF),
        selection = argb(0x404859),
        selectionText = argb(0xABB2BF),
        ansi16 = intArrayOf(
            argb(0x282C34), argb(0xE06C75), argb(0x98C379), argb(0xE5C07B),
            argb(0x61AFEF), argb(0xC678DD), argb(0x56B6C2), argb(0xABB2BF),
            argb(0x5C6370), argb(0xE06C75), argb(0x98C379), argb(0xE5C07B),
            argb(0x61AFEF), argb(0xC678DD), argb(0x56B6C2), argb(0xFFFFFF),
        ),
    )
    TerminalThemePreset.Nord -> TerminalPalette(
        foreground = argb(0xD8DEE9),
        background = argb(0x2E3440),
        cursor = argb(0xD8DEE9),
        selection = argb(0x434C5E),
        selectionText = argb(0xECEFF4),
        ansi16 = intArrayOf(
            argb(0x3B4252), argb(0xBF616A), argb(0xA3BE8C), argb(0xEBCB8B),
            argb(0x81A1C1), argb(0xB48EAD), argb(0x88C0D0), argb(0xE5E9F0),
            argb(0x4C566A), argb(0xBF616A), argb(0xA3BE8C), argb(0xEBCB8B),
            argb(0x81A1C1), argb(0xB48EAD), argb(0x8FBCBB), argb(0xECEFF4),
        ),
    )
    TerminalThemePreset.TokyoNight -> TerminalPalette(
        foreground = argb(0xA9B1D6),
        background = argb(0x1A1B26),
        cursor = argb(0xC0CAF5),
        selection = argb(0x28344E),
        selectionText = argb(0xC0CAF5),
        ansi16 = intArrayOf(
            argb(0x15161E), argb(0xF7768E), argb(0x9ECE6A), argb(0xE0AF68),
            argb(0x7AA2F7), argb(0xBB9AF7), argb(0x7DCFFF), argb(0xA9B1D6),
            argb(0x414868), argb(0xF7768E), argb(0x9ECE6A), argb(0xE0AF68),
            argb(0x7AA2F7), argb(0xBB9AF7), argb(0x7DCFFF), argb(0xC0CAF5),
        ),
    )
    TerminalThemePreset.Everforest -> TerminalPalette(
        foreground = argb(0xD3C6AA),
        background = argb(0x2D353B),
        cursor = argb(0xD3C6AA),
        selection = argb(0x475258),
        selectionText = argb(0xD3C6AA),
        ansi16 = intArrayOf(
            argb(0x343F44), argb(0xE67E80), argb(0xA7C080), argb(0xDBBC7F),
            argb(0x7FBBB3), argb(0xD699B6), argb(0x83C092), argb(0xD3C6AA),
            argb(0x859289), argb(0xE67E80), argb(0xA7C080), argb(0xDBBC7F),
            argb(0x7FBBB3), argb(0xD699B6), argb(0x83C092), argb(0xDDE2C2),
        ),
    )
    TerminalThemePreset.Campbell -> TerminalPalette(
        foreground = argb(0xCCCCCC),
        background = argb(0x0C0C0C),
        cursor = argb(0xFFFFFF),
        selection = argb(0x3A3A3A),
        selectionText = argb(0xFFFFFF),
        ansi16 = intArrayOf(
            argb(0x0C0C0C), argb(0xC50F1F), argb(0x13A10E), argb(0xC19C00),
            argb(0x0037DA), argb(0x881798), argb(0x3A96DD), argb(0xCCCCCC),
            argb(0x767676), argb(0xE74856), argb(0x16C60C), argb(0xF9F1A5),
            argb(0x3B78FF), argb(0xB4009E), argb(0x61D6D6), argb(0xF2F2F2),
        ),
    )
}

fun TerminalAppearanceSnapshot.palette(): TerminalPalette = theme.palette()

fun TerminalAppearanceSnapshot.panelBackgroundArgb(): Long =
    palette().background.toLong() and 0xFFFFFFFFL
