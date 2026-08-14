package app.andy.desktop.service.agents

import app.andy.model.AgentTask
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Captures Codex thread ids from `~/.codex/sessions/**/rollout-*-<id>.jsonl`
 * for resume via `codex resume <id>`.
 */
internal object CodexSessionIds {
    private val json = Json { ignoreUnknownKeys = true }
    private val SessionIdRegex =
        Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
    private val RolloutSessionIdRegex =
        Regex("""rollout-.*-([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\.jsonl$""")

    fun codexHome(home: File = File(System.getProperty("user.home"))): File {
        System.getenv("CODEX_HOME")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        return File(home, ".codex")
    }

    /**
     * Only trusts a stored [AgentTask.vendorSessionId], and only once its
     * rollout file is confirmed to actually contain this task's prompt.
     * Scanning all rollouts for a prompt-text match is deliberately not done
     * here — two chats can share a prefix, and a fuzzy match would silently
     * resume the wrong thread. A missing or unverifiable id means
     * capture-at-launch failed — a separate bug to fix, not something to
     * guess around.
     */
    fun resolveForTask(task: AgentTask, home: File = File(System.getProperty("user.home"))): String? {
        val stored = task.vendorSessionId?.takeIf { it.isNotBlank() } ?: return null
        return stored.takeIf { sessionContainsPrompt(it, task.prompt, task.cwd, home) }
    }

    fun sessionContainsPrompt(
        sessionId: String,
        prompt: String,
        cwd: String?,
        home: File = File(System.getProperty("user.home")),
    ): Boolean {
        val needle = VendorSessionMatching.firstLine(prompt) ?: return false
        scanRolloutFiles(cwd, sessionId, home).forEach { (file, _) ->
            if (rolloutContainsPrompt(file, needle)) return true
        }
        return false
    }

    fun awaitNewSessionId(
        cwd: String?,
        before: String?,
        launchedPrompt: String?,
        startedAtMillis: Long,
        home: File = File(System.getProperty("user.home")),
        attempts: Int = 40,
        delayMs: Long = 250,
    ): String? {
        repeat(attempts) {
            findNewestSession(cwd, before, launchedPrompt, startedAtMillis, home)?.let { return it }
            Thread.sleep(delayMs)
        }
        return findNewestSession(cwd, before, launchedPrompt, startedAtMillis, home)
    }

    fun findNewestSession(
        cwd: String?,
        before: String? = null,
        launchedPrompt: String? = null,
        startedAtMillis: Long = 0L,
        home: File = File(System.getProperty("user.home")),
    ): String? {
        var best: Pair<Long, String>? = null
        scanRolloutFiles(cwd, home = home).forEach { (file, sessionId) ->
            if (sessionId == before) return@forEach
            val mtime = file.lastModified()
            if (startedAtMillis > 0 && mtime + 5_000 < startedAtMillis) return@forEach
            if (launchedPrompt != null && !rolloutContainsPrompt(file, VendorSessionMatching.firstLine(launchedPrompt).orEmpty())) {
                return@forEach
            }
            if (best == null || mtime > best.first) best = mtime to sessionId
        }
        return best?.second
    }

    internal fun parseSessionIdFromRolloutName(filename: String): String? =
        RolloutSessionIdRegex.find(filename)?.groupValues?.getOrNull(1)

    private fun scanRolloutFiles(
        cwd: String?,
        sessionIdFilter: String? = null,
        home: File = File(System.getProperty("user.home")),
    ): Sequence<Pair<File, String>> {
        val sessionsRoot = File(codexHome(home), "sessions")
        if (!sessionsRoot.isDirectory) return emptySequence()
        val workspace = VendorSessionMatching.normalizeWorkspace(cwd)
        return sessionsRoot.walkTopDown()
            .maxDepth(6)
            .filter { it.isFile && it.name.startsWith("rollout-") && it.extension == "jsonl" }
            .mapNotNull { file ->
                val sessionId = parseSessionIdFromRolloutName(file.name)
                    ?: SessionIdRegex.find(file.name)?.value
                    ?: return@mapNotNull null
                if (sessionIdFilter != null && sessionId != sessionIdFilter) return@mapNotNull null
                if (workspace != null && !rolloutMatchesCwd(file, workspace)) return@mapNotNull null
                file to sessionId
            }
            .toList()
            .sortedByDescending { (file, _) -> file.lastModified() }
            .asSequence()
    }

    private fun rolloutMatchesCwd(file: File, workspace: String): Boolean {
        val head = runCatching { file.bufferedReader().use { it.readLine() } }.getOrNull().orEmpty()
        if (head.isEmpty()) return true
        val obj = runCatching { json.parseToJsonElement(head).jsonObject }.getOrNull() ?: return true
        val payload = obj["payload"]?.jsonObject ?: return true
        val sessionCwd = payload["cwd"]?.jsonPrimitive?.contentOrNull
        if (sessionCwd.isNullOrBlank()) return true
        return VendorSessionMatching.cwdMatches(sessionCwd, workspace)
    }

    private fun rolloutContainsPrompt(file: File, needle: String): Boolean {
        if (needle.isBlank()) return false
        val text = runCatching { file.readText().take(32_000) }.getOrNull().orEmpty()
        if (text.isEmpty()) return false
        return text.contains(needle, ignoreCase = true) ||
            VendorSessionMatching.promptMatches(text, needle)
    }
}
