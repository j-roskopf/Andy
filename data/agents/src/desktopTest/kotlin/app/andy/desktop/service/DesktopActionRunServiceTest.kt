package app.andy.desktop.service

import app.andy.model.ActionProject
import app.andy.model.ActionRunStatus
import app.andy.model.ProjectAction
import app.andy.terminal.TerminalLaunchRequest
import app.andy.terminal.TerminalSessions
import app.andy.terminal.rust.RustTerminalBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopActionRunServiceTest {
    @Test
    fun opensAnEmptyInteractiveShellForAProject() = runBlocking {
        val service = DesktopActionRunService(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val project = ActionProject(
            id = "project",
            name = "Project",
            contextDir = createTempDirectory("andy-empty-shell").toString(),
        )
        val runId = service.openShell(project)

        try {
            assertEquals("Terminal", service.running.value.single().actionName)
            awaitRustTerminal(service, runId)
            service.writeToTerminal(runId, "echo ready\r")
            awaitTerminalText(service, runId, "ready")
        } finally {
            service.stop(runId)
            awaitRunFinished(service, runId)
        }
    }

    @Test
    fun keepsTheProjectShellOpenForAdditionalCommands() = runBlocking {
        val service = DesktopActionRunService(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val project = ActionProject(
            id = "project",
            name = "Project",
            contextDir = createTempDirectory("andy-action-shell").toString(),
        )
        val runId = service.run(
            project,
            ProjectAction(id = "run", name = "Run", command = "echo initial"),
        )

        try {
            awaitRustTerminal(service, runId)
            awaitTerminalText(service, runId, "initial")
            assertEquals(ActionRunStatus.Running, service.running.value.single().status)

            service.writeToTerminal(runId, "echo typed\r")
            awaitTerminalText(service, runId, "typed")

            service.writeToTerminal(runId, "exit\r")
            withTimeout(15_000) {
                while (service.running.value.single().status == ActionRunStatus.Running) delay(25)
            }
            assertEquals(ActionRunStatus.Exited, service.running.value.single().status)
        } finally {
            service.stop(runId)
            awaitRunFinished(service, runId)
        }
    }

    @Test
    fun rerunningAnActionAppendsToTheExistingShell() = runBlocking {
        val service = DesktopActionRunService(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val project = ActionProject(
            id = "project",
            name = "Project",
            contextDir = createTempDirectory("andy-rerun-action").toString(),
        )
        val firstAction = ProjectAction(id = "run", name = "Run", command = "echo first")
        val secondAction = firstAction.copy(command = "echo second")

        val runId = service.run(project, firstAction)
        try {
            awaitTerminalText(service, runId, "first")
            assertEquals(ActionRunStatus.Running, service.running.value.single().status)

            val secondRunId = service.run(project, secondAction)
            assertEquals(runId, secondRunId)
            assertEquals(listOf(runId), service.running.value.map { it.runId })
            awaitTerminalText(service, runId, "second")
            assertTrue(service.bufferSnapshot(runId).contains("first"))
        } finally {
            service.stop(runId)
            awaitRunFinished(service, runId)
        }
    }

    private suspend fun awaitRustTerminal(service: DesktopActionRunService, runId: String) {
        withTimeout(5_000) {
            while (service.rustTerminal(runId) == null) delay(25)
        }
        assertNotNull(service.rustTerminal(runId))
    }

    private suspend fun awaitTerminalText(service: DesktopActionRunService, runId: String, text: String) {
        withTimeout(5_000) {
            while (!service.bufferSnapshot(runId).contains(text)) delay(25)
        }
        assertTrue(service.bufferSnapshot(runId).contains(text))
    }

    /** stop() is async; wait until the run and PTY backend are fully torn down so the next test does not race native spawn. */
    private suspend fun awaitRunFinished(service: DesktopActionRunService, runId: String) {
        withTimeout(15_000) {
            while (service.running.value.any {
                it.runId == runId && (it.status == ActionRunStatus.Starting || it.status == ActionRunStatus.Running)
            }) {
                delay(25)
            }
        }
        withTimeout(15_000) {
            while (true) {
                val terminal = service.rustTerminal(runId)
                if (terminal == null) break
                val code = terminal.exitCode.value
                if (code != null && !terminal.isAlive) break
                delay(25)
            }
        }
    }

    @Test
    fun stoppingWhileSpawningNeverPublishesTheBackendOrRunsTheCommand() = runBlocking {
        val spawnGate = CountDownLatch(1)
        val spawned = AtomicReference<RustTerminalBackend?>()
        val service = gatedService(spawnGate, spawned)
        val project = ActionProject(
            id = "project",
            name = "Project",
            contextDir = createTempDirectory("andy-stop-while-spawning").toString(),
        )
        val runId = service.run(
            project,
            ProjectAction(id = "run", name = "Run", command = "echo must-not-run"),
        )

        service.stop(runId)
        // stop() ran while the PTY was still spawning: the run must settle Stopped and
        // must never transition to Running or register a live backend afterwards.
        assertEquals(
            ActionRunStatus.Stopped,
            service.running.value.firstOrNull { it.runId == runId }?.status,
        )
        spawnGate.countDown()

        awaitBackendClosed(spawned)
        assertEquals(ActionRunStatus.Stopped, service.running.value.single().status)
        assertNull(service.rustTerminal(runId))
        assertTrue(service.bufferSnapshot(runId).isEmpty())
    }

    @Test
    fun clearingWhileSpawningNeverPublishesTheBackend() = runBlocking {
        val spawnGate = CountDownLatch(1)
        val spawned = AtomicReference<RustTerminalBackend?>()
        val service = gatedService(spawnGate, spawned)
        val project = ActionProject(
            id = "project",
            name = "Project",
            contextDir = createTempDirectory("andy-clear-while-spawning").toString(),
        )
        val runId = service.run(
            project,
            ProjectAction(id = "run", name = "Run", command = "echo must-not-run"),
        )

        service.clear(runId)
        // clear() ran while the PTY was still spawning: the run must be gone and no
        // live backend may appear in its place once the spawn finishes.
        assertEquals(emptyList(), service.running.value)
        spawnGate.countDown()

        awaitBackendClosed(spawned)
        assertEquals(emptyList(), service.running.value)
        assertNull(service.rustTerminal(runId))
    }

    private fun gatedService(
        spawnGate: CountDownLatch,
        spawned: AtomicReference<RustTerminalBackend?>,
    ): DesktopActionRunService = DesktopActionRunService(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        spawnSession = { runId, argv, cwd, env ->
            spawnGate.await()
            val backend = TerminalSessions.create(
                TerminalLaunchRequest(
                    sessionId = runId,
                    argv = argv,
                    cwd = cwd,
                    env = env,
                ),
            ) as RustTerminalBackend
            spawned.set(backend)
            backend
        },
    )

    private suspend fun awaitBackendClosed(spawned: AtomicReference<RustTerminalBackend?>) {
        withTimeout(5_000) {
            while (spawned.get()?.exitCode?.value == null) delay(25)
        }
        assertTrue(spawned.get()?.exitCode?.value != null)
    }
}
