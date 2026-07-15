package app.andy.service

import app.andy.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

private const val BrowserUnavailable = "This host-only feature is unavailable in Andy Web."
private fun unavailable() = CommandResult.failure(BrowserUnavailable)

object UnavailableAvdService : AvdService {
    override suspend fun listSystemImages() = emptyList<SystemImage>()
    override suspend fun listProfiles() = emptyList<AvdProfile>()
    override suspend fun listVirtualDevices() = emptyList<VirtualDevice>()
    override suspend fun createVirtualDevice(name: String, profileId: String, systemImagePackage: String) = unavailable()
    override suspend fun createVirtualDevice(config: AvdCreationConfig) = unavailable()
    override suspend fun startVirtualDevice(name: String) = unavailable()
    override suspend fun coldBootVirtualDevice(name: String) = unavailable()
    override suspend fun stopVirtualDevice(name: String) = unavailable()
    override suspend fun wipeVirtualDevice(name: String) = unavailable()
    override suspend fun deleteVirtualDevice(name: String) = unavailable()
    override suspend fun cloneVirtualDevice(sourceName: String, newName: String) = unavailable()
    override suspend fun installSystemImage(packageId: String) = unavailable()
    override suspend fun uninstallSystemImage(packageId: String) = unavailable()
    override suspend fun listSnapshots(avdName: String) = emptyList<EmulatorSnapshot>()
    override suspend fun saveSnapshot(avdName: String, snapshotName: String) = unavailable()
    override suspend fun restoreSnapshot(avdName: String, snapshotName: String) = unavailable()
    override suspend fun deleteSnapshot(avdName: String, snapshotName: String) = unavailable()
    override suspend fun renameSnapshot(avdName: String, oldName: String, newName: String) = unavailable()
}

object UnavailableLogcatService : LogcatService {
    override fun stream(serial: String, filter: LogcatFilter): Flow<List<LogcatEntry>> = flowOf(emptyList())
    override suspend fun snapshot(serial: String, filter: LogcatFilter, limit: Int) = emptyList<LogcatEntry>()
    override suspend fun clear(serial: String) = Unit
}

object UnavailableIntentService : IntentService {
    override fun buildCommand(draft: IntentDraft) = emptyList<String>()
    override suspend fun send(serial: String, draft: IntentDraft) = unavailable()
}

object UnavailableAppService : AppService {
    override suspend fun listApps(serial: String) = emptyList<AndroidApp>()
    override suspend fun getAppDetails(serial: String, packageName: String) = AndroidAppDetails()
    override suspend fun launch(serial: String, packageName: String) = unavailable()
    override suspend fun launchActivity(serial: String, packageName: String, activityName: String) = unavailable()
    override suspend fun stop(serial: String, packageName: String) = unavailable()
    override suspend fun clearData(serial: String, packageName: String) = unavailable()
    override suspend fun resetPermissions(serial: String, packageName: String) = unavailable()
    override suspend fun uninstall(serial: String, packageName: String) = unavailable()
    override suspend fun install(serial: String, apkPath: String, replace: Boolean) = unavailable()
    override suspend fun listPermissions(serial: String, packageName: String) = emptyList<AndroidPermission>()
    override suspend fun listActivities(serial: String, packageName: String) = emptyList<AndroidActivity>()
    override suspend fun getIcon(serial: String, packageName: String): ByteArray? = null
}

object UnavailableFileService : FileService {
    override suspend fun list(serial: String, path: String) = emptyList<DeviceFile>()
    override suspend fun pull(serial: String, remotePath: String, localPath: String) = unavailable()
    override suspend fun push(serial: String, localPath: String, remotePath: String) = unavailable()
    override suspend fun delete(serial: String, remotePath: String) = unavailable()
}

object UnavailableHostFileService : HostFileService {
    override suspend fun list(path: String) = emptyList<HostFileEntry>()
    override suspend fun read(path: String): HostFileDocument = error(BrowserUnavailable)
    override suspend fun save(path: String, content: String, expectedModifiedMillis: Long): HostFileSaveResult =
        HostFileSaveResult.Failed(BrowserUnavailable)
    override suspend fun indexStatus(root: String) = HostIndexStatus(root, 0, 0, false, BrowserUnavailable, 0)
    override fun indexRoot(root: String) = flowOf(HostIndexStatus(root, 0, 0, false, BrowserUnavailable, 0))
    override suspend fun search(query: String, mode: HostSearchMode, roots: List<String>, limit: Int) = emptyList<HostSearchResult>()
}

object UnavailableProxyService : ProxyService {
    override val exchanges = flowOf(emptyList<NetworkExchange>())
    override val status = flowOf("Unavailable in Andy Web")
    override val warnings = flowOf(emptyList<ProxyWarning>())
    override val clientConnectionCount = flowOf(0)
    override suspend fun detectMitmproxy() = unavailable()
    override suspend fun ensureCertificateAuthority() = unavailable()
    override suspend fun certificateAuthorityPath() = ""
    override suspend fun start(port: Int, rules: List<ProxyRule>, options: ProxyStartOptions) = unavailable()
    override suspend fun updateRules(rules: List<ProxyRule>) = unavailable()
    override suspend fun clearTraffic() = unavailable()
    override suspend fun stop() = unavailable()
    override suspend fun resolveDeviceProxyHost(serial: String) = ""
    override suspend fun configureDeviceProxy(serial: String, host: String, port: Int) = unavailable()
    override suspend fun clearDeviceProxy(serial: String) = unavailable()
    override suspend fun diagnoseDeviceProxyRoute(serial: String, host: String, port: Int): NetworkRouteDiagnostics = error(BrowserUnavailable)
    override suspend fun openVpnSettings(serial: String) = unavailable()
    override suspend fun prepareUserCertificateInstall(serial: String) = unavailable()
    override suspend fun installSystemCertificateAuthority(serial: String) = unavailable()
    override suspend fun activatePersistedCertificateAuthority(serial: String) = unavailable()
    override suspend fun isCertificateInstalled(serial: String) = false
    override suspend fun isDeviceProxyConfigured(serial: String, host: String, port: Int) = false
}

object UnavailableMetricsService : MetricsService {
    override fun stream(serial: String, packageName: String?): Flow<PerformanceSample> = emptyFlow()
}

object UnavailableAccessibilityService : AccessibilityService {
    override suspend fun dump(serial: String): AccessibilityNode? = null
}

object UnavailableBugService : BugService {
    override val status = flowOf(BugCaptureStatus(message = "Browser capture ready"))
    override suspend fun startCapture(serial: String, device: AndroidDevice?) = Unit
    override suspend fun stopCapture() = Unit
    override fun recordAction(kind: String, label: String, detail: String?) = Unit
    override suspend fun saveBug(draft: BugCaptureDraft, device: AndroidDevice?): BugReport = error(BrowserUnavailable)
    override suspend fun listBugs() = emptyList<BugReport>()
    override suspend fun loadBug(id: String): BugReport? = null
    override suspend fun loadBugLog(id: String) = ""
    override suspend fun deleteBug(id: String) = false
    override suspend fun exportBug(id: String): String? = null
    override fun playbackFrames(id: String, startFrameIndex: Int): Flow<MirrorFrame> = emptyFlow()
    override suspend fun bugVideoFrameCount(id: String) = 0
    override suspend fun loadBugVideoFrame(id: String, frameIndex: Int): MirrorFrame? = null
}

object UnavailableArtifactService : ArtifactService {
    override suspend fun saveScreenshot(serial: String, suggestedName: String) = unavailable()
    override suspend fun saveBugReport(serial: String, suggestedName: String) = unavailable()
}

class InMemoryWorkspaceStore(initial: WorkspaceState = WorkspaceState()) : WorkspaceStore {
    private var value = initial
    override suspend fun load() = value
    override suspend fun save(state: WorkspaceState) { value = state }
}

object EmptyActionConfigStore : ActionConfigStore {
    override suspend fun load() = ActionsConfig()
    override suspend fun save(config: ActionsConfig) = Unit
}

object UnavailableUpdateService : AppUpdateService {
    override val state = MutableStateFlow<AppUpdateState>(AppUpdateState.Current)
    override val pendingInstallConfirmation = MutableStateFlow<AvailableUpdate?>(null)
    override suspend fun checkForUpdates(onFailure: (Throwable) -> Unit) = Unit
    override suspend fun installAvailableUpdate(onMessage: (String) -> Unit) = Unit
    override fun respondToInstallConfirmation(install: Boolean) = Unit
}

object UnavailableMcpService : McpServerService {
    override val status = flowOf("Unavailable in Andy Web")
    override val running = flowOf(false)
    override suspend fun start(port: Int) = unavailable()
    override suspend fun stop() = unavailable()
    override fun getSnippet(clientName: String, port: Int) = ""
    override fun getClients() = emptyList<String>()
    override fun isAutoWriteSupported(clientName: String) = false
    override fun writeConfig(clientName: String, port: Int) = false
    override fun getToolNames() = emptyList<String>()
}

object UnavailableActionRunService : ActionRunService {
    override val running: StateFlow<List<RunningAction>> = MutableStateFlow(emptyList())
    override fun openShell(project: ActionProject) = ""
    override fun run(project: ActionProject, action: ProjectAction) = ""
    override fun stop(runId: String) = Unit
    override fun clear(runId: String) = Unit
}

object UnavailableAgentRunService : AgentRunService {
    override val tasks = MutableStateFlow(emptyList<AgentTask>())
    override val cliStatuses = MutableStateFlow(emptyList<AgentCliStatus>())
    override val providerQuotas = MutableStateFlow(emptyMap<AgentKind, AgentProviderQuota>())
    override val quotaAccess = MutableStateFlow(AgentQuotaAccess())
    override val providerDefaults = MutableStateFlow(emptyMap<AgentKind, AgentProviderDefaults>())
    override val lastUsedAgent = MutableStateFlow<AgentKind?>(null)
    override suspend fun refreshProviderQuotas() = Unit
    override fun setQuotaAccess(agent: AgentKind, enabled: Boolean) = Unit
    override fun skills(agent: AgentKind, directory: String?) = MutableStateFlow(emptyList<AgentSkill>())
    override fun refreshSkills(agent: AgentKind, directory: String?) = Unit
    override suspend fun createAndStart(draft: AgentTaskDraft): AgentTask = error(BrowserUnavailable)
    override suspend fun startImplementation(taskId: String) = Unit
    override fun stop(taskId: String) = Unit
    override suspend fun retry(taskId: String) = Unit
    override fun resume(taskId: String, followUp: String, imagePaths: List<String>, skills: List<AgentSkill>) = Unit
    override fun respondToUserInput(taskId: String, requestId: String, answers: Map<String, String>) = Unit
    override fun queueFollowUp(taskId: String, followUp: String, imagePaths: List<String>, skills: List<AgentSkill>) = Unit
    override fun removeQueuedFollowUp(taskId: String, queueIndex: Int) = Unit
    override fun updateGoal(taskId: String, goal: String?) = Unit
    override suspend fun delete(taskId: String, removeWorktree: Boolean) = Unit
    override fun markRead(taskId: String) = Unit
    override fun markUnread(taskId: String) = Unit
    override fun events(taskId: String) = MutableStateFlow(emptyList<AgentEvent>())
    override fun interactiveResumeCommand(taskId: String): String? = null
    override suspend fun openInTerminal(taskId: String) = unavailable()
    override suspend fun openSkill(path: String) = unavailable()
    override suspend fun worktreeDiffSummary(taskId: String): String? = null
    override suspend fun changeSummary(taskId: String): AgentChangeSummary? = null
    override suspend fun fileDiff(taskId: String, relativePath: String): AgentFileDiff? = null
    override suspend fun refreshCliStatuses() = Unit
    override suspend fun isGitRepo(dir: String) = false
}

object UnavailableProjectWorkflowService : ProjectWorkflowService {
    override val projects = MutableStateFlow(emptyMap<String, ProjectWorkflowState>())
    override suspend fun ensureProject(projectId: String) = Unit
    override suspend fun updateScratchpad(projectId: String, text: String) = Unit
    override suspend fun updateProfile(projectId: String, kind: ProjectTaskKind, profile: ProjectAgentProfile) = Unit
    override suspend fun saveSpec(draft: ProjectSpecDraft): String = error(BrowserUnavailable)
    override suspend fun runSpec(taskId: String, revisionRequest: String?) = error(BrowserUnavailable)
    override suspend fun saveBuildPair(draft: ProjectBuildPairDraft): String = error(BrowserUnavailable)
    override suspend fun startBuildPair(buildTaskId: String) = Unit
    override fun pauseBuildPair(buildTaskId: String) = Unit
    override fun stopBuildPair(buildTaskId: String) = Unit
    override suspend fun resumeBuildPair(buildTaskId: String) = Unit
    override suspend fun startRecoveryFollowUp(buildTaskId: String, followUp: String): String? = BrowserUnavailable
    override suspend fun startRecoveryReview(buildTaskId: String): String? = BrowserUnavailable
    override suspend fun deleteTask(taskId: String, cascade: Boolean) = Unit
    override suspend fun deleteProject(projectId: String) = Unit
}
