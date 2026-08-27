package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentThreadChangeSnapshot
import app.andy.model.WorktreeMergeOutcome
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorktreeManagerTest {
    @Test
    fun createWithStartPointForksFromThatRefNotOriginHead() {
        val repo = File.createTempFile("andy-wt-startpoint", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            initTestRepo(repo)
            // Some git installs still default new repos to master.
            git(repo, "checkout", "-B", "main")
            File(repo, "root.txt").writeText("root\n")
            git(repo, "add", ".")
            git(repo, "commit", "-m", "root")
            git(repo, "checkout", "-b", "base-feature")
            File(repo, "feature.txt").writeText("feature\n")
            git(repo, "add", ".")
            git(repo, "commit", "-m", "feature tip")
            val baseTip = revParse(repo, "base-feature")
            git(repo, "checkout", "main")
            File(repo, "main-only.txt").writeText("main\n")
            git(repo, "add", ".")
            git(repo, "commit", "-m", "main tip")

            val manager = WorktreeManager(File(repo, "worktrees"))
            val created = manager.create(
                originDir = repo.absolutePath,
                taskId = "task-nested-abcdef12",
                agent = AgentKind.Codex,
                title = "nested fork",
                startPoint = "base-feature",
            ).getOrThrow()

            assertEquals(baseTip, revParse(repo, created.branch))
            val mergeBase = gitOutput(repo, "merge-base", created.branch, "base-feature").trim()
            assertEquals(baseTip, mergeBase)
            assertTrue(File(created.path).isDirectory)
            assertEquals(created.branch, manager.currentBranch(created.path))
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun listAllParsesPorcelainIncludingLockedLinkedWorktree() {
        val repo = File.createTempFile("andy-wt-list", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            initTestRepo(repo)
            git(repo, "checkout", "-B", "main")
            File(repo, "readme.txt").writeText("hi\n")
            git(repo, "add", ".")
            git(repo, "commit", "-m", "initial")

            val linkedA = File(repo, "linked-a")
            val linkedB = File(repo, "linked-b")
            git(repo, "worktree", "add", "-b", "feature-a", linkedA.absolutePath)
            git(repo, "worktree", "add", "-b", "feature-b", linkedB.absolutePath)
            git(repo, "worktree", "lock", linkedB.absolutePath)

            val manager = WorktreeManager(File(repo, "worktrees"))
            val infos = manager.listAll(repo.absolutePath)
            assertEquals(3, infos.size)
            val main = infos.first { it.isMain }
            assertTrue(main.path == repo.absolutePath || File(main.path).canonicalPath == repo.canonicalPath)
            assertEquals("main", main.branch)
            assertFalse(main.locked)

            val a = infos.first { it.branch == "feature-a" }
            assertFalse(a.isMain)
            assertFalse(a.locked)
            assertTrue(File(a.path).canonicalPath == linkedA.canonicalPath)

            val b = infos.first { it.branch == "feature-b" }
            assertFalse(b.isMain)
            assertTrue(b.locked)
            assertTrue(File(b.path).canonicalPath == linkedB.canonicalPath)
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun mergeCommandScopesToTargetDir() {
        val manager = WorktreeManager()
        assertEquals(
            "git -C '/tmp/parent wt' merge 'andy/codex/nested-abc'",
            manager.mergeCommand("/tmp/parent wt", "andy/codex/nested-abc"),
        )
    }

    @Test
    fun mergeAppliesUncommittedSourceChangesIntoWorkingTreeWithoutCommitting() {
        val repo = File.createTempFile("andy-wt-merge", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            initTestRepo(repo)
            git(repo, "checkout", "-B", "main")
            File(repo, "root.txt").writeText("root\n")
            git(repo, "add", ".")
            git(repo, "commit", "-m", "root")
            val headBefore = revParse(repo, "HEAD")

            val linked = File(repo, "linked-wt")
            git(repo, "worktree", "add", "-b", "feature-branch", linked.absolutePath)
            // Dirty working tree — the common Andy case (agent edits, never committed).
            File(linked, "from-agent.txt").writeText("agent work\n")
            File(linked, "root.txt").writeText("root\nedited\n")

            val manager = WorktreeManager(File(repo, "worktrees"))
            assertEquals(
                WorktreeMergeOutcome.Applied,
                manager.merge(
                    targetDir = repo.absolutePath,
                    branch = "feature-branch",
                    sourceWorktreePath = linked.absolutePath,
                ),
            )

            assertTrue(File(repo, "from-agent.txt").isFile)
            assertEquals("root\nedited\n", File(repo, "root.txt").readText().replace("\r\n", "\n"))
            assertEquals("main", manager.currentBranch(repo.absolutePath))
            // Target branch must not move — changes stay uncommitted in the working tree.
            assertEquals(headBefore, revParse(repo, "HEAD"))
            val status = gitOutput(repo, "status", "--porcelain")
            assertTrue(status.contains("from-agent.txt"), status)
            assertTrue(status.contains("root.txt"), status)
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun mergeConflictsCanBeKeptOrAborted() {
        val repo = File.createTempFile("andy-wt-conflict", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            initTestRepo(repo)
            git(repo, "checkout", "-B", "main")
            File(repo, "conflict.txt").writeText("base\n")
            git(repo, "add", ".")
            git(repo, "commit", "-m", "base")

            val linked = File(repo, "linked-wt")
            git(repo, "worktree", "add", "-b", "feature-branch", linked.absolutePath)
            File(linked, "conflict.txt").writeText("from feature\n")
            git(linked, "add", ".")
            git(linked, "commit", "-m", "feature edit")

            File(repo, "conflict.txt").writeText("from main\n")
            git(repo, "add", ".")
            git(repo, "commit", "-m", "main edit")
            val headBefore = revParse(repo, "HEAD")

            val manager = WorktreeManager(File(repo, "worktrees"))
            val outcome = manager.merge(
                targetDir = repo.absolutePath,
                branch = "feature-branch",
                sourceWorktreePath = linked.absolutePath,
            )
            assertIs<WorktreeMergeOutcome.Conflicts>(outcome)
            assertTrue(File(repo, "conflict.txt").readText().contains("<<<<<<<"))
            assertEquals(headBefore, revParse(repo, "HEAD"))

            manager.abortMerge(repo.absolutePath).getOrThrow()
            assertEquals("from main\n", File(repo, "conflict.txt").readText().replace("\r\n", "\n"))
            assertEquals(headBefore, revParse(repo, "HEAD"))
            assertFalse(
                ProcessBuilder(listOf("git", "-C", repo.absolutePath, "rev-parse", "-q", "--verify", "MERGE_HEAD"))
                    .start()
                    .waitFor() == 0,
            )
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun currentBranchReturnsNullWhenDetached() {
        val repo = File.createTempFile("andy-wt-detached", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            initTestRepo(repo)
            File(repo, "a.txt").writeText("a\n")
            git(repo, "add", ".")
            git(repo, "commit", "-m", "initial")
            val tip = revParse(repo, "HEAD")
            git(repo, "checkout", "--detach", tip)
            val manager = WorktreeManager(File(repo, "worktrees"))
            assertNull(manager.currentBranch(repo.absolutePath))
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun changeSummaryExcludesUntouchedPreexistingChangesButShowsFurtherEdits() {
        val repo = File.createTempFile("andy-change-summary", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            initTestRepo(repo)
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

    @Test
    fun changeSummaryRestrictsToProvidedPaths() {
        val repo = File.createTempFile("andy-change-summary-paths", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            initTestRepo(repo)
            File(repo, "touched.kt").writeText("one\n")
            File(repo, "other.kt").writeText("one\n")
            git(repo, "add", ".")
            git(repo, "commit", "-m", "initial")
            val manager = WorktreeManager(File(repo, "worktrees"))
            val baseline = assertNotNull(manager.captureChangeBaseline(repo.absolutePath))

            File(repo, "touched.kt").writeText("one\ntwo\n")
            File(repo, "other.kt").writeText("one\ntwo\n")
            File(repo, "unrelated.kt").writeText("new\n")

            val scoped = assertNotNull(
                manager.changeSummary(repo.absolutePath, baseline, listOf("touched.kt")),
            )
            assertEquals(listOf("touched.kt"), scoped.files.map { it.path })

            val empty = assertNotNull(manager.changeSummary(repo.absolutePath, baseline, emptyList()))
            assertTrue(empty.files.isEmpty())

            val unscoped = assertNotNull(manager.changeSummary(repo.absolutePath, baseline))
            assertEquals(listOf("other.kt", "touched.kt", "unrelated.kt"), unscoped.files.map { it.path })

            val scopedSnapshot = assertNotNull(
                manager.changeSnapshot(repo.absolutePath, baseline, listOf("touched.kt")),
            )
            assertEquals(listOf("touched.kt"), scopedSnapshot.summary.files.map { it.path })
            assertEquals(setOf("touched.kt"), scopedSnapshot.diffs.keys)
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun scopedSnapshotIgnoresUnrelatedDirtyFiles() {
        val repo = File.createTempFile("andy-scoped-snapshot", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            initTestRepo(repo)
            File(repo, "agent.kt").writeText("one\n")
            File(repo, "user-wip-a.kt").writeText("one\n")
            File(repo, "user-wip-b.kt").writeText("one\n")
            git(repo, "add", ".")
            git(repo, "commit", "-m", "initial")
            val manager = WorktreeManager(File(repo, "worktrees"))
            val baseline = assertNotNull(manager.captureChangeBaseline(repo.absolutePath))

            File(repo, "agent.kt").writeText("one\ntwo\n")
            File(repo, "user-wip-a.kt").writeText("one\nuser edit\n")
            File(repo, "user-wip-b.kt").writeText("one\nuser edit\n")

            val scoped = assertNotNull(
                manager.changeSummary(repo.absolutePath, baseline, listOf("agent.kt")),
            )
            assertEquals(listOf("agent.kt"), scoped.files.map { it.path })
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun restorePathsRevertsModifiedAndDeletesNewFiles() {
        val repo = File.createTempFile("andy-restore-paths", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            initTestRepo(repo)
            File(repo, "tracked.kt").writeText("base\n")
            git(repo, "add", ".")
            git(repo, "commit", "-m", "initial")
            val manager = WorktreeManager(File(repo, "worktrees"))
            val baseline = assertNotNull(manager.captureChangeBaseline(repo.absolutePath))

            File(repo, "tracked.kt").writeText("base\nagent edit\n")
            File(repo, "agent-created.kt").writeText("new file\n")
            val snapshot = assertNotNull(manager.changeSnapshot(repo.absolutePath, baseline))

            assertTrue(manager.restorePaths(repo.absolutePath, baseline, snapshot).isSuccess)
            assertEquals("base\n", File(repo, "tracked.kt").readText())
            assertFalse(File(repo, "agent-created.kt").exists())
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun restorePathsRevertsWithEmptyDiffMetadata() {
        val repo = File.createTempFile("andy-restore-empty-diffs", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            initTestRepo(repo)
            File(repo, "tracked.kt").writeText("base\n")
            git(repo, "add", ".")
            git(repo, "commit", "-m", "initial")
            val manager = WorktreeManager(File(repo, "worktrees"))
            val baseline = assertNotNull(manager.captureChangeBaseline(repo.absolutePath))

            File(repo, "tracked.kt").writeText("base\nagent edit\n")
            File(repo, "agent-created.kt").writeText("new file\n")
            val snapshot = assertNotNull(manager.changeSnapshot(repo.absolutePath, baseline))
            val emptyDiffSnapshot = AgentThreadChangeSnapshot(summary = snapshot.summary, diffs = emptyMap())

            assertTrue(manager.restorePaths(repo.absolutePath, baseline, emptyDiffSnapshot).isSuccess)
            assertEquals("base\n", File(repo, "tracked.kt").readText())
            assertFalse(File(repo, "agent-created.kt").exists())
        } finally {
            repo.deleteRecursively()
        }
    }

    private fun initTestRepo(dir: File) {
        git(dir, "init")
        git(dir, "config", "core.autocrlf", "false")
        git(dir, "config", "user.email", "andy@example.test")
        git(dir, "config", "user.name", "Andy Test")
    }

    private fun git(dir: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", dir.absolutePath) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
    }

    private fun gitOutput(dir: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", dir.absolutePath) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        return output
    }

    private fun revParse(dir: File, ref: String): String = gitOutput(dir, "rev-parse", ref).trim()
}
