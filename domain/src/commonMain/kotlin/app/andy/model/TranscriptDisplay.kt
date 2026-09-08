@file:Suppress("DEPRECATION")

package app.andy.model

/**
 * Pure transcript display grouping/coalescing helpers shared by the desktop transcript UI
 * (`feature/agents`) and any other platform (e.g. Android) that renders [AgentEvent] streams.
 *
 * Kept free of Compose/UI dependencies so it can live in `:domain`.
 */

/**
 * Providers commonly emit the final response once as an assistant message and
 * again in their completion record. The completion record owns that response
 * in the transcript so it is visible once, with its completed state.
 */
fun transcriptDisplayEvents(
    events: List<AgentEvent>,
    hideOpenTurnFileChanges: Boolean = false,
): List<AgentEvent> {
    val coalesced = coalesceAcpTranscriptEvents(events)
    val displayable = coalesced.mapNotNull { event ->
        when {
            event is AgentEvent.AvailableCommands ||
                event is AgentEvent.AvailableModes ||
                event is AgentEvent.SessionInfo ||
                event is AgentEvent.Raw -> null
            event is AgentEvent.FileChanges && event.undone -> null
            event is AgentEvent.FileChanges && event.snapshot.summary.files.isEmpty() -> null
            event is AgentEvent.AssistantText -> {
                val stripped = event.text.stripTrailingConnectionStallError()
                when {
                    stripped.isBlank() -> null
                    stripped == event.text -> event
                    else -> event.copy(text = stripped)
                }
            }
            event.isHiddenConnectionStallMessage() -> null
            else -> event
        }
    }
    return displayable.filterIndexed { index, event ->
        val completion = displayable.getOrNull(index + 1) as? AgentEvent.TaskResult
        event !is AgentEvent.AssistantText || completion?.finalText?.trim() != event.text.trim()
    }.let(::coalesceFileChangesInTurnSegments)
        .let { if (hideOpenTurnFileChanges) suppressOpenTurnFileChanges(it) else it }
}

private fun AgentEvent.isHiddenConnectionStallMessage(): Boolean = when (this) {
    is AgentEvent.AssistantText -> text.isRetriableConnectionStallMessage()
    is AgentEvent.TaskError -> message.isRetriableConnectionStallMessage()
    is AgentEvent.UserMessage -> text.isSilentConnectionRecoveryPrompt()
    else -> false
}

/**
 * While a turn is still running, hide edited-files cards that belong to the open turn
 * (after the last [AgentEvent.TaskResult] / [AgentEvent.TaskError]). Prior turns keep theirs.
 */
fun suppressOpenTurnFileChanges(events: List<AgentEvent>): List<AgentEvent> {
    val openTurnStart = events.indexOfLast { it is AgentEvent.TaskResult || it is AgentEvent.TaskError }
        .let { if (it < 0) 0 else it + 1 }
    return events.mapIndexedNotNull { index, event ->
        if (index >= openTurnStart && event is AgentEvent.FileChanges) null else event
    }
}

/** Hard boundaries that split edit bursts — a new user/assistant message or turn result. */
fun isFileChangesSegmentBoundary(event: AgentEvent): Boolean = when (event) {
    is AgentEvent.UserMessage -> true
    is AgentEvent.AssistantText ->
        !event.isStreamDelta && event.text.stripTrailingConnectionStallError().isNotBlank()
    is AgentEvent.TaskResult, is AgentEvent.TaskError -> true
    else -> false
}

/**
 * Within each turn segment, merge every edited-files card into one row. Activity
 * (thoughts, reads, searches) may sit between the original cards; the merged card
 * replaces the last one so the summary appears after the related work.
 */
fun coalesceFileChangesInTurnSegments(events: List<AgentEvent>): List<AgentEvent> {
    if (events.isEmpty()) return events
    val output = mutableListOf<AgentEvent>()
    var segment = mutableListOf<AgentEvent>()

    fun flushSegment() {
        if (segment.isNotEmpty()) {
            output += coalesceFileChangesWithinSegment(segment)
            segment = mutableListOf()
        }
    }

    for (event in events) {
        if (isFileChangesSegmentBoundary(event)) {
            flushSegment()
            output += event
        } else {
            segment += event
        }
    }
    flushSegment()
    return output
}

fun coalesceFileChangesWithinSegment(segment: List<AgentEvent>): List<AgentEvent> {
    if (segment.isEmpty()) return segment
    val fileChanges = segment.filterIsInstance<AgentEvent.FileChanges>()
    if (fileChanges.size <= 1) return segment
    val merged = mergeConsecutiveFileChanges(fileChanges)
    val lastIndex = segment.indexOfLast { it is AgentEvent.FileChanges }
    return segment.mapIndexedNotNull { index, event ->
        when {
            event is AgentEvent.FileChanges && index != lastIndex -> null
            event is AgentEvent.FileChanges && index == lastIndex -> merged
            else -> event
        }
    }
}

/** @deprecated Use [coalesceFileChangesInTurnSegments]; kept for tests. */
fun coalesceConsecutiveFileChanges(events: List<AgentEvent>): List<AgentEvent> {
    if (events.isEmpty()) return events
    val merged = mutableListOf<AgentEvent>()
    var index = 0
    while (index < events.size) {
        val event = events[index]
        if (event !is AgentEvent.FileChanges) {
            merged += event
            index += 1
            continue
        }
        val group = mutableListOf<AgentEvent.FileChanges>()
        while (index < events.size && events[index] is AgentEvent.FileChanges) {
            group += events[index] as AgentEvent.FileChanges
            index += 1
        }
        merged += if (group.size == 1) group.single() else mergeConsecutiveFileChanges(group)
    }
    return merged
}

fun mergeConsecutiveFileChanges(changes: List<AgentEvent.FileChanges>): AgentEvent.FileChanges {
    require(changes.isNotEmpty())
    if (changes.size == 1) return changes.single()
    val mergedFiles = linkedMapOf<String, AgentFileChange>()
    val mergedDiffs = linkedMapOf<String, AgentFileDiff>()
    changes.forEach { change ->
        change.snapshot.summary.files.forEach { mergedFiles[it.path] = it }
        change.snapshot.diffs.forEach { (path, diff) -> mergedDiffs[path] = diff }
    }
    val first = changes.first()
    return first.copy(
        atMillis = changes.last().atMillis,
        batchId = changes.last().batchId,
        baselineTree = first.baselineTree,
        groupedBatchIds = changes.map { it.batchId },
        snapshot = AgentThreadChangeSnapshot(
            summary = AgentChangeSummary(mergedFiles.values.sortedBy { it.path }),
            diffs = mergedDiffs,
        ),
    )
}

sealed class TranscriptDisplayItem {
    data class Event(val index: Int, val event: AgentEvent) : TranscriptDisplayItem()
    data class ToolCalls(val startIndex: Int, val events: List<AgentEvent>) : TranscriptDisplayItem()
    /**
     * Child Andy chats linked by [AgentTask.parentChatTaskId] when the provider's
     * transcript hid the chat.start tool payload (e.g. Cursor's opaque "MCP: tool").
     */
    data class ChildSpawns(val tasks: List<AgentTask>) : TranscriptDisplayItem()
}

fun transcriptActivityExpanded(
    key: String,
    overrides: Set<String>,
    autoExpand: Boolean,
): Boolean = if (autoExpand) key !in overrides else key in overrides

fun transcriptDisplayItems(
    events: List<AgentEvent>,
    collapseActivityBetweenMessages: Boolean = false,
    keepThinkingOnTimeline: Boolean = false,
    hideOpenTurnFileChanges: Boolean = false,
): List<TranscriptDisplayItem> {
    val display = transcriptDisplayEvents(events, hideOpenTurnFileChanges = hideOpenTurnFileChanges)
        .filterNot { it is AgentEvent.ContextUsage }
    val items = mutableListOf<TranscriptDisplayItem>()
    var index = 0
    while (index < display.size) {
        val event = display[index]
        if (!event.isTranscriptActivityEvent()) {
            items += TranscriptDisplayItem.Event(index, event)
            index += 1
            continue
        }
        // Keep thinking as first-class timeline rows when requested, even if tool activity collapses.
        if (keepThinkingOnTimeline && event is AgentEvent.Thinking) {
            items += TranscriptDisplayItem.Event(index, event)
            index += 1
            continue
        }
        val startIndex = index
        val group = mutableListOf<AgentEvent>()
        while (index < display.size && display[index].isTranscriptActivityEvent()) {
            val next = display[index]
            if (keepThinkingOnTimeline && next is AgentEvent.Thinking) break
            group += next
            index += 1
        }
        when {
            group.isEmpty() -> Unit
            group.size == 1 -> items += TranscriptDisplayItem.Event(startIndex, group.single())
            collapseActivityBetweenMessages -> items += TranscriptDisplayItem.ToolCalls(startIndex, group)
            group.all { it is AgentEvent.ToolCall || it is AgentEvent.ToolResult } ->
                items += TranscriptDisplayItem.ToolCalls(startIndex, group)
            else -> group.forEachIndexed { offset, activity ->
                items += TranscriptDisplayItem.Event(startIndex + offset, activity)
            }
        }
    }
    return items
}

/**
 * Inserts [TranscriptDisplayItem.ChildSpawns] for parent→child chats that tool-call spawn
 * detection missed. Places the block at the earliest child [AgentTask.createdAtMillis].
 */
fun withLinkedChildSpawnItems(
    items: List<TranscriptDisplayItem>,
    childTasks: List<AgentTask>,
): List<TranscriptDisplayItem> {
    if (childTasks.isEmpty()) return items
    val insertAt = childTasks.minOf { it.createdAtMillis }
    val block = TranscriptDisplayItem.ChildSpawns(childTasks)
    val result = mutableListOf<TranscriptDisplayItem>()
    var inserted = false
    for (item in items) {
        val itemAt = transcriptDisplayItemAtMillis(item)
        if (!inserted && itemAt != null && itemAt > insertAt) {
            result += block
            inserted = true
        }
        result += item
    }
    if (!inserted) result += block
    return result
}

fun transcriptDisplayItemAtMillis(item: TranscriptDisplayItem): Long? = when (item) {
    is TranscriptDisplayItem.Event -> item.event.atMillis.takeIf { it > 0L }
    is TranscriptDisplayItem.ToolCalls -> item.events.firstOrNull()?.atMillis?.takeIf { it > 0L }
    is TranscriptDisplayItem.ChildSpawns -> item.tasks.minOfOrNull { it.createdAtMillis }?.takeIf { it > 0L }
}

fun AgentEvent.isTranscriptActivityEvent(): Boolean =
    this is AgentEvent.Thinking || this is AgentEvent.ToolCall || this is AgentEvent.ToolResult

fun compactActivityHeadline(events: List<AgentEvent>): String {
    val thinkingCount = events.count { it is AgentEvent.Thinking }
    val toolHeadline = compactToolActivityHeadline(events)
    return when {
        thinkingCount > 0 && toolHeadline.isNotBlank() -> {
            val thinkingLabel = if (thinkingCount == 1) "thought" else "$thinkingCount thoughts"
            "$thinkingLabel, $toolHeadline"
        }
        thinkingCount > 0 -> if (thinkingCount == 1) "Thought" else "$thinkingCount thoughts"
        else -> toolHeadline
    }
}

fun compactToolActivityHeadline(events: List<AgentEvent>): String {
    val allCalls = events.filterIsInstance<AgentEvent.ToolCall>()
    if (allCalls.isEmpty()) {
        val count = events.size
        return "$count tool ${if (count == 1) "result" else "results"}"
    }
    // Content-free calls are not rendered as rows, so they must not colour the group headline
    // either — but they still happened, so a group made only of them is counted, not named.
    val toolCalls = allCalls.filterNot {
        toolRowShowsNothing(
            it.toolName,
            it.summary,
            it.detail,
            it.locations,
            it.images.isNotEmpty(),
            isFailure = it.state == AgentToolState.Failed,
        )
    }
    if (toolCalls.isEmpty()) {
        val count = allCalls.size
        return "$count tool ${if (count == 1) "call" else "calls"}"
    }
    if (toolCalls.size == 1) {
        val call = toolCalls.single()
        return toolActionPhrase(call.toolName, call.summary, call.kind, call.locations)
    }
    val counts = toolCalls.groupingBy { it.activityCategory() }.eachCount()
    val summaryParts = buildList {
        counts[ToolActivityCategory.Spawn]?.let { add(AgentSpawnPresentation.spawningHeadline(it)) }
        counts[ToolActivityCategory.Read]?.let { add("read $it ${if (it == 1) "file" else "files"}") }
        counts[ToolActivityCategory.Search]?.let { add(if (it == 1) "searched" else "searched $it times") }
        counts[ToolActivityCategory.Edit]?.let { add("edited $it ${if (it == 1) "file" else "files"}") }
        counts[ToolActivityCategory.Command]?.let { add("ran $it ${if (it == 1) "command" else "commands"}") }
        counts[ToolActivityCategory.Fetch]?.let { add("fetched $it ${if (it == 1) "resource" else "resources"}") }
    }
    if (summaryParts.isNotEmpty()) {
        val other = counts[ToolActivityCategory.Other] ?: 0
        val parts = if (other > 0) {
            summaryParts + "$other other tool ${if (other == 1) "call" else "calls"}"
        } else {
            summaryParts
        }
        return parts.joinToString(", ")
    }
    val phrases = toolCalls.map { toolActionPhrase(it.toolName, it.summary, it.kind, it.locations) }
    val meaningfulPhrases = phrases.filter { it.isNotBlank() && !AcpToolCallPresentation.isMinimalOutput(it) }
    if (meaningfulPhrases.isEmpty()) {
        val count = toolCalls.size
        return "$count tool ${if (count == 1) "call" else "calls"}"
    }
    return meaningfulPhrases.take(3).joinToString(", ").let { headline ->
        if (meaningfulPhrases.size > 3) "$headline, …" else headline
    }
}

private enum class ToolActivityCategory { Spawn, Read, Search, Edit, Command, Fetch, Other }

/**
 * Every call in a group lands in exactly one bucket, so the group headline can account for all of
 * them. Naming only the recognized categories dropped the rest of the group from the headline
 * entirely — a turn of one read and eight greps was headlined "read 1 file" — and cursor-agent makes
 * that the common case: it reports every kind as [AgentToolKind.Other] and titles a shell call with
 * the command itself, leaving the arguments as the only evidence of what ran.
 */
private fun AgentEvent.ToolCall.activityCategory(): ToolActivityCategory {
    if (AgentSpawnPresentation.isAgentSpawn(toolName, summary, detail)) return ToolActivityCategory.Spawn
    val declared = kind?.takeUnless { it == AgentToolKind.Other }
    if (declared != null) return declared.activityCategory()
    val lower = toolName.trim().lowercase()
    return when {
        lower in SearchToolNames -> ToolActivityCategory.Search
        lower in ReadToolNames -> ToolActivityCategory.Read
        lower in EditToolNames -> ToolActivityCategory.Edit
        lower in CommandToolNames -> ToolActivityCategory.Command
        else -> AcpToolCallPresentation.inferKindFromArguments(detail)
            ?.activityCategory()
            ?: ToolActivityCategory.Other
    }
}

private fun AgentToolKind.activityCategory(): ToolActivityCategory = when (this) {
    AgentToolKind.Read -> ToolActivityCategory.Read
    AgentToolKind.Search -> ToolActivityCategory.Search
    AgentToolKind.Edit, AgentToolKind.Delete, AgentToolKind.Move -> ToolActivityCategory.Edit
    AgentToolKind.Execute -> ToolActivityCategory.Command
    AgentToolKind.Fetch -> ToolActivityCategory.Fetch
    AgentToolKind.Think, AgentToolKind.Other -> ToolActivityCategory.Other
}

private const val ToolHeadlineLimit = 160

/**
 * A row headline is a label, but the fields it comes from are provider-controlled: a `summary` can
 * be a whole multi-line command or a 4 KB command result. Collapse it to one short line and leave
 * the rest to the expanded body.
 */
private val WhitespaceRunPattern = Regex("""\s+""")

fun condenseToolHeadline(text: String): String {
    val collapsed = text.replace(WhitespaceRunPattern, " ").trim()
    return if (collapsed.length <= ToolHeadlineLimit) {
        collapsed
    } else {
        collapsed.take(ToolHeadlineLimit).trimEnd() + "…"
    }
}

/**
 * ACP lanes emit bookkeeping tool events carrying only a call id — no name, arguments, output, or
 * location. A row for one of those says nothing at all, so the transcript leaves it out.
 */
fun toolRowShowsNothing(
    name: String?,
    summary: String,
    detail: String,
    locations: List<String>,
    hasImages: Boolean,
    isFailure: Boolean = false,
): Boolean {
    if (isFailure || hasImages || locations.any { it.isNotBlank() }) return false
    if (!AcpToolCallPresentation.isGenericTitle(name?.trim().orEmpty())) return false
    return AcpToolCallPresentation.isMinimalOutput(summary) &&
        AcpToolCallPresentation.isMinimalOutput(detail)
}

fun toolBlockHeadline(
    name: String?,
    summary: String,
    kind: AgentToolKind?,
    locations: List<String>,
): String = condenseToolHeadline(rawToolBlockHeadline(name, summary, kind, locations))

private fun rawToolBlockHeadline(
    name: String?,
    summary: String,
    kind: AgentToolKind?,
    locations: List<String>,
): String {
    val label = name?.trim().orEmpty()
    val detail = summary
        .takeUnless { AcpToolCallPresentation.isMinimalOutput(it) }
        .orEmpty()
        .ifBlank { AcpToolCallPresentation.enrichSummary("", kind, locations) }
    // Bare "Edit" / "Terminal" labels read as empty chrome — prefer an action phrase, and
    // when we do have a path/command, lead with Edited/Ran rather than "Edit: path".
    // A generic "tool" is not a name at all, so it must never prefix the summary.
    if (AcpToolCallPresentation.isGenericOrSparseTitle(label) || label.isBlank()) {
        return toolActionPhrase(label, detail.ifBlank { summary }, kind, locations)
    }
    return when {
        detail.isNotBlank() && !label.equals(detail, ignoreCase = true) -> "$label: $detail"
        label.isNotBlank() -> label
        detail.isNotBlank() -> detail
        else -> toolActionPhrase(label, summary, kind, locations)
    }
}

private fun toolActionPhrase(
    toolName: String,
    summary: String,
    kind: AgentToolKind? = null,
    locations: List<String> = emptyList(),
): String = condenseToolHeadline(rawToolActionPhrase(toolName, summary, kind, locations))

private fun rawToolActionPhrase(
    toolName: String,
    summary: String,
    kind: AgentToolKind? = null,
    locations: List<String> = emptyList(),
): String {
    val lower = toolName.lowercase()
    val trimmedSummary = summary
        .trim()
        .takeUnless { AcpToolCallPresentation.isMinimalOutput(it) }
        .orEmpty()
        .ifBlank { AcpToolCallPresentation.enrichSummary("", kind, locations) }
        .let { text ->
            // Avoid "Ran Terminal" / "Edited Edit" when the only "summary" is the sparse label.
            if (AcpToolCallPresentation.isSparseToolTitle(text)) "" else text
        }
    return when {
        AgentSpawnPresentation.isAgentSpawn(toolName, summary, "") -> AgentSpawnPresentation.spawningHeadline(1)
        lower in SearchToolNames || kind == AgentToolKind.Search ->
            trimmedSummary.takeIf { it.isNotBlank() }?.let { "Searched $it" } ?: "Searched"
        lower in ReadToolNames || kind == AgentToolKind.Read ->
            trimmedSummary.takeIf { it.isNotBlank() }?.let { "Read $it" } ?: "Read file"
        lower in EditToolNames || kind == AgentToolKind.Edit || kind == AgentToolKind.Delete ->
            trimmedSummary.takeIf { it.isNotBlank() }?.let {
                if (kind == AgentToolKind.Delete || lower == "delete") "Deleted $it" else "Edited $it"
            } ?: if (kind == AgentToolKind.Delete || lower == "delete") "Deleted file" else "Edited file"
        lower in CommandToolNames || kind == AgentToolKind.Execute ->
            trimmedSummary.takeIf { it.isNotBlank() }?.let { "Ran $it" } ?: "Ran command"
        trimmedSummary.isNotBlank() -> trimmedSummary
        toolName.isNotBlank() && !AcpToolCallPresentation.isGenericTitle(toolName) -> toolName
        else -> toolKindPhrase(kind) ?: "Tool call"
    }
}

/** Last resort when a provider sent neither a usable name nor arguments: name the action by kind. */
private fun toolKindPhrase(kind: AgentToolKind?): String? = when (kind) {
    AgentToolKind.Read -> "Read file"
    AgentToolKind.Edit -> "Edited file"
    AgentToolKind.Delete -> "Deleted file"
    AgentToolKind.Move -> "Moved file"
    AgentToolKind.Search -> "Searched"
    AgentToolKind.Execute -> "Ran command"
    AgentToolKind.Think -> "Thought"
    AgentToolKind.Fetch -> "Fetched a resource"
    AgentToolKind.Other, null -> null
}

private val ReadToolNames = setOf(
    "read", "read file", "read_file", "file_read", "get_network_request",
)
private val SearchToolNames = setOf(
    "grep", "grep_search", "glob", "glob_file_search", "search", "find", "file_search",
    "codebase_search", "list_dir", "list dir", "web search", "web_search",
)
private val CommandToolNames = setOf(
    "shell", "run_terminal_cmd", "bash", "terminal", "execute", "run", "command",
)
private val EditToolNames = setOf(
    "edit", "edit file", "edit_file", "editing files", "str_replace", "apply_patch",
    "write", "delete", "create", "update",
)
