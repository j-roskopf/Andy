package app.andy.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.andy.domain.parseBounds
import app.andy.service.MirrorFrame
import app.andy.ui.theme.Rust
import app.andy.ui.theme.Yellow

@Composable
internal fun DeviceOverlay(frame: MirrorFrame, highlightBounds: String?, showRuler: Boolean, gridSize: Float?, gridColor: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val rect = fittedRect(size.width, size.height, frame.width, frame.height)
        gridSize?.takeIf { it >= 2f }?.let { step ->
            var x = rect.left
            while (x <= rect.right) {
                drawLine(gridColor, Offset(x, rect.top), Offset(x, rect.bottom))
                x += step
            }
            var y = rect.top
            while (y <= rect.bottom) {
                drawLine(gridColor, Offset(rect.left, y), Offset(rect.right, y))
                y += step
            }
        }
        if (showRuler) {
            val tick = 20.dp.toPx()
            var x = rect.left
            var tickIndex = 0
            while (x <= rect.right) {
                drawLine(Yellow.copy(alpha = 0.65f), Offset(x, rect.top), Offset(x, rect.top + if (tickIndex % 5 == 0) 18.dp.toPx() else 9.dp.toPx()), 1f)
                x += tick
                tickIndex += 1
            }
            var y = rect.top
            tickIndex = 0
            while (y <= rect.bottom) {
                drawLine(Yellow.copy(alpha = 0.65f), Offset(rect.left, y), Offset(rect.left + if (tickIndex % 5 == 0) 18.dp.toPx() else 9.dp.toPx(), y), 1f)
                y += tick
                tickIndex += 1
            }
        }
        parseBounds(highlightBounds)?.let { (left, top, right, bottom) ->
            val scaleX = rect.width / frame.width.coerceAtLeast(1)
            val scaleY = rect.height / frame.height.coerceAtLeast(1)
            drawRect(
                color = Rust.copy(alpha = 0.28f),
                topLeft = Offset(rect.left + left * scaleX, rect.top + top * scaleY),
                size = androidx.compose.ui.geometry.Size((right - left) * scaleX, (bottom - top) * scaleY),
            )
            drawRect(
                color = Rust,
                topLeft = Offset(rect.left + left * scaleX, rect.top + top * scaleY),
                size = androidx.compose.ui.geometry.Size((right - left) * scaleX, (bottom - top) * scaleY),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

internal data class FittedRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

internal fun fittedRect(containerWidth: Float, containerHeight: Float, contentWidth: Int, contentHeight: Int): FittedRect {
    val safeWidth = contentWidth.coerceAtLeast(1)
    val safeHeight = contentHeight.coerceAtLeast(1)
    val scale = minOf(containerWidth / safeWidth, containerHeight / safeHeight)
    val width = safeWidth * scale
    val height = safeHeight * scale
    return FittedRect((containerWidth - width) / 2f, (containerHeight - height) / 2f, width, height)
}
