package app.andy.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Parses Task / Subagent / chat.start tool calls into Cursor-style spawn rows. */
object AgentSpawnPresentation {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** How much of a tool call's summary/detail [isAgentSpawn] scans for spawn metadata. */
    private const val ClassificationScanLimit = 2000

    private val spawnToolNames = setOf(
        "task",
        "agent",
        "mcp_task",
        "subagent",
        "chat.start",
        "mcp_andy_chat_start",
        "mcp_emu_chat_start",
    )

    data class Spawn(
        val name: String,
        val type: String?,
        val instructions: String,
        /** Andy chat id from chat.start (or equivalent) when the tool result is known. */
        val taskId: String? = null,
    )

    data class SpawnSource(
        val toolName: String?,
        val summary: String,
        val detail: String,
    )

    fun isAgentSpawnTool(toolName: String?): Boolean {
        val lower = toolName?.trim()?.lowercase().orEmpty()
        if (lower.isEmpty()) return false
        if (lower in spawnToolNames) return true
        // After AcpToolCallPresentation.formatMcpToolName("mcp_andy_chat_start")
        if (lower == "andy mcp · chat start" || lower == "emu mcp · chat start") return true
        return lower.endsWith("· chat start")
    }

    /**
     * True for Task/chat.start rows even after the provider rewrites the tool title to a
     * persona name (e.g. Cursor's "Archimedes").
     */
    fun isAgentSpawn(toolName: String?, summary: String = "", detail: String = ""): Boolean {
        if (isAgentSpawnTool(toolName)) return true
        // Every match below needs the literal substring "agent" somewhere in the payload
        // (subagent_type, subagentType, or a bare "agent" key next to "prompt"). This runs on
        // every tool call in a transcript — reads, edits, shell commands — and the overwhelming
        // majority never mention "agent" at all, so a plain substring scan rejects them before
        // paying for JSON parsing or the key=value regex scan. That JSON/regex path was the
        // dominant per-row CPU cost while scrolling a transcript with many ordinary tool calls.
        if (!summary.contains("agent", ignoreCase = true) && !detail.contains("agent", ignoreCase = true)) {
            return false
        }
        // A real spawn call's metadata is compact and up front (title/prompt/subagent_type);
        // command output or a read file body that follows can be many KB (e.g. this very
        // transcript, which is about Agent*.kt files, so "agent" appears in nearly every grep
        // dump). Capping what the JSON/regex scan below sees keeps a huge tool result from
        // costing the same as a short one just because the word "agent" turns up somewhere in it.
        val detailHead = detail.take(ClassificationScanLimit)
        val summaryHead = summary.take(ClassificationScanLimit)
        val fields = linkedMapOf<String, String>()
        extractJsonFields(detailHead, fields)
        extractJsonFields(summaryHead, fields)
        extractKeyValueFields(detailHead, fields)
        extractKeyValueFields(summaryHead, fields)
        if (fields.containsKey("subagent_type") || fields.containsKey("subagentType")) return true
        // Andy chat.start: prompt + agent, optionally title.
        return fields.containsKey("prompt") && fields.containsKey("agent")
    }

    fun spawningHeadline(count: Int): String = when {
        count <= 0 -> "Spawning agents"
        count == 1 -> "Spawning agent"
        else -> "Spawning $count agents"
    }

    fun parse(toolName: String?, summary: String, detail: String): Spawn {
        val fields = linkedMapOf<String, String>()
        extractJsonFields(detail, fields)
        extractJsonFields(summary, fields)
        extractKeyValueFields(detail, fields)
        extractKeyValueFields(summary, fields)

        val type = normalizeType(
            fields.firstOf("subagent_type", "subagentType", "agent_type", "type", "agent"),
        )
        val name = resolveName(toolName, fields, type)
        val instructions = resolveInstructions(fields, name)
        val taskId = fields.firstOf("taskId", "task_id", "id")
            ?.takeIf { looksLikeTaskId(it) }
            ?: extractQuotedField(detail, "id", "taskId", "task_id")
            ?: extractQuotedField(summary, "id", "taskId", "task_id")
        return Spawn(name = name, type = type, instructions = instructions, taskId = taskId)
    }

    /**
     * Pairs spawn tool calls with a later result payload so chat.start's `{"id":...}` is available
     * even when ACP/MCP surfaces input and output as separate transcript events.
     */
    fun spawnSources(events: List<AgentEvent>): List<SpawnSource> {
        // Failed spawn results stay visible as transcript error rows; they must not be absorbed
        // into an optimistic spawn row as if they had succeeded.
        val results = events.filterIsInstance<AgentEvent.ToolResult>()
            .filter { !it.isError && isAgentSpawn(it.toolName, it.summary, it.detail) }
            .toMutableList()
        return events.filterIsInstance<AgentEvent.ToolCall>()
            .filter { isAgentSpawn(it.toolName, it.summary, it.detail) }
            .map { call ->
                val resultIndex = pairResultIndex(call, results)
                val result = if (resultIndex != null) results.removeAt(resultIndex) else null
                SpawnSource(
                    toolName = call.toolName,
                    summary = listOf(call.summary, result?.summary.orEmpty())
                        .filter { it.isNotBlank() }
                        .joinToString("\n"),
                    detail = listOf(call.detail, result?.detail.orEmpty())
                        .filter { it.isNotBlank() }
                        .joinToString("\n"),
                )
            }
    }

    /**
     * Chooses which unmatched result belongs to [call], or null when the pairing is ambiguous.
     *
     * A tool-name-only match is only trusted when it is the sole result for that name. When
     * several same-named spawn calls are outstanding, results can finish out of order, so picking
     * by arrival order risks cross-linking each spawn row to the wrong child chat. In that case
     * the row is left unpaired and [resolveTaskId] falls back to title/instructions matching,
     * which is correct rather than crossed.
     */
    private fun pairResultIndex(call: AgentEvent.ToolCall, results: List<AgentEvent.ToolResult>): Int? {
        val nameMatches = results.indices.filter { index ->
            results[index].toolName.equals(call.toolName, ignoreCase = true)
        }
        if (nameMatches.size == 1) return nameMatches.single()
        // Without a name match, an id-bearing result is still a strong signal when it is the only
        // one outstanding (e.g. the provider omitted the result's tool name).
        if (nameMatches.isEmpty()) {
            val idMatches = results.indices.filter { index ->
                looksLikeTaskId(extractQuotedField(results[index].detail, "id", "taskId") ?: "")
            }
            if (idMatches.size == 1) return idMatches.single()
        }
        return null
    }

    /** Match a spawned row to an Andy chat when the tool result omitted / lost the id. */
    fun resolveTaskId(spawn: Spawn, candidates: List<AgentTask>, excludeTaskId: String? = null): String? {
        spawn.taskId?.takeIf { id -> candidates.any { it.id == id } }?.let { return it }
        val usable = candidates.filter { it.id != excludeTaskId }
        usable.firstOrNull { it.title.equals(spawn.name, ignoreCase = true) }?.id?.let { return it }
        val needle = spawn.instructions.trim()
        if (needle.isNotBlank()) {
            usable.firstOrNull { candidate ->
                candidate.prompt.startsWith(needle) ||
                    candidate.prompt.lines().firstOrNull().orEmpty().startsWith(needle.take(96))
            }?.id?.let { return it }
        }
        return null
    }

    private fun resolveName(toolName: String?, fields: Map<String, String>, type: String?): String {
        fields.firstOf("name", "agentName", "agent_name")
            ?.takeIf { it.isNotBlank() && !isAgentSpawnTool(it) }
            ?.let { return it }
        val title = fields["title"]?.trim().orEmpty()
        if (title.isNotBlank() && !isAgentSpawnTool(title)) return title
        val tool = toolName?.trim().orEmpty()
        if (tool.isNotBlank() && !isAgentSpawnTool(tool) && !tool.equals("tool", ignoreCase = true)) {
            return tool
        }
        // Cursor Task: short description is the best stable label when no persona name is present.
        fields["description"]?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        fields["agent"]?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        return type?.replaceFirstChar { if (it.isLowerCase()) it.uppercaseChar() else it } ?: "agent"
    }

    private fun resolveInstructions(fields: Map<String, String>, name: String): String {
        val prompt = fields.firstOf("prompt", "instructions", "message")?.trim().orEmpty()
        if (prompt.isNotBlank()) return firstLine(prompt)
        val description = fields["description"]?.trim().orEmpty()
        if (description.isNotBlank() && !description.equals(name, ignoreCase = true)) {
            return firstLine(description)
        }
        return ""
    }

    private fun normalizeType(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when (value.lowercase()) {
            "explore", "explorer" -> "explorer"
            "generalpurpose", "general_purpose", "general-purpose" -> "generalPurpose"
            "bestofn", "best_of_n", "best-of-n", "best-of-n-runner" -> "best-of-n"
            else -> value
        }
    }

    private fun extractJsonFields(text: String, into: MutableMap<String, String>) {
        text.lineSequence().forEach { line ->
            val candidate = line.trim()
            if (!candidate.startsWith("{")) return@forEach
            val obj = parseJson(candidate) ?: return@forEach
            obj.forEach { (key, element) ->
                val value = (element as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (value.isNotBlank() && !into.containsKey(key)) into[key] = value
            }
        }
        // Whole blob may be one JSON object (common for rawInput stored as detail).
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            parseJson(trimmed)?.forEach { (key, element) ->
                val value = (element as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (value.isNotBlank() && !into.containsKey(key)) into[key] = value
            }
        }
    }

    // AcpToolCallPresentation summarizes as "description=..., prompt=..., subagent_type=explore".
    // Prompt values often contain commas, so only split on ", <knownKey>=". isAgentSpawn (which
    // runs this against every tool call's summary and detail) is called several times per event
    // per transcript-row composition, so this must be a compiled-once val: rebuilding a 16-key
    // case-insensitive alternation via Regex(...) on every call showed up as sustained
    // Pattern.match / Character.toUpperCase CPU while scrolling an ACP chat.
    private val keyValueFieldKeys = listOf(
        "subagent_type", "subagentType", "agent_type", "description", "prompt",
        "instructions", "message", "title", "agent", "name", "agentName", "agent_name", "type",
        "taskId", "task_id", "id",
    )
    private val keyValueFieldPattern = run {
        val alternation = keyValueFieldKeys.joinToString("|") { Regex.escape(it) }
        Regex(
            """\b($alternation)\s*=\s*([\s\S]*?)(?=,\s*(?:$alternation)\s*=|$)""",
            setOf(RegexOption.IGNORE_CASE),
        )
    }

    private fun extractKeyValueFields(text: String, into: MutableMap<String, String>) {
        keyValueFieldPattern.findAll(text).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].trim().trim('"')
            if (value.isNotBlank() && !into.containsKey(key)) into[key] = value
        }
    }

    private fun Map<String, String>.firstOf(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> this[key]?.trim()?.takeIf { it.isNotBlank() } }

    private fun firstLine(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    private fun extractQuotedField(text: String, vararg keys: String): String? {
        keys.forEach { key ->
            val match = Regex(""""$key"\s*:\s*"([^"]+)"""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            if (match != null && looksLikeTaskId(match)) return match
        }
        return null
    }

    /** Andy task ids are slug-like (`task-…`); reject status enums and other short result tokens. */
    private fun looksLikeTaskId(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.startsWith("task-")) return true
        if (trimmed.length < 6) return false
        if (trimmed.equals("Working", true) || trimmed.equals("Done", true) ||
            trimmed.equals("Error", true) || trimmed.equals("Blocked", true)
        ) {
            return false
        }
        return trimmed.any { it == '-' || it.isDigit() } || trimmed.length >= 8
    }

    private fun parseJson(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
}
