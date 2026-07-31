package app.andy.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import kotlinx.coroutines.delay

@Composable
internal fun ChatSendButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    isSending: Boolean = false,
) {
    var launchAnim by remember { mutableStateOf(false) }
    val sending = isSending || launchAnim

    val sendProgress by animateFloatAsState(
        targetValue = if (sending) 1f else 0f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "sendProgress",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (sending) 0.92f else 1f,
        animationSpec = spring(stiffness = 500f, dampingRatio = 0.72f),
        label = "sendPressScale",
    )

    LaunchedEffect(launchAnim) {
        if (launchAnim) {
            delay(320)
            launchAnim = false
        }
    }

    val background = when {
        !enabled -> AndyColors.SurfaceHover
        sending -> AndyColors.OrangePressed
        else -> AndyColors.Orange
    }
    val iconColor = when {
        !enabled -> AndyColors.TextDisabled
        AndyColors.isLight -> Color.White
        else -> Color(0xFF0A0A0A)
    }

    Box(
        modifier
            .size(AndyLayout.ControlHeightMd)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled && !sending) {
                launchAnim = true
                onClick()
            }
            .semantics { contentDescription = "Send" },
        contentAlignment = Alignment.Center,
    ) {
        SendArrowIcon(
            color = iconColor,
            progress = sendProgress,
            modifier = Modifier.size(AndyLayout.IconMd),
        )
    }
}

@Composable
private fun SendArrowIcon(
    color: Color,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.Canvas(
        modifier.graphicsLayer {
            translationY = -progress * 6.dp.toPx()
            alpha = 1f - progress * 0.9f
        },
    ) {
        val w = size.width
        val h = size.height
        val stemWidth = w * 0.24f
        val stemTop = h * 0.52f
        val stemBottom = h * 0.84f
        val corner = stemWidth / 2f

        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset((w - stemWidth) / 2f, stemTop),
            size = androidx.compose.ui.geometry.Size(stemWidth, stemBottom - stemTop),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
        )

        val headPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(w / 2f, h * 0.16f)
            lineTo(w * 0.84f, h * 0.56f)
            lineTo(w * 0.16f, h * 0.56f)
            close()
        }
        drawPath(headPath, color)
    }
}
