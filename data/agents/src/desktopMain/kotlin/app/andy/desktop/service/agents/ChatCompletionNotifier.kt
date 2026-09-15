package app.andy.desktop.service.agents

import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.service.AgentRunService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class ChatAwaitUntil {
    Terminal,
    Blocked,
    Any,
}

data class ChatAwaitResult(
    val taskId: String? = null,
    val status: AgentStatus? = null,
    val timedOut: Boolean = false,
)

/**
 * Watches [AgentRunService.tasks] for status edges used by swarm leads:
 * - parent follow-up when a child with [AgentTask.notifyParentOnCompletion] goes terminal
 * - [awaitAny] long-poll backing the `chat.await` MCP tool
 */
class ChatCompletionNotifier(
    private val agentRuns: AgentRunService,
    private val scope: CoroutineScope,
    /** Cap concurrent [awaitAny] waiters so leaked MCP calls cannot starve Andy. */
    private val maxConcurrentAwaits: Int = 32,
) {
    // ConcurrentHashMap rejects null values, and a queued/relaunching task has a null status.
    // Track only known statuses and drop the entry on null so the next non-null is a fresh edge.
    private val lastStatus = ConcurrentHashMap<String, AgentStatus>()
    /** Dedupes follow-ups per (taskId, edge-status) so flaps cannot spam the parent. */
    private val notifiedEdges = ConcurrentHashMap.newKeySet<String>()
    private val awaitCount = AtomicInteger(0)
    private val awaitJobs = ConcurrentHashMap.newKeySet<Job>()
    private val startMutex = Mutex()
    @Volatile private var started = false

    fun start() {
        scope.launch {
            // Store load is async; seeding before hydration treats every Done worker as a
            // fresh null→Done edge and re-wakes finished swarm leads on every app restart.
            agentRuns.awaitTasksLoaded()
            val shouldCollect = startMutex.withLock {
                if (started) return@withLock false
                started = true
                agentRuns.tasks.value.forEach { task ->
                    task.status?.let { lastStatus[task.id] = it }
                }
                true
            }
            if (!shouldCollect) return@launch
            agentRuns.tasks
                .map { tasks -> tasks.associateBy { it.id } }
                .distinctUntilChanged()
                .collect { byId -> processSnapshot(byId) }
        }
    }

    /** Cancel outstanding awaits (e.g. MCP session close). */
    fun cancelAwaits() {
        awaitJobs.toList().forEach { it.cancel() }
        awaitJobs.clear()
        awaitCount.set(0)
    }

    internal fun processSnapshot(byId: Map<String, AgentTask>) {
        byId.values.forEach { task ->
            val current = task.status
            if (current == null) {
                lastStatus.remove(task.id)
                return@forEach
            }
            val previous = lastStatus.put(task.id, current)
            // First sight is seed-only. Hydration (and late list merges) must not look like
            // a live Working→Done edge — that re-queues "worker finished" into Done leads.
            if (previous == null || previous == current) return@forEach
            if (current == AgentStatus.Done || current == AgentStatus.Error) {
                maybeNotifyParent(task, current, "${task.id}:${current.name}")
            }
        }
        val live = byId.keys
        lastStatus.keys.filter { it !in live }.forEach { lastStatus.remove(it) }
        notifiedEdges.removeIf { key -> key.substringBefore(':') !in live }
    }

    private fun maybeNotifyParent(task: AgentTask, status: AgentStatus, edgeKey: String) {
        if (!task.notifyParentOnCompletion) return
        val parentId = task.parentChatTaskId?.takeIf { it.isNotBlank() } ?: return
        if (!notifiedEdges.add(edgeKey)) return
        // Never notify about our own queued follow-up writes cascading.
        if (task.id == parentId) return
        val parent = agentRuns.tasks.value.firstOrNull { it.id == parentId }
        // User Stop on the lead must stick — do not auto-wake a stopped swarm parent.
        if (parent?.stoppedByUser == true) return
        val line = buildString {
            append("worker `").append(task.id).append("` finished: ")
            append(task.title.ifBlank { "(untitled)" })
            append(" [").append(status.name).append("]")
            task.worktreePath?.takeIf { it.isNotBlank() }?.let { append(" worktree=").append(it) }
            task.branchName?.takeIf { it.isNotBlank() }?.let { append(" branch=").append(it) }
        }
        agentRuns.queueFollowUp(parentId, line)
    }

    /**
     * Blocks until one of [taskIds] matches [until], or [timeout] elapses.
     * Reads the current StateFlow value first so an already-terminal worker returns immediately.
     */
    suspend fun awaitAny(
        taskIds: Collection<String>,
        until: ChatAwaitUntil = ChatAwaitUntil.Any,
        timeout: Duration = 120.seconds,
    ): ChatAwaitResult {
        val ids = taskIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (ids.isEmpty()) return ChatAwaitResult(timedOut = true)
        val bounded = timeout.coerceIn(1.seconds, 300.seconds)

        matchNow(ids, until)?.let { return it }

        if (awaitCount.get() >= maxConcurrentAwaits) {
            return ChatAwaitResult(timedOut = true)
        }
        awaitCount.incrementAndGet()
        val job = currentCoroutineContext()[Job]
        if (job != null) awaitJobs.add(job)
        try {
            val matched = withTimeoutOrNull(bounded) {
                agentRuns.tasks
                    .mapNotNull { tasks -> matchTasks(tasks, ids, until) }
                    .first()
            }
            return matched ?: ChatAwaitResult(timedOut = true)
        } finally {
            if (job != null) awaitJobs.remove(job)
            awaitCount.decrementAndGet()
        }
    }

    private fun matchNow(ids: Set<String>, until: ChatAwaitUntil): ChatAwaitResult? =
        matchTasks(agentRuns.tasks.value, ids, until)

    private fun matchTasks(
        tasks: List<AgentTask>,
        ids: Set<String>,
        until: ChatAwaitUntil,
    ): ChatAwaitResult? {
        for (task in tasks) {
            if (task.id !in ids) continue
            val status = task.status ?: continue
            if (matches(status, until)) {
                return ChatAwaitResult(taskId = task.id, status = status, timedOut = false)
            }
        }
        return null
    }

    companion object {
        fun matches(status: AgentStatus, until: ChatAwaitUntil): Boolean = when (until) {
            ChatAwaitUntil.Terminal -> status == AgentStatus.Done || status == AgentStatus.Error
            ChatAwaitUntil.Blocked -> status == AgentStatus.Blocked
            ChatAwaitUntil.Any ->
                status == AgentStatus.Done || status == AgentStatus.Error || status == AgentStatus.Blocked
        }

        fun parseUntil(raw: String?): ChatAwaitUntil = when (raw?.trim()?.lowercase()) {
            "terminal" -> ChatAwaitUntil.Terminal
            "blocked" -> ChatAwaitUntil.Blocked
            else -> ChatAwaitUntil.Any
        }
    }
}
