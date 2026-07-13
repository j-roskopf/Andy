package app.andy.ui.actions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import app.andy.desktop.service.DesktopActionRunService
import app.andy.service.AndyServices
import javax.swing.SwingUtilities

@Composable
actual fun ProjectTerminalSurface(
    services: AndyServices,
    runId: String,
    modifier: Modifier,
) {
    val terminal = (services.actionRuns as? DesktopActionRunService)?.terminalWidget(runId)
    if (terminal != null) {
        key(runId) {
            SwingPanel(
                modifier = modifier,
                background = Color(0xFF11100D),
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
