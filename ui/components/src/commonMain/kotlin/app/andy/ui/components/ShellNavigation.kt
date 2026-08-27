package app.andy.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import app.andy.service.OpenAgentTaskRequest
import app.andy.service.OpenInvestigationRequest

/** Opens an agent chat from the surface a contextual action was launched from. */
val LocalOpenAgentTask = staticCompositionLocalOf<(OpenAgentTaskRequest) -> Unit> { {} }

/** Returns from an agent chat to the investigation behind it. */
val LocalOpenInvestigation = staticCompositionLocalOf<(OpenInvestigationRequest) -> Unit> { {} }
