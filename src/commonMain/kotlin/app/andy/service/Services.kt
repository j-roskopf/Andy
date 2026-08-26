package app.andy.service

import androidx.compose.runtime.Stable
import app.andy.AndyDestination
import app.andy.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

interface DeviceService {
    suspend fun discoverSdk(): SdkDiscovery
    suspend fun listDevices(): List<AndroidDevice>
    suspend fun shell(serial: String, command: List<String>): CommandResult
    /**
     * Sends an emulator console command via `adb -s SERIAL emu …`.
     * Used for foldable posture/hinge and other virtual-device controls.
     */
    suspend fun emu(serial: String, command: List<String>): CommandResult =
        CommandResult.failure("Emulator console commands are unavailable")
    /**
     * Rotates an emulator's physical framebuffer via gRPC (Andy `-qt-hide-window` launches).
     * [quarterTurn] is 0–3 matching Android `user_rotation` / `wm user-rotation lock`.
     */
    suspend fun applyEmulatorDisplayRotation(serial: String, quarterTurn: Int): CommandResult =
        CommandResult.failure("Emulator display rotation is unavailable")
    /**
     * Reads the emulator's virtual-accelerometer quarter turn (0–3), or null when it cannot
     * be read. This is the source of truth for "which way is the device being held" —
     * `wm user-rotation` reports `free` while the sensor drives rotation.
     */
    suspend fun readEmulatorDisplayRotation(serial: String): Int? = null
    suspend fun pair(host: String, port: Int, code: String): CommandResult
    suspend fun connect(host: String, port: Int): CommandResult
    suspend fun disconnect(serial: String): CommandResult
    suspend fun listMdnsServices(): List<MdnsService>
    suspend fun mdnsAvailable(): Boolean
    suspend fun generatePairingQr(content: String): ByteArray?
}

interface IosDeviceService {
    suspend fun listTargets(): List<IosTarget>
    suspend fun boot(udid: String): CommandResult
    suspend fun shutdown(udid: String): CommandResult
    suspend fun openInSimulatorApp(udid: String): CommandResult
    /** Starts Simulator.app in the background so embedded Live can inject HID. */
    suspend fun prepareEmbeddedMirror(udid: String): CommandResult = openInSimulatorApp(udid)
    /**
     * True when Simulator.app has an on-screen device window. Used to detect handoff end after
     * pop-out so Live can resume mirroring. [displayName] matches the window title when known.
     */
    fun hasVisibleSimulatorDeviceWindow(displayName: String? = null): Boolean = false
    /** Best-effort hide of Simulator.app windows after an embedded-mirror handoff ends. */
    fun hideSimulatorApp(): Unit = Unit
    suspend fun iosSimAvailable(): Boolean
    suspend fun iosSimDiagnostic(): String

    /** Runs `xcrun simctl <args>`. */
    suspend fun simctl(args: List<String>): CommandResult =
        CommandResult.failure("simctl is unavailable")

    /** Device types from `simctl list devicetypes -j`. */
    suspend fun listDeviceTypes(): List<IosDeviceType> = emptyList()

    /** Runtimes from `simctl list runtimes -j`. */
    suspend fun listRuntimes(): List<IosRuntime> = emptyList()

    suspend fun createSimulator(name: String, deviceTypeId: String, runtimeId: String? = null): CommandResult =
        CommandResult.failure("Simulator creation is unavailable")

    suspend fun cloneSimulator(udid: String, newName: String): CommandResult =
        CommandResult.failure("Simulator clone is unavailable")

    suspend fun eraseSimulator(udid: String): CommandResult =
        CommandResult.failure("Simulator erase is unavailable")

    suspend fun renameSimulator(udid: String, newName: String): CommandResult =
        CommandResult.failure("Simulator rename is unavailable")

    suspend fun deleteSimulator(udid: String): CommandResult =
        CommandResult.failure("Simulator delete is unavailable")

    suspend fun deleteUnavailableSimulators(): CommandResult =
        CommandResult.failure("simctl delete unavailable is not supported")

    suspend fun deleteUnusedRuntimes(notUsedSinceDays: Int): CommandResult =
        CommandResult.failure("simctl runtime delete is not supported")

    /**
     * Physical-device Developer Mode status from `devicectl device info details`.
     * Null when the probe fails or the target is a simulator.
     */
    suspend fun developerModeStatus(udid: String): IosDeveloperModeStatus? = null

    /**
     * Native-resolution PNG via `simctl io <udid> screenshot`, for the screenshot studio and
     * Dynamic Type sweep (Phase 4.2/4.3). Null when unsupported or capture failed.
     */
    suspend fun captureScreenshot(udid: String): ByteArray? = null

    /**
     * Sends a push notification payload via `simctl push <udid> <bundleId> <payload>` (Phase 4.4).
     */
    suspend fun push(udid: String, bundleId: String, payloadJson: String): CommandResult =
        CommandResult.failure("simctl push is unavailable")

    /** Downloads the latest iOS platform/runtime via `xcodebuild -downloadPlatform iOS` (Phase 5.1). */
    suspend fun downloadPlatform(): CommandResult =
        CommandResult.failure("Platform download is unavailable")
}

interface AvdService {
    suspend fun listSystemImages(): List<SystemImage>
    suspend fun listProfiles(): List<AvdProfile>
    suspend fun listVirtualDevices(): List<VirtualDevice>
    suspend fun createVirtualDevice(name: String, profileId: String, systemImagePackage: String): CommandResult
    suspend fun createVirtualDevice(config: AvdCreationConfig): CommandResult
    suspend fun startVirtualDevice(name: String): CommandResult
    suspend fun coldBootVirtualDevice(name: String): CommandResult
    suspend fun stopVirtualDevice(name: String): CommandResult
    suspend fun wipeVirtualDevice(name: String): CommandResult
    suspend fun deleteVirtualDevice(name: String): CommandResult
    suspend fun cloneVirtualDevice(sourceName: String, newName: String): CommandResult
    suspend fun installSystemImage(packageId: String): CommandResult
    suspend fun uninstallSystemImage(packageId: String): CommandResult
    suspend fun listSnapshots(avdName: String): List<EmulatorSnapshot>
    suspend fun saveSnapshot(avdName: String, snapshotName: String): CommandResult
    suspend fun restoreSnapshot(avdName: String, snapshotName: String): CommandResult
    suspend fun deleteSnapshot(avdName: String, snapshotName: String): CommandResult
    suspend fun renameSnapshot(avdName: String, oldName: String, newName: String): CommandResult
}

/** Fallback for engines that do not track surface visibility; they always present. */
private val AlwaysPresenting: StateFlow<Boolean> = MutableStateFlow(true)

interface MirrorEngine {
    /**
     * The presentation session. This is deliberately low-frequency state: native and web
     * renderers own video frames, while Compose observes only their verified capabilities
     * and telemetry.
     */
    val session: StateFlow<MirrorSession?>

    /**
     * True while at least one Live surface is on screen. Sessions stay warm when this is false,
     * so callers that only matter to a viewer — bug capture's rolling window, CPU frame
     * conversion, presentation telemetry — can idle instead of burning CPU on pixels and logs
     * nobody can see.
     */
    val presenting: StateFlow<Boolean> get() = AlwaysPresenting

    /**
     * Marks a Live surface as on screen; must be balanced with [releasePresentation]. Several
     * surfaces can present the same session at once (Live plus a pop-out), so the engine counts
     * holders rather than tracking a single boolean.
     */
    fun acquirePresentation() = Unit

    fun releasePresentation() = Unit

    /**
     * Legacy CPU frames. These remain available for screenshots, bug capture and deterministic
     * tests, but must not be used as the normal accelerated presentation path.
     */
    val frames: Flow<MirrorFrame>

    /**
     * Annex-B H.264 access units from the live stream when available. Bug capture prefers this
     * bitstream (full stream FPS, no re-encode) and falls back to [frames] when empty.
     */
    val encodedVideo: Flow<EncodedVideoAccessUnit>
        get() = emptyFlow()

    val status: Flow<String>
    suspend fun connect(serial: String, config: MirrorVideoConfig = MirrorVideoConfig()): CommandResult
    /**
     * Release the live session. By default implementations may keep the stream warm briefly so
     * navigating between Live/Design/Accessibility (and back) can reuse scrcpy instead of
     * black-screening. Live and embedded panels no longer disconnect on leave; pass [immediate]
     * for shutdown, device changes, or other cases that must tear down now.
     */
    suspend fun disconnect(immediate: Boolean = false)
    /**
     * Tear down and start a fresh mirror session. Use after foldable display switches where
     * [connect] would otherwise no-op because the video config is unchanged.
     */
    suspend fun reconnect(serial: String, config: MirrorVideoConfig = MirrorVideoConfig()): CommandResult {
        disconnect(immediate = true)
        return connect(serial, config)
    }
    /**
     * Restart scrcpy transport after a fold/unfold display change without tearing down the GPU
     * decode pipeline or Metal presenters. Falls back to [reconnect] when no live session exists.
     */
    suspend fun restartForDisplayChange(serial: String, config: MirrorVideoConfig = MirrorVideoConfig()): CommandResult =
        reconnect(serial, config)
    suspend fun sendInput(input: MirrorInput): CommandResult
    suspend fun screenshot(serial: String): ByteArray?
}

interface LogcatService {
    fun stream(serial: String, filter: LogcatFilter): Flow<List<LogcatEntry>>
    suspend fun snapshot(serial: String, filter: LogcatFilter, limit: Int): List<LogcatEntry>
    suspend fun clear(serial: String)
}

interface IntentService {
    fun buildCommand(draft: IntentDraft): List<String>
    suspend fun send(serial: String, draft: IntentDraft): CommandResult
}

interface AppService {
    suspend fun listApps(serial: String): List<AndroidApp>
    /** Package currently in the foreground on [serial], or null if unknown. */
    suspend fun focusedPackage(serial: String): String?
    suspend fun getAppDetails(serial: String, packageName: String): AndroidAppDetails
    suspend fun launch(serial: String, packageName: String): CommandResult
    suspend fun launchActivity(serial: String, packageName: String, activityName: String): CommandResult
    suspend fun stop(serial: String, packageName: String): CommandResult
    suspend fun clearData(serial: String, packageName: String): CommandResult
    suspend fun resetPermissions(serial: String, packageName: String): CommandResult
    suspend fun uninstall(serial: String, packageName: String): CommandResult
    suspend fun install(serial: String, apkPath: String, replace: Boolean = false): CommandResult
    suspend fun listPermissions(serial: String, packageName: String): List<AndroidPermission>
    suspend fun listActivities(serial: String, packageName: String): List<AndroidActivity>
    suspend fun getIcon(serial: String, packageName: String): ByteArray?
}

interface FileService {
    suspend fun list(serial: String, path: String): List<DeviceFile>
    suspend fun pull(serial: String, remotePath: String, localPath: String): CommandResult
    suspend fun push(serial: String, localPath: String, remotePath: String): CommandResult
    suspend fun delete(serial: String, remotePath: String): CommandResult
}

interface HostFileService {
    suspend fun list(path: String): List<HostFileEntry>
    suspend fun read(path: String): HostFileDocument
    suspend fun save(path: String, content: String, expectedModifiedMillis: Long): HostFileSaveResult
    suspend fun indexStatus(root: String): HostIndexStatus
    fun indexRoot(root: String): Flow<HostIndexStatus>
    suspend fun search(query: String, mode: HostSearchMode, roots: List<String>, limit: Int = 200): List<HostSearchResult>
}

interface ProxyService {
    val exchanges: Flow<List<NetworkExchange>>
    val status: Flow<String>
    val warnings: Flow<List<ProxyWarning>>
    val clientConnectionCount: Flow<Int>
    suspend fun detectMitmproxy(): CommandResult
    suspend fun ensureCertificateAuthority(): CommandResult
    suspend fun certificateAuthorityPath(): String
    suspend fun start(port: Int, rules: List<ProxyRule>, options: ProxyStartOptions = ProxyStartOptions()): CommandResult
    suspend fun updateRules(rules: List<ProxyRule>): CommandResult
    suspend fun clearTraffic(): CommandResult
    suspend fun stop(): CommandResult
    suspend fun resolveDeviceProxyHost(serial: String): String
    suspend fun configureDeviceProxy(serial: String, host: String, port: Int): CommandResult
    suspend fun clearDeviceProxy(serial: String): CommandResult
    suspend fun diagnoseDeviceProxyRoute(serial: String, host: String, port: Int): NetworkRouteDiagnostics
    suspend fun openVpnSettings(serial: String): CommandResult
    suspend fun prepareUserCertificateInstall(serial: String): CommandResult
    suspend fun installSystemCertificateAuthority(serial: String): CommandResult
    suspend fun activatePersistedCertificateAuthority(serial: String): CommandResult
    suspend fun isCertificateInstalled(serial: String): Boolean
    suspend fun isDeviceProxyConfigured(serial: String, host: String, port: Int): Boolean
}

interface MetricsService {
    fun stream(serial: String, packageName: String?): Flow<PerformanceSample>
    /** `dumpsys meminfo <pkg>` broken into Java/native/graphics/code/stack buckets. */
    suspend fun meminfoBreakdown(serial: String, packageName: String): MeminfoBreakdown? = null
    /** `dumpsys batterystats` summarized into wakelocks/alarms/jobs/drain. */
    suspend fun batteryStatsSummary(serial: String, packageName: String? = null): BatteryStatsSummary = BatteryStatsSummary()
}

/**
 * Crash/ANR inspector reading `dumpsys dropbox` and `/data/anr` (§B.2).
 * Defaults to [UnavailableCrashInspectorService].
 */
interface CrashInspectorService {
    suspend fun listCrashes(serial: String): List<CrashRecord>
    suspend fun loadCrash(serial: String, id: String): String
    suspend fun exportCrash(serial: String, id: String, localPath: String): CommandResult
}

/**
 * On-device heap dump capture/management (§B.3), shaped like [TracingService]: capture,
 * list, reveal, delete locally — no built-in analyzer. Defaults to [UnavailableHeapDumpService].
 */
interface HeapDumpService {
    suspend fun capture(serial: String, packageName: String, localPath: String): Result<HeapDumpInfo>
    suspend fun listCaptures(): List<HeapDumpInfo>
    suspend fun deleteCapture(id: String): Boolean
    suspend fun revealCapture(id: String): CommandResult
}

interface AccessibilityService {
    suspend fun dump(serial: String): AccessibilityNode?
}

/**
 * Tier-1/tier-2 view hierarchy inspector (§D): merges `uiautomator dump` with `dumpsys activity
 * top`'s unmerged view tree (view classes Compose collapses out of the accessibility tree) by
 * bounds + class name, and reads `dumpsys window` for window z-order. Composable names, modifier
 * chains, and recomposition counts (tiers 3–4) need an on-device JVMTI agent Andy does not have
 * and are explicitly out of scope (§D.2). Defaults to [UnavailableViewHierarchyService].
 */
interface ViewHierarchyService {
    suspend fun capture(serial: String, options: HierarchyOptions = HierarchyOptions()): Result<HierarchySnapshot>
}

interface BugService {
    val status: Flow<BugCaptureStatus>
    suspend fun startCapture(serial: String, device: AndroidDevice?)
    suspend fun stopCapture()
    /** Starts a durable screen recording from this instant, replacing the rolling bug window. */
    suspend fun beginRecording()
    fun recordAction(kind: String, label: String, detail: String? = null)
    /** Records a screenshot into the active investigation timeline (no-op if capture inactive). */
    fun recordScreenshot(pngBytes: ByteArray, label: String = "Screenshot", detail: String? = null) {}
    /** Loads timeline.json for a saved report, or null when absent (v1 reports). */
    suspend fun loadBugTimeline(id: String): InvestigationTimeline? = null
    suspend fun saveBug(draft: BugCaptureDraft, device: AndroidDevice?): BugReport
    suspend fun saveRecording(device: AndroidDevice?): BugReport
    suspend fun listBugs(): List<BugReport>
    suspend fun listRecordings(): List<BugReport>
    suspend fun loadBug(id: String): BugReport?
    suspend fun loadBugLog(id: String): String
    suspend fun deleteBug(id: String): Boolean
    suspend fun exportBug(id: String): String?
    /**
     * Exports a portable investigation bundle: `manifest.json`, `summary.md`, `timeline.json`,
     * and sidecars/media, alongside the plain duplicate produced by [exportBug]. Desktop writes
     * this as a folder under `exports/` (no zip dependency); platforms without a timeline just
     * fall back to [exportBug].
     */
    suspend fun exportInvestigationBundle(id: String): String? = exportBug(id)
    fun playbackFrames(id: String, startFrameIndex: Int = 0): Flow<MirrorFrame>
    suspend fun bugVideoFrameCount(id: String): Int
    suspend fun loadBugVideoFrame(id: String, frameIndex: Int): MirrorFrame?
    /** Reveals a bug/recording's directory in Finder/Explorer, when resolvable on this platform. */
    suspend fun revealBug(id: String): CommandResult = CommandResult.failure("Reveal is not supported on this platform")
    /** Absolute local directory for a bug/recording (desktop only); used for "copy as path". */
    suspend fun bugDirectoryPath(id: String): String? = null
    /** Renames a saved bug/recording's title in place. */
    suspend fun renameBug(id: String, title: String): CommandResult = CommandResult.failure("Rename is not supported on this platform")
    /** Stamps or clears project attribution on a saved bug/recording for the Artifacts catalog. */
    suspend fun assignBugProject(id: String, projectId: String?): CommandResult =
        CommandResult.failure("Assign project is not supported on this platform")
}

interface ArtifactService {
    suspend fun saveScreenshot(serial: String, suggestedName: String): CommandResult
    suspend fun saveBugReport(serial: String, suggestedName: String): CommandResult
    /**
     * Captures raw screenshot bytes (PNG) for the redaction/annotation/device-frame editor
     * instead of saving directly. Returns null when unsupported or capture failed.
     */
    suspend fun captureScreenshotForEditing(serial: String): ByteArray? = null
    /**
     * Bakes redaction/annotation/device-frame edits into [basePngBytes] at full resolution.
     * Returns null when unsupported so the caller can fall back to the unedited capture.
     */
    suspend fun renderScreenshotEdits(basePngBytes: ByteArray, edits: ScreenshotEdits): ByteArray? = null
    /** Persists already-rendered (edited) PNG bytes via the platform's normal save flow. */
    suspend fun saveEditedScreenshot(pngBytes: ByteArray, suggestedName: String): CommandResult =
        CommandResult.failure("Screenshot editing is not supported on this platform")
}

/**
 * Hybrid project Artifacts + Media catalog: indexes Andy-instrumented sources, supports
 * direct upload + pin-to-durable copies. Human UI only in v1 (no MCP tools).
 */
interface ProjectArtifactCatalogService {
    /** All catalog entries across projects and Unscoped; UI filters by [ProjectCatalogEntry.projectId]. */
    val entries: StateFlow<List<app.andy.model.ProjectCatalogEntry>>
    suspend fun refresh()
    fun entriesFor(projectId: String?): List<app.andy.model.ProjectCatalogEntry>
    suspend fun upload(projectId: String, paths: List<String>): CommandResult
    suspend fun pin(entryId: String): CommandResult
    suspend fun unpin(entryId: String): CommandResult
    /** Unlink indexed rows; delete durable pins/uploads. Never cascade-deletes chats. */
    suspend fun remove(entryId: String): CommandResult
    suspend fun assignToProject(entryId: String, projectId: String): CommandResult
    suspend fun reveal(entryId: String): CommandResult
    suspend fun absolutePath(entryId: String): String?
    suspend fun readTextPreview(entryId: String, maxChars: Int = 48_000): String?
}

/**
 * Exports part (or all) of a saved recording to a small, shareable clip (§E.4). Defaults to
 * [UnavailableRecordingExportService] so web and `andyd` compile untouched.
 */
interface RecordingExportService {
    suspend fun export(request: RecordingExportRequest, localPath: String): Result<ExportedClip>
}

interface TracingService {
    val status: StateFlow<TraceRecordingStatus>
    val recordings: StateFlow<List<TraceRecording>>
    suspend fun checkSupport(serial: String): CommandResult
    suspend fun start(serial: String, configTextProto: String, name: String, presetId: String?): CommandResult
    suspend fun stop(): CommandResult
    suspend fun refreshRecordings()
    suspend fun deleteRecording(id: String): Boolean
    suspend fun revealRecording(id: String): CommandResult
    suspend fun importConfig(sourcePath: String): CommandResult
    suspend fun listUserConfigs(): List<TraceUserConfig>
    suspend fun loadUserConfig(id: String): String?
    suspend fun saveUserConfig(name: String, content: String): CommandResult
    suspend fun deleteUserConfig(id: String): Boolean
    /** Retries pulling the remote file for the last failed recording, if any. */
    suspend fun retryPull(): CommandResult
}

interface TraceViewerService {
    suspend fun openExternally(traceId: String): CommandResult
    fun shutdown()
}

interface SharedPrefsService {
    /** Lists shared_prefs XML basenames for a debuggable package. */
    suspend fun listFiles(serial: String, packageName: String): Result<List<String>>
    suspend fun read(serial: String, packageName: String, fileName: String): Result<List<PrefEntry>>
    suspend fun upsert(serial: String, packageName: String, fileName: String, entry: PrefEntry): CommandResult
    suspend fun delete(serial: String, packageName: String, fileName: String, key: String): CommandResult
}

interface AppDatabaseService {
    suspend fun listDatabases(serial: String, packageName: String): Result<List<AppDatabaseInfo>>
    suspend fun listTables(serial: String, packageName: String, dbName: String): Result<List<String>>
    /** Row counts for the given tables (one device pull). Missing tables are omitted. */
    suspend fun tableRowCounts(
        serial: String,
        packageName: String,
        dbName: String,
        tables: List<String>,
    ): Result<Map<String, Long>>
    suspend fun tableInfo(serial: String, packageName: String, dbName: String, tableName: String): Result<DbTableInfo>
    suspend fun browseTable(
        serial: String,
        packageName: String,
        dbName: String,
        tableName: String,
        limit: Int = 200,
        offset: Int = 0,
    ): Result<DbQueryResult>
    suspend fun query(serial: String, packageName: String, dbName: String, sql: String, limit: Int = 500): Result<DbQueryResult>
    suspend fun updateCell(
        serial: String,
        packageName: String,
        dbName: String,
        tableName: String,
        column: String,
        newValue: String?,
        rowId: Long?,
        primaryKeyColumn: String?,
        primaryKeyValue: String?,
    ): CommandResult
    suspend fun pullToHost(serial: String, packageName: String, dbName: String, localPath: String): CommandResult
    suspend fun listSavedQueries(packageName: String): List<SavedSqlQuery>
    suspend fun saveQuery(packageName: String, name: String, sql: String): CommandResult
    suspend fun deleteQuery(packageName: String, id: String): Boolean
}

interface WorkspaceStore {
    suspend fun load(): WorkspaceState
    suspend fun save(state: WorkspaceState)
}

enum class AgentAttentionKind {
    /** Blocked on approval/question. */
    Blocked,
    /** Turn finished while unseen. */
    Done,
    /** Crashed or failed. */
    Error,
}

data class AgentAttentionEvent(val taskId: String, val projectId: String?, val title: String, val kind: AgentAttentionKind)
data class OpenAgentTaskRequest(val taskId: String, val projectId: String?)

/**
 * Returns from an agent chat to the investigation a contextual action (§5) was launched from,
 * optionally re-selecting the exact event and playback position.
 */
data class OpenInvestigationRequest(
    val investigationId: String,
    val eventId: String? = null,
    val playbackMillis: Long? = null,
)

interface OsNotificationService { fun show(event: AgentAttentionEvent) }
interface NotificationSoundPlayer { fun play(soundId: String) }
interface AgentAttentionCoordinator {
    fun start()
    fun onTasksChanged(tasks: List<AgentTask>)
}

object NoopOsNotificationService : OsNotificationService { override fun show(event: AgentAttentionEvent) = Unit }
object NoopNotificationSoundPlayer : NotificationSoundPlayer { override fun play(soundId: String) = Unit }

interface ActionConfigStore {
    suspend fun load(): ActionsConfig
    suspend fun save(config: ActionsConfig)
}

interface ActionRunService {
    val running: StateFlow<List<RunningAction>>
    /** Opens an interactive login shell rooted at the project's context directory. */
    fun openShell(project: ActionProject): String
    fun run(project: ActionProject, action: ProjectAction): String
    fun stop(runId: String)
    fun clear(runId: String)
    /** Best-effort root pid for a project terminal PTY (for local-server attribution). */
    fun sessionRootPid(runId: String): Long? = null
}

/** Shared empty backing for [AgentRunService.interactiveTerminalTaskIds] on hosts without terminals. */
private val NoInteractiveTerminals: StateFlow<Set<String>> = MutableStateFlow(emptySet())
private val NoLocalModelBackends: StateFlow<Map<AgentKind, Boolean>> = MutableStateFlow(emptyMap())

interface AgentRunService {
    val tasks: StateFlow<List<AgentTask>>
    val cliStatuses: StateFlow<List<AgentCliStatus>>
    /**
     * Models reported by installed provider CLIs (`agy models`, `cursor-agent models`, …).
     * Missing providers fall back to [app.andy.model.AgentModelCatalog] in the UI.
     */
    val providerModels: StateFlow<Map<AgentKind, List<AgentModelOption>>>
    /** Most recent provider-reported account limits, keyed by provider. */
    val providerQuotas: StateFlow<Map<AgentKind, AgentProviderQuota>>
    /** Explicit consent for provider-local account sources; disabled by default. */
    val quotaAccess: StateFlow<AgentQuotaAccess>
    /** Reads account information from installed provider CLIs without starting an agent task. */
    suspend fun refreshProviderQuotas()
    fun setQuotaAccess(agent: AgentKind, enabled: Boolean)
    /** Last-used launch settings for each provider, used to prefill the new-task composer. */
    val providerDefaults: StateFlow<Map<AgentKind, AgentProviderDefaults>>
    /** Sets the default transport lane for newly-created chats with [agent]. */
    fun setProviderLane(agent: AgentKind, lane: app.andy.model.AgentLaneKind) = Unit
    /** Provider used most recently for a chat, used as the next composer selection. */
    val lastUsedAgent: StateFlow<AgentKind?>
    /**
     * Reachability of Andy Settings URLs for Ollama / LM Studio (`GET /v1/models`).
     * Combo rows also require the selected runtime CLI.
     */
    val localModelBackends: StateFlow<Map<AgentKind, Boolean>>
        get() = NoLocalModelBackends
    /**
     * Skills this provider will load for a task rooted at [directory]. The provider's
     * native global and workspace skill locations are discovered independently, so
     * slash completion never offers skills from a different provider's convention.
     */
    fun skills(agent: AgentKind, directory: String?): StateFlow<List<AgentSkill>>
    /**
     * Slash commands advertised by the provider for [agent] in [directory]. Populated from
     * live ACP sessions and refreshed on demand via a lightweight ACP probe.
     */
    fun slashCommands(agent: AgentKind, directory: String?): StateFlow<List<AgentSlashCommand>>
    /** Re-fetches provider slash commands after CLI upgrades or workspace MCP changes. */
    fun refreshSlashCommands(agent: AgentKind, directory: String?)
    /** All locally installed skill names, used to scope provider-advertised command history. */
    fun knownSkillNames(directory: String?): StateFlow<Set<String>> = MutableStateFlow(emptySet())
    /** Re-scans the provider's skill locations after an external installation. */
    fun refreshSkills(agent: AgentKind, directory: String?)
    suspend fun createAndStart(draft: AgentTaskDraft): AgentTask
    fun stop(taskId: String)
    /** Manually completes an active workflow build run and advances the project workflow. */
    fun completeWorkflowRun(taskId: String)
    /** Starts the failed task over with its original prompt and configuration. */
    suspend fun retry(taskId: String)
    fun resume(
        taskId: String,
        followUp: String,
        imagePaths: List<String> = emptyList(),
        skills: List<AgentSkill> = emptyList(),
        /** Managed evidence bundle ids (§4) to copy into the task's local evidence dir and reference in the prompt. */
        contextBundleIds: List<String> = emptyList(),
        /** Where this turn's contextual action came from (§5); recorded on the task when it has none yet. */
        provenance: AgentContextualProvenance? = null,
    )
    /** Reopens a stored provider session so the live interactive terminal UI comes back. */
    fun reattachSession(taskId: String)
    fun canReattachSession(taskId: String): Boolean
    /** True while the embedded PTY or underlying tmux session is still running. */
    fun isTerminalLive(taskId: String): Boolean = false
    /** True while the task's selected transport lane owns a live session. */
    fun isLaneLive(taskId: String): Boolean = isTerminalLive(taskId)
    /**
     * Best-effort root pid for the chat's interactive session (DirectPty or tmux pane).
     * Used to attribute localhost listeners to the chat that spawned them.
     */
    fun sessionRootPid(taskId: String): Long? = null
    /**
     * Chats this app run still hosts an interactive session for. Sessions that only
     * survive in tmux from an earlier run are deliberately absent: reopening Andy puts
     * those chats back in read-only replay until a follow-up resumes them.
     */
    val interactiveTerminalTaskIds: StateFlow<Set<String>> get() = NoInteractiveTerminals
    /** Chats whose embedded terminal widget is currently mounted in the UI. */
    val attachedTerminalTaskIds: StateFlow<Set<String>> get() = NoInteractiveTerminals
    /**
     * True while the embedded chat for [taskId] is on screen *and* the app window is
     * foreground. A chat left open behind another app is not being watched, so it still
     * earns an unread badge and an OS banner when its turn ends.
     */
    fun isViewing(taskId: String): Boolean = false

    /**
     * Publishes window visibility/focus. Losing focus makes the open chat behave like a
     * background one for attention purposes; regaining it marks that chat read again.
     */
    fun setAppForeground(foreground: Boolean) = Unit
    /** Supplies an answer to an agent-issued decision checkpoint and continues the task. */
    fun respondToUserInput(taskId: String, requestId: String, answers: Map<String, String>)
    /** Switches the live ACP session's mode (e.g. plan vs. execute) for providers that advertise modes. */
    fun setAcpSessionMode(taskId: String, modeId: String) = Unit
    /** Holds a follow-up until the active run completes successfully. */
    fun queueFollowUp(
        taskId: String,
        followUp: String,
        imagePaths: List<String> = emptyList(),
        skills: List<AgentSkill> = emptyList(),
        /** Managed evidence bundle ids (§4); copied into the task's local evidence dir at queue time so they survive even if the managed bundle is later removed. */
        contextBundleIds: List<String> = emptyList(),
        /** Where this follow-up's contextual action came from (§5). */
        provenance: AgentContextualProvenance? = null,
    )
    /** Removes an unsent follow-up at [queueIndex]. */
    fun removeQueuedFollowUp(taskId: String, queueIndex: Int)
    /** Starts the next queued follow-up when the chat is idle. */
    fun sendNextQueuedFollowUp(taskId: String) = Unit
    /** Updates Andy's persisted task goal; providers receive it with subsequent prompts. */
    fun updateGoal(taskId: String, goal: String?)
    /** Toggles Andy plan mode for follow-ups; syncs the live ACP session mode when supported. */
    fun updatePlanMode(taskId: String, planMode: Boolean)
    suspend fun delete(taskId: String, removeWorktree: Boolean, force: Boolean = false): WorktreeDeleteOutcome
    /**
     * Promotes a temporary chat to a normal persisted one, moving its artifacts out of the
     * disposable directory. No-op for a chat that is already permanent.
     */
    suspend fun keepTemporaryChat(taskId: String) = Unit
    fun updateAutomationNotifySuppress(taskId: String, suppress: Boolean) = Unit
    suspend fun cleanupOwnedWorktree(taskId: String) = Unit
    /** Clears the unread indicator for a finished chat (e.g. when opened). */
    fun markRead(taskId: String)
    /** Marks a chat unread so list/dock badges show again. */
    fun markUnread(taskId: String)
    /** Tracks whether an embedded chat is currently on screen (not merely opened before). */
    fun setChatViewing(taskId: String?, viewing: Boolean)
    /** Drops the local Swing viewer while keeping the underlying session alive. */
    fun releaseTerminalViewer(taskId: String) = Unit
    /** Hides a finished chat from the default list without deleting it. */
    fun archive(taskId: String)
    /** Restores an archived chat to the default list. */
    fun unarchive(taskId: String)
    /**
     * Legacy structured transcript. Empty under the embedded-terminal model —
     * the PTY buffer is the transcript. Kept for call-site compatibility during migration.
     */
    fun events(taskId: String): StateFlow<List<AgentEvent>>
    fun interactiveResumeCommand(taskId: String): String?
    /**
     * Provider label when this exact conversation can continue in its desktop app.
     * Null means the provider has no supported same-session desktop handoff.
     */
    fun providerAppContinuationLabel(taskId: String): String? = null
    /** Opens this exact conversation in the provider's desktop app. */
    suspend fun openInProviderApp(taskId: String): CommandResult =
        CommandResult.failure("This provider does not support desktop continuation")
    /** @deprecated Prefer the embedded terminal pane; retained as a copy/paste escape hatch. */
    suspend fun openInTerminal(taskId: String): CommandResult
    suspend fun openSkill(path: String): CommandResult
    suspend fun worktreeDiffSummary(taskId: String): String?
    suspend fun changeSummary(taskId: String): AgentChangeSummary?
    suspend fun fileDiff(taskId: String, relativePath: String): AgentFileDiff?
    suspend fun undoFileChanges(
        taskId: String,
        batchId: String,
        groupedBatchIds: List<String> = emptyList(),
    ): CommandResult
    /**
     * Reverts [snapshot] paths to [AgentTask.changeBaselineTree] when no transcript
     * [AgentEvent.FileChanges] batch exists (legacy / fallback edited-files card).
     */
    suspend fun undoChangeSnapshot(taskId: String, snapshot: AgentThreadChangeSnapshot): CommandResult
    suspend fun refreshCliStatuses()
    suspend fun isGitRepo(dir: String): Boolean
    /** Current branch of [dir], or null when detached HEAD or not a repo. */
    suspend fun currentBranch(dir: String): String?
    /** Active Andy-tracked worktrees for [originDir]'s repo, for the composer's "base on" picker. */
    suspend fun worktreeBaseOptions(originDir: String): List<WorktreeBaseOption>
    /** Full reconciled tree (Andy-tracked + untracked) for the Worktrees tab. */
    suspend fun worktreeTree(originDir: String): List<WorktreeNode>
    /** Shell command that merges [branch] into whatever is checked out in [targetDir]. */
    fun mergeCommand(targetDir: String, branch: String): String
    /**
     * Applies [branch] into [targetDir]'s working tree without committing (HEAD unchanged).
     * When [sourceWorktreePath] is set, dirty worktree changes are included.
     * On [WorktreeMergeOutcome.Conflicts], conflict markers remain until the user keeps them
     * or [abortMerge] is called.
     */
    suspend fun mergeBranch(
        targetDir: String,
        branch: String,
        sourceWorktreePath: String? = null,
    ): WorktreeMergeOutcome
    /** Aborts an in-progress merge left by a conflicted [mergeBranch] call. */
    suspend fun abortMerge(targetDir: String): Result<Unit>
}

data class RetentionSweepResult(
    val chatsCompressedArchived: Int,
    val chatsPermanentlyDeleted: Int,
    val projectLocalFoldersDeleted: Int,
    val bytesReclaimed: Long,
)

interface AgentRetentionService {
    suspend fun runSweepNow(): RetentionSweepResult
}

interface ProjectWorkflowService {
    val projects: StateFlow<Map<String, ProjectWorkflowState>>
    /** Absolute context directory for [projectId], if the project is configured. */
    suspend fun projectContextDir(projectId: String): String?
    suspend fun ensureProject(projectId: String)
    suspend fun updateScratchpad(projectId: String, text: String)
    suspend fun updateProfile(projectId: String, kind: ProjectTaskKind, profile: ProjectAgentProfile)
    suspend fun saveSpec(draft: ProjectSpecDraft): String
    suspend fun runSpec(taskId: String, revisionRequest: String? = null)
    suspend fun saveBuildPair(draft: ProjectBuildPairDraft): String
    suspend fun startBuildPair(buildTaskId: String)
    fun pauseBuildPair(buildTaskId: String)
    fun stopBuildPair(buildTaskId: String)
    suspend fun resumeBuildPair(buildTaskId: String)
    /** Adds a freeform fix thread to a completed workflow without auto-running its gates. */
    suspend fun startRecoveryFollowUp(
        buildTaskId: String,
        followUp: String,
        imagePaths: List<String> = emptyList(),
    ): String?
    /** Runs one explicit cumulative review after manual recovery testing is finished. */
    suspend fun startRecoveryReview(buildTaskId: String): String?
    suspend fun deleteTask(taskId: String, cascade: Boolean = false)
    suspend fun deleteProject(projectId: String)
}

enum class KanbanLaneDirection { Left, Right }

interface KanbanService {
    val boards: StateFlow<Map<String, KanbanBoard>>

    fun addLane(projectId: String, name: String)
    fun renameLane(projectId: String, laneId: String, name: String)
    /** Deletes the lane and all its cards. Caller (UI) must confirm first. No-ops if this is the last lane. */
    fun deleteLane(projectId: String, laneId: String)
    fun moveLane(projectId: String, laneId: String, direction: KanbanLaneDirection)

    fun addCard(projectId: String, laneId: String, title: String, description: String, tags: List<String>)
    fun updateCard(projectId: String, cardId: String, title: String, description: String, tags: List<String>)
    fun deleteCard(projectId: String, cardId: String)
    /** Moves [cardId] to [toLaneId] at position [toIndex] (0-based, post-removal index in the target lane). */
    fun moveCard(projectId: String, cardId: String, toLaneId: String, toIndex: Int)
    fun linkChat(projectId: String, cardId: String, chatTaskId: String)
    fun deleteBoard(projectId: String)
}

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0

    companion object {
        fun success(stdout: String = "") = CommandResult(0, stdout, "")
        fun failure(message: String, exitCode: Int = 1) = CommandResult(exitCode, "", message)
    }
}

data class LogcatFilter(
    val search: String = "",
    val levels: Set<LogLevel> = setOf(LogLevel.Debug, LogLevel.Info, LogLevel.Warn, LogLevel.Error, LogLevel.Fatal),
    val packageName: String? = null,
    val buffers: Set<String> = setOf("main", "system", "crash"),
    /**
     * When true, the stream skips the device's existing log buffer and only follows new lines
     * (`adb logcat -T 0`). Used by rolling bug capture so a 30s window isn't flooded with hours
     * of historical logcat.
     */
    val followOnly: Boolean = false,
)

data class MirrorFrame(
    val width: Int,
    val height: Int,
    val argb: IntArray,
    val frameNumber: Long = 0,
    val decodedFps: Float? = null,
    val displayedFps: Float? = null,
)

data class MirrorVideoConfig(
    val maxSize: Int = 1080,
    val bitRate: Int = 8_000_000,
    val maxFps: Int = 60,
    val codec: String = "h264",
    val rendererMode: MirrorRendererMode = MirrorRendererMode.Auto,
)

/** One Annex-B H.264 access unit from the device stream, for bug capture remux. */
data class EncodedVideoAccessUnit(
    val timestampMillis: Long,
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedVideoAccessUnit) return false
        return timestampMillis == other.timestampMillis &&
            width == other.width &&
            height == other.height &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = timestampMillis.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}

/** User-visible renderer policy. Accelerated never silently falls back. */
enum class MirrorRendererMode {
    Auto,
    Accelerated,
    Legacy,
}

/** The verified class of the currently active decoder/presenter pair. */
enum class MirrorBackendKind {
    NativeHardware,
    BrowserHardware,
    LegacyCpu,
    Unavailable,
}

data class MirrorBackend(
    val kind: MirrorBackendKind = MirrorBackendKind.Unavailable,
    val decoder: String = "Unavailable",
    val renderer: String = "Unavailable",
    val fallbackReason: String? = null,
) {
    val isHardwareBacked: Boolean
        get() = kind == MirrorBackendKind.NativeHardware || kind == MirrorBackendKind.BrowserHardware
}

/**
 * Presentation telemetry produced by the renderer. Latency is host-input to present and does
 * not require clock synchronization with Android.
 */
data class MirrorStats(
    val displayedFps: Float = 0f,
    val decodedFps: Float = 0f,
    val droppedFrames: Long = 0,
    val framesPresented: Long = 0,
    val p95InputToPresentMillis: Float? = null,
)

/**
 * The cross-platform contract for a live mirror. Surface ownership stays with the renderer;
 * Kotlin receives this state only, never a continuous GPU frame stream.
 */
data class MirrorSession(
    val serial: String,
    val requestedMode: MirrorRendererMode,
    val backend: MirrorBackend,
    val stats: MirrorStats = MirrorStats(),
    val width: Int = 0,
    val height: Int = 0,
    /** A decoded frame is buffered and can be shown as soon as the native surface is revealed. */
    val readyForPresentation: Boolean = false,
    val failureReason: String? = null,
)

sealed interface MirrorInput {
    data class Touch(val action: MirrorTouchAction, val x: Int, val y: Int) : MirrorInput
    data class Tap(val x: Int, val y: Int) : MirrorInput
    data class Swipe(val startX: Int, val startY: Int, val endX: Int, val endY: Int, val durationMillis: Int) : MirrorInput
    data class Key(val keyCode: Int) : MirrorInput
    data class Text(val value: String) : MirrorInput
    data object Back : MirrorInput
    data object Home : MirrorInput
    data object Recents : MirrorInput
    data object Power : MirrorInput
}

interface McpServerService {
    val status: Flow<String>            // "stopped" | "running on 127.0.0.1:8565" | "error: ..."
    val running: Flow<Boolean>
    suspend fun start(port: Int): CommandResult
    suspend fun stop(): CommandResult

    fun getSnippet(clientName: String, port: Int): String
    fun getClients(): List<String>
    fun isAutoWriteSupported(clientName: String): Boolean
    fun writeConfig(clientName: String, port: Int): Boolean
    fun getToolNames(): List<String>

    /**
     * Reachable hosts for Network Access (LAN first, then VPN/Tailscale/WireGuard).
     * Used for Settings URL list + QR. Default is loopback-only.
     */
    fun suggestNetworkAccessHosts(): List<String> = listOf("127.0.0.1")

    /** Preferred host (first of [suggestNetworkAccessHosts]). */
    fun suggestNetworkAccessHost(): String = suggestNetworkAccessHosts().firstOrNull() ?: "127.0.0.1"

    /** Cryptographically random access token (URL-safe base64). */
    fun generateNetworkAccessToken(): String = ""
}

enum class MirrorTouchAction { Down, Move, Up }

enum class AndyPlatform { Desktop, Web }

enum class WebConnectionTransport { None, WebSocket, WebUsb }

data class WebConnectionState(
    val transport: WebConnectionTransport = WebConnectionTransport.None,
    val status: String = "Disconnected",
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null,
)

internal fun WebConnectionState.shouldAutoConnectWebSocket(): Boolean =
    transport == WebConnectionTransport.None && !connecting && !connected

interface WebConnectionService {
    val state: StateFlow<WebConnectionState>
    suspend fun connectWebSocket(): CommandResult
    suspend fun requestWebUsb(): CommandResult
    suspend fun retry(): CommandResult
    suspend fun forgetWebUsbAuthorization(): CommandResult
}

data class WebStorageState(
    val persisted: Boolean = false,
    val usageBytes: Long = 0,
    val quotaBytes: Long = 0,
    val resourceOrigins: List<String> = emptyList(),
)

interface WebStorageService {
    val state: StateFlow<WebStorageState>
    suspend fun refresh(): WebStorageState
    suspend fun requestPersistence(): Boolean
    suspend fun clearAll(): CommandResult
}

data class WebServices(
    val connection: WebConnectionService,
    val storage: WebStorageService,
)

data class PlatformCapabilities(
    val platform: AndyPlatform,
    val destinations: List<AndyDestination>,
    val avdManagement: Boolean,
    val wifiPairing: Boolean,
    val hostAutomation: Boolean,
    val proxy: Boolean,
    val mcp: Boolean,
    val updates: Boolean,
    /** True when Live may offer Auto/GPU renderer controls (Mac Metal or browser WebCodecs). */
    val acceleratedMirror: Boolean = false,
    /** True on macOS desktop where simctl/devicectl discovery is available. */
    val iosDeviceManagement: Boolean = false,
) {
    companion object {
        val Desktop = PlatformCapabilities(
            platform = AndyPlatform.Desktop,
            // Tracing lives under Performance as a tab; keep the enum for shortcut remapping.
            destinations = AndyDestination.entries.filter { it != AndyDestination.Tracing },
            avdManagement = true,
            wifiPairing = true,
            hostAutomation = true,
            proxy = true,
            mcp = true,
            updates = true,
            iosDeviceManagement = true,
            // Overridden at service creation from the packaged native bridge (Mac only today).
            acceleratedMirror = false,
        )

        val Web = PlatformCapabilities(
            platform = AndyPlatform.Web,
            destinations = listOf(
                AndyDestination.Devices,
                AndyDestination.Live,
                AndyDestination.Apps,
                AndyDestination.Logcat,
                AndyDestination.Intents,
                AndyDestination.Files,
                AndyDestination.Controls,
                AndyDestination.Performance,
                AndyDestination.Design,
                AndyDestination.Inspector,
                AndyDestination.Bugs,
                AndyDestination.Recordings,
                AndyDestination.Settings,
            ),
            avdManagement = false,
            wifiPairing = false,
            hostAutomation = false,
            proxy = false,
            mcp = false,
            updates = false,
            // Browser path: WebCodecs + WebGL when the runtime verifies hardware.
            acceleratedMirror = true,
        )
    }
}

/**
 * App-wide service graph. Marked [Stable] so panes that take it as a parameter
 * (notably the agent [app.andy.ui.agents.AgentTerminalSurface]) can skip when only
 * unrelated parent state changes — otherwise every tick re-enters the Swing interop host.
 */
@Stable
data class AndyServices(
    val devices: DeviceService,
    val iosDevices: IosDeviceService,
    val avd: AvdService,
    val mirror: MirrorEngine,
    val logcat: LogcatService,
    val intents: IntentService,
    val apps: AppService,
    val files: FileService,
    val hostFiles: HostFileService,
    val proxy: ProxyService,
    val metrics: MetricsService,
    val accessibility: AccessibilityService,
    val viewHierarchy: ViewHierarchyService = UnavailableViewHierarchyService,
    val bugs: BugService,
    val artifacts: ArtifactService,
    val projectArtifacts: ProjectArtifactCatalogService = UnavailableProjectArtifactCatalogService,
    val recordingExport: RecordingExportService = UnavailableRecordingExportService,
    val tracing: TracingService = UnavailableTracingService,
    val traceViewer: TraceViewerService = UnavailableTraceViewerService,
    val sharedPrefs: SharedPrefsService = UnavailableSharedPrefsService,
    val appDatabase: AppDatabaseService = UnavailableAppDatabaseService,
    val dhu: DhuService = UnavailableDhuService,
    val crashInspector: CrashInspectorService = UnavailableCrashInspectorService,
    val heapDump: HeapDumpService = UnavailableHeapDumpService,
    val evidence: InvestigationEvidenceService = UnavailableInvestigationEvidenceService,
    val workspaceStore: WorkspaceStore,
    val updates: AppUpdateService,
    val runtimeBundle: RuntimeBundleService = UnavailableRuntimeBundleService,
    val cliUpdates: CliUpdateCheckService = UnavailableCliUpdateCheckService,
    val mcp: McpServerService,
    val actionConfig: ActionConfigStore,
    val actionRuns: ActionRunService,
    val agentRuns: AgentRunService,
    val agentRetention: AgentRetentionService = UnavailableAgentRetentionService,
    val projectWorkflows: ProjectWorkflowService,
    val kanban: KanbanService = UnavailableKanbanService,
    val automations: AutomationService = UnavailableAutomationService,
    val notificationSounds: NotificationSoundPlayer = NoopNotificationSoundPlayer,
    val voiceSetup: VoiceSetupService = UnavailableVoiceSetupService,
    val voiceDictation: VoiceDictationService = UnavailableVoiceDictationService,
    val orchestrationPreferences: OrchestrationPreferencesService = UnavailableOrchestrationPreferencesService,
    val localServers: LocalServerService = UnavailableLocalServerService,
    val remoteSession: RemoteSessionService = UnavailableRemoteSessionService,
    val capabilities: PlatformCapabilities = PlatformCapabilities.Desktop,
    val web: WebServices? = null,
)
