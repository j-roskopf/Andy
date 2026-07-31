package app.andy.ui.actions

import ai.rever.bossterm.compose.EmbeddableTerminal
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
import app.andy.model.toTerminalAppearance
import app.andy.service.AndyServices
import app.andy.terminal.panelBackgroundArgb
import kotlinx.coroutines.flow.MutableStateFlow

private val NoWorkspace = MutableStateFlow(WorkspaceState())

@Composable
actual fun ProjectTerminalSurface(
    services: AndyServices,
    runId: String,
    modifier: Modifier,
) {
    val view = (services.actionRuns as? DesktopActionRunService)?.terminalView(runId)
    if (view == null) return

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
        key(runId, view.state) {
            EmbeddableTerminal(
                state = view.state,
                settingsOverride = view.settingsOverride,
                command = view.command,
                workingDirectory = view.workingDirectory,
                environment = view.environment,
                platformServices = view.platformServices,
                autoFocus = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
