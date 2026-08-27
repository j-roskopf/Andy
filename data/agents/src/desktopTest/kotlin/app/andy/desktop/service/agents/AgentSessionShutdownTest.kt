package app.andy.desktop.service.agents

import app.andy.desktop.service.DesktopWorkspaceStore
import app.andy.model.WorkspaceState
import app.andy.terminal.TmuxAndy
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AgentSessionShutdownTest {
    @BeforeTest
    fun isolateFromLiveAndyTmux() {
        TmuxAndy.useIsolatedServerForTests()
    }

    @Test
    fun keepSessionsOnQuitDefaultsFalse() {
        val store = DesktopWorkspaceStore(file = java.io.File.createTempFile("andy-ws", ".properties"))
        assertFalse(AgentSessionShutdown.keepSessionsOnQuit(store))
    }

    @Test
    fun keepSessionsOnQuitReadsWorkspaceSetting() = runBlocking {
        val file = java.io.File.createTempFile("andy-ws", ".properties")
        val store = DesktopWorkspaceStore(file = file)
        store.save(WorkspaceState(keepAgentSessionsOnShutdown = true))
        assertTrue(AgentSessionShutdown.keepSessionsOnQuit(store))
    }

    @Test
    fun onProcessExitKillsTmuxServerWhenNotKeepingSessions() {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return
        }
        TmuxAndy.startServer()
        val taskId = "shutdown-test"
        TmuxAndy.newSession(taskId, cwd = System.getProperty("user.home"), argv = listOf("sleep", "300"))
        assertTrue(TmuxAndy.hasSession(taskId))

        val store = DesktopWorkspaceStore(file = java.io.File.createTempFile("andy-ws", ".properties"))
        val terminals = AgentTerminalManager(scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))

        AgentSessionShutdown.onProcessExit(
            terminals = terminals,
            activeTaskIds = emptyList(),
            workspaceStore = store,
            ownsAgentSessions = true,
        )

        assertFalse(TmuxAndy.hasSession(taskId))
        assertFalse(TmuxAndy.serverResponds())
    }

    @Test
    fun onProcessExitDetachesWhenKeepingSessions() {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return
        }
        TmuxAndy.startServer()
        val taskId = "keep-test"
        TmuxAndy.newSession(taskId, cwd = System.getProperty("user.home"), argv = listOf("sleep", "300"))
        assertTrue(TmuxAndy.hasSession(taskId))

        val file = java.io.File.createTempFile("andy-ws", ".properties")
        val store = DesktopWorkspaceStore(file = file)
        runBlocking { store.save(WorkspaceState(keepAgentSessionsOnShutdown = true)) }
        val terminals = AgentTerminalManager(scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))

        AgentSessionShutdown.onProcessExit(
            terminals = terminals,
            activeTaskIds = emptyList(),
            workspaceStore = store,
            ownsAgentSessions = true,
        )

        assertTrue(TmuxAndy.hasSession(taskId))
        TmuxAndy.killSession(taskId)
    }

    @Test
    fun attachOnlyModeNeverKillsServer() {
        if (!TmuxAndy.isAvailable()) {
            println("SKIP: tmux not installed")
            return
        }
        TmuxAndy.startServer()
        val taskId = "attach-only"
        TmuxAndy.newSession(taskId, cwd = System.getProperty("user.home"), argv = listOf("sleep", "300"))

        val store = DesktopWorkspaceStore(file = java.io.File.createTempFile("andy-ws", ".properties"))
        val terminals = AgentTerminalManager(scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))

        AgentSessionShutdown.onProcessExit(
            terminals = terminals,
            activeTaskIds = emptyList(),
            workspaceStore = store,
            ownsAgentSessions = false,
        )

        assertTrue(TmuxAndy.hasSession(taskId))
        TmuxAndy.killSession(taskId)
    }
}
