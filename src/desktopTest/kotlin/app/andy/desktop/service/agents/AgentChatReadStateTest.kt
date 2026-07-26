package app.andy.desktop.service.agents

import app.andy.model.ActionsConfig
import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class AgentChatReadStateTest {
    @Test
    fun setChatViewingClearsUnread() = withService { service, _, _ ->
        service.setChatViewing("chat-read", viewing = true)
        assertFalse(task(service, "chat-read").unread)
    }

    @Test
    fun markReadSurvivesServiceRestart() = withService { service, store, root ->
        service.setChatViewing("chat-read", viewing = true)
        assertFalse(task(service, "chat-read").unread)
        // Flush async persist before simulating quit/reopen.
        runBlocking { store.save(AgentStoreState(tasks = service.tasks.value)) }
        service.close()

        val restarted = DesktopAgentRunService(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            store = store,
            locator = AgentCliLocator(),
            adapters = mapOf(
                AgentKind.ClaudeCode to ClaudeCodeAdapter(),
                AgentKind.Codex to CodexAdapter(),
                AgentKind.Cursor to CursorAdapter(),
                AgentKind.Antigravity to AntigravityAdapter(),
            ),
            worktrees = WorktreeManager(File(root, "worktrees")),
            mcp = ChatReadFakeMcp,
            workspaceStore = ChatReadWorkspaceStore,
            actionConfig = ChatReadActionConfig,
            enableProbes = false,
            terminalMode = AgentTerminalMode.DirectPty,
        )
        try {
            runBlocking { restarted.tasks.first { it.any { task -> task.id == "chat-read" } } }
            assertFalse(task(restarted, "chat-read").unread)
        } finally {
            restarted.close()
        }
    }

    @Test
    fun viewingClearsStaleUnreadBadgeWithoutReconcileChangingIt() = withService(
        taskId = "chat-stale",
        seedStaleFinishedWorking = true,
    ) { service, _, _ ->
        assertTrue(task(service, "chat-stale").unread)
        service.setChatViewing("chat-stale", viewing = true)
        service.reconcileStaleActiveTaskIfNeeded("chat-stale")

        val task = task(service, "chat-stale")
        assertEquals(AgentStatus.Done, task.status)
        assertFalse(task.unread)
    }

    @Test
    fun rapidClickAwayDoesNotRebadgeAfterLateReconcile() = withService(
        taskId = "chat-flip",
        unread = true,
        seedStaleFinishedWorking = true,
    ) { service, _, _ ->
        service.setChatViewing("chat-flip", viewing = true)
        assertFalse(task(service, "chat-flip").unread)

        service.setChatViewing("chat-flip", viewing = false)
        service.reconcileStaleActiveTaskIfNeeded("chat-flip")

        assertFalse(task(service, "chat-flip").unread)
    }

    @Test
    fun reconcileAppliesAttentionWhenDiscoveringCompletedTurn() {
        val working = AgentTask(
            id = "t",
            title = "t",
            prompt = "p",
            agent = AgentKind.ClaudeCode,
            status = AgentStatus.Working,
            startedAtMillis = 1,
            createdAtMillis = 0,
            unread = false,
        )
        assertTrue(
            statusNeedsUnread(
                task = working,
                previous = AgentStatus.Working,
                next = AgentStatus.Done,
                viewing = false,
            ),
        )
        assertFalse(
            statusNeedsUnread(
                task = working,
                previous = AgentStatus.Working,
                next = AgentStatus.Done,
                viewing = true,
            ),
        )
    }

    private fun task(service: DesktopAgentRunService, id: String): AgentTask =
        service.tasks.value.single { it.id == id }

    private fun withService(
        taskId: String = "chat-read",
        unread: Boolean = true,
        seedStaleFinishedWorking: Boolean = false,
        block: suspend (DesktopAgentRunService, DesktopAgentTaskStore, File) -> Unit,
    ) {
        val root = File.createTempFile("andy-read-state", null).also { it.delete(); it.mkdirs() }
        val store = DesktopAgentTaskStore(File(root, "agents.db"))
        val seededTask = when {
            seedStaleFinishedWorking -> AgentTask(
                id = taskId,
                title = "t",
                prompt = "p",
                agent = AgentKind.ClaudeCode,
                cwd = root.absolutePath,
                originDir = root.absolutePath,
                status = AgentStatus.Working,
                statusConfident = true,
                createdAtMillis = 1,
                startedAtMillis = 2,
                finishedAtMillis = 3,
                exitCode = 0,
                unread = unread,
            )
            else -> AgentTask(
                id = taskId,
                title = "t",
                prompt = "p",
                agent = AgentKind.ClaudeCode,
                cwd = root.absolutePath,
                originDir = root.absolutePath,
                status = AgentStatus.Working,
                createdAtMillis = 1,
                startedAtMillis = 2,
                unread = unread,
            )
        }
        val shell = if (System.getProperty("os.name").contains("windows", ignoreCase = true)) {
            checkNotNull(System.getenv("ComSpec"))
        } else {
            "/bin/sh"
        }
        runBlocking {
            // Stub every AgentKind so init-time locateAll never probes real CLIs.
            store.save(
                AgentStoreState(
                    tasks = listOf(seededTask),
                    binaryOverrides = AgentKind.entries.associate { it.cliName to shell },
                ),
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var service: DesktopAgentRunService? = null
        try {
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
                worktrees = WorktreeManager(File(root, "worktrees")),
                mcp = ChatReadFakeMcp,
                workspaceStore = ChatReadWorkspaceStore,
                actionConfig = ChatReadActionConfig,
                enableProbes = false,
                terminalMode = AgentTerminalMode.DirectPty,
            )
            runBlocking {
                service.tasks.first { it.any { task -> task.id == taskId } }
                block(service, store, root)
            }
        } finally {
            runCatching { service?.close() }
            scope.cancel()
            root.deleteRecursively()
        }
    }
}

private object ChatReadFakeMcp : McpServerService {
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

private object ChatReadWorkspaceStore : WorkspaceStore {
    override suspend fun load(): WorkspaceState = WorkspaceState()
    override suspend fun save(state: WorkspaceState) = Unit
}

private object ChatReadActionConfig : ActionConfigStore {
    override suspend fun load(): ActionsConfig = ActionsConfig()
    override suspend fun save(config: ActionsConfig) = Unit
}
