package app.andy.desktop.service

import app.andy.model.ScreenshotAnnotation
import app.andy.model.ScreenshotEdits
import app.andy.service.ArtifactService
import app.andy.service.CommandResult
import app.andy.service.DeviceService
import app.andy.service.MirrorEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class DesktopArtifactService(
    private val runner: CommandRunner,
    private val devices: DeviceService,
    private val mirror: MirrorEngine,
) : ArtifactService {
    override suspend fun saveScreenshot(serial: String, suggestedName: String): CommandResult = withContext(Dispatchers.IO) {
        val target = chooseSaveFile(suggestedName) ?: return@withContext CommandResult.failure("Screenshot save canceled")
        val bytes = mirror.screenshot(serial) ?: return@withContext CommandResult.failure("Screenshot failed")
        runCatching {
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            CommandResult.success("Saved screenshot to ${target.absolutePath}")
        }.getOrElse { CommandResult.failure(it.message ?: "Screenshot save failed") }
    }

    override suspend fun saveBugReport(serial: String, suggestedName: String): CommandResult = withContext(Dispatchers.IO) {
        val target = chooseSaveFile(suggestedName) ?: return@withContext CommandResult.failure("Bug report save canceled")
        val adb = devices.adbPath() ?: return@withContext CommandResult.failure("ADB not found")
        target.parentFile?.mkdirs()
        runner.run(listOf(adb, "-s", serial, "bugreport", target.absolutePath), 180)
    }

    override suspend fun captureScreenshotForEditing(serial: String): ByteArray? = withContext(Dispatchers.IO) {
        mirror.screenshot(serial)
    }

    override suspend fun renderScreenshotEdits(basePngBytes: ByteArray, edits: ScreenshotEdits): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = ImageIO.read(ByteArrayInputStream(basePngBytes)) ?: return@withContext null
                val framed = if (edits.deviceFrame) applyDeviceFrame(base) else base
                val offsetX = (framed.width - base.width) / 2
                val offsetY = if (edits.deviceFrame) DeviceFrameTopInset else 0
                val graphics = framed.createGraphics()
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                edits.annotations.forEach { annotation ->
                    drawAnnotation(graphics, annotation, base.width, base.height, offsetX, offsetY)
                }
                graphics.dispose()
                val output = ByteArrayOutputStream()
                ImageIO.write(framed, "png", output)
                output.toByteArray()
            }.getOrNull()
        }

    override suspend fun saveEditedScreenshot(pngBytes: ByteArray, suggestedName: String): CommandResult =
        withContext(Dispatchers.IO) {
            val target = chooseSaveFile(suggestedName) ?: return@withContext CommandResult.failure("Screenshot save canceled")
            runCatching {
                target.parentFile?.mkdirs()
                target.writeBytes(pngBytes)
                CommandResult.success("Saved screenshot to ${target.absolutePath}")
            }.getOrElse { CommandResult.failure(it.message ?: "Screenshot save failed") }
        }

    /** Simple bezel + bottom chin, roughly evoking a phone frame — not a per-model asset. */
    private fun applyDeviceFrame(base: BufferedImage): BufferedImage {
        val bezel = DeviceFrameBezel
        val width = base.width + bezel * 2
        val height = base.height + DeviceFrameTopInset + DeviceFrameBottomInset
        val framed = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = framed.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.color = Color(18, 18, 20)
        graphics.fillRoundRect(0, 0, width, height, 48, 48)
        graphics.color = Color(40, 40, 44)
        graphics.fillRoundRect(4, 4, width - 8, height - 8, 42, 42)
        graphics.drawImage(base, bezel, DeviceFrameTopInset, null)
        graphics.color = Color(90, 90, 96)
        graphics.fillRoundRect(width / 2 - 24, height - DeviceFrameBottomInset / 2 - 3, 48, 6, 6, 6)
        graphics.dispose()
        return framed
    }

    private fun drawAnnotation(
        graphics: Graphics2D,
        annotation: ScreenshotAnnotation,
        imageWidth: Int,
        imageHeight: Int,
        offsetX: Int,
        offsetY: Int,
    ) {
        fun px(x: Float) = offsetX + x * imageWidth
        fun py(y: Float) = offsetY + y * imageHeight
        when (annotation) {
            is ScreenshotAnnotation.Redaction -> {
                graphics.color = Color.BLACK
                val left = px(annotation.left)
                val top = py(annotation.top)
                graphics.fillRect(left.toInt(), top.toInt(), (px(annotation.right) - left).toInt(), (py(annotation.bottom) - top).toInt())
            }
            is ScreenshotAnnotation.Box -> {
                graphics.color = AnnotationColor
                graphics.stroke = BasicStroke(AnnotationStrokeWidth)
                val left = px(annotation.left)
                val top = py(annotation.top)
                graphics.drawRect(left.toInt(), top.toInt(), (px(annotation.right) - left).toInt(), (py(annotation.bottom) - top).toInt())
            }
            is ScreenshotAnnotation.Arrow -> {
                graphics.color = AnnotationColor
                graphics.stroke = BasicStroke(AnnotationStrokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                drawArrow(graphics, px(annotation.startX), py(annotation.startY), px(annotation.endX), py(annotation.endY))
            }
            is ScreenshotAnnotation.Freehand -> {
                graphics.color = AnnotationColor
                graphics.stroke = BasicStroke(AnnotationStrokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val path = Path2D.Float()
                annotation.points.chunked(2).forEachIndexed { index, point ->
                    if (point.size < 2) return@forEachIndexed
                    val x = px(point[0])
                    val y = py(point[1])
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                graphics.draw(path)
            }
            is ScreenshotAnnotation.TextNote -> {
                graphics.color = AnnotationColor
                graphics.font = graphics.font.deriveFont(28f)
                graphics.drawString(annotation.text, px(annotation.x), py(annotation.y))
            }
        }
    }

    private fun drawArrow(graphics: Graphics2D, startX: Float, startY: Float, endX: Float, endY: Float) {
        graphics.draw(java.awt.geom.Line2D.Float(startX, startY, endX, endY))
        val angle = atan2((endY - startY).toDouble(), (endX - startX).toDouble())
        val headLength = 18.0
        val headAngle = Math.toRadians(28.0)
        val path = Path2D.Float()
        path.moveTo(endX.toDouble(), endY.toDouble())
        path.lineTo(endX - headLength * cos(angle - headAngle), endY - headLength * sin(angle - headAngle))
        path.moveTo(endX.toDouble(), endY.toDouble())
        path.lineTo(endX - headLength * cos(angle + headAngle), endY - headLength * sin(angle + headAngle))
        graphics.draw(path)
    }

    private fun chooseSaveFile(suggestedName: String): File? {
        var selected: File? = null
        val task = Runnable {
            val chooser = JFileChooser().apply {
                selectedFile = File(suggestedName)
                dialogTitle = "Save ${suggestedName.substringBeforeLast('.', suggestedName)}"
            }
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                selected = chooser.selectedFile
            }
        }
        if (SwingUtilities.isEventDispatchThread()) {
            task.run()
        } else {
            SwingUtilities.invokeAndWait(task)
        }
        return selected
    }

    private companion object {
        const val DeviceFrameBezel = 28
        const val DeviceFrameTopInset = 28
        const val DeviceFrameBottomInset = 56
        const val AnnotationStrokeWidth = 5f
        val AnnotationColor: Color = Color(255, 92, 61)
    }
}
