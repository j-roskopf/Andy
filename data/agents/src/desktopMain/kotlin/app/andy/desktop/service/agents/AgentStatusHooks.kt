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

/**
 * Installs vendor lifecycle hooks that append state to `.andy/<taskId>/status.json`
 * via the user-global `~/.andy/bin/andy-status-hook.sh`. Each launched agent session
 * sets `ANDY_TASK_ID` in its environment; `.andy/active-task` remains a fallback.
 *
 * Preferred status mapping (when the vendor emits the event):
 * - working ← prompt submit / pre-invocation
 * - done ← turn stop / idle-complete notifications
 * - blocked ← permission / ask-user
 *
 * **Badge authority** is screen-manifest scrape only ([AgentStatusTracker] — Herdr parity).
 * Hooks do not drive Working/Done/Blocked in the UI; they write optional `status.json`
 * artifacts for MCP/debug. Merge preserves user hooks and replaces only `andy-status-hook`
 * entries per event.
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
        AgentKind.OpenCode -> installOpenCodeStatusHooks(worktreeOrCwd, artifactDir)
        AgentKind.Pi -> installPiStatusHooks(worktreeOrCwd, artifactDir)
        AgentKind.Antigravity, AgentKind.Hermes, AgentKind.OpenClaw, AgentKind.Goose,
        AgentKind.Ollama, AgentKind.LMStudio -> Unit
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
    gate: String = "none",
): String {
    val base = "${AndyStatusHookInstaller.STABLE_HOOK_COMMAND} $status"
    val withRespond = if (respond == "none" && gate == "none") base else "$base $respond"
    return if (gate == "none") withRespond else "$withRespond $gate"
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

private fun writeHooksIfChanged(file: File, merged: JsonObject?) {
    if (merged == null) return
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
 * working ← sessionStart + beforeSubmitPrompt; done ← stop (status completed|aborted).
 * No reliable permission-wait hook — blocked stays on screen scrape.
 */
fun installCursorStatusHooks(worktreeOrCwd: File, artifactDir: File) {
    if (shouldSkipProjectHooks(worktreeOrCwd)) return

    val hooksDir = File(worktreeOrCwd, ".cursor").absoluteFile.normalize()
    hooksDir.mkdirs()
    prepareStatusHooks(artifactDir)

    val workingCmd = statusHookCommand("working", respond = "empty")
    val doneCmd = statusHookCommand("done", respond = "empty", gate = "completed")
    val andyHooks = JsonObject(
        mapOf(
            "sessionStart" to JsonArray(listOf(cursorCommandEntry(workingCmd))),
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
 * Returns null when [settingsFile] exists but cannot be parsed — caller must not overwrite.
 */
internal fun mergeClaudeStyleEventHooks(
    settingsFile: File,
    andyEventHooks: JsonObject,
): JsonObject? {
    val existingRoot = when (val read = readExistingHooksRoot(settingsFile)) {
        HooksFileRead.Missing -> null
        HooksFileRead.Invalid -> return null
        is HooksFileRead.Ok -> read.root
    }
    val root = mutableJsonMap(existingRoot)
    val existingEvents = mutableJsonMap(root["hooks"] as? JsonObject)

    stripAndyHooksFromAllEvents(existingEvents)

    for ((event, andyMatchers) in andyEventHooks) {
        val current = (existingEvents[event] as? JsonArray)?.toList().orEmpty()
        val preserved = current.filterNot(::jsonContainsAndyHook)
        val andyList = (andyMatchers as? JsonArray)?.toList().orEmpty()
        existingEvents[event] = JsonArray(preserved + andyList)
    }

    root["hooks"] = JsonObject(existingEvents)
    return JsonObject(root)
}

internal fun mergeCursorHooks(hooksFile: File, andyEventHooks: JsonObject): JsonObject? {
    val existingRoot = when (val read = readExistingHooksRoot(hooksFile)) {
        HooksFileRead.Missing -> null
        HooksFileRead.Invalid -> return null
        is HooksFileRead.Ok -> read.root
    }
    val root = mutableJsonMap(existingRoot)
    if (!root.containsKey("version")) root["version"] = JsonPrimitive(1)
    val existingEvents = mutableJsonMap(root["hooks"] as? JsonObject)

    stripAndyHooksFromAllEvents(existingEvents)

    for ((event, andyMatchers) in andyEventHooks) {
        val current = (existingEvents[event] as? JsonArray)?.toList().orEmpty()
        val preserved = current.filterNot(::jsonContainsAndyHook)
        val andyList = (andyMatchers as? JsonArray)?.toList().orEmpty()
        existingEvents[event] = JsonArray(preserved + andyList)
    }
    root["hooks"] = JsonObject(existingEvents)
    return JsonObject(root)
}


/**
 * OpenCode: project plugin `.opencode/plugins/andy-status.js`.
 * working ← session.start / tool.execute; done ← session.idle; blocked ← permission.
 */
fun installOpenCodeStatusHooks(worktreeOrCwd: File, artifactDir: File) {
    if (shouldSkipProjectHooks(worktreeOrCwd)) return
    prepareStatusHooks(artifactDir)
    AndyOpenCodePluginInstaller.ensureInstalled(worktreeOrCwd)
}

/**
 * Pi: global Andy extension loaded via `-e` (see [AndyPiExtensionInstaller]).
 * Still writes `.andy/active-task` so the hook script can resolve the task.
 */
fun installPiStatusHooks(worktreeOrCwd: File, artifactDir: File) {
    prepareStatusHooks(artifactDir)
    AndyPiExtensionInstaller.ensureInstalled()
}

private sealed interface HooksFileRead {
    data object Missing : HooksFileRead
    data object Invalid : HooksFileRead
    data class Ok(val root: JsonObject) : HooksFileRead
}

private fun readExistingHooksRoot(file: File): HooksFileRead {
    if (!file.isFile) return HooksFileRead.Missing
    return runCatching { hooksJson.parseToJsonElement(file.readText()).jsonObject }
        .fold(
            onSuccess = { HooksFileRead.Ok(it) },
            onFailure = { HooksFileRead.Invalid },
        )
}

private fun stripAndyHooksFromAllEvents(existingEvents: MutableMap<String, JsonElement>) {
    for (event in existingEvents.keys.toList()) {
        val current = existingEvents[event] as? JsonArray ?: continue
        val preserved = current.filterNot(::jsonContainsAndyHook)
        if (preserved.isEmpty()) existingEvents.remove(event)
        else existingEvents[event] = JsonArray(preserved)
    }
}

private fun mutableJsonMap(source: JsonObject?): MutableMap<String, JsonElement> =
    source?.entries?.associateTo(mutableMapOf()) { it.key to it.value } ?: mutableMapOf()

private fun jsonContainsAndyHook(element: JsonElement): Boolean =
    element.toString().contains(ANDY_HOOK_MARKER)
