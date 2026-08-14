package app.andy.ui.agents

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.desktop.service.DesktopWorkspaceStore
import app.andy.desktop.service.McpAgentRunClient
import app.andy.desktop.service.agents.DesktopAgentRunService
import app.andy.model.WorkspaceState
import app.andy.model.panelBackgroundArgb
import app.andy.model.toTerminalAppearance
import app.andy.onImageFilesDropped
import app.andy.service.AndyServices
import app.andy.terminal.rust.RustScrollbackReplay
import app.andy.terminal.rust.RustTerminalBackend
import app.andy.terminal.rust.RustTerminalCanvas
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

private val NoSessionsRevision = MutableStateFlow(0L)
private val NoWorkspace = MutableStateFlow(WorkspaceState())

// A maximized/ultrawide chat pane otherwise hands the CLI a huge line length, which it
// happily fills edge to edge — dense, hard-to-read text at an otherwise normal font size.
// Cap at a classic terminal width instead of stretching to the full pane.
private const val AGENT_TERMINAL_MAX_COLS = 120

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

    var liveRust by remember(taskId) { mutableStateOf<RustTerminalBackend?>(null) }
    var historyReplay by remember(taskId) { mutableStateOf<RustScrollbackReplay?>(null) }
    // One reattach attempt per task view: a resumable session gets one shot at reconnecting
    // to the live provider CLI before falling back to the read-only transcript.
    var reattachAttempted by remember(taskId) { mutableStateOf(false) }
    var reconnecting by remember(taskId) { mutableStateOf(false) }
    val releaseViewer = remember(agentRuns) { agentRuns?.let { runs -> runs::releaseTerminalViewer } }

    LaunchedEffect(taskId, effectiveSessionActive, sessionsRevision) {
        if (!effectiveSessionActive) return@LaunchedEffect
        liveRust = agentRuns?.rustTerminal(taskId)
    }

    LaunchedEffect(taskId, effectiveSessionActive) {
        fun clearHistory() {
            historyReplay?.close()
            historyReplay = null
        }

        suspend fun openHistoryIfAvailable() {
            if (historyReplay != null) return
            if (agentRuns?.hasScrollback(taskId) != true) return
            var created: RustScrollbackReplay? = null
            try {
                val replay = withContext(Dispatchers.Default) {
                    agentRuns.openScrollbackReplay(taskId).also { created = it }
                }
                historyReplay = replay
            } catch (e: kotlinx.coroutines.CancellationException) {
                created?.close()
                throw e
            }
        }

        if (!effectiveSessionActive) {
            liveRust = null
            releaseViewer?.invoke(taskId)
            if (!reattachAttempted && agentRuns?.canReattachSession(taskId) == true) {
                reattachAttempted = true
                reconnecting = true
                runCatching { agentRuns.reattachSession(taskId) }
                // Don't show the (possibly stale/duplicated) transcript while we try to
                // reconnect. If the session flips live, effectiveSessionActive recomposes
                // true and the branch below takes over; if the attempt fails, the task
                // rolls back to inactive and this effect re-runs with reattachAttempted
                // already true, falling through to the transcript below.
                return@LaunchedEffect
            }
            reconnecting = false
            openHistoryIfAvailable()
            return@LaunchedEffect
        }
        reconnecting = false

        fun adoptLiveIfPresent(): Boolean {
            liveRust = agentRuns?.rustTerminal(taskId)
            if (liveRust == null) return false
            clearHistory()
            return true
        }

        if (adoptLiveIfPresent()) return@LaunchedEffect

        runCatching { agentRuns?.attachTerminalIfNeeded(taskId) }
        if (adoptLiveIfPresent()) return@LaunchedEffect

        var attempts = 0
        val maxAttempts = if (agentRuns?.isTerminalLive(taskId) == true) 400 else 60
        while (liveRust == null && attempts < maxAttempts) {
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
        if (!effectiveSessionActive) {
            openHistoryIfAvailable()
        }
    }

    val historyToDispose = rememberUpdatedState(historyReplay)
    val releaseViewerOnDispose = rememberUpdatedState(releaseViewer)
    DisposableEffect(taskId) {
        onDispose {
            historyToDispose.value?.close()
            releaseViewerOnDispose.value?.invoke(taskId)
        }
    }
    val historyReplayLoading = historyReplay == null && !effectiveSessionActive && !reconnecting &&
        agentRuns?.hasScrollback(taskId) == true && liveRust == null
    val acceptsLiveDrops = effectiveSessionActive && liveRust != null
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
                .then(dropModifier),
        ) {
            val rust = liveRust
            val history = historyReplay
            when {
                rust != null -> {
                    key(taskId, "rust-live") {
                        RustTerminalCanvas(
                            backend = rust,
                            appearance = appearance,
                            autoFocus = acceptsLiveDrops,
                            maxCols = AGENT_TERMINAL_MAX_COLS,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                history != null -> {
                    key(taskId, "rust-history") {
                        RustTerminalCanvas(
                            backend = history,
                            appearance = appearance,
                            autoFocus = false,
                            readOnly = true,
                            maxCols = AGENT_TERMINAL_MAX_COLS,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                else -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                    ) {
                        Text(
                            when {
                                reconnecting -> "Reconnecting…"
                                historyReplayLoading -> "Loading chat history…"
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
                                reconnecting -> "Resuming the provider CLI session for this chat"
                                historyReplayLoading -> "Restoring the saved transcript for this chat"
                                effectiveSessionActive -> "Connecting to the live provider CLI for this chat"
                                else -> "Send a follow-up below to reopen the interactive CLI"
                            },
                            color = if (imageDragActive) Cyan else TextSecondary.copy(alpha = 0.72f),
                            fontFamily = MonoFont,
                            fontSize = 11.sp,
                        )
                    }
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
