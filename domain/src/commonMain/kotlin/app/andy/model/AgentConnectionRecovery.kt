package app.andy.model

/** Follow-up Andy sends after a provider stream stalls mid-turn. */
const val CONNECTION_STALL_RETRY_PROMPT = "Continue where you left off."

/**
 * Maximum automatic continuations without any durable agent progress between them.
 *
 * This deliberately bounds a broken provider connection. A later meaningful tool/message
 * update resets the count, so a real long-running task can recover more than once without
 * turning a persistent outage into a hot loop.
 */
const val MAX_CONNECTION_STALL_AUTO_RETRIES = 5

/** Capacity errors are not transport drops; retry fewer times and back off more slowly. */
const val MAX_RESOURCE_EXHAUSTED_AUTO_RETRIES = 2

/** Backoff before each automatic stall retry (multiplied by attempt number). */
const val CONNECTION_STALL_AUTO_RETRY_BACKOFF_MS = 1_000L

/** Longer backoff when Cursor reports provider capacity exhaustion. */
const val RESOURCE_EXHAUSTED_AUTO_RETRY_BACKOFF_MS = 3_000L

/** Never leave a healthy provider idle in a rapid reconnect loop. */
const val MAX_CONNECTION_STALL_AUTO_RETRY_BACKOFF_MS = 30_000L

/** Follow-up Andy sends when the user accepts a plan-mode turn and asks to implement. */
const val IMPLEMENT_PLAN_PROMPT = "Implement the plan."

enum class AgentConnectionRecoveryReason {
    Transport,
    ResourceExhausted,
}

/**
 * Durable automatic-recovery checkpoint. It is stored on [AgentTask], allowing `andyd` to
 * continue a pending recovery after the desktop client reconnects or the daemon restarts.
 */
data class AgentConnectionRecovery(
    val attemptsWithoutProgress: Int,
    val reason: AgentConnectionRecoveryReason,
    val nextRetryAtMillis: Long? = null,
    /** True only after the bounded automatic budget is spent. */
    val paused: Boolean = false,
)

/**
 * Cursor, Claude Code, and OpenCode ACP adapters surface a transport drop as a standalone
 * error line, an inline suffix appended to partial assistant text, or an RPC error — not as
 * a dedicated ACP stop reason. `cancelled` is also used for user stop, so text matching is
 * the reliable signal.
 *
 * Match a whole line with the provider stall prefix. Substring mentions, source quotes,
 * and stream chunks like `" stalled"` must not count.
 */
private val RETRIABLE_STALL_LINE = Regex(
    """(?i)^(?:internal\s+error:\s*)?(?:error:\s*)?(?:retriableerror:\s+(?:connection\s+stalled(?:\s+repeatedly)?|\[canceled\]\s+http/2\s+stream\s+closed(?:\b.*)?|\[resource_exhausted\](?:\s+error)?)|api\s+error:\s+connection\s+closed\s+mid-response(?:\.\s*the\s+response\s+above\s+may\s+be\s+incomplete\.)?|cannot\s+connect\s+to\s+api:\s+(?:econnreset|etimedout|econnrefused|epipe|the\s+socket\s+connection\s+was\s+closed\s+unexpectedly|fetch\s+failed|socket\s+hang\s+up)\b.*|type\s+validation\s+failed:\s+value:\s+\{"id":"","object":"","created":0,[^\n]*"choices":null\}\.?)\s*$""",
)

/**
 * Claude Code ACP sometimes appends its mid-response disconnect error directly onto the end
 * of the partial assistant text line without a preceding newline.
 */
private val INLINE_TRAILING_STALL_SUFFIX = Regex(
    """(?i)(?:internal\s+error:\s*)?(?:error:\s*)?api\s+error:\s+connection\s+closed\s+mid-response\.\s*the\s+response\s+above\s+may\s+be\s+incomplete\.\s*$""",
)

/**
 * OpenCode ACP surfaces upstream gateway drops that return an empty `{"choices":null}` payload
 * as a multi-line Zod `Type validation failed` block.
 */
private val OPENCODE_NULL_CHOICES_STALL_BLOCK = Regex(
    """(?is)^(?:internal\s+error:\s*)?(?:error:\s*)?type\s+validation\s+failed:\s+value:\s+\{"id":"","object":"","created":0,[^\n]*"choices":null\}\.?\s*(?:\n\s*error\s+message:\s*\[.*\]\s*)?$""",
)

private fun CharSequence.rawNonEmptyLines(): List<String> =
    lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()

/** Provider stall lines are flush-left. Indented or `>`-quoted examples are not. */
private fun String.isProviderStallLine(): Boolean {
    if (isEmpty()) return false
    val first = first()
    if (first == ' ' || first == '\t' || first == '>') return false
    return RETRIABLE_STALL_LINE.matches(trim())
}

private fun String.hasInlineTrailingProviderStall(): Boolean {
    if (isEmpty()) return false
    val first = first()
    if (first == ' ' || first == '\t' || first == '>') return false
    return INLINE_TRAILING_STALL_SUFFIX.containsMatchIn(trimEnd())
}

private fun CharSequence.isOpenCodeNullChoicesStallBlock(): Boolean {
    val lines = rawNonEmptyLines()
    if (lines.isEmpty()) return false
    val first = lines.first().first()
    if (first == ' ' || first == '\t' || first == '>') return false
    return OPENCODE_NULL_CHOICES_STALL_BLOCK.matches(trim())
}

/** True when [text] is only a provider stall error (blank lines allowed). */
fun CharSequence.isRetriableConnectionStallMessage(): Boolean {
    if (isOpenCodeNullChoicesStallBlock()) return true
    val lines = rawNonEmptyLines()
    return lines.isNotEmpty() && lines.all { it.isProviderStallLine() }
}

/** True when the last non-empty line (or trailing suffix) is a provider stall error. */
fun CharSequence.hasRetriableConnectionStallError(): Boolean {
    if (isOpenCodeNullChoicesStallBlock()) return true
    val lastLine = rawNonEmptyLines().lastOrNull() ?: return false
    return lastLine.isProviderStallLine() || lastLine.hasInlineTrailingProviderStall()
}

/** Drops a trailing stall error so partial output before the drop stays visible. */
fun CharSequence.stripTrailingConnectionStallError(): String {
    if (isOpenCodeNullChoicesStallBlock()) return ""
    val lines = toString().split('\n')
    val last = lines.indexOfLast { it.trim().isNotEmpty() }
    if (last < 0) return toString()
    val lastLine = lines[last].trimEnd()
    if (lastLine.isProviderStallLine()) {
        var end = last
        while (end > 0 && lines[end - 1].isBlank()) end--
        return lines.take(end).joinToString("\n").trimEnd()
    }
    if (lastLine.hasInlineTrailingProviderStall()) {
        val strippedLast = INLINE_TRAILING_STALL_SUFFIX.replaceFirst(lastLine, "").trimEnd()
        val updated = lines.take(last).toMutableList()
        if (strippedLast.isNotEmpty()) {
            updated += strippedLast
        } else {
            while (updated.isNotEmpty() && updated.last().isBlank()) {
                updated.removeAt(updated.lastIndex)
            }
        }
        return updated.joinToString("\n").trimEnd()
    }
    return toString()
}

fun List<AgentEvent>.hasRetriableConnectionStall(): Boolean =
    latestTurnStallTexts().any { it.hasRetriableConnectionStallError() }

/** True when the latest-turn stall is provider capacity exhaustion, which needs a longer pause. */
fun List<AgentEvent>.hasRetriableResourceExhausted(): Boolean =
    latestTurnStallTexts().any { it.hasRetriableResourceExhaustedError() }

/** Automatic continue prompts are kept in the log for turn boundaries, not shown in the transcript. */
fun CharSequence.isSilentConnectionRecoveryPrompt(): Boolean =
    trim() == CONNECTION_STALL_RETRY_PROMPT

/** Exponential, capped backoff for the next continuation attempt. */
fun connectionStallRetryBackoffMillis(
    attempt: Int,
    reason: AgentConnectionRecoveryReason,
): Long {
    val base = when (reason) {
        AgentConnectionRecoveryReason.Transport -> CONNECTION_STALL_AUTO_RETRY_BACKOFF_MS
        AgentConnectionRecoveryReason.ResourceExhausted -> RESOURCE_EXHAUSTED_AUTO_RETRY_BACKOFF_MS
    }
    val multiplier = 1L shl (attempt - 1).coerceIn(0, 5)
    return (base * multiplier).coerceAtMost(MAX_CONNECTION_STALL_AUTO_RETRY_BACKOFF_MS)
}

fun AgentConnectionRecoveryReason.maxAutomaticAttempts(): Int = when (this) {
    AgentConnectionRecoveryReason.Transport -> MAX_CONNECTION_STALL_AUTO_RETRIES
    AgentConnectionRecoveryReason.ResourceExhausted -> MAX_RESOURCE_EXHAUSTED_AUTO_RETRIES
}

private fun List<AgentEvent>.latestTurnStallTexts(): Sequence<CharSequence> = sequence {
    for (event in coalesceAcpTranscriptEvents(latestTurnEvents())) {
        when (event) {
            is AgentEvent.AssistantText -> yield(event.text)
            is AgentEvent.TaskError -> yield(event.message)
            is AgentEvent.ToolResult -> if (event.isError) yield(event.detail)
            else -> Unit
        }
    }
}

private fun CharSequence.hasRetriableResourceExhaustedError(): Boolean {
    val line = rawNonEmptyLines().lastOrNull() ?: return false
    return line.isProviderStallLine() && line.contains("[resource_exhausted]", ignoreCase = true)
}

/** Events after the most recent user message — the active/latest turn. */
fun List<AgentEvent>.latestTurnEvents(): List<AgentEvent> {
    val lastUserIndex = indexOfLast { it is AgentEvent.UserMessage }
    return if (lastUserIndex < 0) this else subList(lastUserIndex + 1, size)
}

/** Whether Andy should show the connection-stall recovery banner for this chat. */
fun shouldShowConnectionStallBanner(events: List<AgentEvent>, isActive: Boolean): Boolean =
    !isActive && events.hasRetriableConnectionStall()
