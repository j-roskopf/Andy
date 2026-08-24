package app.andy.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.andy.ui.theme.andyTokens

internal enum class StatusDotVariant {
    Success,
    Warning,
    Error,
    Neutral,
    Info,
}

/** Astryx StatusDot — 8px semantic circle, optional pulse. */
@Composable
internal fun StatusDot(
    modifier: Modifier = Modifier,
    variant: StatusDotVariant = StatusDotVariant.Neutral,
    pulsing: Boolean = false,
) {
    val tokens = andyTokens()
    val color = when (variant) {
        StatusDotVariant.Success -> tokens.success
        StatusDotVariant.Warning -> tokens.warning
        StatusDotVariant.Error -> tokens.error
        StatusDotVariant.Info -> tokens.accent
        StatusDotVariant.Neutral -> tokens.skeleton
    }
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "status-dot-pulse")
        val pulse by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.5f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "status-dot-alpha",
        )
        pulse
    } else {
        1f
    }
    Box(
        modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}
