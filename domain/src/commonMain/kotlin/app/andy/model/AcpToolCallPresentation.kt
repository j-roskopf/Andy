package app.andy.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Human-readable labels for sparse ACP tool-call metadata (especially Andy MCP). */
object AcpToolCallPresentation {
    /** Control-character framing avoids collisions with ordinary edited file or command content. */
    const val DetailSeparator = "\u001eandy-tool-output\u001f"

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
    /** Placeholders some providers send in place of an empty payload; they state nothing. */
    private val placeholderOutput = setOf("no details", "none", "n/a", "null", "undefined")
    private val diffHunkHeader = Regex("""(?m)^@@ .+ @@""")
    private const val InlineValueLimit = 160
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
    /** `"file_path":` in raw arguments, `- **file path:**` once [renderJsonMarkdown] has run. */
    private val argumentKey = Regex("""\*\*([A-Za-z0-9 _]{1,40}):\*\*|"([A-Za-z0-9_]{1,40})"\s*:""")
    private val commandArgumentKeys = setOf("command", "cmd", "script", "shell_command")
    private val editArgumentKeys = setOf(
        "old_string", "new_string", "old_str", "new_str", "replace_all",
        "content", "contents", "file_text", "patch", "diff", "edits",
    )
    private val searchArgumentKeys = setOf(
        "pattern", "glob", "glob_pattern", "regex", "query", "search_term", "output_mode",
    )
    private val readArgumentKeys = setOf(
        "file_path", "filepath", "path", "target_file", "notebook_path", "offset", "limit",
    )
    private const val ArgumentKeyLineLimit = 16

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

    /** Infer kind from a sparse ACP title when the provider reports [AgentToolKind.Other]. */
    fun inferKindFromTitle(title: String): AgentToolKind? {
        val trimmed = title.trim().lowercase()
        return when (trimmed) {
            "edit", "edit file", "editing files", "write", "create", "update",
            "str_replace", "apply_patch", "apply patch",
            -> AgentToolKind.Edit
            "delete", "delete file" -> AgentToolKind.Delete
            "move", "rename", "rename file" -> AgentToolKind.Move
            "read", "read file" -> AgentToolKind.Read
            "search", "grep" -> AgentToolKind.Search
            "fetch" -> AgentToolKind.Fetch
            "terminal", "shell", "bash", "execute", "run", "command" -> AgentToolKind.Execute
            else -> null
        }
    }

    /**
     * Names the action from the tool's own payload, for providers that report every call as
     * [AgentToolKind.Other] and title a shell call with the command itself (cursor-agent). Accepts
     * every shape a transcript can hold: the JSON arguments as sent, the Markdown the store
     * persisted for them, and a rendered diff sent with no arguments at all.
     */
    fun inferKindFromArguments(text: String): AgentToolKind? {
        if (text.isBlank()) return null
        // Only the argument bullets count. Command output and file bodies follow them in a fenced
        // block, and a file that happens to contain `"command":` must not be read as a command.
        val arguments = text.lineSequence()
            .takeWhile { !fenceMarkerLine.matches(it.trim()) }
            .take(ArgumentKeyLineLimit)
            .joinToString("\n")
        val keys = argumentKey.findAll(arguments)
            .mapNotNull { match ->
                match.groupValues.drop(1).firstOrNull { it.isNotBlank() }
                    ?.trim()?.lowercase()?.replace(' ', '_')
            }
            .toSet()
        return when {
            keys.any { it in commandArgumentKeys } -> AgentToolKind.Execute
            keys.any { it in editArgumentKeys } -> AgentToolKind.Edit
            keys.any { it in searchArgumentKeys } -> AgentToolKind.Search
            keys.any { it in readArgumentKeys } -> AgentToolKind.Read
            isRenderedFileDiff(text) -> AgentToolKind.Edit
            else -> null
        }
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

    fun isGenericOrSparseTitle(title: String): Boolean =
        isGenericTitle(title) || isSparseToolTitle(title)

    fun isMinimalOutput(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || minimalSuccessOutput.matches(trimmed)) return true
        if (trimmed.lowercase() in placeholderOutput) return true
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
        val sections = when {
            content.isNotBlank() -> listOf(null to content)
            rawInput.isNotBlank() && rawOutput.isNotBlank() &&
                !isMinimalOutput(rawInput) && !isMinimalOutput(rawOutput) ->
                listOf("Input" to rawInput, "Output" to rawOutput)
            rawInput.isNotBlank() && !isMinimalOutput(rawInput) -> listOf(null to rawInput)
            rawOutput.isNotBlank() && !isMinimalOutput(rawOutput) -> listOf(null to rawOutput)
            else -> emptyList()
        }
        // The summary is derived from the payload as sent; only the body is reformatted, so
        // rendering choices can never change which text is judged minimal or promoted to a headline.
        val rawDetail = sections.joinToString("\n") { it.second }
        val detail = sections.joinToString("\n\n") { (label, value) ->
            val rendered = displayDetail(value)
            when {
                rendered.isBlank() -> ""
                label == null -> rendered
                else -> "### $label\n$rendered"
            }
        }.trim()
        val firstContentLine = firstMeaningfulLine(content)
        val summary = when {
            inputSummary.isNotBlank() -> inputSummary
            firstContentLine.isNotBlank() && !isMinimalOutput(firstContentLine) ->
                summarizeArguments(firstContentLine).ifBlank {
                    if (looksLikeFilePath(firstContentLine)) shortenPath(firstContentLine) else firstContentLine
                }
            rawOutput.isNotBlank() && !isMinimalOutput(rawOutput) ->
                jsonArgumentSummary(rawOutput).ifBlank { firstMeaningfulLine(rawOutput) }
            else -> firstMeaningfulLine(rawDetail).let { line ->
                if (looksLikeFilePath(line)) shortenPath(line) else line
            }
        }
        return summary to detail
    }

    /**
     * True when [detail] states something [headline] does not. Providers routinely echo their
     * arguments as the body of a row whose headline was derived from those same arguments, which
     * makes the row look expandable while revealing nothing.
     */
    fun detailAddsInformation(headline: String, detail: String): Boolean {
        val detailKey = comparisonKey(detail)
        if (detailKey.isEmpty()) return false
        return !comparisonKey(headline).contains(detailKey)
    }

    private fun comparisonKey(text: String): String = text.filter { it.isLetterOrDigit() }.lowercase()

    /** `key=value` summary for a JSON payload only; other text has no arguments to summarize. */
    private fun jsonArgumentSummary(text: String): String =
        parseJson(text)?.let { summarizeArguments(text) }.orEmpty()

    /**
     * Converts a complete JSON payload to compact Markdown so transcript expansion never exposes
     * transport syntax. Non-JSON content is preserved for Markdown/code/diff rendering in the UI.
     */
    fun displayDetail(text: String): String {
        val trimmed = text.trim()
        if (!looksLikeJson(trimmed)) return trimmed
        val element = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return trimmed
        // A payload with nothing to say renders as nothing, leaving the row unexpandable.
        return renderJsonMarkdown(element)
    }

    fun displayDetailExcludingPayload(text: String, excluded: String): String {
        val trimmed = text.trim()
        if (!looksLikeJson(trimmed)) return trimmed.replace(excluded, "").trim()
        val element = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return trimmed
        return removePayload(element, excluded)?.let(::renderJsonMarkdown).orEmpty()
    }

    private fun removePayload(element: JsonElement, excluded: String): JsonElement? = when (element) {
        is JsonObject -> JsonObject(
            element.mapNotNull { (key, value) -> removePayload(value, excluded)?.let { key to it } }.toMap(),
        ).takeIf { it.isNotEmpty() }
        is JsonArray -> JsonArray(element.mapNotNull { removePayload(it, excluded) }).takeIf { it.isNotEmpty() }
        is JsonPrimitive -> {
            if (!element.isString) {
                element
            } else {
                element.content.replace(excluded, "").trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let(::JsonPrimitive)
            }
        }
    }

    /**
     * The command output, file body, or diff a payload wraps, in the order the keys appear. These
     * are the only parts of a payload worth reading in full, so the transcript renders them as
     * blocks — a diff viewer or a highlighted code fence — instead of a truncated `key=value` line.
     */
    fun payloadTextValues(text: String): List<String> {
        val trimmed = text.trim()
        if (!looksLikeJson(trimmed)) return emptyList()
        val element = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return emptyList()
        return blockTextValues(element)
    }

    private fun blockTextValues(element: JsonElement): List<String> = when (element) {
        is JsonObject -> element.values.flatMap { blockTextValues(it) }
        is JsonArray -> element.flatMap { blockTextValues(it) }
        is JsonPrimitive -> if (element.isBlockText()) listOf(element.content) else emptyList()
    }

    /** Long or multi-line strings are content; short ones belong inline next to their key. */
    private fun JsonPrimitive.isBlockText(): Boolean =
        isString && (content.contains('\n') || content.length > InlineValueLimit)

    private fun renderJsonMarkdown(element: JsonElement): String = when (element) {
        is JsonObject -> {
            // Prefer a sibling path so a Read/ Grep `content` fence can carry `kotlin` / `ts` / …
            val pathHint = pathKeys.firstNotNullOfOrNull { key ->
                (element[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            }
            element.entries.joinToString("\n") { (key, value) ->
                val label = humanize(key)
                when {
                    // Nest the fence under the list item (2-space indent). A column-0 fence ends the
                    // list, so mid-file fragments with uneven absolute indent look like they "escaped"
                    // the content: label — less-indented lines align with the bullet while deeper
                    // lines still sit under it.
                    value is JsonPrimitive && value.isBlockText() ->
                        "- **$label:**\n${fencedBlock(value.content, pathHint).prependIndent("  ")}"
                    value is JsonPrimitive -> "- **$label:** ${renderPrimitive(value)}"
                    else -> {
                        val nested = renderJsonMarkdown(value)
                        "- **$label:**\n${nested.prependIndent("  ")}"
                    }
                }
            }
        }
        is JsonArray -> element.joinToString("\n") { value ->
            when {
                value is JsonPrimitive && value.isBlockText() -> fencedBlock(value.content)
                value is JsonPrimitive -> "- ${renderPrimitive(value)}"
                else -> "-\n${renderJsonMarkdown(value).prependIndent("  ")}"
            }
        }
        is JsonPrimitive -> if (element.isBlockText()) fencedBlock(element.content) else renderPrimitive(element)
    }

    /** Fence long enough to survive backticks in the body, so Markdown cannot break mid-payload. */
    private fun fencedBlock(body: String, pathHint: String? = null): String {
        val dedented = dedentCommonIndent(body.trimEnd())
        val longestRun = Regex("`+").findAll(dedented).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longestRun + 1))
        return "$fence${payloadLanguage(dedented, pathHint)}\n$dedented\n$fence"
    }

    /**
     * Strip the shared leading indent from a file/command fragment. Read tools often return a slice
     * that still carries the surrounding function's indent; without this, "main-level" lines in the
     * fragment sit flush with the tool-row bullet while nested lines look correctly nested.
     */
    fun dedentCommonIndent(text: String): String {
        val lines = text.replace("\r\n", "\n").split('\n')
        val indents = lines.mapNotNull { line ->
            if (line.isBlank()) null
            else line.indexOfFirst { it != ' ' && it != '\t' }.takeIf { it >= 0 }
        }
        val indent = indents.minOrNull() ?: return text
        if (indent == 0) return text
        return lines.joinToString("\n") { line ->
            if (line.isBlank()) ""
            else line.drop(minOf(indent, line.length))
        }
    }

    private val pathKeys = listOf(
        "path", "file_path", "filePath", "file", "target_file", "uri", "notebook_path",
    )

    private fun payloadLanguage(body: String, pathHint: String? = null): String {
        languageForPath(pathHint)?.let { return it }
        if (body.startsWith("diff --git") || diffHunkHeader.containsMatchIn(body)) return "diff"
        return languageFromContent(body).orEmpty()
    }

    private fun languageForPath(path: String?): String? {
        val ext = path?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext.isEmpty() || ext == path?.lowercase()) return null
        return when (ext) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "js", "mjs", "cjs" -> "javascript"
            "ts", "tsx" -> "typescript"
            "py" -> "python"
            "rs" -> "rust"
            "go" -> "go"
            "rb" -> "ruby"
            "sh", "bash", "zsh" -> "shell"
            "json" -> "json"
            "yaml", "yml" -> "yaml"
            "md", "markdown" -> "markdown"
            "html", "htm" -> "html"
            "css" -> "css"
            "sql" -> "sql"
            "xml" -> "xml"
            "c", "h" -> "c"
            "cpp", "cc", "cxx", "hpp" -> "cpp"
            "swift" -> "swift"
            else -> null
        }
    }

    /** Best-effort language when the payload has no path (Cursor Read often sends only `content`). */
    private fun languageFromContent(body: String): String? {
        val sample = body.lineSequence().take(40).joinToString("\n")
        return when {
            Regex("""\b(fun |val |var |suspend fun |data class |@Composable)\b""").containsMatchIn(sample) -> "kotlin"
            Regex("""\b(def |async def |from \w+ import |elif )\b""").containsMatchIn(sample) -> "python"
            Regex("""\b(fn |let mut |impl |pub struct )\b""").containsMatchIn(sample) -> "rust"
            Regex("""\b(func |package main|:=)\b""").containsMatchIn(sample) -> "go"
            Regex("""\b(const |let |function |=>|export )\b""").containsMatchIn(sample) &&
                (sample.contains("interface ") || sample.contains(": string") || sample.contains(": number")) ->
                "typescript"
            Regex("""\b(function |const |let |=>|export )\b""").containsMatchIn(sample) -> "javascript"
            else -> null
        }
    }
    private fun renderPrimitive(value: JsonPrimitive): String {
        val content = value.contentOrNull ?: value.toString()
        return when {
            content.contains('\n') -> "\n${content.prependIndent("  ")}"
            content.isBlank() -> "—"
            else -> content
        }
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
        // An empty-arguments placeholder states nothing, and prepending it to real content would
        // hide the shape of that content — a leading "{}" stops a diff from being recognized.
        val a = first.trim().takeUnless { isMinimalOutput(it) }.orEmpty()
        val b = second.trim().takeUnless { isMinimalOutput(it) }.orEmpty()
        return when {
            a.isBlank() -> b
            b.isBlank() -> a
            a == b -> a
            b.contains(a) -> b
            a.contains(b) -> a
            isRenderedFileDiff(b) -> {
                val extra = detailExtra(a) ?: a.takeUnless { isJsonObject(it) }
                extra?.let { "$b$DetailSeparator$it" } ?: b
            }
            isRenderedFileDiff(a) -> appendExtraDetail(a, b)
            isJsonObject(a) -> "$a$DetailSeparator$b"
            else -> "$a\n$b"
        }
    }

    private fun appendExtraDetail(detail: String, output: String): String {
        val separatorIndex = detail.indexOf(DetailSeparator)
        if (separatorIndex < 0) return "$detail$DetailSeparator$output"
        val primary = detail.substring(0, separatorIndex)
        val extra = detail.substring(separatorIndex + DetailSeparator.length)
        return "$primary$DetailSeparator$extra\n$output"
    }

    private fun detailExtra(text: String): String? {
        val separatorIndex = text.indexOf(DetailSeparator)
        if (separatorIndex < 0) return null
        return text.substring(separatorIndex + DetailSeparator.length).takeIf { it.isNotBlank() }
    }

    private fun isJsonObject(text: String): Boolean =
        text.startsWith("{") && runCatching { json.parseToJsonElement(text) is JsonObject }.getOrDefault(false)

    /** ToolCallContent.Diff's stable text shape; it must remain at byte zero for domain parsing. */
    private fun isRenderedFileDiff(text: String): Boolean =
        text.contains("\n--- old\n") && text.contains("\n+++ new\n")

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
