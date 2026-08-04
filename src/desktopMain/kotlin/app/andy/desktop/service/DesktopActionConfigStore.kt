package app.andy.desktop.service

import app.andy.model.ActionProject
import app.andy.model.ActionsConfig
import app.andy.model.ConfigSource
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
    private val discoveryRootsProvider: () -> List<String> = { listOf(System.getProperty("user.dir")) },
) : ActionConfigStore {
    override suspend fun load(): ActionsConfig = withContext(Dispatchers.IO) {
        val personalFileExists = file.isFile
        val personal = if (!personalFileExists) {
            ActionsConfig()
        } else {
            decode(file, ConfigSource.Global).getOrElse {
                file.copyTo(File(file.absolutePath + ".corrupt"), overwrite = true)
                ActionsConfig()
            }
        }
        val discoveredFromProjects = personal.projects.mapNotNull { project ->
            loadRepoConfig(File(project.contextDir.normalizedContextDir()))
        }
        val discoveredFromWorkspace = runCatching { discoveryRootsProvider() }
            .getOrDefault(emptyList())
            .map { it.normalizedContextDir() }
            .distinct()
            .filter { rootPath ->
                personal.projects.none { project ->
                    project.contextDir.normalizedContextDir() == rootPath
                }
            }
            .mapNotNull { rootPath -> loadRepoConfig(File(rootPath)) }
        personal.mergeDiscoveredProjects(discoveredFromProjects + discoveredFromWorkspace)
    }

    private fun loadRepoConfig(rootDir: File): ActionsConfig? {
        val repoFile = File(rootDir, ".andy/actions.toml")
        if (!repoFile.isFile) return null
        val decoded = decode(repoFile, ConfigSource.Repo).getOrNull() ?: return null
        return decoded.resolveRelativeProjectPaths(rootDir).stripRepoEnv(repoFile)
    }

    override suspend fun save(config: ActionsConfig): Unit = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        if (file.exists()) {
            file.copyTo(File(file.absolutePath + ".bak"), overwrite = true)
        }
        val content = Toml.encodeToString(ActionsFileDto.serializer(), config.toFileDto())
        file.writeText(content.trimEnd() + "\n")
    }

    private fun decode(sourceFile: File, source: ConfigSource = ConfigSource.Global): Result<ActionsConfig> = runCatching {
        Toml { ignoreUnknownKeys = true }
            .decodeFromString(ActionsFileDto.serializer(), sourceFile.readText())
            .toModel(source)
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

private fun ActionsFileDto.toModel(source: ConfigSource = ConfigSource.Global): ActionsConfig {
    val actionsByProject = actions.groupBy { it.projectId }
    val notesByProject = notes.groupBy { it.projectId }
    return ActionsConfig(
        projects = projects.map { project ->
            ActionProject(
                id = project.id,
                name = project.name,
                contextDir = project.contextDir,
                env = project.env,
                source = source,
                actions = actionsByProject[project.id].orEmpty().map { action ->
                    ProjectAction(
                        id = action.id,
                        name = action.name,
                        icon = action.icon,
                        command = action.command,
                        cwd = action.cwd.takeIf { it.isNotBlank() },
                        env = action.env,
                        source = source,
                    )
                },
                notes = notesByProject[project.id].orEmpty().map { note ->
                    ProjectNote(
                        id = note.id,
                        title = note.title,
                        body = note.body,
                        completed = note.completed,
                        source = source,
                    )
                },
            )
        },
    )
}

private fun ActionsConfig.toFileDto(): ActionsFileDto {
    val globalProjects = projects.mapNotNull { project ->
        val globalActions = project.actions.filter { it.source == ConfigSource.Global }
        val globalNotes = project.notes.filter { it.source == ConfigSource.Global }
        if (project.source == ConfigSource.Global || globalActions.isNotEmpty() || globalNotes.isNotEmpty()) {
            project.copy(actions = globalActions, notes = globalNotes)
        } else {
            null
        }
    }
    return ActionsFileDto(
        projects = globalProjects.map { project ->
            ProjectDto(
                id = project.id,
                name = project.name,
                contextDir = project.contextDir,
                env = project.env,
            )
        },
        actions = globalProjects.flatMap { project ->
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
        notes = globalProjects.flatMap { project ->
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
}

private fun ActionsConfig.resolveRelativeProjectPaths(workspace: File): ActionsConfig = copy(
    projects = projects.map { project ->
        val context = File(project.contextDir)
        if (context.isAbsolute) project else project.copy(
            contextDir = File(workspace, project.contextDir).toPath().normalize().toFile().absolutePath,
        )
    },
)

private fun ActionsConfig.stripRepoEnv(sourceFile: File): ActionsConfig {
    var hadEnv = false
    val cleanedProjects = projects.map { project ->
        if (project.env.isNotEmpty()) hadEnv = true
        val cleanedActions = project.actions.map { action ->
            if (action.env.isNotEmpty()) hadEnv = true
            action.copy(env = emptyMap())
        }
        project.copy(env = emptyMap(), actions = cleanedActions)
    }
    if (hadEnv) {
        System.err.println("WARNING: Ignoring env in repo action config at ${sourceFile.absolutePath}")
    }
    return copy(projects = cleanedProjects)
}

private fun String.normalizedContextDir(): String =
    File(this).toPath().normalize().toFile().absolutePath

private fun ActionsConfig.mergeDiscoveredProjects(
    discoveredConfigs: List<ActionsConfig>,
): ActionsConfig {
    val merged = projects.toMutableList()
    discoveredConfigs.forEach { discoveredConfig ->
        discoveredConfig.projects.forEach { repoProject ->
            val repoContextDir = repoProject.contextDir.normalizedContextDir()
            val existingIndex = merged.indexOfFirst { project ->
                project.contextDir.normalizedContextDir() == repoContextDir || project.id == repoProject.id
            }
            if (existingIndex < 0) {
                merged += repoProject
            } else {
                val existing = merged[existingIndex]
                val actionAdditions = repoProject.actions.filter { repoAction ->
                    existing.actions.none { action ->
                        action.id == repoAction.id || action.command == repoAction.command
                    }
                }
                val noteAdditions = repoProject.notes.filter { repoNote ->
                    existing.notes.none { note ->
                        note.id == repoNote.id
                    }
                }
                if (actionAdditions.isNotEmpty() || noteAdditions.isNotEmpty()) {
                    merged[existingIndex] = existing.copy(
                        actions = existing.actions + actionAdditions,
                        notes = existing.notes + noteAdditions,
                    )
                }
            }
        }
    }
    return copy(projects = merged)
}
