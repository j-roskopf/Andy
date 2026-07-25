package app.andy.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.desktop.service.DesktopWorkspaceStore
import app.andy.desktop.service.agents.DesktopAgentRunService
import app.andy.installImageDropTarget
import app.andy.model.WorkspaceState
import app.andy.model.toTerminalAppearance
import app.andy.onImageFilesDropped
import app.andy.service.AndyServices
import app.andy.terminal.onSwingEdt
import app.andy.terminal.panelBackgroundArgb
import app.andy.ui.shell.LocalSuppressHeavyweightSurfaces
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import io.github.ketraterm.ui.swing.api.SwingTerminal
import java.awt.Component
import java.awt.dnd.DropTarget
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

private val NoSessionsRevision = MutableStateFlow(0L)
private val NoAttachedIds = MutableStateFlow<Set<String>>(emptySet())
private val NoWorkspace = MutableStateFlow(WorkspaceState())

@Composable
actual fun AgentTerminalSurface(
    services: AndyServices,
    taskId: String,
    sessionActive: Boolean,
    onImagesStaged: (List<String>) -> Unit,
    modifier: Modifier,
) {
    val suppressHeavyweight = LocalSuppressHeavyweightSurfaces.current
    // Terminal widgets attach via the local host; task status ownership stays with
    // services.agentRuns (andyd MCP client or embedded). Never reconcile/persist on
    // the attach-only bridge — that can race the daemon's store.
    val primaryAgentRuns = services.agentRuns as? DesktopAgentRunService
    val agentRuns = when (val runs = services.agentRuns) {
        is DesktopAgentRunService -> runs
        is app.andy.desktop.service.McpAgentRunClient -> runs.terminalHost()
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
    val attachedFlow = remember(agentRuns) { agentRuns?.attachedTerminalTaskIds ?: NoAttachedIds }
    val sessionsRevision by revisionFlow.collectAsState()
    val attachedIds by attachedFlow.collectAsState()

    var liveTerminal by remember(taskId) { mutableStateOf<SwingTerminal?>(null) }
    var historyText by remember(taskId) { mutableStateOf<String?>(null) }

    LaunchedEffect(taskId, sessionActive, sessionsRevision, attachedIds) {
        suspend fun openHistoryIfAvailable() {
            if (historyText != null || agentRuns?.hasScrollback(taskId) != true) return
            historyText = withContext(Dispatchers.IO) {
                agentRuns.scrollbackDisplayText(taskId)
            }
        }

        liveTerminal = agentRuns?.terminalWidget(taskId)
        if (liveTerminal != null) {
            historyText = null
            return@LaunchedEffect
        }

        // Prefer attaching a viewer to an already-live tmux session (no provider restart).
        runCatching { agentRuns?.attachTerminalIfNeeded(taskId) }
        liveTerminal = agentRuns?.terminalWidget(taskId)
        if (liveTerminal != null) {
            historyText = null
            return@LaunchedEffect
        }

        if (!sessionActive) {
            // Finished chats: show cleaned text history. Do not auto-restart the provider CLI.
            openHistoryIfAvailable()
            return@LaunchedEffect
        }

        // Active/queued: wait for the live terminal (or a live tmux attach).
        // Short grace when nothing is live yet so stale "queued" chats fall back to
        // history quickly instead of spinning on reconnect for ~20s.
        var attempts = 0
        val maxAttempts = if (agentRuns?.isTerminalLive(taskId) == true) 400 else 60
        while (liveTerminal == null && attempts < maxAttempts) {
            delay(50)
            if (attempts % 10 == 0) {
                runCatching { agentRuns?.attachTerminalIfNeeded(taskId) }
            }
            liveTerminal = agentRuns?.terminalWidget(taskId)
            attempts++
        }
        if (liveTerminal != null) {
            historyText = null
            return@LaunchedEffect
        }
        if (agentRuns?.isTerminalLive(taskId) != true) {
            // Stale queued/running with no PTY — fall back to history instead of reconnecting.
            primaryAgentRuns?.reconcileStaleActiveTaskIfNeeded(taskId)
            openHistoryIfAvailable()
        }
    }

    val liveToDispose = rememberUpdatedState(liveTerminal)
    DisposableEffect(taskId) {
        onDispose {
            liveToDispose.value?.let { widget ->
                runCatching { onSwingEdt { widget.dispose() } }
            }
        }
    }

    val acceptsLiveDrops = sessionActive && liveTerminal != null
    var imageDragActive by remember(taskId) { mutableStateOf(false) }

    LaunchedEffect(taskId, sessionActive) {
        if (!sessionActive) imageDragActive = false
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

    val dropModifier = if (sessionActive) {
        Modifier.onImageFilesDropped(
            onFiles = { paths -> onTerminalImagesDropped.value(paths) },
            onDragActiveChange = { active -> onDragActiveChange.value(active) },
        )
    } else {
        Modifier
    }

    val dragBorderModifier = if (imageDragActive) {
        Modifier.border(2.dp, Cyan, RoundedCornerShape(AndyRadius.R3))
    } else {
        Modifier
    }

    when {
        liveTerminal != null -> {
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
                    if (!suppressHeavyweight) {
                        key(taskId) {
                            var swingDropTarget by remember(taskId) { mutableStateOf<DropTarget?>(null) }
                            var swingDropHost by remember(taskId) { mutableStateOf<Component?>(null) }
                            DisposableEffect(taskId) {
                                onDispose {
                                    runCatching {
                                        onSwingEdt {
                                            swingDropHost?.dropTarget = null
                                        }
                                    }
                                    swingDropTarget = null
                                    swingDropHost = null
                                }
                            }
                            SwingPanel(
                                modifier = Modifier.fillMaxSize(),
                                background = terminalPanelBackground,
                                factory = {
                                    liveTerminal!!.apply {
                                        if (acceptsLiveDrops) {
                                            SwingUtilities.invokeLater { requestFocusInWindow() }
                                        }
                                    }
                                },
                                update = { terminalWidget ->
                                    if (!acceptsLiveDrops || swingDropTarget != null) return@SwingPanel
                                    val host = terminalWidget.parent ?: terminalWidget
                                    swingDropHost = host
                                    swingDropTarget = host.installImageDropTarget(
                                        onFiles = { paths -> onTerminalImagesDropped.value(paths) },
                                        onDragActiveChange = { active -> onDragActiveChange.value(active) },
                                    )
                                },
                            )
                        }
                    } else {
                        Box(Modifier.fillMaxSize().background(terminalPanelBackground))
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

        historyText != null -> {
            val scroll = rememberScrollState()
            LaunchedEffect(historyText) {
                scroll.scrollTo(scroll.maxValue)
            }
            Box(
                modifier = modifier
                    .background(terminalPanelBackground)
                    .then(dropModifier)
                    .then(dragBorderModifier),
            ) {
                SelectionContainer {
                    Text(
                        text = historyText!!,
                        color = TextPrimary,
                        fontFamily = MonoFont,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(16.dp)
                            .fillMaxWidth(),
                    )
                }
            }
        }

        else -> {
            Box(
                modifier = modifier
                    .background(terminalPanelBackground)
                    .then(dropModifier)
                    .then(dragBorderModifier),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(
                        when {
                            sessionActive -> "Waiting for terminal…"
                            else -> "Terminal session ended"
                        },
                        color = TextSecondary,
                        fontFamily = MonoFont,
                        fontSize = 13.sp,
                    )
                    Text(
                        when {
                            imageDragActive -> "release to stage image for your next message"
                            sessionActive -> "Connecting to the live provider CLI for this chat"
                            else -> "Send a follow-up below to reopen the interactive CLI"
                        },
                        color = if (imageDragActive) Cyan else TextSecondary.copy(alpha = 0.72f),
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}
