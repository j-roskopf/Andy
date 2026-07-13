package app.andy.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object AndyColors {
    val Neutral100 = Color(0xFFF4F1E8)
    val Neutral200 = Color(0xFFE4DED0)
    val Neutral300 = Color(0xFFC6BEAD)
    val Neutral400 = Color(0xFF8E8779)
    val Neutral500 = Color(0xFF514D44)
    val Neutral600 = Color(0xFF302D27)
    val Neutral700 = Color(0xFF24211C)
    val Neutral750 = Color(0xFF1D1A16)
    val Neutral800 = Color(0xFF171511)
    val Neutral850 = Color(0xFF11100D)
    val Neutral900 = Color(0xFF0A0908)

    val Orange = Color(0xFFD18A4B)
    val OrangeHover = Color(0xFFE0A56E)
    val OrangePressed = Color(0xFFB97138)
    val OrangeSubtle = Color(0xFF2C2117)
    val OrangeBorder = Color(0xFF8D6746)
    val Green = Color(0xFF94C17A)
    val GreenSoft = Color(0xFFB4D59E)
    val GreenSubtle = Color(0xFF172418)
    val Blue = Color(0xFF88AFC8)
    val Warning = Color(0xFFE3B05E)
    val Error = Color(0xFFE26F5C)
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
internal val Ink = AndyColors.Neutral900
internal val Panel = AndyColors.Neutral800
internal val PanelSoft = AndyColors.Neutral700
internal val Border = AndyColors.Neutral100.copy(alpha = 0.10f)
internal val PaneDividerTint = AndyColors.OrangeBorder.copy(alpha = 0.72f)
internal val TextPrimary = AndyColors.Neutral200
internal val TextSecondary = AndyColors.Neutral400
internal val Rust = AndyColors.Orange
internal val Green = AndyColors.Green
internal val Cyan = AndyColors.Blue
internal val Yellow = AndyColors.Warning
internal val Red = AndyColors.Error

@Composable
fun AndyTheme(content: @Composable () -> Unit) {
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
