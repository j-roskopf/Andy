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
    private val minimalSuccessOutput =
        Regex("""^\{\s*"?success"?\s*[:=]\s*true\s*\}$""", RegexOption.IGNORE_CASE)
    private val embeddedMcpToolName =
        Regex("""\bmcp_(?:andy|emu)_([a-z0-9_]+)\b""", RegexOption.IGNORE_CASE)

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
        val resolvedName = displayToolName(title, input, details, output)
        val (summary, detail) = formatSummary(resolvedName, input, output, details)
        return Presented(
            toolName = resolvedName,
            summary = summary,
            detail = detail.ifBlank { summary },
        )
    }

    fun mergeToolCalls(previous: AgentEvent.ToolCall, incoming: AgentEvent.ToolCall): AgentEvent.ToolCall {
        val mergedName = when {
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
        val resolvedSummary = enrichSummary(
            summary = summary.ifBlank { presented.summary },
            kind = incoming.kind ?: previous.kind,
            locations = mergedLocations,
        )
        return incoming.copy(
            toolName = presented.toolName.ifBlank { mergedName },
            summary = resolvedSummary,
            detail = presented.detail.ifBlank { mergedDetail },
            locations = mergedLocations,
        )
    }

    fun displayToolName(
        title: String?,
        vararg fallbacks: String?,
    ): String {
        val fromTitle = title?.trim().orEmpty()
        if (!isGenericTitle(fromTitle)) return formatMcpToolName(fromTitle)
        fallbacks.forEach { candidate ->
            extractToolName(candidate)?.let { return formatMcpToolName(it) }
        }
        return fromTitle.ifBlank { "tool" }
    }

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
        if (!isMinimalOutput(summary) && summary.isNotBlank()) return summary
        val paths = locations.map { it.trim() }.filter { it.isNotBlank() }
        if (paths.isEmpty()) return summary.takeUnless { isMinimalOutput(it) }.orEmpty()
        return paths.joinToString(", ") { shortenPath(it) }
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
        val firstContentLine = content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val summary = when {
            inputSummary.isNotBlank() -> inputSummary
            firstContentLine.isNotBlank() && !isMinimalOutput(firstContentLine) ->
                summarizeArguments(firstContentLine).ifBlank { firstContentLine }
            rawOutput.isNotBlank() && !isMinimalOutput(rawOutput) ->
                rawOutput.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            else -> detail.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        }
        return summary to detail
    }

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
            val parts = obj.entries
                .filterNot { (key, _) -> key in toolNameKeys }
                .mapNotNull { (key, value) -> formatArgument(key, value) }
            return if (parts.isNotEmpty()) parts.joinToString(", ") else ""
        }
        return input.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun formatArgument(key: String, value: JsonElement): String? {
        val rendered = when (value) {
            is JsonPrimitive -> value.contentOrNull ?: value.toString()
            else -> value.toString()
        }.trim()
        return if (rendered.isBlank()) null else "$key=$rendered"
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
