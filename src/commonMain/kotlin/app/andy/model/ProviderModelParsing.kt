package app.andy.model

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
    for (token in EffortTokenByLength) {
        val suffix = "-$token"
        if (remaining.endsWith(suffix) && remaining.length > suffix.length) {
            val effort = EffortTokenOrder.first { it.first == token }.second
            return ProviderModelVariant(remaining.removeSuffix(suffix), effort, token, fast)
        }
    }
    return ProviderModelVariant(remaining, null, null, fast)
}

fun parseAntigravityModels(output: String): List<AgentModelOption> =
    groupProviderModelVariants(
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("Available") && !it.startsWith("Tip:") }
            .map { slug -> slug to humanizeModelSlug(stripProviderModelVariant(slug).baseId) }
            .toList(),
    )

fun parseCursorModels(output: String): List<AgentModelOption> {
    val rows = output.lineSequence().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("Available") || trimmed.startsWith("Tip:")) return@mapNotNull null
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

