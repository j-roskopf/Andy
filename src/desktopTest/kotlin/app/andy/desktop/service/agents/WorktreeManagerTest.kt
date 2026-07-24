package app.andy.desktop.service.agents

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorktreeManagerTest {
    @Test
    fun changeSummaryExcludesUntouchedPreexistingChangesButShowsFurtherEdits() {
        val repo = File.createTempFile("andy-change-summary", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            git(repo, "init")
            git(repo, "config", "user.email", "andy@example.test")
            git(repo, "config", "user.name", "Andy Test")
            File(repo, "clean.kt").writeText("one\n")
            File(repo, "already-dirty.kt").writeText("base\n")
            File(repo, "untouched-dirty.kt").writeText("base\n")
            git(repo, "add", ".")
            git(repo, "commit", "-m", "initial")

            File(repo, "already-dirty.kt").writeText("user edit\n")
            File(repo, "untouched-dirty.kt").writeText("user edit\n")
            File(repo, "existing-untracked.txt").writeText("user file\n")
            File(repo, "untouched-untracked.txt").writeText("user file\n")
            val manager = WorktreeManager(File(repo, "worktrees"))
            val baseline = assertNotNull(manager.captureChangeBaseline(repo.absolutePath))

            // Further edits made during the task, layered on top of pre-existing dirty state.
            File(repo, "clean.kt").writeText("one\ntwo\n")
            File(repo, "already-dirty.kt").writeText("user edit\nagent edit\n")
            File(repo, "existing-untracked.txt").writeText("user file\nagent edit\n")
            File(repo, "agent-created.kt").writeText("first\nsecond\n")
            // untouched-dirty.kt and untouched-untracked.txt are left exactly as they were at baseline.

            val summary = assertNotNull(manager.changeSummary(repo.absolutePath, baseline))
            assertEquals(
                listOf("agent-created.kt", "already-dirty.kt", "clean.kt", "existing-untracked.txt"),
                summary.files.map { it.path },
            )
            assertTrue(summary.files.none { it.path == "untouched-dirty.kt" || it.path == "untouched-untracked.txt" })

            val cleanDiff = assertNotNull(manager.fileDiff(repo.absolutePath, "clean.kt", baseline))
            assertEquals(1, cleanDiff.additions)
            assertTrue(cleanDiff.lines.any { it.text == "two" })

            val createdDiff = assertNotNull(manager.fileDiff(repo.absolutePath, "agent-created.kt", baseline))
            assertTrue(createdDiff.isNewFile)
            assertEquals(2, createdDiff.additions)
            assertEquals(listOf("first", "second"), createdDiff.lines.map { it.text })

            // Files dirty at baseline but edited further show only the incremental change, not the whole file.
            val alreadyDirtyDiff = assertNotNull(manager.fileDiff(repo.absolutePath, "already-dirty.kt", baseline))
            assertFalse(alreadyDirtyDiff.isNewFile)
            assertEquals(listOf("agent edit"), alreadyDirtyDiff.lines.filter { it.text != "user edit" }.map { it.text })

            val existingUntrackedDiff =
                assertNotNull(manager.fileDiff(repo.absolutePath, "existing-untracked.txt", baseline))
            assertFalse(existingUntrackedDiff.isNewFile)
            assertEquals(1, existingUntrackedDiff.additions)

            val snapshot = assertNotNull(manager.changeSnapshot(repo.absolutePath, baseline))
            File(repo, "later-user-edit.kt").writeText("not from this chat\n")
            File(repo, "clean.kt").appendText("later user edit\n")
            assertEquals(
                listOf("agent-created.kt", "already-dirty.kt", "clean.kt", "existing-untracked.txt"),
                snapshot.summary.files.map { it.path },
            )
            assertTrue(snapshot.diffs.keys.none { it == "later-user-edit.kt" })
            assertTrue(snapshot.diffs.getValue("clean.kt").lines.none { it.text == "later user edit" })
        } finally {
            repo.deleteRecursively()
        }
    }

    private fun git(dir: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", dir.absolutePath) + args).start()
        assertEquals(0, process.waitFor(), process.errorStream.bufferedReader().readText())
    }
}
