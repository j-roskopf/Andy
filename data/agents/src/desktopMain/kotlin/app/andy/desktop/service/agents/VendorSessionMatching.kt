package app.andy.desktop.service.agents

import java.io.File

/** Shared prompt/cwd matching for vendor session id capture and backfill. */
internal object VendorSessionMatching {
    fun normalizeWorkspace(cwd: String?): String? {
        val raw = cwd?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { File(raw).canonicalPath }.getOrElse { raw }
    }

    fun firstLine(prompt: String): String? =
        prompt.trim().lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    fun promptMatches(display: String, needle: String): Boolean {
        val d = display.trim()
        val n = needle.trim()
        if (d.isEmpty() || n.isEmpty()) return false
        return d.equals(n, ignoreCase = true) ||
            d.startsWith(n.take(80), ignoreCase = true) ||
            n.startsWith(d, ignoreCase = true) ||
            d.contains(n.take(80), ignoreCase = true) ||
            n.contains(d.take(80), ignoreCase = true)
    }

    fun textContainsPrompt(text: String, prompt: String): Boolean {
        val needle = firstLine(prompt) ?: return false
        return promptMatches(text, needle)
    }

    fun cwdMatches(sessionCwd: String?, taskCwd: String?): Boolean {
        val session = normalizeWorkspace(sessionCwd) ?: return taskCwd.isNullOrBlank()
        val task = normalizeWorkspace(taskCwd) ?: return true
        return session == task
    }
}
