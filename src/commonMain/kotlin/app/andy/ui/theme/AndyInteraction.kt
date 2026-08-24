package app.andy.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Tactile press feedback — subtle scale-down while pressed (design-taste §4.5).
 * Animates transform only for performance (§6.A).
 */
@Composable
internal fun Modifier.andyPressScale(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) return this
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = AndyMotion.standardTween(AndyMotion.FastMs),
        label = "andy-press-scale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
