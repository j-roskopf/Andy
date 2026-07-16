package app.andy.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
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

internal enum class AndyTint(val id: String, val label: String, val color: Color) {
    Default("andy-blue", "Andy blue", Color(0xFF02A8F3)),
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
) {
    companion object {
        fun from(tint: Color): AndyTonalPalette {
            val hsl = tint.toHsl()
            val surfaceSaturation = hsl.saturation.coerceIn(0.20f, 0.42f)
            fun surface(lightness: Float, saturation: Float) = hslColor(hsl.hue, saturation.coerceAtMost(surfaceSaturation), lightness)
            return AndyTonalPalette(
                neutral100 = surface(0.94f, 0.08f),
                neutral200 = surface(0.86f, 0.09f),
                neutral300 = surface(0.70f, 0.10f),
                neutral400 = surface(0.50f, 0.12f),
                neutral500 = surface(0.32f, 0.15f),
                neutral600 = surface(0.22f, 0.18f),
                neutral700 = surface(0.15f, 0.24f),
                neutral750 = surface(0.11f, 0.28f),
                neutral800 = surface(0.085f, 0.31f),
                neutral850 = surface(0.062f, 0.34f),
                neutral900 = surface(0.045f, 0.36f),
                // Complementary hue gives borders a quiet separating edge without tinting surfaces.
                border = hslColor(hsl.hue + 180f, 0.18f, 0.64f).copy(alpha = 0.14f),
            )
        }
    }
}

internal fun windowBackgroundForTint(tintId: String): Color =
    AndyTonalPalette.from(AndyTint.fromId(tintId).color).neutral850

internal object AndyColors {
    private var selectedTint by mutableStateOf(AndyTint.Default)
    private var tonalPalette by mutableStateOf(AndyTonalPalette.from(AndyTint.Default.color))

    fun selectTint(id: String) {
        val tint = AndyTint.fromId(id)
        if (selectedTint == tint) return
        selectedTint = tint
        tonalPalette = AndyTonalPalette.from(tint.color)
    }

    // HSL lightness is fixed per role; selected color contributes hue, not a wash of saturation.
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

    val Orange get() = selectedTint.color
    val OrangeHover get() = selectedTint.color.copy(alpha = 0.88f)
    val OrangePressed get() = selectedTint.color.copy(alpha = 0.68f)
    val OrangeSubtle get() = selectedTint.color.copy(alpha = 0.20f)
    val OrangeBorder get() = selectedTint.color.copy(alpha = 0.58f)
    val Green = Color(0xFF72C5A2)
    val GreenSoft = Color(0xFF9AD8BF)
    val GreenSubtle = Color(0xFF102A28)
    val Blue get() = selectedTint.color
    val Warning = Color(0xFFE0B45C)
    val Error = Color(0xFFE37B70)
}

internal object AndySpace {
    val S1 = 4.dp
    val S2 = 8.dp
    val S3 = 12.dp
    val S4 = 16.dp
    val S5 = 24.dp
    val S6 = 32.dp
}

internal object AndyRadius {
    val R2 = 4.dp
    val R3 = 6.dp
    val R4 = 8.dp
    val R5 = 10.dp
    val Pill = 999.dp
}

internal val MonoFont = FontFamily.Monospace
/**
 * Human-facing workspace labels use the platform sans face; paths, commands, and
 * runtime details stay monospaced so scanning dense developer information remains easy.
 */
internal val DisplayFont = FontFamily.SansSerif
internal val Ink get() = AndyColors.Neutral900
internal val Panel get() = AndyColors.Neutral800
internal val PanelSoft get() = AndyColors.Neutral700
internal val Border get() = AndyColors.tonalPaletteBorder
internal val PaneDividerTint get() = AndyColors.OrangeBorder.copy(alpha = 0.72f)
internal val TextPrimary get() = AndyColors.Neutral200
internal val TextSecondary get() = AndyColors.Neutral400
internal val Rust get() = AndyColors.Orange
internal val Green = AndyColors.Green
internal val Cyan get() = AndyColors.Blue
internal val Yellow = AndyColors.Warning
internal val Red = AndyColors.Error

@Composable
fun AndyTheme(tintId: String = AndyTint.Default.id, content: @Composable () -> Unit) {
    remember(tintId) { AndyColors.selectTint(tintId) }
    MaterialTheme(
        colorScheme = darkColorScheme(
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
        ),
        typography = Typography(
            displayLarge = LocalTextStyle.current.copy(fontFamily = DisplayFont, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
            headlineLarge = LocalTextStyle.current.copy(fontFamily = DisplayFont, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
            titleMedium = LocalTextStyle.current.copy(fontFamily = DisplayFont, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            bodyMedium = LocalTextStyle.current.copy(fontFamily = MonoFont, fontSize = 13.sp, lineHeight = 19.sp),
            bodySmall = LocalTextStyle.current.copy(fontFamily = MonoFont, fontSize = 11.sp, lineHeight = 16.sp),
            labelMedium = LocalTextStyle.current.copy(fontFamily = MonoFont, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
            labelSmall = LocalTextStyle.current.copy(fontFamily = MonoFont, fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium),
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(AndyRadius.R2),
            small = RoundedCornerShape(AndyRadius.R3),
            medium = RoundedCornerShape(AndyRadius.R4),
            large = RoundedCornerShape(AndyRadius.R5),
            extraLarge = RoundedCornerShape(18.dp),
        ),
        content = content,
    )
}
