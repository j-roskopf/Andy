package app.andy.ui.agents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import app.andy.desktop.service.McpAgentRunClient
import app.andy.desktop.service.agents.DesktopAgentRunService
import app.andy.installImageDropTarget
import app.andy.model.WorkspaceState
import app.andy.model.toTerminalAppearance
import app.andy.onImageFilesDropped
import app.andy.service.AndyServices
import app.andy.terminal.LiveTerminalWheelHandler
import app.andy.terminal.PendingHistoryScroll
import app.andy.terminal.disposeScrollbackReplayTerminal
import app.andy.terminal.onSwingEdt
import app.andy.terminal.panelBackgroundArgb
import app.andy.ui.shell.LocalSuppressHeavyweightSurfaces
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.TextSecondary
import io.github.ketraterm.ui.swing.api.SwingTerminal
import java.awt.dnd.DropTarget
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val NoSessionsRevision = MutableStateFlow(0L)
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
    val scope = rememberCoroutineScope()
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
    val sessionsRevision by revisionFlow.collectAsState()
    // The caller owns this decision. Never re-derive it from raw tmux liveness — a session
    // that outlived a previous app run is alive but must still open read-only.
    val effectiveSessionActive = sessionActive

    var liveTerminal by remember(taskId) { mutableStateOf<SwingTerminal?>(null) }
    var historyTerminal by remember(taskId) { mutableStateOf<SwingTerminal?>(null) }
    // Live agent TUIs own the alt screen, so wheel events never reveal emulator history.
    // Peek Andy's latest persisted scrollback instead, then return with "follow live".
    var browsingLiveHistory by remember(taskId) { mutableStateOf(false) }
    var openingLiveHistory by remember(taskId) { mutableStateOf(false) }
    val pendingHistoryScroll = remember(taskId) { PendingHistoryScroll() }
    val releaseViewer = remember(agentRuns) { agentRuns?.let { runs -> runs::releaseTerminalViewer } }

    // Re-query the mounted widget when the session set changes. Deliberately separate from
    // the attach effect below, which must NOT be keyed on sessionsRevision: a successful
    // attach bumps the revision itself, so keying the attach work on it made attaching
    // cancel and restart its own coroutine — stranding the tmux client and KetraTerm
    // emulator it had already spawned, once per navigation, for the life of the process.
    LaunchedEffect(taskId, effectiveSessionActive, sessionsRevision) {
        if (!effectiveSessionActive) return@LaunchedEffect
        liveTerminal = agentRuns?.terminalWidget(taskId)
    }

    LaunchedEffect(taskId, effectiveSessionActive) {
        fun disposeHistory(widget: SwingTerminal?) {
            widget?.let { runCatching { disposeScrollbackReplayTerminal(it) } }
        }

        fun clearHistory() {
            disposeHistory(historyTerminal)
            historyTerminal = null
        }

        suspend fun openHistoryIfAvailable() {
            // Keep an existing replay mounted so resume/reattach does not flash through
            // an empty "Waiting for terminal…" gap before the live viewer arrives.
            if (historyTerminal != null) return
            if (agentRuns?.hasScrollback(taskId) != true) return
            val replay = withContext(Dispatchers.IO) {
                agentRuns.openScrollbackReplay(taskId)
            } ?: return
            if (!isActive) {
                disposeHistory(replay)
                return
            }
            historyTerminal = replay
        }

        if (!effectiveSessionActive) {
            // Finished chats: always read-only KetraTerm replay. Never attach a live
            // PTY/tmux viewer — that would accept typing despite the READ-ONLY badge.
            liveTerminal = null
            browsingLiveHistory = false
            releaseViewer?.invoke(taskId)
            openHistoryIfAvailable()
            return@LaunchedEffect
        }

        fun adoptLiveIfPresent(): Boolean {
            liveTerminal = agentRuns?.terminalWidget(taskId)
            if (liveTerminal == null) return false
            if (!browsingLiveHistory) clearHistory()
            return true
        }

        if (adoptLiveIfPresent()) return@LaunchedEffect

        // Prefer attaching a viewer to an already-live tmux session (no provider restart).
        runCatching { agentRuns?.attachTerminalIfNeeded(taskId) }
        if (adoptLiveIfPresent()) return@LaunchedEffect

        // Bridge with scrollback while the live viewer is still attaching — avoids the
        // Waiting placeholder flash that used to land between history teardown and mount.
        openHistoryIfAvailable()

        // Active/queued: wait for the live terminal (or a live tmux attach).
        // Short grace when nothing is live yet so stale "queued" chats fall back to
        // history quickly instead of spinning on reconnect for ~20s.
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
        // Live tmux with no mountable viewer, or stale queued with no PTY — keep history.
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
            // Keep the KetraTerm viewer alive across chat switches. Releasing here forced
            // null → history → live settle (and a SIGWINCH redraw) every time the inbox
            // selection changed. Foreground cadence is owned by setChatViewing; viewers are
            // dropped when the session ends or the handle is cleared.
            historyToDispose.value?.let { widget ->
                runCatching { disposeScrollbackReplayTerminal(widget) }
            }
        }
    }

    LaunchedEffect(taskId, liveTerminal) {
        if (liveTerminal == null) browsingLiveHistory = false
    }

    // History replay and the live PTY are both KetraTerm widgets, so peeking history
    // swaps the source without changing how the surface renders.
    val displayTerminal = if (browsingLiveHistory) {
        historyTerminal ?: liveTerminal
    } else {
        liveTerminal ?: historyTerminal
    }
    val acceptsLiveDrops = effectiveSessionActive && liveTerminal != null && !browsingLiveHistory
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

    fun openLiveHistoryPeek(scrollDelta: Double) {
        val runs = agentRuns ?: return
        if (scrollDelta <= 0.0) return
        if (browsingLiveHistory) {
            historyTerminal?.let { replay ->
                onSwingEdt { replay.scrollViewportBy(scrollDelta) }
            }
            return
        }
        pendingHistoryScroll.add(scrollDelta)
        if (openingLiveHistory) return
        openingLiveHistory = true
        scope.launch {
            var opened = false
            try {
                val replay = withContext(Dispatchers.IO) {
                    // The manager flushes the latest raw suffix, then reuses its on-demand
                    // replay state. Repeat peeks therefore emulate only output added since
                    // the previous peek instead of rebuilding the whole chat.
                    runs.openScrollbackReplay(taskId)
                } ?: return@launch
                if (!isActive) {
                    runCatching { disposeScrollbackReplayTerminal(replay) }
                    return@launch
                }
                val previousReplay = historyTerminal
                Snapshot.withMutableSnapshot {
                    historyTerminal = replay
                    browsingLiveHistory = true
                }
                val queuedScroll = pendingHistoryScroll.drain()
                previousReplay?.let { previous ->
                    runCatching { disposeScrollbackReplayTerminal(previous) }
                }
                // Honor one coalesced gesture after the Swing viewer is ready. Applying the
                // entire gesture backlog here would jump straight to the oldest row.
                onSwingEdt {
                    runCatching { replay.scrollToLiveViewport() }
                    runCatching { replay.scrollViewportBy(queuedScroll) }
                }
                opened = true
            } finally {
                Snapshot.withMutableSnapshot { openingLiveHistory = false }
                if (!opened) pendingHistoryScroll.clear()
            }
        }
    }

    fun returnToLiveTerminal() {
        val peek = historyTerminal
        Snapshot.withMutableSnapshot {
            browsingLiveHistory = false
            historyTerminal = null
        }
        pendingHistoryScroll.clear()
        peek?.let { widget -> runCatching { disposeScrollbackReplayTerminal(widget) } }
        val terminal = liveTerminal ?: return
        onSwingEdt {
            runCatching { terminal.scrollToLiveViewport() }
            runCatching { terminal.requestFocusInWindow() }
        }
    }

    val dropModifier = if (effectiveSessionActive) {
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

    // Stable interop host: never key(SwingPanel) on the terminal widget. Remounting the
    // AWT peer tears down the Skiko clear-hole, re-parents KetraTerm, and SIGWINCHes tmux —
    // that is the multi-flash on resume and on history↔live. Swap the child inside one host.
    val hostPanel = remember(taskId) {
        javax.swing.JPanel(java.awt.BorderLayout()).apply {
            isOpaque = true
        }
    }
    val awtPanelBackground = remember(terminalPanelBackground) {
        java.awt.Color(
            terminalPanelBackground.red,
            terminalPanelBackground.green,
            terminalPanelBackground.blue,
            terminalPanelBackground.alpha,
        )
    }
    val displayTerminalState = rememberUpdatedState(displayTerminal)
    val acceptsLiveDropsState = rememberUpdatedState(acceptsLiveDrops)

    DisposableEffect(taskId, hostPanel) {
        onDispose {
            runCatching {
                onSwingEdt {
                    hostPanel.dropTarget = null
                    hostPanel.removeAll()
                    hostPanel.revalidate()
                }
            }
        }
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
            if (!suppressHeavyweight) {
                key(taskId) {
                    var swingDropTarget by remember(taskId) { mutableStateOf<DropTarget?>(null) }
                    // Live TUIs own the alt screen — scrollViewportBy cannot reveal history
                    // there — so wheel-up opens Andy's flushed scrollback peek. While peeking,
                    // wheel-down at the bottom returns to the live PTY.
                    val wheelOverLive =
                        liveTerminal != null && effectiveSessionActive && !browsingLiveHistory
                    // A finished chat is always a read-only replay. Route its wheel input
                    // through the same explicit viewport controller as a live-history peek:
                    // the live→completed widget swap can otherwise leave KetraTerm's passive
                    // replay listener displaced, making the completed transcript feel stuck.
                    val wheelOverReplay = historyTerminal != null && displayTerminal === historyTerminal
                    val wheelOverHistoryPeek =
                        wheelOverReplay && browsingLiveHistory && liveTerminal != null
                    val openHistoryPeek = rememberUpdatedState<(Double) -> Unit>(
                        newValue = { delta -> openLiveHistoryPeek(delta) },
                    )
                    val followLive = rememberUpdatedState(newValue = { returnToLiveTerminal() })
                    DisposableEffect(
                        taskId,
                        displayTerminal,
                        wheelOverLive,
                        wheelOverReplay,
                        wheelOverHistoryPeek,
                    ) {
                        val terminal = displayTerminal
                        val wheelHandler = onSwingEdt {
                            when {
                                terminal == null -> null
                                wheelOverLive -> LiveTerminalWheelHandler(
                                    terminal = terminal,
                                    onOpenHistoryPeek = { delta -> openHistoryPeek.value.invoke(delta) },
                                )
                                wheelOverHistoryPeek -> LiveTerminalWheelHandler(
                                    terminal = terminal,
                                    onReturnToLive = { followLive.value.invoke() },
                                )
                                wheelOverReplay -> LiveTerminalWheelHandler(terminal = terminal)
                                else -> null
                            }
                        }
                        onDispose {
                            runCatching {
                                if (wheelHandler != null) {
                                    onSwingEdt { wheelHandler.uninstall() }
                                }
                            }
                            runCatching {
                                onSwingEdt { hostPanel.dropTarget = null }
                            }
                            swingDropTarget = null
                        }
                    }
                    SwingPanel(
                        modifier = Modifier.fillMaxSize(),
                        background = terminalPanelBackground,
                        factory = {
                            // Same host across suppress toggles — child stays attached so
                            // chrome-menu close is a reparent, not a KetraTerm rebuild.
                            hostPanel.apply {
                                background = awtPanelBackground
                                val child = displayTerminalState.value
                                if (child != null && (components.firstOrNull() !== child)) {
                                    removeAll()
                                    add(child, java.awt.BorderLayout.CENTER)
                                    revalidate()
                                }
                            }
                        },
                        update = { panel ->
                            panel.background = awtPanelBackground
                            val desired = displayTerminalState.value
                            val current = panel.components.firstOrNull()
                            if (current !== desired) {
                                panel.removeAll()
                                if (desired != null) {
                                    panel.add(desired, java.awt.BorderLayout.CENTER)
                                    if (acceptsLiveDropsState.value) {
                                        SwingUtilities.invokeLater { desired.requestFocusInWindow() }
                                    }
                                }
                                panel.revalidate()
                                panel.repaint()
                                swingDropTarget = null
                            }
                            if (acceptsLiveDropsState.value && swingDropTarget == null) {
                                swingDropTarget = panel.installImageDropTarget(
                                    onFiles = { paths -> onTerminalImagesDropped.value(paths) },
                                    onDragActiveChange = { active -> onDragActiveChange.value(active) },
                                )
                            } else if (!acceptsLiveDropsState.value && swingDropTarget != null) {
                                panel.dropTarget = null
                                swingDropTarget = null
                            }
                        },
                    )
                }
            } else {
                // Must tear the interop host down while chrome menus are open: a mounted
                // SwingPanel still punches BlendMode.Clear through overlapping DropdownMenus.
                Box(Modifier.fillMaxSize().background(terminalPanelBackground))
            }
            if (displayTerminal == null) {
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
            AnimatedVisibility(
                visible = liveTerminal != null && effectiveSessionActive && !browsingLiveHistory,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 12.dp),
            ) {
                Button(
                    onClick = { openLiveHistoryPeek(3.0) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(AndyRadius.Pill),
                ) {
                    Text(
                        "↑  history",
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            AnimatedVisibility(
                visible = browsingLiveHistory && liveTerminal != null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp),
            ) {
                Button(
                    onClick = ::returnToLiveTerminal,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                    shape = RoundedCornerShape(AndyRadius.Pill),
                ) {
                    Text(
                        "↓  follow live",
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
