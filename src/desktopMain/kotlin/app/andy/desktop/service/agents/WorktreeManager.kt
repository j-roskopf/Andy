package app.andy.desktop.service.agents

import app.andy.domain.parseUnifiedDiff
import app.andy.model.AgentKind
import app.andy.model.AgentChangeSummary
import app.andy.model.AgentFileChange
import app.andy.model.AgentFileDiff
import app.andy.model.AgentThreadChangeSnapshot
import app.andy.model.WorktreeMergeOutcome
import java.io.File
import java.util.concurrent.TimeUnit

class WorktreeManager(
    private val worktreesRoot: File = File(System.getProperty("user.home"), ".andy/worktrees"),
) {
    data class Worktree(val path: String, val branch: String)

    data class WorktreeInfo(
        val path: String,
        val branch: String?,
        val isMain: Boolean,
        val locked: Boolean,
        val prunable: Boolean,
    )

    fun isGitRepo(dir: String): Boolean =
        File(dir).isDirectory && git(dir, "rev-parse", "--git-dir").exitCode == 0

    /** Current branch of [dir], or null when detached HEAD or not a repo. */
    fun currentBranch(dir: String): String? =
        git(dir, "branch", "--show-current").takeIf { it.exitCode == 0 }?.output?.trim()?.ifBlank { null }

    /** True when [worktreePath] is still registered for [originDir]'s repo and present on disk. */
    fun isLiveWorktree(originDir: String, worktreePath: String): Boolean {
        if (!File(worktreePath).isDirectory) return false
        val want = runCatching { File(worktreePath).canonicalPath }.getOrElse { worktreePath }
        return listAll(originDir).any { info ->
            runCatching { File(info.path).canonicalPath }.getOrElse { info.path } == want
        }
    }

    /**
     * Every worktree sharing [dir]'s repo — main checkout plus all linked worktrees — regardless of
     * which one [dir] itself is. `git worktree list` is repo-scoped, so this is what gives the
     * "base on" picker and the Worktrees tab correct scoping for free, including nested ones.
     */
    fun listAll(dir: String): List<WorktreeInfo> {
        val result = git(dir, "worktree", "list", "--porcelain")
        if (result.exitCode != 0) return emptyList()
        val infos = mutableListOf<WorktreeInfo>()
        var path: String? = null
        var branch: String? = null
        var locked = false
        var prunable = false
        var sawDetached = false
        fun flush() {
            val worktreePath = path ?: return
            infos += WorktreeInfo(
                path = worktreePath,
                branch = if (sawDetached) null else branch,
                isMain = infos.isEmpty(),
                locked = locked,
                prunable = prunable,
            )
            path = null
            branch = null
            locked = false
            prunable = false
            sawDetached = false
        }
        for (raw in result.output.lineSequence()) {
            val line = raw.trimEnd()
            when {
                line.isEmpty() -> flush()
                line.startsWith("worktree ") -> {
                    flush()
                    path = line.removePrefix("worktree ").trim()
                }
                line.startsWith("branch ") -> {
                    val ref = line.removePrefix("branch ").trim()
                    branch = ref.removePrefix("refs/heads/")
                }
                line == "detached" -> {
                    sawDetached = true
                    branch = null
                }
                line.startsWith("locked") -> locked = true
                line.startsWith("prunable") -> prunable = true
            }
        }
        flush()
        return infos
    }

    fun create(
        originDir: String,
        taskId: String,
        agent: AgentKind,
        title: String,
        startPoint: String? = null,
    ): Result<Worktree> {
        val repoName = File(originDir).name.ifBlank { "repo" }
        val shortId = taskId.substringAfterLast('-').take(8)
        val slug = title.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-').take(32).ifBlank { "task" }
        val branch = "andy/${agent.cliName}/$slug-$shortId"
        val path = File(worktreesRoot, "$repoName-$shortId")
        worktreesRoot.mkdirs()
        val args = buildList {
            addAll(listOf("worktree", "add", path.absolutePath, "-b", branch))
            if (startPoint != null) add(startPoint)
        }
        val result = git(originDir, *args.toTypedArray())
        return if (result.exitCode == 0) {
            Result.success(Worktree(path.absolutePath, branch))
        } else {
            Result.failure(IllegalStateException(result.output.ifBlank { "git worktree add failed" }))
        }
    }

    fun diffSummary(worktreePath: String): String {
        val status = git(worktreePath, "status", "--porcelain")
        val stat = git(worktreePath, "diff", "--stat", "HEAD")
        val parts = buildList {
            if (stat.exitCode == 0 && stat.output.isNotBlank()) add(stat.output.trimEnd())
            val untracked = status.output.lines().filter { it.startsWith("??") }
            if (untracked.isNotEmpty()) {
                add(untracked.joinToString("\n") { "new file  ${it.drop(3)}" })
            }
        }
        return if (parts.isEmpty()) "no changes yet" else parts.joinToString("\n\n")
    }

    /**
     * Snapshots the full working-tree state (tracked + untracked, respecting .gitignore) as a git
     * tree object, without touching HEAD, the branch, or the real index. Used as a content-addressed
     * baseline so later diffs can tell "unchanged since baseline" apart from "further edited during
     * this task", even for files that were already dirty when the baseline was taken.
     */
    private fun snapshotTree(dir: String): String? {
        val tmpIndex = File.createTempFile("andy-snapshot-", ".idx").apply { delete() }
        return try {
            val env = mapOf("GIT_INDEX_FILE" to tmpIndex.absolutePath)
            if (git(dir, listOf("add", "-A"), env).exitCode != 0) return null
            val writeTree = git(dir, listOf("write-tree"), env)
            if (writeTree.exitCode != 0) return null
            writeTree.output.trim().takeIf { it.isNotBlank() }
        } finally {
            tmpIndex.delete()
        }
    }

    /** Records the full working-tree state before an agent task starts, as a diffable baseline. */
    fun captureChangeBaseline(dir: String): String? {
        if (!isGitRepo(dir)) return null
        return snapshotTree(dir)
    }

    /**
     * [paths], when non-null, restricts the diff to those repo-relative paths (e.g. the files an
     * agent's tool calls actually touched) so unrelated changes elsewhere in the working directory
     * — from another concurrent task, a build step, a manual edit — aren't attributed to this task.
     * A non-null empty collection short-circuits to "no changes" without shelling out to git.
     */
    fun changeSummary(dir: String, baselineTree: String?, paths: Collection<String>? = null): AgentChangeSummary? {
        if (!isGitRepo(dir) || baselineTree == null) return null
        if (paths != null && paths.isEmpty()) return AgentChangeSummary(emptyList())
        val currentTree = snapshotTree(dir) ?: return null
        if (currentTree == baselineTree) return AgentChangeSummary(emptyList())
        val args = mutableListOf("diff", "--numstat", "--no-renames", baselineTree, currentTree)
        if (paths != null) {
            args += "--"
            args += paths
        }
        val numstat = git(dir, args, emptyMap())
        if (numstat.exitCode != 0) return null
        val changes = numstat.output.lineSequence().mapNotNull { line ->
            val fields = line.split('\t', limit = 3)
            if (fields.size != 3) return@mapNotNull null
            AgentFileChange(
                path = fields[2],
                additions = fields[0].toIntOrNull() ?: 0,
                deletions = fields[1].toIntOrNull() ?: 0,
            )
        }.sortedBy { it.path }.toList()
        return AgentChangeSummary(changes)
    }

    /** Captures a task's completed change set before later work in the repository can alter it. */
    fun changeSnapshot(dir: String, baselineTree: String?): AgentThreadChangeSnapshot? {
        val summary = changeSummary(dir, baselineTree) ?: return null
        val diffs = summary.files.associate { change ->
            change.path to (fileDiff(dir, change.path, baselineTree) ?: AgentFileDiff(path = change.path, lines = emptyList()))
        }
        return AgentThreadChangeSnapshot(summary = summary, diffs = diffs)
    }

    /** Unified diff for a single path relative to [dir] and [baselineTree], for inline review. */
    fun fileDiff(dir: String, relativePath: String, baselineTree: String?): AgentFileDiff? {
        if (!isGitRepo(dir) || relativePath.isBlank() || baselineTree == null) return null
        val currentTree = snapshotTree(dir) ?: return null
        val result = git(
            dir,
            "diff",
            "--no-color",
            "--no-ext-diff",
            "--no-renames",
            "-U3",
            baselineTree,
            currentTree,
            "--",
            relativePath,
        )
        if (result.exitCode != 0) return null
        if (result.output.isBlank()) return AgentFileDiff(path = relativePath, lines = emptyList())
        return parseUnifiedDiff(result.output, relativePath)
    }

    /** [targetDir] is originDir for a root worktree, or the parent's worktree path for a nested one. */
    fun mergeCommand(targetDir: String, branch: String): String =
        "git -C ${shellQuote(targetDir)} merge ${shellQuote(branch)}"

    /**
     * Brings [branch] (and any dirty work in [sourceWorktreePath]) into [targetDir]'s working
     * tree **without committing**. HEAD stays put so the user can review and commit themselves.
     *
     * Dirty source edits are checkpoint-committed onto [branch] only so git can see them; that
     * commit lives on the worktree branch (removed with the worktree), not on the target branch.
     *
     * On conflicts, conflict markers are left in place ([WorktreeMergeOutcome.Conflicts]); call
     * [abortMerge] if the user declines to keep them.
     */
    fun merge(targetDir: String, branch: String, sourceWorktreePath: String? = null): WorktreeMergeOutcome {
        if (sourceWorktreePath != null) {
            commitDirtyForMerge(sourceWorktreePath).onFailure {
                return WorktreeMergeOutcome.Failed(it.message?.ifBlank { null } ?: "checkpoint commit failed")
            }
        }
        // --no-ff avoids fast-forwarding HEAD; --no-commit leaves the result in the index/worktree.
        val merge = git(targetDir, "merge", "--no-commit", "--no-ff", branch)
        if (merge.exitCode != 0) {
            val detail = merge.output.ifBlank { "git merge failed" }
            // Leave conflicted merges for the caller to keep or abort; clean up other failures.
            return if (mergeInProgress(targetDir)) {
                WorktreeMergeOutcome.Conflicts(detail)
            } else {
                git(targetDir, "merge", "--abort")
                WorktreeMergeOutcome.Failed(detail)
            }
        }
        // Mixed reset clears MERGE_HEAD / the index back to HEAD while keeping the merged files
        // in the working tree as unstaged (or untracked) changes.
        val reset = git(targetDir, "reset")
        return if (reset.exitCode == 0) {
            WorktreeMergeOutcome.Applied
        } else {
            WorktreeMergeOutcome.Failed(reset.output.ifBlank { "git reset failed" })
        }
    }

    fun abortMerge(targetDir: String): Result<Unit> {
        if (!mergeInProgress(targetDir)) return Result.success(Unit)
        val result = git(targetDir, "merge", "--abort")
        return if (result.exitCode == 0) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(result.output.ifBlank { "git merge --abort failed" }))
        }
    }

    private fun mergeInProgress(dir: String): Boolean =
        git(dir, "rev-parse", "-q", "--verify", "MERGE_HEAD").exitCode == 0

    /** Commits tracked + untracked (non-ignored) changes in [worktreePath] so a later merge can see them. */
    private fun commitDirtyForMerge(worktreePath: String): Result<Unit> {
        val status = git(worktreePath, "status", "--porcelain")
        if (status.exitCode != 0) {
            return Result.failure(IllegalStateException(status.output.ifBlank { "git status failed" }))
        }
        if (status.output.isBlank()) return Result.success(Unit)
        val add = git(worktreePath, "add", "-A")
        if (add.exitCode != 0) {
            return Result.failure(IllegalStateException(add.output.ifBlank { "git add failed" }))
        }
        // exit 0 = nothing staged; exit 1 = staged diff present. Other codes are real failures.
        val cached = git(worktreePath, "diff", "--cached", "--quiet")
        if (cached.exitCode == 0) return Result.success(Unit)
        if (cached.exitCode != 1) {
            return Result.failure(IllegalStateException(cached.output.ifBlank { "git diff --cached failed" }))
        }
        val commit = git(worktreePath, "commit", "-m", "andy: checkpoint before merge")
        return if (commit.exitCode == 0) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(commit.output.ifBlank { "git commit failed" }))
        }
    }

    fun remove(originDir: String, worktreePath: String, branch: String?): Result<Unit> {
        val removed = git(originDir, "worktree", "remove", "--force", worktreePath)
        if (removed.exitCode != 0) {
            return Result.failure(IllegalStateException(removed.output.ifBlank { "git worktree remove failed" }))
        }
        branch?.let { git(originDir, "branch", "-D", it) }
        return Result.success(Unit)
    }

    private data class GitResult(val exitCode: Int, val output: String)

    private fun git(dir: String, vararg args: String): GitResult = git(dir, args.toList(), emptyMap())

    private fun git(dir: String, args: List<String>, env: Map<String, String>): GitResult = runCatching {
        val process = ProcessBuilder(listOf("git", "-C", dir) + args).redirectErrorStream(true)
            .apply { environment().putAll(env) }
            .start()
        val output = readOutputWithin(process, timeoutSeconds = 30)
        if (output == null) {
            return GitResult(-1, "git timed out")
        }
        GitResult(process.exitValue(), output)
    }.getOrElse { GitResult(-1, it.message.orEmpty()) }

    /** Drains stdout in parallel so a hung Git subprocess cannot bypass the timeout. */
    private fun readOutputWithin(process: Process, timeoutSeconds: Long): String? {
        val output = StringBuffer()
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().use { stream -> output.append(stream.readText()) }
            }
        }, "andy-git-output-reader").apply { isDaemon = true }
        reader.start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
            reader.join(1_000)
            return null
        }
        reader.join(1_000)
        return output.toString()
    }
}
