package app.andy.desktop.service.agents

import app.andy.model.AgentAutonomy
import app.andy.model.AgentKind
import app.andy.model.AgentQuotaAccess
import app.andy.model.AgentReasoningEffort
import app.andy.model.AgentProviderDefaults
import app.andy.model.AgentQueuedFollowUp
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentSkill
import app.andy.model.AgentTask
import app.andy.model.AgentTaskStatus
import app.andy.model.AgentUserInputOption
import app.andy.model.AgentUserInputQuestion
import app.andy.model.AgentUserInputRequest
import app.andy.model.AgentThreadChangeSnapshot
import app.andy.model.AgentChangeSummary
import app.andy.model.AgentFileChange
import app.andy.model.AgentFileDiff
import app.andy.model.DiffLine
import app.andy.model.DiffLineKind
import app.andy.model.ProjectAgentProfile
import app.andy.model.ProjectPlanSnapshot
import app.andy.model.ProjectPlanVersion
import app.andy.model.ProjectReviewFinding
import app.andy.model.ProjectReviewFindingSeverity
import app.andy.model.ProjectReviewStatus
import app.andy.model.ProjectReviewVerdict
import app.andy.model.ProjectTask
import app.andy.model.ProjectTaskAttempt
import app.andy.model.ProjectTaskKind
import app.andy.model.ProjectTaskState
import app.andy.model.ProjectVerificationStatus
import app.andy.model.ProjectVerificationVerdict
import app.andy.model.ProjectWorkflowStage
import app.andy.model.ProjectWorkflowState
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
    val quotaAccess: AgentQuotaAccess = AgentQuotaAccess(),
    val lastUsedAgent: AgentKind? = null,
    val maxConcurrent: Int = 8,
    val projectWorkflows: Map<String, ProjectWorkflowState> = emptyMap(),
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
    val version: Int = 3,
    val maxConcurrent: Int = 8,
    @TomlInline val binaries: Map<String, String> = emptyMap(),
    val providerDefaults: List<AgentProviderDefaultsDto> = emptyList(),
    val quotaAccess: AgentQuotaAccessDto = AgentQuotaAccessDto(),
    val lastUsedAgent: String = "",
    val tasks: List<AgentTaskDto> = emptyList(),
    val projectWorkflows: List<ProjectWorkflowDto> = emptyList(),
)

@Serializable
private data class AgentQuotaAccessDto(
    val claudeAccountAccess: Boolean = false,
    val cursorAccountAccess: Boolean = false,
    val antigravityAccountAccess: Boolean = false,
)

@Serializable
private data class AgentProviderDefaultsDto(
    val agent: String,
    val model: String = "",
    val reasoningEffort: String = "",
    val fastMode: Boolean = false,
    val autonomy: String = AgentAutonomy.Standard.name,
    val sandboxMode: String = "",
    val planMode: Boolean = false,
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
    val sandboxMode: String = "",
    val planMode: Boolean = false,
    val completedPlanText: String = "",
    val implementationPrompt: String = "",
    val continuationPrompt: String = "",
    val model: String = "",
    val reasoningEffort: String = "",
    val fastMode: Boolean = false,
    val imagePaths: List<String> = emptyList(),
    val skillNames: List<String> = emptyList(),
    val skillPaths: List<String> = emptyList(),
    val goal: String = "",
    val queuedFollowUps: List<AgentQueuedFollowUpDto> = emptyList(),
    /** Kept while migrating the first queue implementation's single saved item. */
    val queuedFollowUp: String = "",
    val queuedFollowUpImagePaths: List<String> = emptyList(),
    val queuedFollowUpSkillNames: List<String> = emptyList(),
    val queuedFollowUpSkillPaths: List<String> = emptyList(),
    val userInputRequest: AgentUserInputRequestDto? = null,
    val maxBudgetUsd: Double = 0.0,
    val changeBaselinePaths: List<String> = emptyList(),
    val hasChangeBaseline: Boolean = false,
    val completedChanges: AgentThreadChangeSnapshotDto? = null,
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
    val contextTokens: Long = 0,
    val contextWindowTokens: Long = 0,
    val unread: Boolean = false,
    val ownsWorktree: Boolean = false,
    val workflowTaskId: String = "",
    val workflowStage: String = "",
    val workflowAttempt: Int = 0,
    val completedResultText: String = "",
)

@Serializable
private data class AgentUserInputRequestDto(
    val id: String,
    val questions: List<AgentUserInputQuestionDto>,
)

@Serializable
private data class AgentUserInputQuestionDto(
    val id: String,
    val header: String = "",
    val question: String,
    val options: List<AgentUserInputOptionDto>,
)

@Serializable
private data class AgentUserInputOptionDto(
    val label: String,
    val description: String = "",
)

@Serializable
private data class ProjectWorkflowDto(
    val projectId: String,
    val scratchpad: String = "",
    val profiles: List<ProjectRoleProfileDto> = emptyList(),
    val tasks: List<ProjectTaskDto> = emptyList(),
    val legacyNotesMigrated: Boolean = false,
)

@Serializable
private data class ProjectRoleProfileDto(
    val kind: String,
    val profile: ProjectAgentProfileDto,
)

@Serializable
private data class ProjectAgentProfileDto(
    val agent: String = AgentKind.Codex.name,
    val model: String = "",
    val reasoningEffort: String = "",
    val fastMode: Boolean = false,
    val autonomy: String = AgentAutonomy.Standard.name,
    val sandboxMode: String = "",
    val useWorktree: Boolean = false,
    val attachAndyMcp: Boolean = false,
    val maxBudgetUsd: Double = 0.0,
)

@Serializable
private data class ProjectTaskDto(
    val id: String,
    val projectId: String,
    val kind: String,
    val title: String,
    val instructions: String,
    val profile: ProjectAgentProfileDto,
    val includeScratchpad: Boolean = false,
    val state: String = ProjectTaskState.Draft.name,
    val linkedSpecTaskId: String = "",
    val linkedBuildTaskId: String = "",
    val linkedReviewTaskId: String = "",
    val linkedVerificationTaskId: String = "",
    val planVersions: List<ProjectPlanVersionDto> = emptyList(),
    val planSnapshot: ProjectPlanSnapshotDto? = null,
    val grillMeEnabled: Boolean = false,
    val buildNotes: String = "",
    val reviewEnabled: Boolean = false,
    val reviewInstructions: String = "",
    val reviewGeneration: Int = 0,
    val maxReviewFailures: Int = 5,
    val reviewReopenedCompleted: Boolean = false,
    val verificationInstructions: String = "",
    val maxVerificationAttempts: Int = 5,
    val maxBudgetUsd: Double = 0.0,
    val paused: Boolean = false,
    val workspacePath: String = "",
    val worktreePath: String = "",
    val branchName: String = "",
    val worktreeOwnerRunId: String = "",
    val attempts: List<ProjectTaskAttemptDto> = emptyList(),
    val reviewVerdicts: List<ProjectReviewVerdictDto> = emptyList(),
    val verdicts: List<ProjectVerificationVerdictDto> = emptyList(),
    val lastError: String = "",
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Serializable
private data class ProjectPlanVersionDto(
    val version: Int,
    val text: String,
    val runId: String,
    val createdAtMillis: Long,
)

@Serializable
private data class ProjectPlanSnapshotDto(
    val text: String,
    val sourceSpecTaskId: String = "",
    val sourceVersion: Int = 0,
    val sourceLabel: String = "external plan",
)

@Serializable
private data class ProjectTaskAttemptDto(
    val runId: String,
    val stage: String,
    val attempt: Int,
    val prompt: String,
    val profile: ProjectAgentProfileDto,
    val scratchpadSnapshot: String = "",
    val createdAtMillis: Long,
    val reviewedBuildRunId: String = "",
    val reviewGeneration: Int = 0,
)

@Serializable
private data class ProjectReviewVerdictDto(
    val status: String,
    val summary: String,
    val findings: List<ProjectReviewFindingDto> = emptyList(),
    val runId: String,
    val reviewedBuildRunId: String,
    val reviewGeneration: Int,
    val createdAtMillis: Long,
)

@Serializable
private data class ProjectReviewFindingDto(
    val severity: String,
    val title: String,
    val details: String,
    val file: String = "",
    val line: Int = 0,
)

@Serializable
private data class ProjectVerificationVerdictDto(
    val status: String,
    val summary: String,
    val evidence: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
    val runId: String,
    val createdAtMillis: Long,
    val reviewedBuildRunId: String = "",
    val reviewGeneration: Int = 0,
)

@Serializable
private data class AgentQueuedFollowUpDto(
    val text: String = "",
    val imagePaths: List<String> = emptyList(),
    val skillNames: List<String> = emptyList(),
    val skillPaths: List<String> = emptyList(),
)

@Serializable
private data class AgentThreadChangeSnapshotDto(
    val files: List<AgentFileChangeDto> = emptyList(),
    val diffs: List<AgentFileDiffDto> = emptyList(),
)

@Serializable
private data class AgentFileChangeDto(
    val path: String,
    val additions: Int,
    val deletions: Int,
)

@Serializable
private data class AgentFileDiffDto(
    val path: String,
    val lines: List<DiffLineDto> = emptyList(),
    val additions: Int = 0,
    val deletions: Int = 0,
    val isBinary: Boolean = false,
    val isNewFile: Boolean = false,
)

@Serializable
private data class DiffLineDto(
    val kind: String,
    val text: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null,
)

private fun AgentsFileDto.toModel(): AgentStoreState = AgentStoreState(
    tasks = tasks.mapNotNull { it.toModel() },
    binaryOverrides = binaries,
    providerDefaults = providerDefaults.mapNotNull { it.toModel() }.toMap(),
    quotaAccess = AgentQuotaAccess(
        claudeAccountAccess = quotaAccess.claudeAccountAccess,
        cursorAccountAccess = quotaAccess.cursorAccountAccess,
        antigravityAccountAccess = quotaAccess.antigravityAccountAccess,
    ),
    lastUsedAgent = AgentKind.entries.firstOrNull { it.name == lastUsedAgent },
    maxConcurrent = maxConcurrent.coerceIn(1, 64),
    projectWorkflows = projectWorkflows.map { it.toModel() }.associateBy { it.projectId },
)

private fun AgentProviderDefaultsDto.toModel(): Pair<AgentKind, AgentProviderDefaults>? {
    val kind = AgentKind.entries.firstOrNull { it.name == agent } ?: return null
    return kind to AgentProviderDefaults(
        model = model.takeIf { it.isNotBlank() },
        reasoningEffort = AgentReasoningEffort.entries.firstOrNull { it.name == reasoningEffort },
        fastMode = fastMode,
        autonomy = AgentAutonomy.entries.firstOrNull { it.name == autonomy } ?: AgentAutonomy.Standard,
        sandboxMode = AgentSandboxMode.entries.firstOrNull { it.name == sandboxMode },
        planMode = planMode,
        useWorktree = useWorktree,
        attachAndyMcp = attachAndyMcp,
        maxBudgetUsd = maxBudgetUsd.takeIf { it > 0 },
    )
}

private fun AgentTaskDto.toModel(): AgentTask? {
    val agentKind = AgentKind.entries.firstOrNull { it.name == agent } ?: return null
    val persistedStatus = AgentTaskStatus.entries.firstOrNull { it.name == status } ?: AgentTaskStatus.Unknown
    val legacyQueuedFollowUp = queuedFollowUp.takeIf { it.isNotBlank() || queuedFollowUpImagePaths.isNotEmpty() }?.let { text ->
        AgentQueuedFollowUp(
            text = text,
            imagePaths = queuedFollowUpImagePaths,
            skills = queuedFollowUpSkillNames.zip(queuedFollowUpSkillPaths)
                .filter { (_, path) -> path.isNotBlank() }
                .map { (name, path) -> AgentSkill(name = name, description = "", path = path) },
        )
    }
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
        ownsWorktree = ownsWorktree || (useWorktree && worktreePath.isNotBlank()),
        workflowTaskId = workflowTaskId.takeIf { it.isNotBlank() },
        workflowStage = ProjectWorkflowStage.entries.firstOrNull { it.name == workflowStage },
        workflowAttempt = workflowAttempt.takeIf { it > 0 },
        attachAndyMcp = attachAndyMcp,
        autonomy = AgentAutonomy.entries.firstOrNull { it.name == autonomy } ?: AgentAutonomy.Standard,
        sandboxMode = AgentSandboxMode.entries.firstOrNull { it.name == sandboxMode },
        planMode = planMode,
        completedPlanText = completedPlanText.takeIf { it.isNotBlank() },
        implementationPrompt = implementationPrompt.takeIf { it.isNotBlank() },
        continuationPrompt = continuationPrompt.takeIf { it.isNotBlank() },
        completedResultText = completedResultText.takeIf { it.isNotBlank() },
        model = model.takeIf { it.isNotBlank() },
        reasoningEffort = AgentReasoningEffort.entries.firstOrNull { it.name == reasoningEffort },
        fastMode = fastMode,
        imagePaths = imagePaths,
        skills = skillNames.zip(skillPaths).filter { (_, path) -> path.isNotBlank() }.map { (name, path) ->
            AgentSkill(name = name, description = "", path = path)
        },
        goal = goal.takeIf { it.isNotBlank() },
        queuedFollowUps = queuedFollowUps.mapNotNull { queued ->
            queued.text.takeIf { it.isNotBlank() || queued.imagePaths.isNotEmpty() }?.let { text ->
                AgentQueuedFollowUp(
                    text = text,
                    imagePaths = queued.imagePaths,
                    skills = queued.skillNames.zip(queued.skillPaths)
                    .filter { (_, path) -> path.isNotBlank() }
                    .map { (name, path) -> AgentSkill(name = name, description = "", path = path) },
                )
            }
        } + listOfNotNull(legacyQueuedFollowUp),
        userInputRequest = userInputRequest?.toModel(),
        maxBudgetUsd = maxBudgetUsd.takeIf { it > 0 },
        changeBaselinePaths = changeBaselinePaths,
        hasChangeBaseline = hasChangeBaseline,
        completedChanges = completedChanges?.toModel(),
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
        contextTokens = contextTokens.takeIf { it > 0 },
        contextWindowTokens = contextWindowTokens.takeIf { it > 0 },
        unread = unread,
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
            sandboxMode = defaults.sandboxMode?.name.orEmpty(),
            planMode = defaults.planMode,
            useWorktree = defaults.useWorktree,
            attachAndyMcp = defaults.attachAndyMcp,
            maxBudgetUsd = defaults.maxBudgetUsd ?: 0.0,
        )
    },
    quotaAccess = AgentQuotaAccessDto(
        claudeAccountAccess = quotaAccess.claudeAccountAccess,
        cursorAccountAccess = quotaAccess.cursorAccountAccess,
        antigravityAccountAccess = quotaAccess.antigravityAccountAccess,
    ),
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
            ownsWorktree = task.ownsWorktree,
            workflowTaskId = task.workflowTaskId.orEmpty(),
            workflowStage = task.workflowStage?.name.orEmpty(),
            workflowAttempt = task.workflowAttempt ?: 0,
            attachAndyMcp = task.attachAndyMcp,
            autonomy = task.autonomy.name,
            sandboxMode = task.sandboxMode?.name.orEmpty(),
            planMode = task.planMode,
            completedPlanText = task.completedPlanText.orEmpty(),
            implementationPrompt = task.implementationPrompt.orEmpty(),
            continuationPrompt = task.continuationPrompt.orEmpty(),
            completedResultText = task.completedResultText.orEmpty(),
            model = task.model.orEmpty(),
            reasoningEffort = task.reasoningEffort?.name.orEmpty(),
            fastMode = task.fastMode,
            imagePaths = task.imagePaths,
            skillNames = task.skills.map { it.name },
            skillPaths = task.skills.map { it.path },
            goal = task.goal.orEmpty(),
            queuedFollowUps = task.queuedFollowUps.map { queued ->
                AgentQueuedFollowUpDto(
                    text = queued.text,
                    imagePaths = queued.imagePaths,
                    skillNames = queued.skills.map { it.name },
                    skillPaths = queued.skills.map { it.path },
                )
            },
            userInputRequest = task.userInputRequest?.toDto(),
            maxBudgetUsd = task.maxBudgetUsd ?: 0.0,
            changeBaselinePaths = task.changeBaselinePaths,
            hasChangeBaseline = task.hasChangeBaseline,
            completedChanges = task.completedChanges?.toDto(),
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
            contextTokens = task.contextTokens ?: 0,
            contextWindowTokens = task.contextWindowTokens ?: 0,
            unread = task.unread,
        )
    },
    projectWorkflows = projectWorkflows.values.map { it.toDto() },
)

private fun AgentUserInputRequestDto.toModel(): AgentUserInputRequest? {
    val parsedQuestions = questions.mapNotNull { question ->
        val id = question.id.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val text = question.question.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val options = question.options.mapNotNull { option ->
            option.label.trim().takeIf { it.isNotBlank() }?.let { label ->
                AgentUserInputOption(label, option.description.trim())
            }
        }
        if (options.size !in 2..3) return@mapNotNull null
        AgentUserInputQuestion(id, question.header.trim(), text, options)
    }
    return AgentUserInputRequest(id = id, questions = parsedQuestions.takeIf { it.isNotEmpty() } ?: return null)
}

private fun AgentUserInputRequest.toDto() = AgentUserInputRequestDto(
    id = id,
    questions = questions.map { question ->
        AgentUserInputQuestionDto(
            id = question.id,
            header = question.header,
            question = question.question,
            options = question.options.map { option -> AgentUserInputOptionDto(option.label, option.description) },
        )
    },
)

private fun ProjectWorkflowDto.toModel(): ProjectWorkflowState = ProjectWorkflowState(
    projectId = projectId,
    scratchpad = scratchpad,
    profiles = profiles.mapNotNull { role ->
        ProjectTaskKind.entries.firstOrNull { it.name == role.kind }?.let { it to role.profile.toModel() }
    }.toMap(),
    tasks = tasks.mapNotNull { it.toModel() },
    legacyNotesMigrated = legacyNotesMigrated,
)

private fun ProjectAgentProfileDto.toModel(): ProjectAgentProfile = ProjectAgentProfile(
    agent = AgentKind.entries.firstOrNull { it.name == agent } ?: AgentKind.Codex,
    model = model.takeIf { it.isNotBlank() },
    reasoningEffort = AgentReasoningEffort.entries.firstOrNull { it.name == reasoningEffort },
    fastMode = fastMode,
    autonomy = AgentAutonomy.entries.firstOrNull { it.name == autonomy } ?: AgentAutonomy.Standard,
    sandboxMode = AgentSandboxMode.entries.firstOrNull { it.name == sandboxMode },
    useWorktree = useWorktree,
    attachAndyMcp = attachAndyMcp,
    maxBudgetUsd = maxBudgetUsd.takeIf { it > 0 },
)

private fun ProjectTaskDto.toModel(): ProjectTask? {
    val taskKind = ProjectTaskKind.entries.firstOrNull { it.name == kind } ?: return null
    return ProjectTask(
        id = id,
        projectId = projectId,
        kind = taskKind,
        title = title,
        instructions = instructions,
        profile = profile.toModel(),
        includeScratchpad = includeScratchpad,
        state = ProjectTaskState.entries.firstOrNull { it.name == state } ?: ProjectTaskState.Draft,
        linkedSpecTaskId = linkedSpecTaskId.takeIf { it.isNotBlank() },
        linkedBuildTaskId = linkedBuildTaskId.takeIf { it.isNotBlank() },
        linkedReviewTaskId = linkedReviewTaskId.takeIf { it.isNotBlank() },
        linkedVerificationTaskId = linkedVerificationTaskId.takeIf { it.isNotBlank() },
        planVersions = planVersions.map { ProjectPlanVersion(it.version, it.text, it.runId, it.createdAtMillis) },
        planSnapshot = planSnapshot?.let {
            ProjectPlanSnapshot(
                text = it.text,
                sourceSpecTaskId = it.sourceSpecTaskId.takeIf(String::isNotBlank),
                sourceVersion = it.sourceVersion.takeIf { version -> version > 0 },
                sourceLabel = it.sourceLabel,
            )
        },
        grillMeEnabled = grillMeEnabled,
        buildNotes = buildNotes,
        reviewEnabled = reviewEnabled,
        reviewInstructions = reviewInstructions,
        reviewGeneration = reviewGeneration.coerceAtLeast(0),
        maxReviewFailures = maxReviewFailures.coerceIn(1, 20),
        reviewReopenedCompleted = reviewReopenedCompleted,
        verificationInstructions = verificationInstructions,
        maxVerificationAttempts = maxVerificationAttempts.coerceIn(1, 20),
        maxBudgetUsd = maxBudgetUsd.takeIf { it > 0 },
        paused = paused,
        workspacePath = workspacePath.takeIf { it.isNotBlank() },
        worktreePath = worktreePath.takeIf { it.isNotBlank() },
        branchName = branchName.takeIf { it.isNotBlank() },
        worktreeOwnerRunId = worktreeOwnerRunId.takeIf { it.isNotBlank() },
        attempts = attempts.mapNotNull { attempt ->
            ProjectWorkflowStage.entries.firstOrNull { it.name == attempt.stage }?.let { stage ->
                ProjectTaskAttempt(
                    runId = attempt.runId,
                    stage = stage,
                    attempt = attempt.attempt,
                    prompt = attempt.prompt,
                    profile = attempt.profile.toModel(),
                    scratchpadSnapshot = attempt.scratchpadSnapshot.takeIf { it.isNotBlank() },
                    createdAtMillis = attempt.createdAtMillis,
                    reviewedBuildRunId = attempt.reviewedBuildRunId.takeIf { it.isNotBlank() },
                    reviewGeneration = attempt.reviewGeneration.coerceAtLeast(0),
                )
            }
        },
        reviewVerdicts = reviewVerdicts.mapNotNull { verdict ->
            val status = ProjectReviewStatus.entries.firstOrNull { it.name == verdict.status } ?: return@mapNotNull null
            ProjectReviewVerdict(
                status = status,
                summary = verdict.summary,
                findings = verdict.findings.mapNotNull { finding ->
                    val severity = ProjectReviewFindingSeverity.entries.firstOrNull { it.name == finding.severity }
                        ?: return@mapNotNull null
                    ProjectReviewFinding(
                        severity = severity,
                        title = finding.title,
                        details = finding.details,
                        file = finding.file.takeIf { it.isNotBlank() },
                        line = finding.line.takeIf { it > 0 },
                    )
                },
                runId = verdict.runId,
                reviewedBuildRunId = verdict.reviewedBuildRunId,
                reviewGeneration = verdict.reviewGeneration,
                createdAtMillis = verdict.createdAtMillis,
            )
        },
        verdicts = verdicts.mapNotNull { verdict ->
            ProjectVerificationStatus.entries.firstOrNull { it.name == verdict.status }?.let { status ->
                ProjectVerificationVerdict(
                    status = status,
                    summary = verdict.summary,
                    evidence = verdict.evidence,
                    failures = verdict.failures,
                    runId = verdict.runId,
                    createdAtMillis = verdict.createdAtMillis,
                    reviewedBuildRunId = verdict.reviewedBuildRunId.takeIf { it.isNotBlank() },
                    reviewGeneration = verdict.reviewGeneration.coerceAtLeast(0),
                )
            }
        },
        lastError = lastError.takeIf { it.isNotBlank() },
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )
}

private fun ProjectWorkflowState.toDto(): ProjectWorkflowDto = ProjectWorkflowDto(
    projectId = projectId,
    scratchpad = scratchpad,
    profiles = profiles.map { (kind, profile) -> ProjectRoleProfileDto(kind.name, profile.toDto()) },
    tasks = tasks.map { it.toDto() },
    legacyNotesMigrated = legacyNotesMigrated,
)

private fun ProjectAgentProfile.toDto(): ProjectAgentProfileDto = ProjectAgentProfileDto(
    agent = agent.name,
    model = model.orEmpty(),
    reasoningEffort = reasoningEffort?.name.orEmpty(),
    fastMode = fastMode,
    autonomy = autonomy.name,
    sandboxMode = sandboxMode?.name.orEmpty(),
    useWorktree = useWorktree,
    attachAndyMcp = attachAndyMcp,
    maxBudgetUsd = maxBudgetUsd ?: 0.0,
)

private fun ProjectTask.toDto(): ProjectTaskDto = ProjectTaskDto(
    id = id,
    projectId = projectId,
    kind = kind.name,
    title = title,
    instructions = instructions,
    profile = profile.toDto(),
    includeScratchpad = includeScratchpad,
    state = state.name,
    linkedSpecTaskId = linkedSpecTaskId.orEmpty(),
    linkedBuildTaskId = linkedBuildTaskId.orEmpty(),
    linkedReviewTaskId = linkedReviewTaskId.orEmpty(),
    linkedVerificationTaskId = linkedVerificationTaskId.orEmpty(),
    planVersions = planVersions.map { ProjectPlanVersionDto(it.version, it.text, it.runId, it.createdAtMillis) },
    planSnapshot = planSnapshot?.let { ProjectPlanSnapshotDto(it.text, it.sourceSpecTaskId.orEmpty(), it.sourceVersion ?: 0, it.sourceLabel) },
    grillMeEnabled = grillMeEnabled,
    buildNotes = buildNotes,
    reviewEnabled = reviewEnabled,
    reviewInstructions = reviewInstructions,
    reviewGeneration = reviewGeneration,
    maxReviewFailures = maxReviewFailures,
    reviewReopenedCompleted = reviewReopenedCompleted,
    verificationInstructions = verificationInstructions,
    maxVerificationAttempts = maxVerificationAttempts,
    maxBudgetUsd = maxBudgetUsd ?: 0.0,
    paused = paused,
    workspacePath = workspacePath.orEmpty(),
    worktreePath = worktreePath.orEmpty(),
    branchName = branchName.orEmpty(),
    worktreeOwnerRunId = worktreeOwnerRunId.orEmpty(),
    attempts = attempts.map {
        ProjectTaskAttemptDto(
            it.runId,
            it.stage.name,
            it.attempt,
            it.prompt,
            it.profile.toDto(),
            it.scratchpadSnapshot.orEmpty(),
            it.createdAtMillis,
            it.reviewedBuildRunId.orEmpty(),
            it.reviewGeneration,
        )
    },
    reviewVerdicts = reviewVerdicts.map { verdict ->
        ProjectReviewVerdictDto(
            status = verdict.status.name,
            summary = verdict.summary,
            findings = verdict.findings.map { finding ->
                ProjectReviewFindingDto(
                    severity = finding.severity.name,
                    title = finding.title,
                    details = finding.details,
                    file = finding.file.orEmpty(),
                    line = finding.line ?: 0,
                )
            },
            runId = verdict.runId,
            reviewedBuildRunId = verdict.reviewedBuildRunId,
            reviewGeneration = verdict.reviewGeneration,
            createdAtMillis = verdict.createdAtMillis,
        )
    },
    verdicts = verdicts.map {
        ProjectVerificationVerdictDto(
            it.status.name,
            it.summary,
            it.evidence,
            it.failures,
            it.runId,
            it.createdAtMillis,
            it.reviewedBuildRunId.orEmpty(),
            it.reviewGeneration,
        )
    },
    lastError = lastError.orEmpty(),
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

private fun AgentThreadChangeSnapshotDto.toModel(): AgentThreadChangeSnapshot = AgentThreadChangeSnapshot(
    summary = AgentChangeSummary(files.map { AgentFileChange(it.path, it.additions, it.deletions) }),
    diffs = diffs.associate { diff ->
        diff.path to AgentFileDiff(
            path = diff.path,
            lines = diff.lines.map { line ->
                DiffLine(
                    kind = DiffLineKind.entries.firstOrNull { it.name == line.kind } ?: DiffLineKind.Context,
                    text = line.text,
                    oldLineNumber = line.oldLineNumber,
                    newLineNumber = line.newLineNumber,
                )
            },
            additions = diff.additions,
            deletions = diff.deletions,
            isBinary = diff.isBinary,
            isNewFile = diff.isNewFile,
        )
    },
)

private fun AgentThreadChangeSnapshot.toDto(): AgentThreadChangeSnapshotDto = AgentThreadChangeSnapshotDto(
    files = summary.files.map { AgentFileChangeDto(it.path, it.additions, it.deletions) },
    diffs = diffs.values.map { diff ->
        AgentFileDiffDto(
            path = diff.path,
            lines = diff.lines.map { line ->
                DiffLineDto(line.kind.name, line.text, line.oldLineNumber, line.newLineNumber)
            },
            additions = diff.additions,
            deletions = diff.deletions,
            isBinary = diff.isBinary,
            isNewFile = diff.isNewFile,
        )
    },
)
