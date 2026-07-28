package app.andy.desktop.service

import app.andy.desktop.service.agents.DesktopAgentRunService
import app.andy.desktop.service.agents.discoverAgentSkills
import app.andy.model.AgentChangeSummary
import app.andy.model.AgentCliIssue
import app.andy.model.AgentCliStatus
import app.andy.model.AgentEvent
import app.andy.model.AgentFileDiff
import app.andy.model.AgentKind
import app.andy.model.AgentModelOption
import app.andy.model.AgentProviderDefaults
import app.andy.model.AgentProviderQuota
import app.andy.model.AgentQuotaAccess
import app.andy.model.AgentSkill
import app.andy.model.AgentTask
import app.andy.model.AgentTaskDraft
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
 * so the Compose GUI can still embed KetraTerm.
 */
class McpAgentRunClient(
    private val scope: CoroutineScope,
    private val socketPath: File,
) : AgentRunService, ProjectWorkflowService {
    private val json = Json { ignoreUnknownKeys = true }
    private val idSeq = AtomicLong(1)

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
    private val clientViewingTaskId = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /** Window visibility/focus. An open chat only counts as watched while the window is up. */
    @Volatile
    private var appForeground: Boolean = true

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

    private val skillFlows = ConcurrentHashMap<SkillScope, MutableStateFlow<List<AgentSkill>>>()
    private val loadedSkillScopes = ConcurrentHashMap.newKeySet<SkillScope>()
    private val emptyEvents = MutableStateFlow<List<AgentEvent>>(emptyList())

    private var localBridge: DesktopAgentRunService? = null

    private val _interactiveTerminalTaskIds = MutableStateFlow<Set<String>>(emptySet())
    /** Mirrors the local terminal bridge, which owns the KetraTerm viewers this GUI hosts. */
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

    /** Local KetraTerm/tmux-attach host used by [app.andy.ui.agents.AgentTerminalSurface]. */
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
        patchTask(taskId) { it.copy(unread = false) }
    }

    // While the window is away the open chat must keep whatever unread the daemon reports;
    // merging it as "viewing" would wipe the badge the user is meant to come back to.
    private fun viewingTaskIdsForMerge(): Set<String> =
        if (appForeground) clientViewingTaskId.get()?.let(::setOf).orEmpty() else emptySet()

    private suspend fun refreshTasks() {
        refreshComposerOptions()
        val raw = callTool("chat.list", emptyMap())
        val arr = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return
        // Keep a lightweight task list for the GUI; lifecycle fields must round-trip so
        // badges/labels match the daemon (startedAtMillis drives isQueued).
        val refreshedTasks = arr.mapNotNull { el -> parseListedTask(el.jsonObject) }
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
            prompt = "",
            agent = agent,
            projectId = obj.string("projectId")?.takeIf { it.isNotBlank() },
            cwd = obj.string("cwd")?.takeIf { it.isNotBlank() },
            status = app.andy.model.AgentStatus.entries.firstOrNull { it.name == statusName },
            stoppedByUser = obj.bool("stoppedByUser"),
            resumable = obj.bool("resumable"),
            interrupted = obj.bool("interrupted"),
            statusConfident = obj.bool("statusConfident"),
            vendorSessionId = obj.string("vendorSessionId")?.takeIf { it.isNotBlank() },
            createdAtMillis = obj.long("createdAtMillis") ?: 0L,
            startedAtMillis = obj.long("startedAtMillis")?.takeIf { it > 0 },
            finishedAtMillis = obj.long("finishedAtMillis")?.takeIf { it > 0 },
            exitCode = exitCodeRaw?.takeUnless { it == Int.MIN_VALUE.toLong() }?.toInt(),
            unread = obj.bool("unread"),
            archived = obj.bool("archived"),
        )
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

    private fun callTaskMutation(tool: String, taskId: String) {
        scope.launch {
            runCatching {
                callTool(tool, mapOf("taskId" to JsonPrimitive(taskId)))
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
        _cliStatuses.value = agents.mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val kind = AgentKind.entries.firstOrNull { it.name == id } ?: return@mapNotNull null
            val available = obj["available"]?.jsonPrimitive?.booleanOrNull
                ?: obj["available"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: false
            val ready = obj["ready"]?.jsonPrimitive?.booleanOrNull
                ?: obj["ready"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: false
            val version = obj["version"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val issueTitle = obj["issue"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            AgentCliStatus(
                kind = kind,
                // [AgentCliStatus.ready] is derived from binaryPath + issue; mirror daemon readiness.
                binaryPath = if (ready) kind.cliName else if (available) null else null,
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

    override fun refreshSkills(agent: AgentKind, directory: String?) {
        val normalizedDirectory = directory
            ?.takeIf { it.isNotBlank() }
            ?.let { path -> runCatching { File(path).canonicalPath }.getOrElse { path } }
        val skillScope = SkillScope(agent, normalizedDirectory)
        val flow = skillFlows.computeIfAbsent(skillScope) { MutableStateFlow(emptyList()) }
        loadedSkillScopes.add(skillScope)
        scope.launch(Dispatchers.IO) {
            flow.value = discoverAgentSkills(agent, normalizedDirectory)
        }
    }

    override suspend fun createAndStart(draft: AgentTaskDraft): AgentTask {
        val raw = callTool(
            "chat.start",
            buildMap {
                put("prompt", JsonPrimitive(draft.prompt))
                put("agent", JsonPrimitive(draft.agent.name))
                put("title", JsonPrimitive(draft.title))
                draft.projectId?.let { put("projectId", JsonPrimitive(it)) }
                draft.directory?.let { put("directory", JsonPrimitive(it)) }
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
                status = app.andy.model.AgentStatus.Working,
                createdAtMillis = System.currentTimeMillis(),
            )
    }

    override suspend fun startImplementation(taskId: String) = Unit
    override fun stop(taskId: String) {
        scope.launch { callTool("chat.stop", mapOf("taskId" to JsonPrimitive(taskId))) }
    }

    override fun completeWorkflowRun(taskId: String) = Unit
    override suspend fun retry(taskId: String) = Unit

    override fun resume(
        taskId: String,
        followUp: String,
        imagePaths: List<String>,
        skills: List<AgentSkill>,
    ) {
        scope.launch {
            callTool(
                "chat.resume",
                mapOf(
                    "taskId" to JsonPrimitive(taskId),
                    "followUp" to JsonPrimitive(followUp),
                ),
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

    override fun isViewing(taskId: String): Boolean =
        appForeground && (clientViewingTaskId.get() == taskId || localBridge?.isChatOpen(taskId) == true)

    override fun setAppForeground(foreground: Boolean) {
        if (appForeground == foreground) return
        appForeground = foreground
        localBridge?.setAppForeground(foreground)
        // The daemon owns unread, so it needs the same signal; older andyd builds without
        // the tool simply keep the previous (always-foreground) behaviour.
        scope.launch {
            runCatching {
                callTool("chat.set_app_focus", mapOf("focused" to JsonPrimitive(foreground)))
            }
        }
        if (foreground) clientViewingTaskId.get()?.let(::markRead)
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

    override fun queueFollowUp(
        taskId: String,
        followUp: String,
        imagePaths: List<String>,
        skills: List<AgentSkill>,
    ) = resume(taskId, followUp, imagePaths, skills)

    override fun removeQueuedFollowUp(taskId: String, queueIndex: Int) = Unit
    override fun updateGoal(taskId: String, goal: String?) = Unit
    override suspend fun delete(taskId: String, removeWorktree: Boolean) {
        callTool(
            "chat.delete",
            mapOf(
                "taskId" to JsonPrimitive(taskId),
                "removeWorktree" to JsonPrimitive(removeWorktree),
            ),
        )
        // The daemon has confirmed deletion. Publish it immediately rather than blocking the
        // interaction on two follow-up RPCs (composer options + the full chat list).
        locallyDeletedTaskIds += taskId
        _tasks.value = _tasks.value.filterNot { it.id == taskId }
        scope.launch { runCatching { refreshTasks() } }
    }

    override fun markRead(taskId: String) {
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        // Skip only once the daemon list agrees the chat is read (id dropped from
        // clientReadTaskIds). A local ack alone must still RPC — otherwise quit/restart
        // reloads unread=true from andyd.
        if (!task.unread && taskId !in clientReadTaskIds) return
        acknowledgeRead(taskId)
        callTaskMutation("chat.mark_read", taskId)
    }

    override fun markUnread(taskId: String) {
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        if (task.unread) return
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
    override fun events(taskId: String): StateFlow<List<AgentEvent>> = emptyEvents

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
    override suspend fun isGitRepo(dir: String): Boolean = File(dir, ".git").exists()

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
