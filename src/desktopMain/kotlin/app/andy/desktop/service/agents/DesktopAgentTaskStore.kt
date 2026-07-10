package app.andy.desktop.service.agents

import app.andy.model.AgentAutonomy
import app.andy.model.AgentKind
import app.andy.model.AgentReasoningEffort
import app.andy.model.AgentProviderDefaults
import app.andy.model.AgentSkill
import app.andy.model.AgentTask
import app.andy.model.AgentTaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.Toml
import net.peanuuutz.tomlkt.TomlInline
import java.io.File

data class AgentStoreState(
    val tasks: List<AgentTask> = emptyList(),
    val binaryOverrides: Map<String, String> = emptyMap(),
    val providerDefaults: Map<AgentKind, AgentProviderDefaults> = emptyMap(),
    val lastUsedAgent: AgentKind? = null,
    val maxConcurrent: Int = 8,
)

class DesktopAgentTaskStore(
    private val file: File = File(System.getProperty("user.home"), ".andy/agents.toml"),
) {
    val transcriptsDir: File = File(file.parentFile, "agents")

    fun transcriptFile(taskId: String): File = File(File(transcriptsDir, taskId), "transcript.jsonl")

    suspend fun load(): AgentStoreState = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext AgentStoreState()
        runCatching {
            Toml { ignoreUnknownKeys = true }.decodeFromString(AgentsFileDto.serializer(), file.readText()).toModel()
        }.getOrElse {
            file.copyTo(File(file.absolutePath + ".corrupt"), overwrite = true)
            AgentStoreState()
        }
    }

    suspend fun save(state: AgentStoreState): Unit = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        if (file.exists()) {
            file.copyTo(File(file.absolutePath + ".bak"), overwrite = true)
        }
        val content = Toml.encodeToString(AgentsFileDto.serializer(), state.toFileDto())
        file.writeText(content.trimEnd() + "\n")
    }

    suspend fun deleteTranscript(taskId: String): Unit = withContext(Dispatchers.IO) {
        File(transcriptsDir, taskId).deleteRecursively()
    }
}

@Serializable
private data class AgentsFileDto(
    val version: Int = 1,
    val maxConcurrent: Int = 8,
    @TomlInline val binaries: Map<String, String> = emptyMap(),
    val providerDefaults: List<AgentProviderDefaultsDto> = emptyList(),
    val lastUsedAgent: String = "",
    val tasks: List<AgentTaskDto> = emptyList(),
)

@Serializable
private data class AgentProviderDefaultsDto(
    val agent: String,
    val model: String = "",
    val reasoningEffort: String = "",
    val fastMode: Boolean = false,
    val autonomy: String = AgentAutonomy.Standard.name,
    val useWorktree: Boolean = false,
    val attachAndyMcp: Boolean = false,
    val maxBudgetUsd: Double = 0.0,
)

@Serializable
private data class AgentTaskDto(
    val id: String,
    val title: String,
    val prompt: String,
    val agent: String,
    val projectId: String = "",
    val cwd: String = "",
    val originDir: String = "",
    val useWorktree: Boolean = false,
    val worktreePath: String = "",
    val branchName: String = "",
    val attachAndyMcp: Boolean = false,
    val autonomy: String = AgentAutonomy.Standard.name,
    val model: String = "",
    val reasoningEffort: String = "",
    val fastMode: Boolean = false,
    val imagePaths: List<String> = emptyList(),
    val skillNames: List<String> = emptyList(),
    val skillPaths: List<String> = emptyList(),
    val maxBudgetUsd: Double = 0.0,
    val changeBaselinePaths: List<String> = emptyList(),
    val hasChangeBaseline: Boolean = false,
    val status: String = AgentTaskStatus.Unknown.name,
    val vendorSessionId: String = "",
    val createdAtMillis: Long,
    val startedAtMillis: Long = 0,
    val finishedAtMillis: Long = 0,
    val exitCode: Int = Int.MIN_VALUE,
    val errorMessage: String = "",
    val totalCostUsd: Double = 0.0,
    val costIsEstimated: Boolean = false,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
)

private fun AgentsFileDto.toModel(): AgentStoreState = AgentStoreState(
    tasks = tasks.mapNotNull { it.toModel() },
    binaryOverrides = binaries,
    providerDefaults = providerDefaults.mapNotNull { it.toModel() }.toMap(),
    lastUsedAgent = AgentKind.entries.firstOrNull { it.name == lastUsedAgent },
    maxConcurrent = maxConcurrent.coerceIn(1, 64),
)

private fun AgentProviderDefaultsDto.toModel(): Pair<AgentKind, AgentProviderDefaults>? {
    val kind = AgentKind.entries.firstOrNull { it.name == agent } ?: return null
    return kind to AgentProviderDefaults(
        model = model.takeIf { it.isNotBlank() },
        reasoningEffort = AgentReasoningEffort.entries.firstOrNull { it.name == reasoningEffort },
        fastMode = fastMode,
        autonomy = AgentAutonomy.entries.firstOrNull { it.name == autonomy } ?: AgentAutonomy.Standard,
        useWorktree = useWorktree,
        attachAndyMcp = attachAndyMcp,
        maxBudgetUsd = maxBudgetUsd.takeIf { it > 0 },
    )
}

private fun AgentTaskDto.toModel(): AgentTask? {
    val agentKind = AgentKind.entries.firstOrNull { it.name == agent } ?: return null
    val persistedStatus = AgentTaskStatus.entries.firstOrNull { it.name == status } ?: AgentTaskStatus.Unknown
    return AgentTask(
        id = id,
        title = title,
        prompt = prompt,
        agent = agentKind,
        projectId = projectId.takeIf { it.isNotBlank() },
        cwd = cwd.takeIf { it.isNotBlank() },
        originDir = originDir.takeIf { it.isNotBlank() },
        useWorktree = useWorktree,
        worktreePath = worktreePath.takeIf { it.isNotBlank() },
        branchName = branchName.takeIf { it.isNotBlank() },
        attachAndyMcp = attachAndyMcp,
        autonomy = AgentAutonomy.entries.firstOrNull { it.name == autonomy } ?: AgentAutonomy.Standard,
        model = model.takeIf { it.isNotBlank() },
        reasoningEffort = AgentReasoningEffort.entries.firstOrNull { it.name == reasoningEffort },
        fastMode = fastMode,
        imagePaths = imagePaths,
        skills = skillNames.zip(skillPaths).filter { (_, path) -> path.isNotBlank() }.map { (name, path) ->
            AgentSkill(name = name, description = "", path = path)
        },
        maxBudgetUsd = maxBudgetUsd.takeIf { it > 0 },
        changeBaselinePaths = changeBaselinePaths,
        hasChangeBaseline = hasChangeBaseline,
        // A task persisted as active belongs to a previous app run; its process is gone.
        status = if (persistedStatus == AgentTaskStatus.Queued || persistedStatus == AgentTaskStatus.Running) {
            AgentTaskStatus.Unknown
        } else {
            persistedStatus
        },
        vendorSessionId = vendorSessionId.takeIf { it.isNotBlank() },
        createdAtMillis = createdAtMillis,
        startedAtMillis = startedAtMillis.takeIf { it > 0 },
        finishedAtMillis = finishedAtMillis.takeIf { it > 0 },
        exitCode = exitCode.takeIf { it != Int.MIN_VALUE },
        errorMessage = errorMessage.takeIf { it.isNotBlank() },
        totalCostUsd = totalCostUsd.takeIf { it > 0 },
        costIsEstimated = costIsEstimated,
        inputTokens = inputTokens.takeIf { it > 0 },
        outputTokens = outputTokens.takeIf { it > 0 },
    )
}

private fun AgentStoreState.toFileDto(): AgentsFileDto = AgentsFileDto(
    maxConcurrent = maxConcurrent,
    binaries = binaryOverrides,
    lastUsedAgent = lastUsedAgent?.name.orEmpty(),
    providerDefaults = providerDefaults.entries.map { (agent, defaults) ->
        AgentProviderDefaultsDto(
            agent = agent.name,
            model = defaults.model.orEmpty(),
            reasoningEffort = defaults.reasoningEffort?.name.orEmpty(),
            fastMode = defaults.fastMode,
            autonomy = defaults.autonomy.name,
            useWorktree = defaults.useWorktree,
            attachAndyMcp = defaults.attachAndyMcp,
            maxBudgetUsd = defaults.maxBudgetUsd ?: 0.0,
        )
    },
    tasks = tasks.map { task ->
        AgentTaskDto(
            id = task.id,
            title = task.title,
            prompt = task.prompt,
            agent = task.agent.name,
            projectId = task.projectId.orEmpty(),
            cwd = task.cwd.orEmpty(),
            originDir = task.originDir.orEmpty(),
            useWorktree = task.useWorktree,
            worktreePath = task.worktreePath.orEmpty(),
            branchName = task.branchName.orEmpty(),
            attachAndyMcp = task.attachAndyMcp,
            autonomy = task.autonomy.name,
            model = task.model.orEmpty(),
            reasoningEffort = task.reasoningEffort?.name.orEmpty(),
            fastMode = task.fastMode,
            imagePaths = task.imagePaths,
            skillNames = task.skills.map { it.name },
            skillPaths = task.skills.map { it.path },
            maxBudgetUsd = task.maxBudgetUsd ?: 0.0,
            changeBaselinePaths = task.changeBaselinePaths,
            hasChangeBaseline = task.hasChangeBaseline,
            status = task.status.name,
            vendorSessionId = task.vendorSessionId.orEmpty(),
            createdAtMillis = task.createdAtMillis,
            startedAtMillis = task.startedAtMillis ?: 0,
            finishedAtMillis = task.finishedAtMillis ?: 0,
            exitCode = task.exitCode ?: Int.MIN_VALUE,
            errorMessage = task.errorMessage.orEmpty(),
            totalCostUsd = task.totalCostUsd ?: 0.0,
            costIsEstimated = task.costIsEstimated,
            inputTokens = task.inputTokens ?: 0,
            outputTokens = task.outputTokens ?: 0,
        )
    },
)
