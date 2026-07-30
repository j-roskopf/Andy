package app.andy.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design DNA — Minimal Native macOS.
 *
 * Surfaces stay neutral; accent is punctuation. Dark and light palettes are
 * tuned independently (not inverted copies of each other).
 */
internal enum class AndyTint(val id: String, val label: String, val color: Color) {
    Default("andy-blue", "Andy blue", Color(0xFF78A9FF)),
    Sky("sky", "Sky", Color(0xFF38BDF8)),
    Azure("azure", "Azure", Color(0xFF3B82F6)),
    Indigo("indigo", "Indigo", Color(0xFF6366F1)),
    Violet("violet", "Violet", Color(0xFF8B5CF6)),
    Purple("purple", "Purple", Color(0xFFA855F7)),
    Fuchsia("fuchsia", "Fuchsia", Color(0xFFD946EF)),
    Pink("pink", "Pink", Color(0xFFEC4899)),
    Rose("rose", "Rose", Color(0xFFFB7185)),
    Coral("coral", "Coral", Color(0xFFFB7C65)),
    Orange("orange", "Orange", Color(0xFFFB923C)),
    Amber("amber", "Amber", Color(0xFFFBBF24)),
    Gold("gold", "Gold", Color(0xFFEAB308)),
    Lime("lime", "Lime", Color(0xFFA3E635)),
    Green("green", "Green", Color(0xFF4ADE80)),
    Emerald("emerald", "Emerald", Color(0xFF34D399)),
    Teal("teal", "Teal", Color(0xFF14B8A6)),
    Aqua("aqua", "Aqua", Color(0xFF22D3EE)),
    Steel("steel", "Steel", Color(0xFF94A3B8)),
    White("white", "Silver", Color(0xFFE2E8F0));

    companion object {
        fun fromId(id: String): AndyTint = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * Background treatment independent of accent tint.
 * Tinted washes surfaces with the selected accent hue.
 * Dark uses the DNA dark neutrals. Light uses the DNA light palette.
 */
internal enum class AndySurfaceMode(val id: String, val label: String) {
    Tinted("tinted", "Tinted"),
    PitchBlack("pitch-black", "Dark"),
    Light("light", "Light");

    val isLight: Boolean get() = this == Light

    companion object {
        fun fromId(id: String): AndySurfaceMode = entries.firstOrNull { it.id == id } ?: Tinted
    }
}

private data class HslColor(val hue: Float, val saturation: Float, val lightness: Float)

private fun Color.toHsl(): HslColor {
    val maximum = maxOf(red, green, blue)
    val minimum = minOf(red, green, blue)
    val delta = maximum - minimum
    val lightness = (maximum + minimum) / 2f
    if (delta == 0f) return HslColor(0f, 0f, lightness)
    val saturation = delta / (1f - kotlin.math.abs(2f * lightness - 1f))
    val hue = when (maximum) {
        red -> 60f * (((green - blue) / delta) % 6f)
        green -> 60f * (((blue - red) / delta) + 2f)
        else -> 60f * (((red - green) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    return HslColor(hue, saturation, lightness)
}

private fun hslColor(hue: Float, saturation: Float, lightness: Float): Color {
    val normalizedHue = ((hue % 360f) + 360f) % 360f
    val chroma = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
    val secondary = chroma * (1f - kotlin.math.abs((normalizedHue / 60f) % 2f - 1f))
    val (red, green, blue) = when {
        normalizedHue < 60f -> Triple(chroma, secondary, 0f)
        normalizedHue < 120f -> Triple(secondary, chroma, 0f)
        normalizedHue < 180f -> Triple(0f, chroma, secondary)
        normalizedHue < 240f -> Triple(0f, secondary, chroma)
        normalizedHue < 300f -> Triple(secondary, 0f, chroma)
        else -> Triple(chroma, 0f, secondary)
    }
    val match = lightness - chroma / 2f
    return Color(
        (red + match).coerceIn(0f, 1f),
        (green + match).coerceIn(0f, 1f),
        (blue + match).coerceIn(0f, 1f),
    )
}

private data class AndyTonalPalette(
    val neutral100: Color,
    val neutral200: Color,
    val neutral300: Color,
    val neutral400: Color,
    val neutral500: Color,
    val neutral600: Color,
    val neutral700: Color,
    val neutral750: Color,
    val neutral800: Color,
    val neutral850: Color,
    val neutral900: Color,
    val border: Color,
    val borderMedium: Color,
    val windowBg: Color,
    val sidebarBg: Color,
    val paneBg: Color,
    val contentBg: Color,
    val surfaceHover: Color,
    val surfaceSelected: Color,
    val surfaceRaised: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,
) {
    companion object {
        fun from(tint: Color, surfaceMode: AndySurfaceMode): AndyTonalPalette = when (surfaceMode) {
            AndySurfaceMode.Light -> light()
            AndySurfaceMode.PitchBlack -> dark()
            AndySurfaceMode.Tinted -> tinted(tint)
        }

        /**
         * Accent-washed dark surfaces. Lightness follows the DNA dark ladder so
         * hierarchy stays familiar; hue/saturation come from the selected tint.
         */
        fun tinted(tint: Color): AndyTonalPalette {
            val hsl = tint.toHsl()
            val surfaceSaturation = hsl.saturation.coerceIn(0.18f, 0.38f)
            fun surface(lightness: Float, saturation: Float = surfaceSaturation) =
                hslColor(hsl.hue, saturation.coerceAtMost(surfaceSaturation), lightness)
            val window = surface(0.090f, 0.30f)
            val content = surface(0.078f, 0.32f)
            val pane = surface(0.105f, 0.28f)
            val sidebar = surface(0.125f, 0.26f)
            val raised = surface(0.145f, 0.24f)
            val hover = surface(0.165f, 0.22f)
            val selected = surface(0.220f, 0.20f)
            val border = hslColor(hsl.hue + 180f, 0.16f, 0.62f).copy(alpha = 0.14f)
            return AndyTonalPalette(
                neutral100 = Color.White.copy(alpha = 0.94f),
                neutral200 = Color.White.copy(alpha = 0.94f),
                neutral300 = Color.White.copy(alpha = 0.70f),
                neutral400 = Color.White.copy(alpha = 0.46f),
                neutral500 = Color.White.copy(alpha = 0.28f),
                neutral600 = hover,
                neutral700 = raised,
                neutral750 = sidebar,
                neutral800 = pane,
                neutral850 = content,
                neutral900 = window,
                border = border,
                borderMedium = border.copy(alpha = 0.20f),
                windowBg = window,
                sidebarBg = sidebar,
                paneBg = pane,
                contentBg = content,
                surfaceHover = hover,
                surfaceSelected = selected,
                surfaceRaised = raised,
                textPrimary = Color.White.copy(alpha = 0.94f),
                textSecondary = Color.White.copy(alpha = 0.70f),
                textTertiary = Color.White.copy(alpha = 0.46f),
                textDisabled = Color.White.copy(alpha = 0.28f),
            )
        }

        /** Exact DNA dark palette. Neutrals map so existing Ink/Panel call sites stay coherent. */
        fun dark() = AndyTonalPalette(
            // Text ladder (bright → muted)
            neutral100 = Color.White.copy(alpha = 0.94f),
            neutral200 = Color.White.copy(alpha = 0.94f),
            neutral300 = Color.White.copy(alpha = 0.70f),
            neutral400 = Color.White.copy(alpha = 0.46f),
            neutral500 = Color.White.copy(alpha = 0.28f),
            // Surface ladder (raised → deep)
            neutral600 = Color(0xFF23292E), // surface-hover
            neutral700 = Color(0xFF20262B), // surface-raised
            neutral750 = Color(0xFF1C2125), // sidebar-bg
            neutral800 = Color(0xFF181C20), // pane-bg
            neutral850 = Color(0xFF14171A), // content-bg
            neutral900 = Color(0xFF15181B), // window-bg
            border = Color.White.copy(alpha = 0.07f),
            borderMedium = Color.White.copy(alpha = 0.11f),
            windowBg = Color(0xFF15181B),
            sidebarBg = Color(0xFF1C2125),
            paneBg = Color(0xFF181C20),
            contentBg = Color(0xFF14171A),
            surfaceHover = Color(0xFF23292E),
            surfaceSelected = Color(0xFF30383F),
            surfaceRaised = Color(0xFF20262B),
            textPrimary = Color.White.copy(alpha = 0.94f),
            textSecondary = Color.White.copy(alpha = 0.70f),
            textTertiary = Color.White.copy(alpha = 0.46f),
            textDisabled = Color.White.copy(alpha = 0.28f),
        )

        /** Exact DNA light palette — tuned independently, not an inversion of dark. */
        fun light() = AndyTonalPalette(
            neutral100 = Color.Black.copy(alpha = 0.90f),
            neutral200 = Color.Black.copy(alpha = 0.90f),
            neutral300 = Color.Black.copy(alpha = 0.64f),
            neutral400 = Color.Black.copy(alpha = 0.44f),
            neutral500 = Color.Black.copy(alpha = 0.26f),
            neutral600 = Color(0xFFE9EAEC), // surface-hover
            neutral700 = Color(0xFFFFFFFF), // surface-raised
            neutral750 = Color(0xFFECEDEF), // sidebar-bg
            neutral800 = Color(0xFFF7F7F8), // pane-bg
            neutral850 = Color(0xFFFFFFFF), // content-bg
            neutral900 = Color(0xFFF4F4F5), // window-bg
            border = Color.Black.copy(alpha = 0.07f),
            borderMedium = Color.Black.copy(alpha = 0.12f),
            windowBg = Color(0xFFF4F4F5),
            sidebarBg = Color(0xFFECEDEF),
            paneBg = Color(0xFFF7F7F8),
            contentBg = Color(0xFFFFFFFF),
            surfaceHover = Color(0xFFE9EAEC),
            surfaceSelected = Color(0xFFDDE1E5),
            surfaceRaised = Color(0xFFFFFFFF),
            textPrimary = Color.Black.copy(alpha = 0.90f),
            textSecondary = Color.Black.copy(alpha = 0.64f),
            textTertiary = Color.Black.copy(alpha = 0.44f),
            textDisabled = Color.Black.copy(alpha = 0.26f),
        )
    }
}

internal fun windowBackgroundForTint(
    tintId: String,
    surfaceModeId: String = AndySurfaceMode.Tinted.id,
): Color = AndyTonalPalette.from(
    AndyTint.fromId(tintId).color,
    AndySurfaceMode.fromId(surfaceModeId),
).windowBg

internal object AndyColors {
    private var selectedTint by mutableStateOf(AndyTint.Default)
    private var selectedSurfaceMode by mutableStateOf(AndySurfaceMode.Tinted)
    private var tonalPalette by mutableStateOf(
        AndyTonalPalette.from(AndyTint.Default.color, AndySurfaceMode.Tinted),
    )

    fun selectTint(id: String, surfaceModeId: String = AndySurfaceMode.Tinted.id) {
        val tint = AndyTint.fromId(id)
        val surfaceMode = AndySurfaceMode.fromId(surfaceModeId)
        if (selectedTint == tint && selectedSurfaceMode == surfaceMode) return
        selectedTint = tint
        selectedSurfaceMode = surfaceMode
        tonalPalette = AndyTonalPalette.from(tint.color, surfaceMode)
    }

    val isLight: Boolean get() = selectedSurfaceMode.isLight

    val Neutral100 get() = tonalPalette.neutral100
    val Neutral200 get() = tonalPalette.neutral200
    val Neutral300 get() = tonalPalette.neutral300
    val Neutral400 get() = tonalPalette.neutral400
    val Neutral500 get() = tonalPalette.neutral500
    val Neutral600 get() = tonalPalette.neutral600
    val Neutral700 get() = tonalPalette.neutral700
    val Neutral750 get() = tonalPalette.neutral750
    val Neutral800 get() = tonalPalette.neutral800
    val Neutral850 get() = tonalPalette.neutral850
    val Neutral900 get() = tonalPalette.neutral900
    val tonalPaletteBorder get() = tonalPalette.border
    val BorderMedium get() = tonalPalette.borderMedium

    val WindowBg get() = tonalPalette.windowBg
    val SidebarBg get() = tonalPalette.sidebarBg
    val PaneBg get() = tonalPalette.paneBg
    val ContentBg get() = tonalPalette.contentBg
    val SurfaceHover get() = tonalPalette.surfaceHover
    val SurfaceSelected get() = tonalPalette.surfaceSelected
    val SurfaceRaised get() = tonalPalette.surfaceRaised
    val TextPrimaryToken get() = tonalPalette.textPrimary
    val TextSecondaryToken get() = tonalPalette.textSecondary
    val TextTertiary get() = tonalPalette.textTertiary
    val TextDisabled get() = tonalPalette.textDisabled

    val Orange get() = selectedTint.color
    val OrangeHover get() = selectedTint.color.copy(alpha = 0.88f)
    val OrangePressed get() = selectedTint.color.copy(alpha = 0.68f)
    val OrangeSubtle get() = selectedTint.color.copy(alpha = if (isLight) 0.12f else 0.16f)
    val OrangeBorder get() = selectedTint.color.copy(alpha = if (isLight) 0.40f else 0.48f)
    val Green = Color(0xFF66D17A)
    val GreenSoft = Color(0xFF8FDC9E)
    val GreenSubtle get() = if (isLight) Color(0xFFDCEFE7) else Color(0xFF1A2E22)
    val Blue get() = selectedTint.color
    val Warning = Color(0xFFE0A64B)
    val Error = Color(0xFFE46A6A)
}

/** Translucency scale for surfaces stacked over other content (raised panels, popovers). */
internal object AndyOverlay {
    val Subtle = 0.55f
    val Medium = 0.72f
    val Strong = 0.90f
}

/** DNA spacing scale. */
internal object AndySpace {
    val Space1 = 4.dp
    val Space2 = 6.dp
    val Space3 = 8.dp
    val Space4 = 12.dp
    val Space5 = 16.dp
    val Space6 = 20.dp
    val Space7 = 24.dp
    val Space8 = 32.dp
}

/** DNA radius system — rounding communicates object type. */
internal object AndyRadius {
    val Control = 6.dp
    val Row = 7.dp
    val Menu = 10.dp
    val Sheet = 12.dp
    val Window = 14.dp
    val Pill = 999.dp
}

/** DNA layout + control metrics. */
internal object AndyLayout {
    val ToolbarHeight = 50.dp
    val SidebarWidth = 210.dp
    val SidebarCollapsedWidth = 52.dp
    val ListWidth = 260.dp
    val SidebarRowHeight = 30.dp
    val ControlHeightXs = 24.dp
    val ControlHeightSm = 28.dp
    val ControlHeightMd = 30.dp
    /** Compact single-line text field — denser than Material, readable at a glance. */
    val FieldHeight = 28.dp
    val ToolbarButtonSize = 28.dp
    val IconSm = 13.dp
    val IconMd = 15.dp
    val IconLg = 17.dp
    val IconHitArea = 28.dp
    val ContentMaxWidth = 760.dp
}

/** DNA motion timing. */
internal object AndyMotion {
    const val FastMs = 100
    const val StandardMs = 170
    const val SpatialMs = 240
    const val MicroMinMs = 80
    const val MicroMaxMs = 120
    const val SmallMinMs = 140
    const val SmallMaxMs = 200
}

internal object AndyStroke {
    /** Hairline separators between panes and rows (DNA border-subtle weight). */
    val Hairline = 1.dp
    /** Invisible drag target width for vertical pane resize handles. */
    val PaneHandleHitWidth = 8.dp
    /** Invisible drag target height for horizontal pane resize handles. */
    val PaneHandleHitHeight = 10.dp
}

/**
 * System UI face for chrome and labels. Paths, commands, and runtime details
 * stay monospaced so dense developer information remains scannable.
 */
internal val DisplayFont = FontFamily.SansSerif
internal val MonoFont = FontFamily.Monospace

internal val Ink get() = AndyColors.WindowBg
internal val Panel get() = AndyColors.PaneBg
internal val PanelSoft get() = AndyColors.SurfaceRaised
internal val Border get() = AndyColors.tonalPaletteBorder
internal val PaneDividerTint get() = AndyColors.BorderMedium
internal val TextPrimary get() = AndyColors.TextPrimaryToken
internal val TextSecondary get() = AndyColors.TextSecondaryToken
internal val Rust get() = AndyColors.Orange
internal val Green = AndyColors.Green
internal val Cyan get() = AndyColors.Blue
internal val Yellow = AndyColors.Warning
internal val Red = AndyColors.Error

@Composable
fun AndyTheme(
    tintId: String = AndyTint.Default.id,
    surfaceModeId: String = AndySurfaceMode.Tinted.id,
    content: @Composable () -> Unit,
) {
    remember(tintId, surfaceModeId) { AndyColors.selectTint(tintId, surfaceModeId) }
    val colorScheme = if (AndySurfaceMode.fromId(surfaceModeId).isLight) {
        lightColorScheme(
            background = Ink,
            surface = Panel,
            surfaceVariant = PanelSoft,
            primary = Rust,
            secondary = Green,
            onBackground = TextPrimary,
            onSurface = TextPrimary,
            onSurfaceVariant = TextSecondary,
            outline = Border,
            error = Red,
        )
    } else {
        darkColorScheme(
            background = Ink,
            surface = Panel,
            surfaceVariant = PanelSoft,
            primary = Rust,
            secondary = Green,
            onBackground = TextPrimary,
            onSurface = TextPrimary,
            onSurfaceVariant = TextSecondary,
            outline = Border,
            error = Red,
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(
            displayLarge = LocalTextStyle.current.copy(
                fontFamily = DisplayFont,
                fontSize = 24.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
            ),
            headlineLarge = LocalTextStyle.current.copy(
                fontFamily = DisplayFont,
                fontSize = 20.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
            ),
            titleMedium = LocalTextStyle.current.copy(
                fontFamily = DisplayFont,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            bodyMedium = LocalTextStyle.current.copy(
                fontFamily = DisplayFont,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Normal,
            ),
            bodySmall = LocalTextStyle.current.copy(
                fontFamily = DisplayFont,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Normal,
            ),
            labelMedium = LocalTextStyle.current.copy(
                fontFamily = DisplayFont,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
            ),
            labelSmall = LocalTextStyle.current.copy(
                fontFamily = DisplayFont,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(AndyRadius.Control),
            small = RoundedCornerShape(AndyRadius.Control),
            medium = RoundedCornerShape(AndyRadius.Row),
            large = RoundedCornerShape(AndyRadius.Menu),
            extraLarge = RoundedCornerShape(AndyRadius.Sheet),
        ),
        content = content,
    )
}
