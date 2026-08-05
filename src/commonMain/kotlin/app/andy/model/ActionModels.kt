package app.andy.model

enum class ConfigSource { Global, Repo }

data class ActionProject(
    val id: String,
    val name: String,
    val contextDir: String,
    val env: Map<String, String> = emptyMap(),
    val actions: List<ProjectAction> = emptyList(),
    val notes: List<ProjectNote> = emptyList(),
    val source: ConfigSource = ConfigSource.Global,
)

data class ProjectAction(
    val id: String,
    val name: String,
    val icon: String = "run",
    val command: String,
    val cwd: String? = null,
    val env: Map<String, String> = emptyMap(),
    val source: ConfigSource = ConfigSource.Global,
)

data class ProjectNote(
    val id: String,
    val title: String,
    val body: String = "",
    val completed: Boolean = false,
    val source: ConfigSource = ConfigSource.Global,
)


data class ActionsConfig(
    val projects: List<ActionProject> = emptyList(),
)

fun ActionProject.rememberedActionId(lastActionIdByProject: Map<String, String>): String? =
    lastActionIdByProject[id]?.takeIf { remembered ->
        actions.any { it.id == remembered }
    } ?: actions.firstOrNull()?.id

enum class ActionRunStatus { Running, Exited, Failed, Stopped }

data class RunningAction(
    val runId: String,
    val projectId: String,
    val actionId: String,
    val actionName: String,
    val icon: String,
    val command: String,
    val cwd: String,
    val status: ActionRunStatus,
    val exitCode: Int? = null,
    val startedAtMillis: Long,
)
