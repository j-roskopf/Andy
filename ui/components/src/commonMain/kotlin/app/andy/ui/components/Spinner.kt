package app.andy.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyMotion
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.andyTokens

enum class SpinnerSize(val diameter: Dp, val stroke: Dp) {
    Sm(10.dp, 2.dp),
    Md(14.dp, 3.dp),
    Lg(18.dp, 3.dp),
    Xl(28.dp, 4.dp),
}

enum class SpinnerShade {
    Default,
    Subtle,
    OnMedia,
    Inherit,
}

/** Astryx Spinner — accent arc on a track ring, 135° sweep. */
@Composable
fun Spinner(
    modifier: Modifier = Modifier,
    spinnerSize: SpinnerSize = SpinnerSize.Md,
    shade: SpinnerShade = SpinnerShade.Default,
    contentDescription: String = "Loading",
) {
    val tokens = andyTokens()
    val arcColor = when (shade) {
        SpinnerShade.Default -> tokens.accent
        SpinnerShade.Subtle -> TextSecondary
        SpinnerShade.OnMedia -> Color.White
        SpinnerShade.Inherit -> Color.Unspecified
    }
    val trackColor = when (shade) {
        SpinnerShade.OnMedia -> Color.White.copy(alpha = 77f / 255f)
        SpinnerShade.Inherit -> Color.Unspecified
        else -> tokens.skeleton
    }
    val resolvedArc = if (arcColor == Color.Unspecified) tokens.accent else arcColor
    val resolvedTrack = if (trackColor == Color.Unspecified) {
        resolvedArc.copy(alpha = 0.30f)
    } else {
        trackColor
    }
    val frame = spinnerSize.diameter + spinnerSize.stroke * 2
    val transition = rememberInfiniteTransition(label = "spinner-rotate")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(AndyMotion.SpatialMs, easing = LinearEasing),
        ),
        label = "spinner-rotation",
    )
    Canvas(
        modifier
            .size(frame)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val strokePx = spinnerSize.stroke.toPx()
        val inset = strokePx / 2f
        val arcSweep = 360f * 0.375f
        val diameterPx = spinnerSize.diameter.toPx()
        drawArc(
            color = resolvedTrack,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(diameterPx, diameterPx),
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
        drawArc(
            color = resolvedArc,
            startAngle = rotation - 90f,
            sweepAngle = arcSweep,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(diameterPx, diameterPx),
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
    }
}
