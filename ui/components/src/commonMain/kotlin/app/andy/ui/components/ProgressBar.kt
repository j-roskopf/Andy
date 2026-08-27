package app.andy.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.andyTokens

enum class ProgressBarVariant {
    Accent,
    Success,
    Warning,
    Error,
    Neutral,
}

/** Astryx ProgressBar — 8dp track, pill fill, optional label row. */
@Composable
fun ProgressBar(
    modifier: Modifier = Modifier,
    value: Float = 0f,
    max: Float = 100f,
    label: String? = null,
    showValueLabel: Boolean = false,
    variant: ProgressBarVariant = ProgressBarVariant.Accent,
    indeterminate: Boolean = false,
    enabled: Boolean = true,
) {
    val tokens = andyTokens()
    val clamped = if (max > 0f) (value / max).coerceIn(0f, 1f) else 0f
    val fillColor = when {
        !enabled -> AndyColors.TextDisabled
        variant == ProgressBarVariant.Accent -> tokens.accent
        variant == ProgressBarVariant.Success -> tokens.success
        variant == ProgressBarVariant.Warning -> tokens.warning
        variant == ProgressBarVariant.Error -> tokens.error
        else -> AndyColors.TextDisabled
    }
    val trackColor = if (AndyColors.isLight) {
        Color(0xFF053659).copy(alpha = 0.05f)
    } else {
        Color(0xFF111112).copy(alpha = 0.50f)
    }
    val shape = RoundedCornerShape(AndyRadius.Pill)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AndySpace.Space1)) {
        if (label != null || showValueLabel) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (label != null) {
                    Text(
                        label,
                        color = if (enabled) TextPrimary else AndyColors.TextDisabled,
                        fontFamily = DisplayFont,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium,
                    )
                } else {
                    Box(Modifier)
                }
                if (showValueLabel && !indeterminate) {
                    Text(
                        "${(clamped * 100).toInt()}%",
                        color = if (enabled) TextSecondary else AndyColors.TextDisabled,
                        fontFamily = DisplayFont,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(shape)
                .background(trackColor),
        ) {
            if (indeterminate) {
                val transition = rememberInfiniteTransition(label = "progress-indeterminate")
                val slide by transition.animateFloat(
                    initialValue = -0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "progress-slide",
                )
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val barWidth = maxWidth * 0.4f
                    Box(
                        Modifier
                            .width(barWidth)
                            .fillMaxHeight()
                            .offset(x = maxWidth * slide)
                            .clip(shape)
                            .background(fillColor),
                    )
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth(clamped)
                        .fillMaxHeight()
                        .clip(shape)
                        .background(fillColor),
                )
            }
        }
    }
}
