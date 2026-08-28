package app.andy.desktop.service.agents

import app.andy.domain.parseUnifiedDiff
import app.andy.model.AgentKind
import app.andy.model.AgentChangeSummary
import app.andy.model.AgentFileChange
import app.andy.model.AgentFileDiff
import app.andy.model.AgentThreadChangeSnapshot
import app.andy.model.GitBranchInfo
import app.andy.model.WorktreeMergeOutcome
import app.andy.model.WorkingTreeStatus
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WorktreeManager(
    private val worktreesRoot: File = File(System.getProperty("user.home"), ".andy/worktrees"),
) {
    companion object {
        /** Test hook: incremented on each [changeSnapshot] invocation. */
        internal val changeSnapshotInvocations = AtomicInteger(0)

        internal fun resetChangeSnapshotInvocationCount() {
            changeSnapshotInvocations.set(0)
        }
    }
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

    /** Local heads for [dir], current branch first when known. */
    fun listLocalBranches(dir: String): List<GitBranchInfo> {
        if (!isGitRepo(dir)) return emptyList()
        val current = currentBranch(dir)
        val listed = git(dir, "for-each-ref", "--format=%(refname:short)", "refs/heads/")
        if (listed.exitCode != 0) return emptyList()
        val names = listed.output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        return names
            .map { GitBranchInfo(name = it, isCurrent = it == current) }
            .sortedWith(
                compareByDescending<GitBranchInfo> { it.isCurrent }
                    .thenBy { it.name.lowercase() },
            )
    }

    /**
     * Dirty working-tree summary for the composer branch chip.
     * File count includes untracked; +/- lines come from tracked diff against HEAD.
     */
    fun workingTreeStatus(dir: String): WorkingTreeStatus? {
        if (!isGitRepo(dir)) return null
        val branch = currentBranch(dir)
        val status = git(dir, "status", "--porcelain")
        if (status.exitCode != 0) return WorkingTreeStatus(branch, 0, 0, 0)
        val dirtyFileCount = status.output.lineSequence().count { it.isNotBlank() }
        val numstat = git(dir, "diff", "--numstat", "HEAD")
        var additions = 0
        var deletions = 0
        if (numstat.exitCode == 0) {
            numstat.output.lineSequence().forEach { line ->
                val fields = line.split('\t')
                if (fields.size >= 2) {
                    additions += fields[0].toIntOrNull() ?: 0
                    deletions += fields[1].toIntOrNull() ?: 0
                }
            }
        }
        return WorkingTreeStatus(
            branch = branch,
            dirtyFileCount = dirtyFileCount,
            additions = additions,
            deletions = deletions,
        )
    }

    fun checkoutBranch(dir: String, branch: String): Result<Unit> {
        val name = branch.trim()
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("branch name is blank"))
        val result = git(dir, "switch", name)
        return if (result.exitCode == 0) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(result.output.ifBlank { "git switch failed" }))
        }
    }

    fun createAndCheckoutBranch(dir: String, branch: String): Result<Unit> {
        val name = branch.trim()
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("branch name is blank"))
        if (name.contains("..") || name.any { it.isWhitespace() || it == ':' || it == '\\' }) {
            return Result.failure(IllegalArgumentException("invalid branch name"))
        }
        val result = git(dir, "switch", "-c", name)
        return if (result.exitCode == 0) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(result.output.ifBlank { "git switch -c failed" }))
        }
    }

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
     * Snapshots working-tree content as a git tree object without touching HEAD or the real index.
     * When [paths] is null, indexes the full tree (`git add -A`). When non-null, only those
     * repo-relative paths are staged so unrelated dirty files elsewhere are not scanned.
     */
    private fun snapshotTree(dir: String, paths: Collection<String>? = null): String? {
        val tmpIndex = File.createTempFile("andy-snapshot-", ".idx").apply { delete() }
        return try {
            val env = mapOf("GIT_INDEX_FILE" to tmpIndex.absolutePath)
            val addArgs = buildList {
                add("add")
                if (paths == null) {
                    add("-A")
                } else {
                    add("--")
                    addAll(paths)
                }
            }
            if (git(dir, addArgs, env).exitCode != 0) return null
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
        val currentTree = snapshotTree(dir, paths) ?: return null
        return changeSummaryFromTrees(dir, baselineTree, currentTree, paths)
    }

    private fun changeSummaryFromTrees(
        dir: String,
        baselineTree: String,
        currentTree: String,
        paths: Collection<String>?,
    ): AgentChangeSummary? {
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

    /**
     * Captures a task's completed change set before later work in the repository can alter it.
     * [paths] is forwarded to [changeSummary] so the frozen snapshot matches the live scoped view.
     */
    fun changeSnapshot(
        dir: String,
        baselineTree: String?,
        paths: Collection<String>? = null,
    ): AgentThreadChangeSnapshot? {
        changeSnapshotInvocations.incrementAndGet()
        if (!isGitRepo(dir) || baselineTree == null) return null
        if (paths != null && paths.isEmpty()) {
            return AgentThreadChangeSnapshot(AgentChangeSummary(emptyList()), emptyMap())
        }
        val currentTree = snapshotTree(dir, paths) ?: return null
        val summary = changeSummaryFromTrees(dir, baselineTree, currentTree, paths) ?: return null
        if (summary.files.isEmpty()) {
            return AgentThreadChangeSnapshot(summary, emptyMap())
        }
        val diffs = summary.files.associate { change ->
            change.path to (
                fileDiffFromTrees(dir, change.path, baselineTree, currentTree)
                    ?: AgentFileDiff(path = change.path, lines = emptyList())
                )
        }
        return AgentThreadChangeSnapshot(summary = summary, diffs = diffs)
    }

    /** True when [paths] differ between HEAD and the working tree (including untracked). */
    fun pathsHaveUncommittedChanges(dir: String, paths: Collection<String>): Boolean {
        if (!isGitRepo(dir) || paths.isEmpty()) return false
        val pathList = paths.toList()
        val diff = git(dir, listOf("diff", "--quiet", "HEAD", "--") + pathList, emptyMap())
        if (diff.exitCode != 0) return true
        val status = git(dir, listOf("status", "--porcelain", "--") + pathList, emptyMap())
        return status.output.isNotBlank()
    }

    /**
     * Restores [paths] to their contents at [baselineTree]. New files (not present in the baseline
     * tree) are deleted from the working directory.
     */
    fun restorePaths(
        dir: String,
        baselineTree: String,
        snapshot: AgentThreadChangeSnapshot,
    ): Result<Unit> {
        if (!isGitRepo(dir) || baselineTree.isBlank()) {
            return Result.failure(IllegalStateException("not a git repository"))
        }
        val existingPaths = mutableListOf<String>()
        val newPaths = mutableListOf<String>()
        snapshot.summary.files.forEach { change ->
            val path = change.path
            when {
                snapshot.diffs[path]?.isNewFile == true -> newPaths += path
                snapshot.diffs[path]?.isNewFile == false -> existingPaths += path
                pathExistsInBaselineTree(dir, baselineTree, path) -> existingPaths += path
                else -> newPaths += path
            }
        }
        if (existingPaths.isNotEmpty()) {
            val restore = git(dir, listOf("checkout", baselineTree, "--") + existingPaths, emptyMap())
            if (restore.exitCode != 0) {
                return Result.failure(IllegalStateException(restore.output.ifBlank { "git checkout failed" }))
            }
        }
        newPaths.forEach { relative ->
            val file = File(dir, relative)
            if (file.exists() && !file.delete()) {
                return Result.failure(IllegalStateException("failed to delete $relative"))
            }
        }
        return Result.success(Unit)
    }

    private fun pathExistsInBaselineTree(dir: String, baselineTree: String, relativePath: String): Boolean {
        if (relativePath.isBlank()) return false
        val result = git(dir, listOf("ls-tree", baselineTree, "--", relativePath), emptyMap())
        return result.exitCode == 0 && result.output.trim().isNotEmpty()
    }

    /** Unified diff for a single path relative to [dir] and [baselineTree], for inline review. */
    fun fileDiff(dir: String, relativePath: String, baselineTree: String?): AgentFileDiff? {
        if (!isGitRepo(dir) || relativePath.isBlank() || baselineTree == null) return null
        val currentTree = snapshotTree(dir, listOf(relativePath)) ?: return null
        return fileDiffFromTrees(dir, relativePath, baselineTree, currentTree)
    }

    private fun fileDiffFromTrees(
        dir: String,
        relativePath: String,
        baselineTree: String,
        currentTree: String,
    ): AgentFileDiff? {
        val result = git(
            dir,
            listOf(
                "diff",
                "--no-color",
                "--no-ext-diff",
                "--no-renames",
                "-U3",
                baselineTree,
                currentTree,
                "--",
                relativePath,
            ),
            emptyMap(),
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
