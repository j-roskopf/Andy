package app.andy.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.andy.ui.theme.AndyMotion
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.andyTokens

/**
 * Loading placeholder — Astryx Skeleton: `--color-skeleton` block with opacity pulse.
 */
@Composable
fun Skeleton(
    modifier: Modifier = Modifier,
    width: Dp = Dp.Unspecified,
    height: Dp = Dp.Unspecified,
    shape: Shape = AndyShape.Menu,
    animate: Boolean = true,
    staggerIndex: Int = 0,
) {
    val tokens = andyTokens()
    val transition = rememberInfiniteTransition(label = "skeleton-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = AndyMotion.MediumMs,
                delayMillis = 1000 + staggerIndex * 100,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-alpha",
    )
    Box(
        modifier
            .then(if (width != Dp.Unspecified) Modifier.width(width) else Modifier)
            .then(if (height != Dp.Unspecified) Modifier.height(height) else Modifier)
            .alpha(if (animate) alpha else 0.25f)
            .background(tokens.skeleton, shape),
    )
}

/** Common skeleton radii mapped from Astryx scale. */
object SkeletonShape {
    val Element = AndyShape.Interactive
    val Container = AndyShape.Menu
    val Pill = RoundedCornerShape(AndyRadius.Pill)
}
