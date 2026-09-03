package app.andy.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
data class FlyingChatMessage(
    val id: Long,
    val text: String,
    val start: Rect,
    val end: Rect,
    /** Right edge of the clipped overlay host, in the same coordinate space as [start]/[end]. */
    val containerRight: Float,
)

/**
 * Computes the destination rect for a sent message overlay.
 *
 * Queue landings sit just above the composer (end-aligned). Transcript landings sit at the
 * bottom-trailing edge of the transcript pane, matching user bubble alignment.
 */
fun flyingChatMessageTarget(
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
fun flyingChatMessageTargetFromRects(
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

/**
 * Frame for the two-phase send flight.
 *
 * Progress `0 → 1` slides off to the right at the composer Y; `1 → 2` slides in from the
 * right at the landing Y. At progress `1` the bubble is fully past [containerRight].
 */
@Immutable
data class FlyingChatMessageFrame(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val alpha: Float,
    val scale: Float,
)

fun flyingChatMessageFrame(
    start: Rect,
    end: Rect,
    containerRight: Float,
    progress: Float,
): FlyingChatMessageFrame {
    val t = progress.coerceIn(0f, 2f)
    // Fully off-screen when the bubble's left edge reaches the host's right edge.
    val offscreenLeft = containerRight
    return if (t <= 1f) {
        val phase = t
        FlyingChatMessageFrame(
            left = lerp(start.left, offscreenLeft, phase),
            top = start.top,
            width = start.width.coerceAtLeast(1f),
            height = start.height.coerceAtLeast(1f),
            alpha = 1f,
            scale = 1f,
        )
    } else {
        val phase = t - 1f
        // Stay opaque through landing; the real bubble is suppressed until onFinished
        // so dissolving early would flash an empty chat slot.
        FlyingChatMessageFrame(
            left = lerp(offscreenLeft, end.left, phase),
            top = end.top,
            width = lerp(start.width, end.width, phase).coerceAtLeast(1f),
            height = lerp(start.height, end.height, phase).coerceAtLeast(1f),
            alpha = 1f,
            scale = lerp(1f, 0.98f, phase),
        )
    }
}

@Composable
fun FlyingChatMessageOverlay(
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
                durationMillis = AndyMotion.SpatialMs,
                easing = FastOutLinearInEasing,
            ),
        )
        progress.animateTo(
            targetValue = 2f,
            animationSpec = tween(
                durationMillis = AndyMotion.SpatialMs,
                easing = LinearOutSlowInEasing,
            ),
        )
        onFinished(flight)
    }

    val frame = flyingChatMessageFrame(
        start = flight.start,
        end = flight.end,
        containerRight = flight.containerRight,
        progress = progress.value,
    )

    // Host fills the pane and clips so the bubble disappears at the right edge
    // before re-entering toward the chat list landing.
    Box(
        modifier
            .fillMaxSize()
            .zIndex(2f),
    ) {
        Box(
            Modifier
                .offset { IntOffset(frame.left.roundToInt(), frame.top.roundToInt()) }
                .size(
                    width = with(density) { frame.width.toDp() },
                    height = with(density) { frame.height.toDp() },
                )
                .graphicsLayer {
                    alpha = frame.alpha
                    scaleX = frame.scale
                    scaleY = frame.scale
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
}
