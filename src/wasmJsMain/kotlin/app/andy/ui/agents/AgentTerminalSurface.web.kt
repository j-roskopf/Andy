package app.andy.ui.agents

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.andy.service.AndyServices

@Composable
actual fun AgentTerminalSurface(
    services: AndyServices,
    taskId: String,
    sessionActive: Boolean,
    onImagesStaged: (List<String>) -> Unit,
    modifier: Modifier,
) {
    // Web has no PTY host.
}
