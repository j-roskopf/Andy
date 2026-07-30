package app.andy.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.MirrorVideoSurface
import app.andy.domain.estimateExportedClipBytes
import app.andy.domain.uniformFrameTimestamps
import app.andy.domain.validateRecordingExportRequest
import app.andy.model.BugReport
import app.andy.model.ClipFormat
import app.andy.model.ExportedClip
import app.andy.model.RecordingExportRequest
import app.andy.rememberCopyText
import app.andy.service.BugService
import app.andy.service.MirrorFrame
import app.andy.service.RecordingExportService
import app.andy.ui.components.Button
import app.andy.ui.components.FilterPill
import app.andy.ui.components.LabeledField
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.shell.SuppressHeavyweightSurfacesWhileOpen
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Green
import app.andy.ui.theme.Panel
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Post-capture sheet shown right after Live's record button saves a clip (§E.2).
 */
@Composable
internal fun RecordingExportSheet(
    report: BugReport,
    bugs: BugService,
    recordingExport: RecordingExportService,
    onDismiss: () -> Unit,
    onRenamed: (String) -> Unit = {},
) {
    SuppressHeavyweightSurfacesWhileOpen()
    val scope = rememberCoroutineScope()
    val copyText = rememberCopyText()

    var title by remember(report.id) { mutableStateOf(report.title) }
    var titleStatus by remember(report.id) { mutableStateOf("") }
    var frameCount by remember(report.id) { mutableStateOf(0) }
    LaunchedEffect(report.id) { frameCount = bugs.bugVideoFrameCount(report.id) }

    val availableStart = report.videoStartedAtMillis ?: report.windowStartedAtMillis
    val availableEnd = report.videoEndedAtMillis ?: report.windowEndedAtMillis
    val timestamps = remember(report.id, frameCount) {
        report.videoFrameTimestampsMillis.ifEmpty { uniformFrameTimestamps(frameCount, availableStart, availableEnd) }
    }
    var previewFrame by remember(report.id) { mutableStateOf<MirrorFrame?>(null) }
    LaunchedEffect(report.id, frameCount) {
        if (frameCount > 0) {
            previewFrame = bugs.loadBugVideoFrame(report.id, 0)
        }
    }

    var format by remember(report.id) { mutableStateOf(ClipFormat.Gif) }
    var scale by remember(report.id) { mutableStateOf(480) }
    var fps by remember(report.id) { mutableStateOf(12) }
    var loop by remember(report.id) { mutableStateOf(true) }
    var exporting by remember(report.id) { mutableStateOf(false) }
    var exportResult by remember(report.id) { mutableStateOf<ExportedClip?>(null) }
    var exportError by remember(report.id) { mutableStateOf<String?>(null) }

    val request = RecordingExportRequest(
        id = report.id,
        startMillis = availableStart,
        endMillis = availableEnd,
        format = format,
        scale = scale,
        fps = fps,
        loop = loop,
    )
    val validationErrors = validateRecordingExportRequest(request, availableStart, availableEnd)
    val sourceSize = parseResolution(report.resolution)
    val estimatedBytes = estimateExportedClipBytes(
        request,
        sourceWidthPx = previewFrame?.width ?: sourceSize?.first ?: 0,
        sourceHeightPx = previewFrame?.height ?: sourceSize?.second ?: 0,
    )

    fun runExport() {
        exportError = null
        exportResult = null
        exporting = true
        scope.launch {
            val directory = bugs.bugDirectoryPath(report.id)
            if (directory == null) {
                exportError = "Export is not supported on this platform"
                exporting = false
                return@launch
            }
            val localPath = when (format) {
                ClipFormat.Mp4 -> "$directory/export.mp4"
                ClipFormat.Gif -> "$directory/export.gif"
                ClipFormat.WebP -> "$directory/export.webp"
                ClipFormat.PngSequence -> "$directory/export-frames"
            }
            recordingExport.export(request, localPath).fold(
                onSuccess = { clip -> exportResult = clip },
                onFailure = { error -> exportError = error.message ?: "Export failed" },
            )
            exporting = false
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .background(Panel, RoundedCornerShape(AndyRadius.Row))
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Recording saved", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss) { Text("Done") }
            }

            LabeledField(
                label = "TITLE",
                value = title,
                onValueChange = { title = it },
                placeholder = "Screen recording",
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val result = bugs.renameBug(report.id, title)
                        titleStatus = if (result.isSuccess) {
                            onRenamed(title)
                            "Saved"
                        } else {
                            result.stderr.ifBlank { "Rename failed" }
                        }
                    }
                }) { Text("Rename") }
            }
            if (titleStatus.isNotBlank()) {
                Text(titleStatus, color = if (titleStatus == "Saved") Green else Rust, fontSize = 11.sp)
            }

            Box(
                Modifier.fillMaxWidth().height(220.dp)
                    .background(Color.Black, RoundedCornerShape(AndyRadius.Control)),
                contentAlignment = Alignment.Center,
            ) {
                val frame = previewFrame
                if (frame != null) {
                    MirrorVideoSurface(frame = frame, modifier = Modifier.fillMaxSize(), passThroughInput = false)
                } else if (frameCount > 0) {
                    CircularProgressIndicator(Modifier.width(24.dp), strokeWidth = 2.dp, color = Rust)
                } else {
                    Text("No video frames captured", color = TextSecondary, fontSize = 12.sp)
                }
            }

            if (frameCount > 0 && timestamps.isNotEmpty()) {
                val durationMillis = (availableEnd - availableStart).coerceAtLeast(0L)
                Text(
                    "Duration · ${formatSeconds(durationMillis)} · $frameCount frames",
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("FORMAT", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ClipFormat.entries.forEach { candidate ->
                        FilterPill(candidate.label(), format == candidate, Rust) { format = candidate }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("WIDTH", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(360, 480, 720).forEach { candidate ->
                            FilterPill("${candidate}px", scale == candidate, Rust) { scale = candidate }
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("FRAME RATE", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(10, 12, 15, 24).forEach { candidate ->
                            FilterPill("${candidate}fps", fps == candidate, Rust) { fps = candidate }
                        }
                    }
                }
            }

            if (format == ClipFormat.Gif || format == ClipFormat.WebP) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = loop, onCheckedChange = { loop = it }, colors = CheckboxDefaults.colors(checkedColor = Rust))
                    Text("Loop forever", color = TextPrimary, fontSize = 12.sp)
                }
            }

            Text(
                "Estimated size: ${formatExportBytes(estimatedBytes)}",
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            validationErrors.forEach { error ->
                Text(error, color = Rust, fontSize = 11.sp)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = ::runExport,
                    enabled = !exporting && validationErrors.isEmpty() && frameCount > 0,
                    colors = primaryButtonColors(),
                    shape = RoundedCornerShape(10.dp),
                ) { Text(if (exporting) "Exporting…" else "Export") }
                if (exporting) {
                    CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp, color = Rust)
                }
            }

            exportError?.let { error ->
                Text(error, color = Rust, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            exportResult?.let { clip ->
                Column(
                    Modifier.fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.28f), RoundedCornerShape(AndyRadius.Control))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Exported ${formatExportBytes(clip.sizeBytes)} · ${clip.frameCount} frames · ${clip.widthPx}×${clip.heightPx}",
                        color = Green,
                        fontSize = 12.sp,
                    )
                    Text(clip.localPath, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { copyText(clip.localPath) }) { Text("Copy path") }
                        OutlinedButton(onClick = { scope.launch { bugs.revealBug(report.id) } }) { Text("Reveal") }
                    }
                }
            }
        }
    }
}

private fun ClipFormat.label(): String = when (this) {
    ClipFormat.Mp4 -> "MP4"
    ClipFormat.Gif -> "GIF"
    ClipFormat.WebP -> "WebP"
    ClipFormat.PngSequence -> "PNG sequence"
}

private fun parseResolution(resolution: String?): Pair<Int, Int>? {
    val parts = resolution?.lowercase()?.split("x")?.takeIf { it.size == 2 } ?: return null
    val width = parts[0].trim().toIntOrNull() ?: return null
    val height = parts[1].trim().toIntOrNull() ?: return null
    return width to height
}

private fun formatSeconds(millis: Long): String {
    val seconds = millis.coerceAtLeast(0L) / 1000.0
    return "${app.andy.formatDecimal(seconds, 1)}s"
}

private fun formatExportBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${app.andy.formatDecimal(kb, 1)} KB"
    return "${app.andy.formatDecimal(kb / 1024.0, 1)} MB"
}
