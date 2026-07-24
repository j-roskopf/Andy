package app.andy.desktop.service

import app.andy.model.ActionProject
import app.andy.model.ActionRunStatus
import app.andy.model.ProjectAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
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
            assertNotNull(service.terminalWidget(runId))
            service.writeToTerminal(runId, "echo ready\r")
            awaitTerminalText(service, runId, "ready")
        } finally {
            service.stop(runId)
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
            assertNotNull(service.terminalWidget(runId))
            awaitTerminalText(service, runId, "initial")
            assertEquals(ActionRunStatus.Running, service.running.value.single().status)

            service.writeToTerminal(runId, "echo typed\r")
            awaitTerminalText(service, runId, "typed")

            service.writeToTerminal(runId, "exit\r")
            withTimeout(5_000) {
                while (service.running.value.single().status == ActionRunStatus.Running) delay(25)
            }
            assertEquals(ActionRunStatus.Exited, service.running.value.single().status)
        } finally {
            service.stop(runId)
        }
    }

    private suspend fun awaitTerminalText(service: DesktopActionRunService, runId: String, text: String) {
        withTimeout(5_000) {
            while (!service.bufferSnapshot(runId).contains(text)) delay(25)
        }
        assertTrue(service.bufferSnapshot(runId).contains(text))
    }
}
