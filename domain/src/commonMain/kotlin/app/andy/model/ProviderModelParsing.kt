package app.andy.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses provider CLI `models` output into Andy's base-model + effort catalog shape.
 * Cursor prints `id - Label`; Antigravity prints one slug per line.
 */

private val EffortTokenOrder = listOf(
    "none" to AgentReasoningEffort.None,
    "minimal" to AgentReasoningEffort.Minimal,
    "low" to AgentReasoningEffort.Low,
    "medium" to AgentReasoningEffort.Medium,
    "high" to AgentReasoningEffort.High,
    "xhigh" to AgentReasoningEffort.ExtraHigh,
    "extra-high" to AgentReasoningEffort.ExtraHigh,
    "max" to AgentReasoningEffort.Max,
    "ultracode" to AgentReasoningEffort.Ultracode,
)

private val EffortTokenByLength = EffortTokenOrder.map { it.first }.sortedByDescending { it.length }

internal fun parseProviderJsonModels(output: String): List<Pair<String, String>> = runCatching {
    val root = Json.parseToJsonElement(output.trim())
    val values = when (root) {
        is JsonArray -> root
        is JsonObject -> (root["models"] ?: root["data"] ?: root["items"])?.jsonArray
        else -> null
    } ?: return emptyList()
    values.mapNotNull { element ->
        when (element) {
            is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }?.let { it to humanizeProviderModel(it) }
            is JsonObject -> {
                val id = element["id"]?.jsonPrimitive?.content ?: element["model"]?.jsonPrimitive?.content ?: element["name"]?.jsonPrimitive?.content
                id?.takeIf { it.isNotBlank() }?.let { it to (element["label"]?.jsonPrimitive?.content ?: humanizeProviderModel(it)) }
            }
            else -> null
        }
    }
}.getOrDefault(emptyList())

fun parseHermesModels(output: String): List<AgentModelOption> = groupProviderModelVariants(parseProviderJsonModels(output))
fun parseOpenClawModels(output: String): List<AgentModelOption> = groupProviderModelVariants(parseProviderJsonModels(output))

/**
 * Goose has no `models list` command. Parse `~/.config/goose/config.yaml` or
 * `goose info -v` for configured provider/model pairs.
 */
fun parseGooseModels(output: String): List<AgentModelOption> {
    val text = output.replace("\r\n", "\n")
    val slugs = linkedSetOf<String>()
    val providerModels = Regex(
        """(?m)^[ \t]{2}([A-Za-z0-9._-]+):(?:\n[ \t]{4}.+)*?\n[ \t]{4}model:[ \t]*["']?([A-Za-z0-9._/: +-]+)""",
    )
    providerModels.findAll(text).forEach { match ->
        val provider = match.groupValues[1].trim()
        val model = match.groupValues[2].trim().trim('"', '\'')
        if (provider.isNotBlank() &&
            model.isNotBlank() &&
            provider !in setOf("mcp_servers", "extensions", "headers", "envs")
        ) {
            slugs += "$provider/$model"
        }
    }
    val gooseProvider = yamlScalar(text, "GOOSE_PROVIDER") ?: yamlScalar(text, "active_provider")
    val gooseModel = yamlScalar(text, "GOOSE_MODEL")
    if (!gooseModel.isNullOrBlank()) {
        slugs += if (!gooseProvider.isNullOrBlank() && '/' !in gooseModel) {
            "$gooseProvider/$gooseModel"
        } else {
            gooseModel
        }
    }
    return groupProviderModelVariants(slugs.map { it to humanizeProviderModel(it) })
}

/** True when Goose config names a provider Andy can launch with. */
fun gooseLooksConfigured(configText: String): Boolean =
    Regex("""(?m)^[ \t]*(GOOSE_PROVIDER|active_provider):[ \t]*["']?[A-Za-z0-9._-]+""")
        .containsMatchIn(configText.replace("\r\n", "\n"))

private fun yamlScalar(text: String, key: String): String? {
    val match = Regex("""(?m)^[ \t]*${Regex.escape(key)}:[ \t]*["']?([A-Za-z0-9._/: +-]+)""").find(text)
        ?: return null
    return match.groupValues[1].trim().trim('"', '\'').takeIf { it.isNotBlank() }
}

internal data class ProviderModelVariant(
    val baseId: String,
    val effort: AgentReasoningEffort?,
    val effortToken: String?,
    val fast: Boolean,
)

/** Strip a trailing effort / fast suffix from a provider model slug. */
internal fun stripProviderModelVariant(modelId: String): ProviderModelVariant {
    var remaining = modelId.trim()
    if (remaining.isEmpty()) return ProviderModelVariant(modelId, null, null, false)
    var fast = false
    if (remaining.endsWith("-fast")) {
        fast = true
        remaining = remaining.removeSuffix("-fast")
    }
    if (':' in remaining) {
        val token = remaining.substringAfterLast(':').lowercase()
        val base = remaining.substringBeforeLast(':')
        val matchingEffort = EffortTokenOrder.firstOrNull { it.first == token }
        if (matchingEffort != null && base.isNotEmpty()) {
            return ProviderModelVariant(base, matchingEffort.second, token, fast)
        }
    }
    for (token in EffortTokenByLength) {
        val suffix = "-$token"
        if (remaining.endsWith(suffix) && remaining.length > suffix.length) {
            val effort = EffortTokenOrder.first { it.first == token }.second
            return ProviderModelVariant(remaining.removeSuffix(suffix), effort, token, fast)
        }
    }
    return ProviderModelVariant(remaining, null, null, fast)
}

fun parseAntigravityModels(output: String): List<AgentModelOption> {
    val rows = output.lineSequence().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() ||
            trimmed.startsWith("Available", ignoreCase = true) ||
            trimmed.startsWith("Tip:", ignoreCase = true) ||
            trimmed.startsWith("Fetching", ignoreCase = true) ||
            trimmed.startsWith("Loading", ignoreCase = true)
        ) {
            return@mapNotNull null
        }
        val tabParts = trimmed.split('\t', limit = 2).map { it.trim() }
        if (tabParts.size == 2 && tabParts[0].isNotBlank() && tabParts[1].isNotBlank()) {
            val rawSlug = tabParts[0]
            val rawLabel = tabParts[1]
            rawSlug to antigravityBaseLabel(rawSlug, rawLabel)
        } else {
            val match = Regex("""^(.+?)\s*\(([^)]+)\)$""").find(trimmed)
            if (match != null) {
                val rawLabel = match.groupValues[1].trim()
                val rawSlug = match.groupValues[2].trim()
                rawSlug to antigravityBaseLabel(rawSlug, rawLabel)
            } else if (trimmed.contains(" - ")) {
                val rawSlug = trimmed.substringBefore(" - ").trim()
                val rawLabel = trimmed.substringAfter(" - ").trim()
                rawSlug to antigravityBaseLabel(rawSlug, rawLabel)
            } else {
                val rawSlug = trimmed.takeWhile { !it.isWhitespace() }
                if (rawSlug.isEmpty()) null else rawSlug to humanizeModelSlug(stripProviderModelVariant(rawSlug).baseId)
            }
        }
    }.toList()
    return groupProviderModelVariants(rows)
}

fun parseCursorModels(output: String): List<AgentModelOption> {
    val rows = output.lineSequence().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() ||
            trimmed.startsWith("Available", ignoreCase = true) ||
            trimmed.startsWith("Tip:", ignoreCase = true) ||
            trimmed.startsWith("Fetching", ignoreCase = true) ||
            trimmed.startsWith("Loading", ignoreCase = true)
        ) return@mapNotNull null
        val separator = trimmed.indexOf(" - ")
        if (separator <= 0) {
            val slug = trimmed.takeWhile { !it.isWhitespace() }
            if (slug.isEmpty()) null else slug to humanizeModelSlug(stripProviderModelVariant(slug).baseId)
        } else {
            val slug = trimmed.take(separator).trim()
            val label = trimmed.substring(separator + 3).trim()
            if (slug.isEmpty()) null else slug to cursorBaseLabel(slug, label)
        }
    }.toList()
    return groupProviderModelVariants(rows)
}

/**
 * OpenCode prints `provider/model` slugs (optionally with labels). Keep the full
 * `provider/model` id so `--model` receives a valid OpenCode selector.
 */
fun parseOpenCodeModels(output: String): List<AgentModelOption> {
    val rows = output.lineSequence().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() ||
            trimmed.startsWith("Available") ||
            trimmed.startsWith("Tip:") ||
            trimmed.startsWith("{") ||
            trimmed.startsWith("[")
        ) {
            return@mapNotNull null
        }
        val slug = when {
            trimmed.contains(" - ") -> trimmed.substringBefore(" - ").trim()
            trimmed.contains('\t') -> trimmed.substringBefore('\t').trim()
            else -> trimmed.takeWhile { !it.isWhitespace() }
        }
        if (slug.isEmpty() || !slug.contains('/')) return@mapNotNull null
        val label = if (trimmed.contains(" - ")) {
            trimmed.substringAfter(" - ").trim().ifBlank { humanizeProviderModel(slug) }
        } else {
            humanizeProviderModel(slug)
        }
        slug to label
    }.toList()
    return groupProviderModelVariants(rows)
}

/**
 * Pi `--list-models` prints a whitespace table:
 * ```
 * provider      model                context  max-out  thinking  images
 * openai-codex  gpt-5.4              272K     128K     yes       yes
 * ```
 * Andy stores/passes `--model provider/model` (e.g. `openai-codex/gpt-5.4`).
 * Also accepts legacy `provider/id` one-slug-per-line output.
 */
fun parsePiModels(output: String): List<AgentModelOption> {
    val PiThinkingEfforts = listOf(
        AgentReasoningEffort.None,
        AgentReasoningEffort.Minimal,
        AgentReasoningEffort.Low,
        AgentReasoningEffort.Medium,
        AgentReasoningEffort.High,
        AgentReasoningEffort.ExtraHigh,
        AgentReasoningEffort.Max,
    )
    data class Row(val slug: String, val label: String, val thinking: Boolean)
    val rows = output.lineSequence().mapNotNull { line ->
        val trimmed = line.trim().trimStart('-', '*', '•', ' ')
        if (trimmed.isEmpty() ||
            trimmed.startsWith("Available") ||
            trimmed.startsWith("Tip:") ||
            trimmed.startsWith("Provider:") ||
            trimmed.equals("provider", ignoreCase = true) ||
            trimmed.lowercase().startsWith("provider ") && trimmed.lowercase().contains("model")
        ) {
            return@mapNotNull null
        }
        // Table row: provider  model  context  max-out  thinking  images
        val cols = trimmed.split(Regex("""\s{2,}|\t+""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (cols.size >= 2 && !cols[0].contains('/') && cols[1].isNotBlank() &&
            !cols[1].equals("model", ignoreCase = true)
        ) {
            val provider = cols[0]
            val model = cols[1].takeWhile { !it.isWhitespace() }
            if (provider.isBlank() || model.isBlank()) return@mapNotNull null
            // Skip header leftovers like "context"
            if (model.equals("context", ignoreCase = true) || model.equals("max-out", ignoreCase = true)) {
                return@mapNotNull null
            }
            val slug = "$provider/$model"
            val thinking = cols.getOrNull(4)?.equals("yes", ignoreCase = true) == true
            return@mapNotNull Row(slug, humanizeProviderModel(slug), thinking = thinking)
        }
        // Legacy / alternate: provider/id on one line
        val slug = trimmed.takeWhile { !it.isWhitespace() && it != ',' }
        if (slug.isEmpty() || slug.length < 2 || !slug.contains('/')) return@mapNotNull null
        Row(slug, humanizeProviderModel(slug), thinking = true)
    }.toList()

    return rows.map { row ->
        AgentModelOption(
            id = row.slug,
            label = row.label,
            efforts = if (row.thinking) PiThinkingEfforts else emptyList(),
        )
    }.distinctBy { it.id }
}

private fun humanizeProviderModel(slug: String): String {
    val model = slug.substringAfterLast('/')
    val provider = slug.substringBefore('/', missingDelimiterValue = "").takeIf { it.isNotBlank() && it != model }
    val modelLabel = humanizeModelSlug(model.replace('_', '-'))
    return if (provider != null) {
        "$modelLabel (${provider.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }})"
    } else {
        modelLabel
    }
}

private fun antigravityBaseLabel(slug: String, variantLabel: String): String {
    val base = stripProviderModelVariant(slug).baseId
    val cleaned = variantLabel
        .removeSuffix(" Fast")
        .replace(Regex("""\s*\((None|Minimal|Low|Medium|High|Extra High|Max|Ultracode|Thinking)\)""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\b(None|Minimal|Low|Medium|High|Extra High|Max|Ultracode)\b""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trimEnd('-', ' ')
    return cleaned.ifBlank { humanizeModelSlug(base) }
}

private fun cursorBaseLabel(slug: String, variantLabel: String): String {
    val base = stripProviderModelVariant(slug).baseId
    val cleaned = variantLabel
        .removeSuffix(" Fast")
        .replace(Regex("""\b(None|Minimal|Low|Medium|High|Extra High|Max|Ultracode)\b"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trimEnd('-', ' ')
    return cleaned.ifBlank { humanizeModelSlug(base) }
}

private fun groupProviderModelVariants(rows: List<Pair<String, String>>): List<AgentModelOption> {
    data class Acc(
        var label: String,
        val efforts: LinkedHashMap<AgentReasoningEffort, String> = linkedMapOf(),
        var supportsFastMode: Boolean = false,
        var sawBareVariant: Boolean = false,
    )
    val grouped = linkedMapOf<String, Acc>()
    for ((slug, label) in rows) {
        val variant = stripProviderModelVariant(slug)
        val acc = grouped.getOrPut(variant.baseId) { Acc(label = label) }
        if (label.isNotBlank()) {
            // Prefer a label from a bare / high variant when available.
            if (variant.effort == null || variant.effort == AgentReasoningEffort.High || acc.label.isBlank()) {
                acc.label = label
            }
        }
        if (variant.fast) acc.supportsFastMode = true
        if (variant.effort == null && !variant.fast) acc.sawBareVariant = true
        val effort = variant.effort
        val token = variant.effortToken
        if (effort != null && token != null && effort !in acc.efforts) {
            acc.efforts[effort] = token
        }
    }
    return grouped.map { (baseId, acc) ->
        val efforts = if (acc.efforts.isEmpty() && acc.sawBareVariant) {
            emptyList()
        } else {
            AgentReasoningEffort.entries.filter { it in acc.efforts }
        }
        AgentModelOption(
            id = baseId,
            label = acc.label.ifBlank { humanizeModelSlug(baseId) },
            efforts = efforts,
            supportsFastMode = acc.supportsFastMode,
            fastRequired = acc.supportsFastMode && !acc.sawBareVariant,
            effortTokens = acc.efforts.toMap(),
        )
    }
}

internal fun humanizeModelSlug(slug: String): String {
    if (slug.isBlank()) return slug
    return slug.split('-').joinToString(" ") { part ->
        when {
            part.equals("gpt", ignoreCase = true) -> "GPT"
            part.equals("oss", ignoreCase = true) -> "OSS"
            part.equals("glm", ignoreCase = true) -> "GLM"
            part.matches(Regex("""\d+(?:\.\d+)*""")) -> part
            else -> part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}

/**
 * Vendor family for Cursor's mixed model marketplace (and similar multi-vendor lists).
 * Order is intentional: Cursor-native first, then the major labs.
 */
enum class AgentModelFamily(val label: String) {
    Cursor("Cursor"),
    OpenAI("OpenAI"),
    Anthropic("Anthropic"),
    Google("Google"),
    XAI("xAI"),
    Moonshot("Moonshot"),
    Zhipu("Zhipu"),
    Other("Other"),
}

fun AgentModelOption.modelFamily(): AgentModelFamily = modelFamilyForId(id)

fun modelFamilyForId(modelId: String): AgentModelFamily {
    val id = modelId.trim().lowercase()
    val provider = id.substringBefore('/', missingDelimiterValue = "")
    val model = id.substringAfter('/', missingDelimiterValue = id)
    return when {
        provider == "openai" || provider == "openai-codex" ||
            (provider == "opencode" && model.startsWith("gpt")) -> AgentModelFamily.OpenAI
        provider == "anthropic" -> AgentModelFamily.Anthropic
        provider == "google" -> AgentModelFamily.Google
        provider == "xai" -> AgentModelFamily.XAI
        provider == "moonshot" -> AgentModelFamily.Moonshot
        provider == "zhipu" -> AgentModelFamily.Zhipu
        id == "auto" || id.startsWith("composer-") || id.startsWith("cursor-") -> AgentModelFamily.Cursor
        id.startsWith("gpt-") || id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") -> AgentModelFamily.OpenAI
        id.startsWith("claude-") || id.startsWith("anthropic-") -> AgentModelFamily.Anthropic
        id.startsWith("gemini-") || id.startsWith("google-") -> AgentModelFamily.Google
        id.startsWith("grok-") || id.startsWith("xai-") -> AgentModelFamily.XAI
        id.startsWith("kimi-") || id.startsWith("moonshot-") -> AgentModelFamily.Moonshot
        id.startsWith("glm-") || id.startsWith("zhipu-") -> AgentModelFamily.Zhipu
        else -> AgentModelFamily.Other
    }
}

/** Groups models by [AgentModelFamily], omitting empty families and preserving catalog order within each. */
fun List<AgentModelOption>.groupedByModelFamily(): List<Pair<AgentModelFamily, List<AgentModelOption>>> {
    if (isEmpty()) return emptyList()
    val buckets = AgentModelFamily.entries.associateWith { mutableListOf<AgentModelOption>() }
    forEach { option -> buckets.getValue(option.modelFamily()).add(option) }
    return AgentModelFamily.entries.mapNotNull { family ->
        buckets.getValue(family).takeIf { it.isNotEmpty() }?.let { family to it }
    }
}
