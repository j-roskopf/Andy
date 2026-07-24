package app.andy.desktop.service.agents

import app.andy.model.AgentCliStatus
import app.andy.model.AgentChangeSummary
import app.andy.model.AgentEvent
import app.andy.model.AgentFileDiff
import app.andy.model.AgentKind
import app.andy.model.andyQuestionArtifactHint
import app.andy.model.isGrillMeSkillName
import app.andy.model.AgentModelCatalog
import app.andy.model.AgentModelOption
import app.andy.model.AgentProviderDefaults
import app.andy.model.AgentQueuedFollowUp
import app.andy.model.AgentProviderQuota
import app.andy.model.AgentQuotaSource
import app.andy.model.AgentQuotaAccess
import app.andy.model.AgentSessionStatus
import app.andy.model.AgentSkill
import app.andy.model.AgentTask
import app.andy.model.AgentTaskDraft
import app.andy.model.AgentTaskStatus
import app.andy.model.AgentUserInputRequest
import app.andy.model.AgentThreadChangeSnapshot
import app.andy.model.AgentSandboxMode
import app.andy.model.grillMeInteractivePromptAddendum
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
import app.andy.model.followUpCliPayload
import app.andy.model.followUpPromptForLiveTerminal
import app.andy.model.promptForCli
import app.andy.model.providerDefaults
import app.andy.desktop.service.DesktopWorkspaceStore
import app.andy.model.TerminalAppearanceSnapshot
import app.andy.model.toTerminalAppearance
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val MAX_EVENTS_IN_MEMORY = 3000
private const val PROVIDER_QUOTA_REFRESH_MILLIS = 5 * 60 * 1000L
private val VERIFICATION_BLOCK = Regex("""<andy_verification>([\s\S]*?)</andy_verification>""")
private val REVIEW_BLOCK = Regex("""<andy_review>([\s\S]*?)</andy_review>""")
private val CursorChatIdRegex = Regex(
    """[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""",
)

class DesktopAgentRunService(
    private val scope: CoroutineScope,
    private val store: DesktopAgentTaskStore,
    private val locator: AgentCliLocator,
    private val adapters: Map<AgentKind, AgentCliAdapter>,
    private val worktrees: WorktreeManager,
    private val mcp: McpServerService,
    private val workspaceStore: WorkspaceStore,
    private val actionConfig: ActionConfigStore,
    private val enableProbes: Boolean = true,
) : AgentRunService, ProjectWorkflowService {
    private class TaskHandle(
        @Volatile var job: Job? = null,
        @Volatile var stopRequested: Boolean = false,
    )

    private val terminals = AgentTerminalManager(
        scope = scope,
        terminalAppearance = {
            when (val ws = workspaceStore) {
                is DesktopWorkspaceStore -> ws.state.value.toTerminalAppearance()
                else -> TerminalAppearanceSnapshot()
            }
        },
        scrollbackFile = { id -> store.scrollbackFile(id) },
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
    private val seenSessionIds = ConcurrentHashMap.newKeySet<String>()
    private val eventFlows = ConcurrentHashMap<String, MutableStateFlow<List<AgentEvent>>>()
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
            snapshotActiveTasksBeforeShutdown()
            handles.keys.toList().forEach(terminals::stop)
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
            legacyTranscriptChatsArchived = state.legacyTranscriptChatsArchived
            slots = Semaphore(state.maxConcurrent)
            _tasks.value = state.tasks
            _projects.value = recoverInterruptedWorkflows(state.projectWorkflows, state.tasks)
                .mapValues { (_, workflow) -> workflow.withMissingProfiles() }
            migrateLegacyProjectNotes()
            archiveLegacyTranscriptChats()
            backfillCursorPlansFromTranscripts()
            ready.complete(Unit)
            refreshCliStatuses()
            if (enableProbes) {
                refreshProviderQuotas()
                while (isActive) {
                    delay(PROVIDER_QUOTA_REFRESH_MILLIS)
                    refreshProviderQuotas()
                }
            }
        }
    }

    internal fun terminalWidget(taskId: String) = terminals.terminalWidget(taskId)

    /** Push latest Settings terminal appearance into live agent sessions. */
    internal fun reloadTerminalAppearance() = terminals.reloadAppearance()

    /** Observed by [app.andy.ui.agents.AgentTerminalSurface] so the SwingPanel mounts when the PTY appears. */
    internal val terminalSessionsRevision: StateFlow<Long> get() = terminals.sessionsRevision

    internal val attachedTerminalTaskIds: StateFlow<Set<String>> get() = terminals.attachedTaskIds

    /** Cumulative scrollback file for finished-chat replay (may not exist yet). */
    internal fun scrollbackFile(taskId: String): File = store.scrollbackFile(taskId)

    internal fun hasScrollback(taskId: String): Boolean = terminals.hasScrollback(taskId)

    override fun sessionStatus(taskId: String): StateFlow<AgentSessionStatus?> = terminals.statusFlow(taskId)

    override val sessionStatuses: StateFlow<Map<String, AgentSessionStatus>> = terminals.sessionStatuses

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
        val runId = newAgentTaskId()
        val prompt = specPrompt(spec, scratchpad, revisionRequest, runId)
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
            taskId = runId,
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
            singleReviewPass = draft.singleReviewPass,
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
            singleReviewPass = draft.singleReviewPass,
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
            build.reviewEnabled && latestReviewVerdict?.status == ProjectReviewStatus.ChangesRequested && !build.singleReviewPass -> startBuildAttempt(buildTaskId)
            build.reviewEnabled && latestReviewVerdict?.status == ProjectReviewStatus.ChangesRequested && build.singleReviewPass -> setPairAttention(build, reviewLimitReachedMessage(build))
            build.reviewEnabled && currentReviewApproval(review, buildRun.id, build.reviewGeneration) == null -> startReviewAttempt(buildTaskId)
            build.linkedVerificationTaskId != null -> startVerificationAttempt(buildTaskId)
            else -> completeBuildWithoutVerification(buildTaskId)
        }
    }

    override suspend fun startRecoveryFollowUp(
        buildTaskId: String,
        followUp: String,
        imagePaths: List<String>,
    ): String? {
        ready.await()
        val build = projectTask(buildTaskId)?.takeIf { it.kind == ProjectTaskKind.Build }
            ?: return "This Build workflow is no longer available."
        val review = build.linkedReviewTaskId?.let(::projectTask)
        val verification = build.linkedVerificationTaskId?.let(::projectTask)
        when {
            followUp.isBlank() && imagePaths.isEmpty() -> return "Describe the issue found during testing, or attach a screenshot, before starting a follow-up."
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
                imagePaths = imagePaths,
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

    override suspend fun createAndStart(draft: AgentTaskDraft): AgentTask = createAndStart(draft, taskId = null)

    private suspend fun createAndStart(draft: AgentTaskDraft, taskId: String?): AgentTask {
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
        val id = taskId ?: newAgentTaskId()
        val resolvedCwd = withContext(Dispatchers.IO) {
            AgentScratchWorkspace.resolveCwd(draft.existingWorktreePath ?: draft.directory)
        }
        var task = AgentTask(
            id = id,
            title = draft.title.ifBlank { draft.prompt.truncateForSummary(60) },
            prompt = draft.prompt,
            agent = draft.agent,
            projectId = draft.projectId,
            cwd = resolvedCwd,
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
            vendorSessionId = null,
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
                task = task.copy(changeBaselineTree = baseline)
            }
        }

        upsertTask(task)
        persist()
        val adapter = adapters.getValue(task.agent)
        val initialPrompt = task.promptForCli().takeIf { it.isNotBlank() }
        // Prefer argv/flag delivery when the CLI supports it (agy --prompt-interactive,
        // claude/codex/cursor positional). PTY typing is a fragile fallback.
        val writeAfterStart = initialPrompt.takeUnless { adapter.embedsInitialPrompt }
        // Do not await the PTY on the caller's dispatcher (often Main/EDT). KetraTerm
        // creates the SwingTerminal via invokeAndWait — awaiting here can deadlock the EDT
        // and leave the UI stuck on "Starting terminal…" even after the session is Idle.
        launchRun(task, writeAfterStart = writeAfterStart) { nextAdapter, resolvedBinary, mcpUrl ->
            nextAdapter.buildInteractiveCommand(resolvedBinary, currentTask(task.id) ?: task, mcpUrl)
        }
        return task
    }

    override fun resume(taskId: String, followUp: String, imagePaths: List<String>, skills: List<AgentSkill>) {
        val task = currentTask(taskId) ?: return
        if (task.userInputRequest != null) return
        val adapter = adapters[task.agent] ?: return

        _lastUsedAgent.value = task.agent

        val skillDirectory = task.worktreePath ?: task.cwd
        val selectedSkills = skills.filter { skill ->
            this.skills(task.agent, skillDirectory).value.any { it.path == skill.path }
        }
        val followUpCli = task.followUpCliPayload(followUp, imagePaths, selectedSkills)
        val followUpForCli = followUpCli.prompt
        val followUpImagePathsForCli = followUpCli.imagePaths

        if (terminals.isAlive(taskId)) {
            val now = System.currentTimeMillis()
            appendEvents(taskId, listOf(AgentEvent.UserMessage(now, followUp, selectedSkills, imagePaths)))
            updateTask(taskId) {
                it.copy(
                    status = AgentTaskStatus.Running,
                    exitCode = null,
                    errorMessage = null,
                    finishedAtMillis = null,
                    unread = false,
                )
            }
            terminals.write(taskId, task.followUpPromptForLiveTerminal(followUp, imagePaths, selectedSkills))
            scope.launch { persist() }
            return
        }

        if (task.isActive) return
        // Only keep a stored agy conversation id when we can prove it belongs to
        // this Andy task (history/transcript contains the original prompt).
        val resolvedAgyId = if (task.agent == AgentKind.Antigravity) {
            AntigravityConversationIds.resolveForTask(task)
        } else {
            task.vendorSessionId
        }
        if (task.agent == AgentKind.Antigravity && task.vendorSessionId != resolvedAgyId) {
            updateTask(taskId) { it.copy(vendorSessionId = resolvedAgyId) }
        }
        val taskForResume = if (resolvedAgyId != task.vendorSessionId) {
            task.copy(vendorSessionId = resolvedAgyId)
        } else {
            task
        }
        val resumeArgv = runCatching {
            adapter.buildInteractiveResumeCommand(
                binaryFor(task.agent) ?: return,
                taskForResume,
                null,
                followUpForCli,
                followUpImagePathsForCli,
            )
        }.getOrNull()

        val now = System.currentTimeMillis()
        appendEvents(taskId, listOf(AgentEvent.UserMessage(now, followUp, selectedSkills, imagePaths)))
        val queued = taskForResume.copy(
            status = AgentTaskStatus.Queued,
            exitCode = null,
            errorMessage = null,
            finishedAtMillis = null,
            unread = false,
        )
        upsertTask(queued)
        scope.launch {
            persist()
            if (resumeArgv == null) {
                // Provider cannot resume (missing vendor session). Start a fresh
                // interactive session that still includes the original Andy prompt.
                val seeded = composeResumePrompt(
                    originalPrompt = queued.promptForCli(),
                    followUp = followUpForCli,
                    boundToConversation = false,
                ) ?: followUpForCli
                val writeAfterStart = seeded.takeUnless { adapter.embedsInitialPrompt }
                launchRunAwaitingTerminal(queued, writeAfterStart = writeAfterStart) { nextAdapter, binary, mcpUrl ->
                    val current = currentTask(taskId) ?: queued
                    nextAdapter.buildInteractiveCommand(
                        binary,
                        current.copy(
                            prompt = seeded,
                            imagePaths = if (current.agent == AgentKind.Codex) {
                                (current.imagePaths + followUpImagePathsForCli).distinct()
                            } else {
                                current.imagePaths
                            },
                        ),
                        mcpUrl,
                    )
                }
                return@launch
            }
            // Await the PTY so the detail pane remounts the live terminal instead of
            // staying on the "session ended" placeholder until a manual refresh.
            val writeAfterStart = followUpForCli.takeUnless { adapter.embedsResumePrompt }
            launchRunAwaitingTerminal(queued, writeAfterStart = writeAfterStart) { resumeAdapter, binary, mcpUrl ->
                resumeAdapter.buildInteractiveResumeCommand(
                    binary,
                    currentTask(taskId) ?: queued,
                    mcpUrl,
                    followUpForCli,
                    followUpImagePathsForCli,
                ) ?: error("interactive resume not supported")
            }
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

        terminals.get(taskId)?.artifacts?.writeAnswer(response)

        if (terminals.isAlive(taskId)) {
            updateTask(taskId) {
                it.copy(
                    status = AgentTaskStatus.Running,
                    userInputRequest = null,
                    exitCode = null,
                    errorMessage = null,
                    finishedAtMillis = null,
                    unread = false,
                )
            }
            terminals.write(taskId, response)
            scope.launch { persist() }
            return
        }

        val next = task.copy(
            status = AgentTaskStatus.Queued,
            userInputRequest = null,
            exitCode = null,
            errorMessage = null,
            finishedAtMillis = null,
            unread = false,
        )
        upsertTask(next)
        scope.launch { persist() }
        val adapter = adapters.getValue(task.agent)
        val writeAfterStart = response.takeUnless { adapter.embedsResumePrompt }
        launchRun(next, writeAfterStart = writeAfterStart) { resumeAdapter, binary, mcpUrl ->
            resumeAdapter.buildInteractiveResumeCommand(
                binary,
                currentTask(taskId) ?: next,
                mcpUrl,
                response,
            ) ?: error("interactive resume not supported")
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
            vendorSessionId = null,
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
            changeBaselineTree = implementationBaseline,
            completedChanges = null,
            unread = false,
        )
        appendEvents(taskId, listOf(AgentEvent.UserMessage(now, implementationPrompt, task.skills)))
        upsertTask(implementationTask)
        persist()
        launchRunAwaitingTerminal(
            implementationTask,
            writeAfterStart = implementationTask.promptForCli()
                .takeIf { it.isNotBlank() }
                .takeUnless { adapters.getValue(implementationTask.agent).embedsInitialPrompt },
        ) { adapter, binary, mcpUrl ->
            adapter.buildInteractiveCommand(binary, currentTask(taskId) ?: implementationTask, mcpUrl)
        }
    }

    override fun queueFollowUp(taskId: String, followUp: String, imagePaths: List<String>, skills: List<AgentSkill>) {
        val task = currentTask(taskId) ?: return
        if (!task.isActive && !terminals.isAlive(taskId)) return

        val text = followUp.trim()
        if (text.isBlank() && imagePaths.isEmpty()) return
        val skillDirectory = task.worktreePath ?: task.cwd
        val selectedSkills = skills.filter { skill ->
            this.skills(task.agent, skillDirectory).value.any { it.path == skill.path }
        }
        val followUpCli = task.followUpCliPayload(text, imagePaths, selectedSkills)
        val followUpForCli = followUpCli.prompt

        if (terminals.isAlive(taskId)) {
            val now = System.currentTimeMillis()
            appendEvents(taskId, listOf(AgentEvent.UserMessage(now, text, selectedSkills, imagePaths)))
            terminals.write(taskId, task.followUpPromptForLiveTerminal(text, imagePaths, selectedSkills))
            return
        }

        updateTask(taskId) { current ->
            current.copy(
                queuedFollowUps = current.queuedFollowUps + AgentQueuedFollowUp(
                    text = text,
                    imagePaths = imagePaths,
                    skills = selectedSkills,
                ),
            )
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
            vendorSessionId = null,
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
        store.deleteTaskArtifacts(taskId)
        eventFlows[taskId]?.value = emptyList()
        upsertTask(retried)
        persist()
        launchRunAwaitingTerminal(
            retried,
            writeAfterStart = retried.promptForCli()
                .takeIf { it.isNotBlank() }
                .takeUnless { adapters.getValue(retried.agent).embedsInitialPrompt },
        ) { adapter, resolvedBinary, mcpUrl ->
            adapter.buildInteractiveCommand(resolvedBinary, currentTask(taskId) ?: retried, mcpUrl)
        }
    }

    override fun updateGoal(taskId: String, goal: String?) {
        val normalizedGoal = goal?.trim()?.takeIf { it.isNotBlank() }
        val task = currentTask(taskId) ?: return
        if (task.goal == normalizedGoal) return
        updateTask(taskId) { it.copy(goal = normalizedGoal) }
        scope.launch { persist() }
    }

    private fun newAgentTaskId(): String = "task-" + UUID.randomUUID().toString().replace("-", "").take(10)

    /**
     * Starts the agent PTY in the background. When [awaitTerminal] is true, blocks
     * until the SwingTerminal exists so the detail pane can mount it on first paint.
     */
    private fun launchRun(
        task: AgentTask,
        writeAfterStart: String? = null,
        argvBuilder: (AgentCliAdapter, String, String?) -> List<String>,
    ): CompletableDeferred<Boolean> {
        val handle = TaskHandle()
        handles[task.id] = handle
        val terminalReady = CompletableDeferred<Boolean>()
        handle.job = scope.launch(Dispatchers.IO) {
            ready.await()
            slots.withPermit {
                if (handle.stopRequested) {
                    terminalReady.complete(false)
                    return@withPermit
                }
                runProcess(task.id, handle, argvBuilder, writeAfterStart, onTerminalStarted = {
                    terminalReady.complete(true)
                })
            }
        }
        handle.job?.invokeOnCompletion {
            if (!terminalReady.isCompleted) terminalReady.complete(false)
        }
        return terminalReady
    }

    private suspend fun launchRunAwaitingTerminal(
        task: AgentTask,
        writeAfterStart: String? = null,
        argvBuilder: (AgentCliAdapter, String, String?) -> List<String>,
    ) {
        // Await only when not on the UI/EDT thread. createAndStart intentionally
        // skips this so Compose Main never blocks across KetraTerm's invokeAndWait.
        val terminalReady = launchRun(task, writeAfterStart, argvBuilder)
        withTimeoutOrNull(20_000) { terminalReady.await() }
    }

    private suspend fun runProcess(
        taskId: String,
        handle: TaskHandle,
        argvBuilder: (AgentCliAdapter, String, String?) -> List<String>,
        writeAfterStart: String? = null,
        onTerminalStarted: () -> Unit = {},
    ) {
        val task = currentTask(taskId) ?: return
        val adapter = adapters.getValue(task.agent)
        val binary = binaryFor(task.agent)
        if (binary == null) {
            finishTask(taskId, AgentTaskStatus.Failed, exitCode = null, error = unavailableCliMessage(task.agent))
            return
        }

        if (task.agent == AgentKind.Cursor) {
            ensureCursorVendorSession(taskId, binary, task.cwd)
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
        val env = buildAgentLaunchEnvironment(projectEnv)

        updateTask(taskId) { it.copy(status = AgentTaskStatus.Running, startedAtMillis = System.currentTimeMillis()) }
        persist()
        reconcileWorkflowRun(taskId)

        if (handle.stopRequested) {
            finishTask(taskId, AgentTaskStatus.Stopped, exitCode = null, error = null)
            return
        }

        val agyBeforeConversationId = if (task.agent == AgentKind.Antigravity) {
            AntigravityConversationIds.lastForWorkspace(task.cwd)
        } else {
            null
        }
        val agyLaunchStartedAt = System.currentTimeMillis()
        val agyLaunchedPrompt = if (task.agent == AgentKind.Antigravity) {
            promptFromInteractiveArgv(argv)
        } else {
            null
        }

        val terminalHandle = runCatching {
            terminals.start(
                task = currentTask(taskId) ?: task,
                argv = argv,
                env = env,
                isTabSeen = { taskId in seenSessionIds },
            )
        }.getOrElse { error ->
            finishTask(taskId, AgentTaskStatus.Failed, exitCode = null, error = "failed to start: ${error.message}")
            return
        }
        writeLaunchDiagnostics(taskId, binary, argv, projectEnv)
        onTerminalStarted()

        if (task.agent == AgentKind.Antigravity) {
            scope.launch(Dispatchers.IO) {
                captureAntigravityConversationId(
                    taskId = taskId,
                    cwd = task.cwd,
                    before = agyBeforeConversationId,
                    launchedPrompt = agyLaunchedPrompt,
                    startedAtMillis = agyLaunchStartedAt,
                )
            }
        }

        // Submit the first turn only after the interactive TUI is accepting input.
        // A fixed delay races the splash screen and drops the prompt (esp. agy).
        writeAfterStart?.takeIf { it.isNotBlank() }?.let { text ->
            writeInitialPromptWhenReady(taskId, handle, text)
        }

        if (handle.stopRequested) {
            terminals.stop(taskId)
            finishTask(taskId, AgentTaskStatus.Stopped, exitCode = null, error = null)
            return
        }

        val outcomeHandled = AtomicBoolean(false)
        val artifacts = terminalHandle.artifacts
        val sessionMonitorJob = scope.launch {
            var sawWorking = false
            terminalHandle.statusTracker.status.collect { status ->
                if (outcomeHandled.get()) return@collect
                if (status == AgentSessionStatus.Working) sawWorking = true
                val current = currentTask(taskId) ?: return@collect
                if (current.workflowStage != ProjectWorkflowStage.Build) return@collect
                val scrollback = terminals.bufferSnapshot(taskId)
                if (
                    !inferWorkflowBuildTurnComplete(
                        agent = current.agent,
                        artifactDir = terminalHandle.artifactDir,
                        scrollback = scrollback,
                        liveSessionStatus = status,
                        sawWorking = sawWorking,
                    )
                ) {
                    return@collect
                }
                outcomeHandled.set(true)
                terminalHandle.statusTracker.markPhaseFinished()
                terminals.stop(taskId)
                finishTask(taskId, AgentTaskStatus.Completed, exitCode = 0, error = null)
            }
        }
        val monitorJob = scope.launch {
            artifacts.events.collect { event ->
                if (outcomeHandled.get()) return@collect
                when (event) {
                    is AgentWorkflowArtifacts.Event.PlanReady -> {
                        updateTask(taskId) { current -> current.copy(completedPlanText = event.text) }
                        terminalHandle.statusTracker.markPhaseFinished()
                        val current = currentTask(taskId) ?: return@collect
                        if (current.planMode || current.workflowStage == ProjectWorkflowStage.Spec) {
                            outcomeHandled.set(true)
                            terminals.stop(taskId)
                            finishTask(taskId, AgentTaskStatus.Completed, exitCode = 0, error = null)
                        }
                    }
                    is AgentWorkflowArtifacts.Event.ReviewReady -> {
                        updateTask(taskId) { current -> current.copy(completedResultText = event.json) }
                        terminalHandle.statusTracker.markPhaseFinished()
                        outcomeHandled.set(true)
                        terminals.stop(taskId)
                        finishTask(taskId, AgentTaskStatus.Completed, exitCode = 0, error = null)
                    }
                    is AgentWorkflowArtifacts.Event.VerificationReady -> {
                        updateTask(taskId) { current -> current.copy(completedResultText = event.json) }
                        terminalHandle.statusTracker.markPhaseFinished()
                        outcomeHandled.set(true)
                        terminals.stop(taskId)
                        finishTask(taskId, AgentTaskStatus.Completed, exitCode = 0, error = null)
                    }
                    is AgentWorkflowArtifacts.Event.QuestionReady -> {
                        waitForUserInput(taskId, event.request, exitCode = 0, keepTerminal = true)
                    }
                }
            }
        }

        val exitCode = terminals.awaitExit(taskId)
        monitorJob.cancel()
        sessionMonitorJob.cancel()

        if (outcomeHandled.get()) return
        if (currentTask(taskId)?.status == AgentTaskStatus.WaitingForInput) return

        val current = currentTask(taskId) ?: return
        val planFromDisk = artifacts.planFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }
        val reviewFromDisk = artifacts.reviewFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }
        val verificationFromDisk = artifacts.verificationFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }

        val status = when {
            handle.stopRequested -> AgentTaskStatus.Stopped
            exitCode == 0 -> AgentTaskStatus.Completed
            else -> AgentTaskStatus.Failed
        }
        val completedPlanText = if (status == AgentTaskStatus.Completed && current.planMode) {
            planFromDisk ?: current.completedPlanText
        } else {
            null
        }
        val completedResultText = when {
            reviewFromDisk != null -> reviewFromDisk
            verificationFromDisk != null -> verificationFromDisk
            status == AgentTaskStatus.Completed -> current.completedResultText
            else -> current.completedResultText
        }
        updateTask(taskId) { task ->
            task.copy(
                completedPlanText = completedPlanText ?: task.completedPlanText,
                completedResultText = completedResultText ?: task.completedResultText,
            )
        }
        val failureError = if (status == AgentTaskStatus.Failed) {
            agentFailureMessage(
                lastError = null,
                authHint = null,
                result = null,
                fallbackText = null,
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
                    appendLine("planFromDisk=${planFromDisk.orEmpty().take(500)}")
                    appendLine("reviewFromDisk=${reviewFromDisk.orEmpty().take(500)}")
                    appendLine("verificationFromDisk=${verificationFromDisk.orEmpty().take(500)}")
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

    private suspend fun ensureCursorVendorSession(taskId: String, binary: String, cwd: String?) {
        val current = currentTask(taskId) ?: return
        if (!current.vendorSessionId.isNullOrBlank()) return
        val chatId = withContext(Dispatchers.IO) { createCursorChatId(binary, cwd) } ?: return
        updateTask(taskId) { it.copy(vendorSessionId = chatId) }
        appendLaunchDiagnostics(taskId, "vendorSessionId=$chatId source=create-chat\n")
        persist()
    }

    private fun createCursorChatId(binary: String, cwd: String?): String? = runCatching {
        val pb = ProcessBuilder(binary, "create-chat").redirectErrorStream(true)
        val workDir = cwd?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }
            ?: AgentScratchWorkspace.ensure()
        pb.directory(workDir)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val finished = process.waitFor(20, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runCatching null
        }
        if (process.exitValue() != 0) return@runCatching null
        CursorChatIdRegex.find(output)?.value
    }.getOrNull()

    private fun promptFromInteractiveArgv(argv: List<String>): String? {
        val idx = argv.indexOfFirst { it == "--prompt-interactive" || it == "-i" }
        if (idx < 0 || idx + 1 >= argv.size) return null
        return argv[idx + 1].trim().takeIf { it.isNotBlank() }
    }

    private fun captureAntigravityConversationId(
        taskId: String,
        cwd: String?,
        before: String?,
        launchedPrompt: String?,
        startedAtMillis: Long,
    ) {
        val captured = AntigravityConversationIds.awaitNewConversationId(
            cwd = cwd,
            before = before,
            launchedPrompt = launchedPrompt,
            startedAtMillis = startedAtMillis,
        ) ?: return
        if (captured.isBlank() || captured == before) return
        updateTask(taskId) { task ->
            if (task.vendorSessionId == captured) task else task.copy(vendorSessionId = captured)
        }
        appendLaunchDiagnostics(
            taskId,
            "vendorSessionId=$captured before=${before.orEmpty()} launchedPrompt=${launchedPrompt?.take(80).orEmpty()}\n",
        )
        scope.launch { persist() }
    }

    /**
     * Wait until the interactive TUI shows an input prompt, then type the first turn.
     * Writing during splash (agy banner, model warnings) is silently discarded.
     */
    private suspend fun writeInitialPromptWhenReady(taskId: String, handle: TaskHandle, text: String) {
        val deadline = System.currentTimeMillis() + 30_000
        var sawOutput = false
        var wrote = false
        while (System.currentTimeMillis() < deadline) {
            if (handle.stopRequested || !terminals.isAlive(taskId)) return
            val buffer = terminals.bufferSnapshot(taskId)
            if (buffer.isNotBlank()) sawOutput = true
            val idle = terminals.statusFlow(taskId).value == AgentSessionStatus.Idle
            if (sawOutput && (terminalLooksReadyForInput(buffer) || idle)) {
                delay(300)
                if (handle.stopRequested || !terminals.isAlive(taskId)) return
                terminals.submitText(taskId, text.trimEnd('\r', '\n'))
                wrote = true
                appendLaunchDiagnostics(taskId, "initialPromptWritten=true readyIdle=$idle\n")
                return
            }
            delay(150)
        }
        if (!wrote && !handle.stopRequested && terminals.isAlive(taskId)) {
            appendLaunchDiagnostics(taskId, "initialPromptFallbackWrite=true\n")
            terminals.submitText(taskId, text.trimEnd('\r', '\n'))
        }
    }

    private fun terminalLooksReadyForInput(buffer: String): Boolean =
        terminalBufferLooksReadyForInput(buffer)

    private fun snapshotActiveTasksBeforeShutdown() {
        val updated = _tasks.value.map { task ->
            if (task.status != AgentTaskStatus.Running) return@map task
            val artifactDir = AgentWorkflowArtifacts.dirFor(task.cwd?.let(::File), task.id)
            val scrollback = terminals.bufferSnapshot(task.id).ifBlank {
                store.scrollbackFile(task.id).takeIf { it.isFile }?.readText().orEmpty()
            }
            val liveStatus = terminals.liveSessionStatus(task.id)
            when {
                inferCompletedTurn(task.agent, artifactDir, scrollback, liveStatus) ->
                    task.copy(
                        status = AgentTaskStatus.Completed,
                        exitCode = task.exitCode ?: 0,
                        finishedAtMillis = task.finishedAtMillis ?: System.currentTimeMillis(),
                        unread = true,
                    )
                inferPausedAtPrompt(task.agent, artifactDir, scrollback, liveStatus) ->
                    task.copy(
                        status = AgentTaskStatus.Paused,
                        finishedAtMillis = task.finishedAtMillis ?: System.currentTimeMillis(),
                    )
                else -> task
            }
        }
        _tasks.value = updated
        persistSync()
    }

    private fun persistSync() {
        store.saveSync(
            AgentStoreState(
                tasks = _tasks.value,
                binaryOverrides = binaryOverrides,
                providerDefaults = _providerDefaults.value,
                quotaAccess = _quotaAccess.value,
                lastUsedAgent = _lastUsedAgent.value,
                maxConcurrent = storedMaxConcurrent,
                projectWorkflows = _projects.value,
                legacyTranscriptChatsArchived = legacyTranscriptChatsArchived,
            ),
        )
    }

    private fun waitForUserInput(
        taskId: String,
        request: AgentUserInputRequest,
        exitCode: Int,
        keepTerminal: Boolean = false,
    ) {
        updateTask(taskId) { task ->
            if (task.status == AgentTaskStatus.Running || task.status == AgentTaskStatus.WaitingForInput) {
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
        if (!keepTerminal) {
            handles.remove(taskId)
        }
        scope.launch {
            persist()
            reconcileWorkflowRun(taskId)
        }
    }

    private fun implementationPromptFor(originalRequest: String, completedPlan: String): String = buildString {
        append("Begin implementation. Implement the completed plan below: make the edits and run the relevant verification.\n\n")
        append("Original request:\n")
        append(originalRequest.trim())
        append("\n\nCompleted plan:\n")
        append(completedPlan.trim())
    }

    override fun completeWorkflowRun(taskId: String) {
        val task = currentTask(taskId) ?: return
        if (!task.isActive || task.workflowStage != ProjectWorkflowStage.Build) return
        handles[taskId]?.stopRequested = true
        terminals.stop(taskId)
        finishTask(taskId, AgentTaskStatus.Completed, exitCode = 0, error = null)
    }

    override fun stop(taskId: String) {
        val waiting = currentTask(taskId)?.takeIf { it.status == AgentTaskStatus.WaitingForInput }
        if (waiting != null) {
            terminals.stop(taskId)
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
        val handle = handles[taskId] ?: run {
            terminals.stop(taskId)
            return
        }
        handle.stopRequested = true
        scope.launch(Dispatchers.IO) {
            terminals.stop(taskId)
            if (handle.job?.isActive != true) {
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
        terminals.clear(taskId)
        eventFlows.remove(taskId)
        _tasks.update { list -> list.filterNot { it.id == taskId } }
        store.deleteTaskArtifacts(taskId)
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
        currentTask(taskId) ?: return emptyEvents
        return eventFlows.computeIfAbsent(taskId) { MutableStateFlow(emptyList()) }
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
        val baseline = task.changeBaselineTree ?: return@withContext null
        val cwd = task.cwd ?: return@withContext null
        worktrees.changeSummary(cwd, baseline)
    }

    override suspend fun fileDiff(taskId: String, relativePath: String): AgentFileDiff? = withContext(Dispatchers.IO) {
        val task = currentTask(taskId) ?: return@withContext null
        task.completedChanges?.diffs?.get(relativePath)?.let { return@withContext it }
        val cwd = task.cwd ?: return@withContext null
        worktrees.fileDiff(cwd, relativePath, task.changeBaselineTree)
    }

    override suspend fun refreshCliStatuses() {
        ready.await()
        val statuses = withContext(Dispatchers.IO) { locator.locateAll(binaryOverrides) }
        _cliStatuses.value = statuses
        if (!enableProbes) return
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

    fun close() {
        terminals.stopAll()
        handles.values.forEach { it.job?.cancel() }
    }

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

    private fun specPrompt(
        spec: ProjectTask,
        scratchpad: String?,
        revisionRequest: String?,
        runTaskId: String,
    ): String = buildString {
        val artifactRelPath = ".andy/$runTaskId"
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
            append("\n\n").append(grillMeInteractivePromptAddendum(artifactRelPath))
        } else {
            append(
                "\n\nWrite the complete implementation specification to `$artifactRelPath/plan.md`, " +
                    "including interfaces, edge cases, and verification steps, then stop (exit the session).",
            )
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
        runTaskId: String,
        manualRecovery: Boolean = false,
    ): String = buildString {
        val artifactRelPath = ".andy/$runTaskId"
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
            "\n\nWrite your review verdict to `$artifactRelPath/review.json` using this JSON schema, then stop (exit the session):\n" +
                """{"status":"approved|changes_requested","summary":"...","findings":[{"severity":"blocking|warning|nit","title":"...","details":"...","file":"optional","line":123}]}""" +
                "\nApproved forbids blocking findings. Changes requested requires at least one blocking finding.",
        )
        append('\n').append(andyQuestionArtifactHint(artifactRelPath))
    }

    private fun verificationPrompt(
        build: ProjectTask,
        buildRun: AgentTask,
        reviewRun: AgentTask?,
        reviewVerdict: ProjectReviewVerdict?,
        scratchpad: String?,
        runTaskId: String,
    ): String = buildString {
        val artifactRelPath = ".andy/$runTaskId"
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
            "\n\nWrite your verification verdict to `$artifactRelPath/verification.json` using this JSON schema, then stop (exit the session):\n" +
                """{"status":"passed|failed","summary":"...","evidence":["..."],"failures":["..."]}""" +
                "\nA passed result requires non-empty evidence and an empty failures list. A failed result requires at least one failure.",
        )
        append('\n').append(andyQuestionArtifactHint(artifactRelPath))
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
        if (build.reviewEnabled && reviewFailureCount(build, linkedReview) >= effectiveMaxReviewFailures(build)) {
            setPairAttention(build, reviewLimitReachedMessage(build))
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
        if (reviewFailureCount(build, review) >= effectiveMaxReviewFailures(build)) {
            setPairAttention(build, reviewLimitReachedMessage(build))
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
        val runId = newAgentTaskId()
        val prompt = reviewPrompt(build, buildRun, scratchpad, runId, manualRecovery)
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
            taskId = runId,
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
        val runId = newAgentTaskId()
        val prompt = verificationPrompt(build, buildRun, reviewRun, reviewVerdict, scratchpad, runId)
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
            taskId = runId,
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
                    AgentTaskStatus.Paused -> ProjectTaskState.Waiting
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
        if (run.status == AgentTaskStatus.WaitingForInput || run.status == AgentTaskStatus.Paused) {
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
            text = artifactTextForRun(run, "review.json") ?: run.completedResultText,
            runId = run.id,
            reviewedBuildRunId = reviewedBuildRunId,
            reviewGeneration = attempt.reviewGeneration,
            atMillis = run.finishedAtMillis ?: System.currentTimeMillis(),
        )
        if (parsed == null) {
            setPairAttention(build, "review did not return one valid review.json artifact")
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
        if (reviewFailureCount(build, refreshedReview) >= effectiveMaxReviewFailures(build)) {
            setPairAttention(build, reviewLimitReachedMessage(build))
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
            text = artifactTextForRun(run, "verification.json") ?: run.completedResultText,
            runId = run.id,
            atMillis = run.finishedAtMillis ?: System.currentTimeMillis(),
            reviewedBuildRunId = attempt?.reviewedBuildRunId,
            reviewGeneration = attempt?.reviewGeneration ?: 0,
        )
        if (parsed == null) {
            setPairAttention(build, "verification did not return one valid verification.json artifact")
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

    private fun artifactTextForRun(run: AgentTask, fileName: String): String? {
        val file = File(AgentWorkflowArtifacts.dirFor(run.cwd?.let(::File), run.id), fileName)
        return file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun parseReviewVerdict(
        text: String?,
        runId: String,
        reviewedBuildRunId: String,
        reviewGeneration: Int,
        atMillis: Long,
    ): ProjectReviewVerdict? {
        val output = text.orEmpty().trim()
        if (output.isNotBlank()) {
            AgentWorkflowArtifacts.parseReviewJson(
                raw = output,
                runId = runId,
                reviewedBuildRunId = reviewedBuildRunId,
                reviewGeneration = reviewGeneration,
                atMillis = atMillis,
            )?.let { return it }
        }
        val matches = REVIEW_BLOCK.findAll(output).toList()
        val terminal = matches.lastOrNull() ?: return null
        if (terminal.range.last != output.lastIndex) return null
        return AgentWorkflowArtifacts.parseReviewJson(
            raw = terminal.groupValues[1],
            runId = runId,
            reviewedBuildRunId = reviewedBuildRunId,
            reviewGeneration = reviewGeneration,
            atMillis = atMillis,
        )
    }

    private fun parseVerificationVerdict(
        text: String?,
        runId: String,
        atMillis: Long,
        reviewedBuildRunId: String?,
        reviewGeneration: Int,
    ): ProjectVerificationVerdict? {
        val output = text.orEmpty().trim()
        if (output.isNotBlank()) {
            AgentWorkflowArtifacts.parseVerificationJson(
                raw = output,
                runId = runId,
                atMillis = atMillis,
                reviewedBuildRunId = reviewedBuildRunId,
                reviewGeneration = reviewGeneration,
            )?.let { return it }
        }
        val matches = VERIFICATION_BLOCK.findAll(output).toList()
        val terminal = matches.lastOrNull() ?: return null
        if (terminal.range.last != output.lastIndex) return null
        return AgentWorkflowArtifacts.parseVerificationJson(
            raw = terminal.groupValues[1],
            runId = runId,
            atMillis = atMillis,
            reviewedBuildRunId = reviewedBuildRunId,
            reviewGeneration = reviewGeneration,
        )
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

    private fun effectiveMaxReviewFailures(build: ProjectTask): Int =
        if (build.singleReviewPass) 1 else build.maxReviewFailures

    private fun reviewLimitReachedMessage(build: ProjectTask): String =
        if (build.singleReviewPass) {
            "review requested changes (single review pass)"
        } else {
            "review requested changes ${build.maxReviewFailures} times"
        }

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

    /**
     * Hides pre-scrollback chats from the project sidebar. Those sessions only had
     * legacy transcript.jsonl (already removed); scrollback.ansi is the replay source.
     */
    private suspend fun archiveLegacyTranscriptChats() {
        if (legacyTranscriptChatsArchived) return
        val candidates = withContext(Dispatchers.IO) {
            _tasks.value.filter { task ->
                !task.archived && !task.isActive && !store.scrollbackFile(task.id).isFile
            }
        }
        if (candidates.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                val backupDir = File(
                    store.storeFile.parentFile,
                    "backups/pre-legacy-chat-archive-${System.currentTimeMillis()}",
                )
                backupDir.mkdirs()
                store.storeFile.copyTo(File(backupDir, "agents.toml"), overwrite = true)
                File(backupDir, "archived-task-ids.txt").writeText(
                    candidates.joinToString("\n") { "${it.id}\t${it.title}" },
                )
            }
            val archivedIds = candidates.map { it.id }.toSet()
            _tasks.update { tasks ->
                tasks.map { task ->
                    if (task.id in archivedIds) task.copy(archived = true, unread = false) else task
                }
            }
        }
        legacyTranscriptChatsArchived = true
        persist()
    }

    /** Repairs Cursor plan-mode runs saved before plan.md artifacts were retained. */
    private suspend fun backfillCursorPlansFromTranscripts() {
        val recoveredPlans = withContext(Dispatchers.IO) {
            _tasks.value.asSequence()
                .filter { task ->
                    task.agent == AgentKind.Cursor &&
                        task.planMode &&
                        task.status == AgentTaskStatus.Completed &&
                        task.completedPlanText.isNullOrBlank()
                }
                .mapNotNull { task ->
                    val plan = runCatching {
                        AgentWorkflowArtifacts.dirFor(task.cwd?.let(::File), task.id)
                            .resolve("plan.md")
                            .takeIf { it.isFile }
                            ?.readText()
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
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
        flow.update { existing -> (existing + events).takeLast(MAX_EVENTS_IN_MEMORY) }
    }

    override fun markRead(taskId: String) {
        seenSessionIds.add(taskId)
        terminals.markSeen(taskId)
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
            val baseline = task.changeBaselineTree
            task.cwd?.takeIf { baseline != null }?.let { cwd ->
                worktrees.changeSnapshot(cwd, baseline)
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
                    legacyTranscriptChatsArchived = legacyTranscriptChatsArchived,
                ),
            )
        }
    }

    @Volatile
    private var storedMaxConcurrent: Int = 8

    @Volatile
    private var legacyTranscriptChatsArchived: Boolean = false

    private fun writeLaunchDiagnostics(
        taskId: String,
        binary: String,
        argv: List<String>,
        projectEnv: Map<String, String>,
    ) {
        runCatching {
            val file = store.launchLogFile(taskId)
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
            val file = store.launchLogFile(taskId)
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

