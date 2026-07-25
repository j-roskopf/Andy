package app.andy.ui.agents

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.andy.service.AndyServices

/**
 * Embedded agent CLI terminal (PTY). Desktop hosts KetraTerm; other targets no-op.
 *
 * [sessionActive] is true while Andy expects a live PTY (queued/running/waiting).
 * Finished chats prefer scrollback history when available; they do not auto-restart
 * the provider CLI (send a follow-up / resume to reopen interactively).
 */
@Composable
expect fun AgentTerminalSurface(
    services: AndyServices,
    taskId: String,
    sessionActive: Boolean,
    onImagesStaged: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier,
)
