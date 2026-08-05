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

object UnavailableIosDeviceService : IosDeviceService {
    override suspend fun listTargets() = emptyList<IosTarget>()
    override suspend fun boot(udid: String) = unavailable()
    override suspend fun shutdown(udid: String) = unavailable()
    override suspend fun openInSimulatorApp(udid: String) = unavailable()
    override suspend fun iosSimAvailable() = false
    override suspend fun iosSimDiagnostic() = BrowserUnavailable
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
    override suspend fun focusedPackage(serial: String): String? = null
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

object UnavailableCrashInspectorService : CrashInspectorService {
    override suspend fun listCrashes(serial: String) = emptyList<CrashRecord>()
    override suspend fun loadCrash(serial: String, id: String) = ""
    override suspend fun exportCrash(serial: String, id: String, localPath: String) = unavailable()
}

object UnavailableHeapDumpService : HeapDumpService {
    override suspend fun capture(serial: String, packageName: String, localPath: String) =
        Result.failure<HeapDumpInfo>(Exception(BrowserUnavailable))
    override suspend fun listCaptures() = emptyList<HeapDumpInfo>()
    override suspend fun deleteCapture(id: String) = false
    override suspend fun revealCapture(id: String) = unavailable()
}

object UnavailableAccessibilityService : AccessibilityService {
    override suspend fun dump(serial: String): AccessibilityNode? = null
}

object UnavailableViewHierarchyService : ViewHierarchyService {
    override suspend fun capture(serial: String, options: HierarchyOptions) =
        Result.failure<HierarchySnapshot>(Exception(BrowserUnavailable))
}

object UnavailableBugService : BugService {
    override val status = flowOf(BugCaptureStatus(message = "Browser capture ready"))
    override suspend fun startCapture(serial: String, device: AndroidDevice?) = Unit
    override suspend fun stopCapture() = Unit
    override suspend fun beginRecording() = Unit
    override fun recordAction(kind: String, label: String, detail: String?) = Unit
    override fun recordScreenshot(pngBytes: ByteArray, label: String, detail: String?) = Unit
    override suspend fun loadBugTimeline(id: String): InvestigationTimeline? = null
    override suspend fun saveBug(draft: BugCaptureDraft, device: AndroidDevice?): BugReport = error(BrowserUnavailable)
    override suspend fun saveRecording(device: AndroidDevice?): BugReport = error(BrowserUnavailable)
    override suspend fun listBugs() = emptyList<BugReport>()
    override suspend fun listRecordings() = emptyList<BugReport>()
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

object UnavailableRecordingExportService : RecordingExportService {
    override suspend fun export(request: RecordingExportRequest, localPath: String) =
        Result.failure<ExportedClip>(Exception(BrowserUnavailable))
}

object UnavailableTracingService : TracingService {
    override val status = MutableStateFlow(TraceRecordingStatus())
    override val recordings = MutableStateFlow(emptyList<TraceRecording>())
    override suspend fun checkSupport(serial: String) = unavailable()
    override suspend fun start(serial: String, configTextProto: String, name: String, presetId: String?) = unavailable()
    override suspend fun stop() = unavailable()
    override suspend fun refreshRecordings() = Unit
    override suspend fun deleteRecording(id: String) = false
    override suspend fun revealRecording(id: String) = unavailable()
    override suspend fun importConfig(sourcePath: String) = unavailable()
    override suspend fun listUserConfigs() = emptyList<TraceUserConfig>()
    override suspend fun loadUserConfig(id: String): String? = null
    override suspend fun saveUserConfig(name: String, content: String) = unavailable()
    override suspend fun deleteUserConfig(id: String) = false
    override suspend fun retryPull() = unavailable()
}

object UnavailableTraceViewerService : TraceViewerService {
    override suspend fun openExternally(traceId: String) = unavailable()
    override fun shutdown() = Unit
}

object UnavailableSharedPrefsService : SharedPrefsService {
    override suspend fun listFiles(serial: String, packageName: String) = Result.failure<List<String>>(Exception(BrowserUnavailable))
    override suspend fun read(serial: String, packageName: String, fileName: String) = Result.failure<List<PrefEntry>>(Exception(BrowserUnavailable))
    override suspend fun upsert(serial: String, packageName: String, fileName: String, entry: PrefEntry) = unavailable()
    override suspend fun delete(serial: String, packageName: String, fileName: String, key: String) = unavailable()
}

object UnavailableAppDatabaseService : AppDatabaseService {
    override suspend fun listDatabases(serial: String, packageName: String) = Result.failure<List<AppDatabaseInfo>>(Exception(BrowserUnavailable))
    override suspend fun listTables(serial: String, packageName: String, dbName: String) = Result.failure<List<String>>(Exception(BrowserUnavailable))
    override suspend fun tableRowCounts(
        serial: String,
        packageName: String,
        dbName: String,
        tables: List<String>,
    ) = Result.failure<Map<String, Long>>(Exception(BrowserUnavailable))
    override suspend fun tableInfo(serial: String, packageName: String, dbName: String, tableName: String) =
        Result.failure<DbTableInfo>(Exception(BrowserUnavailable))
    override suspend fun browseTable(
        serial: String,
        packageName: String,
        dbName: String,
        tableName: String,
        limit: Int,
        offset: Int,
    ) = Result.failure<DbQueryResult>(Exception(BrowserUnavailable))
    override suspend fun query(serial: String, packageName: String, dbName: String, sql: String, limit: Int) =
        Result.failure<DbQueryResult>(Exception(BrowserUnavailable))
    override suspend fun updateCell(
        serial: String,
        packageName: String,
        dbName: String,
        tableName: String,
        column: String,
        newValue: String?,
        rowId: Long?,
        primaryKeyColumn: String?,
        primaryKeyValue: String?,
    ) = unavailable()
    override suspend fun pullToHost(serial: String, packageName: String, dbName: String, localPath: String) = unavailable()
    override suspend fun listSavedQueries(packageName: String) = emptyList<SavedSqlQuery>()
    override suspend fun saveQuery(packageName: String, name: String, sql: String) = unavailable()
    override suspend fun deleteQuery(packageName: String, id: String) = false
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
    override val providerModels = MutableStateFlow(emptyMap<AgentKind, List<AgentModelOption>>())
    override val providerQuotas = MutableStateFlow(emptyMap<AgentKind, AgentProviderQuota>())
    override val quotaAccess = MutableStateFlow(AgentQuotaAccess())
    override val providerDefaults = MutableStateFlow(emptyMap<AgentKind, AgentProviderDefaults>())
    override val lastUsedAgent = MutableStateFlow<AgentKind?>(null)
    override suspend fun refreshProviderQuotas() = Unit
    override fun setQuotaAccess(agent: AgentKind, enabled: Boolean) = Unit
    override fun skills(agent: AgentKind, directory: String?) = MutableStateFlow(emptyList<AgentSkill>())
    override fun refreshSkills(agent: AgentKind, directory: String?) = Unit
    override suspend fun createAndStart(draft: AgentTaskDraft): AgentTask = error(BrowserUnavailable)
    override fun stop(taskId: String) = Unit
    override fun completeWorkflowRun(taskId: String) = Unit
    override suspend fun retry(taskId: String) = Unit
    override fun resume(
        taskId: String,
        followUp: String,
        imagePaths: List<String>,
        skills: List<AgentSkill>,
        contextBundleIds: List<String>,
        provenance: AgentContextualProvenance?,
    ) = Unit

    override fun reattachSession(taskId: String) = Unit

    override fun canReattachSession(taskId: String): Boolean = false
    override fun isViewing(taskId: String): Boolean = false
    override fun respondToUserInput(taskId: String, requestId: String, answers: Map<String, String>) = Unit
    override fun queueFollowUp(
        taskId: String,
        followUp: String,
        imagePaths: List<String>,
        skills: List<AgentSkill>,
        contextBundleIds: List<String>,
        provenance: AgentContextualProvenance?,
    ) = Unit
    override fun removeQueuedFollowUp(taskId: String, queueIndex: Int) = Unit
    override fun updateGoal(taskId: String, goal: String?) = Unit
    override suspend fun delete(taskId: String, removeWorktree: Boolean) = Unit
    override fun markRead(taskId: String) = Unit
    override fun markUnread(taskId: String) = Unit
    override fun setChatViewing(taskId: String?, viewing: Boolean) = Unit
    override fun archive(taskId: String) = Unit
    override fun unarchive(taskId: String) = Unit
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

object UnavailableAgentRetentionService : AgentRetentionService {
    override suspend fun runSweepNow() = RetentionSweepResult(0, 0, 0, 0)
}

object UnavailableProjectWorkflowService : ProjectWorkflowService {
    override val projects = MutableStateFlow(emptyMap<String, ProjectWorkflowState>())
    override suspend fun projectContextDir(projectId: String): String? = null
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
    override suspend fun startRecoveryFollowUp(
        buildTaskId: String,
        followUp: String,
        imagePaths: List<String>,
    ): String? = BrowserUnavailable
    override suspend fun startRecoveryReview(buildTaskId: String): String? = BrowserUnavailable
    override suspend fun deleteTask(taskId: String, cascade: Boolean) = Unit
    override suspend fun deleteProject(projectId: String) = Unit
}

object UnavailableKanbanService : KanbanService {
    override val board = MutableStateFlow(KanbanBoard())

    override fun addLane(name: String) = Unit
    override fun renameLane(laneId: String, name: String) = Unit
    override fun deleteLane(laneId: String) = Unit
    override fun moveLane(laneId: String, direction: KanbanLaneDirection) = Unit
    override fun addCard(laneId: String, title: String, description: String, tags: List<String>) = Unit
    override fun updateCard(cardId: String, title: String, description: String, tags: List<String>) = Unit
    override fun deleteCard(cardId: String) = Unit
    override fun moveCard(cardId: String, toLaneId: String, toIndex: Int) = Unit
}

object UnavailableDhuService : DhuService {
    private val emptyReadiness = DhuReadiness(
        hostKind = DhuHostKind.Unsupported,
        checks = listOf(
            DhuReadinessCheck(
                id = "platform",
                label = "Desktop host",
                status = DhuCheckStatus.Unsupported,
                detail = BrowserUnavailable,
                remediation = "Use Andy Desktop on macOS, Windows, or Linux.",
            ),
        ),
    )
    override val readiness = MutableStateFlow(emptyReadiness)
    override val session = MutableStateFlow<DhuSession?>(null)
    override val console = MutableStateFlow(DhuConsoleState())
    override val captureFrame = MutableStateFlow<DhuCaptureFrame?>(null)
    override suspend fun refreshReadiness(serial: String?) = emptyReadiness
    override suspend fun start(serial: String) = unavailable()
    override suspend fun stop() = Unit
    override suspend fun sendConsoleCommand(command: String) = unavailable()
    override fun openHelp() = Unit
    override fun openExternalTroubleshooting() = unavailable()
    override fun copyDiagnostics() = emptyReadiness.diagnosticsText()
}
