package app.andy.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import app.andy.ui.theme.TextSecondary
import io.github.ketraterm.ui.swing.api.SwingTerminal
import java.awt.Component
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.awt.dnd.DropTarget
import javax.swing.SwingUtilities

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
    val agentRuns = services.agentRuns as? DesktopAgentRunService
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
    var reattachRequested by remember(taskId) { mutableStateOf(false) }

    LaunchedEffect(taskId, sessionActive, sessionsRevision, attachedIds, reattachRequested) {
        liveTerminal = agentRuns?.terminalWidget(taskId)
        if (liveTerminal != null) return@LaunchedEffect

        if (!sessionActive && !reattachRequested && agentRuns?.canReattachSession(taskId) == true) {
            reattachRequested = true
            agentRuns.reattachSession(taskId)
        }

        var attempts = 0
        while (liveTerminal == null && attempts < 400) {
            delay(50)
            liveTerminal = agentRuns?.terminalWidget(taskId)
            attempts++
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

    if (liveTerminal == null) {
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
                        reattachRequested || sessionActive -> "Reconnecting terminal…"
                        else -> "Terminal session ended"
                    },
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 13.sp,
                )
                Text(
                    when {
                        imageDragActive -> "release to stage image for your next message"
                        reattachRequested || sessionActive -> "Restoring the live provider CLI for this chat"
                        else -> "Send a follow-up below to reopen the interactive CLI"
                    },
                    color = if (imageDragActive) Cyan else TextSecondary.copy(alpha = 0.72f),
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                )
            }
        }
        return
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
