package app.andy.desktop.service.agents

import app.andy.model.AgentTask
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Captures OpenCode session ids after launch for resume via `--session <id>`.
 *
 * Prefers `opencode session list --format json` when available, then falls back
 * to scanning `~/.local/share/opencode/storage/session` (or XDG data dirs).
 */
internal object OpenCodeSessionIds {
    private val json = Json { ignoreUnknownKeys = true }
    private val SessionIdRegex = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

    fun resolveForTask(task: AgentTask): String? =
        task.vendorSessionId?.takeIf { it.isNotBlank() }

    fun awaitNewSessionId(
        binary: String?,
        cwd: String?,
        before: String?,
        launchedPrompt: String?,
        attempts: Int = 40,
        delayMs: Long = 250,
    ): String? {
        repeat(attempts) {
            findNewestSession(binary, cwd, before, launchedPrompt)?.let { return it }
            Thread.sleep(delayMs)
        }
        return findNewestSession(binary, cwd, before, launchedPrompt)
    }

    fun findNewestSession(
        binary: String?,
        cwd: String?,
        before: String? = null,
        launchedPrompt: String? = null,
    ): String? {
        listSessionsViaCli(binary, cwd)
            ?.firstOrNull { it != before }
            ?.let { return it }
        return scanSessionStore(cwd, before, launchedPrompt)
    }

    private fun listSessionsViaCli(binary: String?, cwd: String?): List<String>? {
        if (binary.isNullOrBlank() || !File(binary).canExecute()) return null
        return runCatching {
            val pb = ProcessBuilder(binary, "session", "list", "--format", "json")
                .redirectErrorStream(true)
            cwd?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }?.let { pb.directory(it) }
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(8, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly()
                return@runCatching null
            }
            parseSessionListOutput(output)
        }.getOrNull()
    }

    internal fun parseSessionListOutput(output: String): List<String> {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) return emptyList()
        // JSON array of session objects
        runCatching {
            val root = json.parseToJsonElement(trimmed)
            val array = root.jsonArray
            return array.mapNotNull { element ->
                val obj = element.jsonObject
                obj["id"]?.jsonPrimitive?.content
                    ?: obj["sessionID"]?.jsonPrimitive?.content
                    ?: obj["sessionId"]?.jsonPrimitive?.content
            }.filter { it.isNotBlank() }
        }
        // Fallback: one id per line / UUID scrape
        return SessionIdRegex.findAll(trimmed).map { it.value }.toList().distinct()
    }

    private fun scanSessionStore(cwd: String?, before: String?, launchedPrompt: String?): String? {
        val roots = sessionStoreRoots()
        val workspace = cwd?.let(::File)?.takeIf { it.isDirectory }?.canonicalPath
        val needle = launchedPrompt?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        var best: Pair<Long, String>? = null
        for (root in roots) {
            if (!root.isDirectory) continue
            root.walkTopDown().maxDepth(4).forEach { file ->
                if (!file.isFile) return@forEach
                val name = file.nameWithoutExtension
                val id = SessionIdRegex.find(name)?.value
                    ?: SessionIdRegex.find(file.name)?.value
                    ?: return@forEach
                if (id == before) return@forEach
                if (workspace != null) {
                    val text = runCatching { file.readText().take(8_000) }.getOrNull().orEmpty()
                    if (text.isNotEmpty() && !text.contains(workspace) &&
                        !text.contains(File(workspace).name)
                    ) {
                        // Soft filter — still allow if prompt matches.
                        if (needle == null || !text.contains(needle)) return@forEach
                    } else if (needle != null && text.isNotEmpty() && !text.contains(needle)) {
                        // Prefer prompt matches when we have content.
                    }
                }
                val mtime = file.lastModified()
                if (best == null || mtime > best!!.first) best = mtime to id
            }
        }
        return best?.second
    }

    private fun sessionStoreRoots(): List<File> {
        val home = File(System.getProperty("user.home"))
        val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
        return listOfNotNull(
            File(home, ".local/share/opencode"),
            File(home, ".opencode"),
            xdg?.let { File(it, "opencode") },
            File(home, ".config/opencode"),
        )
    }
}
