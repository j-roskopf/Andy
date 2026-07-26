package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import java.io.File

/**
 * Distinguishes a finished turn (hook/session "done") from "idle at prompt mid-session"
 * and "mid-turn when the app quit", using hook artifacts, scrollback, and live session status.
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
    return readLatestHookStatus(artifactDir) == AgentStatus.Done
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
    if (inferCompletedTurn(agent, artifactDir, scrollback, null)) return true
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
    if (inferCompletedTurn(agent, artifactDir, scrollback, liveSessionStatus)) return false
    if (scrollbackLooksBlocked(agent, scrollback)) return false

    when (liveSessionStatus) {
        AgentStatus.Working -> return false
        AgentStatus.Blocked -> return false
        AgentStatus.Done -> {
            return scrollbackLooksIdleAtPrompt(agent, scrollback)
        }
        else -> Unit
    }

    if (!scrollbackLooksIdleAtPrompt(agent, scrollback)) return false

    return when (readLatestHookStatus(artifactDir)) {
        AgentStatus.Working, AgentStatus.Blocked -> false
        else -> true
    }
}

private fun AgentTask.asCompletedTurn(): AgentTask = copy(
    status = AgentStatus.Done,
    exitCode = exitCode ?: 0,
    finishedAtMillis = finishedAtMillis ?: System.currentTimeMillis(),
    unread = true,
)

internal fun readLatestHookStatus(artifactDir: File): AgentStatus? {
    val file = File(artifactDir, "status.json")
    if (!file.isFile) return null
    val parsed = file.readLines()
        .asReversed()
        .mapNotNull { line -> line.takeIf { it.isNotBlank() }?.let(::parseStatusJson) }
    val latest = parsed.firstOrNull() ?: return null
    if (latest != AgentStatus.Blocked) return latest
    // Permission-mode notifications append blocked after Stop already wrote done.
    return parsed.drop(1).firstOrNull { it == AgentStatus.Done } ?: latest
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
