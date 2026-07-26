package app.andy.desktop.service

import app.andy.desktop.service.agents.DesktopAgentRunService
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

    private val skillFlows = ConcurrentHashMap<String, MutableStateFlow<List<AgentSkill>>>()
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
        scope.launch { refreshTasks() }
    }

    private suspend fun refreshTasks() {
        refreshComposerOptions()
        val raw = callTool("chat.list", emptyMap())
        val arr = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return
        // Keep a lightweight task list for the GUI; full AgentTask fields are filled where possible.
        _tasks.value = arr.mapNotNull { el ->
            val obj = el.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val agentName = obj["agent"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val agent = AgentKind.entries.firstOrNull { it.name == agentName } ?: return@mapNotNull null
            val statusName = obj["status"]?.jsonPrimitive?.contentOrNull
            AgentTask(
                id = id,
                title = obj["title"]?.jsonPrimitive?.contentOrNull ?: id,
                prompt = "",
                agent = agent,
                projectId = obj["projectId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                cwd = obj["cwd"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                status = app.andy.model.AgentStatus.entries.firstOrNull { it.name == statusName },
                createdAtMillis = obj["createdAtMillis"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    ?: obj["createdAtMillis"]?.jsonPrimitive?.longOrNull
                    ?: 0L,
                unread = obj["unread"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    ?: obj["unread"]?.jsonPrimitive?.booleanOrNull
                    ?: false,
                archived = obj["archived"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    ?: obj["archived"]?.jsonPrimitive?.booleanOrNull
                    ?: false,
            )
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
                content.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                    ?: content.toString()
            }
        }

    override suspend fun refreshProviderQuotas() = Unit
    override fun setQuotaAccess(agent: AgentKind, enabled: Boolean) = Unit
    override fun skills(agent: AgentKind, directory: String?): StateFlow<List<AgentSkill>> =
        skillFlows.computeIfAbsent("$agent:${directory.orEmpty()}") { MutableStateFlow(emptyList()) }

    override fun refreshSkills(agent: AgentKind, directory: String?) = Unit

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
        localBridge?.isViewing(taskId) == true

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
        stop(taskId)
    }

    override fun markRead(taskId: String) = Unit
    override fun markUnread(taskId: String) = Unit
    override fun setChatViewing(taskId: String?, viewing: Boolean) {
        localBridge?.setChatViewing(taskId, viewing)
    }
    override fun archive(taskId: String) = Unit
    override fun unarchive(taskId: String) = Unit
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
    override suspend fun ensureProject(projectId: String) = Unit
    override suspend fun updateScratchpad(projectId: String, text: String) = Unit
    override suspend fun updateProfile(projectId: String, kind: ProjectTaskKind, profile: ProjectAgentProfile) = Unit
    override suspend fun saveSpec(draft: ProjectSpecDraft): String = error("saveSpec via MCP not yet wired")
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
