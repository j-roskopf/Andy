package app.andy.ui.shell

import androidx.compose.runtime.staticCompositionLocalOf
import app.andy.service.OpenAgentTaskRequest
import app.andy.service.OpenInvestigationRequest

/**
 * Cross-screen navigation for contextual agent actions (§5). Both default to no-ops so panes
 * rendered outside the shell (screenshot fixtures, previews) stay inert.
 */

/** Opens an agent chat from the surface a contextual action was launched from. */
internal val LocalOpenAgentTask = staticCompositionLocalOf<(OpenAgentTaskRequest) -> Unit> { {} }

/** Returns from an agent chat to the investigation, event, and playback position behind it. */
internal val LocalOpenInvestigation = staticCompositionLocalOf<(OpenInvestigationRequest) -> Unit> { {} }
