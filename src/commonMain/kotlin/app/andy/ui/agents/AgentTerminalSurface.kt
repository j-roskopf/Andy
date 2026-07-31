package app.andy.ui.agents

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.andy.service.AndyServices

/**
 * Embedded agent CLI terminal (PTY). Desktop hosts BossTerm; other targets no-op.
 *
 * [sessionActive] is decided by the caller via [isChatTerminalInteractive]: true while
 * Andy is launching this chat and while this app run owns its live session. Everything
 * else — stopped, exited, or carried over from a previous app run — replays scrollback
 * read-only and never auto-restarts the provider CLI (send a follow-up to reopen it).
 */
@Composable
expect fun AgentTerminalSurface(
    services: AndyServices,
    taskId: String,
    sessionActive: Boolean,
    onImagesStaged: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier,
)
