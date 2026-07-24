package app.andy.model

/** ANSI 16-color base palette used by JediTerm for indexed colors. */
enum class TerminalColorPaletteKind(val id: String, val label: String) {
    Xterm("xterm", "Xterm"),
    Windows("windows", "Windows");

    companion object {
        fun fromId(id: String): TerminalColorPaletteKind =
            entries.firstOrNull { it.id == id } ?: Xterm
    }
}

/** Monospace font choices for embedded terminals. `Default` keeps JediTerm's OS font. */
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

/**
 * Named terminal color presets. Selecting a preset overwrites the per-role hex fields
 * on [WorkspaceState]; users can then tweak individual colors.
 */
enum class TerminalThemePreset(
    val id: String,
    val label: String,
    val foregroundHex: String,
    val backgroundHex: String,
    val selectionFgHex: String,
    val selectionBgHex: String,
    val foundFgHex: String,
    val foundBgHex: String,
    val hyperlinkFgHex: String,
    val hyperlinkBgHex: String,
    val useInverseSelection: Boolean,
    val colorPaletteId: String,
) {
    Andy(
        id = "andy",
        label = "Andy",
        foregroundHex = "#E4DED0",
        backgroundHex = "#11100D",
        selectionFgHex = "#FFFFFF",
        selectionBgHex = "#526DA5",
        foundFgHex = "#000000",
        foundBgHex = "#FFFF00",
        hyperlinkFgHex = "#6CB6FF",
        hyperlinkBgHex = "#11100D",
        useInverseSelection = true,
        colorPaletteId = TerminalColorPaletteKind.Xterm.id,
    ),
    Light(
        id = "light",
        label = "Light",
        foregroundHex = "#1A1814",
        backgroundHex = "#F7F4EC",
        selectionFgHex = "#1A1814",
        selectionBgHex = "#B8D0F0",
        foundFgHex = "#1A1814",
        foundBgHex = "#FFE066",
        hyperlinkFgHex = "#0B57D0",
        hyperlinkBgHex = "#F7F4EC",
        useInverseSelection = false,
        colorPaletteId = TerminalColorPaletteKind.Xterm.id,
    ),
    HighContrast(
        id = "high-contrast",
        label = "High contrast",
        foregroundHex = "#FFFFFF",
        backgroundHex = "#000000",
        selectionFgHex = "#000000",
        selectionBgHex = "#FFFF00",
        foundFgHex = "#000000",
        foundBgHex = "#00FFFF",
        hyperlinkFgHex = "#00FFFF",
        hyperlinkBgHex = "#000000",
        useInverseSelection = false,
        colorPaletteId = TerminalColorPaletteKind.Xterm.id,
    ),
    Dracula(
        id = "dracula",
        label = "Dracula",
        foregroundHex = "#F8F8F2",
        backgroundHex = "#282A36",
        selectionFgHex = "#F8F8F2",
        selectionBgHex = "#44475A",
        foundFgHex = "#282A36",
        foundBgHex = "#F1FA8C",
        hyperlinkFgHex = "#8BE9FD",
        hyperlinkBgHex = "#282A36",
        useInverseSelection = false,
        colorPaletteId = TerminalColorPaletteKind.Xterm.id,
    ),
    Nord(
        id = "nord",
        label = "Nord",
        foregroundHex = "#D8DEE9",
        backgroundHex = "#2E3440",
        selectionFgHex = "#ECEFF4",
        selectionBgHex = "#434C5E",
        foundFgHex = "#2E3440",
        foundBgHex = "#EBCB8B",
        hyperlinkFgHex = "#88C0D0",
        hyperlinkBgHex = "#2E3440",
        useInverseSelection = false,
        colorPaletteId = TerminalColorPaletteKind.Xterm.id,
    ),
    SolarizedDark(
        id = "solarized-dark",
        label = "Solarized Dark",
        foregroundHex = "#839496",
        backgroundHex = "#002B36",
        selectionFgHex = "#FDF6E3",
        selectionBgHex = "#586E75",
        foundFgHex = "#002B36",
        foundBgHex = "#B58900",
        hyperlinkFgHex = "#268BD2",
        hyperlinkBgHex = "#002B36",
        useInverseSelection = false,
        colorPaletteId = TerminalColorPaletteKind.Xterm.id,
    ),
    SolarizedLight(
        id = "solarized-light",
        label = "Solarized Light",
        foregroundHex = "#657B83",
        backgroundHex = "#FDF6E3",
        selectionFgHex = "#002B36",
        selectionBgHex = "#93A1A1",
        foundFgHex = "#FDF6E3",
        foundBgHex = "#B58900",
        hyperlinkFgHex = "#268BD2",
        hyperlinkBgHex = "#FDF6E3",
        useInverseSelection = false,
        colorPaletteId = TerminalColorPaletteKind.Xterm.id,
    ),
    GruvboxDark(
        id = "gruvbox-dark",
        label = "Gruvbox Dark",
        foregroundHex = "#EBDBB2",
        backgroundHex = "#282828",
        selectionFgHex = "#FBF1C7",
        selectionBgHex = "#504945",
        foundFgHex = "#282828",
        foundBgHex = "#FABD2F",
        hyperlinkFgHex = "#83A598",
        hyperlinkBgHex = "#282828",
        useInverseSelection = false,
        colorPaletteId = TerminalColorPaletteKind.Xterm.id,
    ),
    OneDark(
        id = "one-dark",
        label = "One Dark",
        foregroundHex = "#ABB2BF",
        backgroundHex = "#282C34",
        selectionFgHex = "#FFFFFF",
        selectionBgHex = "#3E4451",
        foundFgHex = "#282C34",
        foundBgHex = "#E5C07B",
        hyperlinkFgHex = "#61AFEF",
        hyperlinkBgHex = "#282C34",
        useInverseSelection = false,
        colorPaletteId = TerminalColorPaletteKind.Xterm.id,
    );

    fun applyTo(state: WorkspaceState): WorkspaceState = state.copy(
        terminalThemeId = id,
        terminalForegroundHex = foregroundHex,
        terminalBackgroundHex = backgroundHex,
        terminalSelectionFgHex = selectionFgHex,
        terminalSelectionBgHex = selectionBgHex,
        terminalFoundFgHex = foundFgHex,
        terminalFoundBgHex = foundBgHex,
        terminalHyperlinkFgHex = hyperlinkFgHex,
        terminalHyperlinkBgHex = hyperlinkBgHex,
        terminalUseInverseSelection = useInverseSelection,
        terminalColorPaletteId = colorPaletteId,
    )

    companion object {
        fun fromId(id: String): TerminalThemePreset =
            entries.firstOrNull { it.id == id } ?: Andy

        val DefaultFontSize: Float = 13f
        val FontSizes: List<Float> = listOf(11f, 12f, 13f, 14f, 16f, 18f)

        fun coerceFontSize(size: Float): Float =
            FontSizes.minByOrNull { kotlin.math.abs(it - size) } ?: DefaultFontSize
    }
}

/** Snapshot of terminal appearance prefs used when constructing a JediTerm widget. */
data class TerminalAppearanceSnapshot(
    val foregroundHex: String = TerminalThemePreset.Andy.foregroundHex,
    val backgroundHex: String = TerminalThemePreset.Andy.backgroundHex,
    val selectionFgHex: String = TerminalThemePreset.Andy.selectionFgHex,
    val selectionBgHex: String = TerminalThemePreset.Andy.selectionBgHex,
    val foundFgHex: String = TerminalThemePreset.Andy.foundFgHex,
    val foundBgHex: String = TerminalThemePreset.Andy.foundBgHex,
    val hyperlinkFgHex: String = TerminalThemePreset.Andy.hyperlinkFgHex,
    val hyperlinkBgHex: String = TerminalThemePreset.Andy.hyperlinkBgHex,
    val useInverseSelection: Boolean = TerminalThemePreset.Andy.useInverseSelection,
    val colorPalette: TerminalColorPaletteKind = TerminalColorPaletteKind.Xterm,
    val fontFamily: TerminalFontFamily = TerminalFontFamily.Default,
    val fontSize: Float = TerminalThemePreset.DefaultFontSize,
)

fun WorkspaceState.toTerminalAppearance(): TerminalAppearanceSnapshot = TerminalAppearanceSnapshot(
    foregroundHex = normalizeTerminalHex(terminalForegroundHex, TerminalThemePreset.Andy.foregroundHex),
    backgroundHex = normalizeTerminalHex(terminalBackgroundHex, TerminalThemePreset.Andy.backgroundHex),
    selectionFgHex = normalizeTerminalHex(terminalSelectionFgHex, TerminalThemePreset.Andy.selectionFgHex),
    selectionBgHex = normalizeTerminalHex(terminalSelectionBgHex, TerminalThemePreset.Andy.selectionBgHex),
    foundFgHex = normalizeTerminalHex(terminalFoundFgHex, TerminalThemePreset.Andy.foundFgHex),
    foundBgHex = normalizeTerminalHex(terminalFoundBgHex, TerminalThemePreset.Andy.foundBgHex),
    hyperlinkFgHex = normalizeTerminalHex(terminalHyperlinkFgHex, TerminalThemePreset.Andy.hyperlinkFgHex),
    hyperlinkBgHex = normalizeTerminalHex(terminalHyperlinkBgHex, TerminalThemePreset.Andy.hyperlinkBgHex),
    useInverseSelection = terminalUseInverseSelection,
    colorPalette = TerminalColorPaletteKind.fromId(terminalColorPaletteId),
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
    parseTerminalHex(raw) ?: parseTerminalHex(fallback) ?: TerminalThemePreset.Andy.backgroundHex

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
