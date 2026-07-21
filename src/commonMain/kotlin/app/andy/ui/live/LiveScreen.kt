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
import app.andy.model.ActionProject
import app.andy.model.BugCaptureDraft
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.RunningAction
import app.andy.currentTimeMillis
import app.andy.onExternalFileDrop
import app.andy.service.AndyServices
import app.andy.service.CommandResult
import app.andy.service.MirrorInput
import app.andy.service.MirrorRendererMode
import app.andy.service.MirrorSession
import app.andy.service.MirrorVideoConfig
import app.andy.transfer.DeviceTransferCoordinator
import app.andy.transfer.LocalDropKind
import app.andy.transfer.classifyLocalPaths
import app.andy.ui.actions.DockPlacement
import app.andy.ui.actions.TerminalDockDrawer
import app.andy.ui.actions.TerminalDockToggleRow
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun transferStatusColor(status: String): Color = when {
    status.startsWith("App installed") ||
        status.startsWith("App replaced") ||
        status.startsWith("Installed ") ||
        status.startsWith("Replaced ") -> Green
    status.contains("failed", ignoreCase = true) ||
        status.contains("rejected", ignoreCase = true) ||
        status.contains("not allowed", ignoreCase = true) -> Rust
    status == "Cancelled" || status.startsWith("Wait for") -> Yellow
    else -> TextSecondary
}

internal fun mirrorVideoConfig(
    maxSize: String,
    bitRateMbps: String,
    maxFps: String,
    rendererMode: MirrorRendererMode = MirrorRendererMode.Auto,
): MirrorVideoConfig {
    val parsedMaxSize = maxSize.toIntOrNull()
    return MirrorVideoConfig(
        maxSize = when (parsedMaxSize) {
            0 -> 0
            null -> 720
            else -> parsedMaxSize.coerceIn(240, 4_320)
        },
        bitRate = ((bitRateMbps.toFloatOrNull()?.coerceIn(0.5f, 80f) ?: 4f) * 1_000_000).toInt(),
        maxFps = maxFps.toIntOrNull()?.coerceIn(15, 120) ?: 60,
        rendererMode = rendererMode,
    )
}

internal fun MirrorSession.liveTelemetry(): String = buildString {
    append(backend.decoder)
    append(" / ")
    append(backend.renderer)
    if (backend.isHardwareBacked) append(" · GPU accelerated") else append(" · inline CPU")
    if (stats.displayedFps > 0f) append(" · ${stats.displayedFps.toInt()} fps")
    if (stats.droppedFrames > 0) append(" · ${stats.droppedFrames} dropped")
    stats.p95InputToPresentMillis?.let { append(" · ${app.andy.formatDecimal(it, 1)} ms P95") }
    backend.fallbackReason?.let { append(" · $it") }
}

private sealed interface LiveRecordingState {
    data object Idle : LiveRecordingState
    data class Countdown(val seconds: Int) : LiveRecordingState
    data object Recording : LiveRecordingState
    data object Saving : LiveRecordingState
}

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
    onRecordingSaved: () -> Unit,
    logcatState: LogcatState,
    onPopOutMirror: () -> Unit,
    selectedPackage: String?,
    onSelectedPackageChange: (String?) -> Unit,
    transfer: DeviceTransferCoordinator,
    projects: List<ActionProject> = emptyList(),
    running: List<RunningAction> = emptyList(),
    activeRunId: String? = null,
    terminalRunId: String? = null,
    onActiveRunIdChange: (String?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var mirrorStatus by remember { mutableStateOf("Disconnected") }
    var connectResult by remember { mutableStateOf("") }
    val isWeb = services.capabilities.platform == app.andy.service.AndyPlatform.Web
    val acceleratedMirror = services.capabilities.acceleratedMirror
    val controlsPaneMinHeight = if (acceleratedMirror) 320f else 280f
    val preferred = LiveMirrorSettings.config.value
    var maxSize by remember {
        mutableStateOf(if (preferred.maxSize == 0) "0" else preferred.maxSize.toString())
    }
    var bitRateMbps by remember {
        mutableStateOf(
            preferred.bitRate.takeIf { it > 0 }?.let { mbps ->
                val value = mbps / 1_000_000f
                if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
            } ?: if (isWeb) "12" else "8",
        )
    }
    var maxFps by remember { mutableStateOf(preferred.maxFps.toString()) }
    var rendererMode by remember(acceleratedMirror) {
        mutableStateOf(if (acceleratedMirror) preferred.rendererMode else MirrorRendererMode.Legacy)
    }
    var mirrorSession by remember { mutableStateOf<MirrorSession?>(null) }
    var bugDialogVisible by remember { mutableStateOf(false) }
    var bugSaveStatus by remember { mutableStateOf("") }
    var liveActionStatus by remember { mutableStateOf("") }
    var clipDialogVisible by remember { mutableStateOf(false) }
    var recordingState by remember { mutableStateOf<LiveRecordingState>(LiveRecordingState.Idle) }
    var recordingRequestId by remember { mutableStateOf(0) }
    var recordingStartedAtMillis by remember { mutableStateOf<Long?>(null) }
    var recordingElapsedMillis by remember { mutableStateOf(0L) }
    var localDevicePaneWidth by remember(devicePaneWidth) { mutableStateOf(devicePaneWidth.coerceAtLeast(680f)) }
    var localControlsPaneHeight by remember(controlsPaneHeight, controlsPaneMinHeight) {
        mutableStateOf(controlsPaneHeight.coerceIn(controlsPaneMinHeight, 520f))
    }
    var terminalPlacement by remember { mutableStateOf<DockPlacement?>(null) }
    var lastTerminalPlacement by remember { mutableStateOf(DockPlacement.Right) }
    var terminalTabIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var handledTerminalRunId by remember { mutableStateOf<String?>(null) }

    fun selectTerminalTab(runId: String) {
        if (runId !in terminalTabIds) terminalTabIds = terminalTabIds + runId
        onActiveRunIdChange(runId)
    }

    fun closeTerminalTab(runId: String) {
        val remaining = terminalTabIds.filter { it != runId }
        terminalTabIds = remaining
        if (activeRunId == runId) onActiveRunIdChange(remaining.lastOrNull())
        if (remaining.isEmpty()) terminalPlacement = null
    }

    fun openOrFocusTerminal(placement: DockPlacement) {
        val project = projects.firstOrNull { project ->
            activeRunId != null && running.any { it.runId == activeRunId && it.projectId == project.id }
        } ?: projects.firstOrNull()
        if (project == null) {
            liveActionStatus = "Create a project to open a terminal"
            return
        }
        val runId = activeRunId?.takeIf { activeId -> running.any { it.runId == activeId } }
            ?: services.actionRuns.openShell(project)
        selectTerminalTab(runId)
        lastTerminalPlacement = placement
        terminalPlacement = placement
    }

    fun toggleTerminal(placement: DockPlacement) {
        if (terminalPlacement == placement) {
            terminalPlacement = null
            return
        }
        openOrFocusTerminal(placement)
    }

    LaunchedEffect(terminalRunId, running) {
        val runId = terminalRunId ?: return@LaunchedEffect
        if (runId == handledTerminalRunId) return@LaunchedEffect
        if (running.none { it.runId == runId }) return@LaunchedEffect
        selectTerminalTab(runId)
        terminalPlacement = lastTerminalPlacement
        handledTerminalRunId = runId
    }
    LaunchedEffect(running) {
        terminalTabIds = terminalTabIds.filter { tabId -> running.any { it.runId == tabId } }
    }
    LaunchedEffect(controlsPaneMinHeight) {
        if (localControlsPaneHeight < controlsPaneMinHeight) {
            localControlsPaneHeight = controlsPaneMinHeight
            onControlsPaneHeightChange(controlsPaneMinHeight)
        }
    }
    val sendMirrorInput = rememberMirrorInputSender(services, serial)
    fun sendHardware(input: MirrorInput) {
        sendMirrorInput(input)
    }
    fun handleApkDrop(paths: List<String>) {
        if (serial == null) {
            liveActionStatus = "Select an online device"
            return
        }
        when (classifyLocalPaths(paths)) {
            LocalDropKind.Empty -> Unit
            LocalDropKind.Apks -> {
                transfer.tryStart(scope, "Installing…") {
                    installAll(services.apps, serial, paths)
                }
            }
            LocalDropKind.Files, LocalDropKind.Mixed -> {
                liveActionStatus = "Live accepts APK files only — drop rejected"
            }
        }
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
    LaunchedEffect(transfer.status) {
        if (transfer.status.isNotBlank()) liveActionStatus = transfer.status
    }
    // Keying on the device keeps a mid-countdown disconnect from starting a recording:
    // the old coroutine is cancelled and the new one bails out below.
    LaunchedEffect(recordingRequestId, serial, device?.state) {
        if (recordingRequestId == 0) return@LaunchedEffect
        if (serial == null || device?.state != DeviceConnectionState.Online) return@LaunchedEffect
        if (recordingState !is LiveRecordingState.Countdown) return@LaunchedEffect
        for (seconds in 3 downTo 1) {
            recordingState = LiveRecordingState.Countdown(seconds)
            delay(1_000)
        }
        runCatching { services.bugs.beginRecording() }
            .onSuccess {
                recordingStartedAtMillis = currentTimeMillis()
                recordingElapsedMillis = 0L
                recordingState = LiveRecordingState.Recording
                liveActionStatus = "Recording screen and inputs"
            }
            .onFailure { error ->
                recordingState = LiveRecordingState.Idle
                liveActionStatus = error.message ?: "Could not start recording"
            }
    }
    LaunchedEffect(recordingStartedAtMillis, recordingState) {
        val startedAt = recordingStartedAtMillis ?: return@LaunchedEffect
        while (recordingState == LiveRecordingState.Recording) {
            recordingElapsedMillis = (currentTimeMillis() - startedAt).coerceAtLeast(0L)
            delay(1_000)
        }
    }
    fun reconnectMirror(config: MirrorVideoConfig) {
        LiveMirrorSettings.update(config)
        if (serial == null) return
        scope.launch {
            val result = services.mirror.connect(serial, config)
            connectResult = if (result.isSuccess) result.stdout else result.stderr
        }
    }
    fun applyPreset(size: String, mbps: String, fps: String = "60") {
        maxSize = size
        bitRateMbps = mbps
        maxFps = fps
        reconnectMirror(mirrorVideoConfig(size, mbps, fps, rendererMode))
    }
    fun mirrorConfig(): MirrorVideoConfig = mirrorVideoConfig(maxSize, bitRateMbps, maxFps, rendererMode).also {
        LiveMirrorSettings.update(it)
    }
    LaunchedEffect(Unit) {
        services.mirror.status.collectLatest { mirrorStatus = it }
    }
    LaunchedEffect(Unit) {
        services.mirror.session.collectLatest { mirrorSession = it }
    }
    LaunchedEffect(serial, device?.state) {
        recordingState = LiveRecordingState.Idle
        recordingStartedAtMillis = null
        recordingElapsedMillis = 0L
        if (serial != null && device?.state == DeviceConnectionState.Online) {
            val result = services.mirror.connect(serial, mirrorConfig())
            connectResult = if (result.isSuccess) result.stdout else result.stderr
            if (result.isSuccess) {
                try {
                    services.bugs.startCapture(serial, device)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    connectResult = "$connectResult\nBug capture unavailable: ${error.message ?: error}"
                }
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        // Keep the scrcpy session warm when leaving Live so returning is instant.
                        // Device stop / serial change still tear down via ShellState / reconnect.
                        services.bugs.stopCapture()
                    }
                }
            }
        } else {
            // Still on Live but no online device — release the stream.
            withContext(NonCancellable) {
                services.bugs.stopCapture()
                services.mirror.disconnect()
            }
        }
    }
    val activeTerminalRunId = activeRunId?.takeIf { it in terminalTabIds }
    val terminalTabs = terminalTabIds.mapNotNull { tabId -> running.firstOrNull { it.runId == tabId } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(Modifier.weight(1f).fillMaxWidth()) {
        MirrorFrameContent(services.mirror, serial) { frameFlow, frame ->
            val dialogsOpen = bugDialogVisible || clipDialogVisible
            LiveDevicePane(
                serial = serial,
                device = device,
                frame = frame,
                frameFlow = frameFlow,
                mirrorStatus = mirrorStatus,
                mirrorTelemetry = mirrorSession?.liveTelemetry().orEmpty(),
                connectResult = connectResult,
                modifier = Modifier
                    .width(localDevicePaneWidth.dp)
                    .fillMaxHeight()
                    .padding(end = 6.dp)
                    .onExternalFileDrop(enabled = serial != null) { handleApkDrop(it) },
                onPower = { sendHardware(MirrorInput.Power) },
                onVolumeUp = { sendHardware(MirrorInput.Key(24)) },
                onVolumeDown = { sendHardware(MirrorInput.Key(25)) },
                onRotate = { runLiveAction("Rotate") { services.devices.shell(serial!!, listOf("settings", "put", "system", "user_rotation", "1")) } },
                onCaptureScreenshot = { runLiveAction("Screenshot") { services.artifacts.saveScreenshot(serial!!, "andy-${serial}.png") } },
                onBugReport = { bugDialogVisible = true },
                onRecord = {
                    when (recordingState) {
                        LiveRecordingState.Idle -> {
                            recordingState = LiveRecordingState.Countdown(3)
                            recordingRequestId++
                        }
                        LiveRecordingState.Recording -> {
                            recordingState = LiveRecordingState.Saving
                            scope.launch {
                                runCatching { services.bugs.saveRecording(device) }
                                    .onSuccess { recording ->
                                        recordingState = LiveRecordingState.Idle
                                        recordingStartedAtMillis = null
                                        recordingElapsedMillis = 0L
                                        liveActionStatus = "Saved ${recording.title}"
                                        onRecordingSaved()
                                    }
                                    .onFailure { error ->
                                        recordingState = LiveRecordingState.Recording
                                        liveActionStatus = error.message ?: "Could not save recording"
                                    }
                            }
                        }
                        is LiveRecordingState.Countdown, LiveRecordingState.Saving -> Unit
                    }
                },
                recordLabel = when (val state = recordingState) {
                    LiveRecordingState.Idle -> "Record"
                    is LiveRecordingState.Countdown -> state.seconds.toString()
                    LiveRecordingState.Recording -> "Stop"
                    LiveRecordingState.Saving -> "Saving"
                },
                recordEnabled = recordingState !is LiveRecordingState.Countdown && recordingState != LiveRecordingState.Saving,
                recordingCountdown = (recordingState as? LiveRecordingState.Countdown)?.seconds,
                recordingActive = recordingState == LiveRecordingState.Recording || recordingState == LiveRecordingState.Saving,
                recordingDuration = recordingElapsedMillis.takeIf { recordingState == LiveRecordingState.Recording }?.let(::formatRecordingDuration),
                showRecord = true,
                onClipText = { clipDialogVisible = true },
                onPopOut = onPopOutMirror,
                showPopOut = !isWeb,
                surfaceOccluded = dialogsOpen,
                onInput = sendMirrorInput,
                onConnect = {
                    reconnectMirror(mirrorConfig())
                },
            )
        }
        PaneDivider(
            onDrag = { dragX -> localDevicePaneWidth = (localDevicePaneWidth + dragX).coerceIn(560f, 1800f) },
            onDragEnd = { onDevicePaneWidthChange(localDevicePaneWidth) },
        )
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PanelCard(Modifier.fillMaxWidth().height(localControlsPaneHeight.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Controls", color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TerminalDockToggleRow(
                        terminalPlacement = terminalPlacement,
                        onToggle = ::toggleTerminal,
                    )
                }
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill("720 edge", maxSize == "720", Cyan) { applyPreset("720", "4") }
                    FilterPill("1080 edge", maxSize == "1080", Green) { applyPreset("1080", "8") }
                    FilterPill("1440 edge", maxSize == "1440", Yellow) { applyPreset("1440", "12") }
                    FilterPill("Native", maxSize == "0", Rust) { applyPreset("0", "16") }
                }
                if (acceleratedMirror) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterPill("Auto", rendererMode == MirrorRendererMode.Auto, Cyan) {
                            rendererMode = MirrorRendererMode.Auto
                            reconnectMirror(mirrorConfig())
                        }
                        FilterPill("GPU", rendererMode == MirrorRendererMode.Accelerated, Green) {
                            rendererMode = MirrorRendererMode.Accelerated
                            reconnectMirror(mirrorConfig())
                        }
                        FilterPill("CPU", rendererMode == MirrorRendererMode.Legacy, Rust) {
                            rendererMode = MirrorRendererMode.Legacy
                            reconnectMirror(mirrorConfig())
                        }
                    }
                }
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    LabeledField("Max edge", maxSize, { maxSize = it.filter(Char::isDigit) }, Modifier.width(96.dp))
                    LabeledField("Mbps", bitRateMbps, { bitRateMbps = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.width(88.dp))
                    LabeledField("FPS", maxFps, { maxFps = it.filter(Char::isDigit) }, Modifier.width(78.dp))
                    Box(Modifier.align(Alignment.Bottom).padding(bottom = 2.dp)) {
                        Button(onClick = {
                            reconnectMirror(mirrorConfig())
                        }) { Text("Restart mirror") }
                    }
                }
                Text("Max edge is the stream's longest side; 0 keeps the device's native resolution.", color = TextSecondary, fontSize = 10.sp)
                Text(
                    if (acceleratedMirror) {
                        if (isWeb) {
                            "Auto uses WebCodecs/WebGL when the browser verifies hardware, otherwise CPU. GPU never falls back."
                        } else {
                            "Auto uses inline Metal when available and falls back to CPU. GPU never falls back."
                        }
                    } else {
                        "This platform uses CPU presentation until a native GPU mirror is available."
                    },
                    color = TextSecondary,
                    fontSize = 10.sp,
                )
                Text("Bug capture", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CompactHardwareButton("Save bug", serial) { bugDialogVisible = true }
                    if (transfer.busy) {
                        OutlinedButton(onClick = { transfer.cancel() }) { Text("Cancel transfer") }
                    }
                    if (liveActionStatus.isNotBlank()) {
                        Text(
                            liveActionStatus,
                            color = transferStatusColor(liveActionStatus),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = if (liveActionStatus.startsWith("App installed") || liveActionStatus.startsWith("App replaced") || liveActionStatus.startsWith("Installed ") || liveActionStatus.startsWith("Replaced ")) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
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
            }
            HorizontalPaneDivider(
                onDrag = { dragY -> localControlsPaneHeight = (localControlsPaneHeight + dragY).coerceIn(controlsPaneMinHeight, 520f) },
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
        if (terminalPlacement == DockPlacement.Right) {
            TerminalDockDrawer(
                services = services,
                terminalTabs = terminalTabs,
                activeRunId = activeTerminalRunId,
                placement = DockPlacement.Right,
                onSelectTab = ::selectTerminalTab,
                onCloseTab = ::closeTerminalTab,
                onClose = { terminalPlacement = null },
                modifier = Modifier.width(420.dp).fillMaxHeight().padding(start = 6.dp),
            )
        }
    }
    if (terminalPlacement == DockPlacement.Bottom) {
        TerminalDockDrawer(
            services = services,
            terminalTabs = terminalTabs,
            activeRunId = activeTerminalRunId,
            placement = DockPlacement.Bottom,
            onSelectTab = ::selectTerminalTab,
            onCloseTab = ::closeTerminalTab,
            onClose = { terminalPlacement = null },
            modifier = Modifier.fillMaxWidth().height(280.dp),
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

private fun formatRecordingDuration(elapsedMillis: Long): String {
    val totalSeconds = (elapsedMillis / 1_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
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
