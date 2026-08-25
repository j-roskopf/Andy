package app.andy.desktop.service.agents.acp

import app.andy.desktop.service.agents.AgentLane
import app.andy.desktop.service.agents.AgentStatusSnapshot
import app.andy.desktop.service.agents.AgentWorkflowArtifacts
import app.andy.desktop.service.agents.AndyMcpEndpoint
import app.andy.desktop.service.agents.LaneHandle
import app.andy.desktop.service.agents.LaneOutcome
import app.andy.desktop.service.agents.discoverAgentSkills
import app.andy.desktop.service.agents.discoverKnownAgentSkillNames
import app.andy.desktop.service.agents.normalizedAgentCommandName
import app.andy.model.AgentEvent
import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.model.AgentUserInputOption
import app.andy.model.runtimeKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Owns ACP processes independently from the terminal manager. */
class AgentAcpManager(
    private val scope: CoroutineScope,
    private val launcher: AcpProcessLauncher = AcpProcessLauncher(),
    private val binaryFor: (AgentKind) -> String?,
    private val onEvent: (String, AgentEvent) -> Unit,
    private val onStatus: (String, AgentStatusSnapshot) -> Unit,
    private val onPermission: (String, PendingAcpPermission) -> Unit,
    private val onPermissionResolved: (String, String, String, Boolean, String?) -> Unit,
    private val onSessionId: (String, String) -> Unit,
    private val onDiagnosticsLine: (String, String) -> Unit,
) : AgentLane {
    override val kind = app.andy.model.AgentLaneKind.Acp

    private data class Entry(
        val session: AcpSession,
        val bridge: AcpPermissionBridge,
        val artifacts: AgentWorkflowArtifacts,
        val artifactDir: File,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val outcomes = ConcurrentHashMap<String, LaneOutcome>()
    private val statuses = ConcurrentHashMap<String, AgentStatus>()

    override suspend fun start(
        task: AgentTask,
        env: Map<String, String>,
        mcp: AndyMcpEndpoint?,
        onStatusSnapshot: (AgentStatusSnapshot) -> Unit,
    ): LaneHandle {
        entries[task.id]?.let { existing ->
            if (existing.session.alive) {
                return LaneHandle(task.id, existing.artifacts, existing.artifactDir)
            }
            entries.remove(task.id, existing)
            existing.artifacts.close()
        }
        val spec = AcpRegistry.specFor(task) ?: error("${task.runtimeKind().cliName} has no ACP launcher")
        val binary = binaryFor(task.runtimeKind())
        val (knownSkillNames, allowedSkillNames) = withContext(Dispatchers.IO) {
            val allowed = discoverAgentSkills(task.runtimeKind(), task.cwd)
                .mapTo(linkedSetOf()) { it.name.normalizedAgentCommandName() }
            discoverKnownAgentSkillNames(task.cwd) to allowed
        }
        val processLauncher = launcher.withDiagnostics { line ->
            onDiagnosticsLine(task.id, line.trimEnd())
        }
        processLauncher.preflight(spec, binary, env).getOrElse { throw it }
        val artifactDir = AgentWorkflowArtifacts.dirFor(task.cwd?.let(::File), task.id)
        val artifacts = AgentWorkflowArtifacts(scope, task.id, artifactDir)
        val bridge = AcpPermissionBridge(
            taskId = task.id,
            autonomy = task.autonomy,
            planMode = task.planMode,
            sandboxMode = task.sandboxMode,
            confirmToolCalls = task.confirmToolCalls,
            cwd = File(task.cwd ?: error("ACP requires a cwd")),
            onPending = { onPermission(task.id, it) },
            onResolved = { requestId, optionId, allowed, note ->
                onPermissionResolved(task.id, requestId, optionId, allowed, note)
            },
        )
        val session = AcpSession(
            scope = scope,
            task = task,
            launcher = processLauncher,
            binary = binary,
            environment = env,
            mcp = mcp,
            bridge = bridge,
            artifacts = artifacts,
            knownSkillNames = knownSkillNames,
            allowedSkillNames = allowedSkillNames,
            onEvent = { event -> onEvent(task.id, event) },
            onStatus = { snapshot ->
                statuses[task.id] = snapshot.status
                onStatusSnapshot(snapshot)
                onStatus(task.id, snapshot)
            },
            onDiagnostics = { line ->
                onDiagnosticsLine(task.id, line.trimEnd())
            },
        )
        runCatching { session.open() }.onFailure {
            artifacts.close()
            session.stop()
            throw it
        }
        val entry = Entry(session, bridge, artifacts, artifactDir)
        entries[task.id] = entry
        session.sessionId?.let { onSessionId(task.id, it) }
        artifacts.start()
        return LaneHandle(task.id, artifacts, artifactDir)
    }

    override fun isAlive(taskId: String): Boolean = entries[taskId]?.session?.alive == true

    override suspend fun prompt(taskId: String, text: String, imagePaths: List<String>): Boolean {
        val entry = entries[taskId] ?: return false
        val success = entry.session.prompt(text, imagePaths)
        outcomes[taskId] = LaneOutcome(
            status = if (success) AgentStatus.Done else AgentStatus.Error,
            stopReason = entry.session.lastStopReason,
            error = if (success) {
                null
            } else {
                entry.session.lastPromptError?.takeIf { it.isNotBlank() } ?: "ACP prompt failed"
            },
        )
        return success
    }

    fun respondPermission(taskId: String, requestId: String, answer: String): Boolean =
        entries[taskId]?.bridge?.respond(requestId, answer) == true

    suspend fun setMode(taskId: String, modeId: String): Boolean =
        entries[taskId]?.session?.setMode(modeId) == true

    override fun cancelTurn(taskId: String) {
        entries[taskId]?.session?.cancelTurn()
    }

    override fun stop(taskId: String) {
        entries[taskId]?.session?.stop()
        statuses[taskId] = AgentStatus.Done
    }

    override fun detach(taskId: String) = Unit

    override fun clear(taskId: String) {
        entries.remove(taskId)?.let {
            it.artifacts.close()
            it.session.stop()
        }
        outcomes.remove(taskId)
        statuses.remove(taskId)
    }

    override suspend fun awaitRunOutcome(taskId: String): LaneOutcome =
        outcomes[taskId] ?: LaneOutcome(statuses[taskId] ?: AgentStatus.Error)

    override fun liveStatus(taskId: String): AgentStatus? = statuses[taskId]

    override fun artifacts(taskId: String): AgentWorkflowArtifacts? = entries[taskId]?.artifacts
}
