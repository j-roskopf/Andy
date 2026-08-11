package app.andy.model

/** Follow-up Andy sends after a provider stream stalls mid-turn. */
const val CONNECTION_STALL_RETRY_PROMPT = "Continue where you left off."

/** Follow-up Andy sends when the user accepts a plan-mode turn and asks to implement. */
const val IMPLEMENT_PLAN_PROMPT = "Implement the plan."

private val RETRIABLE_CONNECTION_ERROR_PATTERNS = listOf(
    Regex("""(?i)(?:error:\s*)?(?:retriableerror:\s*)?connection\s+stalled(?:\s+repeatedly)?"""),
    Regex(
        """(?i)(?:error:\s*)?(?:retriableerror:\s*)?(?:\[canceled\]\s*)?http/2\s+stream\s+closed""",
    ),
)

/** True when [text] is a Cursor/provider transport failure surfaced in chat output. */
fun CharSequence.isRetriableConnectionStallMessage(): Boolean =
    RETRIABLE_CONNECTION_ERROR_PATTERNS.any { it.containsMatchIn(this) }

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
