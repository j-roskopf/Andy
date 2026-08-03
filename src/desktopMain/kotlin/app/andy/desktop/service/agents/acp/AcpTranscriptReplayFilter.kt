package app.andy.desktop.service.agents.acp

import app.andy.model.AgentEvent

/**
 * Providers may replay prior turns when an ACP session is reloaded after Andy quit.
 * Andy already persisted those turns — drop echoed chunks until genuinely new text arrives.
 *
 * [scratch] accumulates ignored stream deltas so multi-chunk replays are recognized.
 */
internal fun shouldIgnoreAcpProviderHistoryReplay(
    existing: List<AgentEvent>,
    event: AgentEvent,
    scratch: StringBuilder,
): Boolean = when (event) {
    is AgentEvent.AssistantText -> {
        if (!event.isStreamDelta) {
            scratch.clear()
            return false
        }
        scratch.append(event.text)
        val replaying = existingAssistantTexts(existing).any { prior ->
            prior.startsWith(scratch.toString()) && scratch.length <= prior.length
        }
        if (replaying) true else {
            scratch.clear()
            false
        }
    }
    is AgentEvent.Thinking -> {
        if (!event.isStreamDelta) {
            scratch.clear()
            return false
        }
        scratch.append(event.text)
        val replaying = existing.filterIsInstance<AgentEvent.Thinking>().map { it.text }.any { prior ->
            prior.startsWith(scratch.toString()) && scratch.length <= prior.length
        }
        if (replaying) true else {
            scratch.clear()
            false
        }
    }
    is AgentEvent.ToolCall -> {
        val id = event.toolCallId ?: return false
        existing.any { (it as? AgentEvent.ToolCall)?.toolCallId == id }
    }
    else -> false
}

private fun existingAssistantTexts(events: List<AgentEvent>): List<String> =
    events.filterIsInstance<AgentEvent.AssistantText>().map { it.text }
