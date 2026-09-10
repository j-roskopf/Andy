package app.andy.desktop.service.plugins

import app.andy.model.ActionProject
import app.andy.model.ActionRunStatus
import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.model.PluginInvocationContext
import app.andy.model.PluginPanePlacement
import app.andy.model.ProjectAction
import app.andy.model.RunningAction
import app.andy.model.toPluginWireStatus
import app.andy.service.ActionRunService
import app.andy.service.AgentRunService
import app.andy.service.UnavailableAgentRunService
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopPluginServiceTest {
    @Test
    fun wireStatusMapping() {
        assertEquals("working", AgentStatus.Working.toPluginWireStatus())
        assertEquals("blocked", AgentStatus.Blocked.toPluginWireStatus())
        assertEquals("done", AgentStatus.Done.toPluginWireStatus(seen = false))
        assertEquals("idle", AgentStatus.Done.toPluginWireStatus(seen = true))
        assertEquals("unknown", AgentStatus.Error.toPluginWireStatus())
    }

    @Test
    fun linkInvokeAndEmitEvent() = runTest {
        val home = File.createTempFile("andy-plugins-home", null).apply {
            delete()
            mkdirs()
        }
        val pluginRoot = File.createTempFile("andy-plugin-root", null).apply {
            delete()
            mkdirs()
        }
        File(pluginRoot, "andy-plugin.toml").writeText(
            """
            id = "examples.test-plugin"
            name = "Test"
            version = "0.1.0"
            min_andy_version = "0.1.0"
            platforms = ["macos", "linux", "windows"]

            [[actions]]
            id = "ping"
            title = "Ping"
            command = ["sh", "-c", "printf pong"]

            [[events]]
            on = "workspace.focused"
            command = ["sh", "-c", "printf '%s' \"${'$'}ANDY_PLUGIN_EVENT\" > \"${'$'}ANDY_PLUGIN_STATE_DIR/last-event\""]

            [[panes]]
            id = "echo"
            title = "Echo"
            placement = "split"
            command = ["sh", "-c", "printf pane"]
            """.trimIndent(),
        )

        val actionRuns = RecordingActionRuns()
        val service = DesktopPluginService(
            scope = backgroundScope,
            actionRuns = actionRuns,
            store = PluginRegistryStore(andyHome = home),
            andyVersion = { "2026.0909.2356" },
            andyBinPath = { "/tmp/andy" },
            andySocketPath = { "/tmp/andyd.sock" },
        )

        val linked = service.link(pluginRoot.absolutePath)
        assertEquals("examples.test-plugin", linked.pluginId)
        assertTrue(linked.enabled)

        val log = service.invokeAction("ping", pluginId = linked.pluginId)
        assertEquals(0, log.exitCode)
        assertTrue(log.stdout.contains("pong"))

        service.emitEvent(
            "workspace.focused",
            mapOf("workspace_id" to "ws-1"),
            PluginInvocationContext(workspaceId = "ws-1"),
        )
        advanceUntilIdle()
        // Give the IO dispatcher a moment; event hooks launch on Dispatchers.IO
        waitForFile(File(linked.stateDir, "last-event"), timeoutMs = 5_000)
        assertEquals("workspace.focused", File(linked.stateDir, "last-event").readText())

        val session = service.openPane(linked.pluginId, "echo")
        assertEquals(PluginPanePlacement.Split, session.placement)
        assertEquals(1, actionRuns.started.size)
        assertEquals(listOf("sh", "-c", "printf pane"), actionRuns.started.single().argv)

        service.unlink(linked.pluginId)
        assertTrue(service.plugins.value.none { it.pluginId == linked.pluginId })
        home.deleteRecursively()
        pluginRoot.deleteRecursively()
    }

    @Test
    fun loadsInRepoWebhookSample() = runBlocking {
        val root = File("../../../samples/plugins/webhook-notify").canonicalFile
            .takeIf { it.isDirectory }
            ?: File("samples/plugins/webhook-notify").canonicalFile
        if (!root.isDirectory) return@runBlocking
        val home = File.createTempFile("andy-plugins-home", null).apply {
            delete()
            mkdirs()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val service = DesktopPluginService(
                scope = scope,
                actionRuns = RecordingActionRuns(),
                store = PluginRegistryStore(andyHome = home),
                andyVersion = { "2026.0909.2356" },
            )
            val linked = service.link(root.absolutePath)
            assertEquals("examples.webhook-notify", linked.pluginId)
            assertEquals(1, linked.actions.size)
            assertEquals("pane.agent_status_changed", linked.events.single().on)
            service.unlink(linked.pluginId)
        } finally {
            scope.cancel()
            home.deleteRecursively()
        }
    }

    @Test
    fun attachAgentEventsSkipsHydratedChatsButEmitsNewOnes() = runBlocking {
        val home = File.createTempFile("andy-plugins-home", null).apply {
            delete()
            mkdirs()
        }
        val pluginRoot = File.createTempFile("andy-plugin-root", null).apply {
            delete()
            mkdirs()
        }
        File(pluginRoot, "andy-plugin.toml").writeText(
            """
            id = "examples.pane-events"
            name = "Pane Events"
            version = "0.1.0"
            min_andy_version = "0.1.0"
            platforms = ["macos", "linux", "windows"]

            [[events]]
            on = "pane.created"
            command = ["sh", "-c", "printf '%s\n' \"${'$'}ANDY_PLUGIN_EVENT\" >> \"${'$'}ANDY_PLUGIN_STATE_DIR/events\""]
            """.trimIndent(),
        )

        val hydrated = listOf(
            sampleTask("task-a", "Already open A"),
            sampleTask("task-b", "Already open B"),
            sampleTask("task-c", "Already open C"),
        )
        val tasksFlow = MutableStateFlow(emptyList<AgentTask>())
        val loaded = CompletableDeferred<Unit>()
        val agents = object : AgentRunService by UnavailableAgentRunService {
            override val tasks: StateFlow<List<AgentTask>> = tasksFlow
            override suspend fun awaitTasksLoaded() = loaded.await()
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val service = DesktopPluginService(
                scope = scope,
                actionRuns = RecordingActionRuns(),
                store = PluginRegistryStore(andyHome = home),
                andyVersion = { "2026.0909.2356" },
                andyBinPath = { "/tmp/andy" },
                andySocketPath = { "/tmp/andyd.sock" },
            )
            val linked = service.link(pluginRoot.absolutePath)
            assertEquals(1, linked.events.size)
            assertEquals("pane.created", linked.events.single().on)
            service.attachAgentEvents(agents)

            // Hydration: many existing chats appear at once after awaitTasksLoaded.
            tasksFlow.value = hydrated
            loaded.complete(Unit)
            delay(300)
            val eventsFile = File(linked.stateDir, "events")
            assertTrue(
                !eventsFile.exists() || eventsFile.readText().isBlank(),
                "hydration must not emit pane.created; got ${eventsFile.takeIf { it.exists() }?.readText()}",
            )

            // A genuinely new chat after baseline should emit once.
            tasksFlow.value = hydrated + sampleTask("task-d", "Brand new")
            waitForFile(eventsFile, timeoutMs = 5_000)
            val lines = eventsFile.readText().trim().lines().filter { it.isNotBlank() }
            assertEquals(listOf("pane.created"), lines)

            service.unlink(linked.pluginId)
        } finally {
            scope.cancel()
            home.deleteRecursively()
            pluginRoot.deleteRecursively()
        }
    }

    @Test
    fun attachAgentEventsEmitsPaneFocusedOnFocusChangeWithoutTaskUpdate() = runBlocking {
        val home = File.createTempFile("andy-plugins-home", null).apply {
            delete()
            mkdirs()
        }
        val pluginRoot = File.createTempFile("andy-plugin-root", null).apply {
            delete()
            mkdirs()
        }
        File(pluginRoot, "andy-plugin.toml").writeText(
            """
            id = "examples.pane-focus"
            name = "Pane Focus"
            version = "0.1.0"
            min_andy_version = "0.1.0"
            platforms = ["macos", "linux", "windows"]

[[events]]
            on = "pane.focused"
            command = ["sh", "-c", "printf '%s\n' \"${'$'}ANDY_PLUGIN_EVENT\" >> \"${'$'}ANDY_PLUGIN_STATE_DIR/events\""]
            """.trimIndent(),
        )

        val tasks = listOf(sampleTask("task-focus", "Focused chat"))
        val tasksFlow = MutableStateFlow(tasks)
        val loaded = CompletableDeferred<Unit>()
        val viewingFlow = MutableStateFlow<String?>(null)
        val agents = object : AgentRunService by UnavailableAgentRunService {
            override val tasks: StateFlow<List<AgentTask>> = tasksFlow
            override val viewingTaskId: StateFlow<String?> = viewingFlow
            override suspend fun awaitTasksLoaded() = loaded.await()
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val service = DesktopPluginService(
                scope = scope,
                actionRuns = RecordingActionRuns(),
                store = PluginRegistryStore(andyHome = home),
                andyVersion = { "2026.0909.2356" },
                andyBinPath = { "/tmp/andy" },
                andySocketPath = { "/tmp/andyd.sock" },
            )
            val linked = service.link(pluginRoot.absolutePath)
            service.attachAgentEvents(agents)
            loaded.complete(Unit)
            delay(100)

            val eventsFile = File(linked.stateDir, "events")
            // Focusing an already-read chat mutates viewing only — no task value is re-emitted.
            viewingFlow.value = "task-focus"
            waitForFile(eventsFile, timeoutMs = 5_000)
            val lines = eventsFile.readText().trim().lines().filter { it.isNotBlank() }
            assertEquals(listOf("pane.focused"), lines)

            service.unlink(linked.pluginId)
        } finally {
            scope.cancel()
            home.deleteRecursively()
            pluginRoot.deleteRecursively()
        }
    }

    @Test
    fun uninstallStopsRunningPluginPanes() = runBlocking {
        val home = File.createTempFile("andy-plugins-home", null).apply {
            delete()
            mkdirs()
        }
        val pluginRoot = File.createTempFile("andy-plugin-root", null).apply {
            delete()
            mkdirs()
        }
        File(pluginRoot, "andy-plugin.toml").writeText(
            """
            id = "examples.pane-stop"
            name = "Pane Stop"
            version = "0.1.0"
            min_andy_version = "0.1.0"
            platforms = ["macos", "linux", "windows"]

            [[panes]]
            id = "watch"
            title = "Watch"
            placement = "split"
            command = ["sh", "-c", "sleep 1000"]
            """.trimIndent(),
        )

        val actionRuns = RecordingActionRuns()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val service = DesktopPluginService(
                scope = scope,
                actionRuns = actionRuns,
                store = PluginRegistryStore(andyHome = home),
                andyVersion = { "2026.0909.2356" },
                andyBinPath = { "/tmp/andy" },
                andySocketPath = { "/tmp/andyd.sock" },
            )
            val linked = service.link(pluginRoot.absolutePath)
            val session = service.openPane(linked.pluginId, "watch")
            assertTrue(actionRuns.stopped.isEmpty())

            service.uninstall(linked.pluginId)
            assertEquals(listOf(session.runId), actionRuns.stopped)
            assertTrue(service.plugins.value.none { it.pluginId == linked.pluginId })
            assertTrue(service.openPanes.value.none { it.pluginId == linked.pluginId })
        } finally {
            scope.cancel()
            home.deleteRecursively()
            pluginRoot.deleteRecursively()
        }
    }

    private fun sampleTask(id: String, title: String): AgentTask = AgentTask(
        id = id,
        title = title,
        prompt = "prompt",
        agent = AgentKind.Cursor,
        status = AgentStatus.Done,
        statusConfident = true,
        createdAtMillis = 1L,
    )

    private fun waitForFile(file: File, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (file.isFile && file.length() > 0) return
            Thread.sleep(25)
        }
        error("Timed out waiting for ${file.absolutePath}")
    }

    private class RecordingActionRuns : ActionRunService {
        data class Started(
            val title: String,
            val argv: List<String>,
            val cwd: String,
            val env: Map<String, String>,
        )

        val started = mutableListOf<Started>()
        val stopped = mutableListOf<String>()
        private val _running = MutableStateFlow<List<RunningAction>>(emptyList())
        override val running: StateFlow<List<RunningAction>> = _running

        override fun openShell(project: ActionProject, cwdOverride: String?) = ""
        override fun run(project: ActionProject, action: ProjectAction, cwdOverride: String?) = ""
        override fun startCommand(
            title: String,
            argv: List<String>,
            cwd: String,
            env: Map<String, String>,
            projectId: String,
            actionId: String,
        ): String {
            started += Started(title, argv, cwd, env)
            val runId = "run-${started.size}"
            _running.value = _running.value + RunningAction(
                runId = runId,
                projectId = projectId,
                actionId = actionId,
                actionName = title,
                icon = "puzzle",
                command = argv.joinToString(" "),
                cwd = cwd,
                status = ActionRunStatus.Running,
                startedAtMillis = System.currentTimeMillis(),
            )
            return runId
        }

        override fun stop(runId: String) {
            stopped += runId
        }

        override fun clear(runId: String) = Unit
    }
}
