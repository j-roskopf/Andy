package app.andy.desktop.service.remote

import app.andy.desktop.service.agents.skillRootsFor
import app.andy.model.AgentKind
import app.andy.model.AgentSkill
import app.andy.model.WorktreeBaseOption
import app.andy.model.WorktreeNode
import app.andy.terminal.TmuxAndy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs git / skill discovery on a remote host via system `ssh`.
 * Paths are interpreted on the remote machine — never the GUI host.
 * When [controlPath] is set, reuses the Andy connect multiplexed master (no second password).
 */
class SshRemoteProbes(
    private val sshTarget: String,
    private val controlPath: File? = null,
) {
    suspend fun isGitRepo(dir: String): Boolean = withContext(Dispatchers.IO) {
        val code = sshExec(listOf("git", "-C", dir, "rev-parse", "--is-inside-work-tree")).exitCode
        code == 0
    }

    suspend fun currentBranch(dir: String): String? = withContext(Dispatchers.IO) {
        val result = sshExec(listOf("git", "-C", dir, "branch", "--show-current"))
        if (result.exitCode != 0) return@withContext null
        result.stdout.trim().takeIf { it.isNotBlank() }
    }

    suspend fun worktreeTree(originDir: String): List<WorktreeNode> = withContext(Dispatchers.IO) {
        val result = sshExec(
            listOf(
                "git", "-C", originDir, "worktree", "list", "--porcelain",
            ),
        )
        if (result.exitCode != 0) return@withContext emptyList()
        parseWorktreePorcelain(result.stdout)
    }

    suspend fun worktreeBaseOptions(
        originDir: String,
        tracked: List<WorktreeBaseOption>,
    ): List<WorktreeBaseOption> = withContext(Dispatchers.IO) {
        val onDisk = worktreeTree(originDir).mapTo(linkedSetOf()) { it.path }
        tracked.filter { it.path in onDisk }
    }

    /**
     * Best-effort remote skill scan: `find` SKILL.md under the same roots [discoverAgentSkills]
     * would use locally, then parse headers over SSH. Caps at 80 skills.
     */
    suspend fun discoverSkills(agent: AgentKind, directory: String?): List<AgentSkill> =
        withContext(Dispatchers.IO) {
            val home = sshShell("printf '%s' \"\$HOME\"").stdout.trim()
                .takeIf { it.isNotBlank() } ?: return@withContext emptyList()
            val workspace = directory?.takeIf { it.isNotBlank() }
            val roots = remoteSkillRoots(agent, workspace, home)
            if (roots.isEmpty()) return@withContext emptyList()
            val findCmd = buildString {
                append("find")
                roots.forEach { append(" ").append(TmuxAndy.shellQuote(it)) }
                append(" -type f -name SKILL.md 2>/dev/null | head -n 80")
            }
            val listed = sshShell(findCmd)
            if (listed.exitCode != 0 && listed.stdout.isBlank()) return@withContext emptyList()
            val discovered = linkedMapOf<String, AgentSkill>()
            for (path in listed.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }) {
                val header = sshExec(listOf("head", "-n", "24", path)).stdout.lines()
                val name = header.firstOrNull { it.startsWith("name:") }
                    ?.substringAfter(':')?.trim()?.trim('"', '\'')
                    ?.takeIf { it.isNotBlank() }
                    ?: File(path).parentFile?.name
                    ?: continue
                val description = header.firstOrNull { it.startsWith("description:") }
                    ?.substringAfter(':')?.trim()?.trim('"', '\'')
                    .orEmpty()
                val userInvocable = header
                    .firstOrNull { it.startsWith("user-invocable:") }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.trim('"', '\'')
                    ?.lowercase()
                    ?.let { it != "false" }
                    ?: true
                discovered.putIfAbsent(
                    name.lowercase(),
                    AgentSkill(name, description, path, userInvocable = userInvocable),
                )
            }
            discovered.values.sortedBy { it.name.lowercase() }
        }

    suspend fun knownSkillNames(directory: String?): Set<String> = withContext(Dispatchers.IO) {
        AgentKind.entries.flatMapTo(linkedSetOf()) { kind ->
            discoverSkills(kind, directory).map { it.name }
        }
    }

    private fun remoteSkillRoots(agent: AgentKind, workspace: String?, home: String): List<String> {
        // Mirror skillRootsFor paths as remote absolute strings (home is remote $HOME).
        val ws = workspace?.let(::File)
        val homeFile = File(home)
        return skillRootsFor(agent, ws, homeFile).map { it.path }
    }

    private fun parseWorktreePorcelain(stdout: String): List<WorktreeNode> {
        val nodes = mutableListOf<WorktreeNode>()
        var path: String? = null
        var branch: String? = null
        var isMain = false
        fun flush() {
            val p = path ?: return
            nodes += WorktreeNode(
                path = p,
                branch = branch ?: "HEAD",
                isMain = isMain,
                taskId = null,
                taskTitle = null,
                parentTaskId = null,
                tracked = false,
            )
            path = null
            branch = null
            isMain = false
        }
        for (line in stdout.lines()) {
            when {
                line.startsWith("worktree ") -> {
                    flush()
                    path = line.removePrefix("worktree ").trim()
                    isMain = nodes.isEmpty()
                }
                line.startsWith("branch ") -> {
                    branch = line.removePrefix("branch ").trim().removePrefix("refs/heads/")
                }
                line.isBlank() -> flush()
            }
        }
        flush()
        return nodes
    }

    data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)

    fun sshExec(remoteArgv: List<String>): ExecResult {
        val remote = remoteArgv.joinToString(" ") { TmuxAndy.shellQuote(it) }
        return sshShell(remote)
    }

    fun sshShell(remoteCommand: String): ExecResult {
        val cmd = buildList {
            add("ssh")
            addAll(SshProcess.baseOptions(controlPath))
            add(sshTarget)
            add(remoteCommand)
        }
        val process = SshProcess.processBuilder(cmd).redirectErrorStream(false).start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        val code = if (finished) process.exitValue() else {
            process.destroyForcibly()
            -1
        }
        return ExecResult(code, stdout, stderr)
    }
}
