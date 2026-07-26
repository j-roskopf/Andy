package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

private val hooksJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

private const val ANDY_HOOK_MARKER = "andy-status-hook"
private const val ANTIGRAVITY_HOOK_NAME = "andy-status"

/**
 * Writes lifecycle hooks that append state to `.andy/<taskId>/status.json`
 * via the user-global `~/.andy/bin/andy-status-hook.sh` and `.andy/active-task`.
 *
 * Preferred status mapping (when the vendor emits the event):
 * - working ← prompt submit / pre-invocation
 * - done ← turn stop / idle-complete notifications
 * - blocked ← permission / ask-user
 *
 * Screen scrape remains the fallback when hooks are absent (notably Cursor
 * approval chrome, which has no permission-wait hook).
 */
fun installStatusSignals(
    agent: AgentKind,
    worktreeOrCwd: File,
    artifactDir: File,
) {
    when (agent) {
        AgentKind.ClaudeCode -> installClaudeStatusHooks(worktreeOrCwd, artifactDir)
        AgentKind.Cursor -> installCursorStatusHooks(worktreeOrCwd, artifactDir)
        AgentKind.Codex -> installCodexStatusHooks(worktreeOrCwd, artifactDir)
        AgentKind.Antigravity -> installAntigravityStatusHooks(worktreeOrCwd, artifactDir)
    }
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

private fun statusHookCommand(
    status: String,
    respond: String = "none",
): String {
    val base = "${AndyStatusHookInstaller.STABLE_HOOK_COMMAND} $status"
    return if (respond == "none") base else "$base $respond"
}

private fun prepareStatusHooks(artifactDir: File) {
    AndyStatusHookInstaller.ensureInstalled()
    AndyStatusHookInstaller.writeActiveTask(artifactDir)
}

private fun shouldSkipProjectHooks(worktreeOrCwd: File): Boolean {
    val home = File(System.getProperty("user.home")).absoluteFile.normalize()
    val cwd = worktreeOrCwd.absoluteFile.normalize()
    return cwd == home
}

private fun writeHooksIfChanged(file: File, merged: JsonObject) {
    val encoded = hooksJson.encodeToString(JsonObject.serializer(), merged) + "\n"
    val existing = file.takeIf { it.isFile }?.readText()
    if (existing == encoded) return
    file.parentFile?.mkdirs()
    file.writeText(encoded)
}

/**
 * Claude Code: `.claude/settings.json` hooks.
 * working ← UserPromptSubmit
 * done ← Stop + Notification(idle_prompt|agent_completed)
 * blocked ← PermissionRequest + Notification(permission/elicitation/needs_input)
 */
fun installClaudeStatusHooks(worktreeOrCwd: File, artifactDir: File) {
    if (shouldSkipProjectHooks(worktreeOrCwd)) return

    val home = File(System.getProperty("user.home")).absoluteFile.normalize()
    val cwd = worktreeOrCwd.absoluteFile.normalize()
    val settingsDir = File(cwd, ".claude").absoluteFile.normalize()
    if (settingsDir == File(home, ".claude").absoluteFile.normalize()) return

    settingsDir.mkdirs()
    prepareStatusHooks(artifactDir)

    val workingCmd = statusHookCommand("working")
    val doneCmd = statusHookCommand("done")
    val blockedCmd = statusHookCommand("blocked")
    val andyHooks = JsonObject(
        mapOf(
            "UserPromptSubmit" to claudeCommandMatchers(workingCmd),
            "Stop" to claudeCommandMatchers(doneCmd),
            "PermissionRequest" to claudeCommandMatchers(blockedCmd),
            "Notification" to JsonArray(
                listOf(
                    claudeMatcherEntry(
                        matcher = "permission_prompt|agent_needs_input|elicitation_dialog",
                        command = blockedCmd,
                    ),
                    claudeMatcherEntry(
                        matcher = "idle_prompt|agent_completed",
                        command = doneCmd,
                    ),
                ),
            ),
        ),
    )

    val settings = File(settingsDir, "settings.json")
    writeHooksIfChanged(settings, mergeClaudeStyleEventHooks(settings, andyHooks))
}

/**
 * Cursor: `.cursor/hooks.json`.
 * working ← beforeSubmitPrompt; done ← stop.
 * No reliable permission-wait hook — blocked stays on screen scrape.
 */
fun installCursorStatusHooks(worktreeOrCwd: File, artifactDir: File) {
    if (shouldSkipProjectHooks(worktreeOrCwd)) return

    val hooksDir = File(worktreeOrCwd, ".cursor").absoluteFile.normalize()
    hooksDir.mkdirs()
    prepareStatusHooks(artifactDir)

    val workingCmd = statusHookCommand("working", respond = "empty")
    val doneCmd = statusHookCommand("done", respond = "empty")
    val andyHooks = JsonObject(
        mapOf(
            "beforeSubmitPrompt" to JsonArray(listOf(cursorCommandEntry(workingCmd))),
            "stop" to JsonArray(listOf(cursorCommandEntry(doneCmd))),
        ),
    )

    val hooksFile = File(hooksDir, "hooks.json")
    writeHooksIfChanged(hooksFile, mergeCursorHooks(hooksFile, andyHooks))
}

/**
 * Codex: `.codex/hooks.json`.
 * working ← UserPromptSubmit; done ← Stop; blocked ← PermissionRequest.
 * Stop/PermissionRequest expect JSON on stdout (`empty` respond mode).
 */
fun installCodexStatusHooks(worktreeOrCwd: File, artifactDir: File) {
    if (shouldSkipProjectHooks(worktreeOrCwd)) return

    val hooksDir = File(worktreeOrCwd, ".codex").absoluteFile.normalize()
    hooksDir.mkdirs()
    prepareStatusHooks(artifactDir)

    val workingCmd = statusHookCommand("working", respond = "empty")
    val doneCmd = statusHookCommand("done", respond = "empty")
    val blockedCmd = statusHookCommand("blocked", respond = "empty")
    val andyHooks = JsonObject(
        mapOf(
            "UserPromptSubmit" to codexCommandMatchers(workingCmd),
            "Stop" to codexCommandMatchers(doneCmd),
            "PermissionRequest" to codexCommandMatchers(blockedCmd),
        ),
    )

    val hooksFile = File(hooksDir, "hooks.json")
    writeHooksIfChanged(hooksFile, mergeClaudeStyleEventHooks(hooksFile, andyHooks))
}

/**
 * Antigravity: `.agents/hooks.json` named hook `andy-status`.
 * working ← PreInvocation; done ← Stop (fullyIdle path); blocked ← ask_* tools.
 */
fun installAntigravityStatusHooks(worktreeOrCwd: File, artifactDir: File) {
    if (shouldSkipProjectHooks(worktreeOrCwd)) return

    val agentsDir = File(worktreeOrCwd, ".agents").absoluteFile.normalize()
    agentsDir.mkdirs()
    prepareStatusHooks(artifactDir)

    val workingCmd = statusHookCommand("working", respond = "empty")
    val doneCmd = statusHookCommand("done", respond = "stop")
    val blockedCmd = statusHookCommand("blocked", respond = "allow")
    val andyHook = JsonObject(
        mapOf(
            "enabled" to JsonPrimitive(true),
            "PreInvocation" to JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("command"),
                            "command" to JsonPrimitive(workingCmd),
                            "timeout" to JsonPrimitive(5),
                        ),
                    ),
                ),
            ),
            "Stop" to JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("command"),
                            "command" to JsonPrimitive(doneCmd),
                            "timeout" to JsonPrimitive(5),
                        ),
                    ),
                ),
            ),
            "PreToolUse" to JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "matcher" to JsonPrimitive("ask_question|ask_permission"),
                            "hooks" to JsonArray(
                                listOf(
                                    JsonObject(
                                        mapOf(
                                            "type" to JsonPrimitive("command"),
                                            "command" to JsonPrimitive(blockedCmd),
                                            "timeout" to JsonPrimitive(5),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    val hooksFile = File(agentsDir, "hooks.json")
    writeHooksIfChanged(hooksFile, mergeAntigravityHooks(hooksFile, andyHook))
}

private fun claudeCommandMatchers(command: String): JsonArray =
    JsonArray(listOf(claudeMatcherEntry(matcher = null, command = command)))

private fun claudeMatcherEntry(matcher: String?, command: String): JsonObject {
    val fields = mutableMapOf<String, JsonElement>(
        "hooks" to JsonArray(
            listOf(
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("command"),
                        "command" to JsonPrimitive(command),
                    ),
                ),
            ),
        ),
    )
    if (matcher != null) fields["matcher"] = JsonPrimitive(matcher)
    return JsonObject(fields)
}

private fun codexCommandMatchers(command: String): JsonArray =
    JsonArray(
        listOf(
            JsonObject(
                mapOf(
                    "hooks" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("command"),
                                    "command" to JsonPrimitive(command),
                                    "timeout" to JsonPrimitive(5),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

private fun cursorCommandEntry(command: String): JsonObject =
    JsonObject(mapOf("command" to JsonPrimitive(command)))

/**
 * Merge Andy event hooks into a Claude/Codex-style `{ "hooks": { Event: [...] } }` file.
 * Preserves non-Andy entries; replaces prior Andy `andy-status-hook` entries per event.
 */
internal fun mergeClaudeStyleEventHooks(
    settingsFile: File,
    andyEventHooks: JsonObject,
): JsonObject {
    val existingRoot = if (settingsFile.isFile) {
        runCatching { hooksJson.parseToJsonElement(settingsFile.readText()).jsonObject }.getOrNull()
    } else {
        null
    }
    val root = mutableJsonMap(existingRoot)
    val existingEvents = mutableJsonMap(root["hooks"] as? JsonObject)

    for ((event, andyMatchers) in andyEventHooks) {
        val current = (existingEvents[event] as? JsonArray)?.toList().orEmpty()
        val preserved = current.filterNot(::jsonContainsAndyHook)
        val andyList = (andyMatchers as? JsonArray)?.toList().orEmpty()
        existingEvents[event] = JsonArray(preserved + andyList)
    }

    // Drop legacy Andy SubagentStop→done wiring that falsely marks the parent Done.
    val subagentStop = existingEvents["SubagentStop"] as? JsonArray
    if (subagentStop != null) {
        val preserved = subagentStop.filterNot(::jsonContainsAndyHook)
        if (preserved.isEmpty()) existingEvents.remove("SubagentStop")
        else existingEvents["SubagentStop"] = JsonArray(preserved)
    }

    root["hooks"] = JsonObject(existingEvents)
    return JsonObject(root)
}

internal fun mergeCursorHooks(hooksFile: File, andyEventHooks: JsonObject): JsonObject {
    val existingRoot = if (hooksFile.isFile) {
        runCatching { hooksJson.parseToJsonElement(hooksFile.readText()).jsonObject }.getOrNull()
    } else {
        null
    }
    val root = mutableJsonMap(existingRoot)
    if (!root.containsKey("version")) root["version"] = JsonPrimitive(1)
    val existingEvents = mutableJsonMap(root["hooks"] as? JsonObject)

    for ((event, andyMatchers) in andyEventHooks) {
        val current = (existingEvents[event] as? JsonArray)?.toList().orEmpty()
        val preserved = current.filterNot(::jsonContainsAndyHook)
        val andyList = (andyMatchers as? JsonArray)?.toList().orEmpty()
        existingEvents[event] = JsonArray(preserved + andyList)
    }
    root["hooks"] = JsonObject(existingEvents)
    return JsonObject(root)
}

internal fun mergeAntigravityHooks(hooksFile: File, andyHook: JsonObject): JsonObject {
    val existingRoot = if (hooksFile.isFile) {
        runCatching { hooksJson.parseToJsonElement(hooksFile.readText()).jsonObject }.getOrNull()
    } else {
        null
    }
    val root = mutableJsonMap(existingRoot)
    root[ANTIGRAVITY_HOOK_NAME] = andyHook
    return JsonObject(root)
}

private fun mutableJsonMap(source: JsonObject?): MutableMap<String, JsonElement> =
    source?.entries?.associateTo(mutableMapOf()) { it.key to it.value } ?: mutableMapOf()

private fun jsonContainsAndyHook(element: JsonElement): Boolean =
    element.toString().contains(ANDY_HOOK_MARKER)
