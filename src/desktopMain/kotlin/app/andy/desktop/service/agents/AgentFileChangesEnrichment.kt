package app.andy.desktop.service.agents

import app.andy.model.AgentEvent
import app.andy.model.AgentThreadChangeSnapshot
import app.andy.model.AgentToolState
import app.andy.model.coalesceAcpTranscriptEvents
import java.util.UUID

internal data class FileChangesEnrichmentResult(
    val display: List<AgentEvent>,
    val newlyPersisted: List<AgentEvent.FileChanges>,
)

/**
 * Synthesizes [AgentEvent.FileChanges] rows for legacy ACP transcript segments that have
 * completed mutating tool calls but no persisted file-changes event yet.
 */
internal object AgentFileChangesEnrichment {
    fun enrichIncremental(
        worktrees: WorktreeManager,
        cwd: String,
        baseline: String,
        events: List<AgentEvent>,
        segmentPaths: (List<AgentEvent>) -> Set<String>,
    ): FileChangesEnrichmentResult {
        if (events.isEmpty()) return FileChangesEnrichmentResult(events, emptyList())
        val output = mutableListOf<AgentEvent>()
        val newlyPersisted = mutableListOf<AgentEvent.FileChanges>()
        var segment = mutableListOf<AgentEvent>()

        fun flushSegment() {
            if (segment.isEmpty()) return
            val (enriched, synthesized) = enrichTurnSegment(
                worktrees = worktrees,
                cwd = cwd,
                baseline = baseline,
                segment = segment,
                segmentPaths = segmentPaths,
            )
            synthesized?.let { newlyPersisted += it }
            output += enriched
            segment = mutableListOf()
        }

        for (event in events) {
            if (isTranscriptTurnBoundary(event)) {
                flushSegment()
                output += event
            } else {
                segment += event
            }
        }
        flushSegment()
        return FileChangesEnrichmentResult(output, newlyPersisted)
    }

    fun isTranscriptTurnBoundary(event: AgentEvent): Boolean = when (event) {
        is AgentEvent.UserMessage -> true
        is AgentEvent.TaskResult, is AgentEvent.TaskError -> true
        else -> false
    }

    fun enrichTurnSegment(
        worktrees: WorktreeManager,
        cwd: String,
        baseline: String,
        segment: List<AgentEvent>,
        segmentPaths: (List<AgentEvent>) -> Set<String>,
    ): Pair<List<AgentEvent>, AgentEvent.FileChanges?> {
        if (segment.any { it is AgentEvent.FileChanges && !it.undone }) return segment to null
        val paths = segmentPaths(segment)
        if (paths.isEmpty()) return segment to null
        val snapshot = worktrees.changeSnapshot(cwd, baseline, paths) ?: return segment to null
        if (snapshot.summary.files.isEmpty()) return segment to null
        val atMillis = segment.lastOrNull()?.atMillis ?: System.currentTimeMillis()
        val synthesized = AgentEvent.FileChanges(
            atMillis = atMillis,
            batchId = UUID.randomUUID().toString(),
            baselineTree = baseline,
            snapshot = snapshot,
        )
        val insertBefore = segment.indexOfLast { it is AgentEvent.TaskResult }
            .takeIf { it >= 0 }
            ?: segment.size
        val enriched = buildList {
            addAll(segment.subList(0, insertBefore))
            add(synthesized)
            addAll(segment.subList(insertBefore, segment.size))
        }
        return enriched to synthesized
    }

    fun displayEventsEqual(a: List<AgentEvent>, b: List<AgentEvent>): Boolean =
        transcriptDisplayFingerprint(a) == transcriptDisplayFingerprint(b)

    fun coalescedDisplay(events: List<AgentEvent>): List<AgentEvent> =
        coalesceAcpTranscriptEvents(events)

    private fun transcriptDisplayFingerprint(events: List<AgentEvent>): List<String> =
        events.map { event ->
            when (event) {
                is AgentEvent.FileChanges ->
                    "fc:${event.batchId}:${event.undone}:${event.snapshot.summary.files.joinToString { it.path }}"
                is AgentEvent.ToolCall ->
                    "tool:${event.toolCallId}:${event.state}:${event.toolName}:${event.summary}"
                is AgentEvent.AssistantText -> "asst:${event.atMillis}:${event.text.length}"
                is AgentEvent.UserMessage -> "user:${event.atMillis}:${event.text}"
                is AgentEvent.TaskResult -> "result:${event.atMillis}:${event.success}"
                is AgentEvent.TaskError -> "error:${event.atMillis}:${event.message}"
                else -> "${event::class.simpleName}:${event.atMillis}"
            }
        }
}

internal fun touchedPathsFromTranscriptEvents(
    events: List<AgentEvent>,
    cwd: String,
    isMutatingToolCall: (AgentEvent.ToolCall) -> Boolean,
    toolCallPathCandidates: (AgentEvent.ToolCall) -> List<String>,
): Set<String> {
    val root = runCatching { java.io.File(cwd).canonicalFile }.getOrNull() ?: return emptySet()
    return events
        .filterIsInstance<AgentEvent.ToolCall>()
        .filter(isMutatingToolCall)
        .flatMap(toolCallPathCandidates)
        .mapNotNullTo(mutableSetOf()) { location -> relativeRepoPathForEnrichment(root, location) }
}

private fun relativeRepoPathForEnrichment(root: java.io.File, location: String): String? {
    val file = java.io.File(location).let { if (it.isAbsolute) it else java.io.File(root, location) }
    val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
    val relative = runCatching { canonical.relativeTo(root) }.getOrNull() ?: return null
    return relative.invariantSeparatorsPath.takeUnless { it.startsWith("..") }
}
