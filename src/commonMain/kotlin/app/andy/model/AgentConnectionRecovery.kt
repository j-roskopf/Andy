package app.andy.model

/** Follow-up Andy sends after a provider stream stalls mid-turn. */
const val CONNECTION_STALL_RETRY_PROMPT = "Continue where you left off."

/** Automatic "continue" prompts Andy will send before surfacing the stall banner. */
const val MAX_CONNECTION_STALL_AUTO_RETRIES = 2

/** Backoff before each automatic stall retry (multiplied by attempt number). */
const val CONNECTION_STALL_AUTO_RETRY_BACKOFF_MS = 1_000L

/** Follow-up Andy sends when the user accepts a plan-mode turn and asks to implement. */
const val IMPLEMENT_PLAN_PROMPT = "Implement the plan."

/**
 * Cursor (and some other ACP adapters) surface a transport drop as a standalone
 * `RetriableError` assistant/error line — not as an ACP stop reason. `cancelled` is also
 * used for user stop, so text matching is the only reliable signal.
 *
 * Match a whole line with the `RetriableError:` prefix. Substring mentions, source quotes,
 * and stream chunks like `" stalled"` must not count.
 */
private val RETRIABLE_STALL_LINE = Regex(
    """(?i)^(?:error:\s*)?retriableerror:\s+(?:connection\s+stalled(?:\s+repeatedly)?|\[canceled\]\s+http/2\s+stream\s+closed(?:\b.*)?)\s*$""",
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

/** True when [text] is only a provider stall error (blank lines allowed). */
fun CharSequence.isRetriableConnectionStallMessage(): Boolean {
    val lines = rawNonEmptyLines()
    return lines.isNotEmpty() && lines.all { it.isProviderStallLine() }
}

/** True when the last non-empty line is a provider stall error. */
fun CharSequence.hasRetriableConnectionStallError(): Boolean =
    rawNonEmptyLines().lastOrNull()?.isProviderStallLine() == true

/** Drops a trailing stall error so partial output before the drop stays visible. */
fun CharSequence.stripTrailingConnectionStallError(): String {
    val lines = toString().split('\n')
    val last = lines.indexOfLast { it.trim().isNotEmpty() }
    if (last < 0 || !lines[last].trimEnd().isProviderStallLine()) return toString()
    var end = last
    while (end > 0 && lines[end - 1].isBlank()) end--
    return lines.take(end).joinToString("\n").trimEnd()
}

fun List<AgentEvent>.hasRetriableConnectionStall(): Boolean =
    coalesceAcpTranscriptEvents(latestTurnEvents()).any { event ->
        when (event) {
            is AgentEvent.AssistantText -> event.text.hasRetriableConnectionStallError()
            is AgentEvent.TaskError -> event.message.hasRetriableConnectionStallError()
            is AgentEvent.ToolResult -> event.isError && event.detail.hasRetriableConnectionStallError()
            else -> false
        }
    }

/** Events after the most recent user message — the active/latest turn. */
fun List<AgentEvent>.latestTurnEvents(): List<AgentEvent> {
    val lastUserIndex = indexOfLast { it is AgentEvent.UserMessage }
    return if (lastUserIndex < 0) this else subList(lastUserIndex + 1, size)
}

/** Whether Andy should show the connection-stall recovery banner for this chat. */
fun shouldShowConnectionStallBanner(events: List<AgentEvent>, isActive: Boolean): Boolean =
    !isActive && events.hasRetriableConnectionStall()
