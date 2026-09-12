package app.andy.desktop.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.ActionsConfig
import app.andy.model.AgentAutonomy
import app.andy.model.AgentKind
import app.andy.model.AgentModelCatalog
import app.andy.model.AgentTaskDraft
import app.andy.model.WorkspaceState
import app.andy.model.matchProject
import app.andy.model.withAlignedPermissions
import app.andy.service.AndyServices
import app.andy.service.VoiceSetupState
import app.andy.ui.components.ChoicePill
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.Spinner
import app.andy.ui.components.SpinnerSize
import app.andy.ui.components.TextButton
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Why the overlay opened without starting capture. */
sealed interface VoiceNewThreadBlocker {
    data object NotEnabled : VoiceNewThreadBlocker
    data class Failed(val what: String, val message: String) : VoiceNewThreadBlocker
    data object MicDenied : VoiceNewThreadBlocker
    data object ComposerRecording : VoiceNewThreadBlocker
    data class Other(val message: String) : VoiceNewThreadBlocker
}

enum class VoiceNewThreadPhase {
    Blocked,
    Recording,
    Transcribing,
    Confirm,
}

/**
 * Centered always-on-top UI for voice-started threads. Hotkey / Stop ends capture;
 * Start thread + Cancel stay clickable on confirm (Enter / Esc still work). After start
 * the overlay closes without raising the main window.
 */
@Composable
fun VoiceNewThreadOverlayContent(
    services: AndyServices,
    workspaceState: WorkspaceState,
    actionsConfig: ActionsConfig,
    initialBlocker: VoiceNewThreadBlocker?,
    stopRequestId: Int = 0,
    onRecordingChanged: (Boolean) -> Unit = {},
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMicPrivacySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val voice = services.voiceDictation
    val setupState by voice.setup.state.collectAsState()
    val level by voice.audioLevel.collectAsState()
    val focusRequester = remember { FocusRequester() }

    var phase by remember {
        mutableStateOf(if (initialBlocker != null) VoiceNewThreadPhase.Blocked else VoiceNewThreadPhase.Recording)
    }
    var blocker by remember { mutableStateOf(initialBlocker) }
    var transcript by remember { mutableStateOf("") }
    var agent by remember {
        mutableStateOf(
            AgentKind.entries.firstOrNull { it.name == workspaceState.voiceDefaultAgent }
                ?: services.agentRuns.lastUsedAgent.value
                ?: AgentKind.ClaudeCode,
        )
    }
    var model by remember { mutableStateOf(workspaceState.voiceDefaultModel) }
    var autonomy by remember {
        mutableStateOf(
            AgentAutonomy.entries.firstOrNull { it.name == workspaceState.voiceDefaultAutonomy }
                ?: AgentAutonomy.Standard,
        )
    }
    var projectId by remember { mutableStateOf(workspaceState.voiceDefaultProjectId) }
    var starting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var finishJob by remember { mutableStateOf<Job?>(null) }

    val cliStatuses by services.agentRuns.cliStatuses.collectAsState()
    val providerModels by services.agentRuns.providerModels.collectAsState()
    val availableAgents = remember(cliStatuses) {
        cliStatuses.filter { it.available }.map { it.kind }.ifEmpty { AgentKind.entries }
    }
    val modelOptions = remember(agent, providerModels) {
        providerModels[agent] ?: AgentModelCatalog.options(agent)
    }
    val projects = actionsConfig.projects

    fun recordingStartFailureBlocker(): VoiceNewThreadBlocker {
        val err = voice.lastError.value
        return when {
            err?.contains("Already recording", ignoreCase = true) == true ->
                VoiceNewThreadBlocker.ComposerRecording
            err?.contains("Microphone", ignoreCase = true) == true ->
                VoiceNewThreadBlocker.MicDenied
            else -> VoiceNewThreadBlocker.Other(err ?: "Could not start recording")
        }
    }

    /** Starts capture and keeps [onRecordingChanged] in sync so the hotkey can stop. */
    fun beginListening() {
        blocker = null
        phase = VoiceNewThreadPhase.Recording
        scope.launch {
            onRecordingChanged(true)
            val started = voice.startRecording()
            if (!started) {
                blocker = recordingStartFailureBlocker()
                onRecordingChanged(false)
                phase = VoiceNewThreadPhase.Blocked
            }
        }
    }

    fun stopAndConfirm() {
        if (phase != VoiceNewThreadPhase.Recording) return
        phase = VoiceNewThreadPhase.Transcribing
        onRecordingChanged(false)
        finishJob = scope.launch {
            try {
                val text = voice.finishRecording()
                if (!text.isNullOrBlank()) {
                    transcript = text
                    val matched = matchProject(
                        text,
                        projects.map { it.id to it.name },
                    )
                    if (matched != null) projectId = matched
                    phase = VoiceNewThreadPhase.Confirm
                } else {
                    val err = voice.lastError.value
                    blocker = when {
                        err?.contains("Microphone", ignoreCase = true) == true ->
                            VoiceNewThreadBlocker.MicDenied
                        else -> VoiceNewThreadBlocker.Other(err ?: "No speech detected")
                    }
                    phase = VoiceNewThreadPhase.Blocked
                }
            } finally {
                finishJob = null
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        // Let the overlay window finish its first layout pass before touching audio —
        // starting capture in the same frame as window creation has frozen the EDT.
        kotlinx.coroutines.delay(64)
        if (phase == VoiceNewThreadPhase.Recording && blocker == null) {
            onRecordingChanged(true)
            val started = voice.startRecording()
            if (!started) {
                blocker = recordingStartFailureBlocker()
                onRecordingChanged(false)
                phase = VoiceNewThreadPhase.Blocked
            }
        }
    }

    LaunchedEffect(stopRequestId) {
        if (stopRequestId > 0 && phase == VoiceNewThreadPhase.Recording) {
            stopAndConfirm()
        }
    }

    fun cancel() {
        when (phase) {
            VoiceNewThreadPhase.Recording -> voice.cancelRecording()
            VoiceNewThreadPhase.Transcribing -> {
                finishJob?.cancel()
                // Clears isBusy if recording already stopped mid-transcribe.
                voice.cancelRecording()
            }
            else -> Unit
        }
        onRecordingChanged(false)
        onClose()
    }

    fun startThread() {
        if (starting) return
        val prompt = transcript.trim()
        if (prompt.isBlank()) {
            error = "Transcript is empty"
            return
        }
        val project = projects.firstOrNull { it.id == projectId }
        starting = true
        error = null
        scope.launch {
            try {
                services.agentRuns.createAndStart(
                    AgentTaskDraft(
                        title = "",
                        prompt = prompt, // verbatim — never strip the project phrase
                        agent = agent,
                        projectId = project?.id,
                        directory = project?.contextDir?.takeIf { it.isNotBlank() },
                        autonomy = autonomy,
                        model = model?.takeIf { it.isNotBlank() },
                    ).withAlignedPermissions(),
                )
                onClose()
            } catch (t: Throwable) {
                error = t.message ?: "Failed to start thread"
                starting = false
            }
        }
    }

    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.Escape -> {
                        cancel()
                        true
                    }
                    event.key == Key.Enter &&
                        !event.isCtrlPressed &&
                        !event.isMetaPressed &&
                        phase == VoiceNewThreadPhase.Confirm &&
                        !starting -> {
                        startThread()
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.Sheet))
                .border(1.dp, AndyColors.Neutral700, RoundedCornerShape(AndyRadius.Sheet))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("New thread from voice", color = TextPrimary, fontSize = 16.sp)
            when (phase) {
                VoiceNewThreadPhase.Blocked -> BlockedBody(
                    blocker = blocker,
                    setupState = setupState,
                    onEnable = { scope.launch { services.voiceSetup.enable() } },
                    onRetry = { scope.launch { services.voiceSetup.enable() } },
                    onOpenSettings = onOpenSettings,
                    onOpenMicPrivacySettings = onOpenMicPrivacySettings,
                    onDismiss = onClose,
                    onTryAgain = { beginListening() },
                )
                VoiceNewThreadPhase.Recording -> {
                    Text(
                        "Listening… press the hotkey again, or use Stop / Cancel below.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                    LevelMeter(level = level)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { stopAndConfirm() }) { Text("Stop") }
                        TextButton(onClick = { cancel() }) { Text("Cancel", color = TextSecondary) }
                    }
                }
                VoiceNewThreadPhase.Transcribing -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Spinner(spinnerSize = SpinnerSize.Md)
                        Text("Transcribing…", color = TextSecondary, fontSize = 13.sp)
                    }
                    TextButton(onClick = { cancel() }) { Text("Cancel", color = TextSecondary) }
                }
                VoiceNewThreadPhase.Confirm -> {
                    Text(
                        "Review, then Start thread — or Cancel. Enter / Esc also work.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BasicTextField(
                            value = transcript,
                            onValueChange = { transcript = it },
                            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 14.sp),
                            cursorBrush = SolidColor(TextPrimary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .background(AndyColors.Neutral800, RoundedCornerShape(8.dp))
                                .padding(12.dp),
                        )
                        OverlayPicker(
                            label = "Agent",
                            value = agent.label,
                            options = availableAgents.map { it.name to it.label },
                            onSelected = { id ->
                                AgentKind.entries.firstOrNull { it.name == id }?.let { agent = it }
                            },
                        )
                        if (modelOptions.isNotEmpty()) {
                            OverlayPicker(
                                label = "Model",
                                value = model ?: "default",
                                options = listOf("" to "default") +
                                    modelOptions.map { it.id to (it.label.ifBlank { it.id }) },
                                onSelected = { id -> model = id.takeIf { it.isNotBlank() } },
                            )
                        }
                        OverlayPicker(
                            label = "Autonomy",
                            value = autonomy.name,
                            options = AgentAutonomy.entries.map { it.name to it.name },
                            onSelected = { id ->
                                AgentAutonomy.entries.firstOrNull { it.name == id }?.let { autonomy = it }
                            },
                        )
                        OverlayPicker(
                            label = "Project",
                            value = projects.firstOrNull { it.id == projectId }?.name ?: "none",
                            options = listOf("" to "none") + projects.map { it.id to it.name },
                            onSelected = { id -> projectId = id.takeIf { it.isNotBlank() } },
                        )
                        error?.let { Text(it, color = Rust, fontSize = 12.sp) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { startThread() },
                            enabled = !starting && transcript.isNotBlank(),
                        ) {
                            Text(if (starting) "Starting…" else "Start thread")
                        }
                        TextButton(onClick = { cancel() }) { Text("Cancel", color = TextSecondary) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedBody(
    blocker: VoiceNewThreadBlocker?,
    setupState: VoiceSetupState,
    onEnable: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMicPrivacySettings: () -> Unit,
    onDismiss: () -> Unit,
    onTryAgain: () -> Unit,
) {
    val effective: VoiceNewThreadBlocker = when {
        setupState is VoiceSetupState.Failed ->
            VoiceNewThreadBlocker.Failed(setupState.what, setupState.message)
        setupState is VoiceSetupState.NotEnabled &&
            (blocker == null || blocker is VoiceNewThreadBlocker.NotEnabled) ->
            VoiceNewThreadBlocker.NotEnabled
        blocker != null -> blocker
        else -> VoiceNewThreadBlocker.Other("Voice is not ready")
    }
    var awaitingReady by remember { mutableStateOf(false) }
    val message: String
    val actionLabel: String
    val action: () -> Unit
    when (effective) {
        VoiceNewThreadBlocker.NotEnabled -> {
            message = "Voice dictation is not enabled."
            actionLabel = "Enable"
            action = onEnable
        }
        is VoiceNewThreadBlocker.Failed -> {
            message = "${effective.what}: ${effective.message}"
            actionLabel = "Retry"
            action = onRetry
        }
        VoiceNewThreadBlocker.MicDenied -> {
            message = "Microphone access is denied. Allow Andy in System Settings → Privacy & Security → Microphone."
            actionLabel = "Open System Settings"
            action = onOpenMicPrivacySettings
        }
        VoiceNewThreadBlocker.ComposerRecording -> {
            message = "Already recording in a chat. Finish or cancel that capture first."
            actionLabel = "OK"
            action = onDismiss
        }
        is VoiceNewThreadBlocker.Other -> {
            message = effective.message
            actionLabel = "Try again"
            action = onTryAgain
        }
    }
    LaunchedEffect(setupState, awaitingReady) {
        if (awaitingReady && setupState is VoiceSetupState.Ready) {
            awaitingReady = false
            onTryAgain()
        }
    }
    Text(message, color = TextSecondary, fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            if (effective is VoiceNewThreadBlocker.NotEnabled ||
                effective is VoiceNewThreadBlocker.Failed
            ) {
                awaitingReady = true
            }
            action()
        }) { Text(actionLabel) }
        TextButton(onClick = onOpenSettings) { Text("Open Settings", color = TextSecondary) }
        TextButton(onClick = onDismiss) { Text("Close", color = TextSecondary) }
    }
    // When setup becomes Ready after Enable/Retry, offer an immediate re-try.
    if (setupState is VoiceSetupState.Ready &&
        effective !is VoiceNewThreadBlocker.ComposerRecording &&
        effective !is VoiceNewThreadBlocker.MicDenied
    ) {
        TextButton(onClick = onTryAgain) { Text("Start listening", color = Green) }
    }
}

@Composable
private fun LevelMeter(level: Float) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(28.dp),
    ) {
        repeat(8) { index ->
            val threshold = (index + 1) / 8f
            val active = level >= threshold * 0.85f
            Box(
                modifier = Modifier
                    .width(10.dp)
                    .height((8 + index * 2).dp)
                    .background(
                        if (active) Rust else AndyColors.Neutral700,
                        RoundedCornerShape(2.dp),
                    ),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "${(level * 100).toInt()}%",
            color = TextSecondary,
            fontSize = 11.sp,
            fontFamily = MonoFont,
        )
    }
}

@Composable
private fun OverlayPicker(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(72.dp))
        Box {
            ChoicePill(
                label = value,
                selected = true,
                contentDescription = label,
                onClick = { expanded = true },
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = AndyColors.Neutral750,
            ) {
                options.forEach { (id, text) ->
                    DropdownMenuItem(
                        text = { Text(text, color = TextPrimary) },
                        onClick = {
                            onSelected(id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/** Evaluate whether a new capture can start; returns a blocker when it cannot. */
fun voiceNewThreadBlocker(services: AndyServices): VoiceNewThreadBlocker? {
    if (app.andy.ui.components.ActiveVoiceDictationShortcut.isComposerRecording()) {
        return VoiceNewThreadBlocker.ComposerRecording
    }
    if (services.voiceDictation.isBusy) {
        return VoiceNewThreadBlocker.ComposerRecording
    }
    return when (val state = services.voiceSetup.state.value) {
        VoiceSetupState.NotEnabled -> VoiceNewThreadBlocker.NotEnabled
        is VoiceSetupState.Failed -> VoiceNewThreadBlocker.Failed(state.what, state.message)
        is VoiceSetupState.Downloading -> VoiceNewThreadBlocker.Other("Voice model is still downloading")
        VoiceSetupState.Ready -> null
    }
}
