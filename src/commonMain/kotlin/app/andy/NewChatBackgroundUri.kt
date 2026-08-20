package app.andy

/**
 * Trims and normalizes a user-entered new-chat background URI.
 * Returns null when blank after trim.
 *
 * - `http(s):` URLs are kept as-is (trimmed).
 * - `file://` prefixes are stripped so [loadImageBitmap] can open a local path.
 * - Other values are treated as filesystem paths.
 */
fun normalizeNewChatBackgroundUri(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return when {
        isRemoteNewChatBackgroundUri(trimmed) -> trimmed
        trimmed.startsWith("file://", ignoreCase = true) ->
            trimmed.drop(7).substringBefore('#').ifBlank { null }
        else -> trimmed
    }
}

fun isRemoteNewChatBackgroundUri(uri: String): Boolean {
    val value = uri.trim()
    return value.startsWith("http://", ignoreCase = true) ||
        value.startsWith("https://", ignoreCase = true)
}
