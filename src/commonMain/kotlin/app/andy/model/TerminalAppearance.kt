package app.andy.model

/**
 * Built-in KetraTerm color themes. Ids match KetraTerm config theme strings
 * (`one-dark`, `nord`, …).
 */
enum class TerminalThemePreset(val id: String, val label: String) {
    Campbell("campbell", "Campbell"),
    OneDark("one-dark", "One Dark"),
    Nord("nord", "Nord"),
    TokyoNight("tokyo-night", "Tokyo Night"),
    Everforest("everforest", "Everforest");

    fun applyTo(state: WorkspaceState): WorkspaceState = state.copy(terminalThemeId = id)

    companion object {
        val Default: TerminalThemePreset = OneDark
        val DefaultFontSize: Float = 14f
        val FontSizes: List<Float> = listOf(11f, 12f, 13f, 14f, 16f, 18f)

        fun fromId(id: String): TerminalThemePreset {
            val normalized = id.trim().lowercase()
            entries.firstOrNull { it.id == normalized }?.let { return it }
            // Legacy Andy hex-theme ids collapse to KetraTerm default.
            return when (normalized) {
                "nord" -> Nord
                "one-dark", "onedark" -> OneDark
                else -> Default
            }
        }

        fun coerceFontSize(size: Float): Float =
            FontSizes.minByOrNull { kotlin.math.abs(it - size) } ?: DefaultFontSize
    }
}

/** Monospace font choices for embedded terminals. `Default` keeps the OS default. */
enum class TerminalFontFamily(val id: String, val label: String, val awtName: String?) {
    Default("default", "System default", null),
    Menlo("menlo", "Menlo", "Menlo"),
    SfMono("sf-mono", "SF Mono", "SF Mono"),
    JetBrainsMono("jetbrains-mono", "JetBrains Mono", "JetBrains Mono"),
    Consolas("consolas", "Consolas", "Consolas"),
    Monaco("monaco", "Monaco", "Monaco"),
    CourierNew("courier-new", "Courier New", "Courier New"),
    Monospaced("monospaced", "Monospaced", "Monospaced");

    companion object {
        fun fromId(id: String): TerminalFontFamily =
            entries.firstOrNull { it.id == id } ?: Default
    }
}

/** Snapshot of terminal appearance prefs used when constructing a KetraTerm SwingTerminal. */
data class TerminalAppearanceSnapshot(
    val ketraThemeId: String = TerminalThemePreset.Default.id,
    val fontFamily: TerminalFontFamily = TerminalFontFamily.Default,
    val fontSize: Float = TerminalThemePreset.DefaultFontSize,
) {
    val theme: TerminalThemePreset get() = TerminalThemePreset.fromId(ketraThemeId)
}

fun WorkspaceState.toTerminalAppearance(): TerminalAppearanceSnapshot = TerminalAppearanceSnapshot(
    ketraThemeId = TerminalThemePreset.fromId(terminalThemeId).id,
    fontFamily = TerminalFontFamily.fromId(terminalFontFamilyId),
    fontSize = TerminalThemePreset.coerceFontSize(terminalFontSize),
)

/**
 * Parses `#RGB`, `#RRGGBB`, or bare hex into normalized `#RRGGBB`.
 * Returns null when the value is not a valid color.
 */
fun parseTerminalHex(raw: String): String? {
    val trimmed = raw.trim()
    val body = when {
        trimmed.startsWith("#") -> trimmed.drop(1)
        else -> trimmed
    }
    if (body.isEmpty() || body.any { it !in "0123456789abcdefABCDEF" }) return null
    val expanded = when (body.length) {
        3 -> body.map { "$it$it" }.joinToString("")
        6 -> body
        else -> return null
    }
    return "#" + expanded.uppercase()
}

/** Normalizes a stored hex, falling back to [fallback] when invalid. */
fun normalizeTerminalHex(raw: String, fallback: String): String =
    parseTerminalHex(raw) ?: parseTerminalHex(fallback) ?: "#1E2127"

/** RGB components 0–255 from a normalized `#RRGGBB` (or invalid → black). */
fun terminalHexRgb(hex: String): Triple<Int, Int, Int> {
    val normalized = parseTerminalHex(hex) ?: return Triple(0, 0, 0)
    val body = normalized.drop(1)
    return Triple(
        body.substring(0, 2).toInt(16),
        body.substring(2, 4).toInt(16),
        body.substring(4, 6).toInt(16),
    )
}

/** ARGB packed int for Compose `Color(…)`, assuming opaque. */
fun terminalHexArgb(hex: String): Long {
    val (r, g, b) = terminalHexRgb(hex)
    return (0xFFL shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
}
