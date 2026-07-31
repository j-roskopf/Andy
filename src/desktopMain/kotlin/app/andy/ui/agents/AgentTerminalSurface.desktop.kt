package app.andy.ui.agents

import ai.rever.bossterm.compose.EmbeddableTerminal
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.desktop.service.DesktopWorkspaceStore
import app.andy.desktop.service.McpAgentRunClient
import app.andy.desktop.service.agents.DesktopAgentRunService
import app.andy.model.WorkspaceState
import app.andy.model.toTerminalAppearance
import app.andy.onImageFilesDropped
import app.andy.service.AndyServices
import app.andy.terminal.AndyTerminalView
import app.andy.terminal.BossTermAccess
import app.andy.terminal.TmuxWheelInput
import app.andy.terminal.disposeScrollbackReplayView
import app.andy.terminal.panelBackgroundArgb
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

private val NoSessionsRevision = MutableStateFlow(0L)
private val NoWorkspace = MutableStateFlow(WorkspaceState())

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun AgentTerminalSurface(
    services: AndyServices,
    taskId: String,
    sessionActive: Boolean,
    onImagesStaged: (List<String>) -> Unit,
    modifier: Modifier,
) {
    val agentRuns = when (val runs = services.agentRuns) {
        is DesktopAgentRunService -> runs
        is McpAgentRunClient -> runs.terminalHost()
        else -> null
    }
    val workspaceStore = services.workspaceStore as? DesktopWorkspaceStore
    val workspaceFlow = remember(workspaceStore) { workspaceStore?.state ?: NoWorkspace }
    val workspace by workspaceFlow.collectAsState()
    val appearance = remember(workspace.terminalThemeId, workspace.terminalFontFamilyId, workspace.terminalFontSize) {
        workspace.toTerminalAppearance()
    }
    val terminalPanelBackground = remember(appearance) {
        Color(appearance.panelBackgroundArgb())
    }
    val revisionFlow = remember(agentRuns) { agentRuns?.terminalSessionsRevision ?: NoSessionsRevision }
    val sessionsRevision by revisionFlow.collectAsState()
    val effectiveSessionActive = sessionActive

    var liveTerminal by remember(taskId) { mutableStateOf<AndyTerminalView?>(null) }
    var historyTerminal by remember(taskId) { mutableStateOf<AndyTerminalView?>(null) }
    val releaseViewer = remember(agentRuns) { agentRuns?.let { runs -> runs::releaseTerminalViewer } }

    LaunchedEffect(taskId, effectiveSessionActive, sessionsRevision) {
        if (!effectiveSessionActive) return@LaunchedEffect
        liveTerminal = agentRuns?.terminalView(taskId)
    }

    LaunchedEffect(taskId, effectiveSessionActive) {
        fun clearHistory() {
            historyTerminal?.let(::disposeScrollbackReplayView)
            historyTerminal = null
        }

        suspend fun openHistoryIfAvailable() {
            if (historyTerminal != null) return
            if (agentRuns?.hasScrollback(taskId) != true) return
            historyTerminal = agentRuns.openScrollbackReplay(taskId)
        }

        if (!effectiveSessionActive) {
            liveTerminal = null
            releaseViewer?.invoke(taskId)
            openHistoryIfAvailable()
            return@LaunchedEffect
        }

        fun adoptLiveIfPresent(): Boolean {
            liveTerminal = agentRuns?.terminalView(taskId)
            if (liveTerminal == null) return false
            clearHistory()
            return true
        }

        if (adoptLiveIfPresent()) return@LaunchedEffect

        runCatching { agentRuns?.attachTerminalIfNeeded(taskId) }
        if (adoptLiveIfPresent()) return@LaunchedEffect

        // Bridge while the live viewer is still attaching.
        openHistoryIfAvailable()

        var attempts = 0
        val maxAttempts = if (agentRuns?.isTerminalLive(taskId) == true) 400 else 60
        while (liveTerminal == null && attempts < maxAttempts) {
            delay(100)
            if (attempts % 5 == 0) {
                runCatching { agentRuns?.attachTerminalIfNeeded(taskId) }
            }
            if (adoptLiveIfPresent()) return@LaunchedEffect
            attempts++
        }
        if (agentRuns?.isTerminalLive(taskId) != true) {
            when (val runs = services.agentRuns) {
                is DesktopAgentRunService -> runs.reconcileStaleActiveTaskIfNeeded(taskId)
                is McpAgentRunClient -> runs.reconcileStaleActiveTaskIfNeeded(taskId)
            }
        }
        openHistoryIfAvailable()
    }

    val historyToDispose = rememberUpdatedState(historyTerminal)
    DisposableEffect(taskId) {
        onDispose {
            historyToDispose.value?.let(::disposeScrollbackReplayView)
        }
    }
    val displayTerminal = liveTerminal ?: historyTerminal
    val acceptsLiveDrops = effectiveSessionActive && liveTerminal != null
    val tmuxWheelInput = remember(displayTerminal?.state, displayTerminal?.tmuxScrollback) {
        displayTerminal?.takeIf { it.tmuxScrollback }?.let { view ->
            TmuxWheelInput { bytes -> BossTermAccess.writeBytes(view.state, bytes) }
        }
    }
    var imageDragActive by remember(taskId) { mutableStateOf(false) }

    LaunchedEffect(taskId, effectiveSessionActive) {
        if (!effectiveSessionActive) imageDragActive = false
    }

    val onImagesStagedState = rememberUpdatedState(onImagesStaged)
    val onTerminalImagesDropped = rememberUpdatedState(
        newValue = { paths: List<String> ->
            if (paths.isEmpty()) return@rememberUpdatedState
            Snapshot.withMutableSnapshot { imageDragActive = false }
            onImagesStagedState.value(paths)
        },
    )
    val onDragActiveChange = rememberUpdatedState<(Boolean) -> Unit>(
        newValue = { active -> Snapshot.withMutableSnapshot { imageDragActive = active } },
    )

    val dropModifier = if (effectiveSessionActive) {
        Modifier.onImageFilesDropped(
            onFiles = { paths -> onTerminalImagesDropped.value(paths) },
            onDragActiveChange = { active -> onDragActiveChange.value(active) },
        )
    } else {
        Modifier
    }

    val dragBorderModifier = if (imageDragActive) {
        Modifier.border(2.dp, Cyan, RoundedCornerShape(AndyRadius.Control))
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .background(terminalPanelBackground)
            .then(dragBorderModifier),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(dropModifier)
                .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                    val wheel = tmuxWheelInput ?: return@onPointerEvent
                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                    if (wheel.onScroll(change.scrollDelta.y)) change.consume()
                },
        ) {
            val view = displayTerminal
            if (view != null) {
                key(taskId, view.state) {
                    EmbeddableTerminal(
                        state = view.state,
                        settingsOverride = view.settingsOverride,
                        command = view.command,
                        workingDirectory = view.workingDirectory,
                        environment = view.environment,
                        platformServices = view.platformServices,
                        autoFocus = acceptsLiveDrops,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                ) {
                    Text(
                        when {
                            effectiveSessionActive -> "Waiting for terminal…"
                            else -> "Terminal session ended"
                        },
                        color = TextSecondary,
                        fontFamily = MonoFont,
                        fontSize = 13.sp,
                    )
                    Text(
                        when {
                            imageDragActive -> "release to stage image for your next message"
                            effectiveSessionActive -> "Connecting to the live provider CLI for this chat"
                            else -> "Send a follow-up below to reopen the interactive CLI"
                        },
                        color = if (imageDragActive) Cyan else TextSecondary.copy(alpha = 0.72f),
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                    )
                }
            }
            if (imageDragActive && acceptsLiveDrops) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Cyan.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Text(
                        "release to stage image for your next message",
                        color = Cyan,
                        fontFamily = MonoFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
        }
    }
}
