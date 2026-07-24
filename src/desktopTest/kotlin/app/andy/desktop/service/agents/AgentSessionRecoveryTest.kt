package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentSessionStatus
import app.andy.model.AgentTask
import app.andy.model.AgentTaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File

class AgentSessionRecoveryTest {
    @Test
    fun inferCompletedTurnUsesHookDoneAndLiveSessionDone() {
        val artifactDir = File.createTempFile("andy-artifacts", null).also { it.delete(); it.mkdirs() }
        try {
            File(artifactDir, "status.json").writeText("""{"status":"done","at":1}""" + "\n")
            assertTrue(
                inferCompletedTurn(
                    agent = AgentKind.ClaudeCode,
                    artifactDir = artifactDir,
                    scrollback = "Weather is 72F and sunny.\n> ",
                ),
            )
            assertTrue(
                inferCompletedTurn(
                    agent = AgentKind.ClaudeCode,
                    artifactDir = artifactDir,
                    scrollback = "",
                    liveSessionStatus = AgentSessionStatus.Done,
                ),
            )
            val noHookDir = File.createTempFile("andy-artifacts-empty", null).also { it.delete(); it.mkdirs() }
            assertFalse(
                inferCompletedTurn(
                    agent = AgentKind.ClaudeCode,
                    artifactDir = noHookDir,
                    scrollback = "Still thinking…",
                ),
            )
            noHookDir.deleteRecursively()
        } finally {
            artifactDir.deleteRecursively()
        }
    }

    @Test
    fun inferPausedAtPromptUsesLiveIdleOverStaleHookDone() {
        val artifactDir = File.createTempFile("andy-artifacts", null).also { it.delete(); it.mkdirs() }
        try {
            File(artifactDir, "status.json").writeText("""{"status":"done","at":1}""" + "\n")
            val scrollback = "All done.\n> "
            assertFalse(
                inferPausedAtPrompt(
                    agent = AgentKind.ClaudeCode,
                    artifactDir = artifactDir,
                    scrollback = scrollback,
                    liveSessionStatus = AgentSessionStatus.Working,
                ),
            )
            assertFalse(
                inferPausedAtPrompt(
                    agent = AgentKind.ClaudeCode,
                    artifactDir = artifactDir,
                    scrollback = scrollback,
                    liveSessionStatus = AgentSessionStatus.Idle,
                ),
            )
        } finally {
            artifactDir.deleteRecursively()
        }
    }

    @Test
    fun inferPausedAtPromptOnReloadRequiresPromptWithoutDoneHook() {
        val artifactDir = File.createTempFile("andy-artifacts", null).also { it.delete(); it.mkdirs() }
        try {
            assertFalse(
                inferPausedAtPrompt(
                    agent = AgentKind.ClaudeCode,
                    artifactDir = artifactDir,
                    scrollback = "Still thinking about your request…",
                ),
            )
            assertTrue(
                inferPausedAtPrompt(
                    agent = AgentKind.ClaudeCode,
                    artifactDir = artifactDir,
                    scrollback = "Here is the answer.\n> ",
                ),
            )
            File(artifactDir, "status.json").writeText("""{"status":"done","at":1}""" + "\n")
            assertFalse(
                inferPausedAtPrompt(
                    agent = AgentKind.ClaudeCode,
                    artifactDir = artifactDir,
                    scrollback = "Here is the answer.\n> ",
                ),
            )
        } finally {
            artifactDir.deleteRecursively()
        }
    }

    @Test
    fun recoverInterruptedTaskStatusMapsRunningToCompletedWhenHookDone() {
        val root = File.createTempFile("andy-store", null).also { it.delete(); it.mkdirs() }
        try {
            val store = DesktopAgentTaskStore(File(root, "agents.toml"))
            val task = AgentTask(
                id = "task-done",
                title = "t",
                prompt = "p",
                agent = AgentKind.ClaudeCode,
                cwd = root.absolutePath,
                originDir = root.absolutePath,
                status = AgentTaskStatus.Running,
                createdAtMillis = 1,
                startedAtMillis = 2,
            )
            val scrollback = store.scrollbackFile(task.id)
            scrollback.parentFile?.mkdirs()
            scrollback.writeText("72F and sunny.\n> ")
            File(AgentWorkflowArtifacts.dirFor(root, task.id), "status.json")
                .apply { parentFile.mkdirs() }
                .writeText("""{"status":"done","at":1}""" + "\n")

            val recovered = recoverInterruptedTaskStatus(task, scrollback)
            assertEquals(AgentTaskStatus.Completed, recovered.status)
            assertEquals(0, recovered.exitCode)
            assertTrue(recovered.finishedAtMillis != null)
            assertTrue(recovered.unread)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recoverInterruptedTaskStatusUpgradesPausedToCompletedWhenHookDone() {
        val root = File.createTempFile("andy-store", null).also { it.delete(); it.mkdirs() }
        try {
            val store = DesktopAgentTaskStore(File(root, "agents.toml"))
            val task = AgentTask(
                id = "task-paused-done",
                title = "t",
                prompt = "p",
                agent = AgentKind.ClaudeCode,
                cwd = root.absolutePath,
                originDir = root.absolutePath,
                status = AgentTaskStatus.Paused,
                createdAtMillis = 1,
                startedAtMillis = 2,
                finishedAtMillis = 3,
            )
            val scrollback = store.scrollbackFile(task.id)
            scrollback.parentFile?.mkdirs()
            scrollback.writeText("72F and sunny.\n> ")
            File(AgentWorkflowArtifacts.dirFor(root, task.id), "status.json")
                .apply { parentFile.mkdirs() }
                .writeText("""{"status":"done","at":1}""" + "\n")

            val recovered = recoverInterruptedTaskStatus(task, scrollback)
            assertEquals(AgentTaskStatus.Completed, recovered.status)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recoverInterruptedTaskStatusMapsRunningToPausedWhenIdleEvidenceExists() {
        val root = File.createTempFile("andy-store", null).also { it.delete(); it.mkdirs() }
        try {
            val store = DesktopAgentTaskStore(File(root, "agents.toml"))
            val task = AgentTask(
                id = "task-idle",
                title = "t",
                prompt = "p",
                agent = AgentKind.ClaudeCode,
                cwd = root.absolutePath,
                originDir = root.absolutePath,
                status = AgentTaskStatus.Running,
                createdAtMillis = 1,
                startedAtMillis = 2,
            )
            val scrollback = store.scrollbackFile(task.id)
            scrollback.parentFile?.mkdirs()
            scrollback.writeText("Done.\n> ")

            val recovered = recoverInterruptedTaskStatus(task, scrollback)
            assertEquals(AgentTaskStatus.Paused, recovered.status)
            assertTrue(recovered.finishedAtMillis != null)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recoverInterruptedTaskStatusMapsRunningToUnknownWhenMidTurn() {
        val root = File.createTempFile("andy-store", null).also { it.delete(); it.mkdirs() }
        try {
            val store = DesktopAgentTaskStore(File(root, "agents.toml"))
            val task = AgentTask(
                id = "task-busy",
                title = "t",
                prompt = "p",
                agent = AgentKind.ClaudeCode,
                cwd = root.absolutePath,
                originDir = root.absolutePath,
                status = AgentTaskStatus.Running,
                createdAtMillis = 1,
                startedAtMillis = 2,
            )
            val scrollback = store.scrollbackFile(task.id)
            scrollback.parentFile?.mkdirs()
            scrollback.writeText("Let me read the file and")

            val recovered = recoverInterruptedTaskStatus(task, scrollback)
            assertEquals(AgentTaskStatus.Unknown, recovered.status)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recoverInterruptedTaskStatusPreservesWaitingForInput() {
        val task = AgentTask(
            id = "task-wait",
            title = "t",
            prompt = "p",
            agent = AgentKind.Codex,
            cwd = "/tmp",
            originDir = "/tmp",
            status = AgentTaskStatus.WaitingForInput,
            createdAtMillis = 1,
            finishedAtMillis = 2,
        )
        val recovered = recoverInterruptedTaskStatus(task, File("/tmp/missing-scrollback"))
        assertEquals(AgentTaskStatus.WaitingForInput, recovered.status)
    }
}
