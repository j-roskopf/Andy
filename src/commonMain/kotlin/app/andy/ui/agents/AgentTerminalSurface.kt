package app.andy.ui.agents

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.andy.service.AndyServices

/**
 * Embedded agent CLI terminal (PTY). Desktop hosts JediTerm/libghostty; other targets no-op.
 *
 * [sessionActive] is true while Andy expects a live PTY (queued/running/waiting).
 * Finished chats have no widget — the surface shows a reconnect hint instead of a blank pane.
 */
@Composable
expect fun AgentTerminalSurface(
    services: AndyServices,
    taskId: String,
    sessionActive: Boolean,
    onImagesStaged: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier,
)
