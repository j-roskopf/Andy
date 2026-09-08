package com.joetr.andy.mobile.ui

import com.joetr.andy.mobile.data.networkaccess.ChatEventDto
import com.joetr.andy.mobile.data.networkaccess.isVisibleTranscript

/**
 * Wire-event mirror of desktop's `app.andy.model.transcriptDisplayItems`
 * ([domain/src/commonMain/kotlin/app/andy/model/TranscriptDisplay.kt]) and the web companion's
 * `transcriptDisplayItems` in `webchat/app.js`. Network Access sends a flat [ChatEventDto]
 * transcript (no rich [app.andy.model.AgentEvent] union), so this groups on wire `type` strings
 * instead — same grouping rules, sized for the wire model.
 */

/** Activity events that may fold into a collapsed [WireDisplayItem.ToolGroup]. */
fun ChatEventDto.isWireTranscriptActivity(): Boolean =
    type == "thinking" || type == "tool" || type == "tool-result"

sealed class WireDisplayItem {
    data class Event(val index: Int, val event: ChatEventDto) : WireDisplayItem()
    data class ToolGroup(val startIndex: Int, val events: List<ChatEventDto>) : WireDisplayItem()
}

/**
 * Groups a wire transcript into display rows.
 *
 * @param collapseActivityBetweenMessages mirrors desktop's `agentTranscriptCollapseActivityBlocks` —
 * folds every run of activity (thinking + tools) into one [WireDisplayItem.ToolGroup].
 * @param keepThinkingOnTimeline mirrors desktop's `agentTranscriptAutoExpandThinking` — keeps
 * [ChatEventDto] `thinking` events as standalone timeline rows even when collapse is on.
 */
fun wireTranscriptDisplayItems(
    events: List<ChatEventDto>,
    collapseActivityBetweenMessages: Boolean = false,
    keepThinkingOnTimeline: Boolean = false,
): List<WireDisplayItem> {
    val display = events.filter { it.isVisibleTranscript() }
    val items = mutableListOf<WireDisplayItem>()
    var index = 0
    while (index < display.size) {
        val event = display[index]
        if (!event.isWireTranscriptActivity()) {
            items += WireDisplayItem.Event(index, event)
            index += 1
            continue
        }
        // Keep thinking as first-class timeline rows when requested, even if tool activity collapses.
        if (keepThinkingOnTimeline && event.type == "thinking") {
            items += WireDisplayItem.Event(index, event)
            index += 1
            continue
        }
        val startIndex = index
        val group = mutableListOf<ChatEventDto>()
        while (index < display.size && display[index].isWireTranscriptActivity()) {
            val next = display[index]
            if (keepThinkingOnTimeline && next.type == "thinking") break
            group += next
            index += 1
        }
        when {
            group.isEmpty() -> Unit
            group.size == 1 -> items += WireDisplayItem.Event(startIndex, group.single())
            collapseActivityBetweenMessages -> items += WireDisplayItem.ToolGroup(startIndex, group)
            group.all { it.type == "tool" || it.type == "tool-result" } ->
                items += WireDisplayItem.ToolGroup(startIndex, group)
            else -> group.forEachIndexed { offset, activity ->
                items += WireDisplayItem.Event(startIndex + offset, activity)
            }
        }
    }
    return items
}

/** Same rule as desktop's `transcriptActivityExpanded` / web's `activityExpanded`. */
fun wireActivityExpanded(
    key: String,
    overrides: Set<String>,
    autoExpand: Boolean,
): Boolean = if (autoExpand) key !in overrides else key in overrides

/** Stable row key for [WireDisplayItem], used for both `LazyColumn` keys and expand overrides. */
fun wireDisplayItemKey(item: WireDisplayItem): String = when (item) {
    is WireDisplayItem.Event ->
        "event-${item.event.atMillis}-${item.event.type}-${item.event.text.hashCode()}"
    is WireDisplayItem.ToolGroup -> "group-${item.startIndex}"
}

/** Mirrors web's `toolHeadline`. */
fun wireToolHeadline(event: ChatEventDto): String {
    val name = event.toolName.trim()
    val summary = event.summary.trim()
    return when {
        name.isNotEmpty() && summary.isNotEmpty() && !name.equals(summary, ignoreCase = true) ->
            "$name: $summary"
        summary.isNotEmpty() -> summary
        name.isNotEmpty() -> name
        event.type == "tool-result" -> "Tool result"
        else -> "Tool call"
    }
}

/** Mirrors web's `compactActivityHeadline`. */
fun wireCompactActivityHeadline(group: List<ChatEventDto>): String {
    val thinkingCount = group.count { it.type == "thinking" }
    val tools = group.filter { it.type == "tool" || it.type == "tool-result" }
    val toolLabel = when {
        tools.isEmpty() -> ""
        tools.size == 1 -> wireToolHeadline(tools.single())
        else -> "${tools.size} tool steps"
    }
    return when {
        thinkingCount > 0 && toolLabel.isNotEmpty() -> {
            val thought = if (thinkingCount == 1) "thought" else "$thinkingCount thoughts"
            "$thought, $toolLabel"
        }
        thinkingCount > 0 -> if (thinkingCount == 1) "Thought" else "$thinkingCount thoughts"
        toolLabel.isNotEmpty() -> toolLabel
        else -> "Activity"
    }
}

/** Mirrors web's `activityBodyText`. */
fun wireActivityBody(event: ChatEventDto): String = when {
    event.type == "thinking" -> event.text
    event.detail.isNotBlank() -> event.detail
    event.summary.isNotBlank() -> event.summary
    event.text.isNotBlank() -> event.text
    else -> wireToolHeadline(event)
}

/** Body text for a collapsed [WireDisplayItem.ToolGroup], joining each row's body text. */
fun wireGroupBodyText(group: List<ChatEventDto>): String =
    group.map { wireActivityBody(it) }.filter { it.isNotBlank() }.joinToString("\n\n---\n\n")
