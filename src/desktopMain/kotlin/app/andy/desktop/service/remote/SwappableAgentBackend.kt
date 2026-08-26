package app.andy.desktop.service.remote

import app.andy.desktop.service.McpAgentRunClient
import app.andy.desktop.service.agents.DesktopAgentRunService
import app.andy.model.AgentChangeSummary
import app.andy.model.AgentCliStatus
import app.andy.model.AgentContextualProvenance
import app.andy.model.AgentEvent
import app.andy.model.AgentFileDiff
import app.andy.model.AgentKind
import app.andy.model.AgentLaneKind
import app.andy.model.AgentModelOption
import app.andy.model.AgentProviderDefaults
import app.andy.model.AgentProviderQuota
import app.andy.model.AgentQuotaAccess
import app.andy.model.AgentSkill
import app.andy.model.AgentSlashCommand
import app.andy.model.AgentTask
import app.andy.model.AgentTaskDraft
import app.andy.model.AgentThreadChangeSnapshot
import app.andy.model.ProjectAgentProfile
import app.andy.model.ProjectBuildPairDraft
import app.andy.model.ProjectSpecDraft
import app.andy.model.ProjectTaskKind
import app.andy.model.ProjectWorkflowState
import app.andy.model.WorktreeBaseOption
import app.andy.model.WorktreeDeleteOutcome
import app.andy.model.WorktreeMergeOutcome
import app.andy.model.WorktreeNode
import app.andy.service.AgentRunService
import app.andy.service.CommandResult
import app.andy.service.ProjectWorkflowService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Stable [AgentRunService] / [ProjectWorkflowService] facade whose active backend can switch
 * between local (embedded or local andyd) and a remote [McpAgentRunClient] without replacing
 * [app.andy.service.AndyServices].
 *
 * Outer StateFlows stay identity-stable so Compose collectors survive connect/disconnect.
 */
class SwappableAgentBackend(
    initial: AgentRunService,
    private val scope: CoroutineScope,
) : AgentRunService, ProjectWorkflowService {
    init {
        require(initial is ProjectWorkflowService) {
            "SwappableAgentBackend initial backend must also implement ProjectWorkflowService"
        }
    }

    private val active = AtomicReference(initial)

    private val _tasks = MutableStateFlow(initial.tasks.value)
    private val _cliStatuses = MutableStateFlow(initial.cliStatuses.value)
    private val _providerModels = MutableStateFlow(initial.providerModels.value)
    private val _providerQuotas = MutableStateFlow(initial.providerQuotas.value)
    private val _quotaAccess = MutableStateFlow(initial.quotaAccess.value)
    private val _providerDefaults = MutableStateFlow(initial.providerDefaults.value)
    private val _lastUsedAgent = MutableStateFlow(initial.lastUsedAgent.value)
    private val _localModelBackends = MutableStateFlow(initial.localModelBackends.value)
    private val _interactiveTerminalTaskIds = MutableStateFlow(initial.interactiveTerminalTaskIds.value)
    private val _attachedTerminalTaskIds = MutableStateFlow(initial.attachedTerminalTaskIds.value)
    private val _projects = MutableStateFlow((initial as ProjectWorkflowService).projects.value)

    private var mirrorJob: Job? = null

    init {
        restartMirrors(initial)
    }

    fun switchTo(next: AgentRunService) {
        require(next is ProjectWorkflowService) {
            "SwappableAgentBackend next backend must also implement ProjectWorkflowService"
        }
        active.set(next)
        restartMirrors(next)
    }

    fun activeBackend(): AgentRunService = active.get()

    fun terminalHost(): DesktopAgentRunService? = when (val cur = active.get()) {
        is McpAgentRunClient -> cur.terminalHost()
        is DesktopAgentRunService -> cur
        else -> null
    }

    fun reconcileStaleActiveTaskIfNeeded(taskId: String) {
        (active.get() as? McpAgentRunClient)?.reconcileStaleActiveTaskIfNeeded(taskId)
    }

    private fun restartMirrors(backend: AgentRunService) {
        mirrorJob?.cancel()
        val workflows = backend as ProjectWorkflowService
        _tasks.value = backend.tasks.value
        _cliStatuses.value = backend.cliStatuses.value
        _providerModels.value = backend.providerModels.value
        _providerQuotas.value = backend.providerQuotas.value
        _quotaAccess.value = backend.quotaAccess.value
        _providerDefaults.value = backend.providerDefaults.value
        _lastUsedAgent.value = backend.lastUsedAgent.value
        _localModelBackends.value = backend.localModelBackends.value
        _interactiveTerminalTaskIds.value = backend.interactiveTerminalTaskIds.value
        _attachedTerminalTaskIds.value = backend.attachedTerminalTaskIds.value
        _projects.value = workflows.projects.value
        mirrorJob = scope.launch {
            launch { backend.tasks.collectLatest { _tasks.value = it } }
            launch { backend.cliStatuses.collectLatest { _cliStatuses.value = it } }
            launch { backend.providerModels.collectLatest { _providerModels.value = it } }
            launch { backend.providerQuotas.collectLatest { _providerQuotas.value = it } }
            launch { backend.quotaAccess.collectLatest { _quotaAccess.value = it } }
            launch { backend.providerDefaults.collectLatest { _providerDefaults.value = it } }
            launch { backend.lastUsedAgent.collectLatest { _lastUsedAgent.value = it } }
            launch { backend.localModelBackends.collectLatest { _localModelBackends.value = it } }
            launch { backend.interactiveTerminalTaskIds.collectLatest { _interactiveTerminalTaskIds.value = it } }
            launch { backend.attachedTerminalTaskIds.collectLatest { _attachedTerminalTaskIds.value = it } }
            launch { workflows.projects.collectLatest { _projects.value = it } }
        }
    }

    private fun runs(): AgentRunService = active.get()
    private fun workflows(): ProjectWorkflowService = active.get() as ProjectWorkflowService

    override val tasks: StateFlow<List<AgentTask>> = _tasks.asStateFlow()
    override val cliStatuses: StateFlow<List<AgentCliStatus>> = _cliStatuses.asStateFlow()
    override val providerModels: StateFlow<Map<AgentKind, List<AgentModelOption>>> = _providerModels.asStateFlow()
    override val providerQuotas: StateFlow<Map<AgentKind, AgentProviderQuota>> = _providerQuotas.asStateFlow()
    override val quotaAccess: StateFlow<AgentQuotaAccess> = _quotaAccess.asStateFlow()
    override val providerDefaults: StateFlow<Map<AgentKind, AgentProviderDefaults>> = _providerDefaults.asStateFlow()
    override val lastUsedAgent: StateFlow<AgentKind?> = _lastUsedAgent.asStateFlow()
    override val localModelBackends: StateFlow<Map<AgentKind, Boolean>> = _localModelBackends.asStateFlow()
    override val interactiveTerminalTaskIds: StateFlow<Set<String>> = _interactiveTerminalTaskIds.asStateFlow()
    override val attachedTerminalTaskIds: StateFlow<Set<String>> = _attachedTerminalTaskIds.asStateFlow()
    override val projects: StateFlow<Map<String, ProjectWorkflowState>> = _projects.asStateFlow()

    override suspend fun refreshProviderQuotas() = runs().refreshProviderQuotas()
    override fun setQuotaAccess(agent: AgentKind, enabled: Boolean) = runs().setQuotaAccess(agent, enabled)
    override fun setProviderLane(agent: AgentKind, lane: AgentLaneKind) = runs().setProviderLane(agent, lane)
    override fun skills(agent: AgentKind, directory: String?): StateFlow<List<AgentSkill>> =
        runs().skills(agent, directory)
    override fun slashCommands(agent: AgentKind, directory: String?): StateFlow<List<AgentSlashCommand>> =
        runs().slashCommands(agent, directory)
    override fun refreshSlashCommands(agent: AgentKind, directory: String?) =
        runs().refreshSlashCommands(agent, directory)
    override fun knownSkillNames(directory: String?): StateFlow<Set<String>> =
        runs().knownSkillNames(directory)
    override fun refreshSkills(agent: AgentKind, directory: String?) =
        runs().refreshSkills(agent, directory)
    override suspend fun createAndStart(draft: AgentTaskDraft): AgentTask = runs().createAndStart(draft)
    override fun stop(taskId: String) = runs().stop(taskId)
    override fun completeWorkflowRun(taskId: String) = runs().completeWorkflowRun(taskId)
    override suspend fun retry(taskId: String) = runs().retry(taskId)
    override fun resume(
        taskId: String,
        followUp: String,
        imagePaths: List<String>,
        skills: List<AgentSkill>,
        contextBundleIds: List<String>,
        provenance: AgentContextualProvenance?,
    ) = runs().resume(taskId, followUp, imagePaths, skills, contextBundleIds, provenance)
    override fun reattachSession(taskId: String) = runs().reattachSession(taskId)
    override fun canReattachSession(taskId: String): Boolean = runs().canReattachSession(taskId)
    override fun isTerminalLive(taskId: String): Boolean = runs().isTerminalLive(taskId)
    override fun isLaneLive(taskId: String): Boolean = runs().isLaneLive(taskId)
    override fun sessionRootPid(taskId: String): Long? = runs().sessionRootPid(taskId)
    override fun isViewing(taskId: String): Boolean = runs().isViewing(taskId)
    override fun setAppForeground(foreground: Boolean) = runs().setAppForeground(foreground)
    override fun respondToUserInput(taskId: String, requestId: String, answers: Map<String, String>) =
        runs().respondToUserInput(taskId, requestId, answers)
    override fun setAcpSessionMode(taskId: String, modeId: String) = runs().setAcpSessionMode(taskId, modeId)
    override fun queueFollowUp(
        taskId: String,
        followUp: String,
        imagePaths: List<String>,
        skills: List<AgentSkill>,
        contextBundleIds: List<String>,
        provenance: AgentContextualProvenance?,
    ) = runs().queueFollowUp(taskId, followUp, imagePaths, skills, contextBundleIds, provenance)
    override fun removeQueuedFollowUp(taskId: String, queueIndex: Int) =
        runs().removeQueuedFollowUp(taskId, queueIndex)
    override fun sendNextQueuedFollowUp(taskId: String) = runs().sendNextQueuedFollowUp(taskId)
    override fun updateGoal(taskId: String, goal: String?) = runs().updateGoal(taskId, goal)
    override fun updatePlanMode(taskId: String, planMode: Boolean) = runs().updatePlanMode(taskId, planMode)
    override suspend fun delete(taskId: String, removeWorktree: Boolean, force: Boolean): WorktreeDeleteOutcome =
        runs().delete(taskId, removeWorktree, force)
    override suspend fun keepTemporaryChat(taskId: String) = runs().keepTemporaryChat(taskId)
    override fun updateAutomationNotifySuppress(taskId: String, suppress: Boolean) =
        runs().updateAutomationNotifySuppress(taskId, suppress)
    override suspend fun cleanupOwnedWorktree(taskId: String) = runs().cleanupOwnedWorktree(taskId)
    override fun markRead(taskId: String) = runs().markRead(taskId)
    override fun markUnread(taskId: String) = runs().markUnread(taskId)
    override fun setChatViewing(taskId: String?, viewing: Boolean) = runs().setChatViewing(taskId, viewing)
    override fun releaseTerminalViewer(taskId: String) = runs().releaseTerminalViewer(taskId)
    override fun archive(taskId: String) = runs().archive(taskId)
    override fun unarchive(taskId: String) = runs().unarchive(taskId)
    override fun events(taskId: String): StateFlow<List<AgentEvent>> = runs().events(taskId)
    override fun interactiveResumeCommand(taskId: String): String? = runs().interactiveResumeCommand(taskId)
    override fun providerAppContinuationLabel(taskId: String): String? =
        runs().providerAppContinuationLabel(taskId)
    override suspend fun openInProviderApp(taskId: String): CommandResult = runs().openInProviderApp(taskId)
    override suspend fun openInTerminal(taskId: String): CommandResult = runs().openInTerminal(taskId)
    override suspend fun openSkill(path: String): CommandResult = runs().openSkill(path)
    override suspend fun worktreeDiffSummary(taskId: String): String? = runs().worktreeDiffSummary(taskId)
    override suspend fun changeSummary(taskId: String): AgentChangeSummary? = runs().changeSummary(taskId)
    override suspend fun fileDiff(taskId: String, relativePath: String): AgentFileDiff? =
        runs().fileDiff(taskId, relativePath)
    override suspend fun undoFileChanges(
        taskId: String,
        batchId: String,
        groupedBatchIds: List<String>,
    ): CommandResult =
        runs().undoFileChanges(taskId, batchId, groupedBatchIds)
    override suspend fun undoChangeSnapshot(taskId: String, snapshot: AgentThreadChangeSnapshot): CommandResult =
        runs().undoChangeSnapshot(taskId, snapshot)
    override suspend fun refreshCliStatuses() = runs().refreshCliStatuses()
    override suspend fun isGitRepo(dir: String): Boolean = runs().isGitRepo(dir)
    override suspend fun currentBranch(dir: String): String? = runs().currentBranch(dir)
    override suspend fun worktreeBaseOptions(originDir: String): List<WorktreeBaseOption> =
        runs().worktreeBaseOptions(originDir)
    override suspend fun worktreeTree(originDir: String): List<WorktreeNode> = runs().worktreeTree(originDir)
    override fun mergeCommand(targetDir: String, branch: String): String = runs().mergeCommand(targetDir, branch)
    override suspend fun mergeBranch(
        targetDir: String,
        branch: String,
        sourceWorktreePath: String?,
    ): WorktreeMergeOutcome = runs().mergeBranch(targetDir, branch, sourceWorktreePath)
    override suspend fun abortMerge(targetDir: String): Result<Unit> = runs().abortMerge(targetDir)

    override suspend fun projectContextDir(projectId: String): String? = workflows().projectContextDir(projectId)
    override suspend fun ensureProject(projectId: String) = workflows().ensureProject(projectId)
    override suspend fun updateScratchpad(projectId: String, text: String) =
        workflows().updateScratchpad(projectId, text)
    override suspend fun updateProfile(projectId: String, kind: ProjectTaskKind, profile: ProjectAgentProfile) =
        workflows().updateProfile(projectId, kind, profile)
    override suspend fun saveSpec(draft: ProjectSpecDraft): String = workflows().saveSpec(draft)
    override suspend fun runSpec(taskId: String, revisionRequest: String?) =
        workflows().runSpec(taskId, revisionRequest)
    override suspend fun saveBuildPair(draft: ProjectBuildPairDraft): String = workflows().saveBuildPair(draft)
    override suspend fun startBuildPair(buildTaskId: String) = workflows().startBuildPair(buildTaskId)
    override fun pauseBuildPair(buildTaskId: String) = workflows().pauseBuildPair(buildTaskId)
    override fun stopBuildPair(buildTaskId: String) = workflows().stopBuildPair(buildTaskId)
    override suspend fun resumeBuildPair(buildTaskId: String) = workflows().resumeBuildPair(buildTaskId)
    override suspend fun startRecoveryFollowUp(
        buildTaskId: String,
        followUp: String,
        imagePaths: List<String>,
    ): String? = workflows().startRecoveryFollowUp(buildTaskId, followUp, imagePaths)
    override suspend fun startRecoveryReview(buildTaskId: String): String? =
        workflows().startRecoveryReview(buildTaskId)
    override suspend fun deleteTask(taskId: String, cascade: Boolean) = workflows().deleteTask(taskId, cascade)
    override suspend fun deleteProject(projectId: String) = workflows().deleteProject(projectId)
}
