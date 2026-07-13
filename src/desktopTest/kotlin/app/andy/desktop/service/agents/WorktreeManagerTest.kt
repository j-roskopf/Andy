package app.andy.desktop.service.agents

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorktreeManagerTest {
    @Test
    fun changeSummaryExcludesChangesThatPredateTheTask() {
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
            git(repo, "add", ".")
            git(repo, "commit", "-m", "initial")

            File(repo, "already-dirty.kt").writeText("user edit\n")
            File(repo, "existing-untracked.txt").writeText("user file\n")
            val manager = WorktreeManager(File(repo, "worktrees"))
            val baseline = assertNotNull(manager.captureChangeBaseline(repo.absolutePath))

            File(repo, "clean.kt").writeText("one\ntwo\n")
            File(repo, "already-dirty.kt").writeText("user edit\nagent edit\n")
            File(repo, "existing-untracked.txt").writeText("user file\nagent edit\n")
            File(repo, "agent-created.kt").writeText("first\nsecond\n")

            val summary = assertNotNull(manager.changeSummary(repo.absolutePath, baseline))
            assertEquals(listOf("agent-created.kt", "clean.kt"), summary.files.map { it.path })
            assertEquals(3, summary.additions)
            assertEquals(0, summary.deletions)
            assertTrue(summary.files.none { it.path == "already-dirty.kt" || it.path == "existing-untracked.txt" })

            val cleanDiff = assertNotNull(manager.fileDiff(repo.absolutePath, "clean.kt"))
            assertEquals(1, cleanDiff.additions)
            assertTrue(cleanDiff.lines.any { it.text == "two" })

            val createdDiff = assertNotNull(manager.fileDiff(repo.absolutePath, "agent-created.kt"))
            assertTrue(createdDiff.isNewFile)
            assertEquals(2, createdDiff.additions)
            assertEquals(listOf("first", "second"), createdDiff.lines.map { it.text })

            val snapshot = assertNotNull(manager.changeSnapshot(repo.absolutePath, baseline))
            File(repo, "later-user-edit.kt").writeText("not from this chat\n")
            File(repo, "clean.kt").appendText("later user edit\n")
            assertEquals(listOf("agent-created.kt", "clean.kt"), snapshot.summary.files.map { it.path })
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
