package app.andy.desktop.service.agents

import app.andy.model.AgentCliStatus
import app.andy.model.AgentChangeSummary
import app.andy.model.AgentEvent
import app.andy.model.AgentFileDiff
import app.andy.model.AgentKind
import app.andy.model.AgentProviderDefaults
import app.andy.model.AgentQueuedFollowUp
import app.andy.model.AgentProviderQuota
import app.andy.model.AgentQuotaSource
import app.andy.model.AgentQuotaAccess
import app.andy.model.AgentSkill
import app.andy.model.AgentTask
import app.andy.model.AgentTaskDraft
import app.andy.model.AgentTaskStatus
import app.andy.model.AgentThreadChangeSnapshot
import app.andy.model.AgentSandboxMode
import app.andy.model.estimatedTokenCostUsd
import app.andy.model.promptWithGoalHint
import app.andy.model.promptWithSkillHints
import app.andy.model.providerDefaults
import app.andy.service.ActionConfigStore
import app.andy.service.CommandResult
import app.andy.service.AgentRunService
import app.andy.service.McpServerService
import app.andy.service.WorkspaceStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val MAX_EVENTS_IN_MEMORY = 3000
private const val PROVIDER_QUOTA_REFRESH_MILLIS = 5 * 60 * 1000L

class DesktopAgentRunService(
    private val scope: CoroutineScope,
    private val store: DesktopAgentTaskStore,
    private val locator: AgentCliLocator,
    private val adapters: Map<AgentKind, AgentCliAdapter>,
    private val worktrees: WorktreeManager,
    private val mcp: McpServerService,
    private val workspaceStore: WorkspaceStore,
    private val actionConfig: ActionConfigStore,
) : AgentRunService {
    private class TaskHandle(
        @Volatile var process: Process? = null,
        @Volatile var job: Job? = null,
        @Volatile var stopRequested: Boolean = false,
    )

    private val _tasks = MutableStateFlow<List<AgentTask>>(emptyList())
    override val tasks: StateFlow<List<AgentTask>> = _tasks

    private val _cliStatuses = MutableStateFlow<List<AgentCliStatus>>(emptyList())
    override val cliStatuses: StateFlow<List<AgentCliStatus>> = _cliStatuses

    private val _providerQuotas = MutableStateFlow<Map<AgentKind, AgentProviderQuota>>(emptyMap())
    override val providerQuotas: StateFlow<Map<AgentKind, AgentProviderQuota>> = _providerQuotas

    private val _quotaAccess = MutableStateFlow(AgentQuotaAccess())
    override val quotaAccess: StateFlow<AgentQuotaAccess> = _quotaAccess

    private val _providerDefaults = MutableStateFlow<Map<AgentKind, AgentProviderDefaults>>(emptyMap())
    override val providerDefaults: StateFlow<Map<AgentKind, AgentProviderDefaults>> = _providerDefaults

    private val _lastUsedAgent = MutableStateFlow<AgentKind?>(null)
    override val lastUsedAgent: StateFlow<AgentKind?> = _lastUsedAgent

    private data class SkillScope(val agent: AgentKind, val directory: String?)

    private val skillFlows = ConcurrentHashMap<SkillScope, MutableStateFlow<List<AgentSkill>>>()
    private val loadedSkillScopes = ConcurrentHashMap.newKeySet<SkillScope>()

    private val handles = ConcurrentHashMap<String, TaskHandle>()
    private val eventFlows = ConcurrentHashMap<String, MutableStateFlow<List<AgentEvent>>>()
    private val historyLoaded = ConcurrentHashMap.newKeySet<String>()
    private val emptyEvents = MutableStateFlow<List<AgentEvent>>(emptyList())

    private val persistMutex = Mutex()
    private val mcpMutex = Mutex()
    private val quotaRefreshMutex = Mutex()
    private val quotaProbe = ProviderQuotaProbe()
    private val ready = CompletableDeferred<Unit>()
    private var binaryOverrides: Map<String, String> = emptyMap()
    private lateinit var slots: Semaphore

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            handles.values.forEach { handle -> handle.process?.let(::killTree) }
        })
        scope.launch {
            val state = store.load()
            binaryOverrides = state.binaryOverrides
            val recoveredDefaults = state.tasks
                .groupBy { it.agent }
                .mapValues { (_, tasks) -> tasks.maxBy { it.createdAtMillis }.providerDefaults() }
            _providerDefaults.value = recoveredDefaults + state.providerDefaults
            _quotaAccess.value = state.quotaAccess
            _lastUsedAgent.value = state.lastUsedAgent
                ?: state.tasks.maxByOrNull { it.createdAtMillis }?.agent
            storedMaxConcurrent = state.maxConcurrent
            slots = Semaphore(state.maxConcurrent)
            _tasks.value = state.tasks
            ready.complete(Unit)
            scope.launch(Dispatchers.IO) { restoreProviderQuotas(state.tasks) }
            refreshCliStatuses()
            refreshProviderQuotas()
            while (isActive) {
                delay(PROVIDER_QUOTA_REFRESH_MILLIS)
                refreshProviderQuotas()
            }
        }
    }

    override fun skills(agent: AgentKind, directory: String?): StateFlow<List<AgentSkill>> {
        val normalizedDirectory = directory
            ?.takeIf { it.isNotBlank() }
            ?.let { path -> runCatching { File(path).canonicalPath }.getOrElse { path } }
        val skillScope = SkillScope(agent, normalizedDirectory)
        val flow = skillFlows.computeIfAbsent(skillScope) { MutableStateFlow(emptyList()) }
        if (loadedSkillScopes.add(skillScope)) {
            scope.launch(Dispatchers.IO) {
                flow.value = discoverSkills(agent, normalizedDirectory)
            }
        }
        return flow
    }

    override suspend fun createAndStart(draft: AgentTaskDraft): AgentTask {
        ready.await()
        _providerDefaults.update { it + (draft.agent to draft.providerDefaults()) }
        _lastUsedAgent.value = draft.agent
        val now = System.currentTimeMillis()
        val id = "task-" + UUID.randomUUID().toString().replace("-", "").take(10)
        var task = AgentTask(
            id = id,
            title = draft.title.ifBlank { draft.prompt.truncateForSummary(60) },
            prompt = draft.prompt,
            agent = draft.agent,
            projectId = draft.projectId,
            cwd = draft.directory,
            originDir = draft.directory,
            useWorktree = draft.useWorktree,
            attachAndyMcp = draft.attachAndyMcp,
            autonomy = draft.autonomy,
            sandboxMode = draft.sandboxMode,
            planMode = draft.planMode,
            model = draft.model,
            reasoningEffort = draft.reasoningEffort,
            fastMode = draft.fastMode,
            imagePaths = draft.imagePaths,
            skills = draft.skills.filter { skill ->
                skills(draft.agent, draft.directory).value.any { it.path == skill.path }
            },
            goal = draft.goal,
            maxBudgetUsd = draft.maxBudgetUsd,
            status = AgentTaskStatus.Queued,
            // Claude accepts a caller-assigned session id; pre-assigning means resume
            // works even when output parsing fails mid-run.
            vendorSessionId = if (draft.agent == AgentKind.ClaudeCode) UUID.randomUUID().toString() else null,
            createdAtMillis = now,
        )

        val binary = binaryFor(task.agent)
        if (binary == null) {
            task = task.copy(
                status = AgentTaskStatus.Failed,
                errorMessage = unavailableCliMessage(task.agent),
                finishedAtMillis = now,
            )
            upsertTask(task)
            persist()
            return task
        }

        if (task.useWorktree) {
            val originDir = task.originDir
            if (originDir == null) {
                task = task.copy(
                    status = AgentTaskStatus.Failed,
                    errorMessage = "a project directory is required to create a worktree",
                    finishedAtMillis = System.currentTimeMillis(),
                )
                upsertTask(task)
                persist()
                return task
            }
            val created = withContext(Dispatchers.IO) { worktrees.create(originDir, task.id, task.agent, task.title) }
            task = created.fold(
                onSuccess = { task.copy(cwd = it.path, worktreePath = it.path, branchName = it.branch) },
                onFailure = {
                    task.copy(
                        status = AgentTaskStatus.Failed,
                        errorMessage = "worktree creation failed: ${it.message}",
                        finishedAtMillis = System.currentTimeMillis(),
                    )
                },
            )
            if (task.status == AgentTaskStatus.Failed) {
                upsertTask(task)
                persist()
                return task
            }
        }

        task.cwd?.let { cwd ->
            withContext(Dispatchers.IO) { worktrees.captureChangeBaseline(cwd) }?.let { baseline ->
                task = task.copy(changeBaselinePaths = baseline, hasChangeBaseline = true)
            }
        }

        upsertTask(task)
        persist()
        launchRun(task) { adapter, resolvedBinary, mcpUrl ->
            adapter.buildCommand(resolvedBinary, currentTask(task.id) ?: task, mcpUrl)
        }
        return task
    }

    override fun resume(taskId: String, followUp: String, imagePaths: List<String>, skills: List<AgentSkill>) {
        val task = currentTask(taskId) ?: return
        if (task.isActive) return
        val adapter = adapters[task.agent] ?: return
        if (!adapter.supportsHeadlessResume || task.vendorSessionId == null) return

        _lastUsedAgent.value = task.agent

        val now = System.currentTimeMillis()
        val skillDirectory = task.worktreePath ?: task.cwd
        val selectedSkills = skills.filter { skill ->
            this.skills(task.agent, skillDirectory).value.any { it.path == skill.path }
        }
        val followUpForCli = promptWithGoalHint(promptWithSkillHints(followUp, selectedSkills), task.goal)
        appendEvents(taskId, listOf(AgentEvent.UserMessage(now, followUp, selectedSkills)))
        writeAndyTranscriptLine(taskId, followUp, selectedSkills, now)
        val queued = task.copy(
            status = AgentTaskStatus.Queued,
            exitCode = null,
            errorMessage = null,
            finishedAtMillis = null,
            unread = false,
        )
        upsertTask(queued)
        scope.launch { persist() }
        launchRun(queued) { resumeAdapter, binary, mcpUrl ->
            resumeAdapter.buildResumeCommand(binary, currentTask(taskId) ?: queued, followUpForCli, imagePaths, mcpUrl)
                ?: error("resume not supported")
        }
    }

    override suspend fun startImplementation(taskId: String) {
        ready.await()
        val task = currentTask(taskId) ?: return
        val completedPlan = task.completedPlanText?.takeIf { it.isNotBlank() } ?: return
        if (task.status != AgentTaskStatus.Completed || !task.planMode || task.isActive) return

        _lastUsedAgent.value = task.agent
        val now = System.currentTimeMillis()
        val implementationPrompt = implementationPromptFor(task.prompt, completedPlan)
        val implementationBaseline = task.cwd?.let { cwd ->
            withContext(Dispatchers.IO) { worktrees.captureChangeBaseline(cwd) }
        }
        val implementationTask = task.copy(
            planMode = false,
            sandboxMode = AgentSandboxMode.WorkspaceWrite,
            implementationPrompt = implementationPrompt,
            // A handoff intentionally starts a new provider thread. Claude requires a
            // caller-assigned id while the other CLIs report one after startup.
            vendorSessionId = if (task.agent == AgentKind.ClaudeCode) UUID.randomUUID().toString() else null,
            status = AgentTaskStatus.Queued,
            startedAtMillis = null,
            finishedAtMillis = null,
            exitCode = null,
            errorMessage = null,
            totalCostUsd = null,
            costIsEstimated = false,
            inputTokens = null,
            outputTokens = null,
            contextTokens = null,
            contextWindowTokens = null,
            changeBaselinePaths = implementationBaseline.orEmpty(),
            hasChangeBaseline = implementationBaseline != null,
            completedChanges = null,
            unread = false,
        )
        appendEvents(taskId, listOf(AgentEvent.UserMessage(now, implementationPrompt, task.skills)))
        writeAndyTranscriptLine(taskId, implementationPrompt, task.skills, now)
        upsertTask(implementationTask)
        persist()
        launchRun(implementationTask) { adapter, binary, mcpUrl ->
            adapter.buildCommand(binary, currentTask(taskId) ?: implementationTask, mcpUrl)
        }
    }

    override fun queueFollowUp(taskId: String, followUp: String, imagePaths: List<String>, skills: List<AgentSkill>) {
        val task = currentTask(taskId) ?: return
        val adapter = adapters[task.agent] ?: return
        if (!task.isActive || !adapter.supportsHeadlessResume || task.vendorSessionId == null) return

        val text = followUp.trim()
        if (text.isBlank() && imagePaths.isEmpty()) return
        val skillDirectory = task.worktreePath ?: task.cwd
        val selectedSkills = skills.filter { skill ->
            this.skills(task.agent, skillDirectory).value.any { it.path == skill.path }
        }
        updateTask(taskId) { current ->
            if (!current.isActive) {
                current
            } else {
                current.copy(
                    queuedFollowUps = current.queuedFollowUps + AgentQueuedFollowUp(
                        text = text,
                        imagePaths = imagePaths,
                        skills = selectedSkills,
                    ),
                )
            }
        }
        scope.launch { persist() }
    }

    override fun removeQueuedFollowUp(taskId: String, queueIndex: Int) {
        val task = currentTask(taskId) ?: return
        if (queueIndex !in task.queuedFollowUps.indices) return
        updateTask(taskId) { current ->
            current.copy(queuedFollowUps = current.queuedFollowUps.filterIndexed { index, _ -> index != queueIndex })
        }
        scope.launch { persist() }
    }

    override suspend fun retry(taskId: String) {
        ready.await()
        val task = currentTask(taskId) ?: return
        if (task.status != AgentTaskStatus.Failed) return

        _lastUsedAgent.value = task.agent

        // A retry is a fresh run of the same chat, rather than a provider-specific
        // resume. In particular, Claude needs a new caller-assigned session id.
        val retried = task.copy(
            status = AgentTaskStatus.Queued,
            vendorSessionId = if (task.agent == AgentKind.ClaudeCode) UUID.randomUUID().toString() else null,
            startedAtMillis = null,
            finishedAtMillis = null,
            exitCode = null,
            errorMessage = null,
            totalCostUsd = null,
            costIsEstimated = false,
            inputTokens = null,
            outputTokens = null,
            contextTokens = null,
            contextWindowTokens = null,
            unread = false,
        )
        store.deleteTranscript(taskId)
        eventFlows[taskId]?.value = emptyList()
        historyLoaded.remove(taskId)
        upsertTask(retried)
        persist()
        launchRun(retried) { adapter, resolvedBinary, mcpUrl ->
            adapter.buildCommand(resolvedBinary, currentTask(taskId) ?: retried, mcpUrl)
        }
    }

    override fun updateGoal(taskId: String, goal: String?) {
        val normalizedGoal = goal?.trim()?.takeIf { it.isNotBlank() }
        val task = currentTask(taskId) ?: return
        if (task.goal == normalizedGoal) return
        updateTask(taskId) { it.copy(goal = normalizedGoal) }
        scope.launch { persist() }
    }

    private fun launchRun(task: AgentTask, argvBuilder: (AgentCliAdapter, String, String?) -> List<String>) {
        val handle = TaskHandle()
        handles[task.id] = handle
        handle.job = scope.launch(Dispatchers.IO) {
            ready.await()
            slots.withPermit {
                if (handle.stopRequested) return@withPermit
                runProcess(task.id, handle, argvBuilder)
            }
        }
    }

    private suspend fun runProcess(
        taskId: String,
        handle: TaskHandle,
        argvBuilder: (AgentCliAdapter, String, String?) -> List<String>,
    ) {
        val task = currentTask(taskId) ?: return
        val adapter = adapters.getValue(task.agent)
        val binary = binaryFor(task.agent)
        if (binary == null) {
            finishTask(taskId, AgentTaskStatus.Failed, exitCode = null, error = unavailableCliMessage(task.agent))
            return
        }

        val mcpUrl = if (task.attachAndyMcp) {
            runCatching { prepareMcp(task.agent) }.getOrElse { error ->
                finishTask(taskId, AgentTaskStatus.Failed, exitCode = null, error = "failed to prepare Andy MCP: ${error.message}")
                return
            }
        } else {
            null
        }
        val argv = runCatching { argvBuilder(adapter, binary, mcpUrl) }.getOrElse {
            finishTask(taskId, AgentTaskStatus.Failed, exitCode = null, error = it.message ?: "failed to build command")
            return
        }
        val projectEnv = task.projectId?.let { projectId ->
            runCatching { actionConfig.load().projects.firstOrNull { it.id == projectId }?.env }.getOrNull()
        }.orEmpty()

        updateTask(taskId) { it.copy(status = AgentTaskStatus.Running, startedAtMillis = System.currentTimeMillis()) }
        persist()

        val process = runCatching {
            ProcessBuilder(argv)
                .redirectErrorStream(true)
                // Agent CLIs block (codex) or stall (claude) reading an open stdin pipe.
                .redirectInput(nullInputFile())
                .apply {
                    task.cwd?.let { directory(File(it)) }
                    environment().putAll(projectEnv)
                }
                .start()
        }.getOrElse { error ->
            finishTask(taskId, AgentTaskStatus.Failed, exitCode = null, error = "failed to start: ${error.message}")
            return
        }
        handle.process = process
        if (handle.stopRequested) {
            killTree(process)
            finishTask(taskId, AgentTaskStatus.Stopped, exitCode = null, error = null)
            return
        }

        var lastResult: AgentEvent.TaskResult? = null
        var lastError: String? = null
        var lastAssistantText: String? = null
        val rawTail = ArrayDeque<String>()
        val rawPlanOutput = StringBuilder()

        val transcript = store.transcriptFile(taskId)
        transcript.parentFile?.mkdirs()
        FileOutputStream(transcript, true).bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    val batch = mutableListOf<AgentEvent>()
                    var lastFlush = System.currentTimeMillis()
                    fun flush() {
                        if (batch.isEmpty()) return
                        appendEvents(taskId, batch.toList())
                        batch.clear()
                        writer.flush()
                        lastFlush = System.currentTimeMillis()
                    }
                    for (line in lines) {
                        writer.appendLine(line)
                        if (task.planMode) rawPlanOutput.appendLine(line)
                        val now = System.currentTimeMillis()
                        val events = runCatching { adapter.parseLine(line, now) }
                            .getOrElse { listOf(AgentEvent.Raw(now, line)) }
                            .withEstimatedCosts(currentTask(taskId) ?: task)
                        for (event in events) {
                            when (event) {
                                is AgentEvent.SessionStarted -> event.sessionId?.let { sessionId ->
                                    updateTask(taskId) { current ->
                                        if (current.vendorSessionId == null) current.copy(vendorSessionId = sessionId) else current
                                    }
                                }
                                is AgentEvent.TaskResult -> lastResult = event
                                is AgentEvent.ContextUsage -> updateTask(taskId) { current ->
                                    current.copy(
                                        contextTokens = event.usedTokens ?: current.contextTokens,
                                        contextWindowTokens = event.windowTokens ?: current.contextWindowTokens,
                                    )
                                }
                                is AgentEvent.ToolResult -> event.quotaWindows.takeIf { it.isNotEmpty() }?.let { windows ->
                                    updateQuota(task.agent, windows, event.atMillis)
                                }
                                is AgentEvent.TaskError -> lastError = event.message
                                is AgentEvent.AssistantText -> {
                                    lastAssistantText = if (event.isStreamDelta) {
                                        lastAssistantText.orEmpty() + event.text
                                    } else {
                                        event.text
                                    }
                                }
                                is AgentEvent.Raw -> {
                                    rawTail.addLast(event.line)
                                    if (rawTail.size > 20) rawTail.removeFirst()
                                }
                                else -> Unit
                            }
                        }
                        batch += events
                        if (batch.size >= 64 || System.currentTimeMillis() - lastFlush >= 100) flush()
                    }
                    flush()
                }
            }
        }

        val exitCode = runCatching { process.waitFor() }.getOrElse { -1 }
        val result = lastResult
        val status = when {
            handle.stopRequested -> AgentTaskStatus.Stopped
            exitCode == 0 && result?.success != false && lastError == null -> AgentTaskStatus.Completed
            else -> AgentTaskStatus.Failed
        }
        val fallbackText = lastAssistantText ?: rawTail.joinToString("\n").takeIf { it.isNotBlank() }
        val completedPlanText = if (status == AgentTaskStatus.Completed && task.planMode) {
            result?.finalText?.takeIf { it.isNotBlank() }
                ?: lastAssistantText?.takeIf { it.isNotBlank() }
                ?: rawPlanOutput.toString().trim().takeIf { it.isNotBlank() }
                ?: fallbackText
        } else {
            null
        }
        if (result == null && status != AgentTaskStatus.Stopped) {
            // Adapters without structured output (or failed runs) still get a terminal event.
            appendEvents(
                taskId,
                listOf(
                    AgentEvent.TaskResult(
                        atMillis = System.currentTimeMillis(),
                        success = status == AgentTaskStatus.Completed,
                        finalText = fallbackText,
                    ),
                ),
            )
        }
        updateTask(taskId) { current ->
            current.copy(
                totalCostUsd = result?.costUsd ?: current.totalCostUsd,
                costIsEstimated = result?.costIsEstimated ?: current.costIsEstimated,
                inputTokens = result?.inputTokens ?: current.inputTokens,
                outputTokens = result?.outputTokens ?: current.outputTokens,
                completedPlanText = completedPlanText ?: current.completedPlanText,
            )
        }
        finishTask(
            taskId = taskId,
            status = status,
            exitCode = exitCode,
            error = if (status == AgentTaskStatus.Failed) {
                lastError ?: authFailureHint(fallbackText, taskId) ?: "exited with code $exitCode"
            } else {
                null
            },
        )
    }

    private fun authFailureHint(tail: String?, taskId: String): String? {
        val text = tail?.lowercase() ?: return null
        val authIndicators = listOf("not logged in", "please log in", "please run /login", "unauthorized", "authentication", "invalid api key", "login required")
        if (authIndicators.none { it in text }) return null
        val cli = currentTask(taskId)?.agent?.cliName ?: return null
        return "Not logged in — run `$cli` in a terminal and sign in, then retry"
    }

    private fun implementationPromptFor(originalRequest: String, completedPlan: String): String = buildString {
        append("Begin implementation. Implement the completed plan below: make the edits and run the relevant verification.\n\n")
        append("Original request:\n")
        append(originalRequest.trim())
        append("\n\nCompleted plan:\n")
        append(completedPlan.trim())
    }

    override fun stop(taskId: String) {
        val handle = handles[taskId] ?: return
        handle.stopRequested = true
        scope.launch(Dispatchers.IO) {
            val process = handle.process
            if (process != null) {
                killTree(process)
            } else {
                // Still queued: cancel before it ever starts.
                handle.job?.cancel()
                finishTask(taskId, AgentTaskStatus.Stopped, exitCode = null, error = null)
            }
        }
    }

    override suspend fun delete(taskId: String, removeWorktree: Boolean) {
        val task = currentTask(taskId) ?: return
        if (task.isActive) {
            stop(taskId)
        }
        handles.remove(taskId)
        eventFlows.remove(taskId)
        historyLoaded.remove(taskId)
        _tasks.update { list -> list.filterNot { it.id == taskId } }
        store.deleteTranscript(taskId)
        val worktreePath = task.worktreePath
        if (removeWorktree && worktreePath != null) {
            task.originDir?.let { originDir ->
                withContext(Dispatchers.IO) { worktrees.remove(originDir, worktreePath, task.branchName) }
            }
        }
        persist()
    }

    override fun events(taskId: String): StateFlow<List<AgentEvent>> {
        val task = currentTask(taskId) ?: return emptyEvents
        val flow = eventFlows.computeIfAbsent(taskId) { MutableStateFlow(emptyList()) }
        if (!task.isActive && historyLoaded.add(taskId) && flow.value.isEmpty()) {
            scope.launch(Dispatchers.IO) { loadHistoricalEvents(task, flow) }
        }
        return flow
    }

    private fun loadHistoricalEvents(task: AgentTask, flow: MutableStateFlow<List<AgentEvent>>) {
        val file = store.transcriptFile(task.id)
        if (!file.exists()) return
        val adapter = adapters[task.agent] ?: return
        val at = task.createdAtMillis
        val events = mutableListOf<AgentEvent>()
        runCatching {
            file.useLines { lines ->
                for (line in lines) {
                    val andyMessage = parseAndyTranscriptLine(line)
                    if (andyMessage != null) {
                        events += andyMessage.copy(atMillis = andyMessage.atMillis.takeIf { it > 0 } ?: at)
                    } else {
                        events += runCatching { adapter.parseLine(line, at) }
                            .getOrElse { listOf(AgentEvent.Raw(at, line)) }
                            .withEstimatedCosts(task)
                    }
                }
            }
        }
        flow.value = coalesceStreamDeltas(emptyList(), events).takeLast(MAX_EVENTS_IN_MEMORY)
    }

    override fun interactiveResumeCommand(taskId: String): String? {
        val task = currentTask(taskId) ?: return null
        val adapter = adapters[task.agent] ?: return null
        val binary = binaryFor(task.agent) ?: task.agent.cliName
        val changeDirectory = task.cwd?.let { "cd ${shellQuote(it)} && " }.orEmpty()
        return changeDirectory + adapter.interactiveResumeCommand(binary, task)
    }

    override suspend fun openInTerminal(taskId: String): CommandResult = withContext(Dispatchers.IO) {
        val command = interactiveResumeCommand(taskId)
            ?: return@withContext CommandResult.failure("task not found")
        val osName = System.getProperty("os.name")?.lowercase().orEmpty()
        if (!osName.contains("mac")) {
            return@withContext CommandResult.failure("Opening a terminal is only automated on macOS — the command has been copied instead")
        }
        val escaped = command.replace("\\", "\\\\").replace("\"", "\\\"")
        runCatching {
            val process = ProcessBuilder(
                "osascript",
                "-e", "tell application \"Terminal\" to activate",
                "-e", "tell application \"Terminal\" to do script \"$escaped\"",
            ).redirectErrorStream(true).start()
            process.waitFor(10, TimeUnit.SECONDS)
            if (process.exitValue() == 0) {
                CommandResult.success("opened Terminal")
            } else {
                CommandResult.failure(process.inputStream.bufferedReader().readText().truncateForSummary())
            }
        }.getOrElse { CommandResult.failure(it.message ?: "failed to open Terminal") }
    }

    override suspend fun openSkill(path: String): CommandResult = withContext(Dispatchers.IO) {
        val skillFile = File(path)
        if (!skillFile.isFile) return@withContext CommandResult.failure("skill file no longer exists")
        val osName = System.getProperty("os.name")?.lowercase().orEmpty()
        val command = when {
            osName.contains("mac") -> listOf("open", skillFile.absolutePath)
            osName.contains("win") -> listOf("cmd", "/c", "start", "", skillFile.absolutePath)
            else -> listOf("xdg-open", skillFile.absolutePath)
        }
        runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.waitFor(10, TimeUnit.SECONDS)
            if (process.exitValue() == 0) CommandResult.success("opened skill")
            else CommandResult.failure(process.inputStream.bufferedReader().readText().truncateForSummary())
        }.getOrElse { CommandResult.failure(it.message ?: "failed to open skill") }
    }

    override suspend fun worktreeDiffSummary(taskId: String): String? = withContext(Dispatchers.IO) {
        val task = currentTask(taskId) ?: return@withContext null
        val path = task.worktreePath ?: return@withContext null
        worktrees.diffSummary(path)
    }

    override suspend fun changeSummary(taskId: String): AgentChangeSummary? = withContext(Dispatchers.IO) {
        val task = currentTask(taskId) ?: return@withContext null
        task.completedChanges?.let { return@withContext it.summary }
        if (!task.hasChangeBaseline) return@withContext null
        val cwd = task.cwd ?: return@withContext null
        worktrees.changeSummary(cwd, task.changeBaselinePaths)
    }

    override suspend fun fileDiff(taskId: String, relativePath: String): AgentFileDiff? = withContext(Dispatchers.IO) {
        val task = currentTask(taskId) ?: return@withContext null
        task.completedChanges?.diffs?.get(relativePath)?.let { return@withContext it }
        val cwd = task.cwd ?: return@withContext null
        worktrees.fileDiff(cwd, relativePath)
    }

    override suspend fun refreshCliStatuses() {
        ready.await()
        val statuses = withContext(Dispatchers.IO) { locator.locateAll(binaryOverrides) }
        _cliStatuses.value = statuses
    }

    override suspend fun refreshProviderQuotas() {
        ready.await()
        quotaRefreshMutex.withLock {
            val fetched = withContext(Dispatchers.IO) {
                _cliStatuses.value.mapNotNull { status ->
                    status.binaryPath?.let { binary -> quotaProbe.query(status.kind, binary, _quotaAccess.value) }
                }
            }
            if (fetched.isNotEmpty()) {
                _providerQuotas.update { current -> current + fetched.toMap() }
            }
        }
    }

    override suspend fun isGitRepo(dir: String): Boolean = withContext(Dispatchers.IO) { worktrees.isGitRepo(dir) }

    override fun setQuotaAccess(agent: AgentKind, enabled: Boolean) {
        if (agent == AgentKind.Codex) return
        _quotaAccess.update { it.withAccess(agent, enabled) }
        if (!enabled) {
            quotaProbe.clearAccountAccess(agent)
            _providerQuotas.update { current ->
                current.filterNot { (kind, quota) -> kind == agent && quota.source == AgentQuotaSource.ProviderQuery }
            }
        }
        scope.launch {
            persist()
            if (enabled) refreshProviderQuotas()
        }
    }

    private suspend fun prepareMcp(agent: AgentKind): String? = mcpMutex.withLock {
        val port = runCatching { workspaceStore.load().mcpServerPort }.getOrElse { 8565 }
        val isRunning = runCatching { mcp.running.first() }.getOrElse { false }
        if (!isRunning) {
            val result = mcp.start(port)
            check(result.isSuccess) { result.stderr.ifBlank { "server failed to start" } }
        }
        when (agent) {
            // Per-invocation wiring, no config file edits.
            AgentKind.ClaudeCode -> "http://127.0.0.1:$port/mcp-http"
            AgentKind.Codex -> "http://127.0.0.1:$port/mcp"
            // These two only support config-file registration; write it and pass no URL.
            AgentKind.Cursor -> {
                mcp.writeConfig("Cursor", port)
                null
            }
            AgentKind.Antigravity -> {
                mcp.writeConfig("Antigravity", port)
                null
            }
        }
    }

    private fun binaryFor(agent: AgentKind): String? {
        val status = _cliStatuses.value.firstOrNull { it.kind == agent }
        return when {
            status?.ready == true -> status.binaryPath
            status != null -> null
            else -> binaryOverrides[agent.cliName]?.takeIf { File(it).canExecute() }
        }
    }

    private fun unavailableCliMessage(agent: AgentKind): String {
        val issue = _cliStatuses.value.firstOrNull { it.kind == agent }?.issue
        return issue?.let { "${it.title}: ${it.detail}" }
            ?: "${agent.cliName} CLI not found — install it or set a binary override in ~/.andy/agents.toml"
    }

    private fun currentTask(taskId: String): AgentTask? = _tasks.value.firstOrNull { it.id == taskId }

    private fun upsertTask(task: AgentTask) {
        _tasks.update { list ->
            if (list.any { it.id == task.id }) list.map { if (it.id == task.id) task else it } else list + task
        }
    }

    private fun updateTask(taskId: String, transform: (AgentTask) -> AgentTask) {
        _tasks.update { list -> list.map { if (it.id == taskId) transform(it) else it } }
    }

    private fun appendEvents(taskId: String, events: List<AgentEvent>) {
        if (events.isEmpty()) return
        val flow = eventFlows.computeIfAbsent(taskId) { MutableStateFlow(emptyList()) }
        flow.update { existing ->
            coalesceStreamDeltas(existing, events).takeLast(MAX_EVENTS_IN_MEMORY)
        }
    }

    private fun updateQuota(agent: AgentKind, windows: List<app.andy.model.AgentQuotaWindow>, updatedAtMillis: Long) {
        if (windows.isEmpty()) return
        _providerQuotas.update { current ->
            current + (agent to AgentProviderQuota(windows, updatedAtMillis, source = AgentQuotaSource.ProviderEvent))
        }
    }

    /** Recover the latest CLI-emitted limit for each provider without touching provider credentials or network APIs. */
    private fun restoreProviderQuotas(tasks: List<AgentTask>) {
        val restored = linkedMapOf<AgentKind, AgentProviderQuota>()
        tasks.sortedByDescending { it.createdAtMillis }.forEach { task ->
            if (task.agent in restored) return@forEach
            val adapter = adapters[task.agent] ?: return@forEach
            val file = store.transcriptFile(task.id)
            if (!file.isFile) return@forEach
            var latest: Pair<List<app.andy.model.AgentQuotaWindow>, Long>? = null
            runCatching {
                file.useLines { lines ->
                    lines.forEach { line ->
                        adapter.parseLine(line, task.createdAtMillis)
                            .filterIsInstance<AgentEvent.ToolResult>()
                            .lastOrNull { it.quotaWindows.isNotEmpty() }
                            ?.let { result -> latest = result.quotaWindows to result.atMillis }
                    }
                }
            }
            latest?.let { (windows, atMillis) ->
                restored[task.agent] = AgentProviderQuota(windows, atMillis, source = AgentQuotaSource.ProviderEvent)
            }
        }
        if (restored.isNotEmpty()) _providerQuotas.value = restored
    }

    private fun List<AgentEvent>.withEstimatedCosts(task: AgentTask): List<AgentEvent> = map { event ->
        if (event !is AgentEvent.TaskResult || event.costUsd != null) return@map event
        val estimate = task.estimatedTokenCostUsd(event.inputTokens, event.outputTokens) ?: return@map event
        event.copy(costUsd = estimate, costIsEstimated = true)
    }

    private fun coalesceStreamDeltas(
        existing: List<AgentEvent>,
        incoming: List<AgentEvent>,
    ): List<AgentEvent> = incoming.fold(existing) { transcript, event ->
        val previous = transcript.lastOrNull()
        val merged = when {
            event is AgentEvent.AssistantText && event.isStreamDelta &&
                previous is AgentEvent.AssistantText && previous.isStreamDelta -> {
                previous.copy(atMillis = event.atMillis, text = previous.text + event.text)
            }
            event is AgentEvent.Thinking && event.isStreamDelta &&
                previous is AgentEvent.Thinking && previous.isStreamDelta -> {
                previous.copy(atMillis = event.atMillis, text = previous.text + event.text)
            }
            else -> null
        }
        if (merged == null) transcript + event else transcript.dropLast(1) + merged
    }

    private fun writeAndyTranscriptLine(taskId: String, userText: String, skills: List<AgentSkill>, atMillis: Long) {
        runCatching {
            val file = store.transcriptFile(taskId)
            file.parentFile?.mkdirs()
            val line = buildJsonObject {
                put("andyUserMessage", userText)
                put("andySkillNames", skills.joinToString(SKILL_SEPARATOR) { it.name })
                put("andySkillPaths", skills.joinToString(SKILL_SEPARATOR) { it.path })
                put("atMillis", atMillis)
            }.toString()
            file.appendText(line + "\n")
        }
    }

    private fun parseAndyTranscriptLine(line: String): AgentEvent.UserMessage? {
        if (!line.startsWith("{\"andyUserMessage\"")) return null
        val objectValue = parseJsonObject(line) ?: return null
        val text = objectValue.stringOrNull("andyUserMessage") ?: return null
        val names = objectValue.stringOrNull("andySkillNames")?.split(SKILL_SEPARATOR).orEmpty()
        val paths = objectValue.stringOrNull("andySkillPaths")?.split(SKILL_SEPARATOR).orEmpty()
        val skills = names.zip(paths).filter { (_, path) -> path.isNotBlank() }.map { (name, path) ->
            AgentSkill(name = name, description = "", path = path)
        }
        return AgentEvent.UserMessage(objectValue.longOrNull("atMillis") ?: 0L, text, skills)
    }

    override fun markRead(taskId: String) {
        val task = currentTask(taskId) ?: return
        if (!task.unread) return
        updateTask(taskId) { it.copy(unread = false) }
        scope.launch { persist() }
    }

    override fun markUnread(taskId: String) {
        val task = currentTask(taskId) ?: return
        if (task.unread) return
        updateTask(taskId) { it.copy(unread = true) }
        scope.launch { persist() }
    }

    private fun finishTask(taskId: String, status: AgentTaskStatus, exitCode: Int?, error: String?) {
        val completedChanges = currentTask(taskId)?.let { task ->
            task.cwd?.takeIf { task.hasChangeBaseline }?.let { cwd ->
                worktrees.changeSnapshot(cwd, task.changeBaselinePaths)
            }
        }
        updateTask(taskId) { task ->
            if (task.isActive) {
                task.copy(
                    status = status,
                    exitCode = exitCode,
                    errorMessage = error,
                    finishedAtMillis = System.currentTimeMillis(),
                    unread = true,
                    completedChanges = completedChanges ?: task.completedChanges,
                )
            } else {
                task
            }
        }
        val queuedFollowUp = currentTask(taskId)?.queuedFollowUps?.firstOrNull()
        handles.remove(taskId)
        if (status == AgentTaskStatus.Completed && queuedFollowUp != null) {
            updateTask(taskId) { current -> current.copy(queuedFollowUps = current.queuedFollowUps.drop(1)) }
            resume(taskId, queuedFollowUp.text, queuedFollowUp.imagePaths, queuedFollowUp.skills)
        } else {
            scope.launch { persist() }
        }
    }

    private suspend fun persist() {
        persistMutex.withLock {
            store.save(
                AgentStoreState(
                    tasks = _tasks.value,
                    binaryOverrides = binaryOverrides,
                    providerDefaults = _providerDefaults.value,
                    quotaAccess = _quotaAccess.value,
                    lastUsedAgent = _lastUsedAgent.value,
                    maxConcurrent = storedMaxConcurrent,
                ),
            )
        }
    }

    @Volatile
    private var storedMaxConcurrent: Int = 8

    private fun nullInputFile(): File {
        val osName = System.getProperty("os.name")?.lowercase().orEmpty()
        return File(if (osName.contains("win")) "NUL" else "/dev/null")
    }

    /** Discovers each CLI's native roots plus explicitly supported compatibility roots. */
    private fun discoverSkills(agent: AgentKind, directory: String?): List<AgentSkill> {
        val home = System.getProperty("user.home") ?: return emptyList()
        val workspace = directory?.let(::File)?.takeIf(File::isDirectory)
        val codexHome = System.getenv("CODEX_HOME")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: File(home, ".codex")
        val roots = skillRootsFor(agent, workspace, File(home), codexHome)
        val discovered = linkedMapOf<String, AgentSkill>()
        roots.forEach { root ->
            if (!root.isDirectory) return@forEach
            root.walkTopDown()
                .maxDepth(8)
                .filter { file -> file.name == "SKILL.md" && file.isFile }
                .take(200)
                .forEach { file ->
                    val header = runCatching { file.useLines { lines -> lines.take(24).toList() } }.getOrDefault(emptyList())
                    val name = header.firstOrNull { it.startsWith("name:") }
                        ?.substringAfter(':')?.trim()?.trim('"', '\'')
                        ?.takeIf { it.isNotBlank() }
                        ?: file.parentFile.name
                    val description = header.firstOrNull { it.startsWith("description:") }
                        ?.substringAfter(':')?.trim()?.trim('"', '\'')
                        .orEmpty()
                    discovered.putIfAbsent(name.lowercase(), AgentSkill(name, description, file.absolutePath))
                }
        }
        return discovered.values.sortedBy { it.name.lowercase() }
    }

    private fun killTree(process: Process) {
        val descendants = process.descendants().toList().asReversed()
        descendants.forEach { it.destroy() }
        process.destroy()
        process.waitFor(1500, TimeUnit.MILLISECONDS)
        (descendants + process.descendants().toList())
            .distinct()
            .filter { it.isAlive }
            .forEach { it.destroyForcibly() }
        if (process.isAlive) process.destroyForcibly()
    }
}

/**
 * Skill roots are ordered from the provider's native locations to compatible
 * locations. Earlier roots win when two skills use the same name.
 */
internal fun skillRootsFor(
    agent: AgentKind,
    workspace: File?,
    home: File,
    codexHome: File = File(home, ".codex"),
): List<File> = when (agent) {
    // Codex desktop also exposes portable Agent Skills installed under
    // ~/.agents/skills (for example, skills installed by `npx skills`).
    AgentKind.Codex -> listOf(
        File(codexHome, "skills"),
        File(home, ".agents/skills"),
        File(codexHome, "plugins/cache"),
    )
    // Claude gives personal skills precedence over the project directory.
    AgentKind.ClaudeCode -> listOfNotNull(
        File(home, ".claude/skills"),
        workspace?.let { File(it, ".claude/skills") },
    )
    // Cursor discovers its own and portable Agent Skills at workspace and user
    // scope. It also recognizes compatible Codex skills, so include that root
    // after Cursor's native locations rather than hiding installed workflows.
    AgentKind.Cursor -> listOfNotNull(
        workspace?.let { File(it, ".cursor/skills") },
        workspace?.let { File(it, ".agents/skills") },
        File(home, ".cursor/skills"),
        File(home, ".cursor/skills-cursor"),
        File(home, ".agents/skills"),
        File(codexHome, "skills"),
    )
    // Antigravity CLI loads workspace Agent Skills and its own global root.
    AgentKind.Antigravity -> listOfNotNull(
        workspace?.let { File(it, ".agents/skills") },
        File(home, ".gemini/antigravity-cli/skills"),
    )
}

private const val SKILL_SEPARATOR = "\u001F"
