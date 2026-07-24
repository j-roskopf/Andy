package app.andy.desktop.service.agents

import app.andy.domain.parseUnifiedDiff
import app.andy.model.AgentKind
import app.andy.model.AgentChangeSummary
import app.andy.model.AgentFileChange
import app.andy.model.AgentFileDiff
import app.andy.model.AgentThreadChangeSnapshot
import java.io.File
import java.util.concurrent.TimeUnit

class WorktreeManager(
    private val worktreesRoot: File = File(System.getProperty("user.home"), ".andy/worktrees"),
) {
    data class Worktree(val path: String, val branch: String)

    fun isGitRepo(dir: String): Boolean =
        File(dir).isDirectory && git(dir, "rev-parse", "--git-dir").exitCode == 0

    fun create(originDir: String, taskId: String, agent: AgentKind, title: String): Result<Worktree> {
        val repoName = File(originDir).name.ifBlank { "repo" }
        val shortId = taskId.substringAfterLast('-').take(8)
        val slug = title.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-').take(32).ifBlank { "task" }
        val branch = "andy/${agent.cliName}/$slug-$shortId"
        val path = File(worktreesRoot, "$repoName-$shortId")
        worktreesRoot.mkdirs()
        val result = git(originDir, "worktree", "add", path.absolutePath, "-b", branch)
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

    fun changeSummary(dir: String, baselineTree: String?): AgentChangeSummary? {
        if (!isGitRepo(dir) || baselineTree == null) return null
        val currentTree = snapshotTree(dir) ?: return null
        if (currentTree == baselineTree) return AgentChangeSummary(emptyList())
        val numstat = git(dir, "diff", "--numstat", "--no-renames", baselineTree, currentTree)
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

    fun mergeCommand(originDir: String, branch: String): String =
        "git -C ${shellQuote(originDir)} merge ${shellQuote(branch)}"

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
