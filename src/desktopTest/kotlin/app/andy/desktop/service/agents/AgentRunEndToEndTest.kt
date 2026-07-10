package app.andy.desktop.service.agents

import app.andy.model.ActionsConfig
import app.andy.model.AgentAutonomy
import app.andy.model.AgentEvent
import app.andy.model.AgentKind
import app.andy.model.AgentTask
import app.andy.model.AgentTaskDraft
import app.andy.model.AgentTaskStatus
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
        val trueBinary = File("/usr/bin/true")
        if (!trueBinary.canExecute()) return@runBlocking

        val dir = File.createTempFile("andy-agent-retry", null).also {
            it.delete()
            it.mkdirs()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = DesktopAgentTaskStore(File(dir, "agents.toml"))
            val failed = AgentTask(
                id = "task-retry",
                title = "retry me",
                prompt = "do the thing",
                agent = AgentKind.Codex,
                cwd = dir.absolutePath,
                originDir = dir.absolutePath,
                status = AgentTaskStatus.Failed,
                vendorSessionId = "old-session",
                createdAtMillis = 1,
                startedAtMillis = 2,
                finishedAtMillis = 3,
                exitCode = 1,
                errorMessage = "failed before retry",
                totalCostUsd = 0.42,
                inputTokens = 10,
                outputTokens = 20,
            )
            store.save(
                AgentStoreState(
                    tasks = listOf(failed),
                    binaryOverrides = mapOf(AgentKind.Codex.cliName to trueBinary.absolutePath),
                ),
            )
            store.transcriptFile(failed.id).apply {
                parentFile.mkdirs()
                writeText("old failed output\n")
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

            service.retry(failed.id)
            withTimeout(10_000) {
                while (service.tasks.value.single().isActive) delay(25)
            }

            val retried = service.tasks.value.single()
            assertEquals(AgentTaskStatus.Completed, retried.status)
            assertNull(retried.vendorSessionId)
            assertNull(retried.errorMessage)
            assertNull(retried.totalCostUsd)
            assertTrue(store.transcriptFile(failed.id).readText().isBlank())
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
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
