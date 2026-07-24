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

    private fun cliRoot(): File =
        File(System.getProperty("user.home"), ".gemini/antigravity-cli")

    fun lastForWorkspace(cwd: String?): String? {
        val workspace = normalizeWorkspace(cwd) ?: return null
        val map = readLastConversations()
        return map[workspace]
            ?: map[File(workspace).canonicalPath]
            ?: map[File(workspace).absolutePath]
    }

    /**
     * Best-effort match: newest history entry whose display text matches [prompt]
     * (prefix) in the same workspace.
     */
    fun findByPrompt(prompt: String, cwd: String?): String? {
        val needle = firstLine(prompt) ?: return null
        val workspace = normalizeWorkspace(cwd)
        val history = File(cliRoot(), "history.jsonl")
        if (!history.isFile) return null
        return history.readLines()
            .asReversed()
            .firstNotNullOfOrNull { line ->
                val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                    ?: return@firstNotNullOfOrNull null
                val display = obj["display"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (display.isEmpty() || !promptMatches(display, needle)) return@firstNotNullOfOrNull null
                val entryWorkspace = obj["workspace"]?.jsonPrimitive?.contentOrNull?.let(::normalizeWorkspace)
                if (workspace != null && entryWorkspace != null && workspace != entryWorkspace) {
                    return@firstNotNullOfOrNull null
                }
                obj["conversationId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            }
    }

    /**
     * Resolve a resume target for [task].
     *
     * Prefer a history match on the original Andy prompt. A stored
     * [AgentTask.vendorSessionId] is only trusted when the conversation's
     * transcript actually contains that prompt — otherwise a raced capture can
     * stamp the previous workspace conversation and resume the wrong thread.
     */
    fun resolveForTask(task: AgentTask): String? {
        val byPrompt = findByPrompt(task.prompt, task.cwd)
        if (byPrompt != null) return byPrompt
        val stored = task.vendorSessionId?.takeIf { it.isNotBlank() } ?: return null
        return stored.takeIf { conversationContainsPrompt(it, task.prompt) }
    }

    fun conversationContainsPrompt(conversationId: String, prompt: String): Boolean {
        val needle = firstLine(prompt) ?: return false
        val transcript = File(
            cliRoot(),
            "brain/$conversationId/.system_generated/logs/transcript.jsonl",
        )
        if (transcript.isFile) {
            return runCatching { transcript.readText().contains(needle, ignoreCase = true) }.getOrDefault(false)
        }
        // Fall back to history rows tagged with this conversation id.
        val history = File(cliRoot(), "history.jsonl")
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
     * Poll until agy records a conversation that belongs to [launchedPrompt],
     * never returning [before] (the previous workspace conversation).
     */
    fun awaitNewConversationId(
        cwd: String?,
        before: String?,
        launchedPrompt: String?,
        startedAtMillis: Long,
        attempts: Int = 60,
        delayMs: Long = 250,
    ): String? {
        repeat(attempts) {
            resolveAfterLaunch(cwd, before, launchedPrompt, startedAtMillis)?.let { return it }
            Thread.sleep(delayMs)
        }
        return resolveAfterLaunch(cwd, before, launchedPrompt, startedAtMillis)
    }

    private fun resolveAfterLaunch(
        cwd: String?,
        before: String?,
        launchedPrompt: String?,
        startedAtMillis: Long,
    ): String? {
        launchedPrompt?.let { prompt ->
            findByPrompt(prompt, cwd)?.takeIf { it != before }?.let { return it }
        }
        lastForWorkspace(cwd)?.takeIf { it.isNotBlank() && it != before }?.let { return it }
        newestConversationCreatedAfter(startedAtMillis)?.takeIf { it != before }?.let { return it }
        return null
    }

    private fun newestConversationCreatedAfter(startedAtMillis: Long): String? {
        val dir = File(cliRoot(), "conversations")
        if (!dir.isDirectory) return null
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".db") && !it.name.contains("-wal") && !it.name.contains("-shm") }
            ?.filter { it.lastModified() >= startedAtMillis - 1_000 }
            ?.maxByOrNull { it.lastModified() }
            ?.name
            ?.removeSuffix(".db")
    }

    private fun readLastConversations(): Map<String, String> {
        val file = File(cliRoot(), "cache/last_conversations.json")
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
