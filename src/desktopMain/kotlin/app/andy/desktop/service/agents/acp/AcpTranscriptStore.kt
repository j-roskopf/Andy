package app.andy.desktop.service.agents.acp

import app.andy.model.AcpToolCallPresentation
import app.andy.model.AgentEvent
import app.andy.model.coalesceAgentStreamDeltas
import app.andy.model.AgentPlanEntry
import app.andy.model.AgentQuotaWindow
import app.andy.model.AgentSkill
import app.andy.model.AgentSlashCommand
import app.andy.model.AgentToolImage
import app.andy.model.AgentToolKind
import app.andy.model.AgentToolState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/** Crash-safe JSONL transcript used by ACP and by the GUI attach process. */
class AcpTranscriptStore(
    private val fileFor: (String) -> File,
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
        val entries = loadUnlocked(file).toMutableList()
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
    }

    fun load(taskId: String): List<AgentEvent> = synchronized(locks.computeIfAbsent(taskId) { Any() }) {
        loadUnlocked(fileFor(taskId)).mapNotNull { it.toModel() }
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

    /**
     * Persist one event, folding stream deltas into the previous JSONL row when possible so disk
     * usage tracks the coalesced in-memory transcript instead of one line per token.
     */
    private fun persistEvent(file: File, event: AgentEvent) {
        file.parentFile?.mkdirs()
        val last = readLastTranscriptEvent(file)
        if (last == null) {
            appendLine(file, event)
            return
        }
        val merged = coalesceAgentStreamDeltas(listOf(last), listOf(event))
        when {
            merged.size == 1 && merged[0] != last ->
                replaceLastLine(file, json.encodeToString(merged[0].toDto()))
            merged.size == 2 && merged[0] == last ->
                appendLine(file, merged[1])
            else ->
                appendLine(file, event)
        }
    }

    private fun appendLine(file: File, event: AgentEvent) {
        file.appendText(json.encodeToString(TranscriptEvent.serializer(), event.toDto()) + "\n")
    }

    private fun readLastTranscriptEvent(file: File): AgentEvent? {
        val line = readLastNonemptyLine(file) ?: return null
        return runCatching { json.decodeFromString(TranscriptEvent.serializer(), line).toModel() }.getOrNull()
    }

    private fun readLastNonemptyLine(file: File): String? {
        if (!file.isFile || file.length() == 0L) return null
        RandomAccessFile(file, "r").use { raf ->
            var end = raf.length() - 1
            while (end >= 0) {
                raf.seek(end)
                if (raf.readByte() != '\n'.code.toByte()) break
                end--
            }
            if (end < 0) return null
            var start = end
            while (start > 0) {
                raf.seek(start - 1)
                if (raf.readByte() == '\n'.code.toByte()) break
                start--
            }
            val length = (end - start).toInt() + 1
            val bytes = ByteArray(length)
            raf.seek(start)
            raf.readFully(bytes)
            return bytes.toString(Charsets.UTF_8)
        }
    }

    private fun replaceLastLine(file: File, line: String) {
        RandomAccessFile(file, "rw").use { raf ->
            val lineStart = lastLineStartOffset(raf)
            raf.setLength(lineStart)
            raf.seek(lineStart)
            raf.write((line + "\n").toByteArray(Charsets.UTF_8))
        }
    }

    /** Byte offset where the final logical line begins (0 when the file is a single line). */
    private fun lastLineStartOffset(raf: RandomAccessFile): Long {
        var end = raf.length() - 1
        if (end < 0) return 0
        while (end >= 0) {
            raf.seek(end)
            if (raf.readByte() != '\n'.code.toByte()) break
            end--
        }
        if (end < 0) return 0
        var start = end
        while (start > 0) {
            raf.seek(start - 1)
            if (raf.readByte() == '\n'.code.toByte()) return start
            start--
        }
        return 0
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
    is AgentEvent.Raw -> TranscriptEvent("raw", atMillis, rawLine = line)
}

private fun TranscriptEvent.toModel(): AgentEvent? = when (type) {
    "session" -> AgentEvent.SessionStarted(atMillis, sessionId.takeIf { it.isNotBlank() }, model.takeIf { it.isNotBlank() })
    "assistant" -> AgentEvent.AssistantText(atMillis, text, isStreamDelta)
    "thinking" -> AgentEvent.Thinking(atMillis, text, isStreamDelta)
    "user" -> AgentEvent.UserMessage(atMillis, text, skills.map { AgentSkill(it.name, "", it.path) }, images)
    "tool" -> AgentEvent.ToolCall(atMillis, toolName, summary, detail.ifBlank { summary }, toolCallId.takeIf { it.isNotBlank() }, AgentToolKind.entries.firstOrNull { it.name == toolKind }, AgentToolState.entries.firstOrNull { it.name == toolState } ?: AgentToolState.Completed, locations, toolImages.map { AgentToolImage(it) })
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
    "raw" -> AgentEvent.Raw(atMillis, rawLine)
    else -> null
}
