package app.andy.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Human-readable labels for sparse ACP tool-call metadata (especially Andy MCP). */
object AcpToolCallPresentation {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val genericTitles = setOf("", "tool", "Tool")
    /** Provider titles that are only a verb/kind — useful detail lives in args, locations, or a later update. */
    private val sparseToolTitles = setOf(
        "edit", "edit file", "editing files", "write", "delete", "create", "update",
        "read", "read file", "search", "grep", "fetch", "move", "copy",
        "terminal", "shell", "bash", "execute", "run", "command",
    )
    private val minimalSuccessOutput =
        Regex("""^\{\s*"?success"?\s*[:=]\s*true\s*\}$""", RegexOption.IGNORE_CASE)
    private val embeddedMcpToolName =
        Regex("""\bmcp_(?:andy|emu)_([a-z0-9_]+)\b""", RegexOption.IGNORE_CASE)
    private val fenceMarkerLine = Regex("""^(`{3,}|~{3,})\S*$""")
    private val actionTitle = Regex(
        """^(Edit(?:\s+File)?|Write|Delete|Create|Update|Read(?:\s+File)?|Search|Fetch|Move|Copy)\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val preferredArgKeys = listOf(
        "command", "cmd", "path", "file_path", "filePath", "file", "target",
        "uri", "url", "query", "pattern", "description",
    )

    data class Presented(
        val toolName: String,
        val summary: String,
        val detail: String,
    )

    fun present(
        title: String?,
        rawInput: String?,
        rawOutput: String?,
        contentDetails: String,
    ): Presented {
        val details = contentDetails.trim()
        val input = rawInput?.trim().orEmpty()
        val output = rawOutput?.trim().orEmpty()
        val action = parseActionTitle(title)
        val resolvedName = when {
            action != null -> formatMcpToolName(action.first)
            else -> displayToolName(title, input, details, output)
        }
        val (summary, detail) = formatSummary(resolvedName, input, output, details)
        val resolvedSummary = summary.ifBlank { action?.second.orEmpty() }
            .ifBlank { titleSummaryFallback(title) }
        return Presented(
            toolName = resolvedName,
            summary = resolvedSummary,
            detail = detail.ifBlank { resolvedSummary },
        )
    }

    fun mergeToolCalls(previous: AgentEvent.ToolCall, incoming: AgentEvent.ToolCall): AgentEvent.ToolCall {
        val mergedName = when {
            !isGenericOrSparseTitle(incoming.toolName) -> incoming.toolName
            !isGenericOrSparseTitle(previous.toolName) -> previous.toolName
            !isGenericTitle(incoming.toolName) -> incoming.toolName
            !isGenericTitle(previous.toolName) -> previous.toolName
            else -> displayToolName(incoming.toolName, incoming.detail, previous.detail)
        }
        val mergedDetail = richerDetail(previous.detail, incoming.detail)
        val mergedInput = extractLikelyInput(mergedDetail)
        val mergedOutput = extractLikelyOutput(mergedDetail)
        val presented = present(mergedName, mergedInput, mergedOutput, mergedDetail)
        val summary = when {
            !isMinimalOutput(incoming.summary) && incoming.summary.isNotBlank() -> incoming.summary
            !isMinimalOutput(previous.summary) && previous.summary.isNotBlank() -> previous.summary
            else -> presented.summary
        }
        val mergedLocations = (previous.locations + incoming.locations).distinct()
        val mergedImages = (previous.images + incoming.images).distinct()
        val kind = incoming.kind ?: previous.kind
        val resolvedSummary = enrichSummary(
            summary = summary.ifBlank { presented.summary },
            kind = kind,
            locations = mergedLocations,
        )
        return incoming.copy(
            toolName = presented.toolName.ifBlank { mergedName },
            summary = resolvedSummary,
            detail = presented.detail.ifBlank { mergedDetail },
            kind = kind,
            state = mergeToolState(previous.state, incoming.state),
            locations = mergedLocations,
            images = mergedImages,
        )
    }

    fun displayToolName(
        title: String?,
        vararg fallbacks: String?,
    ): String {
        val fromTitle = title?.trim().orEmpty()
        parseActionTitle(fromTitle)?.let { return formatMcpToolName(it.first) }
        if (!isGenericTitle(fromTitle)) return formatMcpToolName(fromTitle)
        fallbacks.forEach { candidate ->
            extractToolName(candidate)?.let { return formatMcpToolName(it) }
        }
        return fromTitle.ifBlank { "tool" }
    }

    /** True when the title is only a bare verb/kind with no path or command yet. */
    fun isSparseToolTitle(title: String): Boolean =
        title.trim().lowercase() in sparseToolTitles

    fun formatMcpToolName(name: String): String {
        val trimmed = name.trim()
        Regex("""^mcp_andy_(.+)$""", RegexOption.IGNORE_CASE).find(trimmed)?.let {
            return "Andy MCP · ${humanize(it.groupValues[1])}"
        }
        Regex("""^mcp_emu_(.+)$""", RegexOption.IGNORE_CASE).find(trimmed)?.let {
            return "Emu MCP · ${humanize(it.groupValues[1])}"
        }
        return trimmed
    }

    fun isGenericTitle(title: String): Boolean = title.trim() in genericTitles

    fun isGenericOrSparseTitle(title: String): Boolean =
        isGenericTitle(title) || isSparseToolTitle(title)

    fun isMinimalOutput(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || minimalSuccessOutput.matches(trimmed)) return true
        if (trimmed == "{}" || trimmed == "[]") return true
        parseJson(trimmed)?.let { obj ->
            if (obj.isEmpty()) return true
            val parts = obj.entries
                .filterNot { (key, _) -> key in toolNameKeys }
                .mapNotNull { (key, value) -> formatArgument(key, value) }
            if (parts.isEmpty()) return true
        }
        return false
    }

    /** Fill in a path-based summary when ACP only sent empty JSON arguments. */
    fun enrichSummary(
        summary: String,
        kind: AgentToolKind?,
        locations: List<String>,
    ): String {
        if (!isMinimalOutput(summary) && summary.isNotBlank()) {
            return if (looksLikeFilePath(summary)) shortenPath(summary) else summary
        }
        val paths = locations.map { it.trim() }.filter { it.isNotBlank() }
        if (paths.isNotEmpty()) return paths.joinToString(", ") { shortenPath(it) }
        val fromSummary = summary.takeUnless { isMinimalOutput(it) }.orEmpty()
        if (fromSummary.isNotBlank()) {
            return if (looksLikeFilePath(fromSummary)) shortenPath(fromSummary) else fromSummary
        }
        // Kind alone never makes a useful summary; callers phrase the fallback.
        return ""
    }

    internal fun formatSummary(
        toolName: String,
        rawInput: String,
        rawOutput: String,
        contentDetails: String,
    ): Pair<String, String> {
        val content = contentDetails.trim()
        val inputSummary = summarizeArguments(rawInput)
        val detail = when {
            content.isNotBlank() -> content
            rawInput.isNotBlank() && rawOutput.isNotBlank() &&
                !isMinimalOutput(rawInput) && !isMinimalOutput(rawOutput) ->
                "$rawInput\n$rawOutput"
            rawInput.isNotBlank() && !isMinimalOutput(rawInput) -> rawInput
            rawOutput.isNotBlank() && !isMinimalOutput(rawOutput) -> rawOutput
            else -> ""
        }
        val firstContentLine = firstMeaningfulLine(content)
        val summary = when {
            inputSummary.isNotBlank() -> inputSummary
            firstContentLine.isNotBlank() && !isMinimalOutput(firstContentLine) ->
                summarizeArguments(firstContentLine).ifBlank {
                    if (looksLikeFilePath(firstContentLine)) shortenPath(firstContentLine) else firstContentLine
                }
            rawOutput.isNotBlank() && !isMinimalOutput(rawOutput) -> firstMeaningfulLine(rawOutput)
            else -> firstMeaningfulLine(detail).let { line ->
                if (looksLikeFilePath(line)) shortenPath(line) else line
            }
        }
        return summary to detail
    }

    /** "Edit src/Foo.kt" / "Delete `path`" → action verb + remainder for the summary line. */
    internal fun parseActionTitle(title: String?): Pair<String, String>? {
        val trimmed = title?.trim().orEmpty()
        if (trimmed.isEmpty() || isSparseToolTitle(trimmed)) return null
        val match = actionTitle.matchEntire(trimmed) ?: return null
        val rest = match.groupValues[2].trim().trim('`', '"', '\'')
        if (rest.isEmpty()) return null
        return match.groupValues[1] to rest
    }

    private fun titleSummaryFallback(title: String?): String {
        val trimmed = title?.trim().orEmpty()
        if (trimmed.isEmpty() || isGenericOrSparseTitle(trimmed)) return ""
        if (trimmed.startsWith("mcp_", ignoreCase = true)) return ""
        if (trimmed.startsWith("Andy MCP", ignoreCase = true) ||
            trimmed.startsWith("Emu MCP", ignoreCase = true)
        ) {
            return ""
        }
        // Permission-time execute titles are often the raw command string.
        return trimmed
    }

    private fun mergeToolState(previous: AgentToolState, incoming: AgentToolState): AgentToolState = when {
        incoming == AgentToolState.Failed || previous == AgentToolState.Failed -> AgentToolState.Failed
        incoming == AgentToolState.Completed || previous == AgentToolState.Completed -> AgentToolState.Completed
        incoming == AgentToolState.InProgress || previous == AgentToolState.InProgress -> AgentToolState.InProgress
        else -> incoming
    }

    private fun looksLikeFilePath(text: String): Boolean {
        val trimmed = text.trim().trim('`', '"', '\'')
        if (trimmed.isEmpty() || trimmed.contains('\n') || trimmed.length > 512) return false
        if (trimmed.any { it.isWhitespace() }) return false
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return false
        val leaf = trimmed.substringAfterLast('/').substringAfterLast('\\')
        return trimmed.contains('/') || trimmed.contains('\\') || leaf.contains('.')
    }

    /** First non-blank line, skipping bare code-fence delimiters (```` ``` ````, ```` ```console ````, `~~~`). */
    private fun firstMeaningfulLine(text: String): String =
        text.lineSequence()
            .firstOrNull { it.isNotBlank() && !fenceMarkerLine.matches(it.trim()) }
            .orEmpty()

    private fun richerDetail(first: String, second: String): String {
        val a = first.trim()
        val b = second.trim()
        return when {
            a.isBlank() -> b
            b.isBlank() -> a
            a == b -> a
            b.contains(a) -> b
            a.contains(b) -> a
            else -> "$a\n$b"
        }
    }

    private fun extractLikelyInput(detail: String): String? {
        val lines = detail.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return null
        return lines.firstOrNull { looksLikeArguments(it) }
    }

    private fun extractLikelyOutput(detail: String): String? {
        val lines = detail.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return null
        return lines.lastOrNull { isMinimalOutput(it) || looksLikeJson(it) }
    }

    private fun extractToolName(text: String?): String? {
        val candidate = text?.trim().orEmpty()
        if (candidate.isEmpty()) return null
        if (!isGenericTitle(candidate) && candidate.startsWith("mcp_", ignoreCase = true)) {
            return embeddedMcpToolName.find(candidate)?.value ?: candidate
        }
        embeddedMcpToolName.find(candidate)?.value?.let { return it }
        parseJson(candidate)?.let { obj ->
            toolNameKeys.forEach { key ->
                obj[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }

    private fun summarizeArguments(rawInput: String): String {
        val input = rawInput.trim()
        if (input.isEmpty() || isMinimalOutput(input)) return ""
        parseJson(input)?.let { obj ->
            val preferred = preferredArgKeys.mapNotNull { key ->
                obj[key]?.let { value -> formatArgumentValue(value)?.let { key to it } }
            }
            // Shell/file tools: lead with the command or path alone so the headline stays readable.
            preferred.firstOrNull { (key, _) -> key in setOf("command", "cmd", "path", "file_path", "filePath", "file") }
                ?.let { (_, value) -> return if (looksLikeFilePath(value)) shortenPath(value) else value }
            if (preferred.isNotEmpty()) {
                return preferred.joinToString(", ") { (key, value) -> "$key=$value" }
            }
            val parts = obj.entries
                .filterNot { (key, _) -> key in toolNameKeys }
                .mapNotNull { (key, value) -> formatArgument(key, value) }
            return if (parts.isNotEmpty()) parts.joinToString(", ") else ""
        }
        return input.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun formatArgument(key: String, value: JsonElement): String? {
        val rendered = formatArgumentValue(value) ?: return null
        return "$key=$rendered"
    }

    private fun formatArgumentValue(value: JsonElement): String? {
        val rendered = when (value) {
            is JsonPrimitive -> value.contentOrNull ?: value.toString()
            else -> value.toString()
        }.trim()
        return rendered.takeIf { it.isNotBlank() }
    }

    private fun parseJson(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject

    private fun looksLikeArguments(text: String): Boolean =
        text.startsWith("{") || text.contains("=")

    private fun looksLikeJson(text: String): Boolean =
        text.startsWith("{") || text.startsWith("[")

    private fun humanize(segment: String): String = segment.replace('_', ' ')

    private fun shortenPath(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\').ifBlank { path }

    private val toolNameKeys = listOf("name", "tool", "toolName", "function", "function_name")
}
