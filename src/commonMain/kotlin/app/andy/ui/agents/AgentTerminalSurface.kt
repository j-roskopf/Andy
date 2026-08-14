package app.andy.ui.agents

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.andy.service.AndyServices

/**
 * Embedded agent CLI terminal (PTY). Desktop hosts the Rust VT canvas; other targets no-op.
 *
 * [sessionActive] is decided by the caller via [isChatTerminalInteractive]: true while
 * Andy is launching this chat and while this app run owns its live session. Everything
 * else — stopped, exited, or carried over from a previous app run — first tries one
 * reattach to the vendor CLI's own resume flag (a loading state shows while that's in
 * flight, never the transcript), and only falls back to a read-only scrollback replay
 * once reattach isn't possible or fails.
 */
@Composable
expect fun AgentTerminalSurface(
    services: AndyServices,
    taskId: String,
    sessionActive: Boolean,
    onImagesStaged: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier,
)
