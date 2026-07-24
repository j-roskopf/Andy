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
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import app.andy.desktop.service.DesktopActionRunService
import app.andy.desktop.service.DesktopWorkspaceStore
import app.andy.model.TerminalThemePreset
import app.andy.model.WorkspaceState
import app.andy.model.normalizeTerminalHex
import app.andy.model.terminalHexArgb
import app.andy.service.AndyServices
import app.andy.ui.shell.LocalSuppressHeavyweightSurfaces
import kotlinx.coroutines.flow.MutableStateFlow
import javax.swing.SwingUtilities

private val NoWorkspace = MutableStateFlow(WorkspaceState())

@Composable
actual fun ProjectTerminalSurface(
    services: AndyServices,
    runId: String,
    modifier: Modifier,
) {
    val suppressHeavyweight = LocalSuppressHeavyweightSurfaces.current
    val terminal = (services.actionRuns as? DesktopActionRunService)?.terminalWidget(runId)
    if (terminal == null) return

    val workspaceStore = services.workspaceStore as? DesktopWorkspaceStore
    val workspaceFlow = remember(workspaceStore) { workspaceStore?.state ?: NoWorkspace }
    val workspace by workspaceFlow.collectAsState()
    val terminalPanelBackground = remember(workspace.terminalBackgroundHex) {
        Color(
            terminalHexArgb(
                normalizeTerminalHex(
                    workspace.terminalBackgroundHex,
                    TerminalThemePreset.Andy.backgroundHex,
                ),
            ),
        )
    }

    // SwingPanel always paints above Compose popups and punches a BlendMode.Clear hole in the
    // Skia layer. Hiding only the JediTerm child leaves the host JPanel (system white) in that
    // hole and still covers chrome DropdownMenus — so tear the interop down while menus are
    // open and keep a matching Compose placeholder in its place.
    Box(modifier.background(terminalPanelBackground)) {
        if (!suppressHeavyweight) {
            key(runId) {
                SwingPanel(
                    modifier = Modifier.fillMaxSize(),
                    background = terminalPanelBackground,
                    factory = {
                        terminal.apply {
                            SwingUtilities.invokeLater { requestFocusInWindow() }
                        }
                    },
                    update = {},
                )
            }
        }
    }
}
