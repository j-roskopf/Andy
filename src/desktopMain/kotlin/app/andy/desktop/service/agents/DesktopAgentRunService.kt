package app.andy.desktop.service.agents

import app.andy.model.AgentCliStatus
import app.andy.model.AgentChangeSummary
import app.andy.model.AgentEvent
import app.andy.model.AgentFileDiff
import app.andy.model.AgentKind
import app.andy.model.grillMeHeadlessPromptAddendum
import app.andy.model.isGrillMeSkillName
import app.andy.model.AgentModelCatalog
import app.andy.model.AgentModelOption
import app.andy.model.coalesceAgentStreamDeltas
import app.andy.model.AgentProviderDefaults
import app.andy.model.AgentQueuedFollowUp
import app.andy.model.AgentProviderQuota
import app.andy.model.AgentQuotaSource
import app.andy.model.AgentQuotaAccess
import app.andy.model.AgentSkill
import app.andy.model.AgentTask
import app.andy.model.AgentTaskDraft
import app.andy.model.AgentTaskStatus
import app.andy.model.AgentUserInputRequest
import app.andy.model.AgentThreadChangeSnapshot
import app.andy.model.AgentSandboxMode
import app.andy.model.ProjectAgentProfile
import app.andy.model.ProjectBuildPairDraft
import app.andy.model.ProjectPlanSnapshot
import app.andy.model.ProjectPlanVersion
import app.andy.model.ProjectReviewFinding
import app.andy.model.ProjectReviewFindingSeverity
import app.andy.model.ProjectReviewStatus
import app.andy.model.ProjectReviewVerdict
import app.andy.model.ProjectSpecDraft
import app.andy.model.ProjectTask
import app.andy.model.ProjectTaskAttempt
import app.andy.model.ProjectTaskKind
import app.andy.model.ProjectTaskState
import app.andy.model.ProjectVerificationStatus
import app.andy.model.ProjectVerificationVerdict
import app.andy.model.ProjectWorkflowStage
import app.andy.model.ProjectWorkflowState
import app.andy.model.toProjectProfile
import app.andy.model.estimatedTokenCostUsd
import app.andy.model.promptWithGoalHint
import app.andy.model.promptWithSkillHints
import app.andy.model.providerDefaults
import app.andy.service.ActionConfigStore
import app.andy.service.CommandResult
import app.andy.service.AgentRunService
import app.andy.service.McpServerService
import app.andy.service.ProjectWorkflowService
import app.andy.service.WorkspaceStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val MAX_EVENTS_IN_MEMORY = 3000
private const val PROVIDER_QUOTA_REFRESH_MILLIS = 5 * 60 * 1000L
private val VERIFICATION_BLOCK = Regex("""<andy_verification>([\s\S]*?)</andy_verification>""")
private val REVIEW_BLOCK = Regex("""<andy_review>([\s\S]*?)</andy_review>""")

class DesktopAgentRunService(
    private val scope: CoroutineScope,
    private val store: DesktopAgentTaskStore,
    private val locator: AgentCliLocator,
    private val adapters: Map<AgentKind, AgentCliAdapter>,
    private val worktrees: WorktreeManager,
    private val mcp: McpServerService,
    private val workspaceStore: WorkspaceStore,
    private val actionConfig: ActionConfigStore,
) : AgentRunService, ProjectWorkflowService {
    private class TaskHandle(
        @Volatile var process: Process? = null,
        @Volatile var job: Job? = null,
        @Volatile var stopRequested: Boolean = false,
    )

    private val _tasks = MutableStateFlow<List<AgentTask>>(emptyList())
    override val tasks: StateFlow<List<AgentTask>> = _tasks

    private val _cliStatuses = MutableStateFlow<List<AgentCliStatus>>(emptyList())
    override val cliStatuses: StateFlow<List<AgentCliStatus>> = _cliStatuses

    private val _providerModels = MutableStateFlow<Map<AgentKind, List<AgentModelOption>>>(emptyMap())
    override val providerModels: StateFlow<Map<AgentKind, List<AgentModelOption>>> = _providerModels

    private val _providerQuotas = MutableStateFlow<Map<AgentKind, AgentProviderQuota>>(emptyMap())
    override val providerQuotas: StateFlow<Map<AgentKind, AgentProviderQuota>> = _providerQuotas

    private val _quotaAccess = MutableStateFlow(AgentQuotaAccess())
    override val quotaAccess: StateFlow<AgentQuotaAccess> = _quotaAccess

    private val _providerDefaults = MutableStateFlow<Map<AgentKind, AgentProviderDefaults>>(emptyMap())
    override val providerDefaults: StateFlow<Map<AgentKind, AgentProviderDefaults>> = _providerDefaults

    private val _lastUsedAgent = MutableStateFlow<AgentKind?>(null)
    override val lastUsedAgent: StateFlow<AgentKind?> = _lastUsedAgent

    private val _projects = MutableStateFlow<Map<String, ProjectWorkflowState>>(emptyMap())
    override val projects: StateFlow<Map<String, ProjectWorkflowState>> = _projects

    private data class SkillScope(val agent: AgentKind, val directory: String?)

    private val skillFlows = ConcurrentHashMap<SkillScope, MutableStateFlow<List<AgentSkill>>>()
    private val loadedSkillScopes = ConcurrentHashMap.newKeySet<SkillScope>()

    private val handles = ConcurrentHashMap<String, TaskHandle>()
    private val eventFlows = ConcurrentHashMap<String, MutableStateFlow<List<AgentEvent>>>()
    private val historyLoaded = ConcurrentHashMap.newKeySet<String>()
    private val emptyEvents = MutableStateFlow<List<AgentEvent>>(emptyList())

    private val persistMutex = Mutex()
    private val mcpMutex = Mutex()
    private val quotaRefreshMutex = Mutex()
    private val quotaProbe = ProviderQuotaProbe()
    private val modelProbe = ProviderModelProbe()
    private val ready = CompletableDeferred<Unit>()
    private var binaryOverrides: Map<String, String> = emptyMap()
    private lateinit var slots: Semaphore

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            handles.values.forEach { handle -> handle.process?.let(::killTree) }
        })
        scope.launch {
            val state = store.load()
            binaryOverrides = state.binaryOverrides
            val recoveredDefaults = state.tasks
                .groupBy { it.agent }
                .mapValues { (_, tasks) -> tasks.maxBy { it.createdAtMillis }.providerDefaults() }
            _providerDefaults.value = recoveredDefaults + state.providerDefaults
            _quotaAccess.value = state.quotaAccess
            _lastUsedAgent.value = state.lastUsedAgent
                ?: state.tasks.maxByOrNull { it.createdAtMillis }?.agent
            storedMaxConcurrent = state.maxConcurrent
            slots = Semaphore(state.maxConcurrent)
            _tasks.value = state.tasks
            _projects.value = recoverInterruptedWorkflows(state.projectWorkflows, state.tasks)
                .mapValues { (_, workflow) -> workflow.withMissingProfiles() }
            migrateLegacyProjectNotes()
            backfillCursorPlansFromTranscripts()
            ready.complete(Unit)
            scope.launch(Dispatchers.IO) { restoreProviderQuotas(state.tasks) }
            refreshCliStatuses()
            refreshProviderQuotas()
            while (isActive) {
                delay(PROVIDER_QUOTA_REFRESH_MILLIS)
                refreshProviderQuotas()
            }
        }
    }

    override fun skills(agent: AgentKind, directory: String?): StateFlow<List<AgentSkill>> {
        val normalizedDirectory = directory
            ?.takeIf { it.isNotBlank() }
            ?.let { path -> runCatching { File(path).canonicalPath }.getOrElse { path } }
        val skillScope = SkillScope(agent, normalizedDirectory)
        val flow = skillFlows.computeIfAbsent(skillScope) { MutableStateFlow(emptyList()) }
        if (loadedSkillScopes.add(skillScope)) {
            scope.launch(Dispatchers.IO) {
                flow.value = discoverSkills(agent, normalizedDirectory)
            }
        }
        return flow
    }

    override fun refreshSkills(agent: AgentKind, directory: String?) {
        val normalizedDirectory = directory
            ?.takeIf { it.isNotBlank() }
            ?.let { path -> runCatching { File(path).canonicalPath }.getOrElse { path } }
        val skillScope = SkillScope(agent, normalizedDirectory)
        val flow = skillFlows.computeIfAbsent(skillScope) { MutableStateFlow(emptyList()) }
        loadedSkillScopes.add(skillScope)
        scope.launch(Dispatchers.IO) {
            flow.value = discoverSkills(agent, normalizedDirectory)
        }
    }

    override suspend fun ensureProject(projectId: String) {
        ready.await()
        if (projectId in _projects.value) return
        _projects.update { it + (projectId to defaultProjectState(projectId)) }
        persist()
    }

    override suspend fun updateScratchpad(projectId: String, text: String) {
        ensureProject(projectId)
        updateProject(projectId) { it.copy(scratchpad = text) }
        persist()
    }

    override suspend fun updateProfile(projectId: String, kind: ProjectTaskKind, profile: ProjectAgentProfile) {
        ensureProject(projectId)
        val normalized = profile.normalizedFor(kind)
        updateProject(projectId) { it.copy(profiles = it.profiles + (kind to normalized)) }
        persist()
    }

    override suspend fun saveSpec(draft: ProjectSpecDraft): String {
        ready.await()
        require(draft.title.isNotBlank()) { "spec title is required" }
        require(draft.brief.isNotBlank()) { "spec brief is required" }
        ensureProject(draft.projectId)
        val now = System.currentTimeMillis()
        val existing = draft.taskId?.let(::projectTask)
        require(existing == null || (existing.kind == ProjectTaskKind.Spec && !existing.isActive)) { "active specs cannot be edited" }
        val id = existing?.id ?: workflowId("spec")
        val task = (existing ?: ProjectTask(
            id = id,
            projectId = draft.projectId,
            kind = ProjectTaskKind.Spec,
            title = draft.title.trim(),
            instructions = draft.brief.trim(),
            profile = draft.profile,
            includeScratchpad = draft.includeScratchpad,
            imagePaths = draft.imagePaths,
            grillMeEnabled = draft.grillMeEnabled,
            createdAtMillis = now,
            updatedAtMillis = now,
        )).copy(
            title = draft.title.trim(),
            instructions = draft.brief.trim(),
            profile = draft.profile.normalizedFor(ProjectTaskKind.Spec),
            includeScratchpad = draft.includeScratchpad,
            imagePaths = draft.imagePaths,
            grillMeEnabled = draft.grillMeEnabled,
            updatedAtMillis = now,
        )
        upsertProjectTask(task)
        persist()
        return id
    }

    override suspend fun runSpec(taskId: String, revisionRequest: String?) {
        ready.await()
        val spec = projectTask(taskId)?.takeIf { it.kind == ProjectTaskKind.Spec } ?: return
        if (spec.isActive) return
        val project = _projects.value[spec.projectId] ?: return
        val directory = projectDirectory(spec.projectId)
        if (directory == null) {
            updateProjectTask(taskId) { it.copy(state = ProjectTaskState.NeedsAttention, lastError = "project directory is unavailable") }
            persist()
            return
        }
        val installedSkills = withContext(Dispatchers.IO) { discoverSkills(spec.profile.agent, directory) }
        val grillSkills = installedSkills.filter { isGrillMeSkillName(it.name) }
        val scratchpad = project.scratchpad.takeIf { spec.includeScratchpad && it.isNotBlank() }
        val attempt = spec.attempts.count { it.stage == ProjectWorkflowStage.Spec } + 1
        val prompt = specPrompt(spec, scratchpad, revisionRequest)
        updateProjectTask(taskId) { it.copy(state = ProjectTaskState.Queued, lastError = null, updatedAtMillis = System.currentTimeMillis()) }
        persist()
        val run = createAndStart(
            spec.profile.toAgentDraft(
                title = "Spec: ${spec.title}",
                prompt = prompt,
                projectId = spec.projectId,
                directory = directory,
                planMode = true,
                skills = grillSkills.takeIf { spec.grillMeEnabled }.orEmpty(),
                imagePaths = spec.imagePaths,
                workflowTaskId = spec.id,
                stage = ProjectWorkflowStage.Spec,
                attempt = attempt,
            ),
        )
        appendAttempt(spec.id, run, ProjectWorkflowStage.Spec, attempt, prompt, spec.profile, scratchpad)
        reconcileWorkflowRun(run.id)
    }

    override suspend fun saveBuildPair(draft: ProjectBuildPairDraft): String {
        ready.await()
        require(draft.title.isNotBlank()) { "build title is required" }
        require(draft.plan.text.isNotBlank()) { "implementation plan is required" }
        ensureProject(draft.projectId)
        val now = System.currentTimeMillis()
        val existingBuild = draft.buildTaskId?.let(::projectTask)
        val activeLinkedVerification = existingBuild?.linkedVerificationTaskId?.let(::projectTask)
        val activeLinkedReview = existingBuild?.linkedReviewTaskId?.let(::projectTask)
        require(
            existingBuild == null || (
                existingBuild.kind == ProjectTaskKind.Build &&
                    !isStageBusy(existingBuild) &&
                    !isStageBusy(activeLinkedReview) &&
                    !isStageBusy(activeLinkedVerification)
                ),
        ) { "active build pairs cannot be edited" }
        val buildId = existingBuild?.id ?: workflowId("build")
        val verificationInstructions = draft.verificationInstructions.trim()
        val previousVerificationId = existingBuild?.linkedVerificationTaskId
        val verificationId = if (verificationInstructions.isNotBlank()) {
            previousVerificationId ?: workflowId("verify")
        } else {
            null
        }
        val removedVerificationId = previousVerificationId.takeIf { it != null && verificationId == null }
        val previousReviewId = existingBuild?.linkedReviewTaskId
        val previousReview = previousReviewId?.let(::projectTask)
        val retainDisabledReview = previousReview?.let { it.attempts.isNotEmpty() || it.reviewVerdicts.isNotEmpty() } == true
        val reviewId = when {
            draft.reviewEnabled -> previousReviewId ?: workflowId("review")
            retainDisabledReview -> previousReviewId
            else -> null
        }
        val removedReviewId = previousReviewId.takeIf { it != null && reviewId == null }
        val reviewWasEnabled = existingBuild?.reviewEnabled == true
        val verificationWasPresent = previousVerificationId != null
        val invalidatingVerification = verificationInstructions.isNotBlank() &&
            !verificationWasPresent &&
            existingBuild?.attempts?.isNotEmpty() == true
        val existingReview = reviewId?.let(::projectTask) ?: previousReview
        val reviewProfile = draft.reviewProfile.normalizedFor(ProjectTaskKind.Review)
        val reviewInstructions = draft.reviewInstructions.trim()
        val reviewGateChanged = draft.reviewEnabled &&
            reviewWasEnabled &&
            existingReview != null &&
            (
                reviewInstructions != existingReview.reviewInstructions ||
                    reviewProfile != existingReview.profile ||
                    draft.includeScratchpadInReview != existingReview.includeScratchpad
                )
        val invalidatingReview = draft.reviewEnabled && (!reviewWasEnabled || reviewGateChanged)
        val reviewGeneration = when {
            invalidatingReview -> (existingBuild?.reviewGeneration ?: 0) + 1
            else -> existingBuild?.reviewGeneration ?: 0
        }
        val reopeningCompleted = invalidatingReview && existingBuild?.state == ProjectTaskState.Completed
        val reopeningForVerification = invalidatingVerification && existingBuild.state == ProjectTaskState.Completed
        val restoringCompleted = !draft.reviewEnabled && reviewWasEnabled && existingBuild.reviewReopenedCompleted == true
        val pauseForReviewChange = (draft.reviewEnabled != reviewWasEnabled || reviewGateChanged) &&
            existingBuild?.attempts?.isNotEmpty() == true
        val buildProfile = draft.buildProfile.normalizedFor(ProjectTaskKind.Build).let { requested ->
            if (existingBuild?.attempts?.isNotEmpty() == true) {
                requested.copy(useWorktree = existingBuild.profile.useWorktree)
            } else {
                requested
            }
        }
        val build = (existingBuild ?: ProjectTask(
            id = buildId,
            projectId = draft.projectId,
            kind = ProjectTaskKind.Build,
            title = draft.title.trim(),
            instructions = draft.buildNotes.trim(),
            profile = buildProfile,
            includeScratchpad = draft.includeScratchpadInBuild,
            linkedSpecTaskId = draft.plan.sourceSpecTaskId,
            linkedReviewTaskId = reviewId,
            linkedVerificationTaskId = verificationId,
            planSnapshot = draft.plan,
            buildNotes = draft.buildNotes.trim(),
            reviewEnabled = draft.reviewEnabled,
            reviewInstructions = reviewInstructions,
            reviewGeneration = reviewGeneration,
            verificationInstructions = verificationInstructions,
            maxBudgetUsd = draft.maxBudgetUsd,
            createdAtMillis = now,
            updatedAtMillis = now,
        )).copy(
            title = draft.title.trim(),
            instructions = draft.buildNotes.trim(),
            profile = buildProfile,
            includeScratchpad = draft.includeScratchpadInBuild,
            linkedSpecTaskId = existingBuild?.linkedSpecTaskId ?: draft.plan.sourceSpecTaskId,
            linkedReviewTaskId = reviewId,
            linkedVerificationTaskId = verificationId,
            planSnapshot = existingBuild?.planSnapshot ?: draft.plan,
            buildNotes = draft.buildNotes.trim(),
            reviewEnabled = draft.reviewEnabled,
            reviewInstructions = reviewInstructions,
            reviewGeneration = reviewGeneration,
            reviewReopenedCompleted = when {
                reopeningCompleted -> true
                restoringCompleted -> false
                else -> existingBuild?.reviewReopenedCompleted ?: false
            },
            verificationInstructions = verificationInstructions,
            maxBudgetUsd = draft.maxBudgetUsd?.takeIf { it > 0.0 },
            state = when {
                restoringCompleted -> ProjectTaskState.Completed
                reopeningCompleted || reopeningForVerification -> ProjectTaskState.Paused
                invalidatingReview && existingBuild?.attempts?.isNotEmpty() == true -> ProjectTaskState.Paused
                !draft.reviewEnabled && reviewWasEnabled && existingBuild.state != ProjectTaskState.Completed -> ProjectTaskState.Paused
                else -> existingBuild?.state ?: ProjectTaskState.Draft
            },
            paused = when {
                restoringCompleted -> false
                reopeningCompleted || reopeningForVerification || pauseForReviewChange -> true
                else -> existingBuild?.paused ?: false
            },
            updatedAtMillis = now,
        )
        val review = reviewId?.let { id ->
            (existingReview?.takeIf { it.id == id } ?: ProjectTask(
                id = id,
                projectId = draft.projectId,
                kind = ProjectTaskKind.Review,
                title = "Review ${draft.title.trim()}",
                instructions = reviewInstructions,
                profile = reviewProfile,
                includeScratchpad = draft.includeScratchpadInReview,
                linkedSpecTaskId = draft.plan.sourceSpecTaskId,
                linkedBuildTaskId = buildId,
                linkedVerificationTaskId = verificationId,
                planSnapshot = draft.plan,
                reviewEnabled = draft.reviewEnabled,
                reviewInstructions = reviewInstructions,
                reviewGeneration = reviewGeneration,
                state = if (draft.reviewEnabled) ProjectTaskState.Draft else ProjectTaskState.Disabled,
                createdAtMillis = now,
                updatedAtMillis = now,
            )).copy(
                title = "Review ${draft.title.trim()}",
                instructions = reviewInstructions,
                profile = reviewProfile,
                includeScratchpad = draft.includeScratchpadInReview,
                linkedVerificationTaskId = verificationId,
                reviewEnabled = draft.reviewEnabled,
                reviewInstructions = reviewInstructions,
                reviewGeneration = reviewGeneration,
                state = when {
                    !draft.reviewEnabled -> ProjectTaskState.Disabled
                    invalidatingReview && existingBuild?.attempts?.isNotEmpty() == true -> ProjectTaskState.Paused
                    else -> existingReview?.takeIf { it.id == id }?.state ?: ProjectTaskState.Draft
                },
                lastError = if (draft.reviewEnabled) existingReview?.takeIf { it.id == id }?.lastError else null,
                updatedAtMillis = now,
            )
        }
        val verification = verificationId?.let { id ->
            val existingVerification = projectTask(id)
            (existingVerification ?: ProjectTask(
                id = id,
                projectId = draft.projectId,
                kind = ProjectTaskKind.Verification,
                title = "Verify ${draft.title.trim()}",
                instructions = verificationInstructions,
                profile = draft.verificationProfile.normalizedFor(ProjectTaskKind.Verification),
                includeScratchpad = draft.includeScratchpadInVerification,
                linkedSpecTaskId = draft.plan.sourceSpecTaskId,
                linkedBuildTaskId = buildId,
                planSnapshot = draft.plan,
                verificationInstructions = verificationInstructions,
                createdAtMillis = now,
                updatedAtMillis = now,
            )).copy(
                title = "Verify ${draft.title.trim()}",
                instructions = verificationInstructions,
                profile = draft.verificationProfile.normalizedFor(ProjectTaskKind.Verification),
                includeScratchpad = draft.includeScratchpadInVerification,
                verificationInstructions = verificationInstructions,
                state = when {
                    restoringCompleted -> ProjectTaskState.Completed
                    reopeningForVerification -> ProjectTaskState.Draft
                    invalidatingReview && existingBuild?.attempts?.isNotEmpty() == true -> ProjectTaskState.Waiting
                    !draft.reviewEnabled && reviewWasEnabled && existingBuild.state != ProjectTaskState.Completed -> ProjectTaskState.Waiting
                    else -> existingVerification?.state ?: ProjectTaskState.Draft
                },
                updatedAtMillis = now,
            )
        }
        updateProject(draft.projectId) { state ->
            val pair = listOfNotNull(build, review, verification).associateBy { it.id }
            val existingIds = state.tasks.mapTo(mutableSetOf()) { it.id }
            state.copy(
                tasks = state.tasks.filterNot { it.id == removedReviewId || it.id == removedVerificationId }.map { pair[it.id] ?: it } +
                    listOfNotNull(build, review, verification).filterNot { it.id in existingIds },
            )
        }
        persist()
        return buildId
    }

    override suspend fun startBuildPair(buildTaskId: String) {
        ready.await()
        val build = projectTask(buildTaskId)?.takeIf { it.kind == ProjectTaskKind.Build } ?: return
        val review = build.linkedReviewTaskId?.let(::projectTask)
        val verification = build.linkedVerificationTaskId?.let(::projectTask)
        if (isStageBusy(build) || isStageBusy(review) || isStageBusy(verification) || build.state == ProjectTaskState.Completed) return
        updateProjectTask(buildTaskId) { it.copy(paused = false, lastError = null) }
        startBuildAttempt(buildTaskId)
    }

    override fun pauseBuildPair(buildTaskId: String) {
        val build = projectTask(buildTaskId) ?: return
        updateProjectTask(buildTaskId) {
            it.copy(paused = true, state = if (isStageBusy(it)) it.state else ProjectTaskState.Paused, updatedAtMillis = System.currentTimeMillis())
        }
        build.linkedReviewTaskId?.let { reviewId ->
            updateProjectTask(reviewId) {
                it.copy(state = if (isStageBusy(it)) it.state else if (build.reviewEnabled) ProjectTaskState.Paused else ProjectTaskState.Disabled)
            }
        }
        build.linkedVerificationTaskId?.let { verificationId ->
            updateProjectTask(verificationId) { it.copy(state = if (isStageBusy(it)) it.state else ProjectTaskState.Paused) }
        }
        scope.launch { persist() }
    }

    override fun stopBuildPair(buildTaskId: String) {
        val build = projectTask(buildTaskId) ?: return
        val review = build.linkedReviewTaskId?.let(::projectTask)
        val verification = build.linkedVerificationTaskId?.let(::projectTask)
        val activeRunId = (build.attempts + review?.attempts.orEmpty() + verification?.attempts.orEmpty())
            .sortedByDescending { it.createdAtMillis }
            .firstOrNull { attempt ->
                currentTask(attempt.runId)?.let { run ->
                    run.isActive || run.status == AgentTaskStatus.WaitingForInput
                } == true
            }
            ?.runId
        activeRunId?.let(::stop)
        updateProjectTask(buildTaskId) { it.copy(paused = true, state = ProjectTaskState.NeedsAttention, lastError = "current workflow run was stopped") }
        review?.let { item -> updateProjectTask(item.id) { it.copy(state = ProjectTaskState.NeedsAttention) } }
        verification?.let { item -> updateProjectTask(item.id) { it.copy(state = ProjectTaskState.NeedsAttention) } }
        scope.launch { persist() }
    }

    override suspend fun resumeBuildPair(buildTaskId: String) {
        ready.await()
        val build = projectTask(buildTaskId)?.takeIf { it.kind == ProjectTaskKind.Build } ?: return
        val review = build.linkedReviewTaskId?.let(::projectTask)
        val verification = build.linkedVerificationTaskId?.let(::projectTask)
        if (isStageBusy(build) || isStageBusy(review) || isStageBusy(verification)) return
        updateProjectTask(buildTaskId) { it.copy(paused = false, reviewReopenedCompleted = false, lastError = null) }
        val buildRun = latestCompletedBuildRun(build)
        val latestReviewVerdict = review?.reviewVerdicts
            ?.lastOrNull { it.reviewedBuildRunId == buildRun?.id && it.reviewGeneration == build.reviewGeneration }
        when {
            buildRun == null -> startBuildAttempt(buildTaskId)
            build.reviewEnabled && latestReviewVerdict?.status == ProjectReviewStatus.ChangesRequested -> startBuildAttempt(buildTaskId)
            build.reviewEnabled && currentReviewApproval(review, buildRun.id, build.reviewGeneration) == null -> startReviewAttempt(buildTaskId)
            build.linkedVerificationTaskId != null -> startVerificationAttempt(buildTaskId)
            else -> completeBuildWithoutVerification(buildTaskId)
        }
    }

    override suspend fun startRecoveryFollowUp(buildTaskId: String, followUp: String): String? {
        ready.await()
        val build = projectTask(buildTaskId)?.takeIf { it.kind == ProjectTaskKind.Build }
            ?: return "This Build workflow is no longer available."
        val review = build.linkedReviewTaskId?.let(::projectTask)
        val verification = build.linkedVerificationTaskId?.let(::projectTask)
        when {
            followUp.isBlank() -> return "Describe the issue found during testing before starting a follow-up."
            !build.recoveryMode && build.state != ProjectTaskState.Completed -> return "Finish or pause the current workflow stage before adding a follow-up."
            isStageBusy(build) || isStageBusy(review) || isStageBusy(verification) -> return "Wait for the current workflow run to finish before adding another follow-up."
            workflowBudgetReached(build) -> return "The workflow's reported-cost guardrail has been reached."
        }
        val project = _projects.value[build.projectId] ?: return "This Project is no longer available."
        val directory = projectDirectory(build.projectId) ?: return "The Project directory is unavailable."
        val recoveryWorkspace = build.worktreePath ?: build.workspacePath ?: directory
        if (!File(recoveryWorkspace).isDirectory) {
            setPairAttention(build, "the retained workflow worktree is missing")
            persist()
            return "The retained workflow workspace is missing."
        }
        val beginsRecovery = !build.recoveryMode
        val generation = if (beginsRecovery) build.reviewGeneration + 1 else build.reviewGeneration
        val attempt = build.attempts.count { it.stage == ProjectWorkflowStage.Build } + 1
        val scratchpad = project.scratchpad.takeIf { build.includeScratchpad && it.isNotBlank() }
        val prompt = recoveryBuildPrompt(build, followUp.trim(), scratchpad)
        updateProjectTask(build.id) {
            it.copy(
                state = ProjectTaskState.Queued,
                paused = false,
                recoveryMode = true,
                reviewStale = true,
                reviewGeneration = generation,
                lastError = null,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        review?.let { item ->
            updateProjectTask(item.id) { it.copy(state = ProjectTaskState.Waiting, reviewGeneration = generation, lastError = null) }
        }
        persist()
        val run = createAndStart(
            build.profile.toAgentDraft(
                title = "Build follow-up: ${build.title}",
                prompt = prompt,
                projectId = build.projectId,
                directory = directory,
                planMode = false,
                workflowTaskId = build.id,
                stage = ProjectWorkflowStage.Build,
                attempt = attempt,
                existingWorktreePath = recoveryWorkspace,
                existingBranchName = build.branchName,
            ),
        )
        appendAttempt(build.id, run, ProjectWorkflowStage.Build, attempt, prompt, build.profile, scratchpad, isRecoveryFollowUp = true)
        updateProjectTask(build.id) {
            it.copy(
                workspacePath = run.cwd ?: it.workspacePath,
                worktreePath = run.worktreePath ?: it.worktreePath,
                branchName = run.branchName ?: it.branchName,
                worktreeOwnerRunId = if (run.ownsWorktree) run.id else it.worktreeOwnerRunId,
            )
        }
        persist()
        reconcileWorkflowRun(run.id)
        return null
    }

    override suspend fun startRecoveryReview(buildTaskId: String): String? {
        ready.await()
        val build = projectTask(buildTaskId)?.takeIf { it.kind == ProjectTaskKind.Build }
            ?: return "This Build workflow is no longer available."
        val review = build.linkedReviewTaskId?.let(::projectTask) ?: return "Enable a Review gate before starting a recovery review."
        val verification = build.linkedVerificationTaskId?.let(::projectTask)
        when {
            !build.recoveryMode || !build.reviewStale -> return "Add and finish at least one recovery follow-up before reviewing."
            !build.reviewEnabled -> return "Enable a Review gate before starting a recovery review."
            isStageBusy(build) || isStageBusy(review) || isStageBusy(verification) -> return "Wait for the current workflow run to finish before reviewing."
            workflowBudgetReached(build) -> return "The workflow's reported-cost guardrail has been reached."
        }
        startReviewAttempt(buildTaskId, manualRecovery = true)
        return null
    }

    override suspend fun deleteTask(taskId: String, cascade: Boolean) {
        ready.await()
        val task = projectTask(taskId) ?: return
        val removeIds = when (task.kind) {
            ProjectTaskKind.Spec -> {
                val children = _projects.value[task.projectId]?.tasks.orEmpty().filter { it.linkedSpecTaskId == task.id }
                if (children.isNotEmpty() && !cascade) return
                setOf(task.id) + children.map { it.id } + children.mapNotNull { it.linkedReviewTaskId } + children.mapNotNull { it.linkedVerificationTaskId }
            }
            ProjectTaskKind.Build -> setOfNotNull(task.id, task.linkedReviewTaskId, task.linkedVerificationTaskId)
            ProjectTaskKind.Review, ProjectTaskKind.Verification -> {
                val linkedBuild = task.linkedBuildTaskId?.let(::projectTask)
                setOfNotNull(linkedBuild?.id, linkedBuild?.linkedReviewTaskId, linkedBuild?.linkedVerificationTaskId)
            }
        }
        if (_projects.value[task.projectId]?.tasks.orEmpty().any { it.id in removeIds && it.isActive }) return
        updateProject(task.projectId) { state -> state.copy(tasks = state.tasks.filterNot { it.id in removeIds }) }
        persist()
    }

    override suspend fun deleteProject(projectId: String) {
        ready.await()
        val workflow = _projects.value[projectId]
        val runIds = workflow?.tasks.orEmpty().flatMap { it.attempts }.map { it.runId }.distinct()
        runIds.forEach { runId ->
            currentTask(runId)?.let { delete(runId, removeWorktree = it.ownsWorktree) }
        }
        _projects.update { it - projectId }
        persist()
    }

    override suspend fun createAndStart(draft: AgentTaskDraft): AgentTask {
        ready.await()
        _providerDefaults.update { it + (draft.agent to draft.providerDefaults()) }
        _lastUsedAgent.value = draft.agent
        val discoveredSkillPaths = if (draft.skills.isEmpty()) {
            emptySet()
        } else {
            withContext(Dispatchers.IO) { discoverSkills(draft.agent, draft.existingWorktreePath ?: draft.directory) }
                .mapTo(mutableSetOf()) { it.path }
        }
        val now = System.currentTimeMillis()
        val id = "task-" + UUID.randomUUID().toString().replace("-", "").take(10)
        var task = AgentTask(
            id = id,
            title = draft.title.ifBlank { draft.prompt.truncateForSummary(60) },
            prompt = draft.prompt,
            agent = draft.agent,
            projectId = draft.projectId,
            cwd = draft.existingWorktreePath ?: draft.directory,
            originDir = draft.directory,
            useWorktree = draft.useWorktree,
            worktreePath = draft.existingWorktreePath,
            branchName = draft.existingBranchName,
            ownsWorktree = false,
            workflowTaskId = draft.workflowTaskId,
            workflowStage = draft.workflowStage,
            workflowAttempt = draft.workflowAttempt,
            attachAndyMcp = draft.attachAndyMcp,
            autonomy = draft.autonomy,
            sandboxMode = draft.sandboxMode,
            planMode = draft.planMode,
            model = draft.model,
            reasoningEffort = draft.reasoningEffort,
            fastMode = draft.fastMode,
            imagePaths = draft.imagePaths,
            skills = draft.skills.filter { it.path in discoveredSkillPaths },
            goal = draft.goal,
            maxBudgetUsd = draft.maxBudgetUsd,
            status = AgentTaskStatus.Queued,
            // Claude accepts a caller-assigned session id; pre-assigning means resume
            // works even when output parsing fails mid-run.
            vendorSessionId = if (draft.agent == AgentKind.ClaudeCode) UUID.randomUUID().toString() else null,
            createdAtMillis = now,
        )

        val binary = binaryFor(task.agent)
        if (binary == null) {
            task = task.copy(
                status = AgentTaskStatus.Failed,
                errorMessage = unavailableCliMessage(task.agent),
                finishedAtMillis = now,
            )
            upsertTask(task)
            persist()
            return task
        }

        if (task.useWorktree) {
            val originDir = task.originDir
            if (originDir == null) {
                task = task.copy(
                    status = AgentTaskStatus.Failed,
                    errorMessage = "a project directory is required to create a worktree",
                    finishedAtMillis = System.currentTimeMillis(),
                )
                upsertTask(task)
                persist()
                return task
            }
            val created = withContext(Dispatchers.IO) { worktrees.create(originDir, task.id, task.agent, task.title) }
            task = created.fold(
                onSuccess = { task.copy(cwd = it.path, worktreePath = it.path, branchName = it.branch, ownsWorktree = true) },
                onFailure = {
                    task.copy(
                        status = AgentTaskStatus.Failed,
                        errorMessage = "worktree creation failed: ${it.message}",
                        finishedAtMillis = System.currentTimeMillis(),
                    )
                },
            )
            if (task.status == AgentTaskStatus.Failed) {
                upsertTask(task)
                persist()
                return task
            }
        }

        task.cwd?.let { cwd ->
            withContext(Dispatchers.IO) { worktrees.captureChangeBaseline(cwd) }?.let { baseline ->
                task = task.copy(changeBaselinePaths = baseline, hasChangeBaseline = true)
            }
        }

        upsertTask(task)
        persist()
        launchRun(task) { adapter, resolvedBinary, mcpUrl ->
            adapter.buildCommand(resolvedBinary, currentTask(task.id) ?: task, mcpUrl)
        }
        return task
    }

    override fun resume(taskId: String, followUp: String, imagePaths: List<String>, skills: List<AgentSkill>) {
        val task = currentTask(taskId) ?: return
        // A decision checkpoint has structured answers and must be resolved by
        // its chooser so a freeform chat message cannot leave stale UI behind.
        if (task.userInputRequest != null) return
        if (task.isActive) return
        val adapter = adapters[task.agent] ?: return
        if (!adapter.supportsHeadlessResume || task.vendorSessionId == null) return

        _lastUsedAgent.value = task.agent

        val now = System.currentTimeMillis()
        val skillDirectory = task.worktreePath ?: task.cwd
        val selectedSkills = skills.filter { skill ->
            this.skills(task.agent, skillDirectory).value.any { it.path == skill.path }
        }
        val followUpForCli = promptWithGoalHint(promptWithSkillHints(followUp, selectedSkills), task.goal)
        appendEvents(taskId, listOf(AgentEvent.UserMessage(now, followUp, selectedSkills, imagePaths)))
        writeAndyTranscriptLine(taskId, followUp, selectedSkills, now, imagePaths)
        val queued = task.copy(
            status = AgentTaskStatus.Queued,
            exitCode = null,
            errorMessage = null,
            finishedAtMillis = null,
            unread = false,
        )
        upsertTask(queued)
        scope.launch { persist() }
        launchRun(queued) { resumeAdapter, binary, mcpUrl ->
            resumeAdapter.buildResumeCommand(binary, currentTask(taskId) ?: queued, followUpForCli, imagePaths, mcpUrl)
                ?: error("resume not supported")
        }
    }

    override fun respondToUserInput(taskId: String, requestId: String, answers: Map<String, String>) {
        val task = currentTask(taskId) ?: return
        val request = task.userInputRequest?.takeIf { it.id == requestId } ?: return
        if (task.status != AgentTaskStatus.WaitingForInput) return
        val normalizedAnswers = request.questions.associate { question ->
            question.id to answers[question.id].orEmpty().trim()
        }
        if (normalizedAnswers.values.any { it.isBlank() }) return

        val response = request.responseForAgent(normalizedAnswers)
        val now = System.currentTimeMillis()
        appendEvents(taskId, listOf(AgentEvent.UserMessage(now, response)))
        writeAndyTranscriptLine(taskId, response, emptyList(), now)

        val adapter = adapters[task.agent] ?: return
        val canResume = adapter.supportsHeadlessResume && task.vendorSessionId != null
        val next = if (canResume) {
            task.copy(
                status = AgentTaskStatus.Queued,
                userInputRequest = null,
                exitCode = null,
                errorMessage = null,
                finishedAtMillis = null,
                unread = false,
            )
        } else {
            val priorPrompt = task.continuationPrompt ?: task.implementationPrompt ?: task.prompt
            task.copy(
                status = AgentTaskStatus.Queued,
                userInputRequest = null,
                continuationPrompt = "$priorPrompt\n\nDecision checkpoint answers:\n$response",
                exitCode = null,
                errorMessage = null,
                finishedAtMillis = null,
                unread = false,
            )
        }
        upsertTask(next)
        scope.launch { persist() }
        if (canResume) {
            launchRun(next) { resumeAdapter, binary, mcpUrl ->
                resumeAdapter.buildResumeCommand(binary, currentTask(taskId) ?: next, response, emptyList(), mcpUrl)
                    ?: error("resume not supported")
            }
        } else {
            launchRun(next) { nextAdapter, binary, mcpUrl ->
                nextAdapter.buildCommand(binary, currentTask(taskId) ?: next, mcpUrl)
            }
        }
    }

    override suspend fun startImplementation(taskId: String) {
        ready.await()
        val task = currentTask(taskId) ?: return
        val completedPlan = task.completedPlanText?.takeIf { it.isNotBlank() } ?: return
        if (task.status != AgentTaskStatus.Completed || !task.planMode || task.isActive || task.workflowTaskId != null) return

        _lastUsedAgent.value = task.agent
        val now = System.currentTimeMillis()
        val implementationPrompt = implementationPromptFor(task.prompt, completedPlan)
        val implementationBaseline = task.cwd?.let { cwd ->
            withContext(Dispatchers.IO) { worktrees.captureChangeBaseline(cwd) }
        }
        val implementationTask = task.copy(
            planMode = false,
            sandboxMode = AgentSandboxMode.WorkspaceWrite,
            implementationPrompt = implementationPrompt,
            // A handoff intentionally starts a new provider thread. Claude requires a
            // caller-assigned id while the other CLIs report one after startup.
            vendorSessionId = if (task.agent == AgentKind.ClaudeCode) UUID.randomUUID().toString() else null,
            status = AgentTaskStatus.Queued,
            startedAtMillis = null,
            finishedAtMillis = null,
            exitCode = null,
            errorMessage = null,
            totalCostUsd = null,
            costIsEstimated = false,
            inputTokens = null,
            outputTokens = null,
            contextTokens = null,
            contextWindowTokens = null,
            changeBaselinePaths = implementationBaseline.orEmpty(),
            hasChangeBaseline = implementationBaseline != null,
            completedChanges = null,
            unread = false,
        )
        appendEvents(taskId, listOf(AgentEvent.UserMessage(now, implementationPrompt, task.skills)))
        writeAndyTranscriptLine(taskId, implementationPrompt, task.skills, now)
        upsertTask(implementationTask)
        persist()
        launchRun(implementationTask) { adapter, binary, mcpUrl ->
            adapter.buildCommand(binary, currentTask(taskId) ?: implementationTask, mcpUrl)
        }
    }

    override fun queueFollowUp(taskId: String, followUp: String, imagePaths: List<String>, skills: List<AgentSkill>) {
        val task = currentTask(taskId) ?: return
        val adapter = adapters[task.agent] ?: return
        if (!task.isActive || !adapter.supportsHeadlessResume || task.vendorSessionId == null) return

        val text = followUp.trim()
        if (text.isBlank() && imagePaths.isEmpty()) return
        val skillDirectory = task.worktreePath ?: task.cwd
        val selectedSkills = skills.filter { skill ->
            this.skills(task.agent, skillDirectory).value.any { it.path == skill.path }
        }
        updateTask(taskId) { current ->
            if (!current.isActive) {
                current
            } else {
                current.copy(
                    queuedFollowUps = current.queuedFollowUps + AgentQueuedFollowUp(
                        text = text,
                        imagePaths = imagePaths,
                        skills = selectedSkills,
                    ),
                )
            }
        }
        scope.launch { persist() }
    }

    override fun removeQueuedFollowUp(taskId: String, queueIndex: Int) {
        val task = currentTask(taskId) ?: return
        if (queueIndex !in task.queuedFollowUps.indices) return
        updateTask(taskId) { current ->
            current.copy(queuedFollowUps = current.queuedFollowUps.filterIndexed { index, _ -> index != queueIndex })
        }
        scope.launch { persist() }
    }

    override suspend fun retry(taskId: String) {
        ready.await()
        val task = currentTask(taskId) ?: return
        if (task.status != AgentTaskStatus.Failed && task.status != AgentTaskStatus.Unknown) return

        _lastUsedAgent.value = task.agent

        // A retry is a fresh run of the same chat, rather than a provider-specific
        // resume. In particular, Claude needs a new caller-assigned session id.
        val retried = task.copy(
            status = AgentTaskStatus.Queued,
            vendorSessionId = if (task.agent == AgentKind.ClaudeCode) UUID.randomUUID().toString() else null,
            startedAtMillis = null,
            finishedAtMillis = null,
            exitCode = null,
            errorMessage = null,
            totalCostUsd = null,
            costIsEstimated = false,
            inputTokens = null,
            outputTokens = null,
            contextTokens = null,
            contextWindowTokens = null,
            unread = false,
        )
        store.deleteTranscript(taskId)
        eventFlows[taskId]?.value = emptyList()
        historyLoaded.remove(taskId)
        upsertTask(retried)
        persist()
        launchRun(retried) { adapter, resolvedBinary, mcpUrl ->
            adapter.buildCommand(resolvedBinary, currentTask(taskId) ?: retried, mcpUrl)
        }
    }

    override fun updateGoal(taskId: String, goal: String?) {
        val normalizedGoal = goal?.trim()?.takeIf { it.isNotBlank() }
        val task = currentTask(taskId) ?: return
        if (task.goal == normalizedGoal) return
        updateTask(taskId) { it.copy(goal = normalizedGoal) }
        scope.launch { persist() }
    }

    private fun launchRun(task: AgentTask, argvBuilder: (AgentCliAdapter, String, String?) -> List<String>) {
        val handle = TaskHandle()
        handles[task.id] = handle
        handle.job = scope.launch(Dispatchers.IO) {
            ready.await()
            slots.withPermit {
                if (handle.stopRequested) return@withPermit
                runProcess(task.id, handle, argvBuilder)
            }
        }
    }

    private suspend fun runProcess(
        taskId: String,
        handle: TaskHandle,
        argvBuilder: (AgentCliAdapter, String, String?) -> List<String>,
    ) {
        val task = currentTask(taskId) ?: return
        val adapter = adapters.getValue(task.agent)
        val binary = binaryFor(task.agent)
        if (binary == null) {
            finishTask(taskId, AgentTaskStatus.Failed, exitCode = null, error = unavailableCliMessage(task.agent))
            return
        }

        val mcpUrl = if (task.attachAndyMcp) {
            runCatching { prepareMcp(task.agent) }.getOrElse { error ->
                finishTask(taskId, AgentTaskStatus.Failed, exitCode = null, error = "failed to prepare Andy MCP: ${error.message}")
                return
            }
        } else {
            null
        }
        val argv = runCatching { argvBuilder(adapter, binary, mcpUrl) }.getOrElse {
            finishTask(taskId, AgentTaskStatus.Failed, exitCode = null, error = it.message ?: "failed to build command")
            return
        }
        val projectEnv = task.projectId?.let { projectId ->
            runCatching { actionConfig.load().projects.firstOrNull { it.id == projectId }?.env }.getOrNull()
        }.orEmpty()

        updateTask(taskId) { it.copy(status = AgentTaskStatus.Running, startedAtMillis = System.currentTimeMillis()) }
        persist()
        reconcileWorkflowRun(taskId)

        val process = runCatching {
            ProcessBuilder(argv)
                .redirectErrorStream(true)
                // Agent CLIs block (codex) or stall (claude) reading an open stdin pipe.
                .redirectInput(nullInputFile())
                .apply {
                    task.cwd?.let { directory(File(it)) }
                    // IDE terminals and local LLM proxies inject env that breaks vendor CLIs
                    // (Cursor NODE_OPTIONS bootloaders, ANTHROPIC_BASE_URL → Ollama, etc.).
                    // Scrub those first; project env can intentionally put them back.
                    scrubInheritedAgentEnvironment(environment())
                    environment().putAll(projectEnv)
                }
                .start()
        }.getOrElse { error ->
            finishTask(taskId, AgentTaskStatus.Failed, exitCode = null, error = "failed to start: ${error.message}")
            return
        }
        writeLaunchDiagnostics(taskId, binary, argv, projectEnv)
        handle.process = process
        if (handle.stopRequested) {
            killTree(process)
            finishTask(taskId, AgentTaskStatus.Stopped, exitCode = null, error = null)
            return
        }

        var lastResult: AgentEvent.TaskResult? = null
        var lastError: String? = null
        var lastAssistantText: String? = null
        var requestedInput: AgentUserInputRequest? = null
        val turnAssistantText = StringBuilder()
        val rawTail = ArrayDeque<String>()
        val rawPlanOutput = StringBuilder()
        var structuredPlanText: String? = null

        val transcript = store.transcriptFile(taskId)
        transcript.parentFile?.mkdirs()
        FileOutputStream(transcript, true).bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    val batch = mutableListOf<AgentEvent>()
                    var lastFlush = System.currentTimeMillis()
                    fun flush() {
                        if (batch.isEmpty()) return
                        appendEvents(taskId, batch.toList())
                        batch.clear()
                        writer.flush()
                        lastFlush = System.currentTimeMillis()
                    }
                    for (line in lines) {
                        writer.appendLine(line)
                        if (task.planMode) {
                            rawPlanOutput.appendLine(line)
                            successfulCursorPlanText(line)?.let { structuredPlanText = it }
                        }
                        val now = System.currentTimeMillis()
                        val events = runCatching { adapter.parseLine(line, now) }
                            .getOrElse { listOf(AgentEvent.Raw(now, line)) }
                            .mapNotNull { event ->
                                val parsed = when (event) {
                                    is AgentEvent.AssistantText -> {
                                        if (event.isStreamDelta) {
                                            turnAssistantText.append(event.text)
                                        } else {
                                            if (turnAssistantText.isNotEmpty()) turnAssistantText.append("\n\n")
                                            turnAssistantText.append(event.text)
                                        }
                                        parseAgentUserInput(turnAssistantText.toString())
                                    }
                                    is AgentEvent.TaskResult -> parseAgentUserInputFromSources(event.finalText, turnAssistantText.toString())
                                    is AgentEvent.Raw -> parseAgentUserInput(event.line)
                                    else -> null
                                }
                                if (parsed == null) {
                                    when (event) {
                                        is AgentEvent.AssistantText -> {
                                            if (containsPartialAgentUserInputMarkup(turnAssistantText.toString())) null else event
                                        }
                                        else -> event
                                    }
                                } else {
                                    if (requestedInput == null) requestedInput = parsed.request
                                    when (event) {
                                        is AgentEvent.AssistantText -> when {
                                            parsed.visibleText.isBlank() -> null
                                            event.isStreamDelta -> null
                                            else -> event.copy(text = parsed.visibleText, isStreamDelta = false)
                                        }
                                        is AgentEvent.TaskResult -> event.copy(finalText = parsed.visibleText.takeIf { it.isNotBlank() })
                                        is AgentEvent.Raw -> event.copy(line = parsed.visibleText).takeIf { it.line.isNotBlank() }
                                        else -> event
                                    }
                                }
                            }
                            .withEstimatedCosts(currentTask(taskId) ?: task)
                        for (event in events) {
                            when (event) {
                                is AgentEvent.SessionStarted -> event.sessionId?.let { sessionId ->
                                    updateTask(taskId) { current ->
                                        if (current.vendorSessionId == null) current.copy(vendorSessionId = sessionId) else current
                                    }
                                }
                                is AgentEvent.TaskResult -> lastResult = event
                                is AgentEvent.ContextUsage -> updateTask(taskId) { current ->
                                    current.copy(
                                        contextTokens = event.usedTokens ?: current.contextTokens,
                                        contextWindowTokens = event.windowTokens ?: current.contextWindowTokens,
                                    )
                                }
                                is AgentEvent.ToolResult -> event.quotaWindows.takeIf { it.isNotEmpty() }?.let { windows ->
                                    updateQuota(task.agent, windows, event.atMillis)
                                }
                                is AgentEvent.TaskError -> lastError = event.message
                                is AgentEvent.AssistantText -> {
                                    lastAssistantText = if (event.isStreamDelta) {
                                        lastAssistantText.orEmpty() + event.text
                                    } else {
                                        event.text
                                    }
                                }
                                is AgentEvent.Raw -> {
                                    rawTail.addLast(event.line)
                                    if (rawTail.size > 20) rawTail.removeFirst()
                                }
                                else -> Unit
                            }
                        }
                        // The CLI's normal terminal event would look like a completed
                        // task while Andy is showing an unanswered checkpoint.
                        batch += if (requestedInput == null) events else events.filterNot { it is AgentEvent.TaskResult }
                        if (batch.size >= 64 || System.currentTimeMillis() - lastFlush >= 100) flush()
                    }
                    flush()
                }
            }
        }

        if (requestedInput == null && turnAssistantText.isNotEmpty()) {
            parseAgentUserInputFromSources(turnAssistantText.toString())?.let { requestedInput = it.request }
        }

        val exitCode = runCatching { process.waitFor() }.getOrElse { -1 }
        val result = lastResult
        val waitingForInput = requestedInput?.takeIf { exitCode == 0 && lastError == null && !handle.stopRequested }
        val status = when {
            handle.stopRequested -> AgentTaskStatus.Stopped
            waitingForInput != null -> AgentTaskStatus.WaitingForInput
            exitCode == 0 && result?.success != false && lastError == null -> AgentTaskStatus.Completed
            else -> AgentTaskStatus.Failed
        }
        val fallbackText = lastAssistantText ?: rawTail.joinToString("\n").takeIf { it.isNotBlank() }
        val completedPlanText = if (status == AgentTaskStatus.Completed && task.planMode) {
            agentPlanTextCandidate(structuredPlanText)
                ?: agentPlanTextCandidate(result?.finalText)
                ?: agentPlanTextCandidate(lastAssistantText)
                ?: agentPlanTextCandidate(turnAssistantText.toString())
                ?: agentPlanTextCandidate(rawPlanOutput.toString())
                ?: agentPlanTextCandidate(fallbackText)
        } else {
            null
        }
        if (result == null && status != AgentTaskStatus.Stopped && status != AgentTaskStatus.WaitingForInput) {
            // Adapters without structured output (or failed runs) still get a terminal event.
            appendEvents(
                taskId,
                listOf(
                    AgentEvent.TaskResult(
                        atMillis = System.currentTimeMillis(),
                        success = status == AgentTaskStatus.Completed,
                        finalText = fallbackText,
                    ),
                ),
            )
        }
        updateTask(taskId) { current ->
            current.copy(
                totalCostUsd = result?.costUsd ?: current.totalCostUsd,
                costIsEstimated = result?.costIsEstimated ?: current.costIsEstimated,
                inputTokens = result?.inputTokens ?: current.inputTokens,
                outputTokens = result?.outputTokens ?: current.outputTokens,
                completedPlanText = completedPlanText ?: current.completedPlanText,
                completedResultText = if (status == AgentTaskStatus.WaitingForInput) {
                    current.completedResultText
                } else {
                    result?.finalText?.takeIf { it.isNotBlank() }
                        ?: fallbackText
                        ?: current.completedResultText
                },
            )
        }
        if (waitingForInput != null) {
            waitForUserInput(taskId, waitingForInput, exitCode)
            return
        }
        val failureError = if (status == AgentTaskStatus.Failed) {
            agentFailureMessage(
                lastError = lastError,
                authHint = authFailureHint(fallbackText, taskId),
                result = result,
                fallbackText = fallbackText,
                exitCode = exitCode,
            )
        } else {
            null
        }
        if (status == AgentTaskStatus.Failed) {
            appendLaunchDiagnostics(
                taskId,
                buildString {
                    appendLine("exitCode=$exitCode")
                    appendLine("error=$failureError")
                    appendLine("resultSuccess=${result?.success}")
                    appendLine("resultText=${result?.finalText.orEmpty().take(2000)}")
                    appendLine("fallbackText=${fallbackText.orEmpty().take(2000)}")
                    appendLine("rawTail:")
                    rawTail.forEach { appendLine(it.take(500)) }
                },
            )
        }
        finishTask(
            taskId = taskId,
            status = status,
            exitCode = exitCode,
            error = failureError,
        )
    }

    private fun waitForUserInput(taskId: String, request: AgentUserInputRequest, exitCode: Int) {
        updateTask(taskId) { task ->
            if (task.status == AgentTaskStatus.Running) {
                task.copy(
                    status = AgentTaskStatus.WaitingForInput,
                    userInputRequest = request,
                    exitCode = exitCode,
                    finishedAtMillis = System.currentTimeMillis(),
                    unread = true,
                )
            } else {
                task
            }
        }
        handles.remove(taskId)
        scope.launch {
            persist()
            reconcileWorkflowRun(taskId)
        }
    }

    private fun authFailureHint(tail: String?, taskId: String): String? {
        val text = tail?.lowercase() ?: return null
        val authIndicators = listOf("not logged in", "please log in", "please run /login", "unauthorized", "authentication", "invalid api key", "login required")
        if (authIndicators.none { it in text }) return null
        val cli = currentTask(taskId)?.agent?.cliName ?: return null
        return "Not logged in — run `$cli` in a terminal and sign in, then retry"
    }

    private fun implementationPromptFor(originalRequest: String, completedPlan: String): String = buildString {
        append("Begin implementation. Implement the completed plan below: make the edits and run the relevant verification.\n\n")
        append("Original request:\n")
        append(originalRequest.trim())
        append("\n\nCompleted plan:\n")
        append(completedPlan.trim())
    }

    override fun stop(taskId: String) {
        val waiting = currentTask(taskId)?.takeIf { it.status == AgentTaskStatus.WaitingForInput }
        if (waiting != null) {
            updateTask(taskId) {
                it.copy(
                    status = AgentTaskStatus.Stopped,
                    userInputRequest = null,
                    errorMessage = null,
                    finishedAtMillis = System.currentTimeMillis(),
                )
            }
            scope.launch {
                persist()
                reconcileWorkflowRun(taskId)
            }
            return
        }
        val handle = handles[taskId] ?: return
        handle.stopRequested = true
        scope.launch(Dispatchers.IO) {
            val process = handle.process
            if (process != null) {
                killTree(process)
            } else {
                // Still queued: cancel before it ever starts.
                handle.job?.cancel()
                finishTask(taskId, AgentTaskStatus.Stopped, exitCode = null, error = null)
            }
        }
    }

    override suspend fun delete(taskId: String, removeWorktree: Boolean) {
        val task = currentTask(taskId) ?: return
        if (task.isActive) {
            stop(taskId)
        }
        handles.remove(taskId)
        eventFlows.remove(taskId)
        historyLoaded.remove(taskId)
        _tasks.update { list -> list.filterNot { it.id == taskId } }
        store.deleteTranscript(taskId)
        task.workflowTaskId?.let { projectTaskId -> detachDeletedWorkflowRun(projectTaskId, taskId) }
        val worktreePath = task.worktreePath
        if (removeWorktree && task.ownsWorktree && worktreePath != null) {
            task.originDir?.let { originDir ->
                withContext(Dispatchers.IO) { worktrees.remove(originDir, worktreePath, task.branchName) }
            }
        }
        persist()
    }

    private fun detachDeletedWorkflowRun(projectTaskId: String, runId: String) {
        val workflowTask = projectTask(projectTaskId) ?: return
        if (workflowTask.attempts.none { it.runId == runId }) return
        updateProjectTask(projectTaskId) { task ->
            val attempts = task.attempts.filterNot { it.runId == runId }
            when (task.kind) {
                ProjectTaskKind.Spec -> task.copy(
                    attempts = attempts,
                    state = when {
                        task.planVersions.isNotEmpty() -> ProjectTaskState.Completed
                        attempts.isEmpty() -> ProjectTaskState.Draft
                        else -> task.state
                    },
                    lastError = when {
                        task.planVersions.isNotEmpty() || attempts.isEmpty() -> null
                        else -> task.lastError
                    },
                    updatedAtMillis = System.currentTimeMillis(),
                )
                else -> task.copy(
                    attempts = attempts,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
        }
    }

    override fun events(taskId: String): StateFlow<List<AgentEvent>> {
        val task = currentTask(taskId) ?: return emptyEvents
        val flow = eventFlows.computeIfAbsent(taskId) { MutableStateFlow(emptyList()) }
        if (!task.isActive && historyLoaded.add(taskId) && flow.value.isEmpty()) {
            scope.launch(Dispatchers.IO) { loadHistoricalEvents(task, flow) }
        }
        return flow
    }

    private fun loadHistoricalEvents(task: AgentTask, flow: MutableStateFlow<List<AgentEvent>>) {
        val file = store.transcriptFile(task.id)
        if (!file.exists()) return
        val adapter = adapters[task.agent] ?: return
        val at = task.createdAtMillis
        val events = mutableListOf<AgentEvent>()
        runCatching {
            file.useLines { lines ->
                for (line in lines) {
                    val andyMessage = parseAndyTranscriptLine(line)
                    if (andyMessage != null) {
                        events += andyMessage.copy(atMillis = andyMessage.atMillis.takeIf { it > 0 } ?: at)
                    } else {
                        events += runCatching { adapter.parseLine(line, at) }
                            .getOrElse { listOf(AgentEvent.Raw(at, line)) }
                            .withEstimatedCosts(task)
                    }
                }
            }
        }
        flow.value = coalesceStreamDeltas(emptyList(), events).takeLast(MAX_EVENTS_IN_MEMORY)
    }

    override fun interactiveResumeCommand(taskId: String): String? {
        val task = currentTask(taskId) ?: return null
        val adapter = adapters[task.agent] ?: return null
        val binary = binaryFor(task.agent) ?: task.agent.cliName
        val changeDirectory = task.cwd?.let { "cd ${shellQuote(it)} && " }.orEmpty()
        return changeDirectory + adapter.interactiveResumeCommand(binary, task)
    }

    override suspend fun openInTerminal(taskId: String): CommandResult = withContext(Dispatchers.IO) {
        val command = interactiveResumeCommand(taskId)
            ?: return@withContext CommandResult.failure("task not found")
        val osName = System.getProperty("os.name")?.lowercase().orEmpty()
        if (!osName.contains("mac")) {
            return@withContext CommandResult.failure("Opening a terminal is only automated on macOS — the command has been copied instead")
        }
        val escaped = command.replace("\\", "\\\\").replace("\"", "\\\"")
        runCatching {
            val process = ProcessBuilder(
                "osascript",
                "-e", "tell application \"Terminal\" to activate",
                "-e", "tell application \"Terminal\" to do script \"$escaped\"",
            ).redirectErrorStream(true).start()
            process.waitFor(10, TimeUnit.SECONDS)
            if (process.exitValue() == 0) {
                CommandResult.success("opened Terminal")
            } else {
                CommandResult.failure(process.inputStream.bufferedReader().readText().truncateForSummary())
            }
        }.getOrElse { CommandResult.failure(it.message ?: "failed to open Terminal") }
    }

    override suspend fun openSkill(path: String): CommandResult = withContext(Dispatchers.IO) {
        val skillFile = File(path)
        if (!skillFile.isFile) return@withContext CommandResult.failure("skill file no longer exists")
        val osName = System.getProperty("os.name")?.lowercase().orEmpty()
        val command = when {
            osName.contains("mac") -> listOf("open", skillFile.absolutePath)
            osName.contains("win") -> listOf("cmd", "/c", "start", "", skillFile.absolutePath)
            else -> listOf("xdg-open", skillFile.absolutePath)
        }
        runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.waitFor(10, TimeUnit.SECONDS)
            if (process.exitValue() == 0) CommandResult.success("opened skill")
            else CommandResult.failure(process.inputStream.bufferedReader().readText().truncateForSummary())
        }.getOrElse { CommandResult.failure(it.message ?: "failed to open skill") }
    }

    override suspend fun worktreeDiffSummary(taskId: String): String? = withContext(Dispatchers.IO) {
        val task = currentTask(taskId) ?: return@withContext null
        val path = task.worktreePath ?: return@withContext null
        worktrees.diffSummary(path)
    }

    override suspend fun changeSummary(taskId: String): AgentChangeSummary? = withContext(Dispatchers.IO) {
        val task = currentTask(taskId) ?: return@withContext null
        task.completedChanges?.let { return@withContext it.summary }
        if (!task.hasChangeBaseline) return@withContext null
        val cwd = task.cwd ?: return@withContext null
        worktrees.changeSummary(cwd, task.changeBaselinePaths)
    }

    override suspend fun fileDiff(taskId: String, relativePath: String): AgentFileDiff? = withContext(Dispatchers.IO) {
        val task = currentTask(taskId) ?: return@withContext null
        task.completedChanges?.diffs?.get(relativePath)?.let { return@withContext it }
        val cwd = task.cwd ?: return@withContext null
        worktrees.fileDiff(cwd, relativePath)
    }

    override suspend fun refreshCliStatuses() {
        ready.await()
        val statuses = withContext(Dispatchers.IO) { locator.locateAll(binaryOverrides) }
        _cliStatuses.value = statuses
        val models = withContext(Dispatchers.IO) {
            statuses
                .mapNotNull { status ->
                    val binary = status.binaryPath?.takeIf { status.available } ?: return@mapNotNull null
                    async { modelProbe.query(status.kind, binary)?.let { status.kind to it } }
                }
                .awaitAll()
                .filterNotNull()
                .toMap()
        }
        if (models.isNotEmpty()) {
            _providerModels.update { current -> current + models }
            AgentModelCatalog.publishDiscovered(_providerModels.value)
        }
    }

    override suspend fun refreshProviderQuotas() {
        ready.await()
        quotaRefreshMutex.withLock {
            val fetched = withContext(Dispatchers.IO) {
                _cliStatuses.value.mapNotNull { status ->
                    status.binaryPath?.let { binary -> quotaProbe.query(status.kind, binary, _quotaAccess.value) }
                }
            }
            if (fetched.isNotEmpty()) {
                _providerQuotas.update { current -> current + fetched.toMap() }
            }
        }
    }

    override suspend fun isGitRepo(dir: String): Boolean = withContext(Dispatchers.IO) { worktrees.isGitRepo(dir) }

    override fun setQuotaAccess(agent: AgentKind, enabled: Boolean) {
        if (agent == AgentKind.Codex) return
        _quotaAccess.update { it.withAccess(agent, enabled) }
        if (!enabled) {
            quotaProbe.clearAccountAccess(agent)
            _providerQuotas.update { current ->
                current.filterNot { (kind, quota) -> kind == agent && quota.source == AgentQuotaSource.ProviderQuery }
            }
        }
        scope.launch {
            persist()
            if (enabled) refreshProviderQuotas()
        }
    }

    private suspend fun prepareMcp(agent: AgentKind): String? = mcpMutex.withLock {
        val port = runCatching { workspaceStore.load().mcpServerPort }.getOrElse { 8565 }
        val isRunning = runCatching { mcp.running.first() }.getOrElse { false }
        if (!isRunning) {
            val result = mcp.start(port)
            check(result.isSuccess) { result.stderr.ifBlank { "server failed to start" } }
        }
        when (agent) {
            // Per-invocation wiring, no config file edits.
            AgentKind.ClaudeCode -> "http://127.0.0.1:$port/mcp-http"
            AgentKind.Codex -> "http://127.0.0.1:$port/mcp"
            // These two only support config-file registration; write it and pass no URL.
            AgentKind.Cursor -> {
                mcp.writeConfig("Cursor", port)
                null
            }
            AgentKind.Antigravity -> {
                mcp.writeConfig("Antigravity", port)
                null
            }
        }
    }

    private fun binaryFor(agent: AgentKind): String? {
        val status = _cliStatuses.value.firstOrNull { it.kind == agent }
        return when {
            status?.ready == true -> status.binaryPath
            status != null -> null
            else -> binaryOverrides[agent.cliName]?.takeIf { File(it).canExecute() }
        }
    }

    private fun unavailableCliMessage(agent: AgentKind): String {
        val issue = _cliStatuses.value.firstOrNull { it.kind == agent }?.issue
        return issue?.let { "${it.title}: ${it.detail}" }
            ?: "${agent.cliName} CLI not found — install it or set a binary override in ~/.andy/agents.toml"
    }

    private fun defaultProjectState(projectId: String): ProjectWorkflowState {
        val agent = _lastUsedAgent.value ?: AgentKind.Codex
        val base = _providerDefaults.value[agent]?.toProjectProfile(agent) ?: ProjectAgentProfile(agent = agent)
        return ProjectWorkflowState(
            projectId = projectId,
            profiles = mapOf(
                ProjectTaskKind.Spec to base.normalizedFor(ProjectTaskKind.Spec),
                ProjectTaskKind.Build to base.normalizedFor(ProjectTaskKind.Build),
                ProjectTaskKind.Review to base.normalizedFor(ProjectTaskKind.Review),
                ProjectTaskKind.Verification to base.normalizedFor(ProjectTaskKind.Verification),
            ),
        )
    }

    private fun ProjectAgentProfile.normalizedFor(kind: ProjectTaskKind): ProjectAgentProfile {
        val normalized = copy(
            model = model?.trim()?.takeIf { it.isNotBlank() },
            maxBudgetUsd = maxBudgetUsd?.takeIf { it > 0.0 },
        )
        return when (kind) {
            ProjectTaskKind.Spec -> normalized.copy(
                autonomy = app.andy.model.AgentAutonomy.ReadOnly,
                sandboxMode = AgentSandboxMode.ReadOnly,
                useWorktree = false,
            )
            ProjectTaskKind.Build -> normalized
            ProjectTaskKind.Review -> normalized.copy(useWorktree = false)
            ProjectTaskKind.Verification -> normalized.copy(useWorktree = false)
        }
    }

    private fun ProjectWorkflowState.withMissingProfiles(): ProjectWorkflowState {
        if (ProjectTaskKind.entries.all { it in profiles }) return this
        val agent = _lastUsedAgent.value ?: AgentKind.Codex
        val base = _providerDefaults.value[agent]?.toProjectProfile(agent) ?: ProjectAgentProfile(agent = agent)
        return copy(
            profiles = ProjectTaskKind.entries.associateWith { kind ->
                profiles[kind] ?: base.normalizedFor(kind)
            },
        )
    }

    private suspend fun projectDirectory(projectId: String): String? =
        runCatching { actionConfig.load().projects.firstOrNull { it.id == projectId }?.contextDir }.getOrNull()

    private fun projectTask(taskId: String): ProjectTask? =
        _projects.value.values.asSequence().flatMap { it.tasks.asSequence() }.firstOrNull { it.id == taskId }

    private fun updateProject(projectId: String, transform: (ProjectWorkflowState) -> ProjectWorkflowState) {
        _projects.update { current ->
            val state = current[projectId] ?: defaultProjectState(projectId)
            current + (projectId to transform(state))
        }
    }

    private fun upsertProjectTask(task: ProjectTask) {
        updateProject(task.projectId) { state ->
            state.copy(tasks = if (state.tasks.any { it.id == task.id }) {
                state.tasks.map { if (it.id == task.id) task else it }
            } else {
                state.tasks + task
            })
        }
    }

    private fun updateProjectTask(taskId: String, transform: (ProjectTask) -> ProjectTask) {
        val task = projectTask(taskId) ?: return
        updateProject(task.projectId) { state ->
            state.copy(tasks = state.tasks.map { if (it.id == taskId) transform(it) else it })
        }
    }

    private fun workflowId(prefix: String): String =
        "$prefix-" + UUID.randomUUID().toString().replace("-", "").take(10)

    private fun ProjectAgentProfile.toAgentDraft(
        title: String,
        prompt: String,
        projectId: String,
        directory: String?,
        planMode: Boolean,
        skills: List<AgentSkill> = emptyList(),
        workflowTaskId: String,
        stage: ProjectWorkflowStage,
        attempt: Int,
        imagePaths: List<String> = emptyList(),
        existingWorktreePath: String? = null,
        existingBranchName: String? = null,
    ): AgentTaskDraft = AgentTaskDraft(
        title = title,
        prompt = prompt,
        agent = agent,
        projectId = projectId,
        directory = directory,
        useWorktree = useWorktree && existingWorktreePath == null,
        attachAndyMcp = attachAndyMcp,
        autonomy = autonomy,
        sandboxMode = if (planMode) AgentSandboxMode.ReadOnly else sandboxMode,
        planMode = planMode,
        model = model,
        reasoningEffort = reasoningEffort,
        fastMode = fastMode,
        imagePaths = imagePaths,
        skills = skills,
        maxBudgetUsd = maxBudgetUsd,
        existingWorktreePath = existingWorktreePath,
        existingBranchName = existingBranchName,
        workflowTaskId = workflowTaskId,
        workflowStage = stage,
        workflowAttempt = attempt,
    )

    private fun specPrompt(spec: ProjectTask, scratchpad: String?, revisionRequest: String?): String = buildString {
        append("Create a decision-complete implementation specification for this project task. Do not implement it.\n\n")
        append("Task:\n").append(spec.instructions.trim())
        spec.planVersions.lastOrNull()?.let { previous ->
            append("\n\nPrevious plan (version ").append(previous.version).append("):\n").append(previous.text.trim())
        }
        revisionRequest?.takeIf { it.isNotBlank() }?.let { request ->
            append("\n\nRevision request:\n").append(request.trim())
        }
        scratchpad?.let { append("\n\nProject scratchpad snapshot:\n").append(it.trim()) }
        if (spec.grillMeEnabled) {
            append("\n\n").append(grillMeHeadlessPromptAddendum())
        } else {
            append("\n\nReturn the complete implementation plan as the final response, including interfaces, edge cases, and verification steps.")
        }
    }

    private fun buildPrompt(
        build: ProjectTask,
        scratchpad: String?,
        previousFeedback: List<String>,
        previousReviewRun: AgentTask?,
    ): String = buildString {
        append("Implement the frozen plan below in the current project workspace. The linked verifier decides when this build is complete.\n\n")
        append("Implementation plan (source: ").append(build.planSnapshot?.sourceLabel ?: "unknown").append("):\n")
        append(build.planSnapshot?.text.orEmpty().trim())
        build.buildNotes.takeIf { it.isNotBlank() }?.let { append("\n\nBuild notes:\n").append(it.trim()) }
        append("\n\nVerification requirements:\n").append(build.verificationInstructions.trim())
        if (previousFeedback.isNotEmpty()) {
            append("\n\nThe previous quality gate requested changes. Fix every finding:\n")
            previousFeedback.forEach { append("- ").append(it).append('\n') }
        }
        previousReviewRun?.completedChanges?.let { changes ->
            append("\n\nWorkspace diff produced by the previous Review:\n")
            changes.diffs.values.forEach { diff ->
                append("--- ").append(diff.path).append('\n')
                if (diff.isBinary) {
                    append("(binary file changed)\n")
                } else {
                    diff.lines.forEach { line ->
                        append(
                            when (line.kind) {
                                app.andy.model.DiffLineKind.Context -> ' '
                                app.andy.model.DiffLineKind.Addition -> '+'
                                app.andy.model.DiffLineKind.Deletion -> '-'
                            },
                        ).append(line.text).append('\n')
                    }
                }
            }
        }
        scratchpad?.let { append("\n\nProject scratchpad snapshot:\n").append(it.trim()) }
        append("\n\nMake the edits and run useful checks, but do not claim the workflow is finished; verification is a separate stage.")
    }

    private fun recoveryBuildPrompt(build: ProjectTask, followUp: String, scratchpad: String?): String = buildString {
        append("Continue the completed workflow in its existing workspace. This is a user-directed fix after manual testing; make only the requested correction and run useful focused checks. Do not start or claim a review or verification pass.\n\n")
        append("Original implementation plan (source: ").append(build.planSnapshot?.sourceLabel ?: "unknown").append("):\n")
        append(build.planSnapshot?.text.orEmpty().trim())
        append("\n\nUser follow-up:\n").append(followUp)
        build.buildNotes.takeIf { it.isNotBlank() }?.let { append("\n\nBuild notes:\n").append(it.trim()) }
        scratchpad?.let { append("\n\nProject scratchpad snapshot:\n").append(it.trim()) }
        append("\n\nWhen the fix is ready, summarize the edits and checks. The user will decide when to run one cumulative review.")
    }

    private fun reviewPrompt(
        build: ProjectTask,
        buildRun: AgentTask,
        scratchpad: String?,
        manualRecovery: Boolean = false,
    ): String = buildString {
        append("Review the current workspace as a blocking code-quality gate. Inspect the actual files and run useful checks. ")
        append("You may edit the workspace only when your configured autonomy and sandbox allow it.\n\n")
        if (manualRecovery) {
            append("This is a manually triggered cumulative re-review after user testing. Review the entire current workflow workspace against the original plan, including all earlier implementation and every recovery follow-up; do not limit the assessment to the latest builder result.\n\n")
        }
        append("Implementation plan:\n").append(build.planSnapshot?.text.orEmpty().trim())
        buildRun.completedResultText?.takeIf { it.isNotBlank() }?.let { append("\n\nBuilder result:\n").append(it.trim()) }
        buildRun.completedChanges?.summary?.files?.takeIf { it.isNotEmpty() }?.let { files ->
            append("\n\nBuilder changed files:\n")
            files.forEach { append("- ").append(it.path).append(" (+").append(it.additions).append(" -").append(it.deletions).append(")\n") }
        }
        append("\n\nStandard review rubric:\n")
        append("- Correctness: behavior, edge cases, regressions, and failure handling.\n")
        append("- Plan alignment: the frozen implementation plan is fully and accurately implemented.\n")
        append("- Maintainability: clear design, appropriate tests, and no unnecessary complexity.\n")
        append("- Security: unsafe input, data exposure, permissions, and dependency risks.\n")
        append("- Scope: no unrelated or accidental changes.\n")
        build.reviewInstructions.takeIf { it.isNotBlank() }?.let { append("\nCustom review instructions:\n").append(it.trim()) }
        scratchpad?.let { append("\n\nProject scratchpad snapshot:\n").append(it.trim()) }
        append(
            "\n\nEnd the final response with exactly one terminal machine-readable block and nothing after it:\n" +
                "<andy_review>{\"status\":\"approved|changes_requested\",\"summary\":\"...\",\"findings\":[{\"severity\":\"blocking|warning|nit\",\"title\":\"...\",\"details\":\"...\",\"file\":\"optional\",\"line\":123}]}</andy_review>\n" +
                "Approved forbids blocking findings. Changes requested requires at least one blocking finding.",
        )
    }

    private fun verificationPrompt(
        build: ProjectTask,
        buildRun: AgentTask,
        reviewRun: AgentTask?,
        reviewVerdict: ProjectReviewVerdict?,
        scratchpad: String?,
    ): String = buildString {
        append("Verify the current workspace against the frozen plan and the explicit verification requirements. Inspect the actual files and run the relevant checks. Do not edit tracked source files.\n\n")
        append("Implementation plan:\n").append(build.planSnapshot?.text.orEmpty().trim())
        append("\n\nVerification requirements:\n").append(build.verificationInstructions.trim())
        buildRun.completedResultText?.takeIf { it.isNotBlank() }?.let { append("\n\nBuilder result:\n").append(it.trim()) }
        buildRun.completedChanges?.summary?.files?.takeIf { it.isNotEmpty() }?.let { files ->
            append("\n\nBuilder changed files:\n")
            files.forEach { append("- ").append(it.path).append(" (+").append(it.additions).append(" -").append(it.deletions).append(")\n") }
        }
        reviewVerdict?.let { verdict ->
            append("\n\nReview approval:\n").append(verdict.summary.trim())
            verdict.findings.filter { it.severity != ProjectReviewFindingSeverity.Blocking }.takeIf { it.isNotEmpty() }?.let { findings ->
                append("\nReview observations:\n")
                findings.forEach { finding -> append("- ").append(finding.severity.name.lowercase()).append(": ").append(finding.title).append(" — ").append(finding.details).append('\n') }
            }
        }
        reviewRun?.completedResultText?.takeIf { it.isNotBlank() }?.let { append("\n\nReviewer result:\n").append(it.trim()) }
        reviewRun?.completedChanges?.summary?.files?.takeIf { it.isNotEmpty() }?.let { files ->
            append("\n\nReviewer changed files:\n")
            files.forEach { append("- ").append(it.path).append(" (+").append(it.additions).append(" -").append(it.deletions).append(")\n") }
        }
        scratchpad?.let { append("\n\nProject scratchpad snapshot:\n").append(it.trim()) }
        append(
            "\n\nEnd the final response with exactly one machine-readable block and no second block:\n" +
                "<andy_verification>{\"status\":\"passed|failed\",\"summary\":\"...\",\"evidence\":[\"...\"],\"failures\":[\"...\"]}</andy_verification>\n" +
                "A passed result requires non-empty evidence and an empty failures list. A failed result requires at least one failure.",
        )
    }

    private suspend fun startBuildAttempt(buildTaskId: String) {
        val build = projectTask(buildTaskId)?.takeIf { it.kind == ProjectTaskKind.Build } ?: return
        val linkedReview = build.linkedReviewTaskId?.let(::projectTask)
        val linkedVerification = build.linkedVerificationTaskId?.let(::projectTask)
        if (
            build.paused ||
            build.state == ProjectTaskState.Completed ||
            isStageBusy(build) ||
            isStageBusy(linkedReview) ||
            isStageBusy(linkedVerification)
        ) {
            return
        }
        if ((linkedVerification?.verdicts?.count { it.status == ProjectVerificationStatus.Failed } ?: 0) >= build.maxVerificationAttempts) {
            setPairAttention(build, "verification reached the ${build.maxVerificationAttempts}-attempt limit")
            persist()
            return
        }
        if (build.reviewEnabled && reviewFailureCount(build, linkedReview) >= build.maxReviewFailures) {
            setPairAttention(build, "review requested changes ${build.maxReviewFailures} times")
            persist()
            return
        }
        if (workflowBudgetReached(build)) {
            setPairAttention(build, "reported workflow cost reached the configured budget")
            persist()
            return
        }
        val project = _projects.value[build.projectId] ?: return
        val directory = projectDirectory(build.projectId)
        if (directory == null) {
            setPairAttention(build, "project directory is unavailable")
            persist()
            return
        }
        val attempt = build.attempts.count { it.stage == ProjectWorkflowStage.Build } + 1
        val scratchpad = project.scratchpad.takeIf { build.includeScratchpad && it.isNotBlank() }
        val verification = linkedVerification
        val lastReviewFailure = linkedReview?.reviewVerdicts
            ?.lastOrNull { it.status == ProjectReviewStatus.ChangesRequested && it.reviewGeneration == build.reviewGeneration }
        val lastVerificationFailure = verification?.verdicts?.lastOrNull { it.status == ProjectVerificationStatus.Failed }
        val feedback = if ((lastReviewFailure?.createdAtMillis ?: Long.MIN_VALUE) > (lastVerificationFailure?.createdAtMillis ?: Long.MIN_VALUE)) {
            lastReviewFailure?.findings.orEmpty().filter { it.severity == ProjectReviewFindingSeverity.Blocking }.map { finding ->
                buildString {
                    append(finding.title).append(": ").append(finding.details)
                    finding.file?.let { file ->
                        append(" (").append(file)
                        finding.line?.let { line -> append(':').append(line) }
                        append(')')
                    }
                }
            }
        } else {
            lastVerificationFailure?.failures.orEmpty()
        }
        val previousReviewRun = lastReviewFailure?.runId?.let(::currentTask)
            ?.takeIf { (lastReviewFailure.createdAtMillis) > (lastVerificationFailure?.createdAtMillis ?: Long.MIN_VALUE) }
        val prompt = buildPrompt(build, scratchpad, feedback, previousReviewRun)
        updateProjectTask(build.id) { it.copy(state = ProjectTaskState.Queued, lastError = null, updatedAtMillis = System.currentTimeMillis()) }
        linkedReview?.let { item ->
            updateProjectTask(item.id) {
                it.copy(state = if (build.reviewEnabled) ProjectTaskState.Waiting else ProjectTaskState.Disabled, lastError = null)
            }
        }
        verification?.let { item -> updateProjectTask(item.id) { it.copy(state = ProjectTaskState.Waiting) } }
        persist()
        val run = createAndStart(
            build.profile.toAgentDraft(
                title = "Build: ${build.title}",
                prompt = prompt,
                projectId = build.projectId,
                directory = directory,
                planMode = false,
                workflowTaskId = build.id,
                stage = ProjectWorkflowStage.Build,
                attempt = attempt,
                existingWorktreePath = build.worktreePath,
                existingBranchName = build.branchName,
            ),
        )
        appendAttempt(build.id, run, ProjectWorkflowStage.Build, attempt, prompt, build.profile, scratchpad)
        updateProjectTask(build.id) {
            it.copy(
                workspacePath = run.cwd ?: it.workspacePath,
                worktreePath = run.worktreePath ?: it.worktreePath,
                branchName = run.branchName ?: it.branchName,
                worktreeOwnerRunId = if (run.ownsWorktree) run.id else it.worktreeOwnerRunId,
            )
        }
        persist()
        reconcileWorkflowRun(run.id)
    }

    private suspend fun startReviewAttempt(buildTaskId: String, manualRecovery: Boolean = false) {
        val build = projectTask(buildTaskId)?.takeIf { it.kind == ProjectTaskKind.Build } ?: return
        val review = build.linkedReviewTaskId?.let(::projectTask) ?: return
        val verification = build.linkedVerificationTaskId?.let(::projectTask)
        if (
            !build.reviewEnabled ||
            build.paused ||
            (!manualRecovery && build.state == ProjectTaskState.Completed) ||
            isStageBusy(build) ||
            isStageBusy(review) ||
            isStageBusy(verification)
        ) {
            return
        }
        if (reviewFailureCount(build, review) >= build.maxReviewFailures) {
            setPairAttention(build, "review requested changes ${build.maxReviewFailures} times")
            persist()
            return
        }
        if (workflowBudgetReached(build)) {
            setPairAttention(build, "reported workflow cost reached the configured budget")
            persist()
            return
        }
        val buildRun = latestCompletedBuildRun(build)
        if (buildRun == null) {
            setPairAttention(build, "the latest build did not complete successfully")
            persist()
            return
        }
        if (build.worktreePath != null && !File(build.worktreePath).isDirectory) {
            setPairAttention(build, "the retained workflow worktree is missing")
            persist()
            return
        }
        val project = _projects.value[build.projectId] ?: return
        val scratchpad = project.scratchpad.takeIf { review.includeScratchpad && it.isNotBlank() }
        val prompt = reviewPrompt(build, buildRun, scratchpad, manualRecovery)
        val attempt = review.attempts.count { it.stage == ProjectWorkflowStage.Review } + 1
        val directory = projectDirectory(build.projectId)
        updateProjectTask(build.id) { it.copy(state = ProjectTaskState.Waiting, reviewReopenedCompleted = false, lastError = null, updatedAtMillis = System.currentTimeMillis()) }
        updateProjectTask(review.id) { it.copy(state = ProjectTaskState.Queued, lastError = null) }
        verification?.let { item -> updateProjectTask(item.id) { it.copy(state = ProjectTaskState.Waiting) } }
        persist()
        val run = createAndStart(
            review.profile.copy(useWorktree = false).toAgentDraft(
                title = "Review: ${build.title}",
                prompt = prompt,
                projectId = build.projectId,
                directory = directory ?: build.workspacePath,
                planMode = false,
                workflowTaskId = review.id,
                stage = ProjectWorkflowStage.Review,
                attempt = attempt,
                existingWorktreePath = build.worktreePath,
                existingBranchName = build.branchName,
            ),
        )
        appendAttempt(
            review.id,
            run,
            ProjectWorkflowStage.Review,
            attempt,
            prompt,
            review.profile,
            scratchpad,
            reviewedBuildRunId = buildRun.id,
            reviewGeneration = build.reviewGeneration,
            isRecoveryFollowUp = manualRecovery,
        )
        persist()
        reconcileWorkflowRun(run.id)
    }

    private suspend fun completeBuildWithoutVerification(buildTaskId: String) {
        updateProjectTask(buildTaskId) {
            it.copy(
                state = ProjectTaskState.Completed,
                paused = false,
                lastError = null,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        persist()
    }

    private suspend fun startVerificationAttempt(buildTaskId: String) {
        val build = projectTask(buildTaskId)?.takeIf { it.kind == ProjectTaskKind.Build } ?: return
        val verification = build.linkedVerificationTaskId?.let(::projectTask) ?: return
        if (
            build.paused ||
            build.state == ProjectTaskState.Completed ||
            isStageBusy(build) ||
            isStageBusy(verification) ||
            isStageBusy(build.linkedReviewTaskId?.let(::projectTask))
        ) {
            return
        }
        val failedVerificationCount = verification.verdicts.count { it.status == ProjectVerificationStatus.Failed }
        if (failedVerificationCount >= build.maxVerificationAttempts) {
            setPairAttention(build, "verification failed ${build.maxVerificationAttempts} times")
            persist()
            return
        }
        if (workflowBudgetReached(build)) {
            setPairAttention(build, "reported workflow cost reached the configured budget")
            persist()
            return
        }
        val buildRun = latestCompletedBuildRun(build)
        if (buildRun == null) {
            setPairAttention(build, "the latest build did not complete successfully")
            persist()
            return
        }
        val project = _projects.value[build.projectId] ?: return
        val scratchpad = project.scratchpad.takeIf { verification.includeScratchpad && it.isNotBlank() }
        val review = build.linkedReviewTaskId?.let(::projectTask)
        val reviewVerdict = if (build.reviewEnabled) currentReviewApproval(review, buildRun.id, build.reviewGeneration) else null
        if (build.reviewEnabled && reviewVerdict == null) {
            setPairAttention(build, "the latest build has not received a fresh review approval")
            persist()
            return
        }
        val reviewRun = reviewVerdict?.runId?.let(::currentTask)
        val prompt = verificationPrompt(build, buildRun, reviewRun, reviewVerdict, scratchpad)
        val attempt = verification.attempts.count { it.stage == ProjectWorkflowStage.Verification } + 1
        val directory = projectDirectory(build.projectId)
        updateProjectTask(build.id) { it.copy(state = ProjectTaskState.Waiting, updatedAtMillis = System.currentTimeMillis()) }
        updateProjectTask(verification.id) { it.copy(state = ProjectTaskState.Queued, lastError = null) }
        persist()
        val run = createAndStart(
            verification.profile.copy(useWorktree = false).toAgentDraft(
                title = "Verify: ${build.title}",
                prompt = prompt,
                projectId = build.projectId,
                directory = directory ?: build.workspacePath,
                planMode = false,
                workflowTaskId = verification.id,
                stage = ProjectWorkflowStage.Verification,
                attempt = attempt,
                existingWorktreePath = build.worktreePath,
                existingBranchName = build.branchName,
            ),
        )
        appendAttempt(
            verification.id,
            run,
            ProjectWorkflowStage.Verification,
            attempt,
            prompt,
            verification.profile,
            scratchpad,
            reviewedBuildRunId = buildRun.id,
            reviewGeneration = build.reviewGeneration,
        )
        persist()
        reconcileWorkflowRun(run.id)
    }

    private fun appendAttempt(
        projectTaskId: String,
        run: AgentTask,
        stage: ProjectWorkflowStage,
        attempt: Int,
        prompt: String,
        profile: ProjectAgentProfile,
        scratchpad: String?,
        reviewedBuildRunId: String? = null,
        reviewGeneration: Int = 0,
        isRecoveryFollowUp: Boolean = false,
    ) {
        updateProjectTask(projectTaskId) { task ->
            if (task.attempts.any { it.runId == run.id }) task else task.copy(
                attempts = task.attempts + ProjectTaskAttempt(
                    run.id,
                    stage,
                    attempt,
                    prompt,
                    profile,
                    scratchpad,
                    run.createdAtMillis,
                    reviewedBuildRunId,
                    reviewGeneration,
                    isRecoveryFollowUp,
                ),
                state = when (run.status) {
                    AgentTaskStatus.Queued -> ProjectTaskState.Queued
                    AgentTaskStatus.Running -> ProjectTaskState.Running
                    AgentTaskStatus.WaitingForInput -> ProjectTaskState.Waiting
                    AgentTaskStatus.Completed -> ProjectTaskState.Waiting
                    else -> ProjectTaskState.NeedsAttention
                },
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    private fun workflowBudgetReached(build: ProjectTask): Boolean {
        val budget = build.maxBudgetUsd ?: return false
        val review = build.linkedReviewTaskId?.let(::projectTask)
        val verification = build.linkedVerificationTaskId?.let(::projectTask)
        val runIds = (build.attempts + review?.attempts.orEmpty() + verification?.attempts.orEmpty()).map { it.runId }.toSet()
        val cost = _tasks.value.filter { it.id in runIds }.sumOf { it.totalCostUsd ?: 0.0 }
        return cost >= budget
    }

    /**
     * True while a stage has an in-flight or unanswered agent run.
     * [ProjectTaskState.Waiting] alone is not busy: the build/review/verify handoff
     * parks siblings in Waiting before launching the next stage.
     */
    private fun isStageBusy(task: ProjectTask?): Boolean {
        if (task == null) return false
        if (task.state == ProjectTaskState.Queued || task.state == ProjectTaskState.Running) return true
        val run = task.attempts.maxByOrNull { it.createdAtMillis }?.runId?.let(::currentTask) ?: return false
        return run.status == AgentTaskStatus.WaitingForInput
    }

    private fun setPairAttention(build: ProjectTask, message: String) {
        updateProjectTask(build.id) { it.copy(state = ProjectTaskState.NeedsAttention, paused = true, lastError = message, updatedAtMillis = System.currentTimeMillis()) }
        build.linkedReviewTaskId?.let { id ->
            updateProjectTask(id) {
                it.copy(
                    state = if (build.reviewEnabled) ProjectTaskState.NeedsAttention else ProjectTaskState.Disabled,
                    lastError = message,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
        }
        build.linkedVerificationTaskId?.let { id ->
            updateProjectTask(id) { it.copy(state = ProjectTaskState.NeedsAttention, lastError = message, updatedAtMillis = System.currentTimeMillis()) }
        }
    }

    private suspend fun reconcileWorkflowRun(runId: String) {
        val run = currentTask(runId) ?: return
        val projectTaskId = run.workflowTaskId ?: return
        val typedTask = projectTask(projectTaskId) ?: return
        if (run.status == AgentTaskStatus.WaitingForInput) {
            updateProjectTask(projectTaskId) { it.copy(state = ProjectTaskState.Waiting, lastError = null) }
            persist()
            return
        }
        if (run.isActive) {
            updateProjectTask(projectTaskId) {
                it.copy(state = if (run.status == AgentTaskStatus.Queued) ProjectTaskState.Queued else ProjectTaskState.Running)
            }
            persist()
            return
        }
        if (run.status != AgentTaskStatus.Completed) {
            val message = run.errorMessage ?: when (run.status) {
                AgentTaskStatus.Unknown -> "the app restarted while this workflow stage was active"
                AgentTaskStatus.Stopped -> "workflow stage was stopped"
                else -> "workflow stage failed"
            }
            if (typedTask.kind == ProjectTaskKind.Spec) {
                // A stopped/failed refine shouldn't bury a previously completed plan.
                if (run.status == AgentTaskStatus.Stopped && typedTask.planVersions.isNotEmpty()) {
                    updateProjectTask(projectTaskId) {
                        it.copy(state = ProjectTaskState.Completed, lastError = null, updatedAtMillis = System.currentTimeMillis())
                    }
                } else {
                    updateProjectTask(projectTaskId) { it.copy(state = ProjectTaskState.NeedsAttention, lastError = message) }
                }
            } else {
                val build = if (typedTask.kind == ProjectTaskKind.Build) typedTask else typedTask.linkedBuildTaskId?.let(::projectTask)
                build?.let { setPairAttention(it, message) }
            }
            persist()
            return
        }
        when (run.workflowStage) {
            ProjectWorkflowStage.Spec -> {
                val plan = run.completedPlanText?.takeIf { it.isNotBlank() }
                if (plan == null) {
                    updateProjectTask(projectTaskId) { it.copy(state = ProjectTaskState.NeedsAttention, lastError = "the planning run returned no final plan") }
                } else {
                    updateProjectTask(projectTaskId) { task ->
                        if (task.planVersions.any { it.runId == run.id }) task else task.copy(
                            planVersions = task.planVersions + ProjectPlanVersion(
                                version = (task.planVersions.maxOfOrNull { it.version } ?: 0) + 1,
                                text = plan,
                                runId = run.id,
                                createdAtMillis = run.finishedAtMillis ?: System.currentTimeMillis(),
                            ),
                            state = ProjectTaskState.Completed,
                            lastError = null,
                            updatedAtMillis = System.currentTimeMillis(),
                        )
                    }
                }
                persist()
            }
            ProjectWorkflowStage.Build -> {
                val build = projectTask(projectTaskId) ?: return
                val recoveryAttempt = build.attempts.firstOrNull { it.runId == run.id }?.isRecoveryFollowUp == true
                if (build.paused) {
                    updateProjectTask(build.id) { it.copy(state = ProjectTaskState.Paused) }
                    build.linkedReviewTaskId?.let { reviewId ->
                        updateProjectTask(reviewId) {
                            it.copy(state = if (build.reviewEnabled) ProjectTaskState.Paused else ProjectTaskState.Disabled)
                        }
                    }
                    persist()
                } else if (recoveryAttempt) {
                    updateProjectTask(build.id) {
                        it.copy(
                            state = ProjectTaskState.Paused,
                            recoveryMode = true,
                            reviewStale = true,
                            lastError = null,
                            updatedAtMillis = System.currentTimeMillis(),
                        )
                    }
                    build.linkedReviewTaskId?.let { reviewId ->
                        updateProjectTask(reviewId) { it.copy(state = ProjectTaskState.Paused, lastError = null) }
                    }
                    persist()
                } else {
                    updateProjectTask(build.id) { it.copy(state = ProjectTaskState.Waiting, lastError = null) }
                    build.linkedReviewTaskId?.let { reviewId ->
                        updateProjectTask(reviewId) {
                            it.copy(state = if (build.reviewEnabled) ProjectTaskState.Waiting else ProjectTaskState.Disabled, lastError = null)
                        }
                    }
                    persist()
                    when {
                        build.reviewEnabled -> startReviewAttempt(build.id)
                        build.linkedVerificationTaskId != null -> startVerificationAttempt(build.id)
                        else -> completeBuildWithoutVerification(build.id)
                    }
                }
            }
            ProjectWorkflowStage.Review -> reconcileReview(run, typedTask)
            ProjectWorkflowStage.Verification -> reconcileVerification(run, typedTask)
            null -> Unit
        }
    }

    private suspend fun reconcileReview(run: AgentTask, review: ProjectTask) {
        val build = review.linkedBuildTaskId?.let(::projectTask) ?: return
        val attempt = review.attempts.firstOrNull { it.runId == run.id } ?: return
        val recoveryReview = attempt.isRecoveryFollowUp
        val reviewedBuildRunId = attempt.reviewedBuildRunId
        if (reviewedBuildRunId.isNullOrBlank()) {
            setPairAttention(build, "review attempt is missing its build provenance")
            persist()
            return
        }
        val parsed = parseReviewVerdict(
            text = run.completedResultText,
            runId = run.id,
            reviewedBuildRunId = reviewedBuildRunId,
            reviewGeneration = attempt.reviewGeneration,
            atMillis = run.finishedAtMillis ?: System.currentTimeMillis(),
        )
        if (parsed == null) {
            setPairAttention(build, "review did not return one valid terminal Andy review block")
            persist()
            return
        }
        updateProjectTask(review.id) { task ->
            if (task.reviewVerdicts.any { it.runId == run.id }) task else task.copy(
                reviewVerdicts = task.reviewVerdicts + parsed,
                state = if (parsed.status == ProjectReviewStatus.Approved) ProjectTaskState.Completed else ProjectTaskState.Failed,
                lastError = null,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        if (recoveryReview) {
            updateProjectTask(build.id) {
                it.copy(
                    state = when {
                        build.paused -> ProjectTaskState.Paused
                        parsed.status == ProjectReviewStatus.Approved -> ProjectTaskState.Completed
                        else -> ProjectTaskState.Paused
                    },
                    paused = false,
                    recoveryMode = build.paused || parsed.status != ProjectReviewStatus.Approved,
                    reviewStale = build.paused || parsed.status != ProjectReviewStatus.Approved,
                    lastError = null,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
            persist()
            return
        }
        if (parsed.status == ProjectReviewStatus.Approved) {
            if (build.paused) {
                updateProjectTask(build.id) { it.copy(state = ProjectTaskState.Paused, lastError = null, updatedAtMillis = System.currentTimeMillis()) }
                persist()
            } else if (workflowBudgetReached(build)) {
                setPairAttention(build, "reported workflow cost reached the configured budget")
                persist()
            } else {
                persist()
                if (build.linkedVerificationTaskId != null) {
                    startVerificationAttempt(build.id)
                } else {
                    completeBuildWithoutVerification(build.id)
                }
            }
            return
        }
        val refreshedReview = projectTask(review.id) ?: review
        if (reviewFailureCount(build, refreshedReview) >= build.maxReviewFailures) {
            setPairAttention(build, "review requested changes ${build.maxReviewFailures} times")
            persist()
        } else if (build.paused) {
            updateProjectTask(build.id) { it.copy(state = ProjectTaskState.Paused, lastError = null, updatedAtMillis = System.currentTimeMillis()) }
            persist()
        } else if (workflowBudgetReached(build)) {
            setPairAttention(build, "reported workflow cost reached the configured budget")
            persist()
        } else {
            persist()
            startBuildAttempt(build.id)
        }
    }

    private suspend fun reconcileVerification(run: AgentTask, verification: ProjectTask) {
        val build = verification.linkedBuildTaskId?.let(::projectTask) ?: return
        val attempt = verification.attempts.firstOrNull { it.runId == run.id }
        val parsed = parseVerificationVerdict(
            text = run.completedResultText,
            runId = run.id,
            atMillis = run.finishedAtMillis ?: System.currentTimeMillis(),
            reviewedBuildRunId = attempt?.reviewedBuildRunId,
            reviewGeneration = attempt?.reviewGeneration ?: 0,
        )
        if (parsed == null) {
            setPairAttention(build, "verification did not return one valid Andy verdict block")
            persist()
            return
        }
        updateProjectTask(verification.id) { task ->
            if (task.verdicts.any { it.runId == run.id }) task else task.copy(
                verdicts = task.verdicts + parsed,
                state = if (parsed.status == ProjectVerificationStatus.Passed) ProjectTaskState.Completed else ProjectTaskState.Failed,
                lastError = null,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        if (parsed.status == ProjectVerificationStatus.Passed) {
            updateProjectTask(build.id) { it.copy(state = ProjectTaskState.Completed, paused = false, lastError = null, updatedAtMillis = System.currentTimeMillis()) }
            persist()
            return
        }
        val refreshedVerification = projectTask(verification.id) ?: verification
        val failedVerdicts = refreshedVerification.verdicts.count { it.status == ProjectVerificationStatus.Failed }
        if (failedVerdicts >= build.maxVerificationAttempts) {
            setPairAttention(build, "verification failed ${build.maxVerificationAttempts} times")
            persist()
        } else if (build.paused) {
            updateProjectTask(build.id) { it.copy(state = ProjectTaskState.Paused, lastError = null, updatedAtMillis = System.currentTimeMillis()) }
            persist()
        } else if (workflowBudgetReached(build)) {
            setPairAttention(build, "reported workflow cost reached the configured budget")
            persist()
        } else {
            startBuildAttempt(build.id)
        }
    }

    private fun parseReviewVerdict(
        text: String?,
        runId: String,
        reviewedBuildRunId: String,
        reviewGeneration: Int,
        atMillis: Long,
    ): ProjectReviewVerdict? {
        val output = text.orEmpty()
        val matches = REVIEW_BLOCK.findAll(output).toList()
        if (matches.size != 1 || matches.single().range.last != output.trimEnd().lastIndex) return null
        return runCatching {
            val value = Json.parseToJsonElement(matches.single().groupValues[1]).jsonObject
            val status = when (value["status"]?.jsonPrimitive?.content?.lowercase()) {
                "approved" -> ProjectReviewStatus.Approved
                "changes_requested" -> ProjectReviewStatus.ChangesRequested
                else -> return null
            }
            val summary = value["summary"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (summary.isBlank()) return null
            val findings = value["findings"]?.jsonArray?.map { element ->
                val finding = element.jsonObject
                val severity = when (finding["severity"]?.jsonPrimitive?.content?.lowercase()) {
                    "blocking" -> ProjectReviewFindingSeverity.Blocking
                    "warning" -> ProjectReviewFindingSeverity.Warning
                    "nit" -> ProjectReviewFindingSeverity.Nit
                    else -> return null
                }
                val title = finding["title"]?.jsonPrimitive?.content?.trim().orEmpty()
                val details = finding["details"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (title.isBlank() || details.isBlank()) return null
                val file = finding["file"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
                val line = finding["line"]?.jsonPrimitive?.content?.toIntOrNull()
                if (line != null && line <= 0) return null
                ProjectReviewFinding(severity, title, details, file, line)
            }.orEmpty()
            val blocking = findings.count { it.severity == ProjectReviewFindingSeverity.Blocking }
            if (status == ProjectReviewStatus.Approved && blocking > 0) return null
            if (status == ProjectReviewStatus.ChangesRequested && blocking == 0) return null
            ProjectReviewVerdict(status, summary, findings, runId, reviewedBuildRunId, reviewGeneration, atMillis)
        }.getOrNull()
    }

    private fun parseVerificationVerdict(
        text: String?,
        runId: String,
        atMillis: Long,
        reviewedBuildRunId: String?,
        reviewGeneration: Int,
    ): ProjectVerificationVerdict? {
        val output = text.orEmpty()
        val matches = VERIFICATION_BLOCK.findAll(output).toList()
        if (matches.size != 1) return null
        if (matches.single().range.last != output.trimEnd().lastIndex) return null
        return runCatching {
            val value = Json.parseToJsonElement(matches.single().groupValues[1]).jsonObject
            val statusText = value["status"]?.jsonPrimitive?.content ?: return null
            val status = when (statusText.lowercase()) {
                "passed" -> ProjectVerificationStatus.Passed
                "failed" -> ProjectVerificationStatus.Failed
                else -> return null
            }
            val summary = value["summary"]?.jsonPrimitive?.content?.trim().orEmpty()
            val evidence = value["evidence"]?.jsonArray?.map { it.jsonPrimitive.content.trim() }?.filter { it.isNotBlank() }.orEmpty()
            val failures = value["failures"]?.jsonArray?.map { it.jsonPrimitive.content.trim() }?.filter { it.isNotBlank() }.orEmpty()
            if (summary.isBlank()) return null
            if (status == ProjectVerificationStatus.Passed && (evidence.isEmpty() || failures.isNotEmpty())) return null
            if (status == ProjectVerificationStatus.Failed && failures.isEmpty()) return null
            ProjectVerificationVerdict(status, summary, evidence, failures, runId, atMillis, reviewedBuildRunId, reviewGeneration)
        }.getOrNull()
    }

    private fun latestCompletedBuildRun(build: ProjectTask): AgentTask? {
        val latest = build.attempts.filter { it.stage == ProjectWorkflowStage.Build }.maxByOrNull { it.createdAtMillis } ?: return null
        return currentTask(latest.runId)?.takeIf { it.status == AgentTaskStatus.Completed }
    }

    private fun currentReviewApproval(review: ProjectTask?, buildRunId: String, generation: Int): ProjectReviewVerdict? =
        review?.reviewVerdicts?.lastOrNull {
            it.status == ProjectReviewStatus.Approved &&
                it.reviewedBuildRunId == buildRunId &&
                it.reviewGeneration == generation
        }

    private fun reviewFailureCount(build: ProjectTask, review: ProjectTask?): Int =
        review?.reviewVerdicts?.count {
            it.status == ProjectReviewStatus.ChangesRequested && it.reviewGeneration == build.reviewGeneration
        } ?: 0

    private fun recoverInterruptedWorkflows(
        workflows: Map<String, ProjectWorkflowState>,
        tasks: List<AgentTask>,
    ): Map<String, ProjectWorkflowState> = workflows.mapValues { (_, state) ->
        val liveRunIds = tasks.mapTo(mutableSetOf()) { it.id }
        val interruptedIds = state.tasks.mapNotNull { workflowTask ->
            val lastRun = workflowTask.attempts.maxByOrNull { it.createdAtMillis }?.runId?.let { id -> tasks.firstOrNull { it.id == id } }
            workflowTask.id.takeIf {
                workflowTask.state in setOf(ProjectTaskState.Queued, ProjectTaskState.Running, ProjectTaskState.Waiting) && lastRun?.status == AgentTaskStatus.Unknown
            }
        }.toSet()
        val affectedBuildIds = state.tasks.mapNotNull { workflowTask ->
            when {
                workflowTask.id !in interruptedIds -> null
                workflowTask.kind == ProjectTaskKind.Build -> workflowTask.id
                workflowTask.kind == ProjectTaskKind.Review || workflowTask.kind == ProjectTaskKind.Verification -> workflowTask.linkedBuildTaskId
                else -> null
            }
        }.toSet()
        val affectedReviewIds = state.tasks.filter { it.kind == ProjectTaskKind.Build && it.id in affectedBuildIds }
            .mapNotNull { it.linkedReviewTaskId }.toSet()
        val affectedVerificationIds = state.tasks.filter { it.kind == ProjectTaskKind.Build && it.id in affectedBuildIds }
            .mapNotNull { it.linkedVerificationTaskId }.toSet()
        state.copy(tasks = state.tasks.map { workflowTask ->
            val prunedAttempts = workflowTask.attempts.filter { it.runId in liveRunIds }
            val attemptsChanged = prunedAttempts.size != workflowTask.attempts.size
            val recovered = if (
                workflowTask.id in interruptedIds ||
                workflowTask.id in affectedBuildIds ||
                workflowTask.id in affectedReviewIds ||
                workflowTask.id in affectedVerificationIds
            ) {
                workflowTask.copy(
                    state = if (workflowTask.kind == ProjectTaskKind.Review && !workflowTask.reviewEnabled) {
                        ProjectTaskState.Disabled
                    } else {
                        ProjectTaskState.NeedsAttention
                    },
                    paused = workflowTask.kind != ProjectTaskKind.Spec,
                    lastError = "the app restarted while this workflow stage was active",
                )
            } else {
                workflowTask
            }
            when {
                recovered.kind == ProjectTaskKind.Spec && attemptsChanged -> recovered.copy(
                    attempts = prunedAttempts,
                    state = when {
                        recovered.planVersions.isNotEmpty() -> ProjectTaskState.Completed
                        prunedAttempts.isEmpty() -> ProjectTaskState.Draft
                        else -> recovered.state
                    },
                    lastError = when {
                        recovered.planVersions.isNotEmpty() || prunedAttempts.isEmpty() -> null
                        else -> recovered.lastError
                    },
                )
                attemptsChanged -> recovered.copy(attempts = prunedAttempts)
                else -> recovered
            }
        })
    }

    private suspend fun migrateLegacyProjectNotes() {
        val config = runCatching { actionConfig.load() }.getOrNull() ?: return
        var changedWorkflows = false
        val projectIdsToClear = mutableSetOf<String>()
        config.projects.forEach { project ->
            if (project.notes.isEmpty()) return@forEach
            val existing = _projects.value[project.id] ?: defaultProjectState(project.id)
            projectIdsToClear += project.id
            if (existing.legacyNotesMigrated) return@forEach
            val block = buildString {
                append("## Migrated todos\n")
                project.notes.forEach { note ->
                    append("- [").append(if (note.completed) 'x' else ' ').append("] ").append(note.title.trim()).append('\n')
                    if (note.body.isNotEmpty()) {
                        note.body.lines().forEach { append("  ").append(it).append('\n') }
                    }
                }
            }.trimEnd()
            val scratchpad = listOf(existing.scratchpad.trim(), block).filter { it.isNotBlank() }.joinToString("\n\n")
            _projects.update { it + (project.id to existing.copy(scratchpad = scratchpad, legacyNotesMigrated = true)) }
            changedWorkflows = true
        }
        if (changedWorkflows) persist()
        if (projectIdsToClear.isEmpty()) return
        actionConfig.save(
            config.copy(projects = config.projects.map { project ->
                if (project.id in projectIdsToClear) project.copy(notes = emptyList()) else project
            }),
        )
    }

    /** Repairs Cursor plan-mode runs saved before structured `createPlan` output was retained. */
    private suspend fun backfillCursorPlansFromTranscripts() {
        val recoveredPlans = withContext(Dispatchers.IO) {
            _tasks.value.asSequence()
                .filter { task ->
                    task.agent == AgentKind.Cursor &&
                        task.planMode &&
                        task.status == AgentTaskStatus.Completed
                }
                .mapNotNull { task ->
                    val plan = runCatching {
                        store.transcriptFile(task.id).takeIf { it.isFile }?.useLines { lines ->
                            lines.mapNotNull(::successfulCursorPlanText).lastOrNull()
                        }
                    }.getOrNull()
                    plan?.let { task.id to it }
                }
                .toMap()
        }
        if (recoveredPlans.isEmpty()) return

        val repairedTasks = _tasks.value.map { task ->
            recoveredPlans[task.id]?.let { plan ->
                if (task.completedPlanText == plan) task else task.copy(completedPlanText = plan)
            } ?: task
        }
        val repairedWorkflows = _projects.value.mapValues { (_, workflow) ->
            workflow.copy(tasks = workflow.tasks.map { task ->
                task.copy(planVersions = task.planVersions.map { version ->
                    recoveredPlans[version.runId]?.let { plan ->
                        if (version.text == plan) version else version.copy(text = plan)
                    } ?: version
                })
            })
        }
        if (repairedTasks == _tasks.value && repairedWorkflows == _projects.value) return

        _tasks.value = repairedTasks
        _projects.value = repairedWorkflows
        persist()
    }

    private fun currentTask(taskId: String): AgentTask? = _tasks.value.firstOrNull { it.id == taskId }

    private fun upsertTask(task: AgentTask) {
        _tasks.update { list ->
            if (list.any { it.id == task.id }) list.map { if (it.id == task.id) task else it } else list + task
        }
    }

    private fun updateTask(taskId: String, transform: (AgentTask) -> AgentTask) {
        _tasks.update { list -> list.map { if (it.id == taskId) transform(it) else it } }
    }

    private fun appendEvents(taskId: String, events: List<AgentEvent>) {
        if (events.isEmpty()) return
        val flow = eventFlows.computeIfAbsent(taskId) { MutableStateFlow(emptyList()) }
        flow.update { existing ->
            coalesceStreamDeltas(existing, events).takeLast(MAX_EVENTS_IN_MEMORY)
        }
    }

    private fun updateQuota(agent: AgentKind, windows: List<app.andy.model.AgentQuotaWindow>, updatedAtMillis: Long) {
        if (windows.isEmpty()) return
        _providerQuotas.update { current ->
            current + (agent to AgentProviderQuota(windows, updatedAtMillis, source = AgentQuotaSource.ProviderEvent))
        }
    }

    /** Recover the latest CLI-emitted limit for each provider without touching provider credentials or network APIs. */
    private fun restoreProviderQuotas(tasks: List<AgentTask>) {
        val restored = linkedMapOf<AgentKind, AgentProviderQuota>()
        tasks.sortedByDescending { it.createdAtMillis }.forEach { task ->
            if (task.agent in restored) return@forEach
            val adapter = adapters[task.agent] ?: return@forEach
            val file = store.transcriptFile(task.id)
            if (!file.isFile) return@forEach
            var latest: Pair<List<app.andy.model.AgentQuotaWindow>, Long>? = null
            runCatching {
                file.useLines { lines ->
                    lines.forEach { line ->
                        adapter.parseLine(line, task.createdAtMillis)
                            .filterIsInstance<AgentEvent.ToolResult>()
                            .lastOrNull { it.quotaWindows.isNotEmpty() }
                            ?.let { result -> latest = result.quotaWindows to result.atMillis }
                    }
                }
            }
            latest?.let { (windows, atMillis) ->
                restored[task.agent] = AgentProviderQuota(windows, atMillis, source = AgentQuotaSource.ProviderEvent)
            }
        }
        if (restored.isNotEmpty()) _providerQuotas.value = restored
    }

    private fun List<AgentEvent>.withEstimatedCosts(task: AgentTask): List<AgentEvent> = map { event ->
        if (event !is AgentEvent.TaskResult || event.costUsd != null) return@map event
        val estimate = task.estimatedTokenCostUsd(event.inputTokens, event.outputTokens) ?: return@map event
        event.copy(costUsd = estimate, costIsEstimated = true)
    }

    private fun coalesceStreamDeltas(
        existing: List<AgentEvent>,
        incoming: List<AgentEvent>,
    ): List<AgentEvent> = coalesceAgentStreamDeltas(existing, incoming)

    private fun writeAndyTranscriptLine(
        taskId: String,
        userText: String,
        skills: List<AgentSkill>,
        atMillis: Long,
        imagePaths: List<String> = emptyList(),
    ) {
        runCatching {
            val file = store.transcriptFile(taskId)
            file.parentFile?.mkdirs()
            val line = buildJsonObject {
                put("andyUserMessage", userText)
                put("andySkillNames", skills.joinToString(SKILL_SEPARATOR) { it.name })
                put("andySkillPaths", skills.joinToString(SKILL_SEPARATOR) { it.path })
                put("andyImagePaths", imagePaths.joinToString(SKILL_SEPARATOR))
                put("atMillis", atMillis)
            }.toString()
            file.appendText(line + "\n")
        }
    }

    private fun parseAndyTranscriptLine(line: String): AgentEvent.UserMessage? {
        if (!line.startsWith("{\"andyUserMessage\"")) return null
        val objectValue = parseJsonObject(line) ?: return null
        val text = objectValue.stringOrNull("andyUserMessage") ?: return null
        val names = objectValue.stringOrNull("andySkillNames")?.split(SKILL_SEPARATOR).orEmpty()
        val paths = objectValue.stringOrNull("andySkillPaths")?.split(SKILL_SEPARATOR).orEmpty()
        val skills = names.zip(paths).filter { (_, path) -> path.isNotBlank() }.map { (name, path) ->
            AgentSkill(name = name, description = "", path = path)
        }
        val imagePaths = objectValue.stringOrNull("andyImagePaths")
            ?.split(SKILL_SEPARATOR)
            .orEmpty()
            .filter { it.isNotBlank() }
        return AgentEvent.UserMessage(objectValue.longOrNull("atMillis") ?: 0L, text, skills, imagePaths)
    }

    override fun markRead(taskId: String) {
        val task = currentTask(taskId) ?: return
        if (!task.unread) return
        updateTask(taskId) { it.copy(unread = false) }
        scope.launch { persist() }
    }

    override fun markUnread(taskId: String) {
        val task = currentTask(taskId) ?: return
        if (task.unread) return
        updateTask(taskId) { it.copy(unread = true) }
        scope.launch { persist() }
    }

    override fun archive(taskId: String) {
        val task = currentTask(taskId) ?: return
        if (task.archived || task.isActive) return
        updateTask(taskId) { it.copy(archived = true, unread = false) }
        scope.launch { persist() }
    }

    override fun unarchive(taskId: String) {
        val task = currentTask(taskId) ?: return
        if (!task.archived) return
        updateTask(taskId) { it.copy(archived = false) }
        scope.launch { persist() }
    }

    private fun finishTask(taskId: String, status: AgentTaskStatus, exitCode: Int?, error: String?) {
        val completedChanges = currentTask(taskId)?.let { task ->
            task.cwd?.takeIf { task.hasChangeBaseline }?.let { cwd ->
                worktrees.changeSnapshot(cwd, task.changeBaselinePaths)
            }
        }
        updateTask(taskId) { task ->
            if (task.isActive) {
                task.copy(
                    status = status,
                    exitCode = exitCode,
                    errorMessage = error,
                    finishedAtMillis = System.currentTimeMillis(),
                    unread = true,
                    completedChanges = completedChanges ?: task.completedChanges,
                )
            } else {
                task
            }
        }
        val queuedFollowUp = currentTask(taskId)?.queuedFollowUps?.firstOrNull()
        handles.remove(taskId)
        if (status == AgentTaskStatus.Completed && queuedFollowUp != null) {
            updateTask(taskId) { current -> current.copy(queuedFollowUps = current.queuedFollowUps.drop(1)) }
            resume(taskId, queuedFollowUp.text, queuedFollowUp.imagePaths, queuedFollowUp.skills)
        } else {
            scope.launch {
                persist()
                reconcileWorkflowRun(taskId)
            }
        }
    }

    private suspend fun persist() {
        persistMutex.withLock {
            store.save(
                AgentStoreState(
                    tasks = _tasks.value,
                    binaryOverrides = binaryOverrides,
                    providerDefaults = _providerDefaults.value,
                    quotaAccess = _quotaAccess.value,
                    lastUsedAgent = _lastUsedAgent.value,
                    maxConcurrent = storedMaxConcurrent,
                    projectWorkflows = _projects.value,
                ),
            )
        }
    }

    @Volatile
    private var storedMaxConcurrent: Int = 8

    private fun nullInputFile(): File {
        val osName = System.getProperty("os.name")?.lowercase().orEmpty()
        return File(if (osName.contains("win")) "NUL" else "/dev/null")
    }

    private fun writeLaunchDiagnostics(
        taskId: String,
        binary: String,
        argv: List<String>,
        projectEnv: Map<String, String>,
    ) {
        runCatching {
            val file = File(store.transcriptFile(taskId).parentFile, "launch.log")
            file.parentFile?.mkdirs()
            file.writeText(
                buildString {
                    appendLine("ts=${System.currentTimeMillis()}")
                    appendLine("binary=$binary")
                    appendLine("argv=${argv.joinToString(" ")}")
                    appendLine("projectEnv=${projectEnv.keys.sorted()}")
                    appendLine("inheritedAnthropicBaseUrl=${System.getenv("ANTHROPIC_BASE_URL").orEmpty()}")
                    appendLine("inheritedNodeOptionsSet=${!System.getenv("NODE_OPTIONS").isNullOrBlank()}")
                },
            )
        }
    }

    private fun appendLaunchDiagnostics(taskId: String, text: String) {
        runCatching {
            val file = File(store.transcriptFile(taskId).parentFile, "launch.log")
            file.parentFile?.mkdirs()
            file.appendText("\n$text")
        }
    }

    /** Discovers each CLI's native roots plus explicitly supported compatibility roots. */
    private fun discoverSkills(agent: AgentKind, directory: String?): List<AgentSkill> {
        val home = System.getProperty("user.home") ?: return emptyList()
        val workspace = directory?.let(::File)?.takeIf(File::isDirectory)
        val codexHome = System.getenv("CODEX_HOME")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: File(home, ".codex")
        val roots = skillRootsFor(agent, workspace, File(home), codexHome)
        val discovered = linkedMapOf<String, AgentSkill>()
        roots.forEach { root ->
            if (!root.isDirectory) return@forEach
            root.walkTopDown()
                .maxDepth(8)
                .filter { file -> file.name == "SKILL.md" && file.isFile }
                .take(200)
                .forEach { file ->
                    val header = runCatching { file.useLines { lines -> lines.take(24).toList() } }.getOrDefault(emptyList())
                    val name = header.firstOrNull { it.startsWith("name:") }
                        ?.substringAfter(':')?.trim()?.trim('"', '\'')
                        ?.takeIf { it.isNotBlank() }
                        ?: file.parentFile.name
                    val description = header.firstOrNull { it.startsWith("description:") }
                        ?.substringAfter(':')?.trim()?.trim('"', '\'')
                        .orEmpty()
                    discovered.putIfAbsent(name.lowercase(), AgentSkill(name, description, file.absolutePath))
                }
        }
        return discovered.values.sortedBy { it.name.lowercase() }
    }

    private fun killTree(process: Process) {
        val descendants = process.descendants().toList().asReversed()
        descendants.forEach { it.destroy() }
        process.destroy()
        process.waitFor(1500, TimeUnit.MILLISECONDS)
        (descendants + process.descendants().toList())
            .distinct()
            .filter { it.isAlive }
            .forEach { it.destroyForcibly() }
        if (process.isAlive) process.destroyForcibly()
    }
}

/**
 * Skill roots are ordered from the provider's native locations to compatible
 * locations. Earlier roots win when two skills use the same name.
 */
internal fun skillRootsFor(
    agent: AgentKind,
    workspace: File?,
    home: File,
    codexHome: File = File(home, ".codex"),
): List<File> = when (agent) {
    // Codex desktop also exposes portable Agent Skills installed under
    // ~/.agents/skills (for example, skills installed by `npx skills`).
    AgentKind.Codex -> listOf(
        File(codexHome, "skills"),
        File(home, ".agents/skills"),
        File(codexHome, "plugins/cache"),
    )
    // Claude gives personal skills precedence over the project directory.
    AgentKind.ClaudeCode -> listOfNotNull(
        File(home, ".claude/skills"),
        workspace?.let { File(it, ".claude/skills") },
    )
    // Cursor discovers its own and portable Agent Skills at workspace and user
    // scope. It also recognizes compatible Codex skills, so include that root
    // after Cursor's native locations rather than hiding installed workflows.
    AgentKind.Cursor -> listOfNotNull(
        workspace?.let { File(it, ".cursor/skills") },
        workspace?.let { File(it, ".agents/skills") },
        File(home, ".cursor/skills"),
        File(home, ".cursor/skills-cursor"),
        File(home, ".agents/skills"),
        File(codexHome, "skills"),
    )
    // Antigravity CLI loads workspace Agent Skills and its own global root.
    AgentKind.Antigravity -> listOfNotNull(
        workspace?.let { File(it, ".agents/skills") },
        File(home, ".gemini/antigravity-cli/skills"),
    )
}

private const val SKILL_SEPARATOR = "\u001F"

/**
 * Host shells (Cursor terminals, local LLM proxy setups) often export variables that
 * vendor agent CLIs interpret as authoritative API config. Clear the known offenders
 * before applying intentional per-project env.
 */
internal fun scrubInheritedAgentEnvironment(env: MutableMap<String, String>) {
    listOf(
        "ANTHROPIC_BASE_URL",
        "NODE_OPTIONS",
        "VSCODE_INSPECTOR_OPTIONS",
        "ELECTRON_RUN_AS_NODE",
    ).forEach(env::remove)
}

internal fun agentFailureMessage(
    lastError: String?,
    authHint: String?,
    result: AgentEvent.TaskResult?,
    fallbackText: String?,
    exitCode: Int,
): String {
    lastError?.takeIf { it.isNotBlank() }?.let { return it }
    authHint?.takeIf { it.isNotBlank() }?.let { return it }
    if (result?.success == false) {
        result.finalText?.takeIf { it.isNotBlank() }?.let { return it.truncateForSummary(240) }
    }
    fallbackText?.takeIf { it.isNotBlank() }?.let { return it.truncateForSummary(240) }
    return "exited with code $exitCode"
}

