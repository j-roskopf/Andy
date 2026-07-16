package app.andy.ui.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import app.andy.desktop.service.DesktopActionRunService
import app.andy.service.AndyServices
import app.andy.ui.shell.LocalSuppressHeavyweightSurfaces
import javax.swing.SwingUtilities

private val TerminalPanelBackground = Color(0xFF11100D)

@Composable
actual fun ProjectTerminalSurface(
    services: AndyServices,
    runId: String,
    modifier: Modifier,
) {
    val suppressHeavyweight = LocalSuppressHeavyweightSurfaces.current
    val terminal = (services.actionRuns as? DesktopActionRunService)?.terminalWidget(runId)
    if (terminal == null) return

    // SwingPanel always paints above Compose popups and punches a BlendMode.Clear hole in the
    // Skia layer. Hiding only the JediTerm child leaves the host JPanel (system white) in that
    // hole and still covers TopChrome DropdownMenus — so tear the interop down while menus are
    // open and keep a matching Compose placeholder in its place.
    Box(modifier.background(TerminalPanelBackground)) {
        if (!suppressHeavyweight) {
            key(runId) {
                SwingPanel(
                    modifier = Modifier.fillMaxSize(),
                    background = TerminalPanelBackground,
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
