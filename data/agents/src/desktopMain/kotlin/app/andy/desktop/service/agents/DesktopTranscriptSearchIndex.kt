package app.andy.desktop.service.agents

import app.andy.desktop.service.agents.acp.AcpTranscriptStore
import app.andy.model.AgentEvent
import app.andy.model.TranscriptSearchHit
import app.andy.store.TranscriptSearchSql
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Incremental FTS5 index over ACP transcript body text for Cmd+K.
 *
 * Searchable content: user messages, assistant text, tool titles/summaries.
 * Thinking blocks and full tool output dumps are intentionally skipped.
 */
class DesktopTranscriptSearchIndex(
    private val driver: SqlDriver,
    private val transcriptFileFor: (taskId: String) -> File,
) {
    private val mutex = Mutex()
    private val loadStore = AcpTranscriptStore(fileFor = transcriptFileFor)

    data class TaskRef(
        val taskId: String,
        val projectId: String?,
        val title: String,
        val projectName: String?,
        val updatedAtMillis: Long,
    )

    fun search(
        query: String,
        tasks: List<TaskRef>,
        excludeTaskIds: Set<String> = emptySet(),
        limit: Int = 40,
    ): Flow<TranscriptSearchHit> = flow {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_CHARS) return@flow
        val fts = ftsMatchQuery(trimmed) ?: return@flow
        val byId = tasks.associateBy { it.taskId }
        val eligible = tasks.filter { it.taskId !in excludeTaskIds }
        if (eligible.isEmpty()) return@flow

        val emitted = linkedSetOf<String>()
        val indexedHits = mutex.withLock {
            queryFts(fts, limit * 2)
        }
        for ((taskId, body) in indexedHits) {
            currentCoroutineContext().ensureActive()
            if (taskId in excludeTaskIds || taskId in emitted) continue
            val ref = byId[taskId] ?: continue
            val snippet = snippetFor(body, trimmed) ?: continue
            emitted += taskId
            emit(
                TranscriptSearchHit(
                    taskId = taskId,
                    projectId = ref.projectId,
                    title = ref.title.ifBlank { "Untitled chat" },
                    projectName = ref.projectName,
                    snippet = snippet,
                    atMillis = ref.updatedAtMillis,
                ),
            )
            if (emitted.size >= limit) return@flow
        }

        // Incomplete docs, plus complete docs whose on-disk transcript changed since index
        // (follow-ups can land after backfill marked the chat complete).
        val coldOrStale = eligible
            .asSequence()
            .filter { it.taskId !in emitted }
            .sortedByDescending { it.updatedAtMillis }
            .toList()
        for (ref in coldOrStale) {
            currentCoroutineContext().ensureActive()
            if (emitted.size >= limit) return@flow
            val body = mutex.withLock {
                val state = readState(ref.taskId)
                if (state != null && state.complete && sourceUnchanged(ref.taskId, state)) {
                    null
                } else {
                    reindexTaskUnlocked(ref.taskId)
                    readBody(ref.taskId)
                }
            } ?: continue
            val snippet = snippetFor(body, trimmed) ?: continue
            emitted += ref.taskId
            emit(
                TranscriptSearchHit(
                    taskId = ref.taskId,
                    projectId = ref.projectId,
                    title = ref.title.ifBlank { "Untitled chat" },
                    projectName = ref.projectName,
                    snippet = snippet,
                    atMillis = ref.updatedAtMillis,
                ),
            )
        }
    }.flowOn(Dispatchers.IO)

    suspend fun reindexTask(taskId: String) = withContext(Dispatchers.IO) {
        mutex.withLock { reindexTaskUnlocked(taskId) }
    }

    suspend fun removeTask(taskId: String) = withContext(Dispatchers.IO) {
        mutex.withLock { removeTaskUnlocked(taskId) }
    }

    /** Idle backfill: index tasks newest-first that are not yet complete. */
    suspend fun backfill(taskIdsNewestFirst: List<String>) = withContext(Dispatchers.IO) {
        for (taskId in taskIdsNewestFirst) {
            mutex.withLock {
                val state = readState(taskId)
                if (state?.complete == true && sourceUnchanged(taskId, state)) return@withLock
                reindexTaskUnlocked(taskId)
            }
        }
    }

    private fun reindexTaskUnlocked(taskId: String): String {
        val file = transcriptFileFor(taskId)
        val mtime = file.takeIf { it.isFile }?.lastModified() ?: 0L
        val len = file.takeIf { it.isFile }?.length() ?: 0L
        val events = runCatching { loadStore.load(taskId) }.getOrDefault(emptyList())
        val body = buildSearchableBody(events)
        writeDoc(taskId, body, mtime, len, complete = true)
        return body
    }

    private fun removeTaskUnlocked(taskId: String) {
        driver.execute(null, TranscriptSearchSql.DELETE_FTS_FOR_TASK, 1) { bindString(0, taskId) }
        driver.execute(null, TranscriptSearchSql.DELETE_DOC, 1) { bindString(0, taskId) }
    }

    private fun writeDoc(taskId: String, body: String, mtime: Long, len: Long, complete: Boolean) {
        driver.execute(null, TranscriptSearchSql.DELETE_FTS_FOR_TASK, 1) { bindString(0, taskId) }
        if (body.isNotBlank()) {
            driver.execute(null, TranscriptSearchSql.INSERT_FTS, 2) {
                bindString(0, taskId)
                bindString(1, body)
            }
        }
        driver.execute(null, TranscriptSearchSql.UPSERT_DOC, 5) {
            bindString(0, taskId)
            bindString(1, body)
            bindLong(2, mtime)
            bindLong(3, len)
            bindLong(4, if (complete) 1L else 0L)
        }
    }

    private fun queryFts(matchQuery: String, limit: Int): List<Pair<String, String>> =
        driver.executeQuery(
            identifier = null,
            sql = TranscriptSearchSql.SEARCH_FTS,
            parameters = 2,
            binders = {
                bindString(0, matchQuery)
                bindLong(1, limit.toLong())
            },
            mapper = { cursor ->
                val rows = mutableListOf<Pair<String, String>>()
                while (cursor.next().value) {
                    val taskId = cursor.getString(0) ?: continue
                    val body = cursor.getString(1) ?: continue
                    rows += taskId to body
                }
                QueryResult.Value(rows)
            },
        ).value

    private fun completeTaskIds(): Set<String> =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT task_id FROM transcript_search_doc WHERE complete = 1",
            parameters = 0,
            mapper = { cursor ->
                val ids = mutableSetOf<String>()
                while (cursor.next().value) {
                    cursor.getString(0)?.let(ids::add)
                }
                QueryResult.Value(ids)
            },
        ).value

    private fun readBody(taskId: String): String? = readState(taskId)?.body

    private data class DocState(
        val body: String,
        val sourceMtime: Long,
        val sourceLen: Long,
        val complete: Boolean,
    )

    private fun readState(taskId: String): DocState? =
        driver.executeQuery(
            identifier = null,
            sql = TranscriptSearchSql.SELECT_DOC,
            parameters = 1,
            binders = { bindString(0, taskId) },
            mapper = { cursor ->
                if (!cursor.next().value) {
                    QueryResult.Value(null)
                } else {
                    QueryResult.Value(
                        DocState(
                            body = cursor.getString(0).orEmpty(),
                            sourceMtime = cursor.getLong(1) ?: 0L,
                            sourceLen = cursor.getLong(2) ?: 0L,
                            complete = (cursor.getLong(3) ?: 0L) != 0L,
                        ),
                    )
                }
            },
        ).value

    private fun sourceUnchanged(taskId: String, state: DocState): Boolean {
        val file = transcriptFileFor(taskId)
        if (!file.isFile) return state.sourceLen == 0L
        return file.lastModified() == state.sourceMtime && file.length() == state.sourceLen
    }

    companion object {
        const val MIN_QUERY_CHARS = 2

        fun buildSearchableBody(events: List<AgentEvent>): String {
            if (events.isEmpty()) return ""
            val parts = ArrayList<String>(events.size)
            for (event in events) {
                searchableText(event)?.takeIf { it.isNotBlank() }?.let(parts::add)
            }
            return parts.joinToString("\n")
        }

        fun searchableText(event: AgentEvent): String? = when (event) {
            is AgentEvent.UserMessage -> event.text
            is AgentEvent.AssistantText -> event.text.takeUnless { event.isStreamDelta }
            is AgentEvent.ToolCall -> {
                val title = event.toolName.trim()
                val summary = event.summary.trim()
                when {
                    title.isBlank() && summary.isBlank() -> null
                    title.isBlank() -> summary
                    summary.isBlank() || summary == title -> title
                    else -> "$title $summary"
                }
            }
            is AgentEvent.TaskResult -> event.finalText
            else -> null
        }

        fun ftsMatchQuery(raw: String): String? {
            val tokens = raw
                .lowercase()
                .split(Regex("[\\s\\p{Punct}]+"))
                .map { it.filter { ch -> ch.isLetterOrDigit() } }
                .filter { it.length >= MIN_QUERY_CHARS }
            if (tokens.isEmpty()) return null
            // Prefix tokens only — input is already stripped to alphanumerics so MATCH stays safe.
            return tokens.joinToString(" AND ") { "$it*" }
        }

        fun snippetFor(body: String, query: String, radius: Int = 56): String? {
            val needle = query.trim().split(Regex("\\s+")).firstOrNull { it.length >= MIN_QUERY_CHARS }
                ?: return null
            val idx = body.indexOf(needle, ignoreCase = true)
            if (idx < 0) {
                // FTS may match stemmed/prefix forms; fall back to a head snippet when body matched.
                val head = body.trim().replace(Regex("\\s+"), " ")
                if (head.isEmpty()) return null
                return if (head.length <= radius * 2) head else head.take(radius * 2).trimEnd() + "…"
            }
            val start = (idx - radius).coerceAtLeast(0)
            val end = (idx + needle.length + radius).coerceAtMost(body.length)
            val slice = body.substring(start, end).replace(Regex("\\s+"), " ").trim()
            val prefix = if (start > 0) "…" else ""
            val suffix = if (end < body.length) "…" else ""
            return prefix + slice + suffix
        }
    }
}
