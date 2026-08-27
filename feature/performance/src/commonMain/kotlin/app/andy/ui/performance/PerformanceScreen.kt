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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.formatDecimal
import app.andy.formatDisplayDateTime
import app.andy.model.AndroidDevice
import app.andy.model.BatteryStatsSummary
import app.andy.model.HeapDumpInfo
import app.andy.model.MeminfoBreakdown
import app.andy.model.PerformanceSample
import app.andy.model.PerformanceTab
import app.andy.rememberCopyText
import app.andy.service.AndyServices
import app.andy.ui.components.Button
import app.andy.ui.components.TabBar
import app.andy.ui.components.MonoCell
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PackageSelector
import app.andy.ui.components.PaneDivider
import app.andy.ui.components.PanelCard
import app.andy.ui.components.TableHeader
import app.andy.ui.components.TableRow
import app.andy.ui.components.TextField
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
import kotlinx.coroutines.launch

@Composable
fun PerformanceScreen(
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
        TabBar(
            tabs = listOf("Metrics", "Tracing", "Memory"),
            selectedIndex = PerformanceTab.entries.indexOf(selectedTab).coerceAtLeast(0),
            onSelect = { onSelectedTabChange(PerformanceTab.entries[it]) },
        )
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
                PerformanceTab.Memory -> MemoryTabContent(
                    services = services,
                    serial = serial,
                    active = active,
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

/**
 * Memory & battery diagnostics (§B.3/B.4): `dumpsys meminfo` breakdown, a heap-dump capture
 * library shaped like [TracingScreen]'s trace library, and a `dumpsys batterystats` summary.
 */
@Composable
private fun MemoryTabContent(
    services: AndyServices,
    serial: String?,
    active: Boolean,
) {
    val scope = rememberCoroutineScope()
    val copyText = rememberCopyText()
    var packageName by remember(serial) { mutableStateOf("") }
    var breakdown by remember { mutableStateOf<MeminfoBreakdown?>(null) }
    var breakdownError by remember { mutableStateOf<String?>(null) }
    var heapDumps by remember { mutableStateOf<List<HeapDumpInfo>>(emptyList()) }
    var capturingHeapDump by remember { mutableStateOf(false) }
    var heapDumpMessage by remember { mutableStateOf<String?>(null) }
    var batteryStats by remember { mutableStateOf(BatteryStatsSummary()) }
    var loadingBattery by remember { mutableStateOf(false) }

    suspend fun refreshMeminfo() {
        val target = serial
        if (target == null || packageName.isBlank()) return
        val result = services.metrics.meminfoBreakdown(target, packageName)
        breakdown = result
        breakdownError = if (result == null) "No meminfo data for $packageName (is it running?)" else null
    }

    suspend fun refreshHeapDumps() {
        heapDumps = services.heapDump.listCaptures()
    }

    suspend fun refreshBattery() {
        val target = serial ?: return
        loadingBattery = true
        batteryStats = services.metrics.batteryStatsSummary(target, packageName.takeIf { it.isNotBlank() })
        loadingBattery = false
    }

    LaunchedEffect(serial, active) {
        if (!active || serial == null) return@LaunchedEffect
        if (packageName.isBlank()) packageName = services.apps.focusedPackage(serial).orEmpty()
        refreshHeapDumps()
    }

    LaunchedEffect(serial, packageName, active) {
        if (active) refreshMeminfo()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Package", color = TextSecondary, fontSize = 11.sp)
            PackageSelector(
                appsService = services.apps,
                serial = serial,
                selectedPackage = packageName.takeIf { it.isNotBlank() },
                onSelectedPackageChange = { selected -> packageName = selected.orEmpty() },
                modifier = Modifier.width(320.dp),
                allowAll = false,
                placeholder = "Select package",
                buttonPrefix = "",
                autoSelectForeground = true,
            )
            OutlinedButton(onClick = { scope.launch { refreshMeminfo() } }, enabled = serial != null && packageName.isNotBlank()) { Text("Refresh") }
        }

        PanelCard(Modifier.fillMaxWidth()) {
            Text("Memory breakdown \u00b7 dumpsys meminfo", color = TextPrimary, fontWeight = FontWeight.SemiBold)
            val info = breakdown
            when {
                breakdownError != null -> Text(breakdownError.orEmpty(), color = TextSecondary, fontSize = 12.sp)
                info == null -> Text("No data yet.", color = TextSecondary, fontSize = 12.sp)
                else -> {
                    MeminfoRow("Java heap", info.javaHeapMb)
                    MeminfoRow("Native heap", info.nativeHeapMb)
                    MeminfoRow("Code", info.codeMb)
                    MeminfoRow("Stack", info.stackMb)
                    MeminfoRow("Graphics", info.graphicsMb)
                    MeminfoRow("Private other", info.privateOtherMb)
                    MeminfoRow("System", info.systemMb)
                    MeminfoRow("Total PSS", info.totalPssMb, emphasize = true)
                }
            }
        }

        PanelCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Heap dumps", color = TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        val target = serial ?: return@Button
                        if (packageName.isBlank()) return@Button
                        capturingHeapDump = true
                        heapDumpMessage = null
                        scope.launch {
                            val result = services.heapDump.capture(target, packageName, "")
                            capturingHeapDump = false
                            result.fold(
                                onSuccess = { refreshHeapDumps() },
                                onFailure = { heapDumpMessage = it.message ?: "Heap dump failed" },
                            )
                        }
                    },
                    enabled = serial != null && packageName.isNotBlank() && !capturingHeapDump,
                ) { Text(if (capturingHeapDump) "Capturing\u2026" else "Capture heap dump") }
            }
            heapDumpMessage?.let { Text(it, color = Red, fontSize = 12.sp) }
            if (heapDumps.isEmpty()) {
                Text("No heap dumps captured yet.", color = TextSecondary, fontSize = 12.sp)
            } else {
                TableHeader(listOf("Package" to 1.dp, "Device" to 120.dp, "When" to 190.dp, "Size" to 80.dp, "" to 170.dp))
                heapDumps.forEach { dump ->
                    TableRow {
                        MonoCell(dump.packageName, 1.dp, TextPrimary, Modifier.weight(1f))
                        MonoCell(dump.deviceLabel ?: dump.serial, 120.dp, TextSecondary)
                        MonoCell(formatDisplayDateTime(dump.capturedAtMillis), 190.dp, TextSecondary)
                        MonoCell(formatHeapSize(dump.sizeBytes), 80.dp, TextSecondary)
                        Row(Modifier.width(170.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(onClick = { scope.launch { services.heapDump.revealCapture(dump.id) } }) { Text("Reveal", fontSize = 11.sp) }
                            OutlinedButton(onClick = { scope.launch { services.heapDump.deleteCapture(dump.id); refreshHeapDumps() } }) { Text("Delete", fontSize = 11.sp) }
                        }
                    }
                }
            }
        }

        PanelCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Battery stats \u00b7 dumpsys batterystats", color = TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { scope.launch { refreshBattery() } }, enabled = serial != null) {
                    Text(if (loadingBattery) "Loading\u2026" else "Refresh")
                }
                OutlinedButton(onClick = { copyText(batteryStats.raw) }, enabled = batteryStats.raw.isNotBlank()) { Text("Copy raw") }
            }
            if (batteryStats.wakelocks.isEmpty() && batteryStats.alarms.isEmpty() && batteryStats.jobs.isEmpty()) {
                Text(if (loadingBattery) "Loading\u2026" else "No data yet. Tap Refresh.", color = TextSecondary, fontSize = 12.sp)
            } else {
                if (batteryStats.wakelocks.isNotEmpty()) {
                    Text("Wakelocks", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    TableHeader(listOf("Name" to 1.dp, "Held" to 100.dp, "Count" to 70.dp))
                    batteryStats.wakelocks.forEach { wakelock ->
                        TableRow {
                            MonoCell(wakelock.name, 1.dp, TextPrimary, Modifier.weight(1f))
                            MonoCell(formatDurationMillis(wakelock.heldMillis), 100.dp, TextSecondary)
                            MonoCell(wakelock.count.toString(), 70.dp, TextSecondary)
                        }
                    }
                }
                if (batteryStats.alarms.isNotEmpty()) {
                    Text("Alarms", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    TableHeader(listOf("Name" to 1.dp, "Count" to 70.dp))
                    batteryStats.alarms.forEach { alarm ->
                        TableRow {
                            MonoCell(alarm.name, 1.dp, TextPrimary, Modifier.weight(1f))
                            MonoCell(alarm.count.toString(), 70.dp, TextSecondary)
                        }
                    }
                }
                if (batteryStats.jobs.isNotEmpty()) {
                    Text("Jobs", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    TableHeader(listOf("Name" to 1.dp, "Duration" to 100.dp, "Count" to 70.dp))
                    batteryStats.jobs.forEach { job ->
                        TableRow {
                            MonoCell(job.name, 1.dp, TextPrimary, Modifier.weight(1f))
                            MonoCell(formatDurationMillis(job.durationMillis), 100.dp, TextSecondary)
                            MonoCell(job.count.toString(), 70.dp, TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MeminfoRow(label: String, valueMb: Float?, emphasize: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Text(
            valueMb?.let { "${formatDecimal(it, 1)} MB" } ?: "-",
            color = if (emphasize) TextPrimary else TextSecondary,
            fontSize = 12.sp,
            fontFamily = MonoFont,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

private fun formatHeapSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

private fun formatDurationMillis(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
