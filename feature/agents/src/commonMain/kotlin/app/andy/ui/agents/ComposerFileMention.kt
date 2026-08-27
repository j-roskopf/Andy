package app.andy.ui.agents

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.andy.model.HostSearchMode
import app.andy.model.HostSearchResult
import app.andy.service.HostFileService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

internal data class ComposerFileMention(val start: Int, val end: Int, val query: String)

private val COMPOSER_FILE_MENTION_TOKEN = Regex("""(?:^|\s)@(\S*)$""")

/**
 * Detects a trailing, in-progress `@token` at the end of the composer text —
 * mirrors [findComposerSlashCommand] but for file mentions.
 */
internal fun findComposerFileMention(text: String): ComposerFileMention? {
    val match = COMPOSER_FILE_MENTION_TOKEN.find(text) ?: return null
    val tokenStart = match.range.first + if (match.value.startsWith('@')) 0 else 1
    return ComposerFileMention(start = tokenStart, end = text.length, query = match.groupValues[1])
}

/**
 * Debounced project file search backing the `@` mention dropdown. Relies on
 * [LaunchedEffect]'s key-based cancellation for debouncing: every query change
 * cancels the prior delay/search and restarts it.
 */
@Composable
internal fun composerFileMentionResults(
    query: String?,
    hostFiles: HostFileService,
    roots: List<String>,
): List<HostSearchResult> {
    var results by remember { mutableStateOf(emptyList<HostSearchResult>()) }
    LaunchedEffect(query, roots) {
        // Drop prior matches immediately so Tab / labels never apply to a stale query.
        results = emptyList()
        if (query == null || roots.isEmpty()) return@LaunchedEffect
        delay(150)
        ensureComposerFileIndexes(hostFiles, roots)
        results = runCatching {
            hostFiles.search(query, HostSearchMode.FileName, roots, limit = 8)
        }.getOrDefault(emptyList())
    }
    return results
}

/**
 * Desktop [HostFileService.search] only loads a previously saved index. Build one
 * for any root that has never been crawled (Computer Files is otherwise the only
 * caller of [HostFileService.indexRoot]).
 */
internal suspend fun ensureComposerFileIndexes(hostFiles: HostFileService, roots: List<String>) {
    roots.forEach { root ->
        val status = runCatching { hostFiles.indexStatus(root) }.getOrNull() ?: return@forEach
        if (status.indexedFiles > 0) return@forEach
        if (status.indexing) {
            while (true) {
                delay(100)
                val next = runCatching { hostFiles.indexStatus(root) }.getOrNull() ?: break
                if (!next.indexing || next.indexedFiles > 0) break
            }
            return@forEach
        }
        var sawIndexing = false
        runCatching {
            hostFiles.indexRoot(root).first { next ->
                if (next.indexing) sawIndexing = true
                (sawIndexing && !next.indexing) || next.indexedFiles > 0
            }
        }
    }
}

internal fun HostSearchResult.relativePath(): String =
    path.removePrefix(root).trimStart('/', '\\')

internal fun insertFileMention(prompt: String, mention: ComposerFileMention, result: HostSearchResult): TextFieldValue {
    val insertion = "@${result.relativePath()} "
    return TextFieldValue(
        text = prompt.replaceRange(mention.start, mention.end, insertion),
        selection = TextRange(mention.start + insertion.length),
    )
}
