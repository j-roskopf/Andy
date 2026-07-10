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
    val id: String? = null,
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
    val sni: String? = null,
    val peer: String? = null,
    val reason: String? = null,
    val sha256: String? = null,
    val version: Int? = null,
    val count: Long? = null,
)

internal sealed interface MitmproxyEvent {
    data class Exchange(val exchange: NetworkExchange) : MitmproxyEvent
    data class ClientConnected(val id: String, val peer: String?, val atMillis: Long) : MitmproxyEvent
    data class ClientDisconnected(val id: String, val peer: String?, val atMillis: Long) : MitmproxyEvent
    data class AddonHello(val sha256: String?, val version: Int?, val atMillis: Long) : MitmproxyEvent
    data class EventsDropped(val count: Long) : MitmproxyEvent
    data class AddonError(val id: String, val error: String?, val atMillis: Long) : MitmproxyEvent
}

internal object ProxyRuleJson {
    fun writeRules(rules: List<ProxyRule>): String {
        val dto = ProxyRulesFileDto(rules = rules.map { it.toDto() })
        return ProxyJsonFormat.encodeToString(dto) + "\n"
    }
}

internal fun parseMitmproxyFlowLine(line: String): NetworkExchange? =
    (parseMitmproxyEvent(line) as? MitmproxyEvent.Exchange)?.exchange

internal fun parseMitmproxyEvent(line: String): MitmproxyEvent? {
    if (!line.trimStart().startsWith("{")) return null
    val dto = runCatching {
        ProxyJsonFormat.decodeFromString(MitmproxyFlowLineDto.serializer(), line)
    }.getOrNull() ?: return null
    return when (dto.type) {
        "flow", "tls_failed" -> {
            val id = dto.id ?: return null
            MitmproxyEvent.Exchange(dto.toNetworkExchange(id))
        }
        "client_connected" -> MitmproxyEvent.ClientConnected(
            id = dto.id ?: return null,
            peer = dto.peer,
            atMillis = dto.startedAtMillis ?: System.currentTimeMillis(),
        )
        "client_disconnected" -> MitmproxyEvent.ClientDisconnected(
            id = dto.id ?: return null,
            peer = dto.peer,
            atMillis = dto.startedAtMillis ?: System.currentTimeMillis(),
        )
        "addon_hello" -> MitmproxyEvent.AddonHello(
            sha256 = dto.sha256,
            version = dto.version,
            atMillis = dto.startedAtMillis ?: System.currentTimeMillis(),
        )
        "events_dropped" -> MitmproxyEvent.EventsDropped(count = dto.count ?: 0L)
        "addon_error" -> MitmproxyEvent.AddonError(
            id = dto.id ?: "addon-error",
            error = dto.error,
            atMillis = dto.startedAtMillis ?: System.currentTimeMillis(),
        )
        else -> null
    }
}

private fun MitmproxyFlowLineDto.toNetworkExchange(id: String): NetworkExchange {
    val host = sni ?: url?.removePrefix("https://")?.substringBefore('/') ?: "unknown-host"
    val synthesizedError = when {
        type != "tls_failed" -> error
        !error.isNullOrBlank() -> error
        else -> "Client rejected Andy's CA for $host: ${reason ?: "client TLS handshake failed"}"
    }
    return NetworkExchange(
        id = id,
        flowId = id,
        startedAtMillis = startedAtMillis ?: System.currentTimeMillis(),
        completedAtMillis = completedAtMillis,
        method = method ?: if (type == "tls_failed") "TLS" else "-",
        url = url ?: if (type == "tls_failed") "https://$host/" else "-",
        statusCode = statusCode,
        contentType = contentType,
        sizeBytes = sizeBytes,
        durationMillis = durationMillis,
        requestHeaders = requestHeaders,
        responseHeaders = responseHeaders,
        requestBodyPreview = requestBodyPreview,
        responseBodyPreview = responseBodyPreview,
        error = synthesizedError,
        tlsStatus = tlsStatus ?: if (type == "tls_failed") "tls" else null,
        matchedRuleId = matchedRuleId,
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
