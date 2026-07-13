package app.andy.desktop.service.agents

import app.andy.model.AgentAutonomy
import app.andy.model.AgentKind
import app.andy.model.AgentReasoningEffort
import app.andy.model.AgentProviderDefaults
import app.andy.model.AgentQueuedFollowUp
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentSkill
import app.andy.model.AgentTask
import app.andy.model.AgentTaskStatus
import app.andy.model.AgentChangeSummary
import app.andy.model.AgentFileChange
import app.andy.model.AgentFileDiff
import app.andy.model.AgentThreadChangeSnapshot
import app.andy.model.DiffLine
import app.andy.model.DiffLineKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class DesktopAgentTaskStoreTest {
    private fun withStore(block: suspend (DesktopAgentTaskStore) -> Unit) {
        val dir = File.createTempFile("andy-agents", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            runBlocking { block(DesktopAgentTaskStore(File(dir, "agents.toml"))) }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun roundTripsTasks() = withStore { store ->
        val task = AgentTask(
            id = "task-abc",
            title = "fix the bug",
            prompt = "fix it\nwith newlines",
            agent = AgentKind.Codex,
            projectId = "proj-1",
            cwd = "/tmp/wt",
            originDir = "/tmp/repo",
            useWorktree = true,
            worktreePath = "/tmp/wt",
            branchName = "andy/codex/fix-abc",
            attachAndyMcp = true,
            autonomy = AgentAutonomy.Full,
            sandboxMode = AgentSandboxMode.None,
            planMode = true,
            completedPlanText = "1. Update the service\n2. Verify it",
            implementationPrompt = "Begin implementation using the completed plan.",
            model = "gpt-5.6-sol",
            reasoningEffort = AgentReasoningEffort.ExtraHigh,
            imagePaths = listOf("/tmp/reference.png"),
            goal = "Ship the regression fix with coverage",
            queuedFollowUps = listOf(
                AgentQueuedFollowUp(
                    text = "run the verification suite next",
                    imagePaths = listOf("/tmp/next.png"),
                    skills = listOf(AgentSkill("verify", "", "/tmp/verify/SKILL.md")),
                ),
                AgentQueuedFollowUp(text = "then summarize the results"),
            ),
            maxBudgetUsd = 2.5,
            completedChanges = AgentThreadChangeSnapshot(
                summary = AgentChangeSummary(listOf(AgentFileChange("src/Main.kt", additions = 2, deletions = 1))),
                diffs = mapOf(
                    "src/Main.kt" to AgentFileDiff(
                        path = "src/Main.kt",
                        lines = listOf(
                            DiffLine(DiffLineKind.Deletion, "old", oldLineNumber = 1),
                            DiffLine(DiffLineKind.Addition, "new", newLineNumber = 1),
                        ),
                    ),
                ),
            ),
            status = AgentTaskStatus.Completed,
            vendorSessionId = "t-99",
            createdAtMillis = 111,
            startedAtMillis = 222,
            finishedAtMillis = 333,
            exitCode = 0,
            totalCostUsd = 0.42,
            inputTokens = 100,
            outputTokens = 200,
            contextTokens = 120_000,
            contextWindowTokens = 272_000,
        )
        store.save(AgentStoreState(tasks = listOf(task), binaryOverrides = mapOf("codex" to "/bin/codex"), maxConcurrent = 4))
        val loaded = store.load()
        assertEquals(listOf(task), loaded.tasks)
        assertEquals(mapOf("codex" to "/bin/codex"), loaded.binaryOverrides)
        assertEquals(4, loaded.maxConcurrent)
    }

    @Test
    fun activeTasksBecomeUnknownOnLoad() = withStore { store ->
        val running = AgentTask(
            id = "task-run",
            title = "still going",
            prompt = "p",
            agent = AgentKind.ClaudeCode,
            cwd = "/tmp",
            originDir = "/tmp",
            status = AgentTaskStatus.Running,
            createdAtMillis = 1,
        )
        val queued = running.copy(id = "task-q", status = AgentTaskStatus.Queued)
        store.save(AgentStoreState(tasks = listOf(running, queued)))
        val loaded = store.load()
        assertEquals(setOf(AgentTaskStatus.Unknown), loaded.tasks.map { it.status }.toSet())
    }

    @Test
    fun missingFileYieldsDefaults() = withStore { store ->
        val loaded = store.load()
        assertEquals(AgentStoreState(), loaded)
    }

    @Test
    fun roundTripsProviderDefaults() = withStore { store ->
        val defaults = AgentProviderDefaults(
            model = "gpt-5.6-terra",
            reasoningEffort = AgentReasoningEffort.High,
            autonomy = AgentAutonomy.Full,
            sandboxMode = AgentSandboxMode.None,
            planMode = true,
            useWorktree = true,
            attachAndyMcp = true,
            maxBudgetUsd = 4.0,
        )
        store.save(
            AgentStoreState(
                providerDefaults = mapOf(AgentKind.Codex to defaults),
                lastUsedAgent = AgentKind.Codex,
            ),
        )

        val loaded = store.load()
        assertEquals(mapOf(AgentKind.Codex to defaults), loaded.providerDefaults)
        assertEquals(AgentKind.Codex, loaded.lastUsedAgent)
    }
}
