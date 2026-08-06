package app.andy.desktop.service.agents.acp

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonElement
import app.andy.model.AgentAutonomy
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentUserInputOption
import app.andy.model.AgentUserInputOrigin
import app.andy.model.AgentUserInputQuestion
import app.andy.model.AgentUserInputRequest
import java.io.File

data class PendingAcpPermission(
    val request: AgentUserInputRequest,
    val options: List<PermissionOption>,
)

/**
 * Maps Andy's autonomy dial onto ACP permission choices.
 * Matches terminal adapters: explicit skip-permissions or full autonomy auto-allows.
 */
class AcpPermissionBridge(
    private val taskId: String,
    private val autonomy: AgentAutonomy,
    private val planMode: Boolean,
    private val sandboxMode: AgentSandboxMode?,
    /** When true, every tool call — including reads/searches that would otherwise auto-allow — is surfaced for approval. */
    private val confirmToolCalls: Boolean,
    private val cwd: File,
    private val onPending: (PendingAcpPermission) -> Unit,
    private val onResolved: (String, String, Boolean, String?) -> Unit,
) {
    private val pending = mutableMapOf<String, Pair<List<PermissionOption>, CompletableDeferred<RequestPermissionResponse>>>()

    suspend fun request(
        toolCall: SessionUpdate.ToolCallUpdate,
        options: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        val requestId = permissionRequestId(toolCall)
        val auto = autoSelect(toolCall, options)
        if (auto != null) {
            return RequestPermissionResponse(RequestPermissionOutcome.Selected(auto.optionId), _meta)
        }

        val deferred = CompletableDeferred<RequestPermissionResponse>()
        val request = buildPending(toolCall, requestId, options).request
        synchronized(pending) { pending[requestId] = options to deferred }
        onPending(PendingAcpPermission(request, options))
        return deferred.await()
    }

    fun respond(requestId: String, answer: String): Boolean {
        val entry = synchronized(pending) { pending[requestId] } ?: return false
        val selected = matchOption(entry.first, answer) ?: return false
        synchronized(pending) { pending.remove(requestId) }
        val deferred = entry.second
        val allowed = selected.kind == PermissionOptionKind.ALLOW_ONCE ||
            selected.kind == PermissionOptionKind.ALLOW_ALWAYS
        onResolved(requestId, selected.optionId.toString(), allowed, null)
        return deferred.complete(RequestPermissionResponse(RequestPermissionOutcome.Selected(selected.optionId), null))
    }

    fun cancelAll() {
        val entries = synchronized(pending) {
            val current = pending.toMap()
            pending.clear()
            current
        }
        entries.forEach { (requestId, pair) ->
            val (options, deferred) = pair
            if (deferred.isCompleted) return@forEach
            val reject = options.firstOrNull { it.kind == PermissionOptionKind.REJECT_ONCE }
                ?: options.firstOrNull { it.kind == PermissionOptionKind.REJECT_ALWAYS }
                ?: options.firstOrNull()
            if (reject == null) {
                deferred.cancel()
                return@forEach
            }
            val note = "session stopped before permission was answered"
            onResolved(requestId, reject.optionId.toString(), false, note)
            deferred.complete(RequestPermissionResponse(RequestPermissionOutcome.Selected(reject.optionId), null))
        }
    }

    private fun permissionRequestId(toolCall: SessionUpdate.ToolCallUpdate): String =
        "acp-permission-$taskId-${toolCall.toolCallId}"

    private fun buildPending(
        toolCall: SessionUpdate.ToolCallUpdate,
        requestId: String,
        options: List<PermissionOption>,
    ): PendingAcpPermission {
        val kindLabel = (toolCall.kind ?: ToolKind.OTHER).name.lowercase()
        val request = AgentUserInputRequest(
            id = requestId,
            origin = AgentUserInputOrigin.AcpPermission,
            questions = listOf(
                AgentUserInputQuestion(
                    id = requestId,
                    header = kindLabel,
                    question = (toolCall.title ?: "").ifBlank { "Allow $kindLabel?" },
                    options = options.map { option ->
                        AgentUserInputOption(option.name, "${option.kind.name.lowercase()} · ${option.optionId}")
                    }.distinctBy { it.label }.take(3),
                ),
            ),
        )
        return PendingAcpPermission(request, options)
    }

    private fun matchOption(options: List<PermissionOption>, answer: String): PermissionOption? {
        val trimmed = answer.trim()
        if (trimmed.isBlank()) return null
        return options.firstOrNull { it.name == trimmed || it.optionId.toString() == trimmed }
            ?: options.firstOrNull { option ->
                trimmed.equals(option.kind.name, ignoreCase = true) ||
                    trimmed.equals(option.kind.name.lowercase(), ignoreCase = true)
            }
    }

    private fun autoSelect(
        toolCall: SessionUpdate.ToolCallUpdate,
        options: List<PermissionOption>,
    ): PermissionOption? {
        if (options.isEmpty()) return null
        if (confirmToolCalls) return null
        val allow = options.filter {
            it.kind == PermissionOptionKind.ALLOW_ONCE || it.kind == PermissionOptionKind.ALLOW_ALWAYS
        }
        val reject = options.filter {
            it.kind == PermissionOptionKind.REJECT_ONCE || it.kind == PermissionOptionKind.REJECT_ALWAYS
        }
        return when {
            effectiveFullBypass() -> allow.firstOrNull() ?: reject.firstOrNull()
            toolCall.kind == ToolKind.READ || toolCall.kind == ToolKind.SEARCH || toolCall.kind == ToolKind.THINK ->
                allow.firstOrNull() ?: reject.firstOrNull()
            toolCall.kind == ToolKind.EDIT &&
                !planMode &&
                autonomy != AgentAutonomy.ReadOnly &&
                locationsInsideWorkspace(toolCall) ->
                allow.firstOrNull()
            else -> null
        }
    }

    private fun effectiveFullBypass(): Boolean {
        if (planMode) return false
        return when (sandboxMode) {
            AgentSandboxMode.None -> true
            null -> autonomy == AgentAutonomy.Full
            else -> false
        }
    }

    private fun locationsInsideWorkspace(toolCall: SessionUpdate.ToolCallUpdate): Boolean {
        val root = runCatching { cwd.canonicalFile.path.trimEnd(File.separatorChar) + File.separator }.getOrNull() ?: return false
        return toolCall.locations.orEmpty().isNotEmpty() && toolCall.locations.orEmpty().all { location ->
            runCatching {
                val path = File(location.path).let { if (it.isAbsolute) it else File(cwd, it.path) }
                path.canonicalFile.path.startsWith(root)
            }.getOrDefault(false)
        }
    }
}

/** Default operation object used by ACP sessions when no permission is pending. */
internal abstract class AcpOperationsBase : ClientSessionOperations {
    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) = Unit
}
