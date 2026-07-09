package app.andy.desktop.service.proxy

import app.andy.model.NetworkExchange
import app.andy.model.ProxyRule
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val ProxyJsonFormat = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

@Serializable
internal data class ProxyRulesFileDto(
    val rules: List<ProxyRuleDto> = emptyList(),
)

@Serializable
internal data class ProxyRuleDto(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val urlPattern: String,
    val method: String? = null,
    val statusCode: Int? = null,
    val setHeaders: Map<String, String> = emptyMap(),
    val removeHeaders: List<String> = emptyList(),
    val responseBody: String? = null,
)

@Serializable
internal data class MitmproxyFlowLineDto(
    val type: String,
    val id: String,
    val startedAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
    val method: String? = null,
    val url: String? = null,
    val statusCode: Int? = null,
    val contentType: String? = null,
    val sizeBytes: Long? = null,
    val durationMillis: Long? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val requestBodyPreview: String? = null,
    val responseBodyPreview: String? = null,
    val error: String? = null,
    val tlsStatus: String? = null,
    val matchedRuleId: String? = null,
)

internal object ProxyRuleJson {
    fun writeRules(rules: List<ProxyRule>): String {
        val dto = ProxyRulesFileDto(rules = rules.map { it.toDto() })
        return ProxyJsonFormat.encodeToString(dto) + "\n"
    }
}

internal fun parseMitmproxyFlowLine(line: String): NetworkExchange? {
    if (!line.trimStart().startsWith("{")) return null
    val dto = runCatching {
        ProxyJsonFormat.decodeFromString(MitmproxyFlowLineDto.serializer(), line)
    }.getOrNull() ?: return null
    if (dto.type != "flow") return null
    return NetworkExchange(
        id = dto.id,
        flowId = dto.id,
        startedAtMillis = dto.startedAtMillis ?: System.currentTimeMillis(),
        completedAtMillis = dto.completedAtMillis,
        method = dto.method ?: "-",
        url = dto.url ?: "-",
        statusCode = dto.statusCode,
        contentType = dto.contentType,
        sizeBytes = dto.sizeBytes,
        durationMillis = dto.durationMillis,
        requestHeaders = dto.requestHeaders,
        responseHeaders = dto.responseHeaders,
        requestBodyPreview = dto.requestBodyPreview,
        responseBodyPreview = dto.responseBodyPreview,
        error = dto.error,
        tlsStatus = dto.tlsStatus,
        matchedRuleId = dto.matchedRuleId,
    )
}

private fun ProxyRule.toDto(): ProxyRuleDto = ProxyRuleDto(
    id = id,
    name = name,
    enabled = enabled,
    urlPattern = urlPattern,
    method = method,
    statusCode = statusCode,
    setHeaders = setHeaders,
    removeHeaders = removeHeaders,
    responseBody = responseBody,
)
