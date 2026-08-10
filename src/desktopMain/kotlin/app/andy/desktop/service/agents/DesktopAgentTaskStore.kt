package app.andy.desktop.service.agents

import app.andy.model.AgentAutonomy
import app.andy.model.AgentContextualProvenance
import app.andy.model.AgentKind
import app.andy.model.AgentLaneKind
import app.andy.model.AgentQuotaAccess
import app.andy.model.AgentReasoningEffort
import app.andy.model.AgentProviderDefaults
import app.andy.model.AgentQueuedFollowUp
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentSkill
import app.andy.model.AgentTask
import app.andy.model.AgentStatus
import app.andy.model.LegacyStatusMigration
import app.andy.model.migrateLegacyTaskStatus
import app.andy.model.AgentUserInputOption
import app.andy.model.AgentUserInputQuestion
import app.andy.model.AgentUserInputRequest
import app.andy.model.AgentUserInputOrigin
import app.andy.model.AgentThreadChangeSnapshot
import app.andy.model.ContextualActionKind
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
import app.andy.model.KanbanBoard
import app.andy.model.ProjectWorkflowStage
import app.andy.model.ProjectWorkflowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.Toml
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

private val archiveViewCleanupRegistered = AtomicBoolean(false)

fun registerArchiveViewShutdownHook() {
    if (!archiveViewCleanupRegistered.compareAndSet(false, true)) return
    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching {
            File(System.getProperty("java.io.tmpdir"), "andy-archive-view").deleteRecursively()
        }
    })
}

data class AgentStoreState(
    val tasks: List<AgentTask> = emptyList(),
    val binaryOverrides: Map<String, String> = emptyMap(),
    val providerDefaults: Map<AgentKind, AgentProviderDefaults> = emptyMap(),
    val quotaAccess: AgentQuotaAccess = AgentQuotaAccess(),
    val lastUsedAgent: AgentKind? = null,
    val maxConcurrent: Int = 8,
    val projectWorkflows: Map<String, ProjectWorkflowState> = emptyMap(),
    /** One-time migration: pre-scrollback chats archived on first launch after upgrade. */
    val legacyTranscriptChatsArchived: Boolean = false,
)

class DesktopAgentTaskStore(
    private val databaseFile: File = File(System.getProperty("user.home"), ".andy/agents.db"),
    transcriptsDir: File? = null,
) {
    val storeFile: File get() = databaseFile
    /** Scrollback / ACP transcript files. Defaults to `~/.andy/agents` beside [databaseFile]. */
    val transcriptsDir: File = transcriptsDir ?: File(databaseFile.parentFile, "agents")
    private val legacyTomlFile: File get() = File(databaseFile.parentFile, "agents.toml")
    private val sqlite by lazy { SqliteAgentStore(dbFile = databaseFile) }

    fun taskDir(taskId: String): File = File(transcriptsDir, taskId)

    fun archiveFile(taskId: String): File = File(taskDir(taskId), "archive.zip")

    fun launchLogFile(taskId: String): File = File(taskDir(taskId), "launch.log")

    /** Task-local copies of managed evidence bundles (§4), keyed by bundle id under this directory. */
    fun taskEvidenceDir(taskId: String): File = File(taskDir(taskId), "evidence")

    /** Cumulative terminal scrollback (ANSI) for finished-chat replay. */
    fun scrollbackFile(taskId: String): File = File(taskDir(taskId), "scrollback.ansi")

    /** ACP's durable structured transcript, tailed by the GUI attach process. */
    fun transcriptFile(taskId: String): File = File(taskDir(taskId), "transcript.jsonl")

    /**
     * Resolves the directory used for reading a task's transcript. Retention leaves compressed
     * chats in place and extracts them into a process-local cache the first time they are opened.
     */
    suspend fun resolvedContentDir(taskId: String, compressed: Boolean): File = withContext(Dispatchers.IO) {
        resolvedContentDirBlocking(taskId, compressed)
    }

    /** Synchronous companion used by the terminal replay path, which already runs off Main. */
    fun resolvedContentDirBlocking(taskId: String, compressed: Boolean): File {
        if (!compressed) return taskDir(taskId)
        val archive = archiveFile(taskId)
        if (!archive.isFile) return taskDir(taskId)
        val extractDir = File(File(System.getProperty("java.io.tmpdir"), "andy-archive-view"), taskId)
        if (extractDir.exists()) return extractDir
        extractDir.mkdirs()
        val canonicalRoot = extractDir.canonicalFile
        ZipFile(archive).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val outFile = File(extractDir, entry.name)
                if (!outFile.canonicalFile.toPath().startsWith(canonicalRoot.toPath())) {
                    error("Archive entry escapes extraction directory: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
        return extractDir
    }

    suspend fun load(): AgentStoreState = withContext(Dispatchers.IO) {
        migrateLegacyTomlIfNeeded()
        runCatching { sqlite.load(::scrollbackFile) }.getOrElse { AgentStoreState() }
    }

    suspend fun save(state: AgentStoreState, allowEmptyTaskList: Boolean = false): Unit =
        withContext(Dispatchers.IO) {
            saveSync(state, allowEmptyTaskList)
        }

    fun saveSync(state: AgentStoreState, allowEmptyTaskList: Boolean = false) {
        databaseFile.parentFile?.mkdirs()
        sqlite.save(state, allowEmptyTaskList)
    }

    fun loadKanbanBoard(): KanbanBoard? = sqlite.loadKanbanBoard()

    fun saveKanbanBoard(board: KanbanBoard) {
        databaseFile.parentFile?.mkdirs()
        sqlite.saveKanbanBoard(board)
    }

    suspend fun deleteTaskArtifacts(taskId: String): Unit = withContext(Dispatchers.IO) {
        taskDir(taskId).deleteRecursively()
    }

    private fun migrateLegacyTomlIfNeeded() {
        if (!legacyTomlFile.isFile) return
        val existing = runCatching { sqlite.load(::scrollbackFile) }.getOrNull()
        if (existing != null && (existing.tasks.isNotEmpty() || existing.projectWorkflows.isNotEmpty())) {
            archiveLegacyToml()
            return
        }
        val migrated = loadLegacyToml() ?: return
        saveSync(migrated, allowEmptyTaskList = true)
        archiveLegacyToml()
    }

    private fun loadLegacyToml(): AgentStoreState? {
        val primary = runCatching {
            Toml { ignoreUnknownKeys = true }
                .decodeFromString(AgentsFileDto.serializer(), legacyTomlFile.readText())
                .toModel(::scrollbackFile)
        }
        if (primary.isSuccess) return primary.getOrThrow()

        runCatching { legacyTomlFile.copyTo(File(legacyTomlFile.absolutePath + ".corrupt"), overwrite = true) }
        val backup = File(legacyTomlFile.absolutePath + ".bak")
        if (!backup.isFile || backup.length() == 0L) return null
        return runCatching {
            Toml { ignoreUnknownKeys = true }
                .decodeFromString(AgentsFileDto.serializer(), backup.readText())
                .toModel(::scrollbackFile)
        }.getOrNull()
    }

    private fun archiveLegacyToml() {
        val archived = File(legacyTomlFile.absolutePath + ".migrated")
        if (legacyTomlFile.renameTo(archived)) return
        runCatching {
            legacyTomlFile.copyTo(archived, overwrite = true)
            legacyTomlFile.delete()
        }
    }
}

@Serializable
internal data class AgentsFileDto(
    val version: Int = 4,
    val maxConcurrent: Int = 8,
    val binaries: Map<String, String> = emptyMap(),
    val providerDefaults: List<AgentProviderDefaultsDto> = emptyList(),
    val quotaAccess: AgentQuotaAccessDto = AgentQuotaAccessDto(),
    val lastUsedAgent: String = "",
    val legacyTranscriptChatsArchived: Boolean = false,
    val tasks: List<AgentTaskDto> = emptyList(),
    val projectWorkflows: List<ProjectWorkflowDto> = emptyList(),
)

@Serializable
internal data class AgentQuotaAccessDto(
    val claudeAccountAccess: Boolean = false,
    val cursorAccountAccess: Boolean = false,
    val antigravityAccountAccess: Boolean = false,
)

@Serializable
internal data class AgentProviderDefaultsDto(
    val agent: String,
    val model: String = "",
    val reasoningEffort: String = "",
    val fastMode: Boolean = false,
    val openClawNewSession: Boolean = true,
    val autonomy: String = AgentAutonomy.Standard.name,
    val sandboxMode: String = "",
    val planMode: Boolean = false,
    val confirmToolCalls: Boolean = false,
    val useWorktree: Boolean = false,
    val attachAndyMcp: Boolean = false,
    val maxBudgetUsd: Double = 0.0,
)

@Serializable
internal data class AgentTaskDto(
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
    val confirmToolCalls: Boolean = false,
    val completedPlanText: String = "",
    val continuationPrompt: String = "",
    val latestPrompt: String = "",
    val model: String = "",
    val reasoningEffort: String = "",
    val fastMode: Boolean = false,
    val openClawNewSession: Boolean = true,
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
    val changeBaselineTree: String? = null,
    val completedChanges: AgentThreadChangeSnapshotDto? = null,
    val status: String = "",
    val stoppedByUser: Boolean = false,
    val resumable: Boolean = false,
    val interrupted: Boolean = false,
    val statusConfident: Boolean = false,
    val vendorSessionId: String = "",
    val acpSessionId: String = "",
    val stopReason: String = "",
    val lane: String = AgentLaneKind.Terminal.name,
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
    val archived: Boolean = false,
    val transcriptCompressed: Boolean = false,
    val ownsWorktree: Boolean = false,
    val parentWorktreeTaskId: String = "",
    val workflowTaskId: String = "",
    val workflowStage: String = "",
    val workflowAttempt: Int = 0,
    val completedResultText: String = "",
    val contextBundleIds: List<String> = emptyList(),
    val provenance: AgentContextualProvenanceDto? = null,
)

@Serializable
internal data class AgentContextualProvenanceDto(
    val sourceKind: String,
    val investigationId: String = "",
    val eventId: String = "",
    val playbackMillis: Long = 0,
    val networkExchangeId: String = "",
    val crashId: String = "",
    val hierarchyNodeId: String = "",
    val packageName: String = "",
)

private fun AgentContextualProvenanceDto.toModel(): AgentContextualProvenance? {
    val kind = ContextualActionKind.entries.firstOrNull { it.name == sourceKind } ?: return null
    return AgentContextualProvenance(
        sourceKind = kind,
        investigationId = investigationId.takeIf { it.isNotBlank() },
        eventId = eventId.takeIf { it.isNotBlank() },
        playbackMillis = playbackMillis.takeIf { it > 0 },
        networkExchangeId = networkExchangeId.takeIf { it.isNotBlank() },
        crashId = crashId.takeIf { it.isNotBlank() },
        hierarchyNodeId = hierarchyNodeId.takeIf { it.isNotBlank() },
        packageName = packageName.takeIf { it.isNotBlank() },
    )
}

private fun AgentContextualProvenance.toDto(): AgentContextualProvenanceDto = AgentContextualProvenanceDto(
    sourceKind = sourceKind.name,
    investigationId = investigationId.orEmpty(),
    eventId = eventId.orEmpty(),
    playbackMillis = playbackMillis ?: 0,
    networkExchangeId = networkExchangeId.orEmpty(),
    crashId = crashId.orEmpty(),
    hierarchyNodeId = hierarchyNodeId.orEmpty(),
    packageName = packageName.orEmpty(),
)

@Serializable
internal data class AgentUserInputRequestDto(
    val id: String,
    val questions: List<AgentUserInputQuestionDto>,
    val origin: String = AgentUserInputOrigin.Artifact.name,
)

@Serializable
internal data class AgentUserInputQuestionDto(
    val id: String,
    val header: String = "",
    val question: String,
    val options: List<AgentUserInputOptionDto>,
)

@Serializable
internal data class AgentUserInputOptionDto(
    val label: String,
    val description: String = "",
)

@Serializable
internal data class ProjectWorkflowDto(
    val projectId: String,
    val scratchpad: String = "",
    val profiles: List<ProjectRoleProfileDto> = emptyList(),
    val tasks: List<ProjectTaskDto> = emptyList(),
    val legacyNotesMigrated: Boolean = false,
)

@Serializable
internal data class ProjectRoleProfileDto(
    val kind: String,
    val profile: ProjectAgentProfileDto,
)

@Serializable
internal data class ProjectAgentProfileDto(
    val agent: String = AgentKind.Codex.name,
    val model: String = "",
    val reasoningEffort: String = "",
    val fastMode: Boolean = false,
    val autonomy: String = AgentAutonomy.Standard.name,
    val sandboxMode: String = "",
    val confirmToolCalls: Boolean = false,
    val useWorktree: Boolean = false,
    val attachAndyMcp: Boolean = false,
    val maxBudgetUsd: Double = 0.0,
)

@Serializable
internal data class ProjectTaskDto(
    val id: String,
    val projectId: String,
    val kind: String,
    val title: String,
    val instructions: String,
    val profile: ProjectAgentProfileDto,
    val includeScratchpad: Boolean = false,
    val imagePaths: List<String> = emptyList(),
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
    val singleReviewPass: Boolean = false,
    val maxReviewFailures: Int = 5,
    val reviewReopenedCompleted: Boolean = false,
    val recoveryMode: Boolean = false,
    val reviewStale: Boolean = false,
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
internal data class ProjectPlanVersionDto(
    val version: Int,
    val text: String,
    val runId: String,
    val createdAtMillis: Long,
)

@Serializable
internal data class ProjectPlanSnapshotDto(
    val text: String,
    val sourceSpecTaskId: String = "",
    val sourceVersion: Int = 0,
    val sourceLabel: String = "external plan",
)

@Serializable
internal data class ProjectTaskAttemptDto(
    val runId: String,
    val stage: String,
    val attempt: Int,
    val prompt: String,
    val profile: ProjectAgentProfileDto,
    val scratchpadSnapshot: String = "",
    val createdAtMillis: Long,
    val reviewedBuildRunId: String = "",
    val reviewGeneration: Int = 0,
    val isRecoveryFollowUp: Boolean = false,
)

@Serializable
internal data class ProjectReviewVerdictDto(
    val status: String,
    val summary: String,
    val findings: List<ProjectReviewFindingDto> = emptyList(),
    val runId: String,
    val reviewedBuildRunId: String,
    val reviewGeneration: Int,
    val createdAtMillis: Long,
)

@Serializable
internal data class ProjectReviewFindingDto(
    val severity: String,
    val title: String,
    val details: String,
    val file: String = "",
    val line: Int = 0,
)

@Serializable
internal data class ProjectVerificationVerdictDto(
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
internal data class AgentQueuedFollowUpDto(
    val text: String = "",
    val imagePaths: List<String> = emptyList(),
    val skillNames: List<String> = emptyList(),
    val skillPaths: List<String> = emptyList(),
    val contextBundleIds: List<String> = emptyList(),
    val provenance: AgentContextualProvenanceDto? = null,
)

@Serializable
internal data class AgentThreadChangeSnapshotDto(
    val files: List<AgentFileChangeDto> = emptyList(),
    val diffs: List<AgentFileDiffDto> = emptyList(),
)

@Serializable
internal data class AgentFileChangeDto(
    val path: String,
    val additions: Int,
    val deletions: Int,
)

@Serializable
internal data class AgentFileDiffDto(
    val path: String,
    val lines: List<DiffLineDto> = emptyList(),
    val additions: Int = 0,
    val deletions: Int = 0,
    val isBinary: Boolean = false,
    val isNewFile: Boolean = false,
)

@Serializable
internal data class DiffLineDto(
    val kind: String,
    val text: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null,
)

internal fun AgentsFileDto.toModel(scrollbackFile: (String) -> File): AgentStoreState = AgentStoreState(
    tasks = tasks.mapNotNull { it.toModel(scrollbackFile) },
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
    legacyTranscriptChatsArchived = legacyTranscriptChatsArchived,
)

internal fun AgentProviderDefaultsDto.toModel(): Pair<AgentKind, AgentProviderDefaults>? {
    val kind = AgentKind.entries.firstOrNull { it.name == agent } ?: return null
    return kind to AgentProviderDefaults(
        model = model.takeIf { it.isNotBlank() },
        reasoningEffort = AgentReasoningEffort.entries.firstOrNull { it.name == reasoningEffort },
        fastMode = fastMode,
        openClawNewSession = openClawNewSession,
        autonomy = AgentAutonomy.entries.firstOrNull { it.name == autonomy } ?: AgentAutonomy.Standard,
        sandboxMode = AgentSandboxMode.entries.firstOrNull { it.name == sandboxMode },
        planMode = planMode,
        confirmToolCalls = confirmToolCalls,
        useWorktree = useWorktree,
        attachAndyMcp = attachAndyMcp,
        maxBudgetUsd = maxBudgetUsd.takeIf { it > 0 },
    )
}

internal fun AgentTaskDto.toModel(scrollbackFile: (String) -> File): AgentTask? {
    val agentKind = AgentKind.entries.firstOrNull { it.name == agent } ?: return null
    val migrated = if (status.isBlank() || AgentStatus.entries.any { it.name == status }) {
        val parsed = AgentStatus.entries.firstOrNull { it.name == status }
        LegacyStatusMigration(
            status = parsed,
            stoppedByUser = stoppedByUser,
            resumable = resumable,
            interrupted = interrupted,
        )
    } else {
        migrateLegacyTaskStatus(status).copy(
            stoppedByUser = stoppedByUser || migrateLegacyTaskStatus(status).stoppedByUser,
            resumable = resumable || migrateLegacyTaskStatus(status).resumable,
            interrupted = interrupted || migrateLegacyTaskStatus(status).interrupted,
        )
    }
    val legacyQueuedFollowUp = queuedFollowUp.takeIf { it.isNotBlank() || queuedFollowUpImagePaths.isNotEmpty() }?.let { text ->
        AgentQueuedFollowUp(
            text = text,
            imagePaths = queuedFollowUpImagePaths,
            skills = queuedFollowUpSkillNames.zip(queuedFollowUpSkillPaths)
                .filter { (_, path) -> path.isNotBlank() }
                .map { (name, path) -> AgentSkill(name = name, description = "", path = path) },
        )
    }
    val task = AgentTask(
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
        parentWorktreeTaskId = parentWorktreeTaskId.takeIf { it.isNotBlank() },
        workflowTaskId = workflowTaskId.takeIf { it.isNotBlank() },
        workflowStage = ProjectWorkflowStage.entries.firstOrNull { it.name == workflowStage },
        workflowAttempt = workflowAttempt.takeIf { it > 0 },
        attachAndyMcp = attachAndyMcp,
        autonomy = AgentAutonomy.entries.firstOrNull { it.name == autonomy } ?: AgentAutonomy.Standard,
        sandboxMode = AgentSandboxMode.entries.firstOrNull { it.name == sandboxMode },
        planMode = planMode,
        confirmToolCalls = confirmToolCalls,
        completedPlanText = completedPlanText.takeIf { it.isNotBlank() },
        continuationPrompt = continuationPrompt.takeIf { it.isNotBlank() },
        latestPrompt = latestPrompt.takeIf { it.isNotBlank() },
        completedResultText = completedResultText.takeIf { it.isNotBlank() },
        model = model.takeIf { it.isNotBlank() },
        reasoningEffort = AgentReasoningEffort.entries.firstOrNull { it.name == reasoningEffort },
        fastMode = fastMode,
        openClawNewSession = openClawNewSession,
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
                    contextBundleIds = queued.contextBundleIds,
                    provenance = queued.provenance?.toModel(),
                )
            }
        } + listOfNotNull(legacyQueuedFollowUp),
        userInputRequest = userInputRequest?.toModel(),
        maxBudgetUsd = maxBudgetUsd.takeIf { it > 0 },
        changeBaselineTree = changeBaselineTree,
        completedChanges = completedChanges?.toModel(),
        status = migrated.status,
        stoppedByUser = migrated.stoppedByUser,
        resumable = migrated.resumable,
        interrupted = migrated.interrupted,
        statusConfident = statusConfident,
        vendorSessionId = vendorSessionId.takeIf { it.isNotBlank() },
        acpSessionId = acpSessionId.takeIf { it.isNotBlank() },
        stopReason = stopReason.takeIf { it.isNotBlank() },
        lane = inferAgentLaneFromArtifacts(
            taskId = id,
            declaredLane = AgentLaneKind.entries.firstOrNull { it.name == lane },
            agent = agentKind,
            agentsDir = scrollbackFile(id).parentFile?.parentFile ?: defaultAndyAgentArtifactsDir(),
        ),
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
        archived = archived,
        transcriptCompressed = transcriptCompressed,
        contextBundleIds = contextBundleIds,
        provenance = provenance?.toModel(),
    )
    return recoverInterruptedTaskStatus(task, scrollbackFile(id))
}

internal fun AgentStoreState.toFileDto(): AgentsFileDto = AgentsFileDto(
    maxConcurrent = maxConcurrent,
    binaries = binaryOverrides,
    lastUsedAgent = lastUsedAgent?.name.orEmpty(),
    legacyTranscriptChatsArchived = legacyTranscriptChatsArchived,
    providerDefaults = providerDefaults.entries.map { (agent, defaults) ->
        AgentProviderDefaultsDto(
            agent = agent.name,
            model = defaults.model.orEmpty(),
            reasoningEffort = defaults.reasoningEffort?.name.orEmpty(),
            fastMode = defaults.fastMode,
            openClawNewSession = defaults.openClawNewSession,
            autonomy = defaults.autonomy.name,
            sandboxMode = defaults.sandboxMode?.name.orEmpty(),
            planMode = defaults.planMode,
            confirmToolCalls = defaults.confirmToolCalls,
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
            parentWorktreeTaskId = task.parentWorktreeTaskId.orEmpty(),
            workflowTaskId = task.workflowTaskId.orEmpty(),
            workflowStage = task.workflowStage?.name.orEmpty(),
            workflowAttempt = task.workflowAttempt ?: 0,
            attachAndyMcp = task.attachAndyMcp,
            autonomy = task.autonomy.name,
            sandboxMode = task.sandboxMode?.name.orEmpty(),
            planMode = task.planMode,
            confirmToolCalls = task.confirmToolCalls,
            completedPlanText = task.completedPlanText.orEmpty(),
            continuationPrompt = task.continuationPrompt.orEmpty(),
            latestPrompt = task.latestPrompt.orEmpty(),
            completedResultText = task.completedResultText.orEmpty(),
            model = task.model.orEmpty(),
            reasoningEffort = task.reasoningEffort?.name.orEmpty(),
            fastMode = task.fastMode,
            openClawNewSession = task.openClawNewSession,
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
                    contextBundleIds = queued.contextBundleIds,
                    provenance = queued.provenance?.toDto(),
                )
            },
            userInputRequest = task.userInputRequest?.toDto(),
            maxBudgetUsd = task.maxBudgetUsd ?: 0.0,
            changeBaselineTree = task.changeBaselineTree,
            completedChanges = task.completedChanges?.toDto(),
            status = task.status?.name.orEmpty(),
            stoppedByUser = task.stoppedByUser,
            resumable = task.resumable,
            interrupted = task.interrupted,
            statusConfident = task.statusConfident,
            vendorSessionId = task.vendorSessionId.orEmpty(),
            acpSessionId = task.acpSessionId.orEmpty(),
            stopReason = task.stopReason.orEmpty(),
            lane = task.lane.name,
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
            archived = task.archived,
            transcriptCompressed = task.transcriptCompressed,
            contextBundleIds = task.contextBundleIds,
            provenance = task.provenance?.toDto(),
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
    return AgentUserInputRequest(
        id = id,
        questions = parsedQuestions.takeIf { it.isNotEmpty() } ?: return null,
        origin = AgentUserInputOrigin.entries.firstOrNull { it.name == origin }
            ?: AgentUserInputOrigin.Artifact,
    )
}

private fun AgentUserInputRequest.toDto() = AgentUserInputRequestDto(
    id = id,
    origin = origin.name,
    questions = questions.map { question ->
        AgentUserInputQuestionDto(
            id = question.id,
            header = question.header,
            question = question.question,
            options = question.options.map { option -> AgentUserInputOptionDto(option.label, option.description) },
        )
    },
)

internal fun ProjectWorkflowDto.toModel(): ProjectWorkflowState = ProjectWorkflowState(
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
    confirmToolCalls = confirmToolCalls,
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
        imagePaths = imagePaths,
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
        singleReviewPass = singleReviewPass,
        maxReviewFailures = maxReviewFailures.coerceIn(1, 20),
        reviewReopenedCompleted = reviewReopenedCompleted,
        recoveryMode = recoveryMode,
        reviewStale = reviewStale,
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
                    isRecoveryFollowUp = attempt.isRecoveryFollowUp,
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
    confirmToolCalls = confirmToolCalls,
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
    imagePaths = imagePaths,
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
    singleReviewPass = singleReviewPass,
    maxReviewFailures = maxReviewFailures,
    reviewReopenedCompleted = reviewReopenedCompleted,
    recoveryMode = recoveryMode,
    reviewStale = reviewStale,
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
            it.isRecoveryFollowUp,
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
