package app.andy.ui.agents

import app.andy.model.AgentEvent
import app.andy.model.TranscriptDisplayItem
import app.andy.model.boundedUserMessagePreview
import app.andy.model.isSilentConnectionRecoveryPrompt
import app.andy.model.stripDecisionCheckpointMarkup
import app.andy.model.stripTrailingConnectionStallError
import app.andy.ui.components.findLiteralRanges

/** One substring hit inside a transcript row. */
data class ChatFindMatch(
    val itemKey: String,
    val start: Int,
    val end: Int,
    /**
     * Activity key inside a [TranscriptDisplayItem.ToolCalls] group (or the event key itself
     * for a standalone thinking/tool row). Used to expand and scope text highlights.
     */
    val nestedEventKey: String? = null,
    val nestedExpandKind: ChatFindExpandKind? = null,
) {
    val range: IntRange get() = start until end
}

enum class ChatFindExpandKind {
    Thinking,
    Tool,
    ToolGroup,
}

/**
 * Case-insensitive literal find over ACP transcript display rows.
 *
 * Searchable content: user messages, assistant text, thinking, tool title/summary/detail,
 * and task results. Collapsed asides stay reachable — navigation expands them.
 */
fun findChatMatches(
    displayItems: List<TranscriptDisplayItem>,
    query: String,
    originalPrompt: String? = null,
    originalPromptVisible: Boolean = false,
): List<ChatFindMatch> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    val out = ArrayList<ChatFindMatch>()
    if (originalPromptVisible) {
        val prompt = originalPrompt?.takeIf { it.isNotBlank() }?.let(::boundedUserMessagePreview)
        if (!prompt.isNullOrEmpty()) {
            appendMatches(out, itemKey = "original-prompt", haystack = prompt, needle = needle)
        }
    }
    for (item in displayItems) {
        when (item) {
            is TranscriptDisplayItem.Event -> {
                val text = chatFindSearchableText(item.event) ?: continue
                val eventKey = transcriptEventKey(item.index, item.event)
                val kind = chatFindExpandKind(item.event)
                appendMatches(
                    out,
                    itemKey = transcriptDisplayItemKey(item),
                    haystack = text,
                    needle = needle,
                    nestedEventKey = eventKey.takeIf { kind != null },
                    nestedExpandKind = kind,
                )
            }
            is TranscriptDisplayItem.ToolCalls -> {
                val groupKey = transcriptDisplayItemKey(item)
                item.events.forEachIndexed { offset, event ->
                    val text = chatFindSearchableText(event) ?: return@forEachIndexed
                    val eventKey = transcriptEventKey(item.startIndex + offset, event)
                    val kind = chatFindExpandKind(event) ?: return@forEachIndexed
                    appendMatches(
                        out,
                        itemKey = groupKey,
                        haystack = text,
                        needle = needle,
                        nestedEventKey = eventKey,
                        nestedExpandKind = kind,
                    )
                }
            }
            is TranscriptDisplayItem.ChildSpawns -> {
                val text = chatFindSearchableText(item) ?: continue
                appendMatches(out, itemKey = transcriptDisplayItemKey(item), haystack = text, needle = needle)
            }
        }
    }
    return out
}

/** Keys that should be force-expanded (no animation) for the active find hit. */
fun chatFindExpandKeys(match: ChatFindMatch?): Set<String> {
    if (match == null) return emptySet()
    val keys = linkedSetOf<String>()
    when (match.nestedExpandKind) {
        ChatFindExpandKind.ToolGroup -> keys += match.itemKey
        ChatFindExpandKind.Thinking, ChatFindExpandKind.Tool -> {
            // Nested hit inside a collapsed activity group: open the group and the row.
            if (match.itemKey.startsWith("tool-group-")) {
                keys += match.itemKey
            }
            match.nestedEventKey?.let { keys += it }
        }
        null -> Unit
    }
    return keys
}

fun chatFindSearchableText(item: TranscriptDisplayItem): String? = when (item) {
    is TranscriptDisplayItem.Event -> chatFindSearchableText(item.event)
    is TranscriptDisplayItem.ToolCalls -> {
        val parts = item.events.mapNotNull(::chatFindSearchableText)
        parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
    is TranscriptDisplayItem.ChildSpawns -> {
        item.tasks.mapNotNull { task ->
            sequenceOf(task.title, task.prompt)
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
        }.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}

fun chatFindSearchableText(event: AgentEvent): String? = when (event) {
    is AgentEvent.UserMessage -> {
        if (event.text.isSilentConnectionRecoveryPrompt()) {
            null
        } else {
            userMessageDisplayText(event).takeIf { it.isNotBlank() }
        }
    }
    is AgentEvent.AssistantText -> {
        val visible = stripDecisionCheckpointMarkup(
            event.text.stripTrailingConnectionStallError(),
        )
        visible.takeIf { it.isNotBlank() }
    }
    is AgentEvent.Thinking -> event.text.takeIf { it.isNotBlank() }
    is AgentEvent.ToolCall -> toolFindText(event.toolName, event.summary, event.detail)
    is AgentEvent.ToolResult -> toolFindText(event.toolName, event.summary, event.detail)
    is AgentEvent.TaskResult -> event.finalText?.takeIf { it.isNotBlank() }
    else -> null
}

private fun chatFindExpandKind(event: AgentEvent): ChatFindExpandKind? = when (event) {
    is AgentEvent.Thinking -> ChatFindExpandKind.Thinking
    is AgentEvent.ToolCall, is AgentEvent.ToolResult -> ChatFindExpandKind.Tool
    else -> null
}

private fun toolFindText(toolName: String?, summary: String, detail: String = summary): String? {
    val title = toolName.orEmpty().trim()
    val body = summary.trim()
    val extra = detail.trim().takeUnless { it.isEmpty() || it == body }.orEmpty()
    val combined = buildString {
        if (title.isNotEmpty()) append(title)
        if (body.isNotEmpty()) {
            if (isNotEmpty()) append(' ')
            append(body)
        }
        if (extra.isNotEmpty()) {
            if (isNotEmpty()) append('\n')
            append(extra)
        }
    }
    return combined.takeIf { it.isNotBlank() }
}

private fun appendMatches(
    out: MutableList<ChatFindMatch>,
    itemKey: String,
    haystack: String,
    needle: String,
    nestedEventKey: String? = null,
    nestedExpandKind: ChatFindExpandKind? = null,
) {
    for (range in findLiteralRanges(haystack, needle)) {
        out += ChatFindMatch(
            itemKey = itemKey,
            start = range.first,
            end = range.last + 1,
            nestedEventKey = nestedEventKey,
            nestedExpandKind = nestedExpandKind,
        )
    }
}
