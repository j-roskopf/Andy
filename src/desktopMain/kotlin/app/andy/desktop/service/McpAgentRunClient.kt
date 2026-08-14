package app.andy.desktop.service

import app.andy.desktop.service.agents.AgentCliLocator
import app.andy.desktop.service.agents.DesktopAgentRunService
import app.andy.desktop.service.agents.DesktopAgentTaskStore
import app.andy.desktop.service.agents.WorktreeManager
import app.andy.desktop.service.agents.acp.AcpTranscriptStore
import app.andy.desktop.service.agents.defaultAndyAgentArtifactsDir
import app.andy.desktop.service.agents.discoverAgentSkills
import app.andy.desktop.service.agents.discoverKnownAgentSkillNames
import app.andy.model.AgentChangeSummary
import app.andy.model.AgentCliIssue
import app.andy.model.AgentCliStatus
import app.andy.model.AgentContextualProvenance
import app.andy.model.AgentEvent
import app.andy.model.AgentFileDiff
import app.andy.model.AgentKind
import app.andy.model.AgentLaneKind
import app.andy.model.defaultLane
import app.andy.model.AgentPlanEntry
import app.andy.model.AgentSlashCommand
import app.andy.model.AgentToolKind
import app.andy.model.AgentToolState
import app.andy.model.AgentUserInputOption
import app.andy.model.AgentUserInputOrigin
import app.andy.model.AgentUserInputQuestion
import app.andy.model.AgentUserInputRequest
import app.andy.model.AgentModelOption
import app.andy.model.AgentProviderDefaults
import app.andy.model.AgentProviderQuota
import app.andy.model.AgentQueuedFollowUp
import app.andy.model.AgentQuotaAccess
import app.andy.model.AgentSessionMode
import app.andy.model.AgentSkill
import app.andy.model.AgentTask
import app.andy.model.AgentTaskDraft
import app.andy.model.WorktreeBaseOption
import app.andy.model.WorktreeDeleteOutcome
import app.andy.model.WorktreeMergeOutcome
import app.andy.model.WorktreeNode
import app.andy.model.ProjectAgentProfile
import app.andy.model.ProjectBuildPairDraft
import app.andy.model.ProjectSpecDraft
import app.andy.model.ProjectTaskKind
import app.andy.model.ProjectWorkflowState
import app.andy.service.AgentRunService
import app.andy.service.CommandResult
import app.andy.service.ProjectWorkflowService
import app.andy.terminal.TmuxAndy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Thin MCP client that implements [AgentRunService] / [ProjectWorkflowService] by
 * calling tools on `andyd` over a Unix domain socket.
 *
 * Terminal attach is bridged to a local [DesktopAgentRunService] in TmuxWithAttach mode
 * so the Compose GUI can still embed BossTerm.
 */
class McpAgentRunClient(
    private val scope: CoroutineScope,
    private val socketPath: File,
    private val cliLocator: AgentCliLocator = AgentCliLocator(),
) : AgentRunService, ProjectWorkflowService {
    private val json = Json { ignoreUnknownKeys = true }
    private val idSeq = AtomicLong(1)
    private val localWorktrees = WorktreeManager()

    private val _tasks = MutableStateFlow<List<AgentTask>>(emptyList())
    override val tasks: StateFlow<List<AgentTask>> = _tasks.asStateFlow()
    /**
     * Keep a successful delete reflected in the UI while an in-flight or periodic daemon
     * refresh may still be returning the previous list.
     */
    private val locallyDeletedTaskIds = ConcurrentHashMap.newKeySet<String>()
    /**
     * Chats the user has read locally while waiting for the daemon to persist
     * [AgentTask.unread] = false. Prevents periodic refresh from re-badging them.
     */
    private val clientReadTaskIds = ConcurrentHashMap.newKeySet<String>()

    /** Ids whose `chat.mark_read` RPC the daemon has acknowledged; see [dropSettledClientReads]. */
    private val daemonAckedReadTaskIds = ConcurrentHashMap.newKeySet<String>()
    private val clientViewingTaskId = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /** Window visibility/focus. An open chat only counts as watched while the window is up. */
    @Volatile
    private var appForeground: Boolean = true

    /**
     * Serializes `chat.set_app_focus` RPCs. Each [callTool] opens its own Unix connection and
     * the daemon handles connections concurrently, so unsynchronized sends can land out of
     * order and leave the daemon believing the window is foreground after it went away —
     * exactly the state that suppresses the badge this focus tracking exists to produce.
     */
    private val appFocusRpcMutex = Mutex()

    /** Last value the daemon accepted, so redundant transitions do not re-send. */
    private var lastSentAppForeground: Boolean? = null

    private val _cliStatuses = MutableStateFlow<List<AgentCliStatus>>(emptyList())
    override val cliStatuses: StateFlow<List<AgentCliStatus>> = _cliStatuses.asStateFlow()

    private val _providerModels = MutableStateFlow<Map<AgentKind, List<AgentModelOption>>>(emptyMap())
    override val providerModels: StateFlow<Map<AgentKind, List<AgentModelOption>>> = _providerModels.asStateFlow()

    private val _providerQuotas = MutableStateFlow<Map<AgentKind, AgentProviderQuota>>(emptyMap())
    override val providerQuotas: StateFlow<Map<AgentKind, AgentProviderQuota>> = _providerQuotas.asStateFlow()

    private val _quotaAccess = MutableStateFlow(AgentQuotaAccess())
    override val quotaAccess: StateFlow<AgentQuotaAccess> = _quotaAccess.asStateFlow()

    private val _providerDefaults = MutableStateFlow<Map<AgentKind, AgentProviderDefaults>>(emptyMap())
    override val providerDefaults: StateFlow<Map<AgentKind, AgentProviderDefaults>> = _providerDefaults.asStateFlow()

    private val _lastUsedAgent = MutableStateFlow<AgentKind?>(null)
    override val lastUsedAgent: StateFlow<AgentKind?> = _lastUsedAgent.asStateFlow()

    private val _projects = MutableStateFlow<Map<String, ProjectWorkflowState>>(emptyMap())
    override val projects: StateFlow<Map<String, ProjectWorkflowState>> = _projects.asStateFlow()

    private data class SkillScope(val agent: AgentKind, val directory: String?)
    private data class KnownSkillScope(val directory: String?)

    private val skillFlows = ConcurrentHashMap<SkillScope, MutableStateFlow<List<AgentSkill>>>()
    private val loadedSkillScopes = ConcurrentHashMap.newKeySet<SkillScope>()
    private val knownSkillNameFlows = ConcurrentHashMap<KnownSkillScope, MutableStateFlow<Set<String>>>()
    private val loadedKnownSkillScopes = ConcurrentHashMap.newKeySet<KnownSkillScope>()
    private val emptyEvents = MutableStateFlow<List<AgentEvent>>(emptyList())
    private val eventFlows = ConcurrentHashMap<String, MutableStateFlow<List<AgentEvent>>>()
    private val eventLoads = ConcurrentHashMap.newKeySet<String>()
    private val sharedAgentArtifactsDir = defaultAndyAgentArtifactsDir()
    private val sharedAgentTaskStore = DesktopAgentTaskStore(transcriptsDir = sharedAgentArtifactsDir)
    private val localTranscriptStore = AcpTranscriptStore(fileFor = { taskId ->
        val compressed = _tasks.value.firstOrNull { it.id == taskId }?.transcriptCompressed == true ||
            sharedAgentTaskStore.archiveFile(taskId).isFile
        File(sharedAgentTaskStore.resolvedContentDirBlocking(taskId, compressed), "transcript.jsonl")
    })

    private var localBridge: DesktopAgentRunService? = null

    private val _interactiveTerminalTaskIds = MutableStateFlow<Set<String>>(emptySet())
    /** Mirrors the local terminal bridge, which owns the BossTerm viewers this GUI hosts. */
    override val interactiveTerminalTaskIds: StateFlow<Set<String>> =
        _interactiveTerminalTaskIds.asStateFlow()

    private val _attachedTerminalTaskIds = MutableStateFlow<Set<String>>(emptySet())
    override val attachedTerminalTaskIds: StateFlow<Set<String>> =
        _attachedTerminalTaskIds.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                runCatching { refreshTasks() }
                delay(2_000)
            }
        }
    }

    fun attachLocalTerminalBridge(local: DesktopAgentRunService) {
        localBridge = local
        scope.launch {
            local.interactiveTerminalTaskIds.collect { _interactiveTerminalTaskIds.value = it }
        }
        scope.launch {
            local.attachedTerminalTaskIds.collect { _attachedTerminalTaskIds.value = it }
        }
    }

    /** Local BossTerm/tmux-attach host used by [app.andy.ui.agents.AgentTerminalSurface]. */
    fun terminalHost(): DesktopAgentRunService? = localBridge

    internal fun reconcileStaleActiveTaskIfNeeded(taskId: String) {
        // Repair is tied to the chat being mounted, not to window focus.
        if (clientViewingTaskId.get() != taskId && localBridge?.isChatOpen(taskId) != true) return
        scope.launch {
            runCatching {
                callTool("chat.reconcile", mapOf("taskId" to JsonPrimitive(taskId)))
            }
            runCatching { refreshTasks() }
        }
    }

    private fun acknowledgeRead(taskId: String) {
        clientReadTaskIds += taskId
        // This ack is pending again until its own RPC lands; an earlier settle marker would
        // otherwise retire it on the next refresh and flash the badge back.
        daemonAckedReadTaskIds -= taskId
        patchTask(taskId) { it.copy(unread = false) }
    }

    // While the window is away the open chat must keep whatever unread the daemon reports;
    // merging it as "viewing" would wipe the badge the user is meant to come back to.
    private fun viewingTaskIdsForMerge(): Set<String> =
        if (appForeground) clientViewingTaskId.get()?.let(::setOf).orEmpty() else emptySet()

    private suspend fun refreshTasks() {
        refreshComposerOptions()
        refreshProviderDefaults()
        // Snapshot before the fetch: the list we are about to request is produced after the
        // daemon acknowledged these reads, so it already reflects them.
        val settledReads = daemonAckedReadTaskIds.toSet()
        val raw = callTool("chat.list", emptyMap())
        val arr = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return
        // Keep a lightweight task list for the GUI; lifecycle fields must round-trip so
        // badges/labels match the daemon (startedAtMillis drives isQueued).
        val refreshedTasks = arr.mapNotNull { el -> parseListedTask(el.jsonObject) }
        dropSettledClientReads(clientReadTaskIds, daemonAckedReadTaskIds, settledReads)
        dropConfirmedClientReads(clientReadTaskIds, refreshedTasks)
        _tasks.value = mergeRefreshedAgentTasks(
            refreshed = refreshedTasks,
            clientReadTaskIds = clientReadTaskIds,
            viewingTaskIds = viewingTaskIdsForMerge(),
        ).filterNot { it.id in locallyDeletedTaskIds }
        locallyDeletedTaskIds.removeAll { deletedId -> refreshedTasks.none { it.id == deletedId } }
    }

    private fun parseListedTask(obj: JsonObject): AgentTask? {
        val id = obj.string("id") ?: return null
        val agentName = obj.string("agent") ?: return null
        val agent = AgentKind.entries.firstOrNull { it.name == agentName } ?: return null
        val statusName = obj.string("status")
        val exitCodeRaw = obj.long("exitCode")
        return AgentTask(
            id = id,
            title = obj.string("title") ?: id,
            prompt = obj.string("prompt")?.takeIf { it.isNotBlank() } ?: obj.string("title").orEmpty(),
            agent = agent,
            // The daemon has already resolved legacy artifacts and persists the authoritative
            // lane. Re-inferring locally would see both ACP and terminal artifacts after a
            // handoff and could remount the wrong surface.
            lane = AgentLaneKind.entries.firstOrNull { it.name == obj.string("lane") } ?: agent.defaultLane(),
            projectId = obj.string("projectId")?.takeIf { it.isNotBlank() },
            cwd = obj.string("cwd")?.takeIf { it.isNotBlank() },
            status = app.andy.model.AgentStatus.entries.firstOrNull { it.name == statusName },
            stoppedByUser = obj.bool("stoppedByUser"),
            resumable = obj.bool("resumable"),
            interrupted = obj.bool("interrupted"),
            statusConfident = obj.bool("statusConfident"),
            vendorSessionId = obj.string("vendorSessionId")?.takeIf { it.isNotBlank() },
            acpSessionId = obj.string("acpSessionId")?.takeIf { it.isNotBlank() },
            stopReason = obj.string("stopReason")?.takeIf { it.isNotBlank() },
            errorMessage = obj.string("errorMessage")?.takeIf { it.isNotBlank() },
            userInputRequest = parseUserInputRequest(obj["userInputRequest"] as? JsonObject),
            createdAtMillis = obj.long("createdAtMillis") ?: 0L,
            startedAtMillis = obj.long("startedAtMillis")?.takeIf { it > 0 },
            finishedAtMillis = obj.long("finishedAtMillis")?.takeIf { it > 0 },
            exitCode = exitCodeRaw?.takeUnless { it == Int.MIN_VALUE.toLong() }?.toInt(),
            unread = obj.bool("unread"),
            archived = obj.bool("archived"),
            transcriptCompressed = obj.bool("transcriptCompressed"),
            planMode = obj.bool("planMode"),
            queuedFollowUps = obj["queuedFollowUps"]?.jsonArray?.mapNotNull { element ->
                val queued = element.jsonObject
                val text = queued.string("text") ?: return@mapNotNull null
                AgentQueuedFollowUp(
                    text = text,
                    contextBundleIds = queued["contextBundleIds"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        .orEmpty(),
                )
            }.orEmpty(),
            originDir = obj.string("originDir")?.takeIf { it.isNotBlank() },
            useWorktree = obj.bool("useWorktree"),
            worktreePath = obj.string("worktreePath")?.takeIf { it.isNotBlank() },
            branchName = obj.string("branchName")?.takeIf { it.isNotBlank() },
            ownsWorktree = obj.bool("ownsWorktree"),
            parentWorktreeTaskId = obj.string("parentWorktreeTaskId")?.takeIf { it.isNotBlank() },
            attachAndyMcp = obj.bool("attachAndyMcp"),
        )
    }

    private fun parseUserInputRequest(obj: JsonObject?): AgentUserInputRequest? {
        obj ?: return null
        val questions = obj["questions"]?.jsonArray?.mapNotNull { element ->
            val question = element.jsonObject
            val options = question["options"]?.jsonArray?.map { option ->
                val value = option.jsonObject
                AgentUserInputOption(value.string("label").orEmpty(), value.string("description").orEmpty())
            }.orEmpty()
            AgentUserInputQuestion(
                id = question.string("id").orEmpty(),
                header = question.string("header").orEmpty(),
                question = question.string("question").orEmpty(),
                options = options,
            )
        }.orEmpty()
        if (questions.isEmpty()) return null
        return AgentUserInputRequest(
            id = obj.string("id").orEmpty(),
            questions = questions,
            origin = AgentUserInputOrigin.entries.firstOrNull { it.name == obj.string("origin") }
                ?: AgentUserInputOrigin.Artifact,
        )
    }

    private fun parseRemoteEvent(element: kotlinx.serialization.json.JsonElement): AgentEvent? {
        val obj = element as? JsonObject ?: return null
        val atMillis = obj.long("atMillis") ?: return null
        return when (obj.string("type")) {
            "session" -> AgentEvent.SessionStarted(atMillis, obj.string("sessionId"), obj.string("model"))
            "assistant" -> AgentEvent.AssistantText(atMillis, obj.string("text").orEmpty(), obj.bool("stream"))
            "thinking" -> AgentEvent.Thinking(atMillis, obj.string("text").orEmpty(), obj.bool("stream"))
            "user" -> AgentEvent.UserMessage(atMillis, obj.string("text").orEmpty(), imagePaths = obj["images"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty())
            "tool" -> AgentEvent.ToolCall(
                atMillis = atMillis,
                toolName = obj.string("toolName").orEmpty(),
                summary = obj.string("summary").orEmpty(),
                detail = obj.string("detail").orEmpty(),
                toolCallId = obj.string("toolCallId")?.takeIf { it.isNotBlank() },
                kind = AgentToolKind.entries.firstOrNull { it.name == obj.string("kind") },
                state = AgentToolState.entries.firstOrNull { it.name == obj.string("state") } ?: AgentToolState.Completed,
                locations = obj["locations"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            )
            "tool-result" -> AgentEvent.ToolResult(atMillis, obj.string("toolName"), obj.string("summary").orEmpty(), obj.string("detail").orEmpty(), obj.bool("isError"))
            "error" -> AgentEvent.TaskError(atMillis, obj.string("text").orEmpty())
            "result" -> AgentEvent.TaskResult(
                atMillis = atMillis,
                success = obj.bool("success"),
                finalText = obj.string("finalText"),
                costUsd = obj["costUsd"]?.jsonPrimitive?.doubleOrNull,
                costIsEstimated = obj.bool("costEstimated"),
                inputTokens = obj.long("inputTokens"),
                outputTokens = obj.long("outputTokens"),
                durationMs = obj.long("durationMs"),
            )
            "usage" -> AgentEvent.ContextUsage(atMillis, obj.long("usedTokens"), obj.long("windowTokens"))
            "plan" -> AgentEvent.PlanUpdate(
                atMillis,
                obj["entries"]?.jsonArray?.map { entry ->
                    AgentPlanEntry(entry.jsonObject.string("content").orEmpty(), entry.jsonObject.string("status").orEmpty())
                }.orEmpty(),
                obj.string("markdown"),
            )
            "mode" -> AgentEvent.ModeChanged(atMillis, obj.string("modeId").orEmpty())
            "modes" -> AgentEvent.AvailableModes(
                atMillis = atMillis,
                modes = obj["modes"]?.jsonArray?.map { mode ->
                    val value = mode.jsonObject
                    AgentSessionMode(
                        id = value.string("id").orEmpty(),
                        name = value.string("name").orEmpty(),
                        description = value.string("description"),
                    )
                }.orEmpty(),
                currentModeId = obj.string("currentModeId"),
            )
            "commands" -> AgentEvent.AvailableCommands(atMillis, obj["commands"]?.jsonArray?.map { command ->
                AgentSlashCommand(command.jsonObject.string("name").orEmpty(), command.jsonObject.string("description").orEmpty(), command.jsonObject.string("inputHint"))
            }.orEmpty())
            "permission" -> AgentEvent.PermissionRequest(atMillis, obj.string("requestId").orEmpty(), obj.string("toolName").orEmpty(), obj.string("question").orEmpty(), obj["options"]?.jsonArray?.map { option ->
                AgentUserInputOption(option.jsonObject.string("label").orEmpty(), option.jsonObject.string("description").orEmpty())
            }.orEmpty())
            "permission-resolved" -> AgentEvent.PermissionResolved(
                atMillis,
                obj.string("requestId").orEmpty(),
                obj.string("optionId").orEmpty(),
                obj.bool("allowed"),
                obj.string("note"),
            )
            "raw" -> AgentEvent.Raw(atMillis, obj.string("line").orEmpty())
            else -> null
        }
    }

    /** Serializes provenance for `chat.start`; never includes a local filesystem path. */
    private fun AgentContextualProvenance.toJsonObject(): JsonObject = buildJsonObject {
        put("sourceKind", sourceKind.name)
        investigationId?.let { put("investigationId", it) }
        eventId?.let { put("eventId", it) }
        playbackMillis?.let { put("playbackMillis", it) }
        networkExchangeId?.let { put("networkExchangeId", it) }
        crashId?.let { put("crashId", it) }
        hierarchyNodeId?.let { put("hierarchyNodeId", it) }
        packageName?.let { put("packageName", it) }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean =
        this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: this[key]?.jsonPrimitive?.booleanOrNull
            ?: false

    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: this[key]?.jsonPrimitive?.longOrNull

    private fun patchTask(taskId: String, transform: (AgentTask) -> AgentTask) {
        _tasks.value = _tasks.value.map { if (it.id == taskId) transform(it) else it }
    }

    private fun callTaskMutation(tool: String, taskId: String, onApplied: (() -> Unit)? = null) {
        scope.launch {
            runCatching {
                callTool(tool, mapOf("taskId" to JsonPrimitive(taskId)))
            }.onSuccess {
                onApplied?.invoke()
            }.onFailure { error ->
                // Local clientReadTaskIds clear the badge for this session only;
                // if the daemon RPC fails (e.g. stale andyd without chat.mark_read),
                // unread returns after GUI restart.
                System.err.println("andy: $tool failed for $taskId: ${error.message}")
            }
            runCatching { refreshTasks() }
        }
    }

    private suspend fun refreshComposerOptions() {
        val raw = runCatching { callTool("chat.composer_options", emptyMap()) }.getOrNull() ?: return
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val agents = root["agents"]?.jsonArray ?: return
        val fromDaemon = agents.mapNotNull(::parseDaemonAgentStatus).associateBy { it.kind }
        val localByKind = if (fromDaemon.keys.containsAll(AgentKind.entries)) {
            emptyMap()
        } else {
            withContext(Dispatchers.IO) { cliLocator.locateAll(emptyMap()).associateBy { it.kind } }
        }
        _cliStatuses.value = AgentKind.entries.map { kind ->
            fromDaemon[kind] ?: statusForDaemonUnknownAgent(kind, localByKind[kind])
        }
    }

    private suspend fun refreshProviderDefaults() {
        val raw = runCatching { callTool("chat.provider_preferences", emptyMap()) }.getOrNull() ?: return
        val providers = runCatching { json.parseToJsonElement(raw).jsonObject["providers"]?.jsonArray }.getOrNull()
            ?: return
        _providerDefaults.value = providers.mapNotNull { element ->
            val obj = element.jsonObject
            val agent = AgentKind.entries.firstOrNull { it.name == obj.string("agent") } ?: return@mapNotNull null
            val lane = AgentLaneKind.entries.firstOrNull { it.name == obj.string("lane") }
                ?: agent.defaultLane()
            agent to AgentProviderDefaults(lane = lane)
        }.toMap()
    }

    private fun parseDaemonAgentStatus(element: kotlinx.serialization.json.JsonElement): AgentCliStatus? {
        val obj = element.jsonObject
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val kind = AgentKind.entries.firstOrNull { it.name == id } ?: return null
        val available = obj["available"]?.jsonPrimitive?.booleanOrNull
            ?: obj["available"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: false
        val ready = obj["ready"]?.jsonPrimitive?.booleanOrNull
            ?: obj["ready"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: false
        val version = obj["version"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val issueTitle = obj["issue"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return AgentCliStatus(
            kind = kind,
            // [AgentCliStatus.ready] is derived from binaryPath + issue; mirror daemon readiness.
            binaryPath = if (ready) kind.cliName else null,
            acpReady = obj["acpReady"]?.jsonPrimitive?.booleanOrNull == true,
            version = version,
            issue = when {
                issueTitle != null -> AgentCliIssue(
                    title = issueTitle,
                    detail = issueTitle,
                    blocksTasks = !ready,
                )
                available && !ready -> AgentCliIssue(
                    title = "${kind.label} unavailable on daemon",
                    detail = "Install or repair ${kind.cliName} where andyd is running.",
                    blocksTasks = true,
                )
                else -> null
            },
        )
    }

    private suspend fun callTool(name: String, arguments: Map<String, kotlinx.serialization.json.JsonElement>): String =
        withContext(Dispatchers.IO) {
            if (!socketPath.exists()) error("andyd socket missing: ${socketPath.absolutePath}")
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(socketPath.toPath()))
                val reader = BufferedReader(Channels.newReader(channel, Charsets.UTF_8))
                val writer = BufferedWriter(Channels.newWriter(channel, Charsets.UTF_8))

                // Minimal initialize handshake
                val initId = idSeq.getAndIncrement()
                writer.write(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", initId)
                        put("method", "initialize")
                        put(
                            "params",
                            buildJsonObject {
                                put("protocolVersion", "2024-11-05")
                                put("capabilities", buildJsonObject {})
                                put(
                                    "clientInfo",
                                    buildJsonObject {
                                        put("name", "andy-gui")
                                        put("version", "1.0.0")
                                    },
                                )
                            },
                        )
                    }.toString(),
                )
                writer.write("\n")
                writer.flush()
                reader.readLine() // init result
                writer.write(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("method", "notifications/initialized")
                    }.toString(),
                )
                writer.write("\n")
                writer.flush()

                val callId = idSeq.getAndIncrement()
                writer.write(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", callId)
                        put("method", "tools/call")
                        put(
                            "params",
                            buildJsonObject {
                                put("name", name)
                                put("arguments", JsonObject(arguments))
                            },
                        )
                    }.toString(),
                )
                writer.write("\n")
                writer.flush()

                val line = reader.readLine() ?: error("no response for $name")
                val root = json.parseToJsonElement(line).jsonObject
                val error = root["error"]?.jsonObject
                if (error != null) {
                    error(error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString())
                }
                val result = root["result"]?.jsonObject ?: return@use ""
                val content = result["content"]?.jsonArray ?: return@use result.toString()
                val text = content.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                    ?: content.toString()
                // MCP tools/call can succeed at the JSON-RPC layer while returning
                // isError=true (unknown tool, validation, etc.). Treat that as failure
                // so mark_read is not silently dropped against a stale andyd.
                if (result["isError"]?.jsonPrimitive?.booleanOrNull == true) {
                    error(text.ifBlank { "$name failed" })
                }
                text
            }
        }

    override suspend fun refreshProviderQuotas() = Unit
    override fun setQuotaAccess(agent: AgentKind, enabled: Boolean) = Unit
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

    override fun slashCommands(agent: AgentKind, directory: String?) = MutableStateFlow(emptyList<AgentSlashCommand>())

    override fun refreshSlashCommands(agent: AgentKind, directory: String?) = Unit

    override suspend fun createAndStart(draft: AgentTaskDraft): AgentTask {
        val raw = callTool(
            "chat.start",
            buildMap {
                put("prompt", JsonPrimitive(draft.prompt))
                put("agent", JsonPrimitive(draft.agent.name))
                put("title", JsonPrimitive(draft.title))
                draft.projectId?.let { put("projectId", JsonPrimitive(it)) }
                draft.directory?.let { put("directory", JsonPrimitive(it)) }
                put("useWorktree", JsonPrimitive(draft.useWorktree))
                draft.existingWorktreePath?.takeIf { it.isNotBlank() }?.let {
                    put("existingWorktreePath", JsonPrimitive(it))
                }
                draft.baseWorktreeTaskId?.let { put("baseWorktreeTaskId", JsonPrimitive(it)) }
                put("attachAndyMcp", JsonPrimitive(draft.attachAndyMcp))
                put("autonomy", JsonPrimitive(draft.autonomy.name))
                draft.model?.takeIf { it.isNotBlank() }?.let { put("model", JsonPrimitive(it)) }
                if (draft.imagePaths.isNotEmpty()) {
                    put("imagePaths", JsonArray(draft.imagePaths.map { JsonPrimitive(it) }))
                }
                // Managed evidence bundle ids only (§4) — never a local filesystem path.
                if (draft.contextBundleIds.isNotEmpty()) {
                    put("contextBundleIds", JsonArray(draft.contextBundleIds.map { JsonPrimitive(it) }))
                }
                draft.provenance?.let { put("provenance", it.toJsonObject()) }
                draft.lane?.let { put("lane", JsonPrimitive(it.name)) }
            },
        )
        refreshTasks()
        val id = runCatching {
            json.parseToJsonElement(raw).jsonObject["id"]?.jsonPrimitive?.content
        }.getOrNull()
        return _tasks.value.firstOrNull { it.id == id }
            ?: AgentTask(
                id = id ?: UUID.randomUUID().toString(),
                title = draft.title,
                prompt = draft.prompt,
                agent = draft.agent,
                projectId = draft.projectId,
                cwd = draft.directory,
                attachAndyMcp = draft.attachAndyMcp,
                status = app.andy.model.AgentStatus.Working,
                createdAtMillis = System.currentTimeMillis(),
            )
    }

    override fun stop(taskId: String) {
        scope.launch { callTool("chat.stop", mapOf("taskId" to JsonPrimitive(taskId))) }
    }

    override fun completeWorkflowRun(taskId: String) = Unit
    override suspend fun retry(taskId: String) = Unit

    override fun setProviderLane(agent: AgentKind, lane: AgentLaneKind) {
        scope.launch {
            runCatching {
                callTool(
                    "chat.set_provider_lane",
                    mapOf("agent" to JsonPrimitive(agent.name), "lane" to JsonPrimitive(lane.name)),
                )
            }
            refreshProviderDefaults()
        }
    }

    override fun resume(
        taskId: String,
        followUp: String,
        imagePaths: List<String>,
        skills: List<AgentSkill>,
        contextBundleIds: List<String>,
        provenance: app.andy.model.AgentContextualProvenance?,
    ) {
        scope.launch {
            callTool(
                "chat.resume",
                buildMap {
                    put("taskId", JsonPrimitive(taskId))
                    put("followUp", JsonPrimitive(followUp))
                    if (imagePaths.isNotEmpty()) {
                        put("imagePaths", JsonArray(imagePaths.map { JsonPrimitive(it) }))
                    }
                    // Managed evidence bundle ids only (§4) — never a local filesystem path.
                    if (contextBundleIds.isNotEmpty()) {
                        put("contextBundleIds", JsonArray(contextBundleIds.map { JsonPrimitive(it) }))
                    }
                },
            )
        }
    }

    override fun reattachSession(taskId: String) {
        localBridge?.reattachSession(taskId)
    }

    override fun canReattachSession(taskId: String): Boolean =
        localBridge?.canReattachSession(taskId) == true

    override fun isTerminalLive(taskId: String): Boolean =
        localBridge?.isTerminalLive(taskId) == true

    override fun isLaneLive(taskId: String): Boolean =
        localBridge?.isLaneLive(taskId) == true

    override fun isViewing(taskId: String): Boolean =
        appForeground && (clientViewingTaskId.get() == taskId || localBridge?.isChatOpen(taskId) == true)

    override fun setAppForeground(foreground: Boolean) {
        if (appForeground == foreground) return
        appForeground = foreground
        localBridge?.setAppForeground(foreground)
        publishAppForeground()
        if (foreground) clientViewingTaskId.get()?.let(::markRead)
    }

    /**
     * Pushes window focus to the daemon, which owns unread. Sends are serialized and always
     * carry the *current* state rather than the value captured at call time, so a burst of
     * transitions converges on the latest one instead of racing. Older andyd builds without
     * the tool simply keep the previous (always-foreground) behaviour.
     */
    private fun publishAppForeground() {
        scope.launch {
            appFocusRpcMutex.withLock {
                val desired = appForeground
                if (lastSentAppForeground == desired) return@withLock
                val sent = runCatching {
                    callTool("chat.set_app_focus", mapOf("focused" to JsonPrimitive(desired)))
                }.isSuccess
                if (sent) lastSentAppForeground = desired
            }
        }
    }

    override fun respondToUserInput(taskId: String, requestId: String, answers: Map<String, String>) {
        scope.launch {
            callTool(
                "chat.respond",
                mapOf(
                    "taskId" to JsonPrimitive(taskId),
                    "requestId" to JsonPrimitive(requestId),
                    "answers" to JsonObject(answers.mapValues { JsonPrimitive(it.value) }),
                ),
            )
        }
    }

    override fun setAcpSessionMode(taskId: String, modeId: String) {
        scope.launch {
            callTool(
                "chat.set_mode",
                mapOf(
                    "taskId" to JsonPrimitive(taskId),
                    "modeId" to JsonPrimitive(modeId),
                ),
            )
        }
    }

    override fun queueFollowUp(
        taskId: String,
        followUp: String,
        imagePaths: List<String>,
        skills: List<AgentSkill>,
        contextBundleIds: List<String>,
        provenance: app.andy.model.AgentContextualProvenance?,
    ) {
        scope.launch {
            callTool(
                "chat.queue_follow_up",
                buildMap {
                    put("taskId", JsonPrimitive(taskId))
                    put("followUp", JsonPrimitive(followUp))
                    if (imagePaths.isNotEmpty()) {
                        put("imagePaths", JsonArray(imagePaths.map { JsonPrimitive(it) }))
                    }
                    if (contextBundleIds.isNotEmpty()) {
                        put("contextBundleIds", JsonArray(contextBundleIds.map { JsonPrimitive(it) }))
                    }
                },
            )
        }
    }

    override fun removeQueuedFollowUp(taskId: String, queueIndex: Int) {
        scope.launch {
            callTool(
                "chat.remove_queued_follow_up",
                mapOf(
                    "taskId" to JsonPrimitive(taskId),
                    "queueIndex" to JsonPrimitive(queueIndex),
                ),
            )
        }
    }

    override fun sendNextQueuedFollowUp(taskId: String) {
        scope.launch { callTool("chat.send_next_queued_follow_up", mapOf("taskId" to JsonPrimitive(taskId))) }
    }

    override fun updateGoal(taskId: String, goal: String?) = Unit

    override fun updatePlanMode(taskId: String, planMode: Boolean) {
        scope.launch {
            callTool(
                "chat.update_plan_mode",
                mapOf(
                    "taskId" to JsonPrimitive(taskId),
                    "planMode" to JsonPrimitive(planMode),
                ),
            )
        }
    }
    override suspend fun delete(taskId: String, removeWorktree: Boolean, force: Boolean): WorktreeDeleteOutcome {
        val result = callTool(
            "chat.delete",
            mapOf(
                "taskId" to JsonPrimitive(taskId),
                "removeWorktree" to JsonPrimitive(removeWorktree),
                "force" to JsonPrimitive(force),
            ),
        )
        parseBlockedByChildren(result)?.let { return it }
        // The daemon has confirmed deletion. Publish it immediately rather than blocking the
        // interaction on two follow-up RPCs (composer options + the full chat list).
        locallyDeletedTaskIds += taskId
        _tasks.value = _tasks.value.mapNotNull { task ->
            when {
                task.id == taskId -> null
                task.parentWorktreeTaskId == taskId -> task.copy(parentWorktreeTaskId = null)
                else -> task
            }
        }
        scope.launch { runCatching { refreshTasks() } }
        return WorktreeDeleteOutcome.Deleted
    }

    override fun markRead(taskId: String) {
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        // Skip only once the daemon list agrees the chat is read (id dropped from
        // clientReadTaskIds). A local ack alone must still RPC — otherwise quit/restart
        // reloads unread=true from andyd.
        if (!task.unread && taskId !in clientReadTaskIds) return
        acknowledgeRead(taskId)
        callTaskMutation("chat.mark_read", taskId) { daemonAckedReadTaskIds += taskId }
    }

    override fun markUnread(taskId: String) {
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        if (task.unread) return
        // A deliberate unread supersedes any in-flight read ack for the same chat.
        clientReadTaskIds -= taskId
        daemonAckedReadTaskIds -= taskId
        patchTask(taskId) { it.copy(unread = true) }
        callTaskMutation("chat.mark_unread", taskId)
    }

    override fun setChatViewing(taskId: String?, viewing: Boolean) {
        when {
            taskId == null -> clientViewingTaskId.set(null)
            viewing -> clientViewingTaskId.set(taskId)
            clientViewingTaskId.get() == taskId -> clientViewingTaskId.set(null)
        }
        localBridge?.setChatViewing(taskId, viewing)
        // Persist read on the daemon. Local acknowledge alone is wiped on GUI restart;
        // chat.set_viewing may also mark read, but older andyd builds only track viewing.
        if (viewing && taskId != null) {
            markRead(taskId)
        }
        scope.launch {
            runCatching {
                callTool(
                    "chat.set_viewing",
                    buildMap {
                        put("viewing", JsonPrimitive(viewing))
                        if (taskId != null) put("taskId", JsonPrimitive(taskId))
                    },
                )
            }
        }
    }

    override fun releaseTerminalViewer(taskId: String) {
        localBridge?.releaseTerminalViewer(taskId)
    }

    override fun archive(taskId: String) {
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        if (task.archived || task.isActive) return
        patchTask(taskId) { it.copy(archived = true, unread = false) }
        callTaskMutation("chat.archive", taskId)
    }

    override fun unarchive(taskId: String) {
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        if (!task.archived) return
        patchTask(taskId) { it.copy(archived = false) }
        callTaskMutation("chat.unarchive", taskId)
    }
    override fun events(taskId: String): StateFlow<List<AgentEvent>> {
        val task = _tasks.value.firstOrNull { it.id == taskId }
        val lane = task?.lane ?: AgentLaneKind.Terminal
        if (lane != AgentLaneKind.Acp) return emptyEvents
        val flow = eventFlows.computeIfAbsent(taskId) {
            MutableStateFlow(localTranscriptStore.load(taskId))
        }
        if (eventLoads.add(taskId)) {
            scope.launch {
                try {
                    do {
                        runCatching {
                            val raw = callTool("chat.events", mapOf("taskId" to JsonPrimitive(taskId)))
                            val parsed = json.parseToJsonElement(raw).jsonArray.mapNotNull(::parseRemoteEvent)
                            if (parsed.isNotEmpty()) {
                                flow.value = parsed
                            }
                        }
                        delay(500)
                    } while (isActive && _tasks.value.firstOrNull { it.id == taskId }?.isActive == true)
                } finally {
                    eventLoads.remove(taskId)
                }
            }
        }
        return flow
    }

    override fun interactiveResumeCommand(taskId: String): String? =
        "tmux -L andy attach -t ${TmuxAndy.sessionName(taskId)}"

    override suspend fun openInTerminal(taskId: String): CommandResult = withContext(Dispatchers.IO) {
        val cmd = interactiveResumeCommand(taskId) ?: return@withContext CommandResult.failure("missing task")
        runCatching {
            ProcessBuilder("/bin/sh", "-c", cmd).start()
            CommandResult.success("attached")
        }.getOrElse { CommandResult.failure(it.message ?: "failed") }
    }

    override suspend fun openSkill(path: String): CommandResult = CommandResult.failure("not available in client mode")
    override suspend fun worktreeDiffSummary(taskId: String): String? = null
    override suspend fun changeSummary(taskId: String): AgentChangeSummary? = null
    override suspend fun fileDiff(taskId: String, relativePath: String): AgentFileDiff? = null
    override suspend fun refreshCliStatuses() {
        refreshComposerOptions()
    }
    override suspend fun isGitRepo(dir: String): Boolean = withContext(Dispatchers.IO) {
        localWorktrees.isGitRepo(dir)
    }
    override suspend fun currentBranch(dir: String): String? = withContext(Dispatchers.IO) {
        localWorktrees.currentBranch(dir)
    }
    override suspend fun worktreeBaseOptions(originDir: String): List<WorktreeBaseOption> {
        val onDiskPaths = withContext(Dispatchers.IO) {
            localWorktrees.listAll(originDir).mapTo(linkedSetOf()) { canonicalPath(it.path) }
        }
        return _tasks.value.filter { task ->
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
        val onDisk = withContext(Dispatchers.IO) { localWorktrees.listAll(originDir) }
        val trackedByPath = _tasks.value
            .filter { it.originDir == originDir && it.worktreePath != null }
            .groupBy { canonicalPath(it.worktreePath!!) }
            .mapValues { (_, group) ->
                group.firstOrNull { it.ownsWorktree }
                    ?: group.minByOrNull { it.createdAtMillis }
                    ?: group.first()
            }
        return onDisk.map { info ->
            val task = trackedByPath[canonicalPath(info.path)]
            WorktreeNode(
                path = info.path,
                branch = info.branch,
                isMain = info.isMain,
                taskId = task?.id,
                taskTitle = task?.title,
                parentTaskId = task?.parentWorktreeTaskId?.takeIf { pid ->
                    onDisk.any { trackedByPath[canonicalPath(it.path)]?.id == pid }
                },
                tracked = task != null,
            )
        }
    }
    override fun mergeCommand(targetDir: String, branch: String): String =
        localWorktrees.mergeCommand(targetDir, branch)

    override suspend fun mergeBranch(
        targetDir: String,
        branch: String,
        sourceWorktreePath: String?,
    ): WorktreeMergeOutcome =
        withContext(Dispatchers.IO) { localWorktrees.merge(targetDir, branch, sourceWorktreePath) }

    override suspend fun abortMerge(targetDir: String): Result<Unit> =
        withContext(Dispatchers.IO) { localWorktrees.abortMerge(targetDir) }

    private fun canonicalPath(path: String): String =
        runCatching { File(path).canonicalPath }.getOrElse { path }

    private fun parseBlockedByChildren(raw: String): WorktreeDeleteOutcome.BlockedByChildren? {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        if (obj["blockedByChildren"]?.jsonPrimitive?.booleanOrNull != true) return null
        val children = obj["children"]?.jsonArray.orEmpty().mapNotNull { element ->
            val child = element as? JsonObject ?: return@mapNotNull null
            WorktreeBaseOption(
                taskId = child["taskId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                title = child["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                branch = child["branch"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                path = child["path"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
        return WorktreeDeleteOutcome.BlockedByChildren(children)
    }

    // ProjectWorkflowService — minimal remote stubs
    override suspend fun projectContextDir(projectId: String): String? = null
    override suspend fun ensureProject(projectId: String) {
        if (projectId in _projects.value) return
        _projects.update { it + (projectId to ProjectWorkflowState(projectId)) }
    }
    override suspend fun updateScratchpad(projectId: String, text: String) = Unit
    override suspend fun updateProfile(projectId: String, kind: ProjectTaskKind, profile: ProjectAgentProfile) = Unit
    override suspend fun saveSpec(draft: ProjectSpecDraft): String {
        val raw = callTool(
            "workflow.save_spec",
            buildMap {
                put("projectId", JsonPrimitive(draft.projectId))
                put("title", JsonPrimitive(draft.title))
                put("brief", JsonPrimitive(draft.brief))
                draft.taskId?.let { put("taskId", JsonPrimitive(it)) }
                put("agent", JsonPrimitive(draft.profile.agent.name))
                draft.profile.model?.let { put("model", JsonPrimitive(it)) }
                put("includeScratchpad", JsonPrimitive(draft.includeScratchpad))
                put("grillMeEnabled", JsonPrimitive(draft.grillMeEnabled))
            },
        )
        return runCatching {
            json.parseToJsonElement(raw).jsonObject["taskId"]?.jsonPrimitive?.content
        }.getOrNull() ?: draft.taskId ?: error("saveSpec returned no task id: $raw")
    }
    override suspend fun runSpec(taskId: String, revisionRequest: String?) {
        callTool(
            "workflow.run_spec",
            buildMap {
                put("taskId", JsonPrimitive(taskId))
                revisionRequest?.let { put("revisionRequest", JsonPrimitive(it)) }
            },
        )
    }

    override suspend fun saveBuildPair(draft: ProjectBuildPairDraft): String =
        error("saveBuildPair via MCP not yet wired")

    override suspend fun startBuildPair(buildTaskId: String) {
        callTool("workflow.start_build", mapOf("buildTaskId" to JsonPrimitive(buildTaskId)))
    }

    override fun pauseBuildPair(buildTaskId: String) = Unit
    override fun stopBuildPair(buildTaskId: String) = Unit
    override suspend fun resumeBuildPair(buildTaskId: String) = Unit
    override suspend fun startRecoveryFollowUp(
        buildTaskId: String,
        followUp: String,
        imagePaths: List<String>,
    ): String? = null

    override suspend fun startRecoveryReview(buildTaskId: String): String? = null
    override suspend fun deleteTask(taskId: String, cascade: Boolean) = Unit
    override suspend fun deleteProject(projectId: String) = Unit
}

/** GUI-only fallback when a newer build knows about a provider the running `andyd` does not. */
internal fun statusForDaemonUnknownAgent(kind: AgentKind, local: AgentCliStatus?): AgentCliStatus {
    if (local?.available == true) {
        return AgentCliStatus(
            kind = kind,
            version = local.version,
            issue = AgentCliIssue(
                title = "Restart andyd",
                detail = "${kind.label} is installed at ${local.binaryPath}, but the running andyd predates it. Restart with ./gradlew runAndyd.",
                blocksTasks = true,
            ),
        )
    }
    return AgentCliStatus(kind = kind)
}
