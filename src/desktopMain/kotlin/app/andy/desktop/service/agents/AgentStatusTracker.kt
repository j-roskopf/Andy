package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentSessionStatus
import app.andy.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hybrid status: lifecycle hooks (authoritative) + PTY-buffer scrape (fallback).
 */
class AgentStatusTracker(
    private val scope: CoroutineScope,
    private val taskId: String,
    private val agent: AgentKind,
    private val artifactDir: File,
    private val session: TerminalSession,
    private val isTabSeen: () -> Boolean,
) {
    private val _status = MutableStateFlow(AgentSessionStatus.Working)
    val status: StateFlow<AgentSessionStatus> = _status.asStateFlow()

    private val hook = HookStatusSource(artifactDir)
    private val scrape = ScrapeStatusSource(agent)
    private val closed = AtomicBoolean(false)
    private var jobs: List<Job> = emptyList()

    fun start() {
        artifactDir.mkdirs()
        jobs = listOf(
            scope.launch { hook.watch { publish() } },
            scope.launch {
                session.bufferSnapshots.collect { buffer ->
                    scrape.onBuffer(buffer)
                    publish()
                }
            },
            scope.launch {
                while (isActive && !closed.get()) {
                    delay(500)
                    scrape.tickQuiescence()
                    if (!session.isAlive) {
                        publish(processExited = true)
                    } else {
                        publish()
                    }
                }
            },
        )
    }

    fun markSeen() {
        if (_status.value == AgentSessionStatus.Done) {
            _status.value = AgentSessionStatus.Idle
        }
    }

    fun markPhaseFinished() {
        publish(phaseFinished = true)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        jobs.forEach { it.cancel() }
    }

    private fun publish(processExited: Boolean = false, phaseFinished: Boolean = false) {
        if (closed.get()) return
        val hookStatus = hook.latest()
        val scrapeStatus = scrape.latest()
        val activelyWorking = scrape.indicatesWorking()
        val quiescentAtPrompt = scrape.isQuiescentAtPrompt()
        val currentlyBlocked = scrape.isCurrentlyBlocked()
        val next = when {
            processExited || phaseFinished -> {
                if (isTabSeen()) AgentSessionStatus.Idle else AgentSessionStatus.Done
            }
            currentlyBlocked || scrapeStatus == AgentSessionStatus.Blocked ||
                (hookStatus == AgentSessionStatus.Blocked && !activelyWorking && currentlyBlocked) ->
                AgentSessionStatus.Blocked
            activelyWorking -> AgentSessionStatus.Working
            hookStatus == AgentSessionStatus.Done && (scrapeStatus == AgentSessionStatus.Idle || quiescentAtPrompt) -> {
                if (isTabSeen()) AgentSessionStatus.Idle else AgentSessionStatus.Done
            }
            scrapeStatus == AgentSessionStatus.Idle || quiescentAtPrompt -> AgentSessionStatus.Idle
            hookStatus == AgentSessionStatus.Working || scrapeStatus == AgentSessionStatus.Working ->
                AgentSessionStatus.Working
            hookStatus == AgentSessionStatus.Idle ->
                AgentSessionStatus.Idle
            else -> AgentSessionStatus.Working
        }
        if (_status.value != next) _status.value = next
    }
}

/** Reads `.andy/<taskId>/status.json` written by CLI lifecycle hooks. */
class HookStatusSource(
    private val artifactDir: File,
) {
    private val statusFile = File(artifactDir, "status.json")
    @Volatile private var latest: AgentSessionStatus? = null

    fun latest(): AgentSessionStatus? = latest

    suspend fun watch(onChange: () -> Unit) {
        var lastModified = -1L
        while (true) {
            if (statusFile.isFile) {
                val modified = statusFile.lastModified()
                if (modified != lastModified) {
                    lastModified = modified
                    latest = readLatestHookStatus(artifactDir)
                    onChange()
                }
            }
            delay(400)
        }
    }
}

/**
 * Debounced PTY-buffer scrape: approval/question prompts → blocked;
 * output churn → working; quiescent at a prompt → idle.
 */
class ScrapeStatusSource(
    private val agent: AgentKind,
) {
    private val rules = scrapeRulesFor(agent)
    @Volatile private var lastBuffer: String = ""
    @Volatile private var lastChangeAt: Long = System.currentTimeMillis()
    @Volatile private var latest: AgentSessionStatus = AgentSessionStatus.Working

    fun latest(): AgentSessionStatus = latest

    fun onBuffer(buffer: String) {
        val trimmed = buffer.takeLast(SCRAPE_BUFFER_CHARS)
        if (trimmed != lastBuffer) {
            lastBuffer = trimmed
            lastChangeAt = System.currentTimeMillis()
            latest = when {
                bufferLooksBlocked(agent, trimmed) -> AgentSessionStatus.Blocked
                bufferLooksWorking(agent, trimmed) -> AgentSessionStatus.Working
                else -> AgentSessionStatus.Working
            }
        }
    }

    fun isRecentlyActive(thresholdMs: Long = 2_000): Boolean =
        System.currentTimeMillis() - lastChangeAt < thresholdMs

    /** Recent PTY churn or a vendor spinner/status line still visible in the tail. */
    fun indicatesWorking(): Boolean =
        isRecentlyActive() || bufferLooksWorking(agent, lastBuffer)

    fun tickQuiescence(idleAfterMs: Long = 4_000) {
        val quiet = System.currentTimeMillis() - lastChangeAt >= idleAfterMs
        val tail = lastBuffer.takeLast(SCRAPE_BUFFER_CHARS)
        val currentlyBlocked = bufferLooksBlocked(agent, tail)
        if (latest == AgentSessionStatus.Blocked) {
            if (quiet && !currentlyBlocked && !bufferLooksWorking(agent, tail)) {
                latest = AgentSessionStatus.Idle
            }
            return
        }
        if (!quiet || bufferLooksWorking(agent, tail)) return
        if (rules.idlePrompt.any { it.containsMatchIn(tail.takeLast(500)) }) {
            latest = AgentSessionStatus.Idle
        } else if (lastBuffer.isNotBlank()) {
            // Quiescent without a clear prompt still counts as idle for badge UX.
            latest = AgentSessionStatus.Idle
        }
    }

    fun isCurrentlyBlocked(): Boolean = bufferLooksBlocked(agent, lastBuffer)

    /** True when output has settled at an input prompt without approval UI in the tail. */
    fun isQuiescentAtPrompt(idleAfterMs: Long = 4_000): Boolean {
        val quiet = System.currentTimeMillis() - lastChangeAt >= idleAfterMs
        if (!quiet || lastBuffer.isBlank()) return false
        val tail = lastBuffer.takeLast(SCRAPE_BUFFER_CHARS)
        if (bufferLooksBlocked(agent, tail) || bufferLooksWorking(agent, tail)) return false
        return rules.idlePrompt.any { it.containsMatchIn(tail.takeLast(500)) } ||
            terminalBufferLooksReadyForInput(tail)
    }
}

data class ScrapeRules(
    val blocked: List<Regex>,
    val idlePrompt: List<Regex>,
    val working: List<Regex> = emptyList(),
)

private const val SCRAPE_BUFFER_CHARS = 4_000
private const val SCRAPE_BLOCKED_TAIL_CHARS = 800

internal fun bufferLooksBlocked(agent: AgentKind, buffer: String): Boolean {
    if (buffer.isBlank()) return false
    val tail = buffer.takeLast(SCRAPE_BLOCKED_TAIL_CHARS)
    return scrapeRulesFor(agent).blocked.any { it.containsMatchIn(tail) }
}

internal fun bufferLooksWorking(agent: AgentKind, buffer: String): Boolean {
    if (buffer.isBlank()) return false
    val tail = buffer.takeLast(SCRAPE_BLOCKED_TAIL_CHARS)
    return scrapeRulesFor(agent).working.any { it.containsMatchIn(tail) }
}

fun scrapeRulesFor(agent: AgentKind): ScrapeRules = when (agent) {
    AgentKind.ClaudeCode -> ScrapeRules(
        blocked = listOf(
            Regex("""Do you want to proceed\?""", RegexOption.IGNORE_CASE),
            Regex("""\(y/n\)""", RegexOption.IGNORE_CASE),
            Regex("""Allow this action""", RegexOption.IGNORE_CASE),
            Regex("""trust this folder""", RegexOption.IGNORE_CASE),
            Regex("""Quick safety check""", RegexOption.IGNORE_CASE),
            Regex("""Yes, I accept""", RegexOption.IGNORE_CASE),
            Regex("""No, exit""", RegexOption.IGNORE_CASE),
        ),
        idlePrompt = listOf(
            Regex(""">\s*$"""),
            Regex("""╭─"""),
        ),
        working = listOf(
            Regex("""Perambulat""", RegexOption.IGNORE_CASE),
            Regex("""thinking more""", RegexOption.IGNORE_CASE),
            Regex("""✻\s+\w+ing\b"""),
            Regex("""↓\s*\d+\s*tokens""", RegexOption.IGNORE_CASE),
        ),
    )
    AgentKind.Codex -> ScrapeRules(
        blocked = listOf(
            Regex("""Allow command""", RegexOption.IGNORE_CASE),
            Regex("""Approve this command""", RegexOption.IGNORE_CASE),
            Regex("""\(y/n\)""", RegexOption.IGNORE_CASE),
        ),
        idlePrompt = listOf(Regex("""›\s*$"""), Regex(""">\s*$""")),
    )
    AgentKind.Cursor -> ScrapeRules(
        blocked = listOf(
            Regex("""Waiting for approval""", RegexOption.IGNORE_CASE),
            Regex("""\(y/n\)""", RegexOption.IGNORE_CASE),
        ),
        idlePrompt = listOf(Regex(""">\s*$""")),
    )
    AgentKind.Antigravity -> ScrapeRules(
        blocked = listOf(
            Regex("""\(y/n\)""", RegexOption.IGNORE_CASE),
            Regex("""Allow this action""", RegexOption.IGNORE_CASE),
        ),
        idlePrompt = listOf(Regex(""">\s*$""")),
    )
}

internal fun parseStatusJson(raw: String): AgentSessionStatus? {
    val normalized = raw.lowercase()
    return when {
        "blocked" in normalized -> AgentSessionStatus.Blocked
        "\"done\"" in normalized || "\"status\": \"done\"" in normalized || """"status":"done"""" in normalized ->
            AgentSessionStatus.Done
        "working" in normalized || "busy" in normalized -> AgentSessionStatus.Working
        "idle" in normalized -> AgentSessionStatus.Idle
        else -> null
    }
}

private val claudeSettingsJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

/**
 * Writes Claude Code hook config into the worktree so Stop/Notification hooks
 * append state to `.andy/<taskId>/status.json`.
 *
 * Never writes into the user's global `~/.claude/` — that path is reserved for
 * Claude's own settings, and an earlier string-template bug corrupted it.
 */
fun installClaudeStatusHooks(worktreeOrCwd: File, artifactDir: File) {
    val home = File(System.getProperty("user.home")).absoluteFile.normalize()
    val cwd = worktreeOrCwd.absoluteFile.normalize()
    if (cwd == home) return

    val settingsDir = File(cwd, ".claude").absoluteFile.normalize()
    val globalClaudeDir = File(home, ".claude").absoluteFile.normalize()
    if (settingsDir == globalClaudeDir) return

    settingsDir.mkdirs()
    artifactDir.mkdirs()

    val statusPath = File(artifactDir, "status.json").absolutePath
    val hookScript = File(artifactDir, "andy-status-hook.sh")
    hookScript.writeText(
        """
        #!/bin/sh
        # Andy-managed Claude status hook — do not edit.
        status="${'$'}{1:-done}"
        printf '{"status":"%s","at":%s}\n' "${'$'}status" "$(date +%s)" >> ${shellSingleQuote(statusPath)}
        """.trimIndent() + "\n",
    )
    hookScript.setExecutable(true)

    val doneCmd = "${shellSingleQuote(hookScript.absolutePath)} done"
    val blockedCmd = "${shellSingleQuote(hookScript.absolutePath)} blocked"
    val hooks = JsonObject(
        mapOf(
            "Stop" to hookMatchers(doneCmd),
            "SubagentStop" to hookMatchers(doneCmd),
            "Notification" to hookMatchers(blockedCmd),
        ),
    )

    val settings = File(settingsDir, "settings.json")
    val merged = mergeClaudeHooks(settings, hooks)
    settings.writeText(claudeSettingsJson.encodeToString(JsonObject.serializer(), merged) + "\n")
}

private fun hookMatchers(command: String) = JsonArray(
    listOf(
        JsonObject(
            mapOf(
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
            ),
        ),
    ),
)

private fun mergeClaudeHooks(settingsFile: File, hooks: JsonObject): JsonObject {
    if (!settingsFile.isFile) return JsonObject(mapOf("hooks" to hooks))
    val existing = runCatching {
        claudeSettingsJson.parseToJsonElement(settingsFile.readText()).jsonObject
    }.getOrNull() ?: return JsonObject(mapOf("hooks" to hooks))
    return JsonObject(existing.toMutableMap().apply { put("hooks", hooks) })
}

private fun shellSingleQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"
