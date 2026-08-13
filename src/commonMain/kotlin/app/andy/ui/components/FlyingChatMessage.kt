package app.andy.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyMotion
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.TextPrimary
import kotlin.math.roundToInt

/** One-shot fly from the composer field into the chat list / queue landing zone. */
@Immutable
internal data class FlyingChatMessage(
    val id: Long,
    val text: String,
    val start: Rect,
    val end: Rect,
)

/**
 * Computes the destination rect for a sent message overlay.
 *
 * Queue landings sit just above the composer (end-aligned). Transcript landings sit at the
 * bottom-trailing edge of the transcript pane, matching user bubble alignment.
 */
internal fun flyingChatMessageTarget(
    root: LayoutCoordinates,
    composer: LayoutCoordinates,
    transcript: LayoutCoordinates?,
    queued: Boolean,
    density: Density,
): Rect {
    val composerBox = root.localBoundingBoxOf(composer, clipBounds = false)
    val transcriptBox = transcript?.let { root.localBoundingBoxOf(it, clipBounds = false) }
    return flyingChatMessageTargetFromRects(
        composerBox = composerBox,
        transcriptBox = transcriptBox,
        queued = queued,
        density = density,
    )
}

/** Pure geometry for [flyingChatMessageTarget] — kept testable without layout coordinates. */
internal fun flyingChatMessageTargetFromRects(
    composerBox: Rect,
    transcriptBox: Rect?,
    queued: Boolean,
    density: Density,
): Rect {
    val padding = with(density) { AndySpace.Space2.toPx() }
    val maxBubbleWidth = with(density) { 640.dp.toPx() }
    val width = minOf(composerBox.width, maxBubbleWidth).coerceAtLeast(1f)

    if (queued) {
        val height = with(density) { 52.dp.toPx() }
        val bottom = composerBox.top - padding
        return Rect(
            left = composerBox.right - width,
            top = (bottom - height).coerceAtMost(bottom - 1f),
            right = composerBox.right,
            bottom = bottom,
        )
    }

    val landing = transcriptBox ?: composerBox
    val height = minOf(composerBox.height, with(density) { 120.dp.toPx() }).coerceAtLeast(1f)
    return Rect(
        left = landing.right - width - padding,
        top = landing.bottom - height - padding,
        right = landing.right - padding,
        bottom = landing.bottom - padding,
    )
}

@Composable
internal fun FlyingChatMessageOverlay(
    flight: FlyingChatMessage?,
    onFinished: (FlyingChatMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (flight == null) return
    val progress = remember(flight.id) { Animatable(0f) }
    val density = LocalDensity.current

    LaunchedEffect(flight.id) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = AndyMotion.SpatialMs + AndyMotion.FastMs,
                easing = FastOutSlowInEasing,
            ),
        )
        onFinished(flight)
    }

    val t = progress.value
    val left = lerp(flight.start.left, flight.end.left, t)
    val top = lerp(flight.start.top, flight.end.top, t)
    val width = lerp(flight.start.width, flight.end.width, t).coerceAtLeast(1f)
    val height = lerp(flight.start.height, flight.end.height, t).coerceAtLeast(1f)
    // Hold full opacity through most of the flight, then dissolve into the real bubble.
    val alpha = if (t < 0.82f) 1f else ((1f - t) / 0.18f).coerceIn(0f, 1f)
    val scale = lerp(1f, 0.98f, t)

    // Sized to the bubble only so the overlay does not steal pointer hits from the chat.
    Box(
        modifier
            .zIndex(2f)
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(
                width = with(density) { width.toDp() },
                height = with(density) { height.toDp() },
            )
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(AndyRadius.Control))
            .background(AndyColors.SurfaceRaised)
            .padding(horizontal = AndySpace.Space4, vertical = AndySpace.Space3),
    ) {
        Text(
            text = flight.text,
            color = TextPrimary,
            fontFamily = DisplayFont,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
