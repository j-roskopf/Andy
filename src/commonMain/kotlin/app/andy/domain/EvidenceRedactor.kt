package app.andy.domain

import app.andy.model.EvidenceArtifactManifestEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject

/** Size/count limits applied while building an evidence bundle (§4). */
object EvidenceBudgets {
    const val MaxCrashChars = 8_000
    const val MaxLogChars = 8_000
    const val MaxHierarchyNodes = 200
    const val MaxHierarchyDepth = 10
    const val MaxNetworkPreviewChars = 4_000
    const val MaxImages = 4
    const val MaxTotalBundleBytes = 8L * 1024L * 1024L
}

/** Header names that are always stripped, regardless of casing. */
private val SensitiveHeaderNames = setOf(
    "authorization",
    "cookie",
    "set-cookie",
    "api-key",
    "x-api-key",
    "proxy-authorization",
    "x-csrf-token",
    "x-session-token",
    "x-auth-token",
)

/** Header names containing these substrings are treated as sensitive even if not an exact match. */
private val SensitiveHeaderNameFragments = listOf("token", "secret", "apikey", "api-key", "session")

/** Bearer-style credentials embedded in an otherwise unremarkable header value. */
private val BearerLikeValue = Regex("""(?i)\bbearer\s+[a-z0-9._-]+""")

data class HeaderRedactionResult(
    val headers: Map<String, String>,
    val redactedNames: List<String>,
)

/**
 * Strips authorization/cookie/token-like headers (by name or bearer-shaped value) before a
 * network sidecar is copied into an evidence bundle. Redacted values are replaced, not removed,
 * so the caller can still see which headers were present.
 */
fun redactHeaders(headers: Map<String, String>): HeaderRedactionResult {
    val redactedNames = mutableListOf<String>()
    val result = headers.mapValues { (name, value) ->
        val lower = name.lowercase()
        val isSensitiveName = lower in SensitiveHeaderNames || SensitiveHeaderNameFragments.any { lower.contains(it) }
        val hasBearerValue = BearerLikeValue.containsMatchIn(value)
        if (isSensitiveName || hasBearerValue) {
            redactedNames += name
            "[redacted]"
        } else {
            value
        }
    }
    return HeaderRedactionResult(result, redactedNames)
}

/** Truncates [text] to [maxChars], appending a note about how much was cut. */
fun capText(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val cut = text.length - maxChars
    return text.take(maxChars) + "…[truncated $cut chars]"
}

data class HierarchyRedactionResult(
    val json: String,
    val redactedNodeCount: Int,
)

private val HierarchyRedactionJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

/**
 * Strips `text`/`contentDescription` from any node whose serialized hierarchy tree marks
 * `password: true`. Operates on the raw sidecar JSON so it stays independent of the specific
 * DTO shape used to write it.
 */
fun redactHierarchyJson(treeJson: String): HierarchyRedactionResult {
    if (treeJson.isBlank()) return HierarchyRedactionResult(treeJson, 0)
    val element = runCatching { HierarchyRedactionJson.parseToJsonElement(treeJson) }.getOrNull()
        ?: return HierarchyRedactionResult(treeJson, 0)
    val (redacted, count) = redactHierarchyElement(element)
    val json = runCatching {
        HierarchyRedactionJson.encodeToString(JsonElement.serializer(), redacted)
    }.getOrNull() ?: treeJson
    return HierarchyRedactionResult(json, count)
}

private fun redactHierarchyElement(element: JsonElement): Pair<JsonElement, Int> {
    if (element !is JsonObject) return element to 0
    val isPassword = (element["password"] as? JsonPrimitive)?.booleanOrNull == true
    var childRedactedCount = 0
    var redactedThisNode = false
    val rebuilt = buildJsonObject {
        element.forEach { (key, value) ->
            when {
                key == "children" && value is JsonArray -> {
                    val results = value.map(::redactHierarchyElement)
                    childRedactedCount += results.sumOf { it.second }
                    put(key, JsonArray(results.map { it.first }))
                }
                isPassword && (key == "text" || key == "contentDescription") -> redactedThisNode = true
                else -> put(key, value)
            }
        }
    }
    return rebuilt to (childRedactedCount + if (redactedThisNode) 1 else 0)
}

data class EvidenceBudgetResult(
    val included: List<EvidenceArtifactManifestEntry>,
    val exclusions: List<String>,
)

/**
 * Applies size and image-count budgets to candidate artifacts, in order. Callers should sort
 * [candidates] by priority beforehand (e.g. focused event first) — the first artifacts to exceed
 * a budget are excluded, not the largest.
 */
fun applyBudget(
    candidates: List<EvidenceArtifactManifestEntry>,
    maxTotalBytes: Long = EvidenceBudgets.MaxTotalBundleBytes,
    maxImages: Int = EvidenceBudgets.MaxImages,
): EvidenceBudgetResult {
    val included = mutableListOf<EvidenceArtifactManifestEntry>()
    val exclusions = mutableListOf<String>()
    var imageCount = 0
    var totalBytes = 0L
    candidates.forEach { candidate ->
        val isImage = candidate.kind == "screenshot" || candidate.kind.startsWith("image")
        when {
            isImage && imageCount >= maxImages ->
                exclusions += "${candidate.relativePath} (image budget of $maxImages exceeded)"
            totalBytes + candidate.sizeBytes > maxTotalBytes ->
                exclusions += "${candidate.relativePath} (bundle size budget exceeded)"
            else -> {
                included += candidate
                totalBytes += candidate.sizeBytes
                if (isImage) imageCount++
            }
        }
    }
    return EvidenceBudgetResult(included, exclusions)
}
