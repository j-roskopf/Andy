package app.andy.desktop.service.agents.acp

import app.andy.model.AgentEvent

internal sealed class AcpReplayFilterResult {
    data object Ignore : AcpReplayFilterResult()
    /** [text] overrides stream chunk text when set; otherwise accept [event] unchanged. */
    data class Accept(val text: String? = null) : AcpReplayFilterResult()
}

/**
 * Providers may replay prior turns when an ACP session is reloaded after Andy quit.
 * Andy already persisted those turns — drop echoed chunks until genuinely new text arrives.
 *
 * [scratch] accumulates ignored stream deltas so multi-chunk replays are recognized.
 */
internal fun filterAcpProviderHistoryReplay(
    existing: List<AgentEvent>,
    event: AgentEvent,
    scratch: StringBuilder,
): AcpReplayFilterResult = when (event) {
    is AgentEvent.UserMessage -> {
        val text = event.text.trim()
        if (text.isNotEmpty() && existing.any { (it as? AgentEvent.UserMessage)?.text?.trim() == text }) {
            AcpReplayFilterResult.Ignore
        } else {
            AcpReplayFilterResult.Accept()
        }
    }
    is AgentEvent.AssistantText -> streamReplayFilterResult(
        priorTexts = existingAssistantTexts(existing),
        text = event.text,
        isStreamDelta = event.isStreamDelta,
        scratch = scratch,
    )
    is AgentEvent.Thinking -> streamReplayFilterResult(
        priorTexts = existing.filterIsInstance<AgentEvent.Thinking>().map { it.text },
        text = event.text,
        isStreamDelta = event.isStreamDelta,
        scratch = scratch,
    )
    is AgentEvent.ToolCall -> {
        val id = event.toolCallId
        if (id != null && existing.any { (it as? AgentEvent.ToolCall)?.toolCallId == id }) {
            AcpReplayFilterResult.Ignore
        } else {
            AcpReplayFilterResult.Accept()
        }
    }
    else -> AcpReplayFilterResult.Accept()
}

private fun streamReplayFilterResult(
    priorTexts: List<String>,
    text: String,
    isStreamDelta: Boolean,
    scratch: StringBuilder,
): AcpReplayFilterResult {
    if (!isStreamDelta) {
        scratch.clear()
        return AcpReplayFilterResult.Accept()
    }
    scratch.append(text)
    val scratchText = scratch.toString()
    val replaying = priorTexts.any { prior ->
        prior.startsWith(scratchText) && scratchText.length <= prior.length
    }
    if (replaying) return AcpReplayFilterResult.Ignore

    val replayedPrefix = priorTexts
        .filter { prior -> prior.isNotEmpty() && scratchText.startsWith(prior) }
        .maxByOrNull { it.length }
    val emit = replayedPrefix?.let(scratchText::removePrefix) ?: scratchText
    scratch.clear()
    return when {
        emit.isEmpty() -> AcpReplayFilterResult.Ignore
        emit == text -> AcpReplayFilterResult.Accept()
        else -> AcpReplayFilterResult.Accept(text = emit)
    }
}

private fun existingAssistantTexts(events: List<AgentEvent>): List<String> =
    events.filterIsInstance<AgentEvent.AssistantText>().map { it.text }
