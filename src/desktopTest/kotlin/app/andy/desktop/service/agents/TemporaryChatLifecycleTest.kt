package app.andy.desktop.service.agents

import app.andy.model.ActionProject
import app.andy.model.ActionsConfig
import app.andy.model.AgentKind
import app.andy.model.AgentTask
import app.andy.model.AgentTaskDraft
import app.andy.model.WorkspaceState
import app.andy.service.ActionConfigStore
import app.andy.service.CommandResult
import app.andy.service.McpServerService
import app.andy.service.WorkspaceStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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

/**
 * End-to-end behaviour of temporary chats against the real run service and store: what never
 * reaches disk, what is wiped on discard, and what promotion moves back into the agent store.
 */
class TemporaryChatLifecycleTest {
    @Test
    fun temporaryChatIsNeverWrittenToTheStore() = withService { harness ->
        harness.service.createAndStart(harness.draft("keep this off disk", temporary = true))

        assertEquals(1, harness.service.tasks.value.size, "it stays in the live list")
        assertTrue(harness.service.tasks.value.single().temporary)
        harness.awaitPersisted()
        assertTrue(harness.reload().tasks.isEmpty(), "nothing about it may survive a reload")
    }

    @Test
    fun temporaryArtifactsStayOutOfTheAgentsDirectory() = withService { harness ->
        val task = harness.service.createAndStart(harness.draft("scratch", temporary = true))

        val scrollback = harness.service.scrollbackFile(task.id)
        assertFalse(
            scrollback.absolutePath.startsWith(harness.store.transcriptsDir.absolutePath),
            "temporary scrollback must not land in the agents dir: ${scrollback.absolutePath}",
        )
        assertFalse(harness.store.taskDir(task.id).exists())
    }

    @Test
    fun permanentChatStillPersistsAlongsideATemporaryOne() = withService { harness ->
        val kept = harness.service.createAndStart(harness.draft("real work"))
        harness.service.createAndStart(harness.draft("throwaway", temporary = true))

        harness.awaitPersisted()
        assertEquals(listOf(kept.id), harness.reload().tasks.map { it.id })
    }

    /**
     * The store refuses an empty task list by default. Deleting the last real chat while a
     * temporary one is open produces exactly that shape, and the deletion must still stick.
     */
    @Test
    fun deletingTheLastPermanentChatPersistsEvenWhileATemporaryChatIsOpen() = withService { harness ->
        val kept = harness.service.createAndStart(harness.draft("real work"))
        harness.service.createAndStart(harness.draft("throwaway", temporary = true))
        harness.awaitPersisted()
        assertEquals(1, harness.reload().tasks.size)

        harness.service.delete(kept.id, removeWorktree = false)

        assertTrue(harness.reload().tasks.isEmpty(), "the deletion must not be swallowed")
    }

    @Test
    fun discardRemovesTheTemporaryArtifactsAndTheRepoLocalWorkflowFolder() = withService { harness ->
        val task = harness.service.createAndStart(harness.draft("scratch", temporary = true))
        val artifactDir = AgentWorkflowArtifacts.dirFor(task.cwd?.let(::File), task.id)
        artifactDir.mkdirs()
        File(artifactDir, "question.json").writeText("""{"questions":[]}""")
        val tempDir = harness.service.scrollbackFile(task.id).parentFile
        tempDir.mkdirs()
        File(tempDir, "scrollback.ansi").writeText("secret output")

        harness.service.delete(task.id, removeWorktree = false)

        assertTrue(harness.service.tasks.value.isEmpty())
        assertFalse(tempDir.exists(), "the disposable directory must be gone")
        assertFalse(artifactDir.exists(), "`.andy/<taskId>` in the project must be gone too")
    }

    @Test
    fun keepPromotesTheChatAndMovesItsArtifactsIntoTheAgentStore() = withService { harness ->
        val task = harness.service.createAndStart(harness.draft("turned into real work", temporary = true))
        val tempDir = harness.service.scrollbackFile(task.id).parentFile
        tempDir.mkdirs()
        File(tempDir, "scrollback.ansi").writeText("worth keeping")

        harness.service.keepTemporaryChat(task.id)

        val promoted = harness.service.tasks.value.single { it.id == task.id }
        assertFalse(promoted.temporary)
        assertFalse(tempDir.exists(), "artifacts move rather than being copied")
        assertEquals(
            "worth keeping",
            File(harness.store.taskDir(task.id), "scrollback.ansi").readText(),
        )
        assertEquals(
            harness.store.taskDir(task.id).absolutePath,
            harness.service.scrollbackFile(task.id).parentFile.absolutePath,
            "later writes must resolve to the new home",
        )
        assertEquals(listOf(task.id), harness.reload().tasks.map { it.id })
    }

    @Test
    fun keepIsANoOpForAChatThatIsAlreadyPermanent() = withService { harness ->
        val task = harness.service.createAndStart(harness.draft("already saved"))

        harness.service.keepTemporaryChat(task.id)

        assertFalse(harness.service.tasks.value.single().temporary)
        assertEquals(listOf(task.id), harness.reload().tasks.map { it.id })
    }

    @Test
    fun quitDiscardsTemporaryChatsAndKeepsTheRest() = withService { harness ->
        val kept = harness.service.createAndStart(harness.draft("real work"))
        val temp = harness.service.createAndStart(harness.draft("throwaway", temporary = true))
        val tempDir = harness.service.scrollbackFile(temp.id).parentFile
        tempDir.mkdirs()
        File(tempDir, "scrollback.ansi").writeText("secret output")

        harness.service.shutdownForProcessExit()

        assertEquals(listOf(kept.id), harness.service.tasks.value.map { it.id })
        assertFalse(tempDir.exists())
    }

    private class Harness(
        val service: DesktopAgentRunService,
        val store: DesktopAgentTaskStore,
        val projectDir: File,
    ) {
        fun draft(prompt: String, temporary: Boolean = false) = AgentTaskDraft(
            title = prompt,
            prompt = prompt,
            agent = AgentKind.Codex,
            projectId = null,
            directory = projectDir.absolutePath,
            temporary = temporary,
        )

        suspend fun reload(): AgentStoreState = store.load()

        /** Persistence is fire-and-forget from createAndStart; wait for it to land. */
        suspend fun awaitPersisted() {
            val expected = service.tasks.value.count { !it.temporary }
            withTimeout(10_000) {
                while (reload().tasks.size != expected) delay(20)
            }
        }
    }

    private fun withService(block: suspend (Harness) -> Unit) = runBlocking {
        val root = File.createTempFile("andy-temp-chat", null).also { it.delete(); it.mkdirs() }
        val projectDir = File(root, "project").apply { mkdirs() }
        val store = DesktopAgentTaskStore(File(root, "agents.db"))
        // Shell stub for every provider so locateAll never probes real CLIs.
        store.save(
            AgentStoreState(
                binaryOverrides = AgentKind.entries.associate { it.cliName to shellBinary() },
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var service: DesktopAgentRunService? = null
        try {
            service = DesktopAgentRunService(
                scope = scope,
                store = store,
                locator = AgentCliLocator(),
                adapters = mapOf(AgentKind.Codex to IdleAdapter()),
                worktrees = WorktreeManager(File(root, "worktrees")),
                mcp = FakeMcp,
                workspaceStore = FakeWorkspaceStore,
                actionConfig = FakeActionConfig(
                    ActionsConfig(projects = listOf(ActionProject("project-1", "Test", projectDir.absolutePath))),
                ),
                enableProbes = false,
                terminalMode = AgentTerminalMode.DirectPty,
            )
            service.awaitReady()
            block(Harness(service, store, projectDir))
        } finally {
            runCatching { service?.close() }
            scope.cancel()
            root.deleteRecursively()
        }
    }

    /** Exits immediately: these tests are about bookkeeping, not agent output. */
    private class IdleAdapter(override val kind: AgentKind = AgentKind.Codex) : AgentCliAdapter {
        override fun buildInteractiveCommand(binary: String, task: AgentTask, mcpUrl: String?): List<String> =
            if (isWindows()) listOf(binary, "/d", "/c", "exit 0") else listOf(binary, "-c", "exit 0")

        override fun buildInteractiveResumeCommand(
            binary: String,
            task: AgentTask,
            mcpUrl: String?,
            followUp: String?,
            followUpImagePaths: List<String>,
        ): List<String>? = null

        override fun interactiveResumeCommand(binary: String, task: AgentTask): String = binary
    }

    private class FakeActionConfig(private val config: ActionsConfig) : ActionConfigStore {
        override suspend fun load(): ActionsConfig = config
        override suspend fun save(config: ActionsConfig) = Unit
    }

    private object FakeWorkspaceStore : WorkspaceStore {
        override suspend fun load(): WorkspaceState = WorkspaceState()
        override suspend fun save(state: WorkspaceState) = Unit
    }

    private object FakeMcp : McpServerService {
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

    private companion object {
        fun isWindows(): Boolean = System.getProperty("os.name").contains("windows", ignoreCase = true)

        fun shellBinary(): String = if (isWindows()) {
            checkNotNull(System.getenv("ComSpec")) { "ComSpec is required on Windows" }
        } else {
            "/bin/sh"
        }
    }
}
