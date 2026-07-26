package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File

class AgentSessionRecoveryTest {
    @Test
    fun terminalBufferLooksReadyForInputRequiresTrailingExactPrompt() {
        assertTrue(terminalBufferLooksReadyForInput("Implemented the fix.\n> "))
        assertTrue(terminalBufferLooksReadyForInput("All done.\n❯ "))
        assertTrue(terminalBufferLooksReadyForInput("Ready.\nclaude> "))
        assertTrue(isExactPromptLine(">"))
        assertTrue(isExactPromptLine("agy>"))

        // Generics / HTML while streaming must not look like a prompt.
        assertFalse(terminalBufferLooksReadyForInput("fun ids(): List<String>\n"))
        assertFalse(terminalBufferLooksReadyForInput("return Optional<Foo>\n"))
        assertFalse(isExactPromptLine("List<String>"))
        assertFalse(isExactPromptLine("</div>"))

        // Leftover prompt higher in scrollback does not count — only the bottom lines.
        assertFalse(
            terminalBufferLooksReadyForInput(
                ">\nThinking about the change…\nreading AgentStatusTracker.kt\nfun ids(): List<String>\n",
            ),
        )
    }

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
                    liveSessionStatus = AgentStatus.Done,
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
    fun inferWorkflowBuildTurnCompleteRequiresWorkingThenIdleAtPrompt() {
        val artifactDir = File.createTempFile("andy-artifacts", null).also { it.delete(); it.mkdirs() }
        try {
            val scrollback = "Applied edits and ran ./gradlew test.\n\n> "
            assertFalse(
                inferWorkflowBuildTurnComplete(
                    agent = AgentKind.Cursor,
                    artifactDir = artifactDir,
                    scrollback = scrollback,
                    liveSessionStatus = AgentStatus.Done,
                    sawWorking = false,
                ),
            )
            assertTrue(
                inferWorkflowBuildTurnComplete(
                    agent = AgentKind.Cursor,
                    artifactDir = artifactDir,
                    scrollback = scrollback,
                    liveSessionStatus = AgentStatus.Done,
                    sawWorking = true,
                ),
            )
            assertFalse(
                inferWorkflowBuildTurnComplete(
                    agent = AgentKind.Cursor,
                    artifactDir = artifactDir,
                    scrollback = scrollback,
                    liveSessionStatus = AgentStatus.Working,
                    sawWorking = true,
                ),
            )
            File(artifactDir, "status.json").writeText("""{"status":"done","at":1}""" + "\n")
            assertTrue(
                inferWorkflowBuildTurnComplete(
                    agent = AgentKind.ClaudeCode,
                    artifactDir = artifactDir,
                    scrollback = "done\n> ",
                    liveSessionStatus = AgentStatus.Done,
                    sawWorking = false,
                ),
            )
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
                    liveSessionStatus = AgentStatus.Working,
                ),
            )
            assertFalse(
                inferPausedAtPrompt(
                    agent = AgentKind.ClaudeCode,
                    artifactDir = artifactDir,
                    scrollback = scrollback,
                    liveSessionStatus = AgentStatus.Done,
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
            val store = DesktopAgentTaskStore(File(root, "agents.db"))
            val task = AgentTask(
                id = "task-done",
                title = "t",
                prompt = "p",
                agent = AgentKind.ClaudeCode,
                cwd = root.absolutePath,
                originDir = root.absolutePath,
                status = AgentStatus.Working,
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
            assertEquals(AgentStatus.Done, recovered.status)
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
            val store = DesktopAgentTaskStore(File(root, "agents.db"))
            val task = AgentTask(
                id = "task-paused-done",
                title = "t",
                prompt = "p",
                agent = AgentKind.ClaudeCode,
                cwd = root.absolutePath,
                originDir = root.absolutePath,
                status = AgentStatus.Done,
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
            assertEquals(AgentStatus.Done, recovered.status)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recoverInterruptedTaskStatusMapsRunningToPausedWhenIdleEvidenceExists() {
        val root = File.createTempFile("andy-store", null).also { it.delete(); it.mkdirs() }
        try {
            val store = DesktopAgentTaskStore(File(root, "agents.db"))
            val task = AgentTask(
                id = "task-idle",
                title = "t",
                prompt = "p",
                agent = AgentKind.ClaudeCode,
                cwd = root.absolutePath,
                originDir = root.absolutePath,
                status = AgentStatus.Working,
                createdAtMillis = 1,
                startedAtMillis = 2,
            )
            val scrollback = store.scrollbackFile(task.id)
            scrollback.parentFile?.mkdirs()
            scrollback.writeText("Done.\n> ")

            val recovered = recoverInterruptedTaskStatus(task, scrollback)
            assertEquals(AgentStatus.Done, recovered.status)
            assertTrue(recovered.finishedAtMillis != null)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recoverInterruptedTaskStatusMapsRunningToUnknownWhenMidTurn() {
        val root = File.createTempFile("andy-store", null).also { it.delete(); it.mkdirs() }
        try {
            val store = DesktopAgentTaskStore(File(root, "agents.db"))
            val task = AgentTask(
                id = "task-busy",
                title = "t",
                prompt = "p",
                agent = AgentKind.ClaudeCode,
                cwd = root.absolutePath,
                originDir = root.absolutePath,
                status = AgentStatus.Working,
                createdAtMillis = 1,
                startedAtMillis = 2,
            )
            val scrollback = store.scrollbackFile(task.id)
            scrollback.parentFile?.mkdirs()
            scrollback.writeText("Let me read the file and")

            val recovered = recoverInterruptedTaskStatus(task, scrollback)
            assertEquals(AgentStatus.Error, recovered.status)
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
            status = AgentStatus.Blocked,
            createdAtMillis = 1,
            finishedAtMillis = 2,
        )
        val recovered = recoverInterruptedTaskStatus(task, File("/tmp/missing-scrollback"))
        assertEquals(AgentStatus.Blocked, recovered.status)
    }
}
