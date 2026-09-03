package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentLaneKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import java.io.File

/**
 * Distinguishes a finished turn (hook/session "done") from "idle at prompt mid-session"
 * and "mid-turn when the app quit", using hook artifacts, scrollback, and live session status.
 *
 * Repairs lifecycle/status fields only — never [AgentTask.unread]. Attention badges are
 * applied separately via [statusNeedsUnread] when a reconcile actually changes status.
 */
internal fun recoverInterruptedTaskStatus(
    task: AgentTask,
    scrollbackFile: File,
): AgentTask {
    val scrollback = scrollbackFile.takeIf { it.isFile }?.readText().orEmpty()
    val artifactDir = AgentWorkflowArtifacts.dirFor(task.cwd?.let(::File), task.id)

    if (task.status == AgentStatus.Blocked) {
        return task
    }
    if (task.lane == AgentLaneKind.Acp && task.status == AgentStatus.Working) {
        return task.copy(
            status = AgentStatus.Error,
            interrupted = true,
            resumable = task.acpSessionId?.isNotBlank() == true,
            finishedAtMillis = task.finishedAtMillis ?: System.currentTimeMillis(),
            statusConfident = true,
        )
    }
    // Turn already finalized but badge left on Working (stale scrape / remount noise).
    if (task.finishedAtMillis != null && task.status == AgentStatus.Working) {
        return task.copy(
            status = AgentStatus.Done,
            resumable = task.resumable || task.exitCode == 0,
            statusConfident = true,
        )
    }
    if (task.resumable) {
        return if (inferCompletedTurn(task.agent, artifactDir, scrollback)) {
            task.asCompletedTurn()
        } else {
            task
        }
    }

    if (task.isQueued && task.finishedAtMillis == null) {
        return task.copy(
            status = AgentStatus.Error,
            interrupted = true,
            finishedAtMillis = task.finishedAtMillis ?: System.currentTimeMillis(),
        )
    }

    val wasActive = task.isActive || task.status == AgentStatus.Working
    if (!wasActive) return task

    return when {
        inferCompletedTurn(task.agent, artifactDir, scrollback) -> task.asCompletedTurn()
        inferPausedAtPrompt(task.agent, artifactDir, scrollback) -> task.copy(
            status = AgentStatus.Done,
            resumable = true,
            finishedAtMillis = task.finishedAtMillis ?: System.currentTimeMillis(),
        )
        else -> task.copy(
            status = AgentStatus.Error,
            interrupted = true,
            finishedAtMillis = task.finishedAtMillis ?: System.currentTimeMillis(),
        )
    }
}

internal fun inferCompletedTurn(
    agent: AgentKind,
    artifactDir: File,
    scrollback: String,
    liveSessionStatus: AgentStatus? = null,
): Boolean {
    if (scrollbackLooksBlocked(agent, scrollback)) return false
    if (liveSessionStatus == AgentStatus.Done) return true
    // Herdr screen-manifest: idle at prompt means the turn finished (no hook authority).
    return scrollbackLooksIdleAtPrompt(agent, scrollback)
}

/**
 * True when a workflow [ProjectWorkflowStage.Build] turn has finished but the
 * interactive CLI is still alive at its input prompt (Cursor, Codex, etc.).
 */
internal fun inferWorkflowBuildTurnComplete(
    agent: AgentKind,
    artifactDir: File,
    scrollback: String,
    liveSessionStatus: AgentStatus?,
    sawWorking: Boolean,
): Boolean {
    if (scrollbackLooksBlocked(agent, scrollback)) return false
    if (liveSessionStatus == AgentStatus.Working || liveSessionStatus == AgentStatus.Blocked) {
        return false
    }
    if (!sawWorking) return false
    return liveSessionStatus == AgentStatus.Done &&
        scrollbackLooksIdleAtPrompt(agent, scrollback)
}

internal fun inferPausedAtPrompt(
    agent: AgentKind,
    artifactDir: File,
    scrollback: String,
    liveSessionStatus: AgentStatus? = null,
): Boolean {
    // Idle-at-prompt is treated as a completed turn under screen-manifest authority.
    if (inferCompletedTurn(agent, artifactDir, scrollback, liveSessionStatus)) return false
    if (scrollbackLooksBlocked(agent, scrollback)) return false

    when (liveSessionStatus) {
        AgentStatus.Working, AgentStatus.Blocked -> return false
        else -> Unit
    }
    return false
}

private fun AgentTask.asCompletedTurn(): AgentTask = copy(
    status = AgentStatus.Done,
    exitCode = exitCode ?: 0,
    finishedAtMillis = finishedAtMillis ?: System.currentTimeMillis(),
)

/** Hook/title status.json reader — badge authority for Antigravity; MCP/debug for others. */
internal fun readLatestHookStatus(artifactDir: File): AgentStatus? {
    val file = File(artifactDir, "status.json")
    if (!file.isFile) return null
    return file.readLines()
        .asReversed()
        .firstNotNullOfOrNull { line -> line.takeIf { it.isNotBlank() }?.let(::parseStatusJson) }
}

internal fun scrollbackLooksBlocked(agent: AgentKind, scrollback: String): Boolean =
    bufferLooksBlocked(agent, scrollback)

internal fun scrollbackLooksIdleAtPrompt(agent: AgentKind, scrollback: String): Boolean {
    if (scrollback.isBlank()) return false
    return bufferLooksIdle(agent, scrollback.takeLast(4000))
}

/** Shared with [DesktopAgentRunService.terminalLooksReadyForInput] for prompt detection. */
internal fun terminalBufferLooksReadyForInput(buffer: String): Boolean {
    fun isChrome(line: String): Boolean {
        val lower = line.lowercase()
        return "shortcut" in lower ||
            line.contains('·') ||
            lower.startsWith("warning") ||
            (lower.startsWith("?") && "shortcut" in lower) ||
            lower.startsWith("╭") ||
            lower.startsWith("╰")
    }
    // Only the bottom of the screen — leftover prompts higher in scrollback must not
    // count. Never use bare endsWith(">") (matches List<String>, HTML, etc.).
    val candidates = buffer.lineSequence()
        .map { it.trimEnd() }
        .filter { it.isNotBlank() && !isChrome(it) }
        .toList()
        .takeLast(2)
    if (candidates.isEmpty()) return false
    return candidates.any { line -> isExactPromptLine(line.trim()) }
}

/** Exact CLI prompt forms only — not code/generics that merely end with '>'. */
internal fun isExactPromptLine(trimmed: String): Boolean {
    if (trimmed == ">" || trimmed == "›" || trimmed == "❯") return true
    // Optional short shell-style name before the marker, e.g. "claude>" / "agy>".
    return Regex("""^[A-Za-z][A-Za-z0-9_-]{0,24}[❯›>]\s*$""").matches(trimmed) ||
        Regex("""^[❯›>]\s*$""").matches(trimmed)
}
