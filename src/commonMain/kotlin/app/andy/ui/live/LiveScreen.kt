package app.andy.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AndroidDevice
import app.andy.model.BugCaptureDraft
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.service.AndyServices
import app.andy.service.CommandResult
import app.andy.service.MirrorInput
import app.andy.service.MirrorVideoConfig
import app.andy.ui.components.Button
import app.andy.ui.components.FilterPill
import app.andy.ui.components.HorizontalPaneDivider
import app.andy.ui.components.LabeledField
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PaneDivider
import app.andy.ui.components.PanelCard
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.logcat.LogcatPanel
import app.andy.ui.logcat.LogcatState
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Green
import app.andy.ui.theme.Panel
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Yellow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
internal fun LiveScreen(
    services: AndyServices,
    serial: String?,
    device: AndroidDevice?,
    devicePaneWidth: Float,
    controlsPaneHeight: Float,
    onStopEmulator: (AndroidDevice) -> Unit,
    stoppingEmulatorSerial: String?,
    stopStatus: String,
    onDevicePaneWidthChange: (Float) -> Unit,
    onControlsPaneHeightChange: (Float) -> Unit,
    onBugSaved: () -> Unit,
    logcatState: LogcatState,
    onPopOutMirror: () -> Unit,
    selectedPackage: String?,
    onSelectedPackageChange: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var mirrorStatus by remember { mutableStateOf("Disconnected") }
    var connectResult by remember { mutableStateOf("") }
    var maxSize by remember { mutableStateOf("720") }
    var bitRateMbps by remember { mutableStateOf("4") }
    var maxFps by remember { mutableStateOf("60") }
    var bugDialogVisible by remember { mutableStateOf(false) }
    var bugSaveStatus by remember { mutableStateOf("") }
    var liveActionStatus by remember { mutableStateOf("") }
    var clipDialogVisible by remember { mutableStateOf(false) }
    var localDevicePaneWidth by remember(devicePaneWidth) { mutableStateOf(devicePaneWidth.coerceAtLeast(680f)) }
    var localControlsPaneHeight by remember(controlsPaneHeight) { mutableStateOf(controlsPaneHeight.coerceIn(170f, 360f)) }
    val sendMirrorInput = rememberMirrorInputSender(services, serial)
    fun sendHardware(input: MirrorInput) {
        sendMirrorInput(input)
    }
    fun runLiveAction(label: String, block: suspend () -> CommandResult) {
        if (serial == null) {
            liveActionStatus = "Select an online device"
            return
        }
        scope.launch {
            val result = block()
            liveActionStatus = "$label: " + if (result.isSuccess) result.stdout.ifBlank { "ok" } else result.stderr.ifBlank { result.stdout }
        }
    }
    fun applyPreset(size: String, mbps: String, fps: String = "60") {
        maxSize = size
        bitRateMbps = mbps
        maxFps = fps
    }
    fun mirrorConfig(): MirrorVideoConfig = MirrorVideoConfig(
        maxSize = maxSize.toIntOrNull()?.coerceIn(240, 2160) ?: 720,
        bitRate = ((bitRateMbps.toFloatOrNull()?.coerceIn(0.5f, 80f) ?: 4f) * 1_000_000).toInt(),
        maxFps = maxFps.toIntOrNull()?.coerceIn(15, 120) ?: 60,
    )
    LaunchedEffect(Unit) {
        services.mirror.status.collectLatest { mirrorStatus = it }
    }
    LaunchedEffect(serial) {
        if (serial != null && device?.state == DeviceConnectionState.Online) {
            val result = services.mirror.connect(serial, mirrorConfig())
            connectResult = if (result.isSuccess) result.stdout else result.stderr
            services.bugs.startCapture(serial, device)
        }
    }
    DisposableEffect(serial) {
        onDispose {
            scope.launch { services.bugs.stopCapture() }
        }
    }
    Row(Modifier.fillMaxSize()) {
        MirrorFrameContent(services.mirror, serial) { frameFlow, frame ->
            val visibleFrame = frame.takeUnless { bugDialogVisible || clipDialogVisible }
            val visibleFrameFlow = frameFlow.takeUnless { bugDialogVisible || clipDialogVisible }
            LiveDevicePane(
                serial = serial,
                device = device,
                frame = visibleFrame,
                frameFlow = visibleFrameFlow,
                mirrorStatus = mirrorStatus,
                connectResult = connectResult,
                modifier = Modifier.width(localDevicePaneWidth.dp).fillMaxHeight().padding(end = 6.dp),
                onPower = { sendHardware(MirrorInput.Power) },
                onVolumeUp = { sendHardware(MirrorInput.Key(24)) },
                onVolumeDown = { sendHardware(MirrorInput.Key(25)) },
                onRotate = { runLiveAction("Rotate") { services.devices.shell(serial!!, listOf("settings", "put", "system", "user_rotation", "1")) } },
                onCaptureScreenshot = { runLiveAction("Screenshot") { services.artifacts.saveScreenshot(serial!!, "andy-${serial}.png") } },
                onBugReport = { bugDialogVisible = true },
                onClipText = { clipDialogVisible = true },
                onPopOut = onPopOutMirror,
                onInput = sendMirrorInput,
                onConnect = {
                    if (serial != null) {
                        scope.launch {
                            val result = services.mirror.connect(serial, mirrorConfig())
                            connectResult = if (result.isSuccess) result.stdout else result.stderr
                        }
                    }
                },
            )
        }
        PaneDivider(
            onDrag = { dragX -> localDevicePaneWidth = (localDevicePaneWidth + dragX).coerceIn(560f, 1800f) },
            onDragEnd = { onDevicePaneWidthChange(localDevicePaneWidth) },
        )
        Column(Modifier.fillMaxSize().padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PanelCard(Modifier.fillMaxWidth().height(localControlsPaneHeight.dp)) {
                Text("Controls", color = TextPrimary, fontWeight = FontWeight.Bold)
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill("SD", maxSize == "480", Cyan) { applyPreset("480", "2") }
                    FilterPill("HD", maxSize == "720", Green) { applyPreset("720", "4") }
                    FilterPill("FHD", maxSize == "1080", Yellow) { applyPreset("1080", "8") }
                    FilterPill("Max", maxSize == "1440", Rust) { applyPreset("1440", "16") }
                }
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    LabeledField("Max px", maxSize, { maxSize = it.filter(Char::isDigit) }, Modifier.width(96.dp))
                    LabeledField("Mbps", bitRateMbps, { bitRateMbps = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.width(88.dp))
                    LabeledField("FPS", maxFps, { maxFps = it.filter(Char::isDigit) }, Modifier.width(78.dp))
                    Box(Modifier.align(Alignment.Bottom).padding(bottom = 2.dp)) {
                        Button(onClick = {
                            if (serial != null) scope.launch {
                                val result = services.mirror.connect(serial, mirrorConfig())
                                connectResult = if (result.isSuccess) result.stdout else result.stderr
                            }
                        }) { Text("Apply") }
                    }
                }
                Text("Bug capture", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactHardwareButton("Save bug", serial) { bugDialogVisible = true }
                    if (liveActionStatus.isNotBlank()) {
                        Text(liveActionStatus, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { device?.let(onStopEmulator) },
                        enabled = device?.kind == DeviceKind.Emulator && serial != null && stoppingEmulatorSerial != serial,
                    ) {
                        Text(if (stoppingEmulatorSerial == serial) "Stopping" else "Stop emulator")
                    }
                    if (stopStatus.isNotBlank()) {
                        Text(stopStatus, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (bugSaveStatus.isNotBlank()) {
                    Text(bugSaveStatus, color = Rust, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalPaneDivider(
                onDrag = { dragY -> localControlsPaneHeight = (localControlsPaneHeight + dragY).coerceIn(170f, 520f) },
                onDragEnd = { onControlsPaneHeightChange(localControlsPaneHeight) },
            )
            LogcatPanel(
                logcat = services.logcat,
                appsService = services.apps,
                serial = serial,
                selectedPackage = selectedPackage,
                onSelectedPackageChange = onSelectedPackageChange,
                modifier = Modifier.fillMaxWidth().weight(0.55f),
                compact = true,
                state = logcatState
            )
        }
    }
    if (clipDialogVisible) {
        ClipTextDialog(
            onDismiss = { clipDialogVisible = false },
            onSend = { text ->
                sendHardware(MirrorInput.Text(text))
                liveActionStatus = "Clip text: sent"
                clipDialogVisible = false
            },
        )
    }
    if (bugDialogVisible) {
        BugCaptureDialog(
            onDismiss = { bugDialogVisible = false },
            onSave = { draft ->
                scope.launch {
                    runCatching { services.bugs.saveBug(draft, device) }
                        .onSuccess { report ->
                            bugSaveStatus = "Saved ${report.title}"
                            bugDialogVisible = false
                            onBugSaved()
                        }
                        .onFailure { error ->
                            bugSaveStatus = error.message ?: "Failed to save bug"
                        }
                }
            },
        )
    }
}

@Composable
internal fun BugCaptureDialog(onDismiss: () -> Unit, onSave: (BugCaptureDraft) -> Unit) {
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Capture bug", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LabeledField("Title", title, { title = it }, Modifier.fillMaxWidth(), placeholder = "Crash opening playlist")
                LabeledField("Notes / repro steps", notes, { notes = it }, Modifier.fillMaxWidth(), singleLine = false, minHeight = 120.dp, placeholder = "What happened? What should have happened?")
                Text("Saves the last 30 seconds of Andy actions, live video, and logcat.", color = TextSecondary, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(BugCaptureDraft(title, notes)) },
                enabled = title.trim().isNotBlank(),
                colors = primaryButtonColors(),
            ) {
                Text("Save bug")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
internal fun NavIconBack(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * 0.85f, size.height * 0.1f)
            lineTo(size.width * 0.15f, size.height * 0.5f)
            lineTo(size.width * 0.85f, size.height * 0.9f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
internal fun NavIconHome(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        drawCircle(
            color = color,
            radius = size.minDimension / 2f * 0.85f
        )
    }
}

@Composable
internal fun NavIconRecents(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        val side = size.minDimension * 0.75f
        val offset = (size.minDimension - side) / 2f
        drawRoundRect(
            color = color,
            topLeft = Offset(offset, offset),
            size = Size(side, side),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        )
    }
}
