package app.andy.desktop.service

import app.andy.model.ActionProject
import app.andy.model.ActionsConfig
import app.andy.model.ProjectAction
import app.andy.model.ProjectNote
import app.andy.service.ActionConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.Toml
import net.peanuuutz.tomlkt.TomlInline
import java.io.File

class DesktopActionConfigStore(
    private val file: File = File(System.getProperty("user.home"), ".andy/actions.toml"),
    private val starterFile: File? = File(System.getProperty("user.dir"), ".andy/actions.toml"),
) : ActionConfigStore {
    override suspend fun load(): ActionsConfig = withContext(Dispatchers.IO) {
        val starter = starterFile
            ?.takeIf { it.isFile }
            ?.let { source ->
                decode(source).getOrElse { ActionsConfig() }
                    .resolveRelativeProjectPaths(source.parentFile?.parentFile ?: File(System.getProperty("user.dir")))
            }
            ?: ActionsConfig()
        val personalFileExists = file.isFile
        var seedMissingProjects = !personalFileExists
        val personal = if (!personalFileExists) {
            ActionsConfig()
        } else {
            decode(file).getOrElse {
                file.copyTo(File(file.absolutePath + ".corrupt"), overwrite = true)
                // Unreadable personal config is not an intentional project deletion.
                seedMissingProjects = true
                ActionsConfig()
            }
        }
        personal.mergeMissingStarterActions(starter, seedMissingProjects = seedMissingProjects)
    }

    override suspend fun save(config: ActionsConfig): Unit = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        if (file.exists()) {
            file.copyTo(File(file.absolutePath + ".bak"), overwrite = true)
        }
        val content = Toml.encodeToString(ActionsFileDto.serializer(), config.toFileDto())
        file.writeText(content.trimEnd() + "\n")
    }

    private fun decode(source: File): Result<ActionsConfig> = runCatching {
        Toml { ignoreUnknownKeys = true }.decodeFromString(ActionsFileDto.serializer(), source.readText()).toModel()
    }
}

@Serializable
private data class ActionsFileDto(
    val version: Int = 1,
    val projects: List<ProjectDto> = emptyList(),
    val actions: List<ActionDto> = emptyList(),
    val notes: List<NoteDto> = emptyList(),
)

@Serializable
private data class ProjectDto(
    val id: String,
    val name: String,
    val contextDir: String,
    @TomlInline val env: Map<String, String> = emptyMap(),
)

@Serializable
private data class ActionDto(
    val id: String,
    val projectId: String,
    val name: String,
    val icon: String = "run",
    val command: String,
    val cwd: String = "",
    @TomlInline val env: Map<String, String> = emptyMap(),
)

@Serializable
private data class NoteDto(
    val id: String,
    val projectId: String,
    val title: String,
    val body: String = "",
    val completed: Boolean = false,
)

private fun ActionsFileDto.toModel(): ActionsConfig {
    val actionsByProject = actions.groupBy { it.projectId }
    val notesByProject = notes.groupBy { it.projectId }
    return ActionsConfig(
        projects = projects.map { project ->
            ActionProject(
                id = project.id,
                name = project.name,
                contextDir = project.contextDir,
                env = project.env,
                actions = actionsByProject[project.id].orEmpty().map { action ->
                    ProjectAction(
                        id = action.id,
                        name = action.name,
                        icon = action.icon,
                        command = action.command,
                        cwd = action.cwd.takeIf { it.isNotBlank() },
                        env = action.env,
                    )
                },
                notes = notesByProject[project.id].orEmpty().map { note ->
                    ProjectNote(
                        id = note.id,
                        title = note.title,
                        body = note.body,
                        completed = note.completed,
                    )
                },
            )
        },
    )
}

private fun ActionsConfig.toFileDto(): ActionsFileDto = ActionsFileDto(
    projects = projects.map { project ->
        ProjectDto(
            id = project.id,
            name = project.name,
            contextDir = project.contextDir,
            env = project.env,
        )
    },
    actions = projects.flatMap { project ->
        project.actions.map { action ->
            ActionDto(
                id = action.id,
                projectId = project.id,
                name = action.name,
                icon = action.icon,
                command = action.command,
                cwd = action.cwd.orEmpty(),
                env = action.env,
            )
        }
    },
    notes = projects.flatMap { project ->
        project.notes.map { note ->
            NoteDto(
                id = note.id,
                projectId = project.id,
                title = note.title,
                body = note.body,
                completed = note.completed,
            )
        }
    },
)

private fun ActionsConfig.resolveRelativeProjectPaths(workspace: File): ActionsConfig = copy(
    projects = projects.map { project ->
        val context = File(project.contextDir)
        if (context.isAbsolute) project else project.copy(
            contextDir = File(workspace, project.contextDir).toPath().normalize().toFile().absolutePath,
        )
    },
)

/**
 * Merges checkout-provided starter data without persisting it.
 *
 * Missing starter *projects* are only seeded when the user has no personal
 * actions.toml yet. Once that file exists, deleted projects stay deleted;
 * matching projects still receive any new starter actions in memory.
 */
private fun ActionsConfig.mergeMissingStarterActions(
    starter: ActionsConfig,
    seedMissingProjects: Boolean,
): ActionsConfig {
    val merged = projects.toMutableList()
    starter.projects.forEach { starterProject ->
        val existingIndex = merged.indexOfFirst { project ->
            project.contextDir == starterProject.contextDir || project.id == starterProject.id
        }
        if (existingIndex < 0) {
            if (seedMissingProjects) {
                merged += starterProject
            }
        } else {
            val existing = merged[existingIndex]
            val additions = starterProject.actions.filter { starterAction ->
                existing.actions.none { action ->
                    action.id == starterAction.id || action.command == starterAction.command
                }
            }
            if (additions.isNotEmpty()) {
                merged[existingIndex] = existing.copy(actions = existing.actions + additions)
            }
        }
    }
    return copy(projects = merged)
}
