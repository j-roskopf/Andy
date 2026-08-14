package app.andy.ui.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.andy.desktop.service.DesktopActionRunService
import app.andy.desktop.service.DesktopWorkspaceStore
import app.andy.model.WorkspaceState
import app.andy.model.panelBackgroundArgb
import app.andy.model.toTerminalAppearance
import app.andy.service.AndyServices
import app.andy.terminal.rust.RustTerminalCanvas
import kotlinx.coroutines.flow.MutableStateFlow

private val NoWorkspace = MutableStateFlow(WorkspaceState())

@Composable
actual fun ProjectTerminalSurface(
    services: AndyServices,
    runId: String,
    modifier: Modifier,
) {
    val actionRuns = services.actionRuns as? DesktopActionRunService
    val rustBackend = actionRuns?.rustTerminal(runId) ?: return

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
