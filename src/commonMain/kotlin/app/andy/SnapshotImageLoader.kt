package app.andy

import androidx.compose.ui.graphics.ImageBitmap

expect fun loadImageBitmap(path: String): ImageBitmap?
expect fun loadImageBitmap(bytes: ByteArray): ImageBitmap?

/** Fetches raw bytes for a remote image URL. Returns null on failure or unsupported hosts. */
expect suspend fun fetchRemoteBytes(url: String): ByteArray?

/**
 * Resolves a new-chat background URI (local path or http(s)) into an [ImageBitmap].
 * Returns null when the URI is blank, unloadable, or unsupported on this host.
 */
suspend fun loadNewChatBackgroundBitmap(uri: String): ImageBitmap? {
    val normalized = normalizeNewChatBackgroundUri(uri) ?: return null
    return runCatching {
        if (isRemoteNewChatBackgroundUri(normalized)) {
            fetchRemoteBytes(normalized)?.let { loadImageBitmap(it) }
        } else {
            loadImageBitmap(normalized)
        }
    }.getOrNull()
}
