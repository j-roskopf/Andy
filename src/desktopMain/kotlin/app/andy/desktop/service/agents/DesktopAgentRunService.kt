package app.andy.desktop.service.agents

import app.andy.model.AgentCliStatus
import app.andy.model.AgentChangeSummary
import app.andy.model.AgentContextualProvenance
import app.andy.model.AgentEvent
import app.andy.model.AgentFileDiff
import app.andy.model.AgentKind
import app.andy.model.AgentLaneKind
import app.andy.model.acpSupported
import app.andy.model.defaultLane
import app.andy.model.andyQuestionArtifactHint
import app.andy.model.isGrillMeSkillName
import app.andy.model.AgentModelCatalog
import app.andy.model.AgentModelOption
import app.andy.model.AgentProviderDefaults
import app.andy.model.AgentMessageDeliveryMode
import app.andy.model.AgentQueuedFollowUp
import app.andy.model.AgentProviderQuota
import app.andy.model.AgentQuotaSource
import app.andy.model.AgentQuotaAccess

import app.andy.model.AgentSkill
import app.andy.model.AgentSlashCommand
import app.andy.model.AgentTask
import app.andy.model.AgentTaskDraft
import app.andy.model.WorktreeBaseOption
import app.andy.model.WorktreeDeleteOutcome
import app.andy.model.WorktreeMergeOutcome
import app.andy.model.WorktreeNode
import app.andy.model.fallbackTitle
import app.andy.model.AgentStatus
import app.andy.model.AgentUserInputRequest
import app.andy.model.AgentThreadChangeSnapshot
import app.andy.model.ConfigSource
import app.andy.model.AgentSandboxMode
import app.andy.model.looksLikePlanMode
import app.andy.model.grillMeInteractivePromptAddendum
import app.andy.model.specPlanWriteInstruction
import app.andy.model.ProjectAgentProfile
import app.andy.model.effectiveSandboxMode
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
import app.andy.model.CONNECTION_STALL_RETRY_PROMPT
import app.andy.model.coalesceAcpTranscriptEvents
import app.andy.model.coalesceAgentStreamDeltas
import app.andy.model.hasRetriableConnectionStall
import app.andy.model.planTextFromAcpTranscript
import app.andy.model.followUpCliPayload
import app.andy.model.followUpPromptForLiveTerminal
import app.andy.model.promptForCli
import app.andy.model.providerDefaults
import app.andy.desktop.service.DesktopWorkspaceStore
import app.andy.desktop.service.McpClientConfig
import app.andy.desktop.service.agents.acp.AgentAcpManager
import app.andy.desktop.service.agents.acp.AcpRegistry
import app.andy.desktop.service.agents.acp.AcpEventMapper
import app.andy.desktop.service.agents.acp.AcpTranscriptStore
import app.andy.desktop.service.agents.acp.AcpReplayFilterResult
import app.andy.desktop.service.agents.acp.filterAcpProviderHistoryReplay
import app.andy.desktop.service.agents.acp.AcpSlashCommandProbe
import app.andy.desktop.service.agents.acp.NodeRuntimeLocator
import app.andy.desktop.service.agents.acp.PendingAcpPermission
import app.andy.model.TerminalAppearanceSnapshot
import app.andy.model.toTerminalAppearance
import app.andy.terminal.TmuxAndy
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

/** Terminal-lane transcript cap; ACP transcripts are coalesced and bounded by disk (8 MB). */
private const val MAX_TERMINAL_EVENTS_IN_MEMORY = 50_000
private const val PROVIDER_QUOTA_REFRESH_MILLIS = 5 * 60 * 1000L
/** Review/verify agents can look idle on screen before the JSON artifact lands on disk. */
private const val WORKFLOW_ARTIFACT_WAIT_MS = 3 * 60 * 1000L
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
    terminalMode: AgentTerminalMode = AgentTerminalManager.defaultMode(),
    artifactPollIntervalMs: Long = AgentWorkflowArtifacts.DEFAULT_POLL_INTERVAL_MS,
    /**
     * False for the GUI attach bridge in daemon-client mode — that process must not
     * kill `tmux -L andy` sessions owned by a running `andyd`.
     */
    private val ownsAgentSessions: Boolean = true,
    /** Managed evidence bundle root (§4), mirroring [app.andy.desktop.service.DesktopInvestigationEvidenceService]. */
    private val evidenceRootDir: File = File(System.getProperty("user.home"), ".andy/evidence"),
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
        scrollbackFile = ::resolvedScrollbackFile,
        mode = terminalMode,
        artifactPollIntervalMs = artifactPollIntervalMs,
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
    private data class KnownSkillScope(val directory: String?)
    private data class SlashCommandScope(val agent: AgentKind, val directory: String?)

    private val skillFlows = ConcurrentHashMap<SkillScope, MutableStateFlow<List<AgentSkill>>>()
    private val loadedSkillScopes = ConcurrentHashMap.newKeySet<SkillScope>()
    private val knownSkillNameFlows = ConcurrentHashMap<KnownSkillScope, MutableStateFlow<Set<String>>>()
    private val loadedKnownSkillScopes = ConcurrentHashMap.newKeySet<KnownSkillScope>()
    private val slashCommandFlows = ConcurrentHashMap<SlashCommandScope, MutableStateFlow<List<AgentSlashCommand>>>()
    private val loadedSlashCommandScopes = ConcurrentHashMap.newKeySet<SlashCommandScope>()
    private val slashCommandRefreshJobs = ConcurrentHashMap<SlashCommandScope, Job>()

    private val handles = ConcurrentHashMap<String, TaskHandle>()
    private val acpArtifactJobs = ConcurrentHashMap<String, Job>()
    private val viewingTaskIds = ConcurrentHashMap.newKeySet<String>()
    private val queuedAcpPermissions = ConcurrentHashMap<String, ArrayDeque<PendingAcpPermission>>()
    /** While reconnecting an ACP session, drop provider history replay into the transcript. */
    private val acpSuppressProviderReplay = ConcurrentHashMap.newKeySet<String>()
    private val acpProviderReplayScratch = ConcurrentHashMap<String, StringBuilder>()
    /** One automatic resume per task turn after a provider connection stall. */
    private val connectionStallAutoRetries = ConcurrentHashMap<String, Int>()

    /**
     * Window visibility/focus, pushed by the GUI. Terminal foreground cadence deliberately
     * ignores this — scraping must stay fast while the user is in another app, otherwise
     * the finished turn we want to notify about is detected late.
     */
    @Volatile
    private var appForeground: Boolean = true

    private val previousTaskStatuses = ConcurrentHashMap<String, AgentStatus?>()
    private val eventFlows = ConcurrentHashMap<String, MutableStateFlow<List<AgentEvent>>>()
    private val emptyEvents = MutableStateFlow<List<AgentEvent>>(emptyList())
    private val acpTranscriptStore = AcpTranscriptStore(::resolvedTranscriptFile)
    private val acpManager = AgentAcpManager(
        scope = scope,
        binaryFor = ::binaryFor,
        onEvent = ::appendAcpEvent,
        onStatus = ::applyStatusSnapshot,
        onPermission = ::handleAcpPermission,
        onPermissionResolved = ::handleAcpPermissionResolved,
        onSessionId = ::persistAcpSessionId,
        onDiagnosticsLine = { taskId, line -> appendLaunchDiagnostics(taskId, line) },
    )

    private val persistMutex = Mutex()
    private val mcpMutex = Mutex()
    private val quotaRefreshMutex = Mutex()
    private val quotaProbe = ProviderQuotaProbe()
    private val modelProbe = ProviderModelProbe()
    private val slashCommandProbe = AcpSlashCommandProbe()
    private val ready = CompletableDeferred<Unit>()
    private var binaryOverrides: Map<String, String> = emptyMap()
    private lateinit var slots: Semaphore

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            // Best-effort: never let a shutdown-time persist failure (e.g. the store
            // already torn down) throw uncaught out of the shutdown thread.
            shutdownForProcessExit()
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
            backfillPlanModeCompletedText()
            repairCompletedSpecWorkflowStates()
            reconcileStuckWorkflowArtifacts()
            ready.complete(Unit)
            refreshCliStatuses()
            refreshSlashCommandsForReadyProviders()
            if (enableProbes) {
                refreshProviderQuotas()
                while (isActive) {
                    delay(PROVIDER_QUOTA_REFRESH_MILLIS)
                    refreshProviderQuotas()
                }
            }
        }
    }

    internal fun terminalView(taskId: String) = terminals.terminalView(taskId)

    /** Push latest Settings terminal appearance into live agent sessions. */
    internal fun reloadTerminalAppearance() = terminals.reloadAppearance()

    /** Observed by [app.andy.ui.agents.AgentTerminalSurface] so the terminal mounts when the PTY appears. */
    internal val terminalSessionsRevision: StateFlow<Long> get() = terminals.sessionsRevision

    override val attachedTerminalTaskIds: StateFlow<Set<String>> get() = terminals.attachedTaskIds

    /** Cumulative scrollback file for finished-chat replay (may not exist yet). */
    internal fun scrollbackFile(taskId: String): File = resolvedScrollbackFile(taskId)

    private fun resolvedContentDir(taskId: String): File =
        store.resolvedContentDirBlocking(
            taskId,
            compressed = currentTask(taskId)?.transcriptCompressed == true || store.archiveFile(taskId).isFile,
        )

    private fun resolvedScrollbackFile(taskId: String): File =
        File(resolvedContentDir(taskId), "scrollback.ansi")

    private fun resolvedTranscriptFile(taskId: String): File =
        File(resolvedContentDir(taskId), "transcript.jsonl")

    internal suspend fun awaitReady() {
        ready.await()
    }

    /**
     * Copies [bundleIds] from the managed evidence root into this task's local evidence
     * directory (so they survive even if the shared managed bundle is later removed) and
     * returns prompt text pointing the agent at the copied paths, or "" when [bundleIds] is
     * empty or none resolve. Blocking file I/O — always call from [Dispatchers.IO].
     */
    private fun materializeTaskEvidence(taskId: String, bundleIds: List<String>): String {
        if (bundleIds.isEmpty()) return ""
        val bundles = AgentEvidenceMaterializer.copyBundles(
            managedRootDir = evidenceRootDir,
            bundleIds = bundleIds,
            taskEvidenceDir = store.taskEvidenceDir(taskId),
        )
        return AgentEvidenceMaterializer.promptSuffix(bundles)
    }

    internal fun hasScrollback(taskId: String): Boolean = terminals.hasScrollback(taskId)

    /**
     * Read-only BossTerm scrollback viewer for ended chats. Caller owns dispose.
     * Does not restart the provider CLI — use [reattachSession] / [resume] for that.
     */
    internal fun openScrollbackReplay(taskId: String) =
        terminals.openScrollbackReplay(taskId)

    /** True while the embedded PTY or underlying tmux session is still running for [taskId]. */
    override fun isTerminalLive(taskId: String): Boolean = terminals.isAlive(taskId)

    override fun isLaneLive(taskId: String): Boolean = currentTask(taskId)?.let { task ->
        if (task.lane == AgentLaneKind.Acp) acpManager.isAlive(taskId) else terminals.isAlive(taskId)
    } ?: false

    override val interactiveTerminalTaskIds: StateFlow<Set<String>> get() = terminals.interactiveTaskIds

    override fun isViewing(taskId: String): Boolean = appForeground && taskId in viewingTaskIds

    /** True while the chat is mounted in the UI, focused or not. */
    internal fun isChatOpen(taskId: String): Boolean = taskId in viewingTaskIds

    /** True while the local BossTerm/PTY viewer attached to tmux is still running. */
    internal fun isViewerAlive(taskId: String): Boolean = terminals.isViewerAlive(taskId)

    /**
     * Reattach a BossTerm viewer to a live tmux session (GUI reopen while daemon/agent continues).
     */
    internal suspend fun attachTerminalIfNeeded(taskId: String) {
        if (terminals.terminalView(taskId) != null) return
        val task = currentTask(taskId)
        val preferredStatus = task?.status?.let { status ->
            AgentStatusSnapshot(status, confident = task.statusConfident)
        }
        terminals.attachExisting(
            taskId = taskId,
            agent = task?.agent ?: AgentKind.ClaudeCode,
            cwd = task?.cwd,
            preferredStatus = preferredStatus,
            onStatusSnapshot = { snapshot -> applyStatusSnapshot(taskId, snapshot) },
        )
    }

    /** Compose unmount: release the local viewer without killing tmux / the agent CLI. */
    override fun releaseTerminalViewer(taskId: String) = terminals.releaseViewerOnly(taskId)

    /**
     * Repairs tasks left [AgentStatus.Working]/[null] after the PTY
     * exited without a clean [finishTask] (e.g. crash). Safe to call when opening history.
     * Does not mark stale while a `tmux -L andy` session for the task still exists,
     * except when [AgentTask.finishedAtMillis] contradicts a lingering Working badge.
     */
    internal fun reconcileStaleActiveTaskIfNeeded(taskId: String) {
        // History repair is tied to the chat on screen. A late reconcile after the user
        // clicked away must not re-badge chats they already read.
        if (taskId !in viewingTaskIds) return
        val task = currentTask(taskId) ?: return
        val viewing = true
        val recovered = when {
            task.finishedAtMillis != null && task.status == AgentStatus.Working ->
                recoverInterruptedTaskStatus(task, resolvedScrollbackFile(taskId))
            !task.isActive && task.status != AgentStatus.Blocked -> return
            handles[taskId]?.job?.isActive == true || terminals.isAlive(taskId) -> return
            else -> recoverInterruptedTaskStatus(task, resolvedScrollbackFile(taskId))
        }
        if (recovered == task) return
        val previousStatus = task.status
        updateTask(taskId) { recovered }
        if (statusNeedsUnread(
                task = task,
                previous = previousStatus,
                next = recovered.status,
                viewing = viewing,
                terminalLive = terminals.isAlive(taskId),
            ) && task.unread
        ) {
            markUnread(taskId)
        }
        scope.launch { persist() }
    }

    override fun skills(agent: AgentKind, directory: String?): StateFlow<List<AgentSkill>> {
        val normalizedDirectory = directory
            ?.takeIf { it.isNotBlank() }
            ?.let { path -> runCatching { File(path).canonicalPath }.getOrElse { path } }
        val skillScope = SkillScope(agent, normalizedDirectory)
        val flow = skillFlows.computeIfAbsent(skillScope) { MutableStateFlow(emptyList()) }
        if (loadedSkillScopes.add(skillScope)) {
            scope.launch(Dispatchers.IO) {
                flow.value = discoverAgentSkills(agent, normalizedDirectory)
            }
        }
        return flow
    }

    override fun knownSkillNames(directory: String?): StateFlow<Set<String>> {
        val normalizedDirectory = directory
            ?.takeIf { it.isNotBlank() }
            ?.let { path -> runCatching { File(path).canonicalPath }.getOrElse { path } }
        val skillScope = KnownSkillScope(normalizedDirectory)
        val flow = knownSkillNameFlows.computeIfAbsent(skillScope) { MutableStateFlow(emptySet()) }
        if (loadedKnownSkillScopes.add(skillScope)) {
            scope.launch(Dispatchers.IO) {
                flow.value = discoverKnownAgentSkillNames(normalizedDirectory)
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
            flow.value = discoverAgentSkills(agent, normalizedDirectory)
            knownSkillNameFlows[KnownSkillScope(normalizedDirectory)]?.value =
                discoverKnownAgentSkillNames(normalizedDirectory)
        }
    }

    override fun slashCommands(agent: AgentKind, directory: String?): StateFlow<List<AgentSlashCommand>> {
        val normalizedDirectory = normalizeSkillDirectory(directory)
        val commandScope = SlashCommandScope(agent, normalizedDirectory)
        val flow = slashCommandFlows.computeIfAbsent(commandScope) { MutableStateFlow(emptyList()) }
        if (loadedSlashCommandScopes.add(commandScope)) {
            refreshSlashCommands(agent, directory)
        }
        return flow
    }

    override fun refreshSlashCommands(agent: AgentKind, directory: String?) {
        val normalizedDirectory = normalizeSkillDirectory(directory)
        val commandScope = SlashCommandScope(agent, normalizedDirectory)
        val flow = slashCommandFlows.computeIfAbsent(commandScope) { MutableStateFlow(emptyList()) }
        loadedSlashCommandScopes.add(commandScope)
        slashCommandRefreshJobs[commandScope]?.cancel()
        slashCommandRefreshJobs[commandScope] = scope.launch(Dispatchers.IO) {
            // Seed from prior ACP transcripts before probing so the new-chat composer is
            // not empty while a slow provider probe runs (or times out).
            bootstrapSlashCommands(agent, normalizedDirectory)?.let { cached ->
                if (flow.value.isEmpty() || cached.size >= flow.value.size) {
                    flow.value = cached
                }
            }
            if (!agent.acpSupported) return@launch
            val status = _cliStatuses.value.firstOrNull { it.kind == agent } ?: return@launch
            if (!status.acpReady) return@launch
            val cwd = normalizedDirectory?.let(::File)?.takeIf { it.isDirectory }
                ?: File(System.getProperty("user.home"))
            val probed = slashCommandProbe.probe(
                agent = agent,
                cwd = cwd,
                binary = status.binaryPath,
                env = buildAgentLaunchEnvironment(emptyMap()),
            )
            if (probed.isNotEmpty()) {
                flow.value = probed
            }
        }
    }

    private fun refreshSlashCommandsForReadyProviders() {
        AgentKind.entries.filter { it.acpSupported }.forEach { agent ->
            refreshSlashCommands(agent, directory = null)
        }
    }

    private fun normalizeSkillDirectory(directory: String?): String? =
        directory
            ?.takeIf { it.isNotBlank() }
            ?.let { path -> runCatching { File(path).canonicalPath }.getOrElse { path } }

    private fun bootstrapSlashCommands(agent: AgentKind, directory: String?): List<AgentSlashCommand>? {
        val scoped = latestSlashCommandsFromTranscripts(agent, directory)
        if (scoped != null) return scoped
        if (directory != null) return latestSlashCommandsFromTranscripts(agent, directory = null)
        return null
    }

    private fun latestSlashCommandsFromTranscripts(agent: AgentKind, directory: String?): List<AgentSlashCommand>? =
        _tasks.value
            .asSequence()
            .filter { task ->
                task.agent == agent &&
                    task.lane == AgentLaneKind.Acp &&
                    !task.archived &&
                    (directory == null ||
                        normalizeSkillDirectory(task.cwd) == directory ||
                        normalizeSkillDirectory(task.worktreePath) == directory)
            }
            .sortedByDescending { it.createdAtMillis }
            .mapNotNull { task ->
                loadAcpEventsForDisplay(task.id)
                    .asReversed()
                    .filterIsInstance<AgentEvent.AvailableCommands>()
                    .firstOrNull()
                    ?.commands
                    ?.takeIf { it.isNotEmpty() }
            }
            .firstOrNull()

    private fun recordSlashCommands(agent: AgentKind, directory: String?, commands: List<AgentSlashCommand>) {
        if (commands.isEmpty()) return
        val normalizedDirectory = normalizeSkillDirectory(directory)
        slashCommandFlows.computeIfAbsent(SlashCommandScope(agent, normalizedDirectory)) {
            MutableStateFlow(emptyList())
        }.value = commands
        if (normalizedDirectory != null) {
            slashCommandFlows.computeIfAbsent(SlashCommandScope(agent, directory = null)) {
                MutableStateFlow(emptyList())
            }.value = commands
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
        val installedSkills = withContext(Dispatchers.IO) { discoverAgentSkills(spec.profile.agent, directory) }
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
                planMode = spec.profile.effectiveSandboxMode() == AgentSandboxMode.ReadOnly,
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
                    run.isActive || run.status == AgentStatus.Blocked
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
            build.reviewEnabled && currentReviewApproval(review, buildRun.id, build.reviewGeneration) == null -> {
                if (!reconcilePendingReviewArtifact(build, review)) {
                    startReviewAttempt(buildTaskId)
                }
            }
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
            withContext(Dispatchers.IO) { discoverAgentSkills(draft.agent, draft.existingWorktreePath ?: draft.directory) }
                .mapTo(mutableSetOf()) { it.path }
        }
        val now = System.currentTimeMillis()
        val id = taskId ?: newAgentTaskId()
        val resolvedCwd = withContext(Dispatchers.IO) {
            AgentScratchWorkspace.resolveCwd(draft.existingWorktreePath ?: draft.directory)
        }
        var task = AgentTask(
            id = id,
            title = draft.title.ifBlank { draft.fallbackTitle().truncateForSummary(60) },
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
            confirmToolCalls = draft.confirmToolCalls,
            model = draft.model,
            reasoningEffort = draft.reasoningEffort,
            fastMode = draft.fastMode,
            openClawNewSession = draft.openClawNewSession,
            imagePaths = draft.imagePaths,
            skills = draft.skills.filter { it.path in discoveredSkillPaths },
            goal = draft.goal,
            maxBudgetUsd = draft.maxBudgetUsd,
            contextBundleIds = draft.contextBundleIds,
            provenance = draft.provenance,
            status = null,
            vendorSessionId = null,
            lane = draft.lane ?: resolveLane(draft.agent),
            createdAtMillis = now,
        )

        val binary = binaryFor(task.agent)
        if (binary == null && task.lane == AgentLaneKind.Terminal) {
            task = task.copy(
                status = AgentStatus.Error,
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
                    status = AgentStatus.Error,
                    errorMessage = "a project directory is required to create a worktree",
                    finishedAtMillis = System.currentTimeMillis(),
                )
                upsertTask(task)
                persist()
                return task
            }
            val baseTaskId = draft.baseWorktreeTaskId
            val baseTask = baseTaskId?.let { id -> tasks.value.find { t -> t.id == id } }
            val baseWorktreeAlive = baseTask != null &&
                baseTask.branchName != null &&
                baseTask.worktreePath != null &&
                withContext(Dispatchers.IO) { worktrees.isLiveWorktree(originDir, baseTask.worktreePath) }
            if (baseTaskId != null && !baseWorktreeAlive) {
                task = task.copy(
                    status = AgentStatus.Error,
                    errorMessage = "base worktree no longer exists",
                    finishedAtMillis = System.currentTimeMillis(),
                )
                upsertTask(task)
                persist()
                return task
            }
            val created = withContext(Dispatchers.IO) {
                worktrees.create(originDir, task.id, task.agent, task.title, startPoint = baseTask?.branchName)
            }
            task = created.fold(
                onSuccess = {
                    task.copy(
                        cwd = it.path,
                        worktreePath = it.path,
                        branchName = it.branch,
                        ownsWorktree = true,
                        parentWorktreeTaskId = baseTaskId,
                    )
                },
                onFailure = {
                    task.copy(
                        status = AgentStatus.Error,
                        errorMessage = "worktree creation failed: ${it.message}",
                        finishedAtMillis = System.currentTimeMillis(),
                    )
                },
            )
            if (task.status == AgentStatus.Error) {
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

        val adapter = adapters.getValue(task.agent)
        if (task.contextBundleIds.isNotEmpty()) {
            val evidenceSuffix = withContext(Dispatchers.IO) { materializeTaskEvidence(task.id, task.contextBundleIds) }
            if (evidenceSuffix.isNotBlank()) {
                task = task.copy(evidenceLocalPathsHint = evidenceSuffix)
            }
        }
        upsertTask(task)
        persist()
        val initialPrompt = task.promptForCli().takeIf { it.isNotBlank() }
        // Prefer argv/flag delivery when the CLI supports it (agy --prompt-interactive,
        // claude/codex/cursor positional). PTY typing is a fragile fallback.
        val writeAfterStart = initialPrompt.takeUnless { adapter.embedsInitialPrompt }
        // Do not await the PTY on the caller's dispatcher (often Main). BossTerm
        // initializes on the Compose path — awaiting here can stall the UI thread
        // and leave the UI stuck on "Starting terminal…" even after the session is Idle.
        launchRun(task, writeAfterStart = writeAfterStart) { nextAdapter, resolvedBinary, mcpUrl ->
            nextAdapter.buildInteractiveCommand(resolvedBinary, currentTask(task.id) ?: task, mcpUrl)
        }
        return task
    }

    private fun isLaunchInProgress(taskId: String): Boolean =
        handles[taskId]?.job?.isActive == true

    override fun resume(
        taskId: String,
        followUp: String,
        imagePaths: List<String>,
        skills: List<AgentSkill>,
        contextBundleIds: List<String>,
        provenance: AgentContextualProvenance?,
    ) {
        val existing = currentTask(taskId) ?: return
        if (existing.userInputRequest != null) return
        // Keep the chat's original provenance; a contextual follow-up only fills an empty one.
        val task = if (provenance != null && existing.provenance == null) {
            existing.copy(provenance = provenance)
        } else {
            existing
        }

        _lastUsedAgent.value = task.agent

        val skillDirectory = task.worktreePath ?: task.cwd
        val selectedSkills = skills.filter { skill ->
            this.skills(task.agent, skillDirectory).value.any { it.path == skill.path }
        }
        if (task.lane == AgentLaneKind.Acp) {
            val acpFollowUp = task.followUpCliPayload(followUp, imagePaths, selectedSkills).prompt
            appendEvents(taskId, listOf(AgentEvent.UserMessage(System.currentTimeMillis(), acpFollowUp, selectedSkills, imagePaths)))
            updateTask(taskId) {
                it.copy(
                    status = AgentStatus.Working,
                    exitCode = null,
                    errorMessage = null,
                    finishedAtMillis = null,
                    unread = false,
                    latestPrompt = acpFollowUp,
                )
            }
            scope.launch(Dispatchers.IO) {
                val success = runAcpFollowUp(taskId, acpFollowUp, imagePaths)
                if (!success && currentTask(taskId)?.lane == AgentLaneKind.Acp) {
                    updateTask(taskId) { it.copy(lane = AgentLaneKind.Terminal, status = null, finishedAtMillis = null) }
                    persist()
                    resume(taskId, followUp, imagePaths, selectedSkills, contextBundleIds, provenance)
                }
            }
            return
        }

        val adapter = adapters[existing.agent] ?: return

        val followUpCli = task.followUpCliPayload(followUp, imagePaths, selectedSkills)
        val followUpForCli = followUpCli.prompt
        val followUpImagePathsForCli = followUpCli.imagePaths

        if (terminals.isAlive(taskId)) {
            val now = System.currentTimeMillis()
            appendEvents(taskId, listOf(AgentEvent.UserMessage(now, followUp, selectedSkills, imagePaths)))
            updateTask(taskId) {
                it.copy(
                    status = AgentStatus.Working,
                    exitCode = null,
                    errorMessage = null,
                    finishedAtMillis = null,
                    unread = false,
                )
            }
            val liveText = task.followUpPromptForLiveTerminal(followUp, imagePaths, selectedSkills)
            scope.launch {
                // A tmux session can outlive the app that spawned it. Mount a viewer before
                // typing so a chat resumed from read-only replay comes back interactive.
                attachTerminalIfNeeded(taskId)
                val evidenceSuffix = withContext(Dispatchers.IO) { materializeTaskEvidence(taskId, contextBundleIds) }
                terminals.write(taskId, liveText + evidenceSuffix)
                persist()
            }
            return
        }

        if (task.isActive && isLaunchInProgress(taskId)) return
        // Working without a live session means a failed reattach/resume — relaunch below.
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
        val resolvedCwd = AgentScratchWorkspace.resolveCwd(taskForResume.cwd)
        val queued = taskForResume.copy(
            cwd = resolvedCwd,
            latestPrompt = followUp.trim().ifBlank { taskForResume.latestPrompt },
            status = null,
            exitCode = null,
            errorMessage = null,
            finishedAtMillis = null,
            unread = false,
        )
        upsertTask(queued)
        scope.launch {
            persist()
            val evidenceSuffix = withContext(Dispatchers.IO) { materializeTaskEvidence(taskId, contextBundleIds) }
            val enrichedFollowUp = followUpForCli + evidenceSuffix
            if (resumeArgv == null) {
                // Provider cannot resume (missing vendor session). Start a fresh
                // interactive session that still includes the original Andy prompt.
                val seeded = composeResumePrompt(
                    originalPrompt = queued.promptForCli(),
                    followUp = enrichedFollowUp,
                    boundToConversation = false,
                ) ?: enrichedFollowUp
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
            val writeAfterStart = enrichedFollowUp.takeUnless { adapter.embedsResumePrompt }
            launchRunAwaitingTerminal(queued, writeAfterStart = writeAfterStart) { resumeAdapter, binary, mcpUrl ->
                resumeAdapter.buildInteractiveResumeCommand(
                    binary,
                    currentTask(taskId) ?: queued,
                    mcpUrl,
                    enrichedFollowUp,
                    followUpImagePathsForCli,
                ) ?: error("interactive resume not supported")
            }
        }
    }

    override fun canReattachSession(taskId: String): Boolean {
        val task = currentTask(taskId) ?: return false
        if (task.userInputRequest != null) return false
        if (task.lane == AgentLaneKind.Acp) {
            return task.resumable && task.acpSessionId?.isNotBlank() == true && !task.isActive && !acpManager.isAlive(taskId)
        }
        val broken = TmuxAndy.isAvailable() && TmuxAndy.sessionLooksBroken(taskId)
        if (!broken && (task.isActive || terminals.isAlive(taskId))) return false
        return resumeTaskForReattach(task) != null
    }

    override fun reattachSession(taskId: String) {
        val task = currentTask(taskId) ?: return
        if (task.userInputRequest != null) return
        if (task.lane == AgentLaneKind.Acp) {
            if (!canReattachSession(taskId)) return
            scope.launch(Dispatchers.IO) {
                val projectEnv = task.projectId?.let { projectId ->
                    runCatching { actionConfig.load().projects.firstOrNull { it.id == projectId }?.env }.getOrNull()
                }.orEmpty()
                val endpoint = if (task.attachAndyMcp) prepareAcpMcp() else null
                val started = runCatching {
                    acpManager.start(task, buildAgentLaunchEnvironment(projectEnv), endpoint) { snapshot ->
                        applyStatusSnapshot(taskId, snapshot)
                    }
                }.isSuccess
                if (started) {
                    acpManager.artifacts(taskId)?.let { ensureAcpArtifactMonitor(taskId, it) }
                    updateTask(taskId) {
                        it.copy(status = AgentStatus.Done, resumable = true, statusConfident = true, unread = false)
                    }
                    persist()
                }
            }
            return
        }
        val broken = TmuxAndy.isAvailable() && TmuxAndy.sessionLooksBroken(taskId)
        if (!broken && (task.isActive || terminals.isAlive(taskId))) return
        if (broken) {
            handles.remove(taskId)?.job?.cancel()
            terminals.stop(taskId)
        }
        val taskForResume = resumeTaskForReattach(task) ?: return
        val adapter = adapters[task.agent] ?: return
        runCatching {
            adapter.buildInteractiveResumeCommand(
                binaryFor(task.agent) ?: return,
                taskForResume,
                null,
                followUp = null,
                followUpImagePaths = emptyList(),
            )
        }.getOrNull() ?: return

        val queued = taskForResume.copy(
            cwd = AgentScratchWorkspace.resolveCwd(taskForResume.cwd),
            status = null,
            exitCode = null,
            errorMessage = null,
            finishedAtMillis = null,
            unread = false,
        )
        val rollbackSnapshot = taskForResume
        upsertTask(queued)
        scope.launch {
            persist()
            val terminalReady = launchRun(queued, writeAfterStart = null) { resumeAdapter, binary, mcpUrl ->
                resumeAdapter.buildInteractiveResumeCommand(
                    binary,
                    currentTask(taskId) ?: queued,
                    mcpUrl,
                    followUp = null,
                    followUpImagePaths = emptyList(),
                ) ?: error("interactive resume not supported")
            }
            val launched = withTimeoutOrNull(20_000) { terminalReady.await() } == true
            if (!launched && !terminals.isAlive(taskId)) {
                upsertTask(
                    rollbackSnapshot.copy(
                        status = AgentStatus.Done,
                        resumable = true,
                        finishedAtMillis = rollbackSnapshot.finishedAtMillis ?: System.currentTimeMillis(),
                    ),
                )
                persist()
            }
        }
    }

    private fun resumeTaskForReattach(task: AgentTask): AgentTask? {
        val taskForResume = enrichTaskWithVendorSession(task) ?: return null
        val adapter = adapters[taskForResume.agent] ?: return null
        val binary = binaryFor(taskForResume.agent) ?: return null
        return runCatching {
            adapter.buildInteractiveResumeCommand(
                binary,
                taskForResume,
                null,
                followUp = null,
                followUpImagePaths = emptyList(),
            )
        }.getOrNull()?.let { taskForResume }
    }

    /**
     * Resolve a provider session id from Andy's store or the vendor's on-disk cache.
     * Persists newly discovered ids so CLI/GUI reattach works after tmux is torn down.
     */
    private fun enrichTaskWithVendorSession(task: AgentTask): AgentTask? {
        val resolvedId = when (task.agent) {
            AgentKind.Antigravity -> AntigravityConversationIds.resolveForTask(task)
            AgentKind.ClaudeCode -> ClaudeSessionIds.resolveForTask(task)
            AgentKind.Codex -> CodexSessionIds.resolveForTask(task)
            AgentKind.Hermes -> HermesSessionIds.resolveForTask(task)
            AgentKind.OpenClaw -> OpenClawSessionIds.resolveForTask(task)
            else -> task.vendorSessionId?.takeIf { it.isNotBlank() }
        } ?: return null
        val enriched = if (resolvedId != task.vendorSessionId) {
            task.copy(vendorSessionId = resolvedId)
        } else {
            task
        }
        if (enriched != task) {
            upsertTask(enriched)
            scope.launch { persist() }
        }
        return enriched
    }

    override fun respondToUserInput(taskId: String, requestId: String, answers: Map<String, String>) {
        val task = currentTask(taskId) ?: return
        val request = task.userInputRequest?.takeIf { it.id == requestId } ?: return
        val normalizedAnswers = request.questions.associate { question ->
            question.id to answers[question.id].orEmpty().trim()
        }
        if (normalizedAnswers.values.any { it.isBlank() }) return

        if (request.origin == app.andy.model.AgentUserInputOrigin.AcpPermission) {
            val answer = normalizedAnswers.values.firstOrNull().orEmpty()
            if (!acpManager.respondPermission(taskId, requestId, answer)) return
            appendEvents(taskId, listOf(AgentEvent.UserMessage(System.currentTimeMillis(), answer)))
            return
        }

        if (task.status != AgentStatus.Blocked) return

        val response = request.responseForAgent(normalizedAnswers)
        val now = System.currentTimeMillis()
        appendEvents(taskId, listOf(AgentEvent.UserMessage(now, response)))

        terminals.get(taskId)?.artifacts?.writeAnswer(response)
        acpManager.artifacts(taskId)?.writeAnswer(response)

        if (task.lane == AgentLaneKind.Acp) {
            updateTask(taskId) {
                it.copy(status = AgentStatus.Working, userInputRequest = null, finishedAtMillis = null, unread = false)
            }
            scope.launch(Dispatchers.IO) {
                runAcpFollowUp(taskId, response, emptyList())
            }
            return
        }

        if (terminals.isAlive(taskId)) {
            updateTask(taskId) {
                it.copy(
                    status = AgentStatus.Working,
                    userInputRequest = null,
                    exitCode = null,
                    errorMessage = null,
                    finishedAtMillis = null,
                    unread = false,
                )
            }
            scope.launch {
                attachTerminalIfNeeded(taskId)
                terminals.write(taskId, response)
                persist()
            }
            return
        }

        val next = task.copy(
            status = null,
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

    override fun setAcpSessionMode(taskId: String, modeId: String) {
        val task = currentTask(taskId) ?: return
        if (task.lane != AgentLaneKind.Acp) return
        val planMode = availableAcpModes(taskId).firstOrNull { it.id == modeId }?.looksLikePlanMode() == true
        if (task.planMode != planMode) {
            updateTask(taskId) { it.copy(planMode = planMode) }
            scope.launch { persist() }
        }
        scope.launch(Dispatchers.IO) { acpManager.setMode(taskId, modeId) }
    }

    override fun queueFollowUp(
        taskId: String,
        followUp: String,
        imagePaths: List<String>,
        skills: List<AgentSkill>,
        contextBundleIds: List<String>,
        provenance: AgentContextualProvenance?,
    ) {
        val task = currentTask(taskId) ?: return
        val preferQueue = agentMessageDeliveryMode() == AgentMessageDeliveryMode.Queue
        if (!task.isActive && !isLaneLive(taskId) && !preferQueue) return

        val text = followUp.trim()
        if (text.isBlank() && imagePaths.isEmpty()) return
        val skillDirectory = task.worktreePath ?: task.cwd
        val selectedSkills = skills.filter { skill ->
            this.skills(task.agent, skillDirectory).value.any { it.path == skill.path }
        }

        if (!preferQueue) {
            if (task.lane == AgentLaneKind.Acp && acpManager.isAlive(taskId)) {
                val now = System.currentTimeMillis()
                val acpPrompt = task.followUpCliPayload(text, imagePaths, selectedSkills).prompt
                appendEvents(taskId, listOf(AgentEvent.UserMessage(now, acpPrompt, selectedSkills, imagePaths)))
                updateTask(taskId) { current ->
                    current.copy(
                        status = AgentStatus.Working,
                        finishedAtMillis = null,
                        latestPrompt = acpPrompt,
                        unread = false,
                    )
                }
                scope.launch(Dispatchers.IO) { runAcpFollowUp(taskId, acpPrompt, imagePaths) }
                return
            }

            if (terminals.isAlive(taskId)) {
                val now = System.currentTimeMillis()
                appendEvents(taskId, listOf(AgentEvent.UserMessage(now, text, selectedSkills, imagePaths)))
                updateTask(taskId) { current -> current.copy(latestPrompt = text.ifBlank { current.latestPrompt }) }
                val liveText = task.followUpPromptForLiveTerminal(text, imagePaths, selectedSkills)
                scope.launch {
                    val evidenceSuffix = withContext(Dispatchers.IO) { materializeTaskEvidence(taskId, contextBundleIds) }
                    terminals.write(taskId, liveText + evidenceSuffix)
                }
                return
            }

            if (task.isActive && !isLaunchInProgress(taskId)) {
                resume(taskId, text, imagePaths, selectedSkills, contextBundleIds, provenance)
                return
            }
        } else if (!task.isActive && !isLaunchInProgress(taskId) && task.queuedFollowUps.isEmpty()) {
            resume(taskId, text, imagePaths, selectedSkills, contextBundleIds, provenance)
            return
        }

        enqueueFollowUp(
            taskId = taskId,
            text = text,
            imagePaths = imagePaths,
            selectedSkills = selectedSkills,
            contextBundleIds = contextBundleIds,
            provenance = provenance,
        )
    }

    override fun sendNextQueuedFollowUp(taskId: String) {
        val task = currentTask(taskId) ?: return
        if (task.isActive || isLaunchInProgress(taskId)) return
        val next = task.queuedFollowUps.firstOrNull() ?: return
        updateTask(taskId) { current -> current.copy(queuedFollowUps = current.queuedFollowUps.drop(1)) }
        resume(
            taskId,
            next.text,
            next.imagePaths,
            next.skills,
            next.contextBundleIds,
            next.provenance,
        )
    }

    override fun removeQueuedFollowUp(taskId: String, queueIndex: Int) {
        val task = currentTask(taskId) ?: return
        if (queueIndex !in task.queuedFollowUps.indices) return
        updateTask(taskId) { current ->
            current.copy(queuedFollowUps = current.queuedFollowUps.filterIndexed { index, _ -> index != queueIndex })
        }
        scope.launch { persist() }
    }

    private fun enqueueFollowUp(
        taskId: String,
        text: String,
        imagePaths: List<String>,
        selectedSkills: List<AgentSkill>,
        contextBundleIds: List<String>,
        provenance: AgentContextualProvenance?,
    ) {
        updateTask(taskId) { current ->
            current.copy(
                latestPrompt = text.ifBlank { current.latestPrompt },
                provenance = current.provenance ?: provenance,
                queuedFollowUps = current.queuedFollowUps + AgentQueuedFollowUp(
                    text = text,
                    imagePaths = imagePaths,
                    skills = selectedSkills,
                    contextBundleIds = contextBundleIds,
                    provenance = provenance,
                ),
            )
        }
        // Copy evidence into task-local storage at queue time (not just at run time) so it
        // survives even if the shared managed bundle is deleted before this follow-up runs.
        scope.launch {
            if (contextBundleIds.isNotEmpty()) {
                withContext(Dispatchers.IO) { materializeTaskEvidence(taskId, contextBundleIds) }
            }
            persist()
        }
    }

    private fun agentMessageDeliveryMode(): AgentMessageDeliveryMode =
        when (val store = workspaceStore) {
            is DesktopWorkspaceStore -> store.state.value.agentMessageDeliveryMode
            else -> AgentMessageDeliveryMode.Immediate
        }

    override suspend fun retry(taskId: String) {
        ready.await()
        val task = currentTask(taskId) ?: return
        if (task.status != AgentStatus.Error && task.status != AgentStatus.Error) return

        _lastUsedAgent.value = task.agent

        // A retry is a fresh run of the same chat, rather than a provider-specific
        // resume. In particular, Claude needs a new caller-assigned session id.
        val retried = task.copy(
            status = null,
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
        connectionStallAutoRetries.remove(taskId)
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

    override fun updatePlanMode(taskId: String, planMode: Boolean) {
        val task = currentTask(taskId) ?: return
        if (task.planMode == planMode) return
        updateTask(taskId) { it.copy(planMode = planMode) }
        scope.launch { persist() }
        syncAcpSessionPlanMode(taskId, planMode)
    }

    private fun syncAcpSessionPlanMode(taskId: String, planMode: Boolean) {
        val task = currentTask(taskId) ?: return
        if (task.lane != AgentLaneKind.Acp) return
        val modes = availableAcpModes(taskId)
        if (modes.isEmpty()) return
        val targetMode = if (planMode) {
            modes.firstOrNull { it.looksLikePlanMode() } ?: return
        } else {
            modes.firstOrNull { !it.looksLikePlanMode() } ?: return
        }
        scope.launch(Dispatchers.IO) { acpManager.setMode(taskId, targetMode.id) }
    }

    private fun availableAcpModes(taskId: String): List<app.andy.model.AgentSessionMode> =
        loadAcpEventsForDisplay(taskId)
            .filterIsInstance<AgentEvent.AvailableModes>()
            .lastOrNull()
            ?.modes
            .orEmpty()

    private fun newAgentTaskId(): String = "task-" + UUID.randomUUID().toString().replace("-", "").take(10)

    /**
     * Starts the agent PTY in the background. When [awaitTerminal] is true, blocks
     * until the AndyTerminalView exists so the detail pane can mount it on first paint.
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
                try {
                    runProcess(task.id, handle, argvBuilder, writeAfterStart, onTerminalStarted = {
                        terminalReady.complete(true)
                    })
                } catch (error: Throwable) {
                    terminals.stop(task.id)
                    finishTask(
                        task.id,
                        AgentStatus.Error,
                        exitCode = null,
                        error = error.message ?: "agent run failed unexpectedly",
                    )
                }
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
        // Await only when not on the UI thread. createAndStart intentionally
        // skips this so Compose Main never blocks across BossTerm initialization.
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
        if (task.lane == AgentLaneKind.Acp) {
            runAcpProcess(taskId, handle, writeAfterStart, onTerminalStarted, argvBuilder)
            return
        }
        val adapter = adapters.getValue(task.agent)
        val binary = binaryFor(task.agent)
        if (binary == null) {
            finishTask(taskId, AgentStatus.Error, exitCode = null, error = unavailableCliMessage(task.agent))
            return
        }

        // Resolve cwd before argv / vendor preflight so Codex `-C`, Cursor create-chat,
        // and Antigravity conversation lookup all see a directory that still exists.
        val resolvedCwd = AgentScratchWorkspace.resolveCwd(task.cwd)
        val taskForLaunch = if (resolvedCwd != task.cwd) {
            updateTask(taskId) { it.copy(cwd = resolvedCwd) }
            persist()
            task.copy(cwd = resolvedCwd)
        } else {
            task
        }

        if (taskForLaunch.agent == AgentKind.Cursor) {
            ensureCursorVendorSession(taskId, binary, taskForLaunch.cwd)
        }

        val openClawModel = taskForLaunch.model?.takeIf { it.isNotBlank() }
        if (taskForLaunch.agent == AgentKind.OpenClaw && openClawModel != null) {
            val modelSet = runOpenClawModelPreflight(binary, openClawModel, taskForLaunch.cwd)
            if (!modelSet) {
                finishTask(taskId, AgentStatus.Error, exitCode = null, error = "OpenClaw could not select model ${taskForLaunch.model}")
                return
            }
        }

        val mcpUrl = if (taskForLaunch.attachAndyMcp) {
            runCatching {
                prepareMcp(taskForLaunch.agent, taskForLaunch.cwd?.let(::File))
            }.getOrElse { error ->
                finishTask(taskId, AgentStatus.Error, exitCode = null, error = "failed to prepare Andy MCP: ${error.message}")
                return
            }
        } else {
            null
        }
        val argv = runCatching { argvBuilder(adapter, binary, mcpUrl) }.getOrElse {
            finishTask(taskId, AgentStatus.Error, exitCode = null, error = it.message ?: "failed to build command")
            return
        }
        val projectEnv = taskForLaunch.projectId?.let { projectId ->
            runCatching { actionConfig.load().projects.firstOrNull { it.id == projectId }?.env }.getOrNull()
        }.orEmpty()
        val env = buildAgentLaunchEnvironment(projectEnv) + buildMap {
            if (mcpUrl != null && taskForLaunch.agent == AgentKind.Pi) {
                put(AndyPiExtensionInstaller.MCP_URL_ENV, mcpUrl)
            }
        }

        updateTask(taskId) { it.copy(status = AgentStatus.Working, startedAtMillis = System.currentTimeMillis()) }
        persist()
        reconcileWorkflowRun(taskId)

        if (handle.stopRequested) {
            finishTask(taskId, AgentStatus.Done, exitCode = null, error = null, stoppedByUser = true)
            return
        }

        val launchTask = currentTask(taskId) ?: taskForLaunch
        val agyBeforeConversationId = if (launchTask.agent == AgentKind.Antigravity) {
            AntigravityConversationIds.lastForWorkspace(launchTask.cwd)
        } else {
            null
        }
        val agyLaunchStartedAt = System.currentTimeMillis()
        val agyLaunchedPrompt = if (launchTask.agent == AgentKind.Antigravity) {
            promptFromInteractiveArgv(argv)
        } else {
            null
        }
        val openCodeBeforeSessionId = if (launchTask.agent == AgentKind.OpenCode) {
            launchTask.vendorSessionId
                ?: OpenCodeSessionIds.findNewestSession(binary, launchTask.cwd)
        } else {
            null
        }
        val piBeforeSessionId = if (launchTask.agent == AgentKind.Pi) {
            launchTask.vendorSessionId
                ?: PiSessionIds.findNewestSession(launchTask.cwd)
        } else {
            null
        }
        val claudeBeforeSessionId = if (launchTask.agent == AgentKind.ClaudeCode) {
            launchTask.vendorSessionId
                ?: ClaudeSessionIds.findNewestSession(launchTask.cwd)
        } else {
            null
        }
        val codexBeforeSessionId = if (launchTask.agent == AgentKind.Codex) {
            launchTask.vendorSessionId
                ?: CodexSessionIds.findNewestSession(launchTask.cwd)
        } else {
            null
        }
        val hermesBeforeSessionId = if (launchTask.agent == AgentKind.Hermes) {
            launchTask.vendorSessionId
                ?: HermesSessionIds.findNewestSession(binary, launchTask.cwd)
        } else {
            null
        }
        val openClawBeforeSessionId = if (launchTask.agent == AgentKind.OpenClaw) {
            launchTask.vendorSessionId
                ?: OpenClawSessionIds.findNewestSession(binary, launchTask.cwd)
        } else {
            null
        }
        val sessionCaptureStartedAt = System.currentTimeMillis()
        val trailingPrompt = promptFromArgv(argv, binary)

        val terminalHandle = runCatching {
            terminals.start(
                task = launchTask,
                argv = argv,
                env = env,
                onStatusSnapshot = { snapshot -> applyStatusSnapshot(taskId, snapshot) },
            )
        }.getOrElse { error ->
            finishTask(taskId, AgentStatus.Error, exitCode = null, error = "failed to start: ${error.message}")
            return
        }
        writeLaunchDiagnostics(taskId, binary, argv, projectEnv)
        onTerminalStarted()

        if (launchTask.agent == AgentKind.Antigravity) {
            scope.launch(Dispatchers.IO) {
                captureAntigravityConversationId(
                    taskId = taskId,
                    cwd = launchTask.cwd,
                    before = agyBeforeConversationId,
                    launchedPrompt = agyLaunchedPrompt,
                    startedAtMillis = agyLaunchStartedAt,
                )
            }
        }
        if (launchTask.agent == AgentKind.OpenCode) {
            scope.launch(Dispatchers.IO) {
                captureOpenCodeSessionId(
                    taskId = taskId,
                    binary = binary,
                    cwd = launchTask.cwd,
                    before = openCodeBeforeSessionId,
                    launchedPrompt = trailingPrompt,
                )
            }
        }
        if (launchTask.agent == AgentKind.Pi) {
            scope.launch(Dispatchers.IO) {
                capturePiSessionId(
                    taskId = taskId,
                    cwd = launchTask.cwd,
                    before = piBeforeSessionId,
                    launchedPrompt = trailingPrompt,
                    startedAtMillis = sessionCaptureStartedAt,
                )
            }
        }
        if (launchTask.agent == AgentKind.ClaudeCode) {
            scope.launch(Dispatchers.IO) {
                captureClaudeSessionId(
                    taskId = taskId,
                    cwd = launchTask.cwd,
                    before = claudeBeforeSessionId,
                    launchedPrompt = trailingPrompt,
                    startedAtMillis = sessionCaptureStartedAt,
                )
            }
        }
        if (launchTask.agent == AgentKind.Codex) {
            scope.launch(Dispatchers.IO) {
                captureCodexSessionId(
                    taskId = taskId,
                    cwd = launchTask.cwd,
                    before = codexBeforeSessionId,
                    launchedPrompt = trailingPrompt,
                    startedAtMillis = sessionCaptureStartedAt,
                )
            }
        }
        if (launchTask.agent == AgentKind.Hermes) {
            scope.launch(Dispatchers.IO) {
                captureHermesSessionId(
                    taskId = taskId,
                    binary = binary,
                    cwd = launchTask.cwd,
                    before = hermesBeforeSessionId,
                )
            }
        }
        if (launchTask.agent == AgentKind.OpenClaw) {
            scope.launch(Dispatchers.IO) {
                captureOpenClawSessionId(
                    taskId = taskId,
                    binary = binary,
                    cwd = launchTask.cwd,
                    before = openClawBeforeSessionId,
                    reuseMainSession = !launchTask.openClawNewSession,
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
            finishTask(taskId, AgentStatus.Done, exitCode = null, error = null, stoppedByUser = true)
            return
        }

        val outcomeHandled = AtomicBoolean(false)
        val artifacts = terminalHandle.artifacts
        val sessionMonitorJob = scope.launch {
            var sawWorking = false
            terminalHandle.statusTracker.status.collect { snapshot ->
                if (outcomeHandled.get()) return@collect
                if (snapshot.status == AgentStatus.Working) sawWorking = true
                val current = currentTask(taskId) ?: return@collect
                if (current.workflowStage != ProjectWorkflowStage.Build) return@collect
                val scrollback = terminals.bufferSnapshot(taskId)
                if (
                    !inferWorkflowBuildTurnComplete(
                        agent = current.agent,
                        artifactDir = terminalHandle.artifactDir,
                        scrollback = scrollback,
                        liveSessionStatus = snapshot.status,
                        sawWorking = sawWorking,
                    )
                ) {
                    return@collect
                }
                outcomeHandled.set(true)
                terminalHandle.statusTracker.markPhaseFinished()
                finishTask(
                    taskId = taskId,
                    status = AgentStatus.Done,
                    exitCode = 0,
                    error = null,
                    resumable = true,
                )
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
                            finishTask(
                                taskId = taskId,
                                status = AgentStatus.Done,
                                exitCode = 0,
                                error = null,
                                resumable = true,
                            )
                        }
                    }
                    is AgentWorkflowArtifacts.Event.ReviewReady -> {
                        updateTask(taskId) { current -> current.copy(completedResultText = event.json) }
                        terminalHandle.statusTracker.markPhaseFinished()
                        outcomeHandled.set(true)
                        finishTask(
                            taskId = taskId,
                            status = AgentStatus.Done,
                            exitCode = 0,
                            error = null,
                            resumable = true,
                        )
                    }
                    is AgentWorkflowArtifacts.Event.VerificationReady -> {
                        updateTask(taskId) { current -> current.copy(completedResultText = event.json) }
                        terminalHandle.statusTracker.markPhaseFinished()
                        outcomeHandled.set(true)
                        finishTask(
                            taskId = taskId,
                            status = AgentStatus.Done,
                            exitCode = 0,
                            error = null,
                            resumable = true,
                        )
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
        if (currentTask(taskId)?.status == AgentStatus.Blocked) return
        // If the question artifact landed while we were tearing down the monitor, still wait.
        if (!artifacts.answerFile.isFile) {
            artifacts.questionFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }
                ?.let { raw -> AgentWorkflowArtifacts.parseQuestionJson(raw) }
                ?.let { request ->
                    waitForUserInput(taskId, request, exitCode = exitCode, keepTerminal = true)
                    return
                }
        }

        val current = currentTask(taskId) ?: return
        val planFromDisk = artifacts.planFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }
        var reviewFromDisk = artifacts.reviewFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }
        var verificationFromDisk = artifacts.verificationFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }
        when (current.workflowStage) {
            ProjectWorkflowStage.Review -> if (reviewFromDisk == null) {
                reviewFromDisk = awaitWorkflowArtifactText(artifacts.reviewFile)
            }
            ProjectWorkflowStage.Verification -> if (verificationFromDisk == null) {
                verificationFromDisk = awaitWorkflowArtifactText(artifacts.verificationFile)
            }
            else -> Unit
        }

        val status = when {
            handle.stopRequested -> AgentStatus.Done
            exitCode == 0 -> AgentStatus.Done
            else -> AgentStatus.Error
        }
        val completedPlanText = if (status == AgentStatus.Done && current.planMode) {
            planFromDisk ?: current.completedPlanText
        } else {
            null
        }
        val completedResultText = when {
            reviewFromDisk != null -> reviewFromDisk
            verificationFromDisk != null -> verificationFromDisk
            status == AgentStatus.Done -> current.completedResultText
            else -> current.completedResultText
        }
        updateTask(taskId) { task ->
            task.copy(
                completedPlanText = completedPlanText ?: task.completedPlanText,
                completedResultText = completedResultText ?: task.completedResultText,
            )
        }
        val failureError = if (status == AgentStatus.Error) {
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
        if (status == AgentStatus.Error) {
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
            stoppedByUser = handle.stopRequested,
            resumable = status == AgentStatus.Done && exitCode == 0 && terminals.isAlive(taskId),
        )
    }

    private suspend fun runAcpProcess(
        taskId: String,
        handle: TaskHandle,
        writeAfterStart: String?,
        onTerminalStarted: () -> Unit,
        argvBuilder: (AgentCliAdapter, String, String?) -> List<String>,
    ) {
        val task = currentTask(taskId) ?: return
        val resolvedCwd = AgentScratchWorkspace.resolveCwd(task.cwd)
        val taskForLaunch = if (resolvedCwd != task.cwd) {
            updateTask(taskId) { it.copy(cwd = resolvedCwd) }
            persist()
            task.copy(cwd = resolvedCwd)
        } else task
        val projectEnv = taskForLaunch.projectId?.let { projectId ->
            runCatching { actionConfig.load().projects.firstOrNull { it.id == projectId }?.env }.getOrNull()
        }.orEmpty()
        val env = buildAgentLaunchEnvironment(projectEnv)
        val mcpEndpoint = if (taskForLaunch.attachAndyMcp) {
            runCatching { prepareAcpMcp() }.getOrElse { error ->
                finishTask(taskId, AgentStatus.Error, null, "failed to prepare Andy MCP: ${error.message}")
                return
            }
        } else null

        updateTask(taskId) {
            it.copy(
                status = AgentStatus.Working,
                startedAtMillis = it.startedAtMillis ?: System.currentTimeMillis(),
                exitCode = null,
                errorMessage = null,
                finishedAtMillis = null,
                unread = false,
            )
        }
        persist()
        reconcileWorkflowRun(taskId)
        if (handle.stopRequested) {
            finishTask(taskId, AgentStatus.Done, null, null, stoppedByUser = true)
            return
        }

        val launchTask = currentTask(taskId) ?: taskForLaunch
        val started = runCatching {
            acpManager.start(
                task = launchTask,
                env = env,
                mcp = mcpEndpoint,
                onStatusSnapshot = { snapshot -> applyStatusSnapshot(taskId, snapshot) },
            )
        }.getOrElse { error ->
            appendLaunchDiagnostics(taskId, "acpStartFailed=${error.message}\n")
            // ACP is an opportunistic lane. A failed spawn/initialize returns to the
            // existing terminal path while preserving the same task and prompt.
            updateTask(taskId) { it.copy(lane = AgentLaneKind.Terminal, status = null, startedAtMillis = null, finishedAtMillis = null) }
            persist()
            runProcess(taskId, handle, argvBuilder, writeAfterStart, onTerminalStarted)
            return
        }
        onTerminalStarted()
        ensureAcpArtifactMonitor(taskId, started.artifacts)
        appendEvents(
            taskId,
            listOf(AgentEvent.UserMessage(System.currentTimeMillis(), launchTask.promptForCli(), launchTask.skills, launchTask.imagePaths)),
        )
        val success = acpManager.prompt(taskId, launchTask.promptForCli(), launchTask.imagePaths)
        if (!success) {
            appendLaunchDiagnostics(taskId, "acpPromptFailed=true\n")
        }
        completeAcpPromptTurn(taskId, success, isAutoRetry = false)
    }

    private suspend fun runAcpFollowUp(taskId: String, prompt: String, imagePaths: List<String>): Boolean {
        acpSuppressProviderReplay.add(taskId)
        acpProviderReplayScratch.remove(taskId)
        try {
            val task = currentTask(taskId) ?: return false
            if (!acpManager.isAlive(taskId)) {
                val projectEnv = task.projectId?.let { projectId ->
                    runCatching { actionConfig.load().projects.firstOrNull { it.id == projectId }?.env }.getOrNull()
                }.orEmpty()
                val env = buildAgentLaunchEnvironment(projectEnv)
                val endpoint = if (task.attachAndyMcp) prepareAcpMcp() else null
                runCatching {
                    acpManager.start(task, env, endpoint) { snapshot -> applyStatusSnapshot(taskId, snapshot) }
                }.getOrElse {
                    appendLaunchDiagnostics(taskId, "acpResumeFailed=${it.message}\n")
                    return false
                }
            }
            acpManager.artifacts(taskId)?.let { ensureAcpArtifactMonitor(taskId, it) }
            val success = acpManager.prompt(taskId, prompt, imagePaths)
            return completeAcpPromptTurn(taskId, success, isAutoRetry = false)
        } finally {
            acpSuppressProviderReplay.remove(taskId)
            acpProviderReplayScratch.remove(taskId)
        }
    }

    private suspend fun completeAcpPromptTurn(
        taskId: String,
        promptSuccess: Boolean,
        isAutoRetry: Boolean,
    ): Boolean {
        if (deferAcpFinishIfAwaitingInput(taskId)) return promptSuccess

        val stalled = transcriptHasConnectionStall(taskId)
        if (
            stalled &&
            !isAutoRetry &&
            connectionStallAutoRetries.getOrDefault(taskId, 0) < 1 &&
            acpManager.isAlive(taskId)
        ) {
            connectionStallAutoRetries[taskId] = 1
            appendLaunchDiagnostics(taskId, "connectionStallAutoRetry=true\n")
            updateTask(taskId) {
                it.copy(
                    status = AgentStatus.Working,
                    errorMessage = null,
                    finishedAtMillis = null,
                    exitCode = null,
                )
            }
            val retrySuccess = acpManager.prompt(taskId, CONNECTION_STALL_RETRY_PROMPT, emptyList())
            return completeAcpPromptTurn(taskId, retrySuccess, isAutoRetry = true)
        }

        connectionStallAutoRetries.remove(taskId)
        val outcome = acpManager.awaitRunOutcome(taskId)
        val stillStalled = transcriptHasConnectionStall(taskId)
        val recovered = promptSuccess && !stillStalled
        val resumableAfterStall = stillStalled && acpManager.isAlive(taskId)
        finishTask(
            taskId = taskId,
            status = when {
                recovered -> AgentStatus.Done
                resumableAfterStall -> AgentStatus.Done
                else -> AgentStatus.Error
            },
            exitCode = null,
            error = when {
                recovered || resumableAfterStall -> null
                else -> outcome.error ?: "ACP prompt failed"
            },
            resumable = acpManager.isAlive(taskId) && (recovered || resumableAfterStall),
            statusConfident = true,
            stopReason = outcome.stopReason,
        )
        return recovered || resumableAfterStall
    }

    private fun transcriptHasConnectionStall(taskId: String): Boolean =
        eventFlows[taskId]?.value.orEmpty().hasRetriableConnectionStall()

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

    /**
     * Best-effort extraction of the launched prompt from provider argv.
     * Prefer flagged forms (`--prompt`, `--prompt-interactive`); fall back to a
     * trailing positional only when it is not a known flag value.
     */
    private fun promptFromArgv(argv: List<String>, binary: String): String? {
        promptFromInteractiveArgv(argv)?.let { return it }
        val promptFlag = argv.indexOfFirst { it == "--prompt" }
        if (promptFlag >= 0 && promptFlag + 1 < argv.size) {
            return argv[promptFlag + 1].trim().takeIf { it.isNotBlank() }
        }
        val flagValueIndexes = argv.indices.filter { index ->
            index > 0 && argv[index - 1].startsWith("-")
        }.toSet()
        return argv.withIndex().lastOrNull { (index, value) ->
            index > 0 &&
                index !in flagValueIndexes &&
                !value.startsWith("-") &&
                File(value).name != File(binary).name
        }?.value?.trim()?.takeIf { it.isNotBlank() }
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

    private fun captureOpenCodeSessionId(
        taskId: String,
        binary: String,
        cwd: String?,
        before: String?,
        launchedPrompt: String?,
    ) {
        val captured = OpenCodeSessionIds.awaitNewSessionId(
            binary = binary,
            cwd = cwd,
            before = before,
            launchedPrompt = launchedPrompt,
        ) ?: return
        if (captured.isBlank() || captured == before) return
        updateTask(taskId) { task ->
            if (task.vendorSessionId == captured) task else task.copy(vendorSessionId = captured)
        }
        appendLaunchDiagnostics(
            taskId,
            "vendorSessionId=$captured source=opencode before=${before.orEmpty()}\n",
        )
        scope.launch { persist() }
    }

    private fun capturePiSessionId(
        taskId: String,
        cwd: String?,
        before: String?,
        launchedPrompt: String?,
        startedAtMillis: Long,
    ) {
        val captured = PiSessionIds.awaitNewSessionId(
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
            "vendorSessionId=$captured source=pi before=${before.orEmpty()}\n",
        )
        scope.launch { persist() }
    }

    private fun captureClaudeSessionId(
        taskId: String,
        cwd: String?,
        before: String?,
        launchedPrompt: String?,
        startedAtMillis: Long,
    ) {
        val captured = ClaudeSessionIds.awaitNewSessionId(
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
            "vendorSessionId=$captured source=claude before=${before.orEmpty()}\n",
        )
        scope.launch { persist() }
    }

    private fun captureCodexSessionId(
        taskId: String,
        cwd: String?,
        before: String?,
        launchedPrompt: String?,
        startedAtMillis: Long,
    ) {
        val captured = CodexSessionIds.awaitNewSessionId(
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
            "vendorSessionId=$captured source=codex before=${before.orEmpty()}\n",
        )
        scope.launch { persist() }
    }

    private fun captureHermesSessionId(
        taskId: String,
        binary: String,
        cwd: String?,
        before: String?,
    ) {
        val captured = HermesSessionIds.awaitNewSessionId(
            binary = binary,
            cwd = cwd,
            before = before,
        ) ?: return
        if (captured.isBlank() || captured == before) return
        updateTask(taskId) { task ->
            if (task.vendorSessionId == captured) task else task.copy(vendorSessionId = captured)
        }
        appendLaunchDiagnostics(
            taskId,
            "vendorSessionId=$captured source=hermes before=${before.orEmpty()}\n",
        )
        scope.launch { persist() }
    }

    private fun captureOpenClawSessionId(
        taskId: String,
        binary: String,
        cwd: String?,
        before: String?,
        reuseMainSession: Boolean = false,
    ) {
        val captured = if (reuseMainSession) {
            OpenClawSessionIds.findNewestSession(binary, cwd)
        } else {
            OpenClawSessionIds.awaitNewSessionId(
                binary = binary,
                cwd = cwd,
                before = before,
            )
        } ?: return
        if (captured.isBlank() || (!reuseMainSession && captured == before)) return
        updateTask(taskId) { task ->
            if (task.vendorSessionId == captured) task else task.copy(vendorSessionId = captured)
        }
        appendLaunchDiagnostics(
            taskId,
            "vendorSessionId=$captured source=openclaw before=${before.orEmpty()}\n",
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
            val readyAtPrompt = terminals.liveSessionStatus(taskId) == AgentStatus.Done
            if (sawOutput && (terminalLooksReadyForInput(buffer) || readyAtPrompt)) {
                delay(300)
                if (handle.stopRequested || !terminals.isAlive(taskId)) return
                terminals.submitText(taskId, text.trimEnd('\r', '\n'))
                wrote = true
                appendLaunchDiagnostics(taskId, "initialPromptWritten=true readyAtPrompt=$readyAtPrompt\n")
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
            if (task.status != AgentStatus.Working) return@map task
            if (task.lane == AgentLaneKind.Acp) {
                return@map task.copy(
                    status = AgentStatus.Error,
                    interrupted = true,
                    resumable = task.acpSessionId?.isNotBlank() == true,
                    finishedAtMillis = task.finishedAtMillis ?: System.currentTimeMillis(),
                    statusConfident = true,
                )
            }
            val artifactDir = AgentWorkflowArtifacts.dirFor(task.cwd?.let(::File), task.id)
            val scrollback = terminals.bufferSnapshot(task.id).ifBlank {
                resolvedScrollbackFile(task.id).takeIf { it.isFile }?.readText().orEmpty()
            }
            val liveStatus = terminals.liveSessionStatus(task.id)
            when {
                // Status repair only — never revive unread. finishTask/markUnread own
                // attention while live; markRead must survive quit/restart.
                inferCompletedTurn(task.agent, artifactDir, scrollback, liveStatus) ->
                    task.copy(
                        status = AgentStatus.Done,
                        exitCode = task.exitCode ?: 0,
                        finishedAtMillis = task.finishedAtMillis ?: System.currentTimeMillis(),
                    )
                inferPausedAtPrompt(task.agent, artifactDir, scrollback, liveStatus) ->
                    task.copy(
                        status = AgentStatus.Done,
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
            // A question artifact is authoritative even if scrape/exit already published Done.
            if (task.status == AgentStatus.Error && task.userInputRequest == null) {
                task
            } else {
                task.copy(
                    status = AgentStatus.Blocked,
                    userInputRequest = request,
                    exitCode = exitCode,
                    finishedAtMillis = System.currentTimeMillis(),
                    unread = true,
                )
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

    override fun completeWorkflowRun(taskId: String) {
        val task = currentTask(taskId) ?: return
        if (!task.isActive || task.workflowStage != ProjectWorkflowStage.Build) return
        handles[taskId]?.stopRequested = true
        // Mark Completed before stopping the terminal. finishTask stops the session
        // itself (after atomically setting the status), so the run pipeline's own
        // awaitExit wakes only once the task is already inactive — otherwise it would
        // race in and overwrite this completion with Stopped/Failed.
        finishTask(
            taskId = taskId,
            status = AgentStatus.Done,
            exitCode = 0,
            error = null,
            forceKillTerminal = true,
        )
    }

    override fun stop(taskId: String) {
        val acpTask = currentTask(taskId)?.takeIf { it.lane == AgentLaneKind.Acp }
        if (acpTask != null) {
            handles[taskId]?.stopRequested = true
            acpManager.stop(taskId)
            finishTask(
                taskId = taskId,
                status = AgentStatus.Done,
                exitCode = null,
                error = null,
                stoppedByUser = true,
                forceKillTerminal = true,
            )
            return
        }
        val waiting = currentTask(taskId)?.takeIf { it.status == AgentStatus.Blocked }
        if (waiting != null) {
            terminals.stop(taskId)
            updateTask(taskId) {
                it.copy(
                    status = AgentStatus.Done,
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
                finishTask(taskId, AgentStatus.Done, exitCode = null, error = null, stoppedByUser = true)
            }
        }
    }

    override suspend fun delete(taskId: String, removeWorktree: Boolean, force: Boolean): WorktreeDeleteOutcome {
        val task = currentTask(taskId) ?: return WorktreeDeleteOutcome.Deleted
        val worktreePath = task.worktreePath
        val liveChildren = if (removeWorktree && task.ownsWorktree && worktreePath != null) {
            tasks.value.filter { child ->
                child.id != taskId &&
                    child.parentWorktreeTaskId == taskId &&
                    child.worktreePath != null &&
                    File(child.worktreePath).isDirectory
            }
        } else {
            emptyList()
        }
        if (liveChildren.isNotEmpty() && !force) {
            return WorktreeDeleteOutcome.BlockedByChildren(
                liveChildren.map { child ->
                    WorktreeBaseOption(
                        taskId = child.id,
                        title = child.title.ifBlank { child.id },
                        branch = child.branchName.orEmpty(),
                        path = child.worktreePath.orEmpty(),
                    )
                },
            )
        }
        if (task.isActive) {
            stop(taskId)
        }
        handles.remove(taskId)
        terminals.clear(taskId)
        acpArtifactJobs.remove(taskId)?.cancel()
        acpManager.clear(taskId)
        queuedAcpPermissions.remove(taskId)
        eventFlows.remove(taskId)
        connectionStallAutoRetries.remove(taskId)
        _tasks.update { list ->
            list.mapNotNull { existing ->
                when {
                    existing.id == taskId -> null
                    existing.parentWorktreeTaskId == taskId -> existing.copy(parentWorktreeTaskId = null)
                    else -> existing
                }
            }
        }
        store.deleteTaskArtifacts(taskId)
        task.workflowTaskId?.let { projectTaskId -> detachDeletedWorkflowRun(projectTaskId, taskId) }
        if (removeWorktree && task.ownsWorktree && worktreePath != null) {
            task.originDir?.let { originDir ->
                withContext(Dispatchers.IO) { worktrees.remove(originDir, worktreePath, task.branchName) }
            }
        }
        persist(allowEmptyTaskList = true)
        return WorktreeDeleteOutcome.Deleted
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
        return eventFlows.computeIfAbsent(taskId) {
            MutableStateFlow(
                if (currentTask(taskId)?.lane == AgentLaneKind.Acp) loadAcpEventsForDisplay(taskId) else emptyList(),
            )
        }
    }

    override fun interactiveResumeCommand(taskId: String): String? {
        val task = currentTask(taskId) ?: return null
        // Prefer tmux attach when the Andy tmux session is alive.
        if (app.andy.terminal.TmuxAndy.isAvailable() &&
            app.andy.terminal.TmuxAndy.hasSession(taskId)
        ) {
            return app.andy.terminal.TmuxAndy.attachArgv(taskId).joinToString(" ") { shellQuote(it) }
        }
        val adapter = adapters[task.agent] ?: return null
        val binary = binaryFor(task.agent) ?: task.agent.cliName
        val changeDirectory = "cd ${shellQuote(AgentScratchWorkspace.resolveCwd(task.cwd))} && "
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
        val statuses = withContext(Dispatchers.IO) {
            val located = locator.locateAll(binaryOverrides)
            val nodeAvailable = NodeRuntimeLocator().locate() != null
            located.map { status ->
                val acpReady = when (AcpRegistry.spec(status.kind)) {
                    is app.andy.desktop.service.agents.acp.AcpLaunchSpec.Npx -> nodeAvailable
                    is app.andy.desktop.service.agents.acp.AcpLaunchSpec.Native -> status.binaryPath != null
                    null -> false
                }
                status.copy(acpReady = acpReady)
            }
        }
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

    override suspend fun currentBranch(dir: String): String? =
        withContext(Dispatchers.IO) { worktrees.currentBranch(dir) }

    override suspend fun worktreeBaseOptions(originDir: String): List<WorktreeBaseOption> {
        val onDiskPaths = withContext(Dispatchers.IO) {
            worktrees.listAll(originDir).mapTo(linkedSetOf()) { canonicalPath(it.path) }
        }
        return tasks.value.filter { task ->
            task.originDir == originDir &&
                !task.archived &&
                task.branchName != null &&
                task.worktreePath != null &&
                canonicalPath(task.worktreePath) in onDiskPaths
        }.map { task ->
            WorktreeBaseOption(
                taskId = task.id,
                title = task.title.ifBlank { task.id },
                branch = task.branchName!!,
                path = task.worktreePath!!,
            )
        }
    }

    override suspend fun worktreeTree(originDir: String): List<WorktreeNode> {
        val onDisk = withContext(Dispatchers.IO) { worktrees.listAll(originDir) }
        val trackedByPath = trackedWorktreeOwnerByPath(originDir)
        return onDisk.map { info ->
            val task = trackedByPath[canonicalPath(info.path)]
            WorktreeNode(
                path = info.path,
                branch = info.branch,
                isMain = info.isMain,
                taskId = task?.id,
                taskTitle = task?.title,
                // Only honor lineage when the parent is still present on disk; otherwise this node is a root
                // (covers manual deletion outside Andy, matching the forced-delete orphaning behavior).
                parentTaskId = task?.parentWorktreeTaskId?.takeIf { pid ->
                    onDisk.any { trackedByPath[canonicalPath(it.path)]?.id == pid }
                },
                tracked = task != null,
            )
        }
    }

    override fun mergeCommand(targetDir: String, branch: String): String =
        worktrees.mergeCommand(targetDir, branch)

    override suspend fun mergeBranch(
        targetDir: String,
        branch: String,
        sourceWorktreePath: String?,
    ): WorktreeMergeOutcome =
        withContext(Dispatchers.IO) { worktrees.merge(targetDir, branch, sourceWorktreePath) }

    override suspend fun abortMerge(targetDir: String): Result<Unit> =
        withContext(Dispatchers.IO) { worktrees.abortMerge(targetDir) }

    /** Prefer the owning task when workflow runs reuse one worktree path. */
    private fun trackedWorktreeOwnerByPath(originDir: String): Map<String, AgentTask> =
        tasks.value
            .filter { it.originDir == originDir && it.worktreePath != null }
            .groupBy { canonicalPath(it.worktreePath!!) }
            .mapValues { (_, group) ->
                group.firstOrNull { it.ownsWorktree }
                    ?: group.minByOrNull { it.createdAtMillis }
                    ?: group.first()
            }

    private fun canonicalPath(path: String): String =
        runCatching { File(path).canonicalPath }.getOrElse { path }

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

    private suspend fun prepareMcp(agent: AgentKind, cwd: File? = null): String? = mcpMutex.withLock {
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
            // These only support config-file registration; write it and pass no URL.
            AgentKind.Cursor -> {
                mcp.writeConfig("Cursor", port)
                null
            }
            AgentKind.Antigravity -> {
                mcp.writeConfig("Antigravity", port)
                null
            }
            AgentKind.OpenCode -> {
                McpClientConfig.writeConfig(McpClientConfig.ClientType.OpenCode, port, cwd)
                null
            }
            // Pi has no native MCP config; wire ~/.pi/mcp.json for pi-mcp-compatible extensions
            // and pass ANDY_MCP_URL to Andy's Pi extension.
            AgentKind.Pi -> {
                McpClientConfig.writeConfig(McpClientConfig.ClientType.Pi, port, cwd)
                "http://127.0.0.1:$port/mcp-http"
            }
            AgentKind.Hermes -> {
                McpClientConfig.writeConfig(McpClientConfig.ClientType.Hermes, port, cwd)
                null
            }
            AgentKind.OpenClaw -> {
                McpClientConfig.writeConfig(McpClientConfig.ClientType.OpenClaw, port, cwd)
                null
            }
        }
    }

    /** ACP receives an MCP server descriptor directly; it must not mutate provider config files. */
    private suspend fun prepareAcpMcp(): AndyMcpEndpoint = mcpMutex.withLock {
        val port = runCatching { workspaceStore.load().mcpServerPort }.getOrElse { 8565 }
        val isRunning = runCatching { mcp.running.first() }.getOrElse { false }
        if (!isRunning) {
            val result = mcp.start(port)
            check(result.isSuccess) { result.stderr.ifBlank { "server failed to start" } }
        }
        AndyMcpEndpoint(port = port, httpUrl = "http://127.0.0.1:$port/mcp-http")
    }

    private fun runOpenClawModelPreflight(binary: String, model: String, cwd: String?): Boolean = runCatching {
        val process = ProcessBuilder(binary, "models", "set", model)
            .directory(cwd?.let(::File))
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().readText()
        process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    private fun binaryFor(agent: AgentKind): String? {
        val status = _cliStatuses.value.firstOrNull { it.kind == agent }
        return when {
            status?.ready == true -> status.binaryPath
            status != null -> null
            else -> binaryOverrides[agent.cliName]?.takeIf { File(it).canExecute() }
        }
    }

    private fun resolveLane(agent: AgentKind): AgentLaneKind {
        val suffix = agent.name.uppercase()
        val configured = System.getenv("ANDY_AGENT_LANE_$suffix")
            ?: System.getenv("ANDY_AGENT_LANE")
        return when (configured?.lowercase()) {
            "terminal", "tmux", "bossterm" -> AgentLaneKind.Terminal
            "acp" -> AgentLaneKind.Acp
            else -> agent.defaultLane()
        }
    }

    private fun unavailableCliMessage(agent: AgentKind): String {
        val issue = _cliStatuses.value.firstOrNull { it.kind == agent }?.issue
        return issue?.let { "${it.title}: ${it.detail}" }
            ?: "${agent.cliName} CLI not found — install it or set a binary override"
    }

    private fun defaultProjectState(projectId: String): ProjectWorkflowState {
        val agent = _lastUsedAgent.value ?: AgentKind.Codex
        val base = _providerDefaults.value[agent]?.toProjectProfile(agent) ?: ProjectAgentProfile(agent = agent)
        return ProjectWorkflowState(
            projectId = projectId,
            profiles = mapOf(
                ProjectTaskKind.Spec to base.normalizedFor(ProjectTaskKind.Spec).copy(
                    autonomy = app.andy.model.AgentAutonomy.ReadOnly,
                    sandboxMode = AgentSandboxMode.ReadOnly,
                ),
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
            ProjectTaskKind.Spec -> normalized.copy(useWorktree = false)
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
                profiles[kind] ?: base.normalizedFor(kind).let {
                    if (kind == ProjectTaskKind.Spec) {
                        it.copy(autonomy = app.andy.model.AgentAutonomy.ReadOnly, sandboxMode = AgentSandboxMode.ReadOnly)
                    } else {
                        it
                    }
                }
            },
        )
    }

    override suspend fun projectContextDir(projectId: String): String? =
        runCatching { actionConfig.load().projects.firstOrNull { it.id == projectId }?.contextDir }.getOrNull()

    private suspend fun projectDirectory(projectId: String): String? = projectContextDir(projectId)

    fun close() {
        shutdownForProcessExit()
    }

    /** Persist state and tear down owned tmux sessions on JVM exit or explicit close. */
    fun shutdownForProcessExit() {
        runCatching { snapshotActiveTasksBeforeShutdown() }
        _tasks.value.filter { it.lane == AgentLaneKind.Acp && acpManager.isAlive(it.id) }.forEach { task ->
            acpManager.stop(task.id)
        }
        val activeTaskIds = handles.keys.toList()
        val jobs = handles.values.map { it.job }
        AgentSessionShutdown.onProcessExit(
            terminals = terminals,
            activeTaskIds = activeTaskIds,
            workspaceStore = workspaceStore,
            ownsAgentSessions = ownsAgentSessions,
        )
        handles.clear()
        jobs.forEach { it?.cancel() }
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
        confirmToolCalls = confirmToolCalls,
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
            append("\n\n").append(
                specPlanWriteInstruction(
                    artifactRelPath,
                    including = "including interfaces, edge cases, and verification steps",
                ),
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
                    null -> ProjectTaskState.Queued
                    AgentStatus.Working -> ProjectTaskState.Running
                    AgentStatus.Blocked -> ProjectTaskState.Waiting
                    AgentStatus.Done -> ProjectTaskState.Waiting
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
        return run.status == AgentStatus.Blocked
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
        if (run.status == AgentStatus.Blocked) {
            updateProjectTask(projectTaskId) { task ->
                if (task.kind == ProjectTaskKind.Spec && task.state == ProjectTaskState.Completed) task
                else task.copy(state = ProjectTaskState.Waiting, lastError = null)
            }
            persist()
            return
        }
        // null status means createAndStart returned before launchRun promoted the agent to
        // Working. That is still in-flight — do not treat it as a failed stage.
        if (run.status == null || run.isActive) {
            updateProjectTask(projectTaskId) {
                it.copy(
                    state = if (run.status == null) ProjectTaskState.Queued else ProjectTaskState.Running,
                    lastError = null,
                )
            }
            persist()
            return
        }
        if (run.status != AgentStatus.Done) {
            val message = run.errorMessage ?: when (run.status) {
                AgentStatus.Error -> "the app restarted while this workflow stage was active"
                AgentStatus.Done -> "workflow stage was stopped"
                else -> "workflow stage failed"
            }
            if (typedTask.kind == ProjectTaskKind.Spec) {
                // A stopped/failed refine shouldn't bury a previously completed plan.
                if (run.stoppedByUser && typedTask.planVersions.isNotEmpty()) {
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
                val plan = resolveCompletedPlanText(run.id, run)?.takeIf { it.isNotBlank() }
                if (plan == null) {
                    if (run.stoppedByUser && typedTask.planVersions.isNotEmpty()) {
                        updateProjectTask(projectTaskId) {
                            it.copy(state = ProjectTaskState.Completed, lastError = null, updatedAtMillis = System.currentTimeMillis())
                        }
                    } else {
                        updateProjectTask(projectTaskId) {
                            it.copy(state = ProjectTaskState.NeedsAttention, lastError = "the planning run returned no final plan")
                        }
                    }
                } else {
                    updateProjectTask(projectTaskId) { task ->
                        val existing = task.planVersions.firstOrNull { it.runId == run.id }
                        when {
                            existing == null -> task.copy(
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
                            existing.text == plan -> task.copy(
                                state = ProjectTaskState.Completed,
                                lastError = null,
                                updatedAtMillis = System.currentTimeMillis(),
                            )
                            else -> task.copy(
                                planVersions = task.planVersions.map { version ->
                                    if (version.runId == run.id) version.copy(text = plan) else version
                                },
                                state = ProjectTaskState.Completed,
                                lastError = null,
                                updatedAtMillis = System.currentTimeMillis(),
                            )
                        }
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
        return readWorkflowArtifactText(file)
    }

    private fun readWorkflowArtifactText(file: File): String? =
        file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }

    private suspend fun awaitWorkflowArtifactText(file: File): String? {
        val deadline = System.currentTimeMillis() + WORKFLOW_ARTIFACT_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            readWorkflowArtifactText(file)?.let { return it }
            delay(AgentWorkflowArtifacts.DEFAULT_POLL_INTERVAL_MS)
        }
        return readWorkflowArtifactText(file)
    }

    private suspend fun reconcilePendingReviewArtifact(build: ProjectTask, review: ProjectTask?): Boolean {
        val reviewTask = review ?: return false
        val attempt = reviewTask.attempts.lastOrNull {
            it.stage == ProjectWorkflowStage.Review && it.reviewGeneration == build.reviewGeneration
        } ?: return false
        val run = currentTask(attempt.runId) ?: return false
        if (run.status != AgentStatus.Done || reviewTask.reviewVerdicts.any { it.runId == run.id }) return false
        val artifactText = artifactTextForRun(run, "review.json") ?: run.completedResultText
        if (artifactText.isNullOrBlank()) return false
        reconcileWorkflowRun(run.id)
        return projectTask(build.id)?.state != ProjectTaskState.NeedsAttention
    }

    /** Pairs stuck in NeedsAttention after a late review.json write recover on launch or resume. */
    private suspend fun reconcileStuckWorkflowArtifacts() {
        val buildIds = _projects.value.values.flatMap { workflow ->
            workflow.tasks.filter { it.kind == ProjectTaskKind.Build && it.state == ProjectTaskState.NeedsAttention }.map { it.id }
        }
        for (buildId in buildIds) {
            val build = projectTask(buildId) ?: continue
            val review = build.linkedReviewTaskId?.let(::projectTask)
            reconcilePendingReviewArtifact(build, review)
        }
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
        return currentTask(latest.runId)?.takeIf { it.status == AgentStatus.Done }
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
                workflowTask.state in setOf(ProjectTaskState.Queued, ProjectTaskState.Running, ProjectTaskState.Waiting) && lastRun?.status == AgentStatus.Error
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
            val globalNotes = project.notes.filter { it.source == ConfigSource.Global }
            if (globalNotes.isEmpty()) return@forEach
            val existing = _projects.value[project.id] ?: defaultProjectState(project.id)
            projectIdsToClear += project.id
            if (existing.legacyNotesMigrated) return@forEach
            val block = buildString {
                append("## Migrated todos\n")
                globalNotes.forEach { note ->
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
                if (project.id in projectIdsToClear) project.copy(notes = project.notes.filterNot { it.source == ConfigSource.Global }) else project
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
                !task.archived && !task.isActive && !resolvedScrollbackFile(task.id).isFile
            }
        }
        if (candidates.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                val backupDir = File(
                    store.storeFile.parentFile,
                    "backups/pre-legacy-chat-archive-${System.currentTimeMillis()}",
                )
                backupDir.mkdirs()
                store.storeFile.copyTo(File(backupDir, "agents.db"), overwrite = true)
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

    /** Repairs plan-mode runs whose stored plan text disagrees with `.andy/<taskId>/plan.md`. */
    private suspend fun backfillPlanModeCompletedText() {
        val recoveredPlans = withContext(Dispatchers.IO) {
            _tasks.value.asSequence()
                .filter { task -> task.planMode && task.status == AgentStatus.Done }
                .mapNotNull { task ->
                    resolveCompletedPlanText(task.id, task)?.let { plan ->
                        val runMismatch = task.completedPlanText?.trim() != plan
                        val versionMismatch = _projects.value.values.any { workflow ->
                            workflow.tasks.any { projectTask ->
                                projectTask.planVersions.any { version ->
                                    version.runId == task.id && version.text != plan
                                }
                            }
                        }
                        if (runMismatch || versionMismatch) task.id to plan else null
                    }
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
                repairSpecWorkflowState(
                    task.copy(planVersions = task.planVersions.map { version ->
                        recoveredPlans[version.runId]?.let { plan ->
                            if (version.text == plan) version else version.copy(text = plan)
                        } ?: version
                    }),
                )
            })
        }
        if (repairedTasks == _tasks.value && repairedWorkflows == _projects.value) return

        _tasks.value = repairedTasks
        _projects.value = repairedWorkflows
        persist()
    }

    /**
     * Grill-me ACP specs can park on [ProjectTaskState.Waiting] after a blocked checkpoint while
     * the planning run later finishes with a plan artifact. Repair any spec that already has a
     * done run + plan version but never reached [ProjectTaskState.Completed].
     */
    private suspend fun repairCompletedSpecWorkflowStates() {
        val repaired = _projects.value.mapValues { (_, workflow) ->
            workflow.copy(tasks = workflow.tasks.map { repairSpecWorkflowState(it) })
        }
        if (repaired == _projects.value) return
        _projects.value = repaired
        persist()
    }

    private fun repairSpecWorkflowState(task: ProjectTask): ProjectTask {
        if (task.kind != ProjectTaskKind.Spec || task.planVersions.isEmpty()) return task
        val hasDonePlan = task.planVersions.any { version ->
            version.text.isNotBlank() &&
                currentTask(version.runId)?.status == AgentStatus.Done
        }
        if (!hasDonePlan || task.state == ProjectTaskState.Completed) return task
        return task.copy(
            state = ProjectTaskState.Completed,
            lastError = null,
            updatedAtMillis = System.currentTimeMillis(),
        )
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

    private fun loadAcpEventsForDisplay(taskId: String): List<AgentEvent> =
        coalesceAcpTranscriptEvents(acpTranscriptStore.load(taskId))

    private fun readPlanFromDisk(task: AgentTask): String? = runCatching {
        AgentWorkflowArtifacts.dirFor(task.cwd?.let(::File), task.id)
            .resolve("plan.md")
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun readPendingWorkflowQuestion(task: AgentTask): AgentUserInputRequest? = runCatching {
        val artifactDir = AgentWorkflowArtifacts.dirFor(task.cwd?.let(::File), task.id)
        val questionFile = File(artifactDir, "question.json")
        if (!questionFile.isFile) return@runCatching null
        questionFile.readText().trim().takeIf { it.isNotBlank() }
            ?.let { AgentWorkflowArtifacts.parseQuestionJson(it) }
    }.getOrNull()

    private suspend fun deferAcpFinishIfAwaitingInput(taskId: String): Boolean {
        val task = currentTask(taskId) ?: return false
        if (task.status == AgentStatus.Blocked) return true
        val request = readPendingWorkflowQuestion(task) ?: return false
        waitForUserInput(taskId, request, exitCode = 0, keepTerminal = true)
        return true
    }

    private fun resolveCompletedPlanText(taskId: String, task: AgentTask): String? {
        readPlanFromDisk(task)?.let { return it }
        task.completedPlanText?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        if (task.workflowStage == ProjectWorkflowStage.Spec) return null
        if (task.lane != AgentLaneKind.Acp) return null
        return planTextFromAcpTranscript(acpTranscriptStore.load(taskId))
    }

    private fun refreshAcpTranscriptFromDisk(taskId: String) {
        val task = currentTask(taskId) ?: return
        if (task.lane != AgentLaneKind.Acp) return
        scope.launch(Dispatchers.IO) {
            val loaded = loadAcpEventsForDisplay(taskId)
            eventFlows.computeIfAbsent(taskId) { MutableStateFlow(loaded) }.value = loaded
            if (task.planMode && task.status == AgentStatus.Done && task.completedPlanText.isNullOrBlank()) {
                resolveCompletedPlanText(taskId, task)?.let { plan ->
                    updateTask(taskId) { current ->
                        if (current.completedPlanText.isNullOrBlank()) current.copy(completedPlanText = plan) else current
                    }
                    persist()
                }
            }
        }
    }

    private fun appendEvents(taskId: String, events: List<AgentEvent>) {
        if (events.isEmpty()) return
        val task = currentTask(taskId)
        val isAcp = task?.lane == AgentLaneKind.Acp
        val flow = eventFlows.computeIfAbsent(taskId) {
            MutableStateFlow(if (isAcp) loadAcpEventsForDisplay(taskId) else emptyList())
        }
        val filterReplay = taskId in acpSuppressProviderReplay
        val replayScratch = if (filterReplay) {
            acpProviderReplayScratch.computeIfAbsent(taskId) { StringBuilder() }
        } else {
            null
        }
        val accepted = if (!filterReplay) {
            events
        } else {
            events.fold(emptyList<AgentEvent>() to flow.value) { (acceptedEvents, acc), event ->
                when (val decision = filterAcpProviderHistoryReplay(acc, event, replayScratch!!)) {
                    AcpReplayFilterResult.Ignore -> acceptedEvents to acc
                    is AcpReplayFilterResult.Accept -> {
                        val accepted = when (event) {
                            is AgentEvent.AssistantText -> decision.text?.let { text -> event.copy(text = text) } ?: event
                            is AgentEvent.Thinking -> decision.text?.let { text -> event.copy(text = text) } ?: event
                            else -> event
                        }
                        val nextAcc = mergeAcpTranscriptEvent(acc, accepted)
                        (acceptedEvents + accepted) to nextAcc
                    }
                }
            }.first
        }
        if (accepted.isEmpty()) return
        accepted.filterIsInstance<AgentEvent.AvailableCommands>().forEach { event ->
            task?.let { recordSlashCommands(it.agent, it.worktreePath ?: it.cwd, event.commands) }
        }
        if (isAcp) {
            accepted.forEach { event ->
                if (event is AgentEvent.ToolCall) acpTranscriptStore.upsert(taskId, event)
                else acpTranscriptStore.append(taskId, event)
            }
        }
        flow.update { existing ->
            val next = if (isAcp) {
                accepted.fold(existing) { acc, event -> mergeAcpTranscriptEvent(acc, event) }
            } else {
                accepted.fold(existing) { acc, event -> acc + event }
            }
            if (isAcp) next else next.takeLast(MAX_TERMINAL_EVENTS_IN_MEMORY)
        }
    }

    private fun mergeAcpTranscriptEvent(
        existing: List<AgentEvent>,
        event: AgentEvent,
    ): List<AgentEvent> = when {
        event is AgentEvent.ToolCall -> AcpEventMapper.reduce(existing, event)
        else -> coalesceAgentStreamDeltas(existing, listOf(event))
    }

    private fun appendAcpEvent(taskId: String, event: AgentEvent) = appendEvents(taskId, listOf(event))

    private fun ensureAcpArtifactMonitor(taskId: String, artifacts: AgentWorkflowArtifacts) {
        if (acpArtifactJobs.containsKey(taskId)) return
        acpArtifactJobs[taskId] = scope.launch {
            artifacts.events.collect { event ->
                when (event) {
                    is AgentWorkflowArtifacts.Event.PlanReady -> {
                        updateTask(taskId) { it.copy(completedPlanText = event.text) }
                        reconcileWorkflowRun(taskId)
                    }
                    is AgentWorkflowArtifacts.Event.ReviewReady -> updateTask(taskId) { it.copy(completedResultText = event.json) }
                    is AgentWorkflowArtifacts.Event.VerificationReady -> updateTask(taskId) { it.copy(completedResultText = event.json) }
                    is AgentWorkflowArtifacts.Event.QuestionReady -> waitForUserInput(taskId, event.request, exitCode = 0, keepTerminal = true)
                }
                persist()
            }
        }.also { job ->
            job.invokeOnCompletion { acpArtifactJobs.remove(taskId, job) }
        }
    }

    private fun persistAcpSessionId(taskId: String, sessionId: String) {
        updateTask(taskId) { it.copy(acpSessionId = sessionId) }
        scope.launch { persist() }
    }

    private fun handleAcpPermission(taskId: String, pending: PendingAcpPermission) {
        val task = currentTask(taskId)
        val activePermission = task?.userInputRequest?.takeIf {
            it.origin == app.andy.model.AgentUserInputOrigin.AcpPermission
        }
        if (activePermission != null && activePermission.id != pending.request.id) {
            queuedAcpPermissions.computeIfAbsent(taskId) { ArrayDeque() }.addLast(pending)
            return
        }
        presentAcpPermission(taskId, pending)
    }

    private fun presentAcpPermission(taskId: String, pending: PendingAcpPermission) {
        val request = pending.request
        val question = request.questions.firstOrNull()
        if (question == null) return
        appendEvents(
            taskId,
            listOf(
                AgentEvent.PermissionRequest(
                    atMillis = System.currentTimeMillis(),
                    requestId = request.id,
                    toolName = question.header,
                    question = question.question,
                    options = question.options,
                ),
            ),
        )
        updateTask(taskId) {
            it.copy(
                status = AgentStatus.Blocked,
                statusConfident = true,
                userInputRequest = request,
                finishedAtMillis = null,
                unread = true,
            )
        }
        scope.launch { persist() }
    }

    private fun handleAcpPermissionResolved(
        taskId: String,
        requestId: String,
        optionId: String,
        allowed: Boolean,
        note: String? = null,
    ) {
        appendEvents(
            taskId,
            listOf(AgentEvent.PermissionResolved(System.currentTimeMillis(), requestId, optionId, allowed, note)),
        )
        updateTask(taskId) {
            if (it.userInputRequest?.id == requestId) {
                it.copy(status = AgentStatus.Working, statusConfident = true, userInputRequest = null, finishedAtMillis = null)
            } else it
        }
        queuedAcpPermissions[taskId]?.removeFirstOrNull()?.let { next -> presentAcpPermission(taskId, next) }
        scope.launch { persist() }
    }

    override fun markRead(taskId: String) {
        val task = currentTask(taskId) ?: return
        if (!task.unread) return
        updateTask(taskId) { it.copy(unread = false) }
        scope.launch { persist() }
    }

    override fun setChatViewing(taskId: String?, viewing: Boolean) {
        when {
            taskId == null -> {
                viewingTaskIds.clear()
                terminals.clearForeground()
            }
            viewing -> {
                viewingTaskIds.add(taskId)
                terminals.setOnlyForeground(taskId)
                markRead(taskId)
                refreshAcpTranscriptFromDisk(taskId)
            }
            else -> {
                viewingTaskIds.remove(taskId)
                terminals.setForeground(taskId, false)
            }
        }
    }

    override fun setAppForeground(foreground: Boolean) {
        if (appForeground == foreground) return
        appForeground = foreground
        // Coming back to the window is the moment the open chat is actually seen: clear the
        // badge that accumulated while it sat behind another app.
        if (foreground) viewingTaskIds.forEach(::markRead)
    }

    private fun applyStatusSnapshot(taskId: String, snapshot: AgentStatusSnapshot) {
        val task = currentTask(taskId) ?: return
        val terminalLive = isLaneLive(taskId)
        if (shouldIgnoreStatusSnapshot(task, snapshot, terminalLive = terminalLive)) return
        val previous = previousTaskStatuses.put(taskId, snapshot.status)
        val clearResumable = snapshot.status == AgentStatus.Working ||
            snapshot.status == AgentStatus.Blocked
        val statusChanged = task.status != snapshot.status
        // Confidence-only flips while Working are scrape noise. Each updateTask republishes
        // the tasks list and recomposes the chat pane. Attention only cares about confidence
        // on Done/Blocked/Error.
        val confidenceChanged =
            task.statusConfident != snapshot.confident && snapshot.status != AgentStatus.Working
        if (statusChanged ||
            confidenceChanged ||
            (clearResumable && task.resumable)
        ) {
            updateTask(taskId) {
                it.copy(
                    status = snapshot.status,
                    statusConfident = snapshot.confident,
                    // Live Working/Blocked means the turn is not finished anymore.
                    finishedAtMillis = when (snapshot.status) {
                        AgentStatus.Working, AgentStatus.Blocked -> null
                        else -> it.finishedAtMillis
                    },
                    resumable = if (clearResumable) false else it.resumable,
                )
            }
        }
        if (previous != null && previous != snapshot.status &&
            statusNeedsUnread(
                task = task,
                previous = previous,
                next = snapshot.status,
                viewing = isViewing(taskId),
                terminalLive = terminalLive,
            )
        ) {
            markUnread(taskId)
        }
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

    /** Marks a task as retained by the automatic sweep after its files are safely rewritten. */
    internal suspend fun markArchivedByRetention(taskId: String, compressed: Boolean = true) {
        val task = currentTask(taskId) ?: return
        if (task.isActive) return
        updateTask(taskId) {
            it.copy(
                archived = true,
                transcriptCompressed = compressed,
                unread = false,
            )
        }
        persist()
    }

    override fun unarchive(taskId: String) {
        val task = currentTask(taskId) ?: return
        if (!task.archived) return
        updateTask(taskId) { it.copy(archived = false) }
        scope.launch { persist() }
    }

    private fun finishTask(
        taskId: String,
        status: AgentStatus,
        exitCode: Int?,
        error: String?,
        stoppedByUser: Boolean = false,
        resumable: Boolean = false,
        interrupted: Boolean = false,
        statusConfident: Boolean = true,
        forceKillTerminal: Boolean = false,
        stopReason: String? = null,
    ) {
        val lane = currentTask(taskId)?.lane ?: AgentLaneKind.Terminal
        val completedChanges = currentTask(taskId)?.let { task ->
            val baseline = task.changeBaselineTree
            task.cwd?.takeIf { baseline != null }?.let { cwd ->
                worktrees.changeSnapshot(cwd, baseline)
            }
        }
        updateTask(taskId) { task ->
            val shouldFinalize = task.finishedAtMillis == null && (task.isActive || task.status != null)
            if (shouldFinalize) {
                val resolvedPlanText = if (status == AgentStatus.Done && task.planMode) {
                    resolveCompletedPlanText(taskId, task)
                } else {
                    null
                }
                val completedPlanText = when {
                    resolvedPlanText != null -> resolvedPlanText
                    status == AgentStatus.Done && task.planMode && task.workflowStage == ProjectWorkflowStage.Spec -> null
                    else -> task.completedPlanText
                }
                task.copy(
                    status = status,
                    stoppedByUser = stoppedByUser,
                    resumable = resumable,
                    interrupted = interrupted,
                    statusConfident = statusConfident,
                    stopReason = stopReason ?: task.stopReason,
                    exitCode = exitCode,
                    errorMessage = error,
                    finishedAtMillis = System.currentTimeMillis(),
                    unread = !isViewing(taskId),
                    completedChanges = completedChanges ?: task.completedChanges,
                    completedPlanText = completedPlanText,
                )
            } else {
                task
            }
        }
        val queuedFollowUp = currentTask(taskId)?.queuedFollowUps?.firstOrNull()
        handles.remove(taskId)
        val keepViewerMounted = resumable || taskId in viewingTaskIds
        if (lane == AgentLaneKind.Acp) {
            if (forceKillTerminal || stoppedByUser || (status == AgentStatus.Error && !resumable)) {
                acpArtifactJobs.remove(taskId)?.cancel()
                acpManager.clear(taskId)
                queuedAcpPermissions.remove(taskId)
            }
        } else {
            when {
                forceKillTerminal || stoppedByUser -> terminals.stop(taskId)
                terminals.isAlive(taskId) && keepViewerMounted -> Unit
                terminals.isAlive(taskId) -> terminals.detach(taskId)
                else -> terminals.stop(taskId)
            }
        }
        previousTaskStatuses.remove(taskId)
        if (status == AgentStatus.Done && queuedFollowUp != null && !stoppedByUser) {
            updateTask(taskId) { current -> current.copy(queuedFollowUps = current.queuedFollowUps.drop(1)) }
            resume(
                taskId,
                queuedFollowUp.text,
                queuedFollowUp.imagePaths,
                queuedFollowUp.skills,
                queuedFollowUp.contextBundleIds,
                queuedFollowUp.provenance,
            )
        } else {
            scope.launch {
                persist()
                reconcileWorkflowRun(taskId)
                val workflowTaskId = currentTask(taskId)?.workflowTaskId
                if (workflowTaskId != null) {
                    updateProjectTask(workflowTaskId) { repairSpecWorkflowState(it) }
                    persist()
                }
            }
        }
    }

    private suspend fun persist(allowEmptyTaskList: Boolean = false) {
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
                allowEmptyTaskList = allowEmptyTaskList,
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
