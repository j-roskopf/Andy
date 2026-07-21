package app.andy.ui.performance

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AndroidDevice
import app.andy.model.PerformanceSample
import app.andy.model.PerformanceTab
import app.andy.service.AndyServices
import app.andy.ui.components.FilterPill
import app.andy.ui.components.MonoCell
import app.andy.ui.components.PaneDivider
import app.andy.ui.components.PanelCard
import app.andy.ui.components.TableHeader
import app.andy.ui.components.TableRow
import app.andy.ui.live.DeviceLivePanel
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Yellow
import app.andy.ui.tracing.TracingScreen
import kotlinx.coroutines.flow.collectLatest

@Composable
internal fun PerformanceScreen(
    services: AndyServices,
    serial: String?,
    device: AndroidDevice?,
    active: Boolean,
    selectedTab: PerformanceTab,
    onSelectedTabChange: (PerformanceTab) -> Unit,
    processesPaneWidth: Float,
    onProcessesPaneWidthChange: (Float) -> Unit,
    liveVisible: Boolean,
    livePaneWidth: Float,
    onLivePaneWidthChange: (Float) -> Unit,
    tracingPresetId: String,
    tracingDurationSeconds: Int,
    tracingBufferSizeMb: Int,
    tracingPresetsPaneWidth: Float,
    tracingLibraryPaneHeight: Float,
    onTracingPresetIdChange: (String) -> Unit,
    onTracingDurationSecondsChange: (Int) -> Unit,
    onTracingBufferSizeMbChange: (Int) -> Unit,
    onTracingPresetsPaneWidthChange: (Float) -> Unit,
    onTracingLibraryPaneHeightChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill("Metrics", selectedTab == PerformanceTab.Metrics, Rust) {
                onSelectedTabChange(PerformanceTab.Metrics)
            }
            FilterPill("Tracing", selectedTab == PerformanceTab.Tracing, Rust) {
                onSelectedTabChange(PerformanceTab.Tracing)
            }
        }
        Box(Modifier.fillMaxSize().weight(1f)) {
            // Compose only the active tab. Tracing must not realize a heavyweight editor
            // while Metrics is showing (screenshot tests / occlusion).
            when (selectedTab) {
                PerformanceTab.Metrics -> MetricsTabContent(
                    services = services,
                    serial = serial,
                    device = device,
                    active = active,
                    processesPaneWidth = processesPaneWidth,
                    onProcessesPaneWidthChange = onProcessesPaneWidthChange,
                    liveVisible = liveVisible,
                    livePaneWidth = livePaneWidth,
                    onLivePaneWidthChange = onLivePaneWidthChange,
                )
                PerformanceTab.Tracing -> TracingScreen(
                    services = services,
                    serial = serial,
                    device = device,
                    presetId = tracingPresetId,
                    durationSeconds = tracingDurationSeconds,
                    bufferSizeMb = tracingBufferSizeMb,
                    presetsPaneWidth = tracingPresetsPaneWidth,
                    libraryPaneHeight = tracingLibraryPaneHeight,
                    onPresetIdChange = onTracingPresetIdChange,
                    onDurationSecondsChange = onTracingDurationSecondsChange,
                    onBufferSizeMbChange = onTracingBufferSizeMbChange,
                    onPresetsPaneWidthChange = onTracingPresetsPaneWidthChange,
                    onLibraryPaneHeightChange = onTracingLibraryPaneHeightChange,
                )
            }
        }
    }
}

@Composable
private fun MetricsTabContent(
    services: AndyServices,
    serial: String?,
    device: AndroidDevice?,
    active: Boolean,
    processesPaneWidth: Float,
    onProcessesPaneWidthChange: (Float) -> Unit,
    liveVisible: Boolean,
    livePaneWidth: Float,
    onLivePaneWidthChange: (Float) -> Unit,
) {
    var samples by remember { mutableStateOf<List<PerformanceSample>>(emptyList()) }
    var localProcessesPaneWidth by remember(processesPaneWidth) { mutableStateOf(processesPaneWidth) }
    var localLivePaneWidth by remember(livePaneWidth) { mutableStateOf(livePaneWidth) }
    LaunchedEffect(serial, active) {
        if (!active) return@LaunchedEffect
        samples = emptyList()
        if (serial != null) services.metrics.stream(serial, null).collectLatest { samples = (samples + it).takeLast(60) }
    }
    val latest = samples.lastOrNull()
    val recentFrames = samples.flatMap { it.frameRenderTimes }.takeLast(60)
    val cpuSeries = samples.map { it.cpuPercent ?: 0f }
    val memorySeries = samples.map { it.memoryMb ?: 0f }
    val networkSeries = samples.map { (it.networkRxKbps ?: 0f) + (it.networkTxKbps ?: 0f) }
    val fpsSeries = samples.map { it.fps ?: 0f }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PerformanceChartCard(
                    title = "CPU",
                    valueText = latest?.cpuPercent?.let { "${it.toInt()}" } ?: "-",
                    unitText = "%",
                    caption = "4 cores · avg ${cpuSeries.takeIf { it.isNotEmpty() }?.average()?.toInt() ?: 0}%",
                    values = cpuSeries,
                    maxValue = 100f,
                    lineColor = Rust,
                    modifier = Modifier.weight(1f),
                )
                PerformanceChartCard(
                    title = "Memory",
                    valueText = latest?.memoryMb?.let { "${it.toInt()}" } ?: "-",
                    unitText = "MB",
                    caption = "peak ${memorySeries.maxOrNull()?.toInt() ?: 0} MB",
                    values = memorySeries,
                    maxValue = (memorySeries.maxOrNull() ?: 0f).coerceAtLeast(256f) * 1.15f,
                    lineColor = Cyan,
                    modifier = Modifier.weight(1f),
                )
                PerformanceChartCard(
                    title = "Network",
                    valueText = latest?.let { ((it.networkRxKbps ?: 0f) + (it.networkTxKbps ?: 0f)).toInt().toString() } ?: "-",
                    unitText = "KB/s",
                    caption = "down ${latest?.networkRxKbps?.toInt() ?: 0} · up ${latest?.networkTxKbps?.toInt() ?: 0} KB/s",
                    values = networkSeries,
                    maxValue = (networkSeries.maxOrNull() ?: 0f).coerceAtLeast(64f) * 1.15f,
                    lineColor = Green,
                    modifier = Modifier.weight(1f),
                )
                PerformanceChartCard(
                    title = "FPS",
                    valueText = latest?.fps?.toInt()?.toString() ?: "Idle",
                    unitText = if (latest?.fps != null) "fps" else "",
                    caption = recentFrames.takeIf { it.isNotEmpty() }?.let { frames -> "${frames.size} frames sampled · ${frames.count { it.millis <= 16.6f }} green" } ?: "No active rendering",
                    values = fpsSeries,
                    maxValue = (fpsSeries.maxOrNull() ?: 60f).coerceAtLeast(60f) * 1.1f,
                    lineColor = Yellow,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.width(localProcessesPaneWidth.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TableHeader(listOf("PID" to 80.dp, "CPU" to 70.dp, "MEM" to 90.dp, "PROCESS" to 1.dp))
                    LazyColumn {
                        items(latest?.processes.orEmpty()) { process ->
                            TableRow {
                                MonoCell(process.pid, 80.dp, TextSecondary)
                                MonoCell(process.cpuPercent?.let { "${app.andy.formatDecimal(it, 1)}%" } ?: "-", 70.dp, if ((process.cpuPercent ?: 0f) > 10f) Rust else TextPrimary)
                                MonoCell(process.memoryMb?.let { app.andy.formatDecimal(it, 1) } ?: "-", 90.dp, TextSecondary)
                                MonoCell(process.name, 1.dp, TextPrimary, Modifier.weight(1f))
                            }
                        }
                    }
                }
                PaneDivider(
                    onDrag = { dragX -> localProcessesPaneWidth = (localProcessesPaneWidth + dragX).coerceIn(360f, 1300f) },
                    onDragEnd = { onProcessesPaneWidthChange(localProcessesPaneWidth) },
                )
                PanelCard(Modifier.fillMaxSize().padding(start = 6.dp).weight(1f)) {
                    Text("Frame rendering", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text("Green <= 16.6 ms, red is slower than 60 fps.", color = TextSecondary, fontSize = 12.sp)
                    Canvas(Modifier.fillMaxWidth().height(190.dp)) {
                        val frames = recentFrames
                        val barWidth = if (frames.isEmpty()) size.width else size.width / frames.size
                        frames.forEachIndexed { index, frame ->
                            val height = (frame.millis.coerceIn(0f, 50f) / 50f) * size.height
                            drawRect(
                                color = if (frame.millis <= 16.6f) Green else Red,
                                topLeft = Offset(index * barWidth, size.height - height),
                                size = androidx.compose.ui.geometry.Size((barWidth - 1f).coerceAtLeast(1f), height),
                            )
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(recentFrames) { frame ->
                            Text("${frame.label}  ${app.andy.formatDecimal(frame.millis, 2)} ms", color = if (frame.millis <= 16.6f) Green else Red, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = liveVisible,
            modifier = Modifier.fillMaxHeight(),
            enter = expandHorizontally(animationSpec = tween(220)) + fadeIn(animationSpec = tween(220)),
            exit = shrinkHorizontally(animationSpec = tween(220)) + fadeOut(animationSpec = tween(160)),
        ) {
            Row(Modifier.fillMaxHeight()) {
                PaneDivider(
                    onDrag = { dragX -> localLivePaneWidth = (localLivePaneWidth - dragX).coerceIn(220f, 700f) },
                    onDragEnd = { onLivePaneWidthChange(localLivePaneWidth) },
                )
                DeviceLivePanel(
                    services = services,
                    serial = serial,
                    device = device,
                    modifier = Modifier.width(localLivePaneWidth.dp).fillMaxHeight(),
                    showChromeControls = false,
                )
            }
        }
    }
}

@Composable
private fun PerformanceChartCard(
    title: String,
    valueText: String,
    unitText: String,
    caption: String,
    values: List<Float>,
    maxValue: Float,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    PanelCard(modifier.height(190.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title.lowercase(), color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(valueText, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = MonoFont)
                if (unitText.isNotEmpty()) {
                    Text(" ${unitText.lowercase()}", color = TextSecondary, fontSize = 12.sp, fontFamily = MonoFont, modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            if (values.size < 2) return@Canvas
            val safeMax = maxValue.takeIf { it > 0f } ?: 1f
            val stepX = size.width / (values.size - 1)
            val points = values.mapIndexed { index, value ->
                Offset(index * stepX, size.height - (value.coerceIn(0f, safeMax) / safeMax) * size.height)
            }
            val linePath = androidx.compose.ui.graphics.Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            val fillPath = androidx.compose.ui.graphics.Path().apply {
                addPath(linePath)
                lineTo(points.last().x, size.height)
                lineTo(points.first().x, size.height)
                close()
            }
            drawPath(fillPath, brush = Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.32f), lineColor.copy(alpha = 0.02f))))
            drawPath(linePath, color = lineColor, style = Stroke(width = 2f))
        }
        Text(caption.lowercase(), color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
    }
}
