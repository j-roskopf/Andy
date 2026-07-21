package app.andy.ui.tracing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.formatDisplayDateTime
import app.andy.model.AndroidDevice
import app.andy.model.DeviceConnectionState
import app.andy.model.TraceConfigPresets
import app.andy.model.TracePhase
import app.andy.model.TraceRecording
import app.andy.model.TraceUserConfig
import app.andy.pickFiles
import app.andy.service.AndyServices
import app.andy.ui.components.Button
import app.andy.ui.components.HorizontalPaneDivider
import app.andy.ui.components.MonoCell
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PaneDivider
import app.andy.ui.components.TextField
import app.andy.ui.components.TableHeader
import app.andy.ui.components.TableRow
import app.andy.ui.components.TextField
import app.andy.ui.theme.Green
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Yellow
import kotlinx.coroutines.launch

@Composable
internal fun TracingScreen(
    services: AndyServices,
    serial: String?,
    device: AndroidDevice?,
    presetId: String,
    durationSeconds: Int,
    bufferSizeMb: Int,
    presetsPaneWidth: Float,
    libraryPaneHeight: Float,
    onPresetIdChange: (String) -> Unit,
    onDurationSecondsChange: (Int) -> Unit,
    onBufferSizeMbChange: (Int) -> Unit,
    onPresetsPaneWidthChange: (Float) -> Unit,
    onLibraryPaneHeightChange: (Float) -> Unit,
) {
    val status by services.tracing.status.collectAsState()
    val recordings by services.tracing.recordings.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedPresetId by remember(presetId) { mutableStateOf(presetId) }
    var durationText by remember(durationSeconds) { mutableStateOf(durationSeconds.toString()) }
    var bufferText by remember(bufferSizeMb) { mutableStateOf(bufferSizeMb.toString()) }
    var configText by remember { mutableStateOf("") }
    var baselineConfig by remember { mutableStateOf("") }
    var modified by remember { mutableStateOf(false) }
    var userConfigs by remember { mutableStateOf<List<TraceUserConfig>>(emptyList()) }
    var localPresetsPaneWidth by remember(presetsPaneWidth) { mutableStateOf(presetsPaneWidth) }
    var localLibraryHeight by remember(libraryPaneHeight) { mutableStateOf(libraryPaneHeight) }
    var offerOpenNow by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }

    fun currentDuration(): Int = durationText.toIntOrNull()?.coerceAtLeast(0) ?: 0
    fun currentBuffer(): Int = bufferText.toIntOrNull()?.coerceIn(1, 1024) ?: 64

    fun applyPreset(id: String, force: Boolean = false) {
        val preset = TraceConfigPresets.byId(id) ?: return
        if (!force && modified) return
        val materialized = TraceConfigPresets.materialize(preset.template, currentDuration(), currentBuffer())
        configText = materialized
        baselineConfig = materialized
        modified = false
        selectedPresetId = id
        onPresetIdChange(id)
    }

    LaunchedEffect(Unit) {
        services.tracing.refreshRecordings()
        userConfigs = services.tracing.listUserConfigs()
        if (configText.isBlank()) applyPreset(selectedPresetId, force = true)
    }

    LaunchedEffect(durationText, bufferText, selectedPresetId) {
        if (!modified && TraceConfigPresets.byId(selectedPresetId) != null) {
            applyPreset(selectedPresetId, force = true)
        }
    }

    LaunchedEffect(status.phase, status.lastTraceId) {
        if (status.phase == TracePhase.Done && status.lastTraceId != null) {
            offerOpenNow = true
        }
    }

    val recordingActive = status.phase == TracePhase.Recording || status.phase == TracePhase.Starting ||
        status.phase == TracePhase.Stopping || status.phase == TracePhase.Pulling
    val pullRetryAvailable = status.phase == TracePhase.Error &&
        status.message.orEmpty().contains("Retry pull", ignoreCase = true)
    val deviceOnline = device?.state == DeviceConnectionState.Online
    val canRecord = serial != null && deviceOnline && !recordingActive && !pullRetryAvailable

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TraceStatusChip(
                label = statusChipLabel(status.phase, status.durationMs, status.message),
                color = when (status.phase) {
                    TracePhase.Recording -> Green
                    TracePhase.Error -> Red
                    TracePhase.Pulling, TracePhase.Stopping, TracePhase.Starting -> Yellow
                    TracePhase.Done -> Rust
                    TracePhase.Idle -> TextSecondary
                },
            )
            Spacer(Modifier.weight(1f))
            Text("Duration s", color = TextSecondary, fontSize = 11.sp)
            TextField(
                value = durationText,
                onValueChange = {
                    durationText = it.filter { ch -> ch.isDigit() }.take(5)
                    durationText.toIntOrNull()?.let(onDurationSecondsChange)
                },
                modifier = Modifier.width(64.dp),
                singleLine = true,
                enabled = !recordingActive,
            )
            Text("Buffer MB", color = TextSecondary, fontSize = 11.sp)
            TextField(
                value = bufferText,
                onValueChange = {
                    bufferText = it.filter { ch -> ch.isDigit() }.take(4)
                    bufferText.toIntOrNull()?.let(onBufferSizeMbChange)
                },
                modifier = Modifier.width(64.dp),
                singleLine = true,
                enabled = !recordingActive,
            )
            if (recordingActive && status.phase == TracePhase.Recording) {
                Button(onClick = {
                    scope.launch {
                        val result = services.tracing.stop()
                        if (!result.isSuccess) actionMessage = result.stderr
                    }
                }) { Text("Stop") }
            } else {
                Button(
                    onClick = {
                        val target = serial ?: return@Button
                        scope.launch {
                            offerOpenNow = false
                            val result = services.tracing.start(
                                serial = target,
                                configTextProto = configText,
                                name = selectedPresetId,
                                presetId = selectedPresetId.takeIf { TraceConfigPresets.byId(it) != null },
                            )
                            if (!result.isSuccess) actionMessage = result.stderr
                        }
                    },
                    enabled = canRecord,
                ) { Text("Record") }
            }
        }

        if (offerOpenNow && status.lastTraceId != null) {
            val readyId = status.lastTraceId!!
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Trace ready.", color = TextPrimary, fontSize = 12.sp)
                Button(onClick = {
                    scope.launch {
                        val result = services.traceViewer.openExternally(readyId)
                        if (!result.isSuccess) actionMessage = result.stderr
                        offerOpenNow = false
                    }
                }) { Text("Open in browser") }
                OutlinedButton(onClick = { offerOpenNow = false }) { Text("Dismiss") }
            }
        }
        actionMessage?.let { msg ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(msg, color = Red, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (msg.contains("Retry pull", ignoreCase = true)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            val result = services.tracing.retryPull()
                            actionMessage = if (result.isSuccess) null else result.stderr
                        }
                    }) { Text("Retry pull") }
                }
                OutlinedButton(onClick = { actionMessage = null }) { Text("Dismiss") }
            }
        }
        status.message?.takeIf { status.phase == TracePhase.Error }?.let { msg ->
            if (actionMessage == null && msg.contains("Retry pull", ignoreCase = true)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, color = Red, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        scope.launch {
                            val result = services.tracing.retryPull()
                            if (!result.isSuccess) actionMessage = result.stderr
                        }
                    }) { Text("Retry pull") }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(
                Modifier.width(localPresetsPaneWidth.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Quick starts", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                for (row in TraceConfigPresets.all.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        for (preset in row) {
                            TracePresetCard(
                                preset = preset,
                                selected = selectedPresetId == preset.id && !modified,
                                onClick = { applyPreset(preset.id, force = true) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Text("User configs", color = TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                TraceImportConfigCard(onClick = {
                    scope.launch {
                        val path = pickFiles(allowMultiple = false).firstOrNull() ?: return@launch
                        val result = services.tracing.importConfig(path)
                        if (result.isSuccess) {
                            userConfigs = services.tracing.listUserConfigs()
                        } else if (result.stderr.isNotBlank()) {
                            actionMessage = result.stderr
                        }
                    }
                })
                for (config in userConfigs) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(config.name, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            scope.launch {
                                val content = services.tracing.loadUserConfig(config.id) ?: return@launch
                                configText = content
                                baselineConfig = content
                                modified = false
                                selectedPresetId = config.id
                                onPresetIdChange(config.id)
                            }
                        }) { Text("Load") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                services.tracing.deleteUserConfig(config.id)
                                userConfigs = services.tracing.listUserConfigs()
                            }
                        }) { Text("Delete") }
                    }
                }
            }
            PaneDivider(
                onDrag = { dx -> localPresetsPaneWidth = (localPresetsPaneWidth + dx).coerceIn(240f, 560f) },
                onDragEnd = { onPresetsPaneWidthChange(localPresetsPaneWidth) },
            )
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Config", color = TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (modified) TraceStatusChip("modified", Yellow)
                    OutlinedButton(
                        onClick = {
                            applyPreset(
                                selectedPresetId.takeIf { TraceConfigPresets.byId(it) != null } ?: "default",
                                force = true,
                            )
                        },
                        enabled = TraceConfigPresets.byId(selectedPresetId) != null,
                    ) { Text("Re-apply preset") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val name = selectedPresetId.ifBlank { "custom" }
                            val result = services.tracing.saveUserConfig(name, configText)
                            if (result.isSuccess) {
                                userConfigs = services.tracing.listUserConfigs()
                                modified = false
                                baselineConfig = configText
                            } else {
                                actionMessage = result.stderr
                            }
                        }
                    }) { Text("Save as user config") }
                }
                TextField(
                    value = configText,
                    onValueChange = { next ->
                        configText = next
                        modified = next != baselineConfig
                    },
                    modifier = Modifier.fillMaxSize().weight(1f),
                    singleLine = false,
                    minLines = 12,
                )
            }
        }

        HorizontalPaneDivider(
            onDrag = { dy -> localLibraryHeight = (localLibraryHeight - dy).coerceIn(140f, 520f) },
            onDragEnd = { onLibraryPaneHeightChange(localLibraryHeight) },
        )
        TraceLibraryPane(
            recordings = recordings,
            height = localLibraryHeight,
            onOpenBrowser = { id ->
                scope.launch {
                    val result = services.traceViewer.openExternally(id)
                    if (!result.isSuccess) actionMessage = result.stderr
                }
            },
            onReveal = { id -> scope.launch { services.tracing.revealRecording(id) } },
            onDelete = { id -> scope.launch { services.tracing.deleteRecording(id) } },
        )
    }
}

@Composable
private fun TraceLibraryPane(
    recordings: List<TraceRecording>,
    height: Float,
    onOpenBrowser: (String) -> Unit,
    onReveal: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().height(height.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Trace library", color = TextPrimary, fontWeight = FontWeight.SemiBold)
        TableHeader(listOf("Name" to 1.dp, "Device" to 140.dp, "When" to 210.dp, "Size" to 80.dp, "" to 280.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(recordings, key = { it.id }) { recording ->
                TableRow {
                    MonoCell(recording.name, 1.dp, TextPrimary, Modifier.weight(1f))
                    MonoCell(recording.deviceLabel ?: recording.serial, 140.dp, TextSecondary)
                    MonoCell(formatWhen(recording.recordedAtMillis), 210.dp, TextSecondary)
                    MonoCell(formatSize(recording.sizeBytes), 80.dp, TextSecondary)
                    Row(Modifier.width(280.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = { onOpenBrowser(recording.id) }) { Text("Browser", fontSize = 11.sp) }
                        OutlinedButton(onClick = { onReveal(recording.id) }) { Text("Reveal", fontSize = 11.sp) }
                        OutlinedButton(onClick = { onDelete(recording.id) }) { Text("Delete", fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}

private fun statusChipLabel(phase: TracePhase, durationMs: Long?, message: String?): String = when (phase) {
    TracePhase.Idle -> "idle"
    TracePhase.Starting -> "starting"
    TracePhase.Recording -> {
        val totalSeconds = (durationMs ?: 0L) / 1000
        val minutes = (totalSeconds / 60).toString().padStart(2, '0')
        val seconds = (totalSeconds % 60).toString().padStart(2, '0')
        "RECORDING $minutes:$seconds"
    }
    TracePhase.Stopping -> "stopping"
    TracePhase.Pulling -> "pulling"
    TracePhase.Done -> "done"
    TracePhase.Error -> message?.take(48) ?: "error"
}

private fun formatWhen(millis: Long): String = formatDisplayDateTime(millis)

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}
