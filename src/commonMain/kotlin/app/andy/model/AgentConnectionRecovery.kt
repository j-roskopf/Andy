package app.andy.model

/** Follow-up Andy sends after a provider stream stalls mid-turn. */
const val CONNECTION_STALL_RETRY_PROMPT = "Continue where you left off."

private val CONNECTION_STALL_PATTERN = Regex(
    """(?i)(?:error:\s*)?(?:retriableerror:\s*)?connection\s+stalled(?:\s+repeatedly)?""",
)

/** True when [text] is a Cursor/provider transport stall surfaced in chat output. */
fun CharSequence.isRetriableConnectionStallMessage(): Boolean =
    CONNECTION_STALL_PATTERN.containsMatchIn(this)

fun List<AgentEvent>.hasRetriableConnectionStall(): Boolean =
    any { event ->
        when (event) {
            is AgentEvent.AssistantText -> event.text.isRetriableConnectionStallMessage()
            is AgentEvent.TaskError -> event.message.isRetriableConnectionStallMessage()
            is AgentEvent.ToolResult -> event.isError && event.detail.isRetriableConnectionStallMessage()
            else -> false
        }
    }

/** Whether Andy should show the connection-stall recovery banner for this chat. */
fun shouldShowConnectionStallBanner(events: List<AgentEvent>, isActive: Boolean): Boolean =
    !isActive && events.hasRetriableConnectionStall()
