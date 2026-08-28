package app.andy.desktop.service.agents

import app.andy.model.AgentEvent
import app.andy.model.AgentThreadChangeSnapshot
import app.andy.model.AgentToolState
import app.andy.model.coalesceAcpTranscriptEvents
import app.andy.model.stripTrailingConnectionStallError
import java.util.UUID

internal data class FileChangesEnrichmentResult(
    val display: List<AgentEvent>,
    val newlyPersisted: List<AgentEvent.FileChanges>,
)

/**
 * Synthesizes [AgentEvent.FileChanges] rows for legacy ACP transcript segments that have
 * completed mutating tool calls but no persisted file-changes event yet.
 *
 * When [synthesizeTrailingSegment] is false, the open (incomplete) turn is left without a
 * synthesized card — edited-files UI should only appear at turn end.
 */
internal object AgentFileChangesEnrichment {
    fun enrichIncremental(
        worktrees: WorktreeManager,
        cwd: String,
        baseline: String,
        events: List<AgentEvent>,
        segmentPaths: (List<AgentEvent>) -> Set<String>,
        synthesizeTrailingSegment: Boolean = true,
    ): FileChangesEnrichmentResult {
        if (events.isEmpty()) return FileChangesEnrichmentResult(events, emptyList())
        val output = mutableListOf<AgentEvent>()
        val newlyPersisted = mutableListOf<AgentEvent.FileChanges>()
        var segment = mutableListOf<AgentEvent>()

        fun flushSegment(synthesizeIfMissing: Boolean) {
            if (segment.isEmpty()) return
            val (enriched, synthesized) = enrichTurnSegment(
                worktrees = worktrees,
                cwd = cwd,
                baseline = baseline,
                segment = segment,
                segmentPaths = segmentPaths,
                synthesizeIfMissing = synthesizeIfMissing,
            )
            synthesized?.let { newlyPersisted += it }
            output += enriched
            segment = mutableListOf()
        }

        for (event in events) {
            if (isTranscriptTurnBoundary(event)) {
                // Closed by a turn boundary — always allow synthesis for legacy segments.
                flushSegment(synthesizeIfMissing = true)
                output += event
            } else {
                segment += event
            }
        }
        // Trailing open segment: only synthesize when the turn is finishing.
        flushSegment(synthesizeIfMissing = synthesizeTrailingSegment)
        return FileChangesEnrichmentResult(
            display = stripStaleFileChanges(worktrees, cwd, output),
            newlyPersisted = newlyPersisted,
        )
    }

    private fun stripStaleFileChanges(
        worktrees: WorktreeManager,
        cwd: String,
        events: List<AgentEvent>,
    ): List<AgentEvent> = events.mapNotNull { event ->
        when (event) {
            is AgentEvent.FileChanges -> revalidateFileChange(worktrees, cwd, event)
            else -> event
        }
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
        synthesizeIfMissing: Boolean = true,
    ): Pair<List<AgentEvent>, AgentEvent.FileChanges?> {
        val paths = segmentPaths(segment)
        val baseSegment = segment.filter { it !is AgentEvent.FileChanges || it.undone }
        val retained = segment
            .filterIsInstance<AgentEvent.FileChanges>()
            .filter { !it.undone }
            .filter { fileChangesBelongsToSegment(it, paths, segment) }

        val fileChanges = when {
            retained.isNotEmpty() -> retained.last()
            !synthesizeIfMissing || paths.isEmpty() -> null
            else -> {
                val snapshot = worktrees.changeSnapshot(cwd, baseline, paths) ?: return baseSegment to null
                if (snapshot.summary.files.isEmpty()) return baseSegment to null
                val atMillis = segment.lastOrNull()?.atMillis ?: System.currentTimeMillis()
                AgentEvent.FileChanges(
                    atMillis = atMillis,
                    batchId = UUID.randomUUID().toString(),
                    baselineTree = baseline,
                    snapshot = snapshot,
                )
            }
        } ?: return baseSegment to null

        val insertBefore = fileChangesInsertIndex(baseSegment)
        val enriched = buildList {
            addAll(baseSegment.subList(0, insertBefore))
            add(fileChanges)
            addAll(baseSegment.subList(insertBefore, baseSegment.size))
        }
        val synthesized = if (retained.isEmpty()) fileChanges else null
        return enriched to synthesized
    }

    /**
     * Drops [AgentEvent.FileChanges] rows whose paths are not attributable to this segment's
     * mutating tool calls. Legacy persistence appended cards at transcript end, which made them
     * show up on unrelated follow-ups such as a skill-only `/gh-ship-pr` turn.
     */
    internal fun fileChangesBelongsToSegment(
        change: AgentEvent.FileChanges,
        segmentPaths: Set<String>,
        segment: List<AgentEvent> = emptyList(),
    ): Boolean {
        val filePaths = change.snapshot.summary.files.map { it.path }.toSet()
        if (filePaths.isEmpty()) return false
        if (segmentPaths.isNotEmpty()) return filePaths.all { it in segmentPaths }
        val nonFileChanges = segment.filter { it !is AgentEvent.FileChanges || it.undone }
        return nonFileChanges.isEmpty()
    }

    /** Place the card after tool activity but before the assistant's closing message. */
    internal fun fileChangesInsertIndex(segment: List<AgentEvent>): Int {
        val taskResult = segment.indexOfLast { it is AgentEvent.TaskResult }.takeIf { it >= 0 }
        val assistant = segment.indexOfFirst { event ->
            event is AgentEvent.AssistantText &&
                !event.isStreamDelta &&
                event.text.stripTrailingConnectionStallError().isNotBlank()
        }.takeIf { it >= 0 }
        return listOfNotNull(taskResult, assistant).minOrNull() ?: segment.size
    }

    /** Hide cards whose working tree no longer differs from the batch baseline (e.g. after commit). */
    internal fun revalidateFileChange(
        worktrees: WorktreeManager,
        cwd: String,
        change: AgentEvent.FileChanges,
    ): AgentEvent.FileChanges? {
        if (change.undone) return null
        val paths = change.snapshot.summary.files.map { it.path }
        if (paths.isEmpty()) return null
        if (!worktrees.pathsHaveUncommittedChanges(cwd, paths)) return null
        return change
    }

    internal fun fileChangesAlreadyRecorded(
        events: List<AgentEvent>,
        synthesized: AgentEvent.FileChanges,
    ): Boolean {
        val paths = synthesized.snapshot.summary.files.map { it.path }.toSet()
        if (paths.isEmpty()) return true
        return events.any { event ->
            event is AgentEvent.FileChanges &&
                !event.undone &&
                event.baselineTree == synthesized.baselineTree &&
                event.snapshot.summary.files.map { it.path }.toSet() == paths
        }
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
