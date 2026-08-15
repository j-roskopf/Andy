package app.andy.ui.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.desktop.service.DesktopActionRunService
import app.andy.desktop.service.DesktopWorkspaceStore
import app.andy.model.ActionRunStatus
import app.andy.model.RunningAction
import app.andy.model.WorkspaceState
import app.andy.model.panelBackgroundArgb
import app.andy.model.toTerminalAppearance
import app.andy.service.AndyServices
import app.andy.terminal.rust.RustTerminalCanvas
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.flow.MutableStateFlow

private val NoWorkspace = MutableStateFlow(WorkspaceState())
private val NoRunning = MutableStateFlow(emptyList<RunningAction>())

@Composable
actual fun ProjectTerminalSurface(
    services: AndyServices,
    runId: String,
    modifier: Modifier,
) {
    val actionRuns = services.actionRuns as? DesktopActionRunService
    // Observed (not a one-shot lookup) so this recomposes once the PTY finishes spawning
    // in the background and the backend becomes available.
    val runningFlow = remember(actionRuns) { actionRuns?.running ?: NoRunning }
    val running by runningFlow.collectAsState()
    val rustBackend = actionRuns?.rustTerminal(runId)

    val workspaceStore = services.workspaceStore as? DesktopWorkspaceStore
    val workspaceFlow = remember(workspaceStore) { workspaceStore?.state ?: NoWorkspace }
    val workspace by workspaceFlow.collectAsState()
    val appearance = remember(workspace.terminalThemeId, workspace.terminalFontFamilyId, workspace.terminalFontSize) {
        workspace.toTerminalAppearance()
    }
    val terminalPanelBackground = remember(appearance) {
        Color(appearance.panelBackgroundArgb())
    }

    Box(modifier.background(terminalPanelBackground)) {
        if (rustBackend == null) {
            val starting = running.firstOrNull { it.runId == runId }?.status == ActionRunStatus.Starting
            if (starting) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = Rust)
                        Text(
                            "Starting terminal…",
                            color = TextSecondary,
                            fontFamily = MonoFont,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        } else {
            key(runId, "rust") {
                RustTerminalCanvas(
                    backend = rustBackend,
                    appearance = appearance,
                    autoFocus = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
