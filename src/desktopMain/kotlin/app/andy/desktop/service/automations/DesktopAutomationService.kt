package app.andy.desktop.service.automations

import app.andy.currentTimeMillis
import app.andy.desktop.service.agents.DesktopAgentTaskStore
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.model.AgentTaskDraft
import app.andy.model.Automation
import app.andy.model.AutomationDraft
import app.andy.model.AutomationMode
import app.andy.model.AutomationNotify
import app.andy.model.AutomationRunRecord
import app.andy.model.AutomationSchedule
import app.andy.model.resolveAutomationTimeZoneId
import app.andy.model.applyAutomationWorkOutcome
import app.andy.model.evaluatorStopWhenPrompt
import app.andy.model.parseAndyStopTag
import app.andy.service.AgentRunService
import app.andy.service.AutomationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DesktopAutomationService(
    private val store: DesktopAgentTaskStore,
    private val agentRuns: AgentRunService,
    private val scope: CoroutineScope,
    startScheduler: Boolean = true,
    private val nowMillis: () -> Long = { currentTimeMillis() },
) : AutomationService {
    private val _automations = MutableStateFlow(store.loadAllAutomations())
    override val automations: StateFlow<List<Automation>> = _automations.asStateFlow()
    private val mutex = Mutex()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val schedulerWake = Channel<Unit>(Channel.CONFLATED)
    private var schedulerJob: Job? = null

    init {
        if (startScheduler) {
            schedulerJob = scope.launch { runScheduler() }
        }
    }

    fun stop() {
        schedulerJob?.cancel()
    }

    override suspend fun create(draft: AutomationDraft, arm: Boolean): Automation = mutex.withLock {
        val now = nowMillis()
        val automation = draft.toAutomation(
            id = newId(),
            now = now,
            paused = !arm,
            pauseReason = if (arm) null else "Paused until resume",
        ).let { prepared ->
            prepared.copy(nextRunAtMillis = if (arm) computeNext(prepared, now, catchUp = false) else null)
        }
        upsert(automation)
        automation
    }

    override suspend fun update(id: String, draft: AutomationDraft): Automation = mutex.withLock {
        val existing = requireAutomation(id)
        val now = nowMillis()
        val updated = draft.toAutomation(
            id = existing.id,
            now = now,
            paused = existing.paused,
            pauseReason = existing.pauseReason,
        ).copy(
            boundTaskId = existing.boundTaskId,
            lastTaskId = existing.lastTaskId,
            consecutiveFailures = existing.consecutiveFailures,
            fireCount = existing.fireCount,
            createdAtMillis = existing.createdAtMillis,
            lastFiredAtMillis = existing.lastFiredAtMillis,
            runs = existing.runs,
            nextRunAtMillis = if (existing.paused) {
                existing.nextRunAtMillis
            } else {
                computeNext(
                    draft.toAutomation(id, now, existing.paused, existing.pauseReason)
                        .copy(lastFiredAtMillis = existing.lastFiredAtMillis),
                    now,
                    catchUp = false,
                )
            },
        )
        upsert(updated)
        updated
    }

    override suspend fun pause(id: String, reason: String?) = mutex.withLock {
        val current = requireAutomation(id)
        upsert(current.copy(paused = true, pauseReason = reason ?: "Paused", updatedAtMillis = nowMillis()))
    }

    override suspend fun resume(id: String) = mutex.withLock {
        val current = requireAutomation(id)
        val now = nowMillis()
        val armed = current.copy(
            paused = false,
            pauseReason = null,
            consecutiveFailures = 0,
            updatedAtMillis = now,
        )
        upsert(armed.copy(nextRunAtMillis = computeNext(armed, now, catchUp = true)))
    }

    override suspend fun delete(id: String) {
        val current = mutex.withLock { _automations.value.firstOrNull { it.id == id } } ?: return
        if (current.mode == AutomationMode.Dedicated && current.cleanupWorktree) {
            current.boundTaskId?.let { taskId ->
                runCatching { agentRuns.cleanupOwnedWorktree(taskId) }
            }
        }
        mutex.withLock {
            _automations.value = _automations.value.filterNot { it.id == id }
            store.deleteAutomation(id)
        }
        requestSchedulerWake()
    }

    override suspend fun runNow(id: String): Automation {
        val current = mutex.withLock { requireAutomation(id) }
        fire(current, scheduled = false)
        return mutex.withLock { requireAutomation(id) }
    }

    private suspend fun runScheduler() {
        while (scope.isActive) {
            val now = nowMillis()
            val snapshot = _automations.value
            dueScheduledAutomations(snapshot, now, inFlight).forEach { automation ->
                if (inFlight.add(automation.id)) {
                    scope.launch { fire(automation, scheduled = true, alreadyClaimed = true) }
                }
            }
            val wait = nextSchedulerWakeDelayMillis(_automations.value, now, inFlight)
            awaitSchedulerWake(wait)
        }
    }

    private suspend fun awaitSchedulerWake(waitMillis: Long) {
        if (waitMillis == NoScheduledWakeDelayMillis) {
            schedulerWake.receiveCatching()
            return
        }
        val capped = waitMillis.coerceIn(0L, MaxArmedSchedulerPollMillis)
        if (capped == 0L) {
            yield()
            return
        }
        withTimeoutOrNull(capped) { schedulerWake.receiveCatching() }
    }

    private fun requestSchedulerWake() {
        schedulerWake.trySend(Unit)
    }

    private suspend fun fire(automation: Automation, scheduled: Boolean, alreadyClaimed: Boolean = false) {
        if (!alreadyClaimed && !inFlight.add(automation.id)) return
        try {
            val latest = mutex.withLock { _automations.value.firstOrNull { it.id == automation.id } } ?: return
            if (latest.paused && scheduled) return
            val busyTaskId = targetTaskId(latest)
            val busy = busyTaskId?.let { id -> agentRuns.tasks.value.firstOrNull { it.id == id }?.isActive } == true
            if (busy) {
                recordSkip(latest, "Skipped; target chat is still working")
                return
            }
            val heartbeatMissing = latest.mode == AutomationMode.Heartbeat &&
                agentRuns.tasks.value.none { it.id == latest.heartbeatTaskId }
            if (heartbeatMissing) {
                mutex.withLock {
                    upsert(
                        latest.copy(
                            paused = true,
                            pauseReason = "Heartbeat target chat is gone",
                            updatedAtMillis = nowMillis(),
                            nextRunAtMillis = null,
                        ),
                    )
                }
                return
            }
            val started = nowMillis()
            val previousFinished = when (latest.mode) {
                AutomationMode.Standalone -> null
                AutomationMode.Dedicated -> latest.boundTaskId?.let { id ->
                    agentRuns.tasks.value.firstOrNull { it.id == id }?.finishedAtMillis
                }
                AutomationMode.Heartbeat -> latest.heartbeatTaskId?.let { id ->
                    agentRuns.tasks.value.firstOrNull { it.id == id }?.finishedAtMillis
                }
            }
            val task = dispatch(latest)
            val waited = awaitTurnComplete(task.id, previousFinished)
            val finished = agentRuns.tasks.value.firstOrNull { it.id == task.id } ?: task
            val workFailed = finished.status == AgentStatus.Error || waited == null
            var stopWhenYes = false
            if (!workFailed && latest.stopWhen.isNotBlank()) {
                agentRuns.updateAutomationNotifySuppress(finished.id, true)
                val evalFinished = finished.finishedAtMillis
                agentRuns.resume(finished.id, evaluatorStopWhenPrompt(latest.stopWhen))
                awaitTurnComplete(finished.id, evalFinished)
                val after = agentRuns.tasks.value.firstOrNull { it.id == finished.id }
                val text = after?.completedResultText.orEmpty()
                stopWhenYes = parseAndyStopTag(text) == true
            }
            if (!workFailed &&
                latest.mode == AutomationMode.Standalone &&
                latest.useWorktree &&
                latest.cleanupWorktree
            ) {
                runCatching { agentRuns.cleanupOwnedWorktree(finished.id) }
            }
            val policy = applyAutomationWorkOutcome(latest, workFailed, stopWhenYes)
            val now = nowMillis()
            mutex.withLock {
                val current = _automations.value.firstOrNull { it.id == latest.id } ?: return@withLock
                val bound = when (current.mode) {
                    AutomationMode.Dedicated -> current.boundTaskId ?: finished.id
                    AutomationMode.Heartbeat -> current.heartbeatTaskId
                    AutomationMode.Standalone -> current.boundTaskId
                }
                val onceDone = current.schedule is AutomationSchedule.Once
                val paused = policy.paused || onceDone
                val pauseReason = when {
                    policy.pauseReason != null -> policy.pauseReason
                    onceDone -> "Completed one-time run"
                    else -> current.pauseReason
                }
                val updated = current.copy(
                    boundTaskId = bound,
                    lastTaskId = finished.id,
                    paused = paused,
                    pauseReason = pauseReason,
                    consecutiveFailures = policy.consecutiveFailures,
                    fireCount = policy.fireCount,
                    lastFiredAtMillis = started,
                    updatedAtMillis = now,
                    runs = (
                        current.runs + AutomationRunRecord(
                            id = "run-${UUID.randomUUID()}",
                            taskId = finished.id,
                            startedAtMillis = started,
                            finishedAtMillis = now,
                            outcome = when {
                                workFailed -> "error"
                                stopWhenYes -> "stop_yes"
                                else -> "done"
                            },
                            detail = pauseReason,
                        )
                        ).takeLast(25),
                )
                val withNext = updated.copy(
                    nextRunAtMillis = if (paused) {
                        null
                    } else {
                        catchUpOccurrence(computeNext(updated, now, catchUp = false), now)
                            ?.takeUnless { scheduled && it <= now }
                            ?: computeNext(updated, now, catchUp = false)
                    },
                )
                upsert(withNext)
            }
        } catch (error: Throwable) {
            mutex.withLock {
                val current = _automations.value.firstOrNull { it.id == automation.id } ?: return@withLock
                val policy = applyAutomationWorkOutcome(current, workFailed = true, stopWhenYes = false)
                upsert(
                    current.copy(
                        paused = policy.paused,
                        pauseReason = error.message ?: policy.pauseReason,
                        consecutiveFailures = policy.consecutiveFailures,
                        fireCount = policy.fireCount,
                        updatedAtMillis = nowMillis(),
                    ),
                )
            }
        } finally {
            inFlight.remove(automation.id)
            requestSchedulerWake()
        }
    }

    private suspend fun dispatch(automation: Automation): AgentTask {
        val snapshot = automation.launch
        val failedOnly = automation.notify == AutomationNotify.FailedOnly
        return when (automation.mode) {
            AutomationMode.Standalone -> agentRuns.createAndStart(
                AgentTaskDraft(
                    title = automation.title,
                    prompt = automation.prompt,
                    agent = snapshot.agentKind(),
                    projectId = automation.projectId,
                    directory = snapshot.directory,
                    useWorktree = automation.useWorktree,
                    attachAndyMcp = true,
                    autonomy = snapshot.autonomyLevel(),
                    model = snapshot.model,
                    reasoningEffort = snapshot.effort(),
                    automationId = automation.id,
                    automationNotifyFailedOnly = failedOnly,
                ),
            )
            AutomationMode.Dedicated -> {
                val existing = automation.boundTaskId?.let { id ->
                    agentRuns.tasks.value.firstOrNull { it.id == id }
                }
                if (existing == null) {
                    agentRuns.createAndStart(
                        AgentTaskDraft(
                            title = automation.title,
                            prompt = automation.prompt,
                            agent = snapshot.agentKind(),
                            projectId = automation.projectId,
                            directory = snapshot.directory,
                            useWorktree = automation.useWorktree,
                            attachAndyMcp = true,
                            autonomy = snapshot.autonomyLevel(),
                            model = snapshot.model,
                            reasoningEffort = snapshot.effort(),
                            automationId = automation.id,
                            automationNotifyFailedOnly = failedOnly,
                        ),
                    )
                } else {
                    agentRuns.resume(existing.id, automation.prompt)
                    existing
                }
            }
            AutomationMode.Heartbeat -> {
                val targetId = automation.heartbeatTaskId ?: error("Heartbeat requires a chat")
                agentRuns.resume(targetId, automation.prompt)
                agentRuns.tasks.value.firstOrNull { it.id == targetId }
                    ?: error("Heartbeat target chat is gone")
            }
        }
    }

    private fun targetTaskId(automation: Automation): String? = when (automation.mode) {
        AutomationMode.Standalone -> automation.lastTaskId
        AutomationMode.Dedicated -> automation.boundTaskId
        AutomationMode.Heartbeat -> automation.heartbeatTaskId
    }

    private suspend fun awaitTurnComplete(taskId: String, previousFinishedAtMillis: Long?): AgentTask? {
        withTimeoutOrNull(30_000L) {
            agentRuns.tasks.first { list ->
                val task = list.firstOrNull { it.id == taskId } ?: return@first true
                task.isActive || task.finishedAtMillis != previousFinishedAtMillis
            }
        }
        return awaitInactive(taskId)
    }

    private suspend fun awaitInactive(taskId: String): AgentTask? =
        withTimeoutOrNull(6 * 60 * 60 * 1000L) {
            agentRuns.tasks.first { list ->
                val task = list.firstOrNull { it.id == taskId } ?: return@first true
                !task.isActive && task.status != null && task.statusConfident
            }.firstOrNull { it.id == taskId }
        }

    private suspend fun recordSkip(automation: Automation, detail: String) {
        mutex.withLock {
            val current = _automations.value.firstOrNull { it.id == automation.id } ?: return
            val now = nowMillis()
            upsert(
                current.copy(
                    updatedAtMillis = now,
                    nextRunAtMillis = computeNext(current, now, catchUp = false),
                    runs = (
                        current.runs + AutomationRunRecord(
                            id = "run-${UUID.randomUUID()}",
                            startedAtMillis = now,
                            finishedAtMillis = now,
                            outcome = "skipped",
                            detail = detail,
                        )
                        ).takeLast(25),
                ),
            )
        }
    }

    private fun computeNext(automation: Automation, now: Long, catchUp: Boolean): Long? {
        val from = automation.lastFiredAtMillis ?: now
        val scheduled = nextAutomationOccurrence(automation, fromExclusiveMillis = from, lastFiredAtMillis = automation.lastFiredAtMillis)
            ?: return null
        return if (catchUp) catchUpOccurrence(scheduled, now) else scheduled.takeIf { it > now } ?: scheduled
    }

    private fun requireAutomation(id: String): Automation =
        _automations.value.firstOrNull { it.id == id } ?: error("Automation $id not found")

    private fun upsert(automation: Automation) {
        _automations.value = _automations.value.filterNot { it.id == automation.id } + automation
        store.saveAutomation(automation)
        requestSchedulerWake()
    }

    private fun newId(): String = "auto-${UUID.randomUUID()}"

    private fun resolveStoredTimeZone(raw: String): String {
        val resolved = resolveAutomationTimeZoneId(raw, fallback = ZoneId.systemDefault().id)
        return runCatching { ZoneId.of(resolved).id }.getOrElse { ZoneId.systemDefault().id }
    }

    private fun AutomationDraft.toAutomation(
        id: String,
        now: Long,
        paused: Boolean,
        pauseReason: String?,
    ): Automation {
        val heartbeat = heartbeatTaskId?.takeIf { mode == AutomationMode.Heartbeat }
        val useTree = if (mode == AutomationMode.Heartbeat) false else useWorktree
        val zone = resolveStoredTimeZone(timeZone)
        val hour = runHour.coerceIn(0, 23)
        val minute = runMinute.coerceIn(0, 59)
        val resolvedSchedule = when (val item = schedule) {
            is AutomationSchedule.Once -> AutomationSchedule.Once(
                nextScheduleOccurrence(
                    schedule = item,
                    timeZone = zone,
                    runHour = hour,
                    runMinute = minute,
                    fromExclusiveMillis = now,
                    lastFiredAtMillis = null,
                ) ?: now,
            )
            else -> item
        }
        return Automation(
            id = id,
            projectId = projectId,
            title = title.trim().ifBlank { prompt.trim().lineSequence().firstOrNull().orEmpty().take(60) },
            prompt = prompt,
            mode = mode,
            schedule = resolvedSchedule,
            timeZone = zone,
            runHour = hour,
            runMinute = minute,
            stopWhen = stopWhen.trim(),
            failurePolicy = failurePolicy,
            maxIterations = maxIterations,
            notify = notify,
            useWorktree = useTree,
            cleanupWorktree = if (mode == AutomationMode.Heartbeat) false else cleanupWorktree,
            heartbeatTaskId = heartbeat,
            launch = launch,
            paused = paused,
            pauseReason = pauseReason,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
    }
}
