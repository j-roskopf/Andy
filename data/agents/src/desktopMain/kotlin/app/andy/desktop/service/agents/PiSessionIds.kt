package app.andy.desktop.service.agents

import app.andy.model.AgentTask
import java.io.File
import java.security.MessageDigest

/**
 * Captures Pi session ids from `~/.pi/agent/sessions/` after launch.
 *
 * Pi stores JSONL sessions organized by working directory. Resume uses
 * `--session <path|id>` with a full or partial UUID.
 */
internal object PiSessionIds {
    private val SessionIdRegex = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

    fun resolveForTask(task: AgentTask): String? =
        task.vendorSessionId?.takeIf { it.isNotBlank() }

    fun awaitNewSessionId(
        cwd: String?,
        before: String?,
        launchedPrompt: String?,
        startedAtMillis: Long,
        attempts: Int = 40,
        delayMs: Long = 250,
    ): String? {
        repeat(attempts) {
            findNewestSession(cwd, before, launchedPrompt, startedAtMillis)?.let { return it }
            Thread.sleep(delayMs)
        }
        return findNewestSession(cwd, before, launchedPrompt, startedAtMillis)
    }

    fun findNewestSession(
        cwd: String?,
        before: String? = null,
        launchedPrompt: String? = null,
        startedAtMillis: Long = 0L,
    ): String? {
        val dirs = sessionDirsFor(cwd)
        var best: Pair<Long, String>? = null
        val needle = launchedPrompt?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            dir.listFiles().orEmpty().forEach { file ->
                if (!file.isFile) return@forEach
                val id = SessionIdRegex.find(file.nameWithoutExtension)?.value
                    ?: SessionIdRegex.find(file.name)?.value
                    ?: return@forEach
                if (id == before) return@forEach
                val mtime = file.lastModified()
                if (startedAtMillis > 0 && mtime + 5_000 < startedAtMillis) return@forEach
                if (needle != null) {
                    val head = runCatching { file.readText().take(12_000) }.getOrNull().orEmpty()
                    if (head.isNotEmpty() && !head.contains(needle)) {
                        // Still consider by mtime if nothing better matches.
                    } else if (head.contains(needle)) {
                        val current = best
                        if (current == null || mtime >= current.first) best = mtime to id
                        return@forEach
                    }
                }
                val current = best
                if (current == null || mtime > current.first) best = mtime to id
            }
        }
        return best?.second
    }

    internal fun sessionDirsFor(cwd: String?, home: File = File(System.getProperty("user.home"))): List<File> {
        val root = File(home, ".pi/agent/sessions")
        if (!root.isDirectory) return listOf(root)
        val workspace = cwd?.let(::File)?.takeIf { it.isDirectory }?.canonicalFile
        if (workspace == null) {
            return listOf(root) + root.listFiles().orEmpty().filter { it.isDirectory }
        }
        val hashed = shortHash(workspace.path)
        val named = workspace.name
        val candidates = buildList {
            add(File(root, hashed))
            add(File(root, named))
            // Pi sometimes nests by sanitized absolute path fragments.
            root.listFiles().orEmpty().filter { it.isDirectory }.forEach { child ->
                if (child.name.contains(named, ignoreCase = true) ||
                    child.name.contains(hashed)
                ) {
                    add(child)
                }
            }
            add(root)
        }
        return candidates.distinct()
    }

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(8).joinToString("") { b -> "%02x".format(b) }
    }
}
