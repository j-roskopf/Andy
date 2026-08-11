package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentLaneKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import java.io.File

/** The local Andy MCP streamable-HTTP endpoint for provider attach. */
data class AndyMcpEndpoint(
    val port: Int,
    val httpUrl: String,
)

/** Appends `andyTaskId` so chat.start can inherit the parent's autonomy dial. */
internal fun mcpUrlWithCallerTaskId(baseUrl: String, taskId: String): String {
    val id = taskId.trim()
    if (id.isEmpty()) return baseUrl
    val sep = if ('?' in baseUrl) '&' else '?'
    return "$baseUrl${sep}andyTaskId=${java.net.URLEncoder.encode(id, Charsets.UTF_8)}"
}

data class LaneOutcome(
    val status: AgentStatus,
    val exitCode: Int? = null,
    val stopReason: String? = null,
    val error: String? = null,
)

/** A lane-owned run handle containing only the concepts shared by terminal and ACP. */
data class LaneHandle(
    val taskId: String,
    val artifacts: AgentWorkflowArtifacts,
    val artifactDir: File,
    val statusTracker: AgentStatusTracker? = null,
    val terminalHandle: AgentTerminalManager.Handle? = null,
)

/** Narrow transport contract; terminal-only rendering and lifecycle stay out of this API. */
interface AgentLane {
    val kind: AgentLaneKind

    suspend fun start(
        task: AgentTask,
        env: Map<String, String>,
        mcp: AndyMcpEndpoint?,
        onStatusSnapshot: (AgentStatusSnapshot) -> Unit = {},
    ): LaneHandle

    fun isAlive(taskId: String): Boolean
    suspend fun prompt(taskId: String, text: String, imagePaths: List<String> = emptyList()): Boolean
    fun cancelTurn(taskId: String)
    fun stop(taskId: String)
    fun detach(taskId: String)
    fun clear(taskId: String)
    suspend fun awaitRunOutcome(taskId: String): LaneOutcome
    fun liveStatus(taskId: String): AgentStatus?
    fun artifacts(taskId: String): AgentWorkflowArtifacts?
}

/** Compatibility lane around the unchanged tmux/BossTerm manager. */
class TerminalLane(
    private val terminals: AgentTerminalManager,
    private val commandFor: suspend (AgentTask, AndyMcpEndpoint?) -> List<String>,
) : AgentLane {
    override val kind: AgentLaneKind = AgentLaneKind.Terminal

    override suspend fun start(
        task: AgentTask,
        env: Map<String, String>,
        mcp: AndyMcpEndpoint?,
        onStatusSnapshot: (AgentStatusSnapshot) -> Unit,
    ): LaneHandle {
        val handle = terminals.start(
            task = task,
            argv = commandFor(task, mcp),
            env = env,
            onStatusSnapshot = onStatusSnapshot,
        )
        return LaneHandle(
            taskId = task.id,
            artifacts = handle.artifacts,
            artifactDir = handle.artifactDir,
            statusTracker = handle.statusTracker,
            terminalHandle = handle,
        )
    }

    override fun isAlive(taskId: String): Boolean = terminals.isAlive(taskId)

    override suspend fun prompt(taskId: String, text: String, imagePaths: List<String>): Boolean {
        if (!terminals.isAlive(taskId)) return false
        terminals.submitText(taskId, text)
        return true
    }

    override fun cancelTurn(taskId: String) {
        terminals.stop(taskId)
    }

    override fun stop(taskId: String) = terminals.stop(taskId)

    override fun detach(taskId: String) = terminals.detach(taskId)

    override fun clear(taskId: String) = terminals.clear(taskId)

    override suspend fun awaitRunOutcome(taskId: String): LaneOutcome {
        val exitCode = terminals.awaitExit(taskId)
        return LaneOutcome(
            status = if (exitCode == 0) AgentStatus.Done else AgentStatus.Error,
            exitCode = exitCode,
            error = if (exitCode == 0) null else "exited with code $exitCode",
        )
    }

    override fun liveStatus(taskId: String): AgentStatus? = terminals.liveSessionStatus(taskId)

    override fun artifacts(taskId: String): AgentWorkflowArtifacts? = terminals.get(taskId)?.artifacts
}

/**
 * HTTP MCP URL handed to ACP providers.
 *
 * Codex (CLI and ACP) speaks streamable HTTP only — legacy SSE `/mcp` is rejected
 * with `sessionId query parameter is not provided` on initialize.
 */
internal fun AgentKind.acpEndpointUrl(endpoint: AndyMcpEndpoint): String = endpoint.httpUrl
