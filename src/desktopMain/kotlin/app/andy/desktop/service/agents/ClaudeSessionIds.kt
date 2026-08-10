package app.andy.desktop.service.agents

import app.andy.model.AgentTask
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Captures Claude Code session ids from `~/.claude/projects/<encoded-cwd>/<session>.jsonl`
 * for resume via `claude --resume <id>`.
 */
internal object ClaudeSessionIds {
    private val json = Json { ignoreUnknownKeys = true }
    private val SessionIdRegex =
        Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

    fun claudeHome(home: File = File(System.getProperty("user.home"))): File = File(home, ".claude")

    fun encodeProjectPath(cwd: String?): String? {
        val workspace = VendorSessionMatching.normalizeWorkspace(cwd) ?: return null
        return workspace.replace(Regex("""[/\\:]"""), "-")
    }

    fun projectDir(cwd: String?, home: File = File(System.getProperty("user.home"))): File? {
        val encoded = encodeProjectPath(cwd) ?: return null
        val dir = File(File(home, ".claude/projects"), encoded)
        return dir.takeIf { it.isDirectory }
    }

    fun resolveForTask(task: AgentTask, home: File = File(System.getProperty("user.home"))): String? {
        findByPrompt(task.prompt, task.cwd, home)?.let { return it }
        val stored = task.vendorSessionId?.takeIf { it.isNotBlank() } ?: return null
        return stored.takeIf { sessionContainsPrompt(it, task.prompt, task.cwd, home) }
    }

    fun findByPrompt(prompt: String, cwd: String?, home: File = File(System.getProperty("user.home"))): String? {
        val needle = VendorSessionMatching.firstLine(prompt) ?: return null
        val workspace = VendorSessionMatching.normalizeWorkspace(cwd)
        projectDir(cwd, home)?.listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension == "jsonl" }
            .sortedByDescending { it.lastModified() }
            .forEach { file ->
                val sessionId = file.nameWithoutExtension
                if (!SessionIdRegex.matches(sessionId)) return@forEach
                val head = runCatching { file.readText().take(16_000) }.getOrNull().orEmpty()
                if (head.isNotEmpty() && VendorSessionMatching.textContainsPrompt(head, prompt)) {
                    return sessionId
                }
            }
        // Fall back to live session registry (pid -> sessionId).
        val sessionsDir = File(claudeHome(home), "sessions")
        sessionsDir.listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension == "json" }
            .sortedByDescending { it.lastModified() }
            .forEach { file ->
                val obj = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
                    ?: return@forEach
                val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@forEach
                val sessionCwd = obj["cwd"]?.jsonPrimitive?.contentOrNull
                if (workspace != null && !VendorSessionMatching.cwdMatches(sessionCwd, cwd)) return@forEach
                val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (VendorSessionMatching.promptMatches(name, needle)) return sessionId
            }
        return null
    }

    fun sessionContainsPrompt(
        sessionId: String,
        prompt: String,
        cwd: String?,
        home: File = File(System.getProperty("user.home")),
    ): Boolean {
        projectDir(cwd, home)?.let { dir ->
            val file = File(dir, "$sessionId.jsonl")
            if (file.isFile) {
                val text = runCatching { file.readText().take(32_000) }.getOrNull().orEmpty()
                if (text.isNotEmpty() && VendorSessionMatching.textContainsPrompt(text, prompt)) return true
            }
        }
        // Session may live under a different encoded project path (worktree moves).
        val projectsRoot = File(claudeHome(home), "projects")
        if (projectsRoot.isDirectory) {
            projectsRoot.listFiles().orEmpty()
                .filter { it.isDirectory }
                .forEach { project ->
                    val file = File(project, "$sessionId.jsonl")
                    if (!file.isFile) return@forEach
                    val text = runCatching { file.readText().take(32_000) }.getOrNull().orEmpty()
                    if (text.isNotEmpty() && VendorSessionMatching.textContainsPrompt(text, prompt)) return true
                }
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
        val dir = projectDir(cwd, home) ?: return null
        var best: Pair<Long, String>? = null
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "jsonl" }
            .forEach { file ->
                val id = file.nameWithoutExtension
                if (!SessionIdRegex.matches(id) || id == before) return@forEach
                val mtime = file.lastModified()
                if (startedAtMillis > 0 && mtime + 5_000 < startedAtMillis) return@forEach
                if (launchedPrompt != null) {
                    val head = runCatching { file.readText().take(12_000) }.getOrNull().orEmpty()
                    if (head.isNotEmpty() && !VendorSessionMatching.textContainsPrompt(head, launchedPrompt)) {
                        return@forEach
                    }
                }
                if (best == null || mtime > best.first) best = mtime to id
            }
        return best?.second
    }
}
