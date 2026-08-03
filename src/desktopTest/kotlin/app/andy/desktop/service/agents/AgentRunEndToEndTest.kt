package app.andy.desktop.service.agents

import app.andy.model.ActionsConfig
import app.andy.model.AgentAutonomy
import app.andy.model.AgentEvent
import app.andy.model.AgentKind
import app.andy.model.AgentTask
import app.andy.model.AgentTaskDraft
import app.andy.model.AgentStatus
import app.andy.model.ProjectAgentProfile
import app.andy.model.ProjectPlanVersion
import app.andy.model.ProjectTask
import app.andy.model.ProjectTaskKind
import app.andy.model.ProjectTaskState
import app.andy.model.ProjectWorkflowStage
import app.andy.model.ProjectWorkflowState
import app.andy.model.WorkspaceState
import app.andy.desktop.test.OptInGates
import app.andy.service.ActionConfigStore
import app.andy.service.CommandResult
import app.andy.service.McpServerService
import app.andy.service.WorkspaceStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import app.andy.desktop.test.OptInGates.harnessTimeoutMillis
import org.junit.Assume.assumeTrue

/**
 * Live smoke test against the real vendor CLIs on this machine. Costs a few
 * cents of subscription usage per run, so it only executes when explicitly
 * requested: ANDY_AGENT_E2E=1 ./gradlew desktopTest --tests "*AgentRunEndToEndTest*"
 * Agents whose CLI is not installed are skipped. Not enabled on PR CI.
 */
class AgentRunEndToEndTest {
    @Test
    fun claudeHeadlessRoundTrip() = liveRun(AgentKind.ClaudeCode)

    @Test
    fun codexHeadlessRoundTrip() = liveRun(AgentKind.Codex)

    @Test
    fun antigravityHeadlessRoundTrip() = liveRun(AgentKind.Antigravity)

    private fun liveRun(agent: AgentKind) {
        OptInGates.requireAgentE2E()
        val dir = File.createTempFile("andy-agent-e2e", null).also {
            it.delete()
            it.mkdirs()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            runBlocking {
                val service = DesktopAgentRunService(
                    scope = scope,
                    store = DesktopAgentTaskStore(File(dir, "agents.db")),
                    locator = AgentCliLocator(),
                    adapters = mapOf(
                        AgentKind.ClaudeCode to ClaudeCodeAdapter(),
                        AgentKind.Codex to CodexAdapter(),
                        AgentKind.Cursor to CursorAdapter(),
                        AgentKind.Antigravity to AntigravityAdapter(),
                    ),
                    worktrees = WorktreeManager(File(dir, "worktrees")),
                    mcp = FakeMcp(),
                    workspaceStore = FakeWorkspaceStore(),
                    actionConfig = FakeActionConfig(),
                )
                withTimeout(30_000) {
                    while (service.cliStatuses.value.isEmpty()) delay(100)
                }
                assumeTrue(
                    "SKIP: ${agent.cliName} not installed",
                    service.cliStatuses.value.any { it.kind == agent && it.available },
                )

                val task = service.createAndStart(
                    AgentTaskDraft(
                        title = "e2e ping",
                        prompt = "Reply with exactly the single word: pong",
                        agent = agent,
                        projectId = null,
                        directory = dir.absolutePath,
                        autonomy = AgentAutonomy.Standard,
                    ),
                )
                withTimeout(180_000) {
                    while (service.tasks.value.first { it.id == task.id }.isActive) delay(250)
                }
                val finished = service.tasks.value.first { it.id == task.id }
                val events = service.events(task.id).value
                System.err.println("E2E ${agent.cliName}: status=${finished.status} exit=${finished.exitCode} session=${finished.vendorSessionId} events=${events.size} cost=${finished.totalCostUsd}")
                assumeTrue(
                    "SKIP: ${agent.cliName} not logged in for headless use (error path verified)",
                    !(finished.status == AgentStatus.Error && finished.errorMessage?.contains("Not logged in") == true),
                )
                assertEquals(AgentStatus.Done, finished.status, "events: ${events.takeLast(5)}")
                val launchLog = DesktopAgentTaskStore(File(dir, "agents.db")).launchLogFile(task.id)
                assertTrue(launchLog.exists() && launchLog.length() > 0, "launch diagnostics should be persisted")
            }
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}

class AgentRetryTest {
    @Test
    fun retriesFailedTaskWithAFreshTranscriptAndSession() = runBlocking {
        assertRetryRestartsTask(AgentStatus.Error, errorMessage = "failed before retry", exitCode = 1)
    }

    @Test
    fun retriesInterruptedTaskWithAFreshTranscriptAndSession() = runBlocking {
        assertRetryRestartsTask(AgentStatus.Error, errorMessage = null, exitCode = null)
    }

    private suspend fun assertRetryRestartsTask(
        status: AgentStatus,
        errorMessage: String?,
        exitCode: Int?,
    ) {
        val trueBinary = File("/usr/bin/true")
        if (!trueBinary.canExecute()) return

        val dir = File.createTempFile("andy-agent-retry", null).also {
            it.delete()
            it.mkdirs()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var service: DesktopAgentRunService? = null
        try {
            val store = DesktopAgentTaskStore(File(dir, "agents.db"))
            val task = AgentTask(
                id = "task-retry",
                title = "retry me",
                prompt = "do the thing",
                agent = AgentKind.Codex,
                cwd = dir.absolutePath,
                originDir = dir.absolutePath,
                status = status,
                vendorSessionId = "old-session",
                createdAtMillis = 1,
                startedAtMillis = 2,
                finishedAtMillis = 3,
                exitCode = exitCode,
                errorMessage = errorMessage,
                totalCostUsd = 0.42,
                inputTokens = 10,
                outputTokens = 20,
            )
            store.save(
                AgentStoreState(
                    tasks = listOf(task),
                    binaryOverrides = mapOf(AgentKind.Codex.cliName to trueBinary.absolutePath),
                ),
            )
            store.taskDir(task.id).apply {
                mkdirs()
                resolve("legacy-artifact.txt").writeText("old output\n")
            }

            service = DesktopAgentRunService(
                scope = scope,
                store = store,
                locator = AgentCliLocator(),
                adapters = mapOf(
                    AgentKind.ClaudeCode to ClaudeCodeAdapter(),
                    AgentKind.Codex to CodexAdapter(),
                    AgentKind.Cursor to CursorAdapter(),
                    AgentKind.Antigravity to AntigravityAdapter(),
                ),
                worktrees = WorktreeManager(File(dir, "worktrees")),
                mcp = FakeMcp(),
                workspaceStore = FakeWorkspaceStore(),
                actionConfig = FakeActionConfig(),
                // Fast-exiting fake agents race the tmux-attach path; run them in-process.
                terminalMode = AgentTerminalMode.DirectPty,
            )
            withTimeout(10_000) {
                while (service.cliStatuses.value.none { it.kind == AgentKind.Codex && it.available }) delay(25)
            }

            service.retry(task.id)
            withTimeout(10_000) {
                while (service.tasks.value.single().isActive) delay(25)
            }

            val retried = service.tasks.value.single()
            assertEquals(AgentStatus.Done, retried.status)
            assertNull(retried.vendorSessionId)
            assertNull(retried.errorMessage)
            assertNull(retried.totalCostUsd)
            assertFalse(store.taskDir(task.id).resolve("legacy-artifact.txt").exists())
        } finally {
            // The service's terminal sessions (BossTermBackend) run their PTY wait/scrape
            // loops on their own internal scope, independent of the outer test scope above —
            // scope.cancel() alone never reaches them. Left open, those loops keep polling
            // pty.waitFor() for the rest of the (single-JVM, sequential) suite run, competing
            // with later tests for Dispatchers.IO and occasionally starving their own exit-code
            // detection past its grace window (observed: a later test's process finished but
            // its status read back Error/null instead of Done because of exactly this leak).
            runCatching { service?.close() }
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}

class AgentQueuedFollowUpTest {
    @Test
    fun startsQueuedFollowUpAfterTheCurrentRunCompletes() = runBlocking {
        val shell = File("/bin/sh")
        if (!shell.canExecute()) return@runBlocking
        val dir = File.createTempFile("andy-agent-queue", null).also {
            it.delete()
            it.mkdirs()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var service: DesktopAgentRunService? = null
        try {
            val store = DesktopAgentTaskStore(File(dir, "agents.db"))
            store.save(AgentStoreState(binaryOverrides = mapOf(AgentKind.Codex.cliName to shell.absolutePath)))
            service = DesktopAgentRunService(
                scope = scope,
                store = store,
                locator = AgentCliLocator(),
                adapters = mapOf(AgentKind.Codex to QueueTestAdapter()),
                worktrees = WorktreeManager(File(dir, "worktrees")),
                mcp = FakeMcp(),
                workspaceStore = FakeWorkspaceStore(),
                actionConfig = FakeActionConfig(),
                // Fast-exiting fake agents race the tmux-attach path; run them in-process.
                terminalMode = AgentTerminalMode.DirectPty,
            )
            val task = service.createAndStart(
                AgentTaskDraft(
                    title = "queue test",
                    prompt = "first message",
                    agent = AgentKind.Codex,
                    projectId = null,
                    directory = dir.absolutePath,
                ),
            )
            withTimeout(harnessTimeoutMillis(60_000, 180_000)) {
                while (service.tasks.value.first { it.id == task.id }.status != AgentStatus.Working) delay(25)
            }

            service.queueFollowUp(task.id, "second message")
            service.queueFollowUp(task.id, "third message")
            // queueFollowUp posts to the service scope, so the queue may not reflect both
            // entries the instant the calls return. Poll the combined (still-queued + already
            // delivered) view until both follow-ups are accounted for, in order, instead of
            // sampling once and racing that async write.
            withTimeout(harnessTimeoutMillis(30_000, 120_000)) {
                while (true) {
                    val current = service.tasks.value.first { it.id == task.id }
                    val queuedTexts = current.queuedFollowUps.map { it.text }
                    val liveUserMessages = service.events(task.id).value
                        .filterIsInstance<AgentEvent.UserMessage>()
                        .map { it.text }
                    val observedFollowUps = (queuedTexts + liveUserMessages)
                        .filter { it == "second message" || it == "third message" }
                    if (observedFollowUps == listOf("second message", "third message")) break
                    delay(25)
                }
            }
            File(dir, ".queue-test-ready").writeText("go")

            withTimeout(harnessTimeoutMillis(120_000, 360_000)) {
                while (true) {
                    val current = service.tasks.value.first { it.id == task.id }
                    val userMessages = service.events(task.id).value
                        .filterIsInstance<AgentEvent.UserMessage>()
                        .map { it.text }
                    if (
                        current.status == AgentStatus.Done &&
                        current.queuedFollowUps.isEmpty() &&
                        userMessages == listOf("second message", "third message")
                    ) {
                        break
                    }
                    delay(25)
                }
            }
            val finished = service.tasks.value.first { it.id == task.id }
            assertEquals(AgentStatus.Done, finished.status)
            assertTrue(finished.queuedFollowUps.isEmpty())
            assertTrue(
                service.events(task.id).value.filterIsInstance<AgentEvent.UserMessage>().map { it.text } == listOf("second message", "third message"),
            )
        } finally {
            // See AgentRetryTest's finally block for why this matters: without it, this
            // test's PTY wait/scrape loop leaks into the rest of the suite.
            runCatching { service?.close() }
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}

class AgentUserInputResumeTest {
    @Test
    fun choiceCheckpointWaitsForAnAnswerThenResumesTheProviderSession() = runBlocking {
        val shell = File("/bin/sh")
        if (!shell.canExecute()) return@runBlocking
        val dir = File.createTempFile("andy-agent-user-input", null).also {
            it.delete()
            it.mkdirs()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var service: DesktopAgentRunService? = null
        try {
            val store = DesktopAgentTaskStore(File(dir, "agents.db"))
            store.save(AgentStoreState(binaryOverrides = mapOf(AgentKind.Codex.cliName to shell.absolutePath)))
            service = DesktopAgentRunService(
                scope = scope,
                store = store,
                locator = AgentCliLocator(),
                adapters = mapOf(AgentKind.Codex to UserInputTestAdapter()),
                worktrees = WorktreeManager(File(dir, "worktrees")),
                mcp = FakeMcp(),
                workspaceStore = FakeWorkspaceStore(),
                actionConfig = FakeActionConfig(),
                // Fast-exiting fake agents race the tmux-attach path; run them in-process.
                terminalMode = AgentTerminalMode.DirectPty,
            )
            val task = service.createAndStart(
                AgentTaskDraft("ask", "Ask before planning", AgentKind.Codex, projectId = null, directory = dir.absolutePath),
            )
            withTimeout(30_000) {
                while (service.tasks.value.first { it.id == task.id }.status != AgentStatus.Blocked) delay(25)
            }
            val waiting = service.tasks.value.first { it.id == task.id }
            val request = assertNotNull(waiting.userInputRequest)
            assertEquals("Desktop", request.questions.single().options.first().label)

            service.respondToUserInput(task.id, request.id, mapOf("platform" to "Desktop"))
            withTimeout(30_000) {
                while (service.tasks.value.first { it.id == task.id }.isActive) delay(25)
            }
            val finished = service.tasks.value.first { it.id == task.id }
            assertEquals(AgentStatus.Done, finished.status)
            assertNull(finished.userInputRequest)
            assertTrue(
                service.events(task.id).value.filterIsInstance<AgentEvent.UserMessage>()
                    .any { it.text.contains("Desktop") },
            )
        } finally {
            // See AgentRetryTest's finally block for why this matters: without it, this
            // test's PTY wait/scrape loop leaks into the rest of the suite.
            runCatching { service?.close() }
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}

class CursorPlanBackfillTest {
    @Test
    fun restoresStructuredCursorPlansIntoTheTaskAndProjectWorkflow() = runBlocking {
        val dir = File.createTempFile("andy-cursor-plan-backfill", null).also {
            it.delete()
            it.mkdirs()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var service: DesktopAgentRunService? = null
        try {
            val oldPlan = "Gathering details. Writing the specification."
            val recoveredPlan = "# iOS Live Mirror\n\n- Keep Android and iOS sessions independent."
            val run = AgentTask(
                id = "cursor-spec-run",
                title = "Spec: iOS mirror",
                prompt = "Plan iOS mirroring",
                agent = AgentKind.Cursor,
                cwd = dir.absolutePath,
                originDir = dir.absolutePath,
                planMode = true,
                completedPlanText = null,
                status = AgentStatus.Done,
                workflowTaskId = "spec-ios",
                workflowStage = ProjectWorkflowStage.Spec,
                createdAtMillis = 1,
                finishedAtMillis = 2,
            )
            val workflow = ProjectWorkflowState(
                projectId = "project-ios",
                tasks = listOf(
                    ProjectTask(
                        id = "spec-ios",
                        projectId = "project-ios",
                        kind = ProjectTaskKind.Spec,
                        title = "iOS mirror",
                        instructions = "Plan it",
                        profile = ProjectAgentProfile(agent = AgentKind.Cursor),
                        includeScratchpad = false,
                        state = ProjectTaskState.Completed,
                        planVersions = listOf(ProjectPlanVersion(1, oldPlan, run.id, 2)),
                        createdAtMillis = 1,
                        updatedAtMillis = 2,
                    ),
                ),
            )
            val store = DesktopAgentTaskStore(File(dir, "agents.db"))
            store.save(
                AgentStoreState(
                    tasks = listOf(run),
                    projectWorkflows = mapOf(workflow.projectId to workflow),
                ),
            )
            val artifactDir = AgentWorkflowArtifacts.dirFor(dir, run.id).apply { mkdirs() }
            File(artifactDir, "plan.md").writeText(recoveredPlan)

            service = DesktopAgentRunService(
                scope = scope,
                store = store,
                locator = AgentCliLocator(),
                adapters = mapOf(AgentKind.Cursor to CursorAdapter()),
                worktrees = WorktreeManager(File(dir, "worktrees")),
                mcp = FakeMcp(),
                workspaceStore = FakeWorkspaceStore(),
                actionConfig = FakeActionConfig(),
                // Fast-exiting fake agents race the tmux-attach path; run them in-process.
                terminalMode = AgentTerminalMode.DirectPty,
            )

            withTimeout(10_000) {
                while (true) {
                    val saved = store.load()
                    val memoryHasRecoveredPlan =
                        service.tasks.value.singleOrNull()?.completedPlanText == recoveredPlan &&
                            service.projects.value[workflow.projectId]?.tasks?.singleOrNull()?.planVersions?.singleOrNull()?.text == recoveredPlan
                    val storeHasRecoveredPlan =
                        saved.tasks.singleOrNull()?.completedPlanText == recoveredPlan &&
                            saved.projectWorkflows[workflow.projectId]?.tasks?.singleOrNull()?.planVersions?.singleOrNull()?.text == recoveredPlan
                    if (memoryHasRecoveredPlan && storeHasRecoveredPlan) break
                    delay(25)
                }
            }
            assertEquals(recoveredPlan, service.projects.value[workflow.projectId]?.tasks?.single()?.planVersions?.single()?.text)

            val saved = store.load()
            assertEquals(recoveredPlan, saved.tasks.single().completedPlanText)
            assertEquals(recoveredPlan, saved.projectWorkflows[workflow.projectId]?.tasks?.single()?.planVersions?.single()?.text)
        } finally {
            // See AgentRetryTest's finally block for why this matters: without it, this
            runCatching { service?.close() }
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun prefersPlanArtifactOverGrillMeTranscriptCapturedInCompletedPlanText() = runBlocking {
        val dir = File.createTempFile("andy-cursor-plan-backfill", null).also {
            it.delete()
            it.mkdirs()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var service: DesktopAgentRunService? = null
        try {
            val grillMePreamble = "I dug into the repo before grilling you. Which platforms should v1 ship on?"
            val recoveredPlan = "# Cook mode\n\n- Call keepScreenOn(true) on entry\n- Add DisposableEffect cleanup"
            val run = AgentTask(
                id = "cursor-spec-run",
                title = "Spec: Cook mode",
                prompt = "Plan cook mode keep-awake",
                agent = AgentKind.Cursor,
                cwd = dir.absolutePath,
                originDir = dir.absolutePath,
                planMode = true,
                completedPlanText = grillMePreamble,
                status = AgentStatus.Done,
                workflowTaskId = "spec-cook",
                workflowStage = ProjectWorkflowStage.Spec,
                createdAtMillis = 1,
                finishedAtMillis = 2,
            )
            val workflow = ProjectWorkflowState(
                projectId = "project-cook",
                tasks = listOf(
                    ProjectTask(
                        id = "spec-cook",
                        projectId = "project-cook",
                        kind = ProjectTaskKind.Spec,
                        title = "Cook mode",
                        instructions = "Plan it",
                        profile = ProjectAgentProfile(agent = AgentKind.Cursor),
                        includeScratchpad = false,
                        state = ProjectTaskState.Completed,
                        planVersions = listOf(ProjectPlanVersion(1, grillMePreamble, run.id, 2)),
                        createdAtMillis = 1,
                        updatedAtMillis = 2,
                    ),
                ),
            )
            val store = DesktopAgentTaskStore(File(dir, "agents.db"))
            store.save(
                AgentStoreState(
                    tasks = listOf(run),
                    projectWorkflows = mapOf(workflow.projectId to workflow),
                ),
            )
            File(AgentWorkflowArtifacts.dirFor(dir, run.id).apply { mkdirs() }, "plan.md").writeText(recoveredPlan)

            service = DesktopAgentRunService(
                scope = scope,
                store = store,
                locator = AgentCliLocator(),
                adapters = mapOf(AgentKind.Cursor to CursorAdapter()),
                worktrees = WorktreeManager(File(dir, "worktrees")),
                mcp = FakeMcp(),
                workspaceStore = FakeWorkspaceStore(),
                actionConfig = FakeActionConfig(),
                terminalMode = AgentTerminalMode.DirectPty,
            )

            withTimeout(10_000) {
                while (true) {
                    val saved = store.load()
                    val memoryHasRecoveredPlan =
                        service.tasks.value.singleOrNull()?.completedPlanText == recoveredPlan &&
                            service.projects.value[workflow.projectId]?.tasks?.singleOrNull()?.planVersions?.singleOrNull()?.text == recoveredPlan
                    val storeHasRecoveredPlan =
                        saved.tasks.singleOrNull()?.completedPlanText == recoveredPlan &&
                            saved.projectWorkflows[workflow.projectId]?.tasks?.singleOrNull()?.planVersions?.singleOrNull()?.text == recoveredPlan
                    if (memoryHasRecoveredPlan && storeHasRecoveredPlan) break
                    delay(25)
                }
            }
            assertEquals(recoveredPlan, service.projects.value[workflow.projectId]?.tasks?.single()?.planVersions?.single()?.text)
        } finally {
            // See AgentRetryTest's finally block for why this matters: without it, this
            // test's PTY wait/scrape loop leaks into the rest of the suite.
            runCatching { service?.close() }
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}

private class UserInputTestAdapter : AgentCliAdapter {
    override val kind = AgentKind.Codex

    override fun buildInteractiveCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> {
        val artifactDir = AgentWorkflowArtifacts.dirFor(task.cwd?.let(::File), task.id).absolutePath
        val question =
            """{"questions":[{"id":"platform","question":"Which platform?","options":[{"label":"Desktop"},{"label":"Desktop + web"}]}]}"""
        return listOf(
            binary,
            "-c",
            "mkdir -p ${shellQuote(artifactDir)} && printf %s ${shellQuote(question)} > ${shellQuote("$artifactDir/question.json")} && sleep 2",
        )
    }

    override fun buildInteractiveResumeCommand(
        binary: String,
        task: AgentTask,
        mcpUrl: String?,
        followUp: String?,
        followUpImagePaths: List<String>,
    ): List<String> =
        listOf(binary, "-c", "printf 'planned for desktop\\n'")

    override fun interactiveResumeCommand(binary: String, task: AgentTask): String = shellQuote(binary)
}

private class QueueTestAdapter : AgentCliAdapter {
    override val kind = AgentKind.Codex

    override fun buildInteractiveCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> =
        listOf(
            binary,
            "-c",
            "while [ ! -f '${task.cwd}/.queue-test-ready' ]; do sleep 0.05; done",
        )

    override fun buildInteractiveResumeCommand(
        binary: String,
        task: AgentTask,
        mcpUrl: String?,
        followUp: String?,
        followUpImagePaths: List<String>,
    ): List<String> =
        listOf(binary, "-c", "printf 'queued response\\n'")

    override fun interactiveResumeCommand(binary: String, task: AgentTask): String = shellQuote(binary)
}

private class FakeMcp : McpServerService {
    override val status = MutableStateFlow("stopped")
    override val running = MutableStateFlow(false)
    override suspend fun start(port: Int): CommandResult = CommandResult.success()
    override suspend fun stop(): CommandResult = CommandResult.success()
    override fun getSnippet(clientName: String, port: Int): String = ""
    override fun getClients(): List<String> = emptyList()
    override fun isAutoWriteSupported(clientName: String): Boolean = false
    override fun writeConfig(clientName: String, port: Int): Boolean = false
    override fun getToolNames(): List<String> = emptyList()
}

private class FakeWorkspaceStore : WorkspaceStore {
    override suspend fun load(): WorkspaceState = WorkspaceState()
    override suspend fun save(state: WorkspaceState) = Unit
}

private class FakeActionConfig : ActionConfigStore {
    override suspend fun load(): ActionsConfig = ActionsConfig()
    override suspend fun save(config: ActionsConfig) = Unit
}
