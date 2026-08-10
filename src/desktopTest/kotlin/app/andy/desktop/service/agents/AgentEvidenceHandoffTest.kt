package app.andy.desktop.service.agents

import app.andy.desktop.test.OptInGates.harnessTimeoutMillis
import app.andy.model.ActionsConfig
import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.model.AgentTaskDraft
import app.andy.model.WorkspaceState
import app.andy.model.promptForCli
import app.andy.service.ActionConfigStore
import app.andy.service.CommandResult
import app.andy.service.McpServerService
import app.andy.service.WorkspaceStore
import java.io.File
import kotlin.test.Test
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
 * Covers agent-handoff evidence materialization (plan §4/§5): copying managed evidence
 * bundles into a task's local Andy-managed directory and enriching the launched/resumed
 * prompt with concrete local paths, for createAndStart, resume, and queueFollowUp.
 */
class AgentEvidenceHandoffTest {
    @Test
    fun createAndStartCopiesContextBundlesAndMentionsThemInThePrompt() = runBlocking {
        val shell = File("/bin/sh")
        if (!shell.canExecute()) return@runBlocking
        val dir = File.createTempFile("andy-agent-evidence-start", null).also {
            it.delete()
            it.mkdirs()
        }
        val evidenceRoot = File(dir, "evidence-root")
        val bundleId = "bundle-crash-1"
        File(evidenceRoot, "$bundleId/manifest.json").apply {
            parentFile.mkdirs()
            writeText("""{"artifacts":[]}""")
        }
        File(evidenceRoot, "$bundleId/crash.txt").writeText("fatal exception")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var service: DesktopAgentRunService? = null
        try {
            val store = DesktopAgentTaskStore(File(dir, "agents.db"))
            store.save(AgentStoreState(binaryOverrides = mapOf(AgentKind.Codex.cliName to shell.absolutePath)))
            val adapter = EvidenceCapturingTestAdapter()
            service = DesktopAgentRunService(
                scope = scope,
                store = store,
                locator = AgentCliLocator(),
                adapters = mapOf(AgentKind.Codex to adapter),
                worktrees = WorktreeManager(File(dir, "worktrees")),
                mcp = EvidenceFakeMcp(),
                workspaceStore = EvidenceFakeWorkspaceStore(),
                actionConfig = EvidenceFakeActionConfig(),
                // Fast-exiting fake agents race the tmux-attach path; run them in-process.
                terminalMode = AgentTerminalMode.DirectPty,
                evidenceRootDir = evidenceRoot,
            )
            withTimeout(10_000) {
                while (service.cliStatuses.value.none { it.kind == AgentKind.Codex && it.available }) delay(25)
            }

            val task = service.createAndStart(
                AgentTaskDraft(
                    title = "investigate crash",
                    prompt = "why did this crash",
                    agent = AgentKind.Codex,
                    projectId = null,
                    directory = dir.absolutePath,
                    contextBundleIds = listOf(bundleId),
                ),
            )
            withTimeout(10_000) { while (adapter.launchedTasks.isEmpty()) delay(25) }
            withTimeout(10_000) {
                while (service.tasks.value.first { it.id == task.id }.isActive) delay(25)
            }

            val copiedManifest = File(store.taskEvidenceDir(task.id), "$bundleId/manifest.json")
            val copiedArtifact = File(store.taskEvidenceDir(task.id), "$bundleId/crash.txt")
            assertTrue(copiedManifest.isFile, "evidence manifest should be copied into the task-local dir")
            assertTrue(copiedArtifact.isFile, "evidence artifact should be copied into the task-local dir")

            val launchedTask = adapter.launchedTasks.first()
            val prompt = launchedTask.promptForCli()
            assertTrue(
                prompt.contains(copiedManifest.absolutePath),
                "prompt should point at the copied manifest: $prompt",
            )
            assertTrue(
                prompt.contains(copiedArtifact.absolutePath),
                "prompt should point at the copied artifact: $prompt",
            )
        } finally {
            runCatching { service?.close() }
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun resumeAfterRestartCopiesContextBundlesForAnOrphanedTask() = runBlocking {
        val shell = File("/bin/sh")
        if (!shell.canExecute()) return@runBlocking
        val dir = File.createTempFile("andy-agent-evidence-resume", null).also {
            it.delete()
            it.mkdirs()
        }
        val evidenceRoot = File(dir, "evidence-root")
        val bundleId = "bundle-network-1"
        File(evidenceRoot, "$bundleId/manifest.json").apply {
            parentFile.mkdirs()
            writeText("""{"artifacts":[]}""")
        }

        val dbFile = File(dir, "agents.db")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var service: DesktopAgentRunService? = null
        try {
            // Simulates Andy restarting with a task that finished in a prior process run —
            // there is no live terminal/handle for it in this fresh service instance.
            val store = DesktopAgentTaskStore(dbFile)
            val finished = AgentTask(
                id = "task-restart-resume",
                title = "investigate network failure",
                prompt = "why did the request fail",
                agent = AgentKind.Codex,
                cwd = dir.absolutePath,
                originDir = dir.absolutePath,
                status = AgentStatus.Done,
                createdAtMillis = 1,
                finishedAtMillis = 2,
            )
            store.save(
                AgentStoreState(
                    tasks = listOf(finished),
                    binaryOverrides = mapOf(AgentKind.Codex.cliName to shell.absolutePath),
                ),
            )

            val adapter = EvidenceCapturingTestAdapter()
            service = DesktopAgentRunService(
                scope = scope,
                store = store,
                locator = AgentCliLocator(),
                adapters = mapOf(AgentKind.Codex to adapter),
                worktrees = WorktreeManager(File(dir, "worktrees")),
                mcp = EvidenceFakeMcp(),
                workspaceStore = EvidenceFakeWorkspaceStore(),
                actionConfig = EvidenceFakeActionConfig(),
                terminalMode = AgentTerminalMode.DirectPty,
                evidenceRootDir = evidenceRoot,
            )
            withTimeout(10_000) {
                while (service.cliStatuses.value.none { it.kind == AgentKind.Codex && it.available }) delay(25)
            }
            withTimeout(10_000) { while (service.tasks.value.isEmpty()) delay(25) }

            service.resume(finished.id, "check the network trace", contextBundleIds = listOf(bundleId))

            val copiedManifest = File(store.taskEvidenceDir(finished.id), "$bundleId/manifest.json")
            withTimeout(10_000) { while (!copiedManifest.isFile) delay(25) }
            withTimeout(10_000) {
                while (service.tasks.value.first { it.id == finished.id }.isActive) delay(25)
            }

            assertTrue(copiedManifest.isFile, "evidence should be copied on resume even for an orphaned/restarted task")
            assertTrue(
                adapter.resumeFollowUps.any { it?.contains(copiedManifest.absolutePath) == true },
                "resume follow-up text should point at the copied evidence: ${adapter.resumeFollowUps}",
            )

            // "Restart" again: a brand-new store instance over the same directory must still see
            // the task-local evidence copy — it lives on disk independent of any one store/service,
            // and evidenceLocalPathsHint is intentionally not persisted (recomputed each launch).
            val reloadedStore = DesktopAgentTaskStore(dbFile)
            val afterRestart = File(reloadedStore.taskEvidenceDir(finished.id), "$bundleId/manifest.json")
            assertTrue(afterRestart.isFile, "evidence copy must survive process restart")
        } finally {
            runCatching { service?.close() }
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun queueFollowUpMaterializesContextBundlesWhileTheRunIsStillActive() = runBlocking {
        val shell = File("/bin/sh")
        if (!shell.canExecute()) return@runBlocking
        val dir = File.createTempFile("andy-agent-evidence-queue", null).also {
            it.delete()
            it.mkdirs()
        }
        val evidenceRoot = File(dir, "evidence-root")
        val bundleId = "bundle-hierarchy-1"
        File(evidenceRoot, "$bundleId/manifest.json").apply {
            parentFile.mkdirs()
            writeText("""{"artifacts":[]}""")
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
                adapters = mapOf(AgentKind.Codex to EvidenceQueueTestAdapter()),
                worktrees = WorktreeManager(File(dir, "worktrees")),
                mcp = EvidenceFakeMcp(),
                workspaceStore = EvidenceFakeWorkspaceStore(),
                actionConfig = EvidenceFakeActionConfig(),
                // Fast-exiting fake agents race the tmux-attach path; run them in-process.
                terminalMode = AgentTerminalMode.DirectPty,
                evidenceRootDir = evidenceRoot,
            )
            val task = service.createAndStart(
                AgentTaskDraft(
                    title = "queue evidence test",
                    prompt = "first message",
                    agent = AgentKind.Codex,
                    projectId = null,
                    directory = dir.absolutePath,
                ),
            )
            withTimeout(harnessTimeoutMillis(60_000, 180_000)) {
                while (service.tasks.value.first { it.id == task.id }.status != AgentStatus.Working) delay(25)
            }

            service.queueFollowUp(task.id, "check the hierarchy dump", contextBundleIds = listOf(bundleId))

            val copiedManifest = File(store.taskEvidenceDir(task.id), "$bundleId/manifest.json")
            withTimeout(harnessTimeoutMillis(30_000, 120_000)) {
                while (!copiedManifest.isFile) delay(25)
            }
            assertTrue(copiedManifest.isFile, "queueFollowUp should copy evidence before the run finishes")
            assertTrue(
                service.tasks.value.first { it.id == task.id }.isActive,
                "the run should still be active while the evidence is copied",
            )

            File(dir, ".queue-evidence-ready").writeText("go")
            withTimeout(harnessTimeoutMillis(120_000, 360_000)) {
                while (service.tasks.value.first { it.id == task.id }.isActive) delay(25)
            }
        } finally {
            runCatching { service?.close() }
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}

private class EvidenceCapturingTestAdapter : AgentCliAdapter {
    override val kind = AgentKind.Codex
    val launchedTasks = mutableListOf<AgentTask>()
    val resumeFollowUps = mutableListOf<String?>()

    override fun buildInteractiveCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> {
        launchedTasks += task
        return listOf(binary, "-c", "printf 'done\\n'")
    }

    override fun buildInteractiveResumeCommand(
        binary: String,
        task: AgentTask,
        mcpUrl: String?,
        followUp: String?,
        followUpImagePaths: List<String>,
    ): List<String> {
        resumeFollowUps += followUp
        return listOf(binary, "-c", "printf 'resumed\\n'")
    }

    override fun interactiveResumeCommand(binary: String, task: AgentTask): String = shellQuote(binary)
}

private class EvidenceQueueTestAdapter : AgentCliAdapter {
    override val kind = AgentKind.Codex

    override fun buildInteractiveCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> =
        listOf(
            binary,
            "-c",
            // Relative to the launched cwd so path canonicalization cannot desync the ready file.
            "while [ ! -f .queue-evidence-ready ]; do sleep 0.05; done",
        )

    override fun buildInteractiveResumeCommand(
        binary: String,
        task: AgentTask,
        mcpUrl: String?,
        followUp: String?,
        followUpImagePaths: List<String>,
    ): List<String> = listOf(binary, "-c", "printf 'queued response\\n'")

    override fun interactiveResumeCommand(binary: String, task: AgentTask): String = shellQuote(binary)
}

private class EvidenceFakeMcp : McpServerService {
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

private class EvidenceFakeWorkspaceStore : WorkspaceStore {
    override suspend fun load(): WorkspaceState = WorkspaceState()
    override suspend fun save(state: WorkspaceState) = Unit
}

private class EvidenceFakeActionConfig : ActionConfigStore {
    override suspend fun load(): ActionsConfig = ActionsConfig()
    override suspend fun save(config: ActionsConfig) = Unit
}
