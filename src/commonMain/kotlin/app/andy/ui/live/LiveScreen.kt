package app.andy.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import app.andy.model.BugReport
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.IosTargetState
import app.andy.model.RunningAction
import app.andy.currentTimeMillis
import app.andy.onExternalFileDrop
import app.andy.service.AndyPlatform
import app.andy.service.AndyServices
import app.andy.service.CommandResult
import app.andy.service.DhuSessionPhase
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
import app.andy.model.VirtualDevice
import app.andy.ui.components.PaneDivider
import app.andy.ui.controls.FoldableDisplayProfile
import app.andy.ui.controls.foldableDisplayProfile
import app.andy.ui.controls.foldablePostureForAngle
import app.andy.ui.controls.isFoldableEmulator
import app.andy.ui.controls.parseWmSizePx
import app.andy.ui.controls.setFoldableHingeAngle
import app.andy.ui.controls.setFoldablePosture
import app.andy.ui.controls.sizeForPosture
import app.andy.ui.logcat.LogcatState
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.Border
import app.andy.ui.theme.Green
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Yellow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.snapshotFlow

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

internal enum class LiveStreamChipTone { Neutral, Active, Warning }

internal data class LiveStreamChip(
    val label: String,
    val tone: LiveStreamChipTone = LiveStreamChipTone.Neutral,
)

internal fun liveStreamChips(
    session: MirrorSession?,
    frame: app.andy.service.MirrorFrame?,
    mirrorStatus: String,
): List<LiveStreamChip> {
    val chips = mutableListOf<LiveStreamChip>()
    val width = session?.width?.takeIf { it > 1 } ?: frame?.width?.takeIf { it > 1 }
    val height = session?.height?.takeIf { it > 1 } ?: frame?.height?.takeIf { it > 1 }
    if (width != null && height != null) {
        chips += LiveStreamChip("${width}×${height}")
    }
    val fps = session?.stats?.displayedFps?.takeIf { it > 0f }
        ?: frame?.displayedFps?.takeIf { it > 0f }
    if (fps != null) {
        chips += LiveStreamChip("${fps.toInt()} fps", LiveStreamChipTone.Active)
    }
    session?.let { active ->
        if (active.backend.decoder != "Unavailable") {
            chips += LiveStreamChip(active.backend.decoder)
        }
        if (active.backend.renderer != "Unavailable") {
            chips += LiveStreamChip(active.backend.renderer)
        }
        chips += LiveStreamChip(
            if (active.backend.isHardwareBacked) "GPU" else "CPU",
            if (active.backend.isHardwareBacked) LiveStreamChipTone.Active else LiveStreamChipTone.Neutral,
        )
        if (active.stats.droppedFrames > 0) {
            chips += LiveStreamChip("${active.stats.droppedFrames} dropped", LiveStreamChipTone.Warning)
        }
        active.stats.p95InputToPresentMillis?.let { latency ->
            chips += LiveStreamChip("${app.andy.formatDecimal(latency, 1)} ms P95")
        }
        active.backend.fallbackReason?.let { reason ->
            chips += LiveStreamChip(reason, LiveStreamChipTone.Warning)
        }
    }
    if (chips.isEmpty() && mirrorStatus.isNotBlank()) {
        chips += LiveStreamChip(mirrorStatus)
    }
    return chips
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
    iosTarget: IosTarget? = null,
    /** True when this device is currently shown in a pop-out window and should not mirror here. */
    mirroredElsewhere: Boolean = false,
    devicePaneWidth: Float,
    onStopEmulator: (AndroidDevice) -> Unit,
    stoppingEmulatorSerial: String?,
    stopStatus: String,
    onDevicePaneWidthChange: (Float) -> Unit,
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
    foldableHingeAngle: Float = 180f,
    onFoldableHingeAngleChange: (Float) -> Unit = {},
) {
    val isIosTarget = iosTarget != null
    val mirrorReady = when {
        isIosTarget -> iosTarget.isLiveReady
        else -> serial != null && device?.state == DeviceConnectionState.Online
    }
    // Only iOS Simulator hands off to an external host app (Simulator.app). Android emulators
    // and physical devices use an Andy pop-out window (emulators are launched window-hidden).
    val mirroredInExternalApp =
        mirroredElsewhere && iosTarget?.kind == IosTargetKind.Simulator
    val scope = rememberCoroutineScope()
    var mirrorStatus by remember { mutableStateOf("Disconnected") }
    var connectResult by remember { mutableStateOf("") }
    val isWeb = services.capabilities.platform == AndyPlatform.Web
    val acceleratedMirror = services.capabilities.acceleratedMirror
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
    // A surface recreated for a different target must not inherit presentation stats from the
    // previous connection. In particular, returning to the same iOS serial after Android could
    // reuse framesPresented > 0, hide the loading overlay, and expose a black presenter while the
    // new SimulatorKit session was still attaching.
    var mirrorSession by remember(serial) { mutableStateOf<MirrorSession?>(null) }
    var bugDialogVisible by remember { mutableStateOf(false) }
    var bugSaveStatus by remember { mutableStateOf("") }
    var liveActionStatus by remember { mutableStateOf("") }
    var clipDialogVisible by remember { mutableStateOf(false) }
    var screenshotEditorBytes by remember { mutableStateOf<ByteArray?>(null) }
    var completedRecording by remember { mutableStateOf<BugReport?>(null) }
    var recordingState by remember { mutableStateOf<LiveRecordingState>(LiveRecordingState.Idle) }
    var recordingRequestId by remember { mutableStateOf(0) }
    var recordingStartedAtMillis by remember { mutableStateOf<Long?>(null) }
    var recordingElapsedMillis by remember { mutableStateOf(0L) }
    var localDevicePaneWidth by remember(devicePaneWidth) { mutableStateOf(devicePaneWidth) }
    var userResizedDevicePane by remember(serial) { mutableStateOf(false) }
    var minDevicePaneWidth by remember(serial) { mutableStateOf(360f) }
    var terminalPlacement by remember { mutableStateOf<DockPlacement?>(null) }
    var lastTerminalPlacement by remember { mutableStateOf(DockPlacement.Right) }
    var terminalTabIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var handledTerminalRunId by remember { mutableStateOf<String?>(null) }
    // Android Auto DHU: off by default; Live-scoped and cleared on device switch.
    // DHU runs in its own desktop-head-unit window (no Andy embed / pointer forwarding).
    var androidAutoEnabled by remember(serial) { mutableStateOf(false) }
    val dhuReadiness by services.dhu.readiness.collectAsState()
    val dhuSession by services.dhu.session.collectAsState()
    val dhuConsole by services.dhu.console.collectAsState()
    val showAndroidAuto = !isIosTarget && !isWeb && serial != null && device != null
    val androidAutoReadyHint = remember(dhuReadiness, dhuSession) {
        when {
            !dhuReadiness.ready && dhuReadiness.checks.isNotEmpty() ->
                dhuReadiness.blocking.firstOrNull()?.let { "${it.label}: ${it.remediation ?: it.detail}" }
            dhuSession?.phase == DhuSessionPhase.Running ->
                "DHU running in its own window — interact there; console below"
            dhuSession?.phase == DhuSessionPhase.Starting ->
                "Starting Desktop Head Unit…"
            else -> null
        }
    }

    fun selectTerminalTab(runId: String) {
        if (runId !in terminalTabIds) terminalTabIds = terminalTabIds + runId
        onActiveRunIdChange(runId)
    }

    fun closeTerminalTab(runId: String) {
        services.actionRuns.stop(runId)
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
    fun reconnectMirror(config: MirrorVideoConfig, force: Boolean = false, displayChange: Boolean = false) {
        LiveMirrorSettings.update(config)
        if (serial == null) return
        scope.launch {
            val result = when {
                displayChange -> services.mirror.restartForDisplayChange(serial, config)
                force -> services.mirror.reconnect(serial, config)
                else -> services.mirror.connect(serial, config)
            }
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
    LaunchedEffect(services.mirror, serial) {
        services.mirror.session.collectLatest { session ->
            mirrorSession = session?.takeIf { it.serial == serial }
        }
    }
    LaunchedEffect(serial, device?.state, iosTarget?.udid, mirrorReady, mirroredElsewhere, androidAutoEnabled) {
        recordingState = LiveRecordingState.Idle
        recordingStartedAtMillis = null
        recordingElapsedMillis = 0L
        // Bug capture is owned by DesktopBugService's mirror.session observer so the rolling
        // 30s buffer survives Live ↔ Design (and other mirror pages) without clearing on leave.
        if (mirrorReady && serial != null && !mirroredElsewhere) {
            val result = services.mirror.connect(serial, mirrorConfig())
            connectResult = if (result.isSuccess) result.stdout else result.stderr
            if (result.isSuccess) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        // USB AOA prep for DHU briefly drops ADB/mirror; keep DHU if AA stays on.
                        if (!androidAutoEnabled) {
                            services.dhu.stop()
                        }
                    }
                }
            }
        } else {
            // When this device is mirrored elsewhere, do not disconnect the primary engine here.
            // iOS Simulator handoff / shared-primary pop-outs keep Live's session warm; Android
            // pop-outs of the Live device take over that engine into the pop-out pool instead.
            withContext(NonCancellable) {
                if (!androidAutoEnabled) {
                    services.dhu.stop()
                }
                when {
                    mirroredElsewhere -> Unit
                    // Transient offline while AA USB renegotiates — reconnect when mirrorReady returns.
                    androidAutoEnabled -> Unit
                    else -> services.mirror.disconnect(immediate = false)
                }
            }
        }
    }
    LaunchedEffect(androidAutoEnabled, serial, mirroredElsewhere) {
        if (!showAndroidAuto || !androidAutoEnabled || mirroredElsewhere) {
            services.dhu.stop()
            return@LaunchedEffect
        }
        // Wait for mirror without keying on mirrorReady — USB accessory reset drops ADB briefly
        // and must not cancel/stop an in-flight DHU start.
        snapshotFlow {
            Triple(
                mirrorReady,
                mirrorSession?.readyForPresentation == true,
                connectResult,
            )
        }.first { (ready, presenting, connect) ->
            ready && (presenting || connect.isNotBlank())
        }
        val phase = services.dhu.session.value?.phase
        if (phase == DhuSessionPhase.Starting || phase == DhuSessionPhase.Running) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { services.dhu.stop() }
            }
            return@LaunchedEffect
        }
        services.dhu.refreshReadiness(serial)
        val result = services.dhu.start(serial)
        if (!result.isSuccess && result.stderr.isNotBlank()) {
            liveActionStatus = "Android Auto: ${result.stderr}"
        }
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                services.dhu.stop()
            }
        }
    }
    LaunchedEffect(serial, androidAutoEnabled) {
        if (showAndroidAuto) {
            services.dhu.refreshReadiness(serial)
        }
    }
    val activeTerminalRunId = activeRunId?.takeIf { it in terminalTabIds }
    val terminalTabs = terminalTabIds.mapNotNull { tabId -> running.firstOrNull { it.runId == tabId } }
    val iosInputEnabled = isIosTarget && when (iosTarget.kind) {
        // Physical devices have no HID path. Sims accept input as soon as Live attaches — don't
        // gate on frame presentation or registry Booted state (Devices → Live can race that).
        IosTargetKind.Physical -> false
        IosTargetKind.Simulator -> true
    }
    val showLogcat = !isIosTarget
    val showMirrorStreamControls = !isIosTarget
    val iosSinglePane = isIosTarget
    var virtualDevices by remember { mutableStateOf<List<VirtualDevice>>(emptyList()) }
    LaunchedEffect(device?.kind, device?.displayName) {
        virtualDevices = if (device?.kind == DeviceKind.Emulator) {
            runCatching { services.avd.listVirtualDevices() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }
    val foldable = !isIosTarget && isFoldableEmulator(device, virtualDevices)
    val foldableProfile = remember(foldable, device?.displayName, virtualDevices) {
        if (!foldable) return@remember null
        val avdName = device?.displayName?.trim().orEmpty()
        val avd = virtualDevices.firstOrNull { it.name.equals(avdName, ignoreCase = true) }
        foldableDisplayProfile(avd)
    }
    var foldableCaptureHint by remember(serial) { mutableStateOf<MirrorSourceSize?>(null) }
    fun hintForFoldableAngle(angle: Float, profile: FoldableDisplayProfile?): MirrorSourceSize? {
        val size = profile?.sizeForPosture(foldablePostureForAngle(angle)) ?: return null
        return MirrorSourceSize(size.first, size.second)
    }
    fun applyFoldableAndRefresh(label: String, angle: Float, block: suspend () -> CommandResult) {
        if (serial == null) return
        // Resize the Live host immediately to the expected outer/inner geometry.
        foldableCaptureHint = hintForFoldableAngle(angle, foldableProfile) ?: foldableCaptureHint
        userResizedDevicePane = false
        scope.launch {
            val result = block()
            liveActionStatus = if (result.isSuccess) {
                result.stdout.ifBlank { label }
            } else {
                result.stderr.ifBlank { result.stdout }.ifBlank { "$label failed" }
            }
            if (result.isSuccess) {
                // Same stream settings would otherwise no-op connect(); force a full restart
                // so scrcpy picks up the new display mode.
                delay(250)
                reconnectMirror(mirrorConfig(), displayChange = true)
            }
        }
    }
    LaunchedEffect(mirrorSession, foldableCaptureHint, foldableHingeAngle, foldableProfile) {
        val hint = foldableCaptureHint ?: return@LaunchedEffect
        val session = mirrorSession ?: return@LaunchedEffect
        if (!session.readyForPresentation || session.width <= 1 || session.height <= 1) return@LaunchedEffect
        val stream = MirrorSourceSize(session.width, session.height)
        val profile = foldableProfile
        if (profile == null) {
            foldableCaptureHint = null
            return@LaunchedEffect
        }
        val posture = foldablePostureForAngle(foldableHingeAngle)
        if (foldableStreamMatchesPosture(stream, posture, profile)) {
            foldableCaptureHint = null
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        MirrorFrameContent(services.mirror, serial) { frameFlow, frame ->
            val dialogsOpen = bugDialogVisible || clipDialogVisible || completedRecording != null || screenshotEditorBytes != null
            val devicePaneModifier = if (iosSinglePane) {
                Modifier.weight(1f).fillMaxHeight()
            } else {
                Modifier.fillMaxHeight()
            }
            BoxWithConstraints(devicePaneModifier) {
                val fittedDevicePaneWidth = liveDevicePaneFittedWidth(
                    maxPaneHeight = maxHeight,
                    device = device,
                    frame = frame,
                    showHardwareControls = !isIosTarget,
                    showDeviceHeader = serial != null,
                    showChromeControls = !isIosTarget,
                    session = mirrorSession,
                    captureHint = foldableCaptureHint,
                    foldableProfile = foldableProfile,
                    foldableHingeAngle = foldableHingeAngle,
                )
                minDevicePaneWidth = fittedDevicePaneWidth.value
                // While a foldable open/close hint is active, follow the fitted outer/inner
                // width even if the user previously dragged the divider wider.
                val devicePaneWidthDp = if (userResizedDevicePane && foldableCaptureHint == null) {
                    localDevicePaneWidth.dp.coerceAtLeast(fittedDevicePaneWidth)
                } else {
                    fittedDevicePaneWidth
                }
                val dhuActive = showAndroidAuto && androidAutoEnabled
                val leftWidth = devicePaneWidthDp
                Column(
                    Modifier
                        .then(
                            if (iosSinglePane) Modifier.fillMaxSize()
                            else Modifier.width(leftWidth).padding(end = 6.dp),
                        )
                        .fillMaxHeight(),
                ) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        LiveDevicePane(
                            serial = serial,
                            device = device,
                            displayName = iosTarget?.displayName ?: device?.displayName,
                            frame = frame,
                            frameFlow = frameFlow,
                            mirrorStatus = mirrorStatus,
                            mirrorSession = mirrorSession,
                            connectResult = when {
                                mirrorSession?.failureReason != null -> mirrorSession?.failureReason.orEmpty()
                                else -> connectResult
                            },
                            showAndroidNavButtons = !isIosTarget,
                            showHardwareControls = !isIosTarget,
                            showClipTextControl = !isIosTarget || iosInputEnabled,
                            passThroughInput = !isIosTarget || iosInputEnabled,
                            terminalPlacement = terminalPlacement.takeIf { iosSinglePane },
                            onTerminalToggle = if (iosSinglePane) ::toggleTerminal else null,
                            modifier = Modifier.fillMaxSize().onExternalFileDrop(enabled = serial != null) { handleApkDrop(it) },
                            onPower = { sendHardware(MirrorInput.Power) },
                            onVolumeUp = { sendHardware(MirrorInput.Key(24)) },
                            onVolumeDown = { sendHardware(MirrorInput.Key(25)) },
                            onRotate = { runLiveAction("Rotate") { services.devices.shell(serial!!, listOf("settings", "put", "system", "user_rotation", "1")) } },
                            onCaptureScreenshot = {
                                if (serial == null) {
                                    liveActionStatus = "Select an online device"
                                } else {
                                    scope.launch {
                                        val bytes = services.artifacts.captureScreenshotForEditing(serial)
                                        if (bytes != null) {
                                            services.bugs.recordScreenshot(bytes, "Screenshot")
                                            screenshotEditorBytes = bytes
                                        } else {
                                            liveActionStatus = "Screenshot: " + runCatching {
                                                services.artifacts.saveScreenshot(serial, "andy-$serial.png")
                                            }.getOrNull()?.let { result ->
                                                if (result.isSuccess) result.stdout.ifBlank { "ok" } else result.stderr.ifBlank { result.stdout }
                                            }.orEmpty()
                                        }
                                    }
                                }
                            },
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
                                                    liveActionStatus = recording.videoCaptureWarning
                                                        ?.let { "Saved ${recording.title} — $it" }
                                                        ?: "Saved ${recording.title}"
                                                    completedRecording = recording
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
                            showPopOut = !isWeb && !mirroredElsewhere,
                            mirroredElsewhere = mirroredElsewhere,
                            mirroredInExternalApp = mirroredInExternalApp,
                            surfaceOccluded = dialogsOpen,
                            foldableEnabled = foldable,
                            foldableHingeAngle = foldableHingeAngle,
                            foldableProfile = foldableProfile,
                            foldableCaptureHint = foldableCaptureHint,
                            onInput = sendMirrorInput,
                            onConnect = {
                                reconnectMirror(mirrorConfig())
                            },
                        )
                    }
                    if (dhuActive) {
                        Spacer(Modifier.height(8.dp))
                        DhuConsolePanel(
                            dhu = services.dhu,
                            console = dhuConsole,
                            session = dhuSession,
                            readiness = dhuReadiness,
                            onRetry = {
                                scope.launch {
                                    services.dhu.start(serial)
                                }
                            },
                            onStop = {
                                androidAutoEnabled = false
                                scope.launch { services.dhu.stop() }
                            },
                        )
                    }
                }
            }
        }
        if (!iosSinglePane) {
        PaneDivider(
            onDrag = { dragX ->
                userResizedDevicePane = true
                localDevicePaneWidth = (localDevicePaneWidth + dragX).coerceIn(minDevicePaneWidth, 1800f)
            },
            onDragEnd = {
                if (userResizedDevicePane) onDevicePaneWidthChange(localDevicePaneWidth)
            },
        )
        LiveSidePanel(
            serial = serial,
            device = device,
            displayName = device?.displayName,
            showLogcat = showLogcat,
            showMirrorStreamControls = showMirrorStreamControls,
            showAndroidAuto = showAndroidAuto,
            androidAutoEnabled = androidAutoEnabled,
            onAndroidAutoEnabledChange = { enabled ->
                androidAutoEnabled = enabled
                if (!enabled) {
                    scope.launch { services.dhu.stop() }
                }
            },
            androidAutoReadyHint = androidAutoReadyHint,
            acceleratedMirror = acceleratedMirror,
            isWeb = isWeb,
            maxSize = maxSize,
            bitRateMbps = bitRateMbps,
            maxFps = maxFps,
            rendererMode = rendererMode,
            onMaxSizeChange = { maxSize = it },
            onBitRateMbpsChange = { bitRateMbps = it },
            onMaxFpsChange = { maxFps = it },
            onRendererModeChange = { mode ->
                rendererMode = mode
                reconnectMirror(mirrorVideoConfig(maxSize, bitRateMbps, maxFps, mode))
            },
            onApplyPreset = { size, mbps -> applyPreset(size, mbps) },
            onReconnectMirror = { reconnectMirror(mirrorConfig()) },
            foldable = foldable,
            foldableHingeAngle = foldableHingeAngle,
            onFoldablePostureSelected = { posture ->
                onFoldableHingeAngleChange(posture.defaultAngle)
                applyFoldableAndRefresh("Posture ${posture.label}", posture.defaultAngle) {
                    services.devices.setFoldablePosture(serial!!, posture)
                }
            },
            transferBusy = transfer.busy,
            onCancelTransfer = { transfer.cancel() },
            liveActionStatus = liveActionStatus,
            liveActionStatusColor = transferStatusColor(liveActionStatus),
            onSaveBug = { bugDialogVisible = true },
            onStopEmulator = { device?.let(onStopEmulator) },
            stoppingEmulator = stoppingEmulatorSerial == serial,
            stopStatus = stopStatus,
            bugSaveStatus = bugSaveStatus,
            terminalPlacement = terminalPlacement,
            onTerminalToggle = ::toggleTerminal,
            logcat = services.logcat,
            appsService = services.apps,
            selectedPackage = selectedPackage,
            onSelectedPackageChange = onSelectedPackageChange,
            logcatState = logcatState,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 6.dp),
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
    screenshotEditorBytes?.let { bytes ->
        ScreenshotEditorSheet(
            pngBytes = bytes,
            artifacts = services.artifacts,
            bugs = services.bugs,
            suggestedName = "andy-${serial ?: "screenshot"}.png",
            onDismiss = { screenshotEditorBytes = null },
        )
    }
    completedRecording?.let { recording ->
        RecordingExportSheet(
            report = recording,
            bugs = services.bugs,
            recordingExport = services.recordingExport,
            onDismiss = {
                completedRecording = null
                onRecordingSaved()
            },
            onRenamed = { newTitle -> completedRecording = recording.copy(title = newTitle) },
        )
    }
    if (bugDialogVisible) {
        BugCaptureDialog(
            onDismiss = { bugDialogVisible = false },
            onSave = { draft ->
                scope.launch {
                    runCatching { services.bugs.saveBug(draft, device) }
                        .onSuccess { report ->
                            bugSaveStatus = report.videoCaptureWarning
                                ?.let { "Saved ${report.title} — $it" }
                                ?: "Saved ${report.title}"
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
