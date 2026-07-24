package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentSessionStatus
import app.andy.model.AgentTask
import app.andy.model.AgentTaskStatus
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

    if (task.status == AgentTaskStatus.WaitingForInput) {
        return task
    }
    if (task.status == AgentTaskStatus.Paused) {
        return if (inferCompletedTurn(task.agent, artifactDir, scrollback)) {
            task.asCompletedTurn()
        } else {
            task
        }
    }

    val wasActive = task.status == AgentTaskStatus.Running || task.status == AgentTaskStatus.Queued
    if (!wasActive) return task

    if (task.status == AgentTaskStatus.Queued) {
        return task.copy(
            status = AgentTaskStatus.Unknown,
            finishedAtMillis = task.finishedAtMillis ?: System.currentTimeMillis(),
        )
    }

    return when {
        inferCompletedTurn(task.agent, artifactDir, scrollback) -> task.asCompletedTurn()
        inferPausedAtPrompt(task.agent, artifactDir, scrollback) -> task.copy(
            status = AgentTaskStatus.Paused,
            finishedAtMillis = task.finishedAtMillis ?: System.currentTimeMillis(),
        )
        else -> task.copy(
            status = AgentTaskStatus.Unknown,
            finishedAtMillis = task.finishedAtMillis ?: System.currentTimeMillis(),
        )
    }
}

internal fun inferCompletedTurn(
    agent: AgentKind,
    artifactDir: File,
    scrollback: String,
    liveSessionStatus: AgentSessionStatus? = null,
): Boolean {
    if (scrollbackLooksBlocked(agent, scrollback)) return false
    if (liveSessionStatus == AgentSessionStatus.Done) return true
    return readLatestHookStatus(artifactDir) == AgentSessionStatus.Done
}

/**
 * True when a workflow [ProjectWorkflowStage.Build] turn has finished but the
 * interactive CLI is still alive at its input prompt (Cursor, Codex, etc.).
 */
internal fun inferWorkflowBuildTurnComplete(
    agent: AgentKind,
    artifactDir: File,
    scrollback: String,
    liveSessionStatus: AgentSessionStatus?,
    sawWorking: Boolean,
): Boolean {
    if (scrollbackLooksBlocked(agent, scrollback)) return false
    if (liveSessionStatus == AgentSessionStatus.Working || liveSessionStatus == AgentSessionStatus.Blocked) {
        return false
    }
    if (inferCompletedTurn(agent, artifactDir, scrollback, liveSessionStatus)) return true
    if (!sawWorking) return false
    return liveSessionStatus == AgentSessionStatus.Idle &&
        scrollbackLooksIdleAtPrompt(agent, scrollback)
}

internal fun inferPausedAtPrompt(
    agent: AgentKind,
    artifactDir: File,
    scrollback: String,
    liveSessionStatus: AgentSessionStatus? = null,
): Boolean {
    if (inferCompletedTurn(agent, artifactDir, scrollback, liveSessionStatus)) return false
    if (scrollbackLooksBlocked(agent, scrollback)) return false

    when (liveSessionStatus) {
        AgentSessionStatus.Working -> return false
        AgentSessionStatus.Blocked -> return false
        AgentSessionStatus.Idle, AgentSessionStatus.Done -> {
            return scrollbackLooksIdleAtPrompt(agent, scrollback)
        }
        null -> Unit
    }

    if (!scrollbackLooksIdleAtPrompt(agent, scrollback)) return false

    return when (readLatestHookStatus(artifactDir)) {
        AgentSessionStatus.Working, AgentSessionStatus.Blocked -> false
        else -> true
    }
}

private fun AgentTask.asCompletedTurn(): AgentTask = copy(
    status = AgentTaskStatus.Completed,
    exitCode = exitCode ?: 0,
    finishedAtMillis = finishedAtMillis ?: System.currentTimeMillis(),
    unread = true,
)

internal fun readLatestHookStatus(artifactDir: File): AgentSessionStatus? {
    val file = File(artifactDir, "status.json")
    if (!file.isFile) return null
    val parsed = file.readLines()
        .asReversed()
        .mapNotNull { line -> line.takeIf { it.isNotBlank() }?.let(::parseStatusJson) }
    val latest = parsed.firstOrNull() ?: return null
    if (latest != AgentSessionStatus.Blocked) return latest
    // Permission-mode notifications append blocked after Stop already wrote done.
    return parsed.drop(1).firstOrNull { it == AgentSessionStatus.Done } ?: latest
}

internal fun scrollbackLooksBlocked(agent: AgentKind, scrollback: String): Boolean =
    bufferLooksBlocked(agent, scrollback)

internal fun scrollbackLooksIdleAtPrompt(agent: AgentKind, scrollback: String): Boolean {
    if (scrollback.isBlank()) return false
    val tail = scrollback.takeLast(4000)
    val rules = scrapeRulesFor(agent)
    if (rules.idlePrompt.any { it.containsMatchIn(tail.takeLast(500)) }) return true
    return terminalBufferLooksReadyForInput(tail)
}

/** Shared with [DesktopAgentRunService.terminalLooksReadyForInput] for prompt detection. */
internal fun terminalBufferLooksReadyForInput(buffer: String): Boolean {
    val lines = buffer.lineSequence()
        .map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .toList()
        .takeLast(16)
    if (lines.isEmpty()) return false
    fun isChrome(line: String): Boolean {
        val lower = line.lowercase()
        return "shortcut" in lower ||
            line.contains('·') ||
            lower.startsWith("warning") ||
            (lower.startsWith("?") && "shortcut" in lower) ||
            lower.startsWith("╭") ||
            lower.startsWith("╰")
    }
    return lines.any { line ->
        if (isChrome(line)) return@any false
        val trimmed = line.trim()
        trimmed == ">" || trimmed == "›" || trimmed == "❯" ||
            trimmed.endsWith(">") ||
            Regex("""^>\s*$""").containsMatchIn(trimmed) ||
            Regex("""[❯›>]\s*$""").containsMatchIn(trimmed)
    }
}
