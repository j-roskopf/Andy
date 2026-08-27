package app.andy.desktop.service.agents

import app.andy.desktop.service.DesktopActionConfigStore
import app.andy.desktop.service.DesktopWorkspaceStore
import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.model.WorkspaceState
import app.andy.service.UnavailableMcpService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopAgentRetentionServiceTest {
    private val dayMillis = 24L * 60L * 60L * 1_000L

    private fun task(
        id: String,
        finishedAtMillis: Long,
        archived: Boolean = false,
        transcriptCompressed: Boolean = false,
    ) = AgentTask(
        id = id,
        title = id,
        prompt = "prompt",
        agent = AgentKind.Codex,
        status = AgentStatus.Done,
        createdAtMillis = finishedAtMillis,
        finishedAtMillis = finishedAtMillis,
        archived = archived,
        transcriptCompressed = transcriptCompressed,
    )

    private fun withRuntime(
        tasks: List<AgentTask>,
        workspace: WorkspaceState,
        block: suspend (DesktopAgentTaskStore, DesktopAgentRetentionService) -> Unit,
    ) = runBlocking {
        val root = Files.createTempDirectory("andy-retention").toFile()
        val store = DesktopAgentTaskStore(
            databaseFile = File(root, "agents.db"),
            transcriptsDir = File(root, "agents"),
        )
        val workspaceStore = DesktopWorkspaceStore(File(root, "workspace.properties"))
        val actionConfig = DesktopActionConfigStore(file = File(root, "actions.toml"))
        val runScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val retentionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            store.save(AgentStoreState(tasks = tasks))
            tasks.filterNot { it.transcriptCompressed }.forEach { task ->
                store.scrollbackFile(task.id).apply {
                    parentFile.mkdirs()
                    writeText("seed")
                }
            }
            workspaceStore.save(workspace)
            val runService = DesktopAgentRunService(
                scope = runScope,
                store = store,
                locator = AgentCliLocator(),
                adapters = emptyMap(),
                worktrees = WorktreeManager(),
                mcp = UnavailableMcpService,
                workspaceStore = workspaceStore,
                actionConfig = actionConfig,
                enableProbes = false,
            )
            runService.awaitReady()
            val retention = DesktopAgentRetentionService(
                runService = runService,
                store = store,
                actionConfigStore = actionConfig,
                workspace = workspaceStore.state,
                scope = retentionScope,
            )
            block(store, retention)
        } finally {
            retentionScope.cancel()
            runScope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun compressesTranscriptDirectoryIntoReadableArchive() = withRuntime(
        tasks = listOf(task("compress-me", System.currentTimeMillis() - 31 * dayMillis)),
        workspace = WorkspaceState(retentionCompressArchiveAfterDays = 30, retentionPermanentDeleteAfterDays = 90),
    ) { store, retention ->
        val dir = store.taskDir("compress-me").also { it.mkdirs() }
        dir.resolve("scrollback.ansi").writeText("scrollback")
        dir.resolve("transcript.jsonl").writeText("transcript")

        val result = retention.runSweepNow()

        assertEquals(1, result.chatsCompressedArchived)
        assertFalse(dir.resolve("scrollback.ansi").exists())
        assertFalse(dir.resolve("transcript.jsonl").exists())
        assertTrue(store.archiveFile("compress-me").isFile)
        ZipFile(store.archiveFile("compress-me")).use { zip ->
            val scrollback = assertNotNull(zip.getEntry("scrollback.ansi"))
            val transcript = assertNotNull(zip.getEntry("transcript.jsonl"))
            assertEquals("scrollback", zip.getInputStream(scrollback).bufferedReader().readText())
            assertEquals("transcript", zip.getInputStream(transcript).bufferedReader().readText())
        }
        assertTrue(store.load().tasks.single().transcriptCompressed)
        assertTrue(store.resolvedContentDir("compress-me", compressed = true).resolve("scrollback.ansi").isFile)
    }

    @Test
    fun permanentlyDeletesOnlyRetentionArchives() = withRuntime(
        tasks = listOf(task("delete-me", System.currentTimeMillis() - 91 * dayMillis, archived = true, transcriptCompressed = true)),
        workspace = WorkspaceState(retentionCompressArchiveAfterDays = 30, retentionPermanentDeleteAfterDays = 90),
    ) { store, retention ->
        store.archiveFile("delete-me").apply {
            parentFile.mkdirs()
            writeText("archive")
        }

        val result = retention.runSweepNow()

        assertEquals(1, result.chatsPermanentlyDeleted)
        assertFalse(store.taskDir("delete-me").exists())
        assertTrue(store.load().tasks.isEmpty())
    }

    @Test
    fun sweepsOldProjectFoldersButKeepsUnreadManualAndControlFiles() {
        val projectRoot = Files.createTempDirectory("andy-retention-project").toFile()
        try {
            val old = System.currentTimeMillis() - 31 * dayMillis
            val tasks = listOf(
                task("project-old", old).copy(originDir = projectRoot.absolutePath),
                task("project-unread", old).copy(originDir = projectRoot.absolutePath, unread = true),
                task("project-manual", old, archived = true).copy(originDir = projectRoot.absolutePath),
            )
            withRuntime(
                tasks = tasks,
                workspace = WorkspaceState(retentionCompressArchiveAfterDays = 30, retentionPermanentDeleteAfterDays = 90),
            ) { _, retention ->
                val andyDir = projectRoot.resolve(".andy").also { it.mkdirs() }
                listOf("project-old", "project-unread", "project-manual", "orphan-old", "orphan-fresh").forEach { id ->
                    andyDir.resolve(id).mkdirs()
                    andyDir.resolve(id).resolve("status.json").writeText(id)
                }
                andyDir.resolve("orphan-old").setLastModified(old)
                andyDir.resolve("active-task").writeText("keep")
                andyDir.resolve("actions.toml").writeText("keep")

                val result = retention.runSweepNow()

                assertEquals(2, result.projectLocalFoldersDeleted)
                assertFalse(andyDir.resolve("project-old").exists())
                assertFalse(andyDir.resolve("orphan-old").exists())
                assertTrue(andyDir.resolve("project-unread").exists())
                assertTrue(andyDir.resolve("project-manual").exists())
                assertTrue(andyDir.resolve("orphan-fresh").exists())
                assertTrue(andyDir.resolve("active-task").isFile)
                assertTrue(andyDir.resolve("actions.toml").isFile)
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }
}
