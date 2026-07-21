package app.andy.desktop.service.agents

import app.andy.model.ActionsConfig
import app.andy.model.AgentAutonomy
import app.andy.model.AgentEvent
import app.andy.model.AgentKind
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentSkill
import app.andy.model.AgentTask
import app.andy.model.AgentTaskDraft
import app.andy.model.AgentTaskStatus
import app.andy.model.ProjectAgentProfile
import app.andy.model.ProjectPlanVersion
import app.andy.model.ProjectTask
import app.andy.model.ProjectTaskKind
import app.andy.model.ProjectTaskState
import app.andy.model.ProjectWorkflowStage
import app.andy.model.ProjectWorkflowState
import app.andy.model.WorkspaceState
import app.andy.service.ActionConfigStore
import app.andy.service.CommandResult
import app.andy.service.McpServerService
import app.andy.service.WorkspaceStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Live smoke test against the real vendor CLIs on this machine. Costs a few
 * cents of subscription usage per run, so it only executes when explicitly
 * requested: ANDY_AGENT_E2E=1 ./gradlew desktopTest --tests "*AgentRunEndToEndTest*"
 * Agents whose CLI is not installed are skipped.
 */
class AgentRunEndToEndTest {
    private val enabled = System.getenv("ANDY_AGENT_E2E") == "1"

    @Test
    fun claudeHeadlessRoundTrip() = liveRun(AgentKind.ClaudeCode)

    @Test
    fun codexHeadlessRoundTrip() = liveRun(AgentKind.Codex)

    @Test
    fun antigravityHeadlessRoundTrip() = liveRun(AgentKind.Antigravity)

    private fun liveRun(agent: AgentKind) {
        if (!enabled) return
        val dir = File.createTempFile("andy-agent-e2e", null).also {
            it.delete()
            it.mkdirs()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            runBlocking {
                val service = DesktopAgentRunService(
                    scope = scope,
                    store = DesktopAgentTaskStore(File(dir, "agents.toml")),
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
                if (service.cliStatuses.value.none { it.kind == agent && it.available }) {
                    println("SKIP: ${agent.cliName} not installed")
                    return@runBlocking
                }

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
                println("E2E ${agent.cliName}: status=${finished.status} exit=${finished.exitCode} session=${finished.vendorSessionId} events=${events.size} cost=${finished.totalCostUsd}")
                if (finished.status == AgentTaskStatus.Failed && finished.errorMessage?.contains("Not logged in") == true) {
                    // The CLI has no headless credentials on this machine; the auth
                    // failure was detected and surfaced exactly as designed.
                    println("SKIP: ${agent.cliName} not logged in for headless use (error path verified)")
                    return@runBlocking
                }
                assertEquals(AgentTaskStatus.Completed, finished.status, "final text/events: ${events.takeLast(5)}")
                if (agent != AgentKind.Antigravity) {
                    assertNotNull(finished.vendorSessionId, "session id should be captured for resume")
                }
                val result = events.filterIsInstance<AgentEvent.TaskResult>().lastOrNull()
                assertNotNull(result)
                assertTrue(
                    events.any { it is AgentEvent.AssistantText && it.text.contains("pong", ignoreCase = true) } ||
                        result.finalText?.contains("pong", ignoreCase = true) == true,
                    "expected pong in transcript",
                )
                val transcript = DesktopAgentTaskStore(File(dir, "agents.toml")).transcriptFile(task.id)
                assertTrue(transcript.exists() && transcript.length() > 0, "raw transcript should be persisted")
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
        assertRetryRestartsTask(AgentTaskStatus.Failed, errorMessage = "failed before retry", exitCode = 1)
    }

    @Test
    fun retriesInterruptedTaskWithAFreshTranscriptAndSession() = runBlocking {
        assertRetryRestartsTask(AgentTaskStatus.Unknown, errorMessage = null, exitCode = null)
    }

    private suspend fun assertRetryRestartsTask(
        status: AgentTaskStatus,
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
        try {
            val store = DesktopAgentTaskStore(File(dir, "agents.toml"))
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
            store.transcriptFile(task.id).apply {
                parentFile.mkdirs()
                writeText("old output\n")
            }

            val service = DesktopAgentRunService(
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
            )
            withTimeout(10_000) {
                while (service.cliStatuses.value.none { it.kind == AgentKind.Codex && it.available }) delay(25)
            }

            service.retry(task.id)
            withTimeout(10_000) {
                while (service.tasks.value.single().isActive) delay(25)
            }

            val retried = service.tasks.value.single()
            assertEquals(AgentTaskStatus.Completed, retried.status)
            assertNull(retried.vendorSessionId)
            assertNull(retried.errorMessage)
            assertNull(retried.totalCostUsd)
            assertTrue(store.transcriptFile(task.id).readText().isBlank())
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}

class AgentPlanHandoffTest {
    @Test
    fun completedPlanStartsAFreshWritableRunWithTheOriginalRequestAndPlan() = runBlocking {
        val shell = File("/bin/sh")
        if (!shell.canExecute()) return@runBlocking

        val dir = File.createTempFile("andy-agent-plan-handoff", null).also {
            it.delete()
            it.mkdirs()
        }
        val cwd = File(dir, "existing-worktree").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val completedPlan = "1. Add the handoff transition.\n2. Cover it with tests."
            val planned = AgentTask(
                id = "task-plan-handoff",
                title = "plan the handoff",
                prompt = "make the agent plan handoff work",
                agent = AgentKind.Codex,
                cwd = cwd.absolutePath,
                originDir = dir.absolutePath,
                useWorktree = true,
                worktreePath = cwd.absolutePath,
                branchName = "andy/codex/plan-handoff",
                attachAndyMcp = true,
                autonomy = AgentAutonomy.ReadOnly,
                sandboxMode = AgentSandboxMode.ReadOnly,
                planMode = true,
                completedPlanText = completedPlan,
                model = "gpt-5.6-terra",
                skills = listOf(AgentSkill("verify", "", "/tmp/verify/SKILL.md")),
                status = AgentTaskStatus.Completed,
                vendorSessionId = "read-only-plan-thread",
                createdAtMillis = 1,
                finishedAtMillis = 2,
                exitCode = 0,
                changeBaselinePaths = listOf("stale-plan-baseline"),
                hasChangeBaseline = true,
            )
            val store = DesktopAgentTaskStore(File(dir, "agents.toml"))
            store.save(
                AgentStoreState(
                    tasks = listOf(planned),
                    binaryOverrides = mapOf(AgentKind.Codex.cliName to shell.absolutePath),
                ),
            )
            val adapter = PlanHandoffTestAdapter()
            val service = DesktopAgentRunService(
                scope = scope,
                store = store,
                locator = AgentCliLocator(),
                adapters = mapOf(AgentKind.Codex to adapter),
                worktrees = WorktreeManager(File(dir, "worktrees")),
                mcp = FakeMcp(),
                workspaceStore = FakeWorkspaceStore(),
                actionConfig = FakeActionConfig(),
            )
            withTimeout(10_000) {
                while (service.cliStatuses.value.none { it.kind == AgentKind.Codex && it.available }) delay(25)
            }

            service.startImplementation(planned.id)
            withTimeout(10_000) {
                while (adapter.freshTasks.isEmpty()) delay(25)
            }
            withTimeout(10_000) {
                while (service.tasks.value.single().isActive) delay(25)
            }

            val implementation = service.tasks.value.single()
            val launched = adapter.freshTasks.single()
            assertEquals(AgentTaskStatus.Completed, implementation.status)
            assertTrue(!implementation.planMode)
            assertEquals(AgentSandboxMode.WorkspaceWrite, implementation.sandboxMode)
            assertEquals(planned.cwd, implementation.cwd)
            assertEquals(planned.worktreePath, implementation.worktreePath)
            assertEquals(planned.model, implementation.model)
            assertEquals(planned.skills, implementation.skills)
            assertEquals(planned.attachAndyMcp, implementation.attachAndyMcp)
            assertTrue(!implementation.hasChangeBaseline)
            assertTrue(implementation.changeBaselinePaths.isEmpty())
            assertTrue(launched.implementationPrompt?.contains(planned.prompt) == true)
            assertTrue(launched.implementationPrompt.contains(completedPlan))
            assertTrue(!launched.implementationPrompt.contains("Plan mode is active"))
            assertTrue(adapter.resumeCalls == 0, "implementation must never use a provider resume command")
            assertTrue(
                service.events(planned.id).value.filterIsInstance<AgentEvent.UserMessage>()
                    .any { it.text.startsWith("Begin implementation.") },
            )
        } finally {
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
        try {
            val store = DesktopAgentTaskStore(File(dir, "agents.toml"))
            store.save(AgentStoreState(binaryOverrides = mapOf(AgentKind.Codex.cliName to shell.absolutePath)))
            val service = DesktopAgentRunService(
                scope = scope,
                store = store,
                locator = AgentCliLocator(),
                adapters = mapOf(AgentKind.Codex to QueueTestAdapter()),
                worktrees = WorktreeManager(File(dir, "worktrees")),
                mcp = FakeMcp(),
                workspaceStore = FakeWorkspaceStore(),
                actionConfig = FakeActionConfig(),
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
            withTimeout(10_000) {
                while (service.tasks.value.first { it.id == task.id }.vendorSessionId == null) delay(25)
            }

            service.queueFollowUp(task.id, "second message")
            service.queueFollowUp(task.id, "third message")
            assertEquals(
                listOf("second message", "third message"),
                service.tasks.value.first { it.id == task.id }.queuedFollowUps.map { it.text },
            )

            withTimeout(10_000) {
                while (true) {
                    val current = service.tasks.value.first { it.id == task.id }
                    val userMessages = service.events(task.id).value
                        .filterIsInstance<AgentEvent.UserMessage>()
                        .map { it.text }
                    if (
                        current.status == AgentTaskStatus.Completed &&
                        current.queuedFollowUps.isEmpty() &&
                        userMessages == listOf("second message", "third message")
                    ) {
                        break
                    }
                    delay(25)
                }
            }
            val finished = service.tasks.value.first { it.id == task.id }
            assertEquals(AgentTaskStatus.Completed, finished.status)
            assertTrue(finished.queuedFollowUps.isEmpty())
            assertTrue(
                service.events(task.id).value.filterIsInstance<AgentEvent.UserMessage>().map { it.text } == listOf("second message", "third message"),
            )
        } finally {
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
        try {
            val store = DesktopAgentTaskStore(File(dir, "agents.toml"))
            store.save(AgentStoreState(binaryOverrides = mapOf(AgentKind.Codex.cliName to shell.absolutePath)))
            val service = DesktopAgentRunService(
                scope = scope,
                store = store,
                locator = AgentCliLocator(),
                adapters = mapOf(AgentKind.Codex to UserInputTestAdapter()),
                worktrees = WorktreeManager(File(dir, "worktrees")),
                mcp = FakeMcp(),
                workspaceStore = FakeWorkspaceStore(),
                actionConfig = FakeActionConfig(),
            )
            val task = service.createAndStart(
                AgentTaskDraft("ask", "Ask before planning", AgentKind.Codex, projectId = null, directory = dir.absolutePath),
            )
            withTimeout(10_000) {
                while (service.tasks.value.first { it.id == task.id }.status != AgentTaskStatus.WaitingForInput) delay(25)
            }
            val waiting = service.tasks.value.first { it.id == task.id }
            val request = assertNotNull(waiting.userInputRequest)
            assertEquals("Desktop", request.questions.single().options.first().label)

            service.respondToUserInput(task.id, request.id, mapOf("platform" to "Desktop"))
            withTimeout(10_000) {
                while (service.tasks.value.first { it.id == task.id }.isActive) delay(25)
            }
            val finished = service.tasks.value.first { it.id == task.id }
            assertEquals(AgentTaskStatus.Completed, finished.status)
            assertNull(finished.userInputRequest)
            assertTrue(
                service.events(task.id).value.filterIsInstance<AgentEvent.UserMessage>()
                    .any { it.text.contains("Desktop") },
            )
        } finally {
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
                completedPlanText = oldPlan,
                status = AgentTaskStatus.Completed,
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
            val store = DesktopAgentTaskStore(File(dir, "agents.toml"))
            store.save(
                AgentStoreState(
                    tasks = listOf(run),
                    projectWorkflows = mapOf(workflow.projectId to workflow),
                ),
            )
            store.transcriptFile(run.id).apply { parentFile.mkdirs() }.writeText(
                """
                {"type":"tool_call","subtype":"completed","tool_call":{"createPlanToolCall":{"args":{"plan":"# iOS Live Mirror\n\n- Keep Android and iOS sessions independent."},"result":{"success":{}}}}}
                {"type":"result","subtype":"success","result":"$oldPlan"}
                """.trimIndent() + "\n",
            )

            val service = DesktopAgentRunService(
                scope = scope,
                store = store,
                locator = AgentCliLocator(),
                adapters = mapOf(AgentKind.Cursor to CursorAdapter()),
                worktrees = WorktreeManager(File(dir, "worktrees")),
                mcp = FakeMcp(),
                workspaceStore = FakeWorkspaceStore(),
                actionConfig = FakeActionConfig(),
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
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}

private class UserInputTestAdapter : AgentCliAdapter {
    override val kind = AgentKind.Codex
    override val supportsHeadlessResume = true
    override val supportsStreamJson = false

    override fun buildCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> =
        listOf(binary, "-c", "printf 'ask\\n'")

    override fun buildResumeCommand(
        binary: String,
        task: AgentTask,
        followUp: String,
        imagePaths: List<String>,
        mcpUrl: String?,
    ): List<String> = listOf(binary, "-c", "printf 'resumed\\n'")

    override fun interactiveResumeCommand(binary: String, task: AgentTask): String = binary

    override fun parseLine(line: String, nowMillis: Long): List<AgentEvent> = when (line) {
        "ask" -> listOf(
            AgentEvent.SessionStarted(nowMillis, "user-input-session", null),
            AgentEvent.AssistantText(
                nowMillis,
                "<andy_user_input>{\"questions\":[{\"id\":\"platform\",\"question\":\"Which platform?\",\"options\":[{\"label\":\"Desktop\"},{\"label\":\"Desktop + web\"}]}]}</andy_user_input>",
            ),
            AgentEvent.TaskResult(nowMillis, success = true, finalText = "asked"),
        )
        "resumed" -> listOf(
            AgentEvent.AssistantText(nowMillis, "planned for desktop"),
            AgentEvent.TaskResult(nowMillis, success = true, finalText = "planned for desktop"),
        )
        else -> emptyList()
    }
}

private class QueueTestAdapter : AgentCliAdapter {
    override val kind = AgentKind.Codex
    override val supportsHeadlessResume = true
    override val supportsStreamJson = false

    override fun buildCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> =
        listOf(binary, "-c", "printf 'session\\n'; sleep 1")

    override fun buildResumeCommand(
        binary: String,
        task: AgentTask,
        followUp: String,
        imagePaths: List<String>,
        mcpUrl: String?,
    ): List<String> = listOf(binary, "-c", "printf 'resumed\\n'")

    override fun interactiveResumeCommand(binary: String, task: AgentTask): String = binary

    override fun parseLine(line: String, nowMillis: Long): List<AgentEvent> = when (line) {
        "session" -> listOf(AgentEvent.SessionStarted(nowMillis, "queue-test-session", null))
        "resumed" -> listOf(AgentEvent.AssistantText(nowMillis, "queued response"))
        else -> emptyList()
    }
}

private class PlanHandoffTestAdapter : AgentCliAdapter {
    override val kind = AgentKind.Codex
    override val supportsHeadlessResume = true
    override val supportsStreamJson = false
    val freshTasks = mutableListOf<AgentTask>()
    var resumeCalls = 0

    override fun buildCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> {
        freshTasks += task
        return listOf(binary, "-c", "printf 'implementation complete\\n'")
    }

    override fun buildResumeCommand(
        binary: String,
        task: AgentTask,
        followUp: String,
        imagePaths: List<String>,
        mcpUrl: String?,
    ): List<String> {
        resumeCalls += 1
        return listOf(binary, "-c", "printf 'resumed\\n'")
    }

    override fun interactiveResumeCommand(binary: String, task: AgentTask): String = binary

    override fun parseLine(line: String, nowMillis: Long): List<AgentEvent> =
        listOf(AgentEvent.AssistantText(nowMillis, line))
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
