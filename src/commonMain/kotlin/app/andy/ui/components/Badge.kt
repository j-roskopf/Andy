package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.andyTokens

internal enum class BadgeVariant {
    Neutral,
    Info,
    Success,
    Warning,
    Error,
    Blue,
    Green,
    Red,
    Yellow,
}

/** Astryx Badge — 20dp pill, supporting type, semantic fills. */
@Composable
internal fun Badge(
    label: String,
    modifier: Modifier = Modifier,
    variant: BadgeVariant = BadgeVariant.Neutral,
    leading: (@Composable () -> Unit)? = null,
) {
    val (background, foreground) = badgeColors(variant)
    Row(
        modifier
            .height(AndySpace.Space5)
            .background(background, RoundedCornerShape(AndyRadius.Pill))
            .padding(horizontal = AndySpace.Space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space1),
    ) {
        leading?.invoke()
        Text(
            label,
            color = foreground,
            fontFamily = DisplayFont,
            fontSize = 12.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun badgeColors(variant: BadgeVariant): Pair<Color, Color> {
    val tokens = andyTokens()
    val isLight = AndyColors.isLight
    return when (variant) {
        BadgeVariant.Neutral -> tokens.neutralFill to TextPrimary
        BadgeVariant.Info -> tokens.accent to tokens.onAccent
        BadgeVariant.Success -> tokens.success to Color.White
        BadgeVariant.Warning -> tokens.warning to Color(0xFF0A1317)
        BadgeVariant.Error -> tokens.error to Color.White
        BadgeVariant.Blue -> Color(0x330171E3) to if (isLight) Color(0xFF042F97) else Color(0xFFAFD7FF)
        BadgeVariant.Green -> Color(0x3324BB5E) to if (isLight) Color(0xFF09441F) else Color(0xFFA5F690)
        BadgeVariant.Red -> Color(0x33E3193B) to if (isLight) Color(0xFF7B0210) else Color(0xFFFFB2B8)
        BadgeVariant.Yellow -> Color(0x33E2A400) to if (isLight) Color(0xFF753F07) else Color(0xFFFBCE03)
    }
}
