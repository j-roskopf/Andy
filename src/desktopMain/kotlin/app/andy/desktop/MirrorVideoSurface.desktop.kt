package app.andy

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.service.MirrorTouchAction
import java.awt.Point
import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.JPanel
import kotlin.math.roundToInt

@Composable
actual fun MirrorVideoSurface(
    frame: MirrorFrame?,
    modifier: Modifier,
    onInput: (MirrorInput) -> Unit,
    onHoverColor: (String) -> Unit,
    passThroughInput: Boolean,
    onDevicePointClick: (Int, Int) -> Unit,
    onRulerResize: (Float, Float) -> Unit,
    overlay: MirrorOverlay,
) {
    SwingPanel(
        modifier = modifier,
        background = Color.Black,
        factory = { MirrorPanel() },
        update = { panel ->
            panel.setFrame(frame)
            panel.onInput = onInput
            panel.onHoverColor = onHoverColor
            panel.passThroughInput = passThroughInput
            panel.onDevicePointClick = onDevicePointClick
            panel.onRulerResize = onRulerResize
            panel.setOverlay(overlay)
        },
    )
}

private class MirrorPanel : JPanel() {
    private enum class DragMode { Device, RulerWidth, RulerHeight, Inspect, None }

    private var image: BufferedImage? = null
    private var frameNumber: Long = -1
    private var overlay: MirrorOverlay = MirrorOverlay()
    var onInput: (MirrorInput) -> Unit = {}
    var onHoverColor: (String) -> Unit = {}
    var onDevicePointClick: (Int, Int) -> Unit = { _, _ -> }
    var onRulerResize: (Float, Float) -> Unit = { _, _ -> }
    var passThroughInput: Boolean = true
    private var pressedPoint: Point? = null
    private var pickerPoint: Point? = null
    private var dragMode = DragMode.None
    private var lastDeviceMoveSentAtNanos = 0L

    init {
        background = java.awt.Color.BLACK
        preferredSize = Dimension(240, 520)
        isDoubleBuffered = true
        val listener = object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                pressedPoint = event.point
                mapPoint(event.point)?.let { point ->
                    dragMode = rulerDragMode(event.point)
                    when {
                        dragMode == DragMode.RulerWidth || dragMode == DragMode.RulerHeight -> updateRulerDrag(event.point)
                        passThroughInput -> {
                            dragMode = DragMode.Device
                            lastDeviceMoveSentAtNanos = 0L
                            onInput(MirrorInput.Touch(MirrorTouchAction.Down, point.x, point.y))
                        }
                        else -> {
                            dragMode = DragMode.Inspect
                            val source = toSourcePoint(point)
                            onDevicePointClick(source.x, source.y)
                        }
                    }
                }
            }

            override fun mouseDragged(event: MouseEvent) {
                updateHoverColor(event.point)
                mapPoint(event.point)?.let { point ->
                    when (dragMode) {
                        DragMode.RulerWidth, DragMode.RulerHeight -> updateRulerDrag(event.point)
                        DragMode.Device -> sendDeviceMove(point)
                        else -> Unit
                    }
                }
            }

            override fun mouseReleased(event: MouseEvent) {
                mapPoint(event.point)?.let { point ->
                    if (dragMode == DragMode.Device) onInput(MirrorInput.Touch(MirrorTouchAction.Up, point.x, point.y))
                }
                pressedPoint = null
                lastDeviceMoveSentAtNanos = 0L
                dragMode = DragMode.None
            }

            override fun mouseMoved(event: MouseEvent) {
                updateHoverColor(event.point)
                cursor = when (rulerDragMode(event.point)) {
                    DragMode.RulerWidth -> Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
                    DragMode.RulerHeight -> Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR)
                    else -> Cursor.getDefaultCursor()
                }
            }
        }
        addMouseListener(listener)
        addMouseMotionListener(listener)
    }

    private fun sendDeviceMove(point: DevicePoint) {
        val now = System.nanoTime()
        if (now - lastDeviceMoveSentAtNanos < DEVICE_MOVE_MIN_INTERVAL_NANOS) return
        lastDeviceMoveSentAtNanos = now
        onInput(MirrorInput.Touch(MirrorTouchAction.Move, point.x, point.y))
    }

    fun setFrame(frame: MirrorFrame?) {
        if (frame == null || frame.frameNumber == frameNumber || frame.argb.size < frame.width * frame.height) return
        frameNumber = frame.frameNumber
        val buffered = image?.takeIf { it.width == frame.width && it.height == frame.height }
            ?: BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB).also {
                image = it
            }
        buffered.setRGB(0, 0, frame.width, frame.height, frame.argb, 0, frame.width)
        repaint()
    }

    fun setOverlay(next: MirrorOverlay) {
        if (overlay == next) return
        overlay = next
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val frameImage = image ?: return
        val g2 = graphics as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        val rect = fittedRect(frameImage)
        g2.drawImage(frameImage, rect.x, rect.y, rect.width, rect.height, null)
        paintOverlay(g2, frameImage, rect)
    }

    private fun mapPoint(point: Point): DevicePoint? {
        val frameImage = image ?: return null
        if (width <= 0 || height <= 0 || frameImage.width <= 0 || frameImage.height <= 0) return null
        val rect = fittedRect(frameImage)
        val localX = point.x - rect.x
        val localY = point.y - rect.y
        if (localX < 0.0 || localY < 0.0 || localX > rect.width || localY > rect.height) return null
        return DevicePoint(
            x = (localX / rect.scale).roundToInt().coerceIn(0, frameImage.width - 1),
            y = (localY / rect.scale).roundToInt().coerceIn(0, frameImage.height - 1),
        )
    }

    private fun fittedRect(frameImage: BufferedImage): DrawRect {
        val scale = minOf(width.toDouble() / frameImage.width, height.toDouble() / frameImage.height)
        val drawWidth = (frameImage.width * scale).toInt()
        val drawHeight = (frameImage.height * scale).toInt()
        return DrawRect((width - drawWidth) / 2, (height - drawHeight) / 2, drawWidth, drawHeight, scale)
    }

    private fun paintOverlay(g2: Graphics2D, frameImage: BufferedImage, rect: DrawRect) {
        if (overlay.showGrid && overlay.gridSize >= 2f) {
            val color = overlay.gridColor.toAwtColor(alphaOverride = 0.28f)
            g2.color = color
            g2.stroke = BasicStroke(1f)
            var x = rect.x.toFloat()
            val step = overlay.gridSize
            while (x <= rect.x + rect.width) {
                g2.drawLine(x.roundToInt(), rect.y, x.roundToInt(), rect.y + rect.height)
                x += step
            }
            var y = rect.y.toFloat()
            while (y <= rect.y + rect.height) {
                g2.drawLine(rect.x, y.roundToInt(), rect.x + rect.width, y.roundToInt())
                y += step
            }
        }
        if (overlay.showRuler) {
            val color = overlay.rulerColor.toAwtColor(alphaOverride = 0.95f)
            g2.color = color
            g2.stroke = BasicStroke(1.5f)
            val centerX = rect.x + rect.width / 2
            val centerY = rect.y + rect.height / 2
            val sourceWidth = overlay.sourceWidth ?: frameImage.width
            val sourceHeight = overlay.sourceHeight ?: frameImage.height
            val widthPx = overlay.rulerWidth.coerceIn(1f, sourceWidth.toFloat())
            val heightPx = overlay.rulerHeight.coerceIn(1f, sourceHeight.toFloat())
            val rulerDrawWidth = (widthPx * rect.width / sourceWidth).roundToInt()
            val rulerDrawHeight = (heightPx * rect.height / sourceHeight).roundToInt()
            g2.drawLine(centerX, rect.y, centerX, rect.y + rect.height)
            g2.drawLine(rect.x, centerY, rect.x + rect.width, centerY)
            val hY = rect.y + 28
            val vX = rect.x + 28
            g2.drawLine(centerX - rulerDrawWidth / 2, hY, centerX + rulerDrawWidth / 2, hY)
            g2.drawLine(vX, centerY - rulerDrawHeight / 2, vX, centerY + rulerDrawHeight / 2)
            g2.fillOval(centerX - rulerDrawWidth / 2 - 4, hY - 4, 8, 8)
            g2.fillOval(centerX + rulerDrawWidth / 2 - 4, hY - 4, 8, 8)
            g2.fillOval(vX - 4, centerY - rulerDrawHeight / 2 - 4, 8, 8)
            g2.fillOval(vX - 4, centerY + rulerDrawHeight / 2 - 4, 8, 8)
            g2.drawLine(centerX, rect.y + rect.height - 18, centerX + rulerDrawWidth / 2, rect.y + rect.height - 18)
            g2.drawLine(rect.x + rect.width - 18, centerY, rect.x + rect.width - 18, centerY + rulerDrawHeight / 2)
            drawPill(g2, centerX - 12, rect.y - 3, "V")
            drawPill(g2, rect.x - 28, centerY - 8, "H")
            drawPill(g2, centerX - 18, rect.y + 18, widthPx.roundToInt().toString())
            drawPill(g2, rect.x + 4, centerY - 8, heightPx.roundToInt().toString())
        }
        parseBounds(overlay.highlightBounds)?.let { bounds ->
            val sourceWidth = overlay.sourceWidth ?: frameImage.width
            val sourceHeight = overlay.sourceHeight ?: frameImage.height
            val scaleX = rect.width.toDouble() / sourceWidth.coerceAtLeast(1)
            val scaleY = rect.height.toDouble() / sourceHeight.coerceAtLeast(1)
            val x = rect.x + (bounds[0] * scaleX).roundToInt()
            val y = rect.y + (bounds[1] * scaleY).roundToInt()
            val w = ((bounds[2] - bounds[0]) * scaleX).roundToInt().coerceAtLeast(2)
            val h = ((bounds[3] - bounds[1]) * scaleY).roundToInt().coerceAtLeast(2)
            g2.color = java.awt.Color(216, 111, 74, 90)
            g2.fillRect(x, y, w, h)
            g2.color = java.awt.Color(216, 111, 74, 240)
            g2.stroke = BasicStroke(2f)
            g2.drawRect(x, y, w, h)
        }
        overlay.pickerColor?.let { color ->
            val radius = 48
            val point = pickerPoint?.takeIf { it.x in rect.x..(rect.x + rect.width) && it.y in rect.y..(rect.y + rect.height) }
            val cx = point?.x ?: (rect.x + rect.width * 7 / 10)
            val cy = point?.y ?: (rect.y + rect.height / 3)
            g2.color = color.toAwtColor(alphaOverride = 0.38f)
            g2.fillOval(cx - radius, cy - radius, radius * 2, radius * 2)
            g2.color = java.awt.Color(216, 111, 74)
            g2.stroke = BasicStroke(2f)
            g2.drawOval(cx - radius, cy - radius, radius * 2, radius * 2)
            g2.drawLine(cx - 7, cy, cx + 7, cy)
            g2.drawLine(cx, cy - 7, cx, cy + 7)
            drawPill(g2, cx - 24, cy + radius + 8, overlay.pickerHex ?: color.toHex())
        }
    }

    private fun updateHoverColor(point: Point) {
        val frameImage = image ?: return
        val mapped = mapPoint(point) ?: return
        pickerPoint = point
        val rgb = frameImage.getRGB(mapped.x, mapped.y)
        val color = java.awt.Color(rgb, true)
        onHoverColor("#%02X%02X%02X".format(color.red, color.green, color.blue))
        repaint()
    }

    private fun rulerDragMode(point: Point): DragMode {
        if (!overlay.showRuler) return DragMode.None
        val frameImage = image ?: return DragMode.None
        val rect = fittedRect(frameImage)
        val centerX = rect.x + rect.width / 2
        val centerY = rect.y + rect.height / 2
        val sourceWidth = overlay.sourceWidth ?: frameImage.width
        val sourceHeight = overlay.sourceHeight ?: frameImage.height
        val rulerDrawWidth = (overlay.rulerWidth.coerceIn(1f, sourceWidth.toFloat()) * rect.width / sourceWidth).roundToInt()
        val rulerDrawHeight = (overlay.rulerHeight.coerceIn(1f, sourceHeight.toFloat()) * rect.height / sourceHeight).roundToInt()
        val hY = rect.y + 28
        val vX = rect.x + 28
        val nearHorizontalHandle = kotlin.math.abs(point.y - hY) <= 14 &&
            (kotlin.math.abs(point.x - (centerX - rulerDrawWidth / 2)) <= 18 || kotlin.math.abs(point.x - (centerX + rulerDrawWidth / 2)) <= 18)
        val nearVerticalHandle = kotlin.math.abs(point.x - vX) <= 14 &&
            (kotlin.math.abs(point.y - (centerY - rulerDrawHeight / 2)) <= 18 || kotlin.math.abs(point.y - (centerY + rulerDrawHeight / 2)) <= 18)
        return when {
            nearHorizontalHandle -> DragMode.RulerWidth
            nearVerticalHandle -> DragMode.RulerHeight
            else -> DragMode.None
        }
    }

    private fun updateRulerDrag(point: Point) {
        val frameImage = image ?: return
        val mapped = mapPoint(point) ?: return
        val source = toSourcePoint(mapped)
        val sourceWidth = overlay.sourceWidth ?: frameImage.width
        val sourceHeight = overlay.sourceHeight ?: frameImage.height
        val nextWidth = if (dragMode == DragMode.RulerWidth) {
            (kotlin.math.abs(source.x - sourceWidth / 2f) * 2f).coerceIn(1f, sourceWidth.toFloat())
        } else {
            overlay.rulerWidth
        }
        val nextHeight = if (dragMode == DragMode.RulerHeight) {
            (kotlin.math.abs(source.y - sourceHeight / 2f) * 2f).coerceIn(1f, sourceHeight.toFloat())
        } else {
            overlay.rulerHeight
        }
        onRulerResize(nextWidth, nextHeight)
    }

    private fun toSourcePoint(point: DevicePoint): DevicePoint {
        val frameImage = image ?: return point
        val sourceWidth = overlay.sourceWidth ?: frameImage.width
        val sourceHeight = overlay.sourceHeight ?: frameImage.height
        return DevicePoint(
            x = (point.x.toDouble() / frameImage.width * sourceWidth).roundToInt().coerceIn(0, sourceWidth - 1),
            y = (point.y.toDouble() / frameImage.height * sourceHeight).roundToInt().coerceIn(0, sourceHeight - 1),
        )
    }

    private fun drawPill(g2: Graphics2D, x: Int, y: Int, text: String) {
        val width = maxOf(20, text.length * 7 + 10)
        g2.color = java.awt.Color(216, 111, 74)
        g2.fillRoundRect(x, y, width, 16, 8, 8)
        g2.color = java.awt.Color.WHITE
        g2.font = g2.font.deriveFont(10f)
        g2.drawString(text, x + 5, y + 12)
    }

    private fun parseBounds(bounds: String?): List<Int>? {
        if (bounds.isNullOrBlank()) return null
        val values = Regex("""\d+""").findAll(bounds).map { it.value.toInt() }.toList()
        return values.takeIf { it.size == 4 }
    }

    private fun Color.toAwtColor(alphaOverride: Float? = null): java.awt.Color {
        return java.awt.Color(
            red.coerceIn(0f, 1f),
            green.coerceIn(0f, 1f),
            blue.coerceIn(0f, 1f),
            (alphaOverride ?: alpha).coerceIn(0f, 1f),
        )
    }

    private fun Color.toHex(): String {
        val r = (red * 255).toInt().coerceIn(0, 255)
        val g = (green * 255).toInt().coerceIn(0, 255)
        val b = (blue * 255).toInt().coerceIn(0, 255)
        return "#%02X%02X%02X".format(r, g, b)
    }

    private data class DevicePoint(val x: Int, val y: Int)
    private data class DrawRect(val x: Int, val y: Int, val width: Int, val height: Int, val scale: Double)

    companion object {
        private const val DEVICE_MOVE_MIN_INTERVAL_NANOS = 8_000_000L
    }
}
