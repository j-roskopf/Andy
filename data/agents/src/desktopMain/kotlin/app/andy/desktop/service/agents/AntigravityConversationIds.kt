package app.andy.desktop.service.agents

import app.andy.model.AgentTask
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Reads Antigravity CLI (`agy`) conversation ids from its local cache.
 *
 * `--continue` always binds to the most recent conversation for a workspace —
 * which is wrong for Andy's per-task resume. Prefer an explicit
 * `--conversation <id>` only when we can prove it belongs to this Andy task.
 */
internal object AntigravityConversationIds {
    private val json = Json { ignoreUnknownKeys = true }

    private fun cliRoot(home: File): File =
        File(home, ".gemini/antigravity-cli")

    fun lastForWorkspace(cwd: String?, home: File = File(System.getProperty("user.home"))): String? {
        val workspace = normalizeWorkspace(cwd) ?: return null
        val map = readLastConversations(home)
        return map[workspace]
            ?: map[File(workspace).canonicalPath]
            ?: map[File(workspace).absolutePath]
    }

    /**
     * Resolve a resume target for [task].
     *
     * Only trusts a stored [AgentTask.vendorSessionId], and only once the
     * conversation's transcript is confirmed to actually contain this task's
     * prompt. Matching across `agy`'s history by prompt text is deliberately
     * not done here: two chats can share a prefix (e.g. both starting
     * "hello"), and a fuzzy match would silently resume the wrong thread. A
     * missing or unverifiable id means capture-at-launch failed — that is a
     * separate bug to fix, not something to guess around.
     */
    fun resolveForTask(task: AgentTask, home: File = File(System.getProperty("user.home"))): String? {
        val stored = task.vendorSessionId?.takeIf { it.isNotBlank() } ?: return null
        return stored.takeIf { conversationContainsPrompt(it, task.prompt, home) }
    }

    fun conversationContainsPrompt(
        conversationId: String,
        prompt: String,
        home: File = File(System.getProperty("user.home")),
    ): Boolean {
        val needle = firstLine(prompt) ?: return false
        val transcript = File(
            cliRoot(home),
            "brain/$conversationId/.system_generated/logs/transcript.jsonl",
        )
        if (transcript.isFile) {
            return runCatching { transcript.readText().contains(needle, ignoreCase = true) }.getOrDefault(false)
        }
        // Fall back to history rows tagged with this conversation id.
        val history = File(cliRoot(home), "history.jsonl")
        if (!history.isFile) return false
        return history.readLines().any { line ->
            val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@any false
            val id = obj["conversationId"]?.jsonPrimitive?.contentOrNull
            if (id != conversationId) return@any false
            val display = obj["display"]?.jsonPrimitive?.contentOrNull.orEmpty()
            promptMatches(display, needle)
        }
    }

    /**
     * Poll until agy records a conversation created by this launch, never
     * returning [before] (the previous workspace conversation). Bounded by
     * recency ([startedAtMillis]) and, when [launchedPrompt] is given, by
     * confirming the one time-bound candidate's own transcript actually
     * contains it — never by searching prior history for a text match.
     */
    fun awaitNewConversationId(
        cwd: String?,
        before: String?,
        launchedPrompt: String?,
        startedAtMillis: Long,
        home: File = File(System.getProperty("user.home")),
        attempts: Int = 60,
        delayMs: Long = 250,
    ): String? {
        repeat(attempts) {
            resolveAfterLaunch(cwd, before, launchedPrompt, startedAtMillis, home)?.let { return it }
            Thread.sleep(delayMs)
        }
        return resolveAfterLaunch(cwd, before, launchedPrompt, startedAtMillis, home)
    }

    private fun resolveAfterLaunch(
        cwd: String?,
        before: String?,
        launchedPrompt: String?,
        startedAtMillis: Long,
        home: File,
    ): String? {
        lastForWorkspace(cwd, home)?.takeIf { it.isNotBlank() && it != before }?.let { return it }
        val newest = newestConversationCreatedAfter(startedAtMillis, home)?.takeIf { it != before } ?: return null
        if (launchedPrompt != null && !conversationContainsPrompt(newest, launchedPrompt, home)) return null
        return newest
    }

    private fun newestConversationCreatedAfter(startedAtMillis: Long, home: File): String? {
        val dir = File(cliRoot(home), "conversations")
        if (!dir.isDirectory) return null
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".db") && !it.name.contains("-wal") && !it.name.contains("-shm") }
            ?.filter { it.lastModified() >= startedAtMillis - 1_000 }
            ?.maxByOrNull { it.lastModified() }
            ?.name
            ?.removeSuffix(".db")
    }

    private fun readLastConversations(home: File): Map<String, String> {
        val file = File(cliRoot(home), "cache/last_conversations.json")
        if (!file.isFile) return emptyMap()
        val root = runCatching { json.parseToJsonElement(file.readText()) }.getOrNull() as? JsonObject
            ?: return emptyMap()
        return root.mapNotNull { (key, value) ->
            value.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }?.let { key to it }
        }.toMap()
    }

    private fun normalizeWorkspace(cwd: String?): String? {
        val raw = cwd?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")
        return runCatching { File(raw).canonicalPath }.getOrElse { raw }
    }

    private fun firstLine(prompt: String): String? =
        prompt.trim().lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun promptMatches(display: String, needle: String): Boolean {
        val d = display.trim()
        val n = needle.trim()
        if (d.isEmpty() || n.isEmpty()) return false
        return d.equals(n, ignoreCase = true) ||
            d.startsWith(n.take(80), ignoreCase = true) ||
            n.startsWith(d, ignoreCase = true)
    }
}
