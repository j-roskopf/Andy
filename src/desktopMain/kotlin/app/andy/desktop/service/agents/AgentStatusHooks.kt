package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

private val hooksJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

private const val ANDY_HOOK_MARKER = "andy-status-hook"
private const val ANTIGRAVITY_HOOK_NAME = "andy-status"

/**
 * Prepares per-task artifact pointers for Andy.
 *
 * Badge status for Claude / Cursor / Codex / Antigravity is **screen-manifest only**
 * (Herdr session-identity agents — see https://herdr.dev/docs/agents/). Incomplete
 * vendor lifecycle hooks are intentionally not installed for Working/Blocked/Done;
 * they race Stop vs permissions and can revive Working after Done.
 *
 * This still writes `.andy/active-task` and ensures the optional MCP status helper
 * script exists. Any previously installed Andy status hooks are removed on the next
 * chat start so projects stop appending conflicting status.json lines.
 */
fun installStatusSignals(
    agent: AgentKind,
    worktreeOrCwd: File,
    artifactDir: File,
) {
    artifactDir.mkdirs()
    AndyStatusHookInstaller.ensureInstalled()
    AndyStatusHookInstaller.writeActiveTask(artifactDir)
    if (shouldSkipProjectHooks(worktreeOrCwd)) return
    removeAndyStatusHooks(agent, worktreeOrCwd)
}

/**
 * Ensures the global helper exists and points `.andy/active-task` at this task.
 * Kept for tests and callers that previously wrote a per-task script copy.
 */
internal fun installGenericStatusHookScript(artifactDir: File) {
    artifactDir.mkdirs()
    AndyStatusHookInstaller.ensureInstalled()
    AndyStatusHookInstaller.writeActiveTask(artifactDir)
}

private fun shouldSkipProjectHooks(worktreeOrCwd: File): Boolean {
    val home = File(System.getProperty("user.home")).absoluteFile.normalize()
    val cwd = worktreeOrCwd.absoluteFile.normalize()
    return cwd == home
}

/** Strip legacy Andy status-hook entries from vendor config so they cannot fight scrape. */
private fun removeAndyStatusHooks(agent: AgentKind, worktreeOrCwd: File) {
    when (agent) {
        AgentKind.ClaudeCode -> {
            val home = File(System.getProperty("user.home")).absoluteFile.normalize()
            val settingsDir = File(worktreeOrCwd, ".claude").absoluteFile.normalize()
            if (settingsDir == File(home, ".claude").absoluteFile.normalize()) return
            val settings = File(settingsDir, "settings.json")
            stripAndyHooksFromClaudeStyleFile(settings)
        }
        AgentKind.Codex -> stripAndyHooksFromClaudeStyleFile(File(worktreeOrCwd, ".codex/hooks.json"))
        AgentKind.Cursor -> stripAndyHooksFromCursorFile(File(worktreeOrCwd, ".cursor/hooks.json"))
        AgentKind.Antigravity -> stripAndyAntigravityHook(File(worktreeOrCwd, ".agents/hooks.json"))
    }
}

private fun stripAndyHooksFromClaudeStyleFile(file: File) {
    if (!file.isFile) return
    val root = runCatching { hooksJson.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return
    val events = mutableJsonMap(root["hooks"] as? JsonObject)
    if (events.isEmpty()) return
    var changed = false
    for (key in events.keys.toList()) {
        val current = (events[key] as? JsonArray)?.toList().orEmpty()
        val preserved = current.filterNot(::jsonContainsAndyHook)
        if (preserved.size != current.size) changed = true
        if (preserved.isEmpty()) events.remove(key) else events[key] = JsonArray(preserved)
    }
    if (!changed) return
    val next = JsonObject(mutableJsonMap(root).also { it["hooks"] = JsonObject(events) })
    file.writeText(hooksJson.encodeToString(JsonObject.serializer(), next) + "\n")
}

private fun stripAndyHooksFromCursorFile(file: File) {
    if (!file.isFile) return
    val root = runCatching { hooksJson.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return
    val events = mutableJsonMap(root["hooks"] as? JsonObject)
    if (events.isEmpty()) return
    var changed = false
    for (key in events.keys.toList()) {
        val current = (events[key] as? JsonArray)?.toList().orEmpty()
        val preserved = current.filterNot(::jsonContainsAndyHook)
        if (preserved.size != current.size) changed = true
        if (preserved.isEmpty()) events.remove(key) else events[key] = JsonArray(preserved)
    }
    if (!changed) return
    val next = JsonObject(mutableJsonMap(root).also { it["hooks"] = JsonObject(events) })
    file.writeText(hooksJson.encodeToString(JsonObject.serializer(), next) + "\n")
}

private fun stripAndyAntigravityHook(file: File) {
    if (!file.isFile) return
    val root = runCatching { hooksJson.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return
    if (!root.containsKey(ANTIGRAVITY_HOOK_NAME)) return
    val next = JsonObject(root.filterKeys { it != ANTIGRAVITY_HOOK_NAME })
    file.writeText(hooksJson.encodeToString(JsonObject.serializer(), next) + "\n")
}

private fun jsonContainsAndyHook(element: JsonElement): Boolean =
    element.toString().contains(ANDY_HOOK_MARKER)

private fun mutableJsonMap(obj: JsonObject?): MutableMap<String, JsonElement> =
    obj?.toMutableMap() ?: mutableMapOf()
