package app.andy.desktop.service.agents

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemporaryChatArtifactsTest {
    private fun tempParent(): File = File.createTempFile("andy-temp-parent", null).also {
        it.delete()
        it.mkdirs()
    }

    @Test
    fun dirForIsStablePerTaskAndDistinctBetweenTasks() {
        val parent = tempParent()
        try {
            val artifacts = TemporaryChatArtifacts(parent)
            val first = artifacts.dirFor("task-1")
            assertEquals(first, artifacts.dirFor("task-1"))
            assertNotEquals(first, artifacts.dirFor("task-2"))
            assertTrue(first.isDirectory)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun discardRemovesTheDirectoryAndItsContents() {
        val parent = tempParent()
        try {
            val artifacts = TemporaryChatArtifacts(parent)
            val dir = artifacts.dirFor("task-1")
            File(dir, "scrollback.ansi").writeText("secret output")

            artifacts.discard("task-1")

            assertFalse(dir.exists())
            // A later request allocates a fresh, empty directory rather than resurrecting it.
            assertEquals(0, artifacts.dirFor("task-1").listFiles()?.size ?: 0)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun releaseHandsTheDirectoryOverWithoutDeletingIt() {
        val parent = tempParent()
        try {
            val artifacts = TemporaryChatArtifacts(parent)
            val dir = artifacts.dirFor("task-1")
            File(dir, "scrollback.ansi").writeText("kept output")

            val released = artifacts.release("task-1")

            assertEquals(dir, released)
            assertTrue(dir.isDirectory, "promotion moves these files; release must not delete them")
            assertNull(artifacts.release("task-1"), "a released task is no longer routed here")
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun discardAllClearsEveryLiveChat() {
        val parent = tempParent()
        try {
            val artifacts = TemporaryChatArtifacts(parent)
            val dirs = listOf("a", "b", "c").map { artifacts.dirFor(it) }
            dirs.forEach { File(it, "scrollback.ansi").writeText("output") }

            artifacts.discardAll()

            assertTrue(dirs.none { it.exists() })
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun sweepOrphansRemovesStaleRootsAndSparesLiveOnes() {
        val parent = tempParent()
        try {
            val live = TemporaryChatArtifacts(parent)
            val liveDir = live.dirFor("live-task")
            val stale = File(parent, "andy-temp-chats-crashed").apply { mkdirs() }
            File(stale, "scrollback.ansi").writeText("left by a crash")
            val twoDaysAgo = System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000
            stale.setLastModified(twoDaysAgo)
            val unrelated = File(parent, "some-other-tool").apply { mkdirs() }
            unrelated.setLastModified(twoDaysAgo)

            val swept = TemporaryChatArtifacts.sweepOrphans(parent)

            assertEquals(1, swept)
            assertFalse(stale.exists())
            assertTrue(liveDir.isDirectory, "a concurrently running instance keeps its temp chats")
            assertTrue(unrelated.exists(), "only Andy's own roots are candidates")
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun sweepOrphansAlsoDeletesRememberedWorkflowDirs() {
        val parent = tempParent()
        val project = tempParent()
        try {
            val staleRoot = File(parent, "andy-temp-chats-crashed").apply { mkdirs() }
            val taskDir = File(staleRoot, "task-1").apply { mkdirs() }
            val workflowDir = File(project, ".andy/task-1").apply { mkdirs() }
            File(workflowDir, "status.json").writeText("{}")
            File(taskDir, TemporaryChatArtifacts.WORKFLOW_DIR_MARKER).writeText(workflowDir.absolutePath)
            val twoDaysAgo = System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000
            staleRoot.setLastModified(twoDaysAgo)

            val swept = TemporaryChatArtifacts.sweepOrphans(parent)

            assertEquals(1, swept)
            assertFalse(staleRoot.exists())
            assertFalse(workflowDir.exists(), "project-local .andy/<taskId> must not survive a crash sweep")
        } finally {
            parent.deleteRecursively()
            project.deleteRecursively()
        }
    }
}
