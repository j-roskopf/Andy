package app.andy.ui.live
import app.andy.ui.components.Spinner
import app.andy.ui.components.SpinnerSize

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import app.andy.ui.components.AndyCheckbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.loadImageBitmap
import app.andy.model.ScreenshotAnnotation
import app.andy.model.ScreenshotEdits
import app.andy.service.ArtifactService
import app.andy.service.BugService
import app.andy.service.CommandResult
import app.andy.ui.components.Button
import app.andy.ui.components.FilterPill
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.TextField
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.components.SuppressHeavyweightSurfacesWhileOpen
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Panel
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch

internal enum class ScreenshotTool(val label: String) {
    Redact("Redact"),
    Box("Box"),
    Arrow("Arrow"),
    Freehand("Pen"),
    Text("Text"),
}

/**
 * Redaction/annotation/device-frame editor shown after a screenshot capture (§E.5). Annotations
 * are stored normalized to `[0, 1]` over the base image so the same coordinates work for this
 * (possibly letterboxed/scaled-down) preview and [ArtifactService.renderScreenshotEdits]'s
 * full-resolution bake. Undo pops the most recent annotation off a flat stack.
 */
@Composable
internal fun ScreenshotEditorSheet(
    pngBytes: ByteArray,
    artifacts: ArtifactService,
    bugs: BugService? = null,
    suggestedName: String,
    onDismiss: () -> Unit,
) {
    SuppressHeavyweightSurfacesWhileOpen()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val bitmap = remember(pngBytes) { loadImageBitmap(pngBytes) }
    val annotations = remember(pngBytes) { mutableStateListOf<ScreenshotAnnotation>() }
    var tool by remember { mutableStateOf(ScreenshotTool.Redact) }
    var deviceFrame by remember { mutableStateOf(false) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    var freehandPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var pendingTextAt by remember { mutableStateOf<Offset?>(null) }
    var pendingText by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    fun normalized(point: Offset, rect: FittedRect): Offset = Offset(
        ((point.x - rect.left) / rect.width).coerceIn(0f, 1f),
        ((point.y - rect.top) / rect.height).coerceIn(0f, 1f),
    )

    fun commitDrag(rect: FittedRect) {
        val start = dragStart
        val end = dragCurrent
        when {
            tool == ScreenshotTool.Freehand && freehandPoints.size > 1 -> {
                val normalizedPoints = freehandPoints.map { normalized(it, rect) }
                annotations += ScreenshotAnnotation.Freehand(normalizedPoints.flatMap { listOf(it.x, it.y) })
            }
            start != null && end != null && (start - end).getDistance() > 4f -> {
                val a = normalized(start, rect)
                val b = normalized(end, rect)
                annotations += when (tool) {
                    ScreenshotTool.Redact -> ScreenshotAnnotation.Redaction(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
                    ScreenshotTool.Box -> ScreenshotAnnotation.Box(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
                    ScreenshotTool.Arrow -> ScreenshotAnnotation.Arrow(a.x, a.y, b.x, b.y)
                    else -> return
                }
            }
        }
        dragStart = null
        dragCurrent = null
        freehandPoints = emptyList()
    }

    fun runSave() {
        saving = true
        status = ""
        scope.launch {
            val edits = ScreenshotEdits(annotations.toList(), deviceFrame)
            val finalBytes = artifacts.renderScreenshotEdits(pngBytes, edits) ?: pngBytes
            val result: CommandResult = artifacts.saveEditedScreenshot(finalBytes, suggestedName)
            saving = false
            if (result.isSuccess) {
                bugs?.recordScreenshot(finalBytes, "Edited screenshot")
                onDismiss()
            } else {
                status = result.stderr.ifBlank { "Save failed" }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .background(Panel, RoundedCornerShape(AndyRadius.Row))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Edit screenshot", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            }

            if (bitmap == null) {
                Text("Could not load the screenshot", color = Rust, fontSize = 12.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScreenshotTool.entries.forEach { candidate ->
                        FilterPill(candidate.label, tool == candidate, Rust) { tool = candidate }
                    }
                }

                BoxWithConstraints(
                    Modifier.fillMaxWidth().heightIn(max = 460.dp)
                        .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                        .background(Color.Black, RoundedCornerShape(AndyRadius.Control)),
                ) {
                    val rect = remember(maxWidth, maxHeight, bitmap.width, bitmap.height, density) {
                        val widthPx = with(density) { maxWidth.toPx() }
                        val heightPx = with(density) { maxHeight.toPx() }
                        fittedRect(widthPx, heightPx, bitmap.width, bitmap.height)
                    }
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    Canvas(
                        Modifier.fillMaxSize()
                            .pointerInput(tool) {
                                if (tool == ScreenshotTool.Text) {
                                    detectTapGestures { point ->
                                        pendingTextAt = point
                                        pendingText = ""
                                    }
                                } else {
                                    detectDragGestures(
                                        onDragStart = { point ->
                                            dragStart = point
                                            dragCurrent = point
                                            freehandPoints = listOf(point)
                                        },
                                        onDrag = { change, _ ->
                                            dragCurrent = change.position
                                            if (tool == ScreenshotTool.Freehand) freehandPoints = freehandPoints + change.position
                                        },
                                        onDragEnd = { commitDrag(rect) },
                                        onDragCancel = { dragStart = null; dragCurrent = null; freehandPoints = emptyList() },
                                    )
                                }
                            },
                    ) {
                        annotations.forEach { annotation -> drawAnnotationPreview(annotation, rect) }
                        drawInProgressAnnotation(tool, dragStart, dragCurrent, freehandPoints)
                    }
                    pendingTextAt?.let { at ->
                        val offsetX = with(density) { at.x.toDp() }
                        val offsetY = with(density) { at.y.toDp() }
                        Box(Modifier.offset(x = offsetX, y = offsetY)) {
                            TextField(
                                value = pendingText,
                                onValueChange = { pendingText = it },
                                singleLine = true,
                                modifier = Modifier.widthIn(min = 120.dp),
                            )
                        }
                        Row(Modifier.offset(x = offsetX, y = offsetY + 32.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { pendingTextAt = null }) { Text("Cancel") }
                            Button(
                                onClick = {
                                    val normalizedPoint = normalized(at, rect)
                                    if (pendingText.isNotBlank()) {
                                        annotations += ScreenshotAnnotation.TextNote(normalizedPoint.x, normalizedPoint.y, pendingText)
                                    }
                                    pendingTextAt = null
                                },
                                colors = primaryButtonColors(),
                                shape = RoundedCornerShape(8.dp),
                            ) { Text("Add") }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AndyCheckbox(checked = deviceFrame, onCheckedChange = { deviceFrame = it })
                    Text("Device frame", color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { if (annotations.isNotEmpty()) annotations.removeAt(annotations.lastIndex) }, enabled = annotations.isNotEmpty()) {
                        Text("Undo")
                    }
                }

                if (status.isNotBlank()) Text(status, color = Rust, fontSize = 12.sp)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (saving) Spinner(spinnerSize = SpinnerSize.Md)
                    Button(
                        onClick = ::runSave,
                        enabled = !saving,
                        colors = primaryButtonColors(),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.padding(start = 10.dp),
                    ) { Text(if (saving) "Saving…" else "Save…") }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAnnotationPreview(annotation: ScreenshotAnnotation, rect: FittedRect) {
    fun px(x: Float) = rect.left + x * rect.width
    fun py(y: Float) = rect.top + y * rect.height
    when (annotation) {
        is ScreenshotAnnotation.Redaction -> drawRect(
            color = Color.Black,
            topLeft = Offset(px(annotation.left), py(annotation.top)),
            size = androidx.compose.ui.geometry.Size(px(annotation.right) - px(annotation.left), py(annotation.bottom) - py(annotation.top)),
        )
        is ScreenshotAnnotation.Box -> drawRect(
            color = Rust,
            topLeft = Offset(px(annotation.left), py(annotation.top)),
            size = androidx.compose.ui.geometry.Size(px(annotation.right) - px(annotation.left), py(annotation.bottom) - py(annotation.top)),
            style = Stroke(width = 3f),
        )
        is ScreenshotAnnotation.Arrow -> drawArrowPreview(Offset(px(annotation.startX), py(annotation.startY)), Offset(px(annotation.endX), py(annotation.endY)))
        is ScreenshotAnnotation.Freehand -> {
            val points = annotation.points.chunked(2).mapNotNull { if (it.size == 2) Offset(it[0], it[1]) else null }
            points.zipWithNext().forEach { (a, b) -> drawLine(Rust, a, b, strokeWidth = 5f, cap = StrokeCap.Round) }
        }
        is ScreenshotAnnotation.TextNote -> drawCircle(Rust, radius = 4f, center = Offset(px(annotation.x), py(annotation.y)))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawInProgressAnnotation(
    tool: ScreenshotTool,
    start: Offset?,
    current: Offset?,
    freehand: List<Offset>,
) {
    if (tool == ScreenshotTool.Freehand) {
        freehand.zipWithNext().forEach { (a, b) -> drawLine(Rust, a, b, strokeWidth = 5f, cap = StrokeCap.Round) }
        return
    }
    val a = start ?: return
    val b = current ?: return
    when (tool) {
        ScreenshotTool.Redact -> drawRect(Color.Black.copy(alpha = 0.7f), topLeft = Offset(minOf(a.x, b.x), minOf(a.y, b.y)), size = androidx.compose.ui.geometry.Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)))
        ScreenshotTool.Box -> drawRect(Rust, topLeft = Offset(minOf(a.x, b.x), minOf(a.y, b.y)), size = androidx.compose.ui.geometry.Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)), style = Stroke(width = 3f))
        ScreenshotTool.Arrow -> drawArrowPreview(a, b)
        else -> Unit
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrowPreview(start: Offset, end: Offset) {
    drawLine(Rust, start, end, strokeWidth = 4f, cap = StrokeCap.Round)
    val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
    val headLength = 16f
    val headAngle = 28.0 * kotlin.math.PI / 180.0
    val left = Offset(end.x - headLength * cos(angle - headAngle).toFloat(), end.y - headLength * sin(angle - headAngle).toFloat())
    val right = Offset(end.x - headLength * cos(angle + headAngle).toFloat(), end.y - headLength * sin(angle + headAngle).toFloat())
    drawLine(Rust, end, left, strokeWidth = 4f, cap = StrokeCap.Round)
    drawLine(Rust, end, right, strokeWidth = 4f, cap = StrokeCap.Round)
}
