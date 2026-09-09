package app.andy.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import app.andy.model.ProjectAction
import app.andy.service.OpenAgentTaskRequest
import app.andy.service.OpenInvestigationRequest

/** Opens an agent chat from the surface a contextual action was launched from. */
val LocalOpenAgentTask = staticCompositionLocalOf<(OpenAgentTaskRequest) -> Unit> { {} }

/** Returns from an agent chat to the investigation behind it. */
val LocalOpenInvestigation = staticCompositionLocalOf<(OpenInvestigationRequest) -> Unit> { {} }

/**
 * Runs a project's shell or runbook actions in an arbitrary directory — the shell owns the
 * terminal docks, so surfaces deep in the tree (a chat's worktree card) reach them through here
 * instead of threading callbacks down. [dir] is normally a worktree path.
 */
class ProjectDirLauncher(
    val actionsFor: (projectId: String?) -> List<ProjectAction> = { emptyList() },
    val openTerminal: (projectId: String?, dir: String) -> Unit = { _, _ -> },
    val runAction: (projectId: String?, action: ProjectAction, dir: String) -> Unit = { _, _, _ -> },
)

val LocalProjectDirLauncher = staticCompositionLocalOf { ProjectDirLauncher() }
