package app.andy.desktop.service.proxy

import app.andy.model.NetworkExchange
import app.andy.model.ProxyRule

internal object ProxyRuleJson {
    fun writeRules(rules: List<ProxyRule>): String {
        return rules.joinToString(prefix = "{\"rules\":[", postfix = "]}\n") { rule ->
            buildString {
                append("{")
                appendJsonField("id", rule.id)
                append(",")
                appendJsonField("name", rule.name)
                append(",\"enabled\":${rule.enabled}")
                append(",")
                appendJsonField("urlPattern", rule.urlPattern)
                append(",\"method\":")
                append(rule.method?.let(::quoteJson) ?: "null")
                append(",\"statusCode\":")
                append(rule.statusCode?.toString() ?: "null")
                append(",\"setHeaders\":{")
                append(rule.setHeaders.entries.joinToString(",") { "${quoteJson(it.key)}:${quoteJson(it.value)}" })
                append("},\"removeHeaders\":[")
                append(rule.removeHeaders.joinToString(",") { quoteJson(it) })
                append("],\"responseBody\":")
                append(rule.responseBody?.let(::quoteJson) ?: "null")
                append("}")
            }
        }
    }

    private fun StringBuilder.appendJsonField(name: String, value: String) {
        append(quoteJson(name))
        append(":")
        append(quoteJson(value))
    }
}

internal fun parseMitmproxyFlowLine(line: String): NetworkExchange? {
    if (!line.trimStart().startsWith("{") || jsonString(line, "type") != "flow") return null
    val id = jsonString(line, "id") ?: return null
    val requestHeaders = jsonObject(line, "requestHeaders")
    val responseHeaders = jsonObject(line, "responseHeaders")
    val started = jsonLong(line, "startedAtMillis") ?: System.currentTimeMillis()
    val completed = jsonLong(line, "completedAtMillis")
    return NetworkExchange(
        id = id,
        flowId = id,
        startedAtMillis = started,
        completedAtMillis = completed,
        method = jsonString(line, "method") ?: "-",
        url = jsonString(line, "url") ?: "-",
        statusCode = jsonInt(line, "statusCode"),
        contentType = jsonString(line, "contentType"),
        sizeBytes = jsonLong(line, "sizeBytes"),
        durationMillis = jsonLong(line, "durationMillis"),
        requestHeaders = requestHeaders,
        responseHeaders = responseHeaders,
        requestBodyPreview = jsonNullableString(line, "requestBodyPreview"),
        responseBodyPreview = jsonNullableString(line, "responseBodyPreview"),
        error = jsonNullableString(line, "error"),
        tlsStatus = jsonNullableString(line, "tlsStatus"),
        matchedRuleId = jsonNullableString(line, "matchedRuleId"),
    )
}
internal fun quoteJson(value: String): String {
    return buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }
}

internal fun jsonString(source: String, key: String): String? = jsonNullableString(source, key)

internal fun jsonNullableString(source: String, key: String): String? {
    val start = Regex(""""${Regex.escape(key)}"\s*:\s*""").find(source)?.range?.last?.plus(1) ?: return null
    val trimmed = source.substring(start).trimStart()
    if (trimmed.startsWith("null")) return null
    if (!trimmed.startsWith("\"")) return null
    val builder = StringBuilder()
    var escape = false
    for (char in trimmed.drop(1)) {
        if (escape) {
            builder.append(
                when (char) {
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    else -> char
                },
            )
            escape = false
        } else if (char == '\\') {
            escape = true
        } else if (char == '"') {
            return builder.toString()
        } else {
            builder.append(char)
        }
    }
    return null
}

internal fun jsonInt(source: String, key: String): Int? = Regex(""""${Regex.escape(key)}"\s*:\s*(-?\d+)""")
    .find(source)
    ?.groupValues
    ?.getOrNull(1)
    ?.toIntOrNull()

internal fun jsonLong(source: String, key: String): Long? = Regex(""""${Regex.escape(key)}"\s*:\s*(-?\d+)""")
    .find(source)
    ?.groupValues
    ?.getOrNull(1)
    ?.toLongOrNull()

internal fun jsonObject(source: String, key: String): Map<String, String> {
    val start = Regex(""""${Regex.escape(key)}"\s*:\s*\{""").find(source)?.range?.last ?: return emptyMap()
    var depth = 0
    var end = -1
    for (index in start until source.length) {
        when (source[index]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) {
                    end = index
                    break
                }
            }
        }
    }
    if (end < 0) return emptyMap()
    val body = source.substring(start + 1, end)
    return Regex(""""((?:\\.|[^"\\])*)"\s*:\s*"((?:\\.|[^"\\])*)"""")
        .findAll(body)
        .associate { match -> unescapeJson(match.groupValues[1]) to unescapeJson(match.groupValues[2]) }
}

internal fun unescapeJson(value: String): String {
    val builder = StringBuilder()
    var escape = false
    for (char in value) {
        if (escape) {
            builder.append(
                when (char) {
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    else -> char
                },
            )
            escape = false
        } else if (char == '\\') {
            escape = true
        } else {
            builder.append(char)
        }
    }
    return builder.toString()
}

