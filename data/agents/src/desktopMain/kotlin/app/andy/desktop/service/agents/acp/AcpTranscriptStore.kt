package app.andy.desktop.service.agents.acp

import app.andy.model.AcpToolCallPresentation
import app.andy.model.AgentEvent
import app.andy.model.coalesceAgentStreamDeltas
import app.andy.model.AgentPlanEntry
import app.andy.model.AgentQuotaWindow
import app.andy.model.AgentSkill
import app.andy.model.AgentSlashCommand
import app.andy.model.AgentThreadChangeSnapshot
import app.andy.model.AgentChangeSummary
import app.andy.model.AgentFileChange
import app.andy.model.AgentFileDiff
import app.andy.model.DiffLineKind
import app.andy.model.AgentToolImage
import app.andy.model.AgentToolKind
import app.andy.model.AgentToolState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Crash-safe JSONL transcript used by ACP and by the GUI attach process. */
class AcpTranscriptStore(
    private val fileFor: (String) -> File,
    private val maxBytes: Long = 8L * 1024L * 1024L,
) {
    private val locks = ConcurrentHashMap<String, Any>()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun append(taskId: String, event: AgentEvent) = synchronized(locks.computeIfAbsent(taskId) { Any() }) {
        persistEvent(fileFor(taskId), event)
    }

    fun upsert(taskId: String, event: AgentEvent) = synchronized(locks.computeIfAbsent(taskId) { Any() }) {
        val incoming = event as? AgentEvent.ToolCall
        val id = incoming?.toolCallId
        if (incoming == null || id == null) {
            appendUnlocked(taskId, event)
            return@synchronized
        }
        val file = fileFor(taskId)
        val entries = coalesceLoaded(loadUnlocked(file)).map { it.toDto() }.toMutableList()
        val index = entries.indexOfLast { (it.toModel() as? AgentEvent.ToolCall)?.toolCallId == id }
        if (index < 0) {
            entries += incoming.toDto()
        } else {
            // A provider update repeats only what changed: cursor-agent sends the title once and
            // the output later, with no title and often no status. Overwriting the stored row threw
            // away whichever half arrived first, which is how completed calls persisted as pending
            // rows with no arguments. Merge exactly as the in-memory transcript does.
            val previous = entries[index].toModel() as? AgentEvent.ToolCall
            val merged = previous?.let { AcpToolCallPresentation.mergeToolCalls(it, incoming) } ?: incoming
            entries[index] = merged.toDto()
        }
        writeUnlocked(file, entries)
        trim(file)
    }

    fun load(taskId: String): List<AgentEvent> = synchronized(locks.computeIfAbsent(taskId) { Any() }) {
        coalesceLoaded(loadUnlocked(fileFor(taskId)))
    }

    fun markFileChangesUndone(taskId: String, batchId: String) = synchronized(locks.computeIfAbsent(taskId) { Any() }) {
        val file = fileFor(taskId)
        val entries = coalesceLoaded(loadUnlocked(file)).map { it.toDto() }.toMutableList()
        val index = entries.indexOfFirst { it.type == "file-changes" && it.batchId == batchId }
        if (index < 0) return@synchronized
        entries[index] = entries[index].copy(fileChangesUndone = true)
        writeUnlocked(file, entries)
    }

    private fun appendUnlocked(taskId: String, event: AgentEvent) {
        persistEvent(fileFor(taskId), event)
    }

    private fun loadUnlocked(file: File): List<TranscriptEvent> {
        if (!file.isFile) return emptyList()
        return file.readLines().mapNotNull { line -> runCatching { json.decodeFromString(TranscriptEvent.serializer(), line) }.getOrNull() }
    }

    private fun writeUnlocked(file: File, entries: List<TranscriptEvent>) {
        file.parentFile?.mkdirs()
        file.writeText(entries.joinToString(separator = "\n", postfix = if (entries.isNotEmpty()) "\n" else "") {
            json.encodeToString(TranscriptEvent.serializer(), it)
        })
    }

    private fun coalesceLoaded(entries: List<TranscriptEvent>): List<AgentEvent> =
        coalesceAgentStreamDeltas(emptyList(), entries.mapNotNull { it.toModel() })

    /**
     * Append stream deltas in O(1) and compact to coalesced rows when a turn boundary arrives.
     * Rewriting the accumulated response on every token was quadratic; compaction keeps disk usage
     * bounded without paying that cost per delta.
     */
    private fun persistEvent(file: File, event: AgentEvent) {
        file.parentFile?.mkdirs()
        appendLine(file, event)
        if (!isStreamDeltaEvent(event)) {
            compactUnlocked(file)
        }
        trim(file)
    }

    private fun compactUnlocked(file: File) {
        val coalesced = coalesceLoaded(loadUnlocked(file))
        writeUnlocked(file, coalesced.map { it.toDto() })
    }

    private fun trim(file: File) {
        if (!file.isFile || file.length() <= maxBytes) return
        val bytes = file.readBytes()
        val start = bytes.size - maxBytes.toInt()
        val aligned = bytes.copyOfRange(start, bytes.size).toString(Charsets.UTF_8).indexOf('\n')
        val retained = bytes.copyOfRange(start + aligned + 1, bytes.size)
        file.writeBytes(retained)
    }

    private fun isStreamDeltaEvent(event: AgentEvent): Boolean = when (event) {
        is AgentEvent.AssistantText -> event.isStreamDelta
        is AgentEvent.Thinking -> event.isStreamDelta
        else -> false
    }

    private fun appendLine(file: File, event: AgentEvent) {
        file.appendText(json.encodeToString(TranscriptEvent.serializer(), event.toDto()) + "\n")
    }

}

@Serializable
private data class TranscriptEvent(
    val type: String,
    val atMillis: Long,
    val text: String = "",
    val isStreamDelta: Boolean = false,
    val sessionId: String = "",
    val model: String = "",
    val skills: List<TranscriptSkill> = emptyList(),
    val images: List<String> = emptyList(),
    val toolImages: List<String> = emptyList(),
    val toolName: String = "",
    val toolCallId: String = "",
    val summary: String = "",
    val detail: String = "",
    val toolKind: String = "",
    val toolState: String = AgentToolState.Completed.name,
    val locations: List<String> = emptyList(),
    val isError: Boolean = false,
    val quotaWindows: List<TranscriptQuotaWindow> = emptyList(),
    val success: Boolean = false,
    val finalText: String = "",
    val costUsd: Double = 0.0,
    val costIsEstimated: Boolean = false,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val durationMs: Long = 0,
    val usedTokens: Long = 0,
    val windowTokens: Long = 0,
    val plan: List<TranscriptPlanEntry> = emptyList(),
    val planMarkdown: String = "",
    val modeId: String = "",
    val commands: List<TranscriptCommand> = emptyList(),
    val modes: List<TranscriptMode> = emptyList(),
    val currentModeId: String = "",
    val requestId: String = "",
    val question: String = "",
    val options: List<TranscriptOption> = emptyList(),
    val optionId: String = "",
    val allowed: Boolean = false,
    val note: String = "",
    val rawLine: String = "",
    val batchId: String = "",
    val baselineTree: String = "",
    val fileChangesUndone: Boolean = false,
    val fileChanges: List<TranscriptFileChange> = emptyList(),
    val fileDiffs: List<TranscriptFileDiff> = emptyList(),
)

@Serializable private data class TranscriptFileChange(val path: String, val additions: Int, val deletions: Int)
@Serializable private data class TranscriptFileDiff(
    val path: String,
    val lines: List<TranscriptDiffLine> = emptyList(),
    val additions: Int = 0,
    val deletions: Int = 0,
    val isBinary: Boolean = false,
    val isNewFile: Boolean = false,
)
@Serializable private data class TranscriptDiffLine(
    val kind: String,
    val text: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null,
)

@Serializable private data class TranscriptSkill(val name: String, val path: String)
@Serializable private data class TranscriptQuotaWindow(val label: String, val fraction: Float? = null, val resetAt: Long? = null, val detail: String? = null)
@Serializable private data class TranscriptPlanEntry(val content: String, val status: String)
@Serializable private data class TranscriptCommand(val name: String, val description: String, val inputHint: String? = null)
@Serializable private data class TranscriptMode(val id: String, val name: String, val description: String? = null)
@Serializable private data class TranscriptOption(val label: String, val description: String)

private fun AgentEvent.toDto(): TranscriptEvent = when (this) {
    is AgentEvent.SessionStarted -> TranscriptEvent("session", atMillis, sessionId = sessionId.orEmpty(), model = model.orEmpty())
    is AgentEvent.AssistantText -> TranscriptEvent("assistant", atMillis, text = text, isStreamDelta = isStreamDelta)
    is AgentEvent.Thinking -> TranscriptEvent("thinking", atMillis, text = text, isStreamDelta = isStreamDelta)
    is AgentEvent.UserMessage -> TranscriptEvent("user", atMillis, text = text, skills = skills.map { TranscriptSkill(it.name, it.path) }, images = imagePaths)
    is AgentEvent.ToolCall -> TranscriptEvent("tool", atMillis, toolName = toolName, toolCallId = toolCallId.orEmpty(), summary = summary, detail = detail, toolKind = kind?.name.orEmpty(), toolState = state.name, locations = locations, toolImages = images.map { it.dataUri })
    is AgentEvent.ToolResult -> TranscriptEvent("tool-result", atMillis, toolName = toolName.orEmpty(), summary = summary, detail = detail, isError = isError, quotaWindows = quotaWindows.map { TranscriptQuotaWindow(it.label, it.remainingFraction, it.resetAtMillis, it.detail) })
    is AgentEvent.TaskError -> TranscriptEvent("error", atMillis, text = message)
    is AgentEvent.TaskResult -> TranscriptEvent("result", atMillis, success = success, finalText = finalText.orEmpty(), costUsd = costUsd ?: 0.0, costIsEstimated = costIsEstimated, inputTokens = inputTokens ?: 0, outputTokens = outputTokens ?: 0, durationMs = durationMs ?: 0)
    is AgentEvent.ContextUsage -> TranscriptEvent("usage", atMillis, usedTokens = usedTokens ?: 0, windowTokens = windowTokens ?: 0)
    is AgentEvent.PlanUpdate -> TranscriptEvent(
        "plan",
        atMillis,
        plan = entries.map { TranscriptPlanEntry(it.content, it.status) },
        planMarkdown = markdown.orEmpty(),
    )
    is AgentEvent.ModeChanged -> TranscriptEvent("mode", atMillis, modeId = modeId)
    is AgentEvent.AvailableCommands -> TranscriptEvent("commands", atMillis, commands = commands.map { TranscriptCommand(it.name, it.description, it.inputHint) })
    is AgentEvent.AvailableModes -> TranscriptEvent("modes", atMillis, modes = modes.map { TranscriptMode(it.id, it.name, it.description) }, currentModeId = currentModeId.orEmpty())
    is AgentEvent.PermissionRequest -> TranscriptEvent("permission", atMillis, requestId = requestId, toolName = toolName, question = question, options = options.map { TranscriptOption(it.label, it.description) })
    is AgentEvent.PermissionResolved -> TranscriptEvent("permission-resolved", atMillis, requestId = requestId, optionId = optionId, allowed = allowed, note = note.orEmpty())
    is AgentEvent.FileChanges -> TranscriptEvent(
        type = "file-changes",
        atMillis = atMillis,
        batchId = batchId,
        baselineTree = baselineTree,
        fileChangesUndone = undone,
        fileChanges = snapshot.summary.files.map { TranscriptFileChange(it.path, it.additions, it.deletions) },
        fileDiffs = snapshot.diffs.values.map { diff ->
            TranscriptFileDiff(
                path = diff.path,
                lines = diff.lines.map { line ->
                    TranscriptDiffLine(
                        kind = line.kind.name,
                        text = line.text,
                        oldLineNumber = line.oldLineNumber,
                        newLineNumber = line.newLineNumber,
                    )
                },
                additions = diff.additions,
                deletions = diff.deletions,
                isBinary = diff.isBinary,
                isNewFile = diff.isNewFile,
            )
        },
    )
    is AgentEvent.Raw -> TranscriptEvent("raw", atMillis, rawLine = line)
}

private fun TranscriptEvent.toModel(): AgentEvent? = when (type) {
    "session" -> AgentEvent.SessionStarted(atMillis, sessionId.takeIf { it.isNotBlank() }, model.takeIf { it.isNotBlank() })
    "assistant" -> AgentEvent.AssistantText(atMillis, text, isStreamDelta)
    "thinking" -> AgentEvent.Thinking(atMillis, text, isStreamDelta)
    "user" -> AgentEvent.UserMessage(atMillis, text, skills.map { AgentSkill(it.name, "", it.path) }, images)
    "tool" -> {
        val storedKind = AgentToolKind.entries.firstOrNull { it.name == toolKind } ?: AgentToolKind.Other
        val resolvedKind = if (storedKind == AgentToolKind.Other) {
            AcpToolCallPresentation.inferKindFromTitle(toolName)
                ?: AcpToolCallPresentation.inferKindFromArguments(detail)
                ?: storedKind
        } else {
            storedKind
        }
        AgentEvent.ToolCall(atMillis, toolName, summary, detail.ifBlank { summary }, toolCallId.takeIf { it.isNotBlank() }, resolvedKind, AgentToolState.entries.firstOrNull { it.name == toolState } ?: AgentToolState.Completed, locations, toolImages.map { AgentToolImage(it) })
    }
    "tool-result" -> AgentEvent.ToolResult(atMillis, toolName.takeIf { it.isNotBlank() }, summary, detail.ifBlank { summary }, isError, quotaWindows.map { AgentQuotaWindow(it.label, it.fraction, it.resetAt, it.detail) })
    "error" -> AgentEvent.TaskError(atMillis, text)
    "result" -> AgentEvent.TaskResult(atMillis, success, finalText.takeIf { it.isNotBlank() }, costUsd.takeIf { it != 0.0 }, costIsEstimated, inputTokens.takeIf { it != 0L }, outputTokens.takeIf { it != 0L }, durationMs.takeIf { it != 0L })
    "usage" -> AgentEvent.ContextUsage(atMillis, usedTokens.takeIf { it != 0L }, windowTokens.takeIf { it != 0L })
    "plan" -> AgentEvent.PlanUpdate(
        atMillis,
        plan.map { AgentPlanEntry(it.content, it.status) },
        planMarkdown.takeIf { it.isNotBlank() },
    )
    "mode" -> AgentEvent.ModeChanged(atMillis, modeId)
    "commands" -> AgentEvent.AvailableCommands(atMillis, commands.map { AgentSlashCommand(it.name, it.description, it.inputHint) })
    "modes" -> AgentEvent.AvailableModes(atMillis, modes.map { app.andy.model.AgentSessionMode(it.id, it.name, it.description) }, currentModeId.takeIf { it.isNotBlank() })
    "permission" -> AgentEvent.PermissionRequest(atMillis, requestId, toolName, question, options.map { app.andy.model.AgentUserInputOption(it.label, it.description) })
    "permission-resolved" -> AgentEvent.PermissionResolved(atMillis, requestId, optionId, allowed, note.takeIf { it.isNotBlank() })
    "file-changes" -> {
        val summary = AgentChangeSummary(
            fileChanges.map { AgentFileChange(it.path, it.additions, it.deletions) },
        )
        val diffs = fileDiffs.associate { diff ->
            diff.path to AgentFileDiff(
                path = diff.path,
                lines = diff.lines.map { line ->
                    app.andy.model.DiffLine(
                        kind = DiffLineKind.entries.firstOrNull { it.name == line.kind } ?: DiffLineKind.Context,
                        text = line.text,
                        oldLineNumber = line.oldLineNumber,
                        newLineNumber = line.newLineNumber,
                    )
                },
                additions = diff.additions,
                deletions = diff.deletions,
                isBinary = diff.isBinary,
                isNewFile = diff.isNewFile,
            )
        }
        AgentEvent.FileChanges(
            atMillis = atMillis,
            batchId = batchId,
            baselineTree = baselineTree,
            snapshot = AgentThreadChangeSnapshot(summary = summary, diffs = diffs),
            undone = fileChangesUndone,
        )
    }
    "raw" -> AgentEvent.Raw(atMillis, rawLine)
    else -> null
}
