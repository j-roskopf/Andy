package app.andy.desktop.service.webchat

import app.andy.domain.excludingTemporary
import app.andy.model.AgentLaneKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.service.AgentAttentionEvent
import app.andy.service.AgentAttentionKind
import app.andy.service.AgentRunService
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Host-side attention fan-out for Network Access remotes (Android WS, Web Push).
 *
 * ACP tasks (Network Access) notify on status change even before scrape confidence —
 * otherwise remotes never see completions that Mac already showed. Non-ACP still
 * requires [AgentTask.statusConfident] to avoid terminal scrape flicker.
 */
class AttentionHub(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val sameKindWindowMs: Long = SAME_KIND_WINDOW_MS,
) {
    private data class TrackedStatus(
        val status: AgentStatus?,
        val confident: Boolean,
    )

    private val previous = mutableMapOf<String, TrackedStatus>()
    private val recentByKey = ConcurrentHashMap<String, Long>()
    private var seeded = false
    private var watching = false

    private val _events = MutableSharedFlow<AgentAttentionEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<AgentAttentionEvent> = _events.asSharedFlow()

    fun startWatching(agentRuns: AgentRunService) {
        if (watching) return
        watching = true
        scope.launch {
            agentRuns.tasks.collect { tasks ->
                runCatching { onTasksChanged(tasks.excludingTemporary()) }
            }
        }
    }

    /** Evaluate a task snapshot; returns events that were emitted. */
    fun onTasksChanged(tasks: List<AgentTask>): List<AgentAttentionEvent> {
        if (!seeded) {
            tasks.forEach { previous[it.id] = TrackedStatus(it.status, it.statusConfident) }
            seeded = true
            return emptyList()
        }
        val emitted = mutableListOf<AgentAttentionEvent>()
        for (task in tasks) {
            val prior = previous.put(task.id, TrackedStatus(task.status, task.statusConfident))
                ?: continue
            val kind = when (task.status) {
                AgentStatus.Blocked -> AgentAttentionKind.Blocked
                AgentStatus.Done -> AgentAttentionKind.Done
                AgentStatus.Error -> AgentAttentionKind.Error
                else -> null
            }
            val statusChanged = prior.status != task.status
            val becameConfident = task.statusConfident && !prior.confident
            if (kind == null || (!statusChanged && !becameConfident)) continue
            // Terminal scrape needs confidence; ACP Network Access chats use status alone.
            val acpSoftOk = task.lane == AgentLaneKind.Acp && statusChanged
            if (!task.statusConfident && !acpSoftOk) continue
            if (kind == AgentAttentionKind.Done && task.queuedFollowUps.isNotEmpty()) continue
            if (task.automationSuppressOsNotify) continue
            if (task.automationNotifyFailedOnly && kind == AgentAttentionKind.Done) continue
            if (!tryMarkNotified(task.id, kind.name)) continue
            val event = AgentAttentionEvent(
                taskId = task.id,
                projectId = task.projectId,
                title = task.notificationTitle,
                kind = kind,
                planMode = kind == AgentAttentionKind.Done && task.planMode,
            )
            _events.tryEmit(event)
            emitted += event
        }
        previous.keys.retainAll(tasks.map { it.id }.toSet())
        return emitted
    }

    fun resetForTests() {
        previous.clear()
        recentByKey.clear()
        seeded = false
    }

    private fun tryMarkNotified(taskId: String, kind: String): Boolean {
        val key = "$taskId:$kind"
        val now = nowMillis()
        val last = recentByKey[key]
        if (last != null && now - last < sameKindWindowMs) return false
        recentByKey[key] = now
        return true
    }

    companion object {
        private const val SAME_KIND_WINDOW_MS = 5_000L

        fun subtitle(kind: AgentAttentionKind, planMode: Boolean = false): String = when (kind) {
            AgentAttentionKind.Blocked -> "Needs your input"
            AgentAttentionKind.Done -> if (planMode) "Plan ready" else "Agent completed"
            AgentAttentionKind.Error -> "Agent failed"
        }

        fun toWireJson(event: AgentAttentionEvent): String =
            buildJsonObject {
                put("kind", event.kind.name)
                put("taskId", event.taskId)
                put("projectId", event.projectId.orEmpty())
                put("title", event.title)
                put("subtitle", subtitle(event.kind, event.planMode))
                put("planMode", event.planMode)
            }.toString()
    }
}
