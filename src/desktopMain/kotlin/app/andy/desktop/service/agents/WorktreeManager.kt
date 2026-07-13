package app.andy.desktop.service.agents

import app.andy.domain.diffForNewFile
import app.andy.domain.parseUnifiedDiff
import app.andy.model.AgentKind
import app.andy.model.AgentChangeSummary
import app.andy.model.AgentFileChange
import app.andy.model.AgentFileDiff
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

    /** Records local changes that predate an agent task so they are never attributed to it later. */
    fun captureChangeBaseline(dir: String): List<String>? {
        if (!isGitRepo(dir)) return null
        return (changedPaths(dir) + untrackedPaths(dir)).distinct().sorted()
    }

    fun changeSummary(dir: String, baselinePaths: List<String>): AgentChangeSummary? {
        if (!isGitRepo(dir)) return null
        val ignored = baselinePaths.toSet()
        val changes = linkedMapOf<String, AgentFileChange>()
        val numstat = git(dir, "diff", "--numstat", "--no-renames", "HEAD")
        if (numstat.exitCode == 0) {
            numstat.output.lineSequence().forEach { line ->
                val fields = line.split('\t', limit = 3)
                if (fields.size != 3 || fields[2] in ignored) return@forEach
                changes[fields[2]] = AgentFileChange(
                    path = fields[2],
                    additions = fields[0].toIntOrNull() ?: 0,
                    deletions = fields[1].toIntOrNull() ?: 0,
                )
            }
        }
        untrackedPaths(dir).filterNot { it in ignored || it in changes }.forEach { path ->
            val lines = runCatching { File(dir, path).useLines { it.count() } }.getOrDefault(0)
            changes[path] = AgentFileChange(path, additions = lines, deletions = 0)
        }
        return AgentChangeSummary(changes.values.sortedBy { it.path })
    }

    /** Unified diff for a single path relative to [dir], for inline review. */
    fun fileDiff(dir: String, relativePath: String): AgentFileDiff? {
        if (!isGitRepo(dir) || relativePath.isBlank()) return null
        if (relativePath in untrackedPaths(dir)) {
            val file = File(dir, relativePath)
            if (!file.isFile) return AgentFileDiff(path = relativePath, lines = emptyList(), isNewFile = true)
            val content = runCatching { file.readText() }.getOrElse { return null }
            return diffForNewFile(relativePath, content)
        }
        val result = git(
            dir,
            "diff",
            "--no-color",
            "--no-ext-diff",
            "--no-renames",
            "-U3",
            "HEAD",
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

    private fun changedPaths(dir: String): List<String> = git(dir, "diff", "--name-only", "--no-renames", "HEAD")
        .output.lineSequence().filter { it.isNotBlank() }.toList()

    private fun untrackedPaths(dir: String): List<String> = git(dir, "ls-files", "--others", "--exclude-standard")
        .output.lineSequence().filter { it.isNotBlank() }.toList()

    private fun git(dir: String, vararg args: String): GitResult = runCatching {
        val process = ProcessBuilder(listOf("git", "-C", dir) + args).redirectErrorStream(true).start()
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
