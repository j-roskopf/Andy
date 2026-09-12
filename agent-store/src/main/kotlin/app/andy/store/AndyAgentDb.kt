package app.andy.store

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties

/**
 * The database plus the driver that backs it. The driver is exposed so callers can run the
 * JSON1-based statements in [AgentJsonSql], which SQLDelight cannot generate.
 */
class AndyAgentDb(
    val database: AndyAgentDatabase,
    val driver: SqlDriver,
)

fun openAndyAgentDatabase(dbFile: File): AndyAgentDb {
    dbFile.parentFile?.mkdirs()
    val fresh = !dbFile.exists()
    val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties())
    if (fresh) {
        AndyAgentDatabase.Schema.create(driver)
    }
    // Schema.create aborts on the first already-existing table, so additive tables
    // (e.g. kanban_board, transcript FTS) never land on older agents.db files. Always
    // apply IF NOT EXISTS migrations — including on fresh DBs for FTS (not in .sq).
    ensureAdditiveSchema(driver)
    return AndyAgentDb(AndyAgentDatabase(driver), driver)
}

/**
 * Statements that need SQLite's JSON1 functions, which none of SQLDelight's dialects model, so
 * they cannot live in AgentStore.sq. All of them exist to keep `completedChanges.diffs` — tens of
 * MB of stored diff text — out of the JVM heap.
 */
object AgentJsonSql {
    /**
     * Startup projection of [AgentStore.sq]'s `selectAllTasks` with `completedChanges.diffs`
     * stripped. Read-only: stored rows keep their diffs untouched.
     */
    const val SELECT_ALL_TASKS_LITE: String =
        "SELECT id, json_remove(payload, '\$.completedChanges.diffs') AS payload " +
            "FROM agent_task ORDER BY created_at_millis DESC"

    /** One task's stored diffs, fetched when a diff view actually opens. */
    const val SELECT_TASK_DIFFS: String =
        "SELECT json_extract(payload, '\$.completedChanges.diffs') FROM agent_task WHERE id = ?"

    /**
     * Writes a task whose in-memory snapshot never hydrated its diffs, splicing the already-stored
     * diffs back inside SQLite so the save cannot blank them. The `ELSE` covers both a task that
     * genuinely has no stored diffs and one whose `completedChanges` was cleared (e.g. by undo),
     * which must be allowed to write through.
     */
    const val UPDATE_TASK_PRESERVING_DIFFS: String =
        """
        UPDATE agent_task SET
          status = ?, agent = ?, created_at_millis = ?, updated_at_millis = ?,
          payload = CASE
            WHEN json_extract(payload, '$.completedChanges.diffs') IS NOT NULL
             AND json_extract(?, '$.completedChanges') IS NOT NULL
            THEN json_set(?, '$.completedChanges.diffs',
                          json_extract(payload, '$.completedChanges.diffs'))
            ELSE ?
          END
        WHERE id = ?
        """
}

/**
 * Tables added after the initial agents.db schema. Kept in sync with AgentStore.sq.
 * Prefer CREATE TABLE IF NOT EXISTS over Schema.create for existing databases.
 */
internal fun ensureAdditiveSchema(driver: SqlDriver) {
    migrateLegacyKanbanTable(driver)
    driver.execute(
        identifier = null,
        sql = """
            CREATE TABLE IF NOT EXISTS kanban_board (
              project_id TEXT NOT NULL PRIMARY KEY,
              updated_at_millis INTEGER NOT NULL,
              payload TEXT NOT NULL
            )
        """.trimIndent(),
        parameters = 0,
    )
    driver.execute(
        identifier = null,
        sql = """
            CREATE TABLE IF NOT EXISTS automation (
              id TEXT NOT NULL PRIMARY KEY,
              project_id TEXT NOT NULL,
              updated_at_millis INTEGER NOT NULL,
              payload TEXT NOT NULL
            )
        """.trimIndent(),
        parameters = 0,
    )
    ensureTranscriptSearchSchema(driver)
}

/**
 * Cmd+K transcript body search. Kept as raw SQL because SQLDelight does not model FTS5
 * virtual tables cleanly, and older agents.db files need IF NOT EXISTS migrations.
 */
internal fun ensureTranscriptSearchSchema(driver: SqlDriver) {
    driver.execute(
        identifier = null,
        sql = """
            CREATE TABLE IF NOT EXISTS transcript_search_doc (
              task_id TEXT NOT NULL PRIMARY KEY,
              body TEXT NOT NULL,
              source_mtime INTEGER NOT NULL,
              source_len INTEGER NOT NULL,
              complete INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent(),
        parameters = 0,
    )
    driver.execute(
        identifier = null,
        sql = """
            CREATE VIRTUAL TABLE IF NOT EXISTS transcript_fts USING fts5(
              task_id UNINDEXED,
              body,
              tokenize = 'unicode61'
            )
        """.trimIndent(),
        parameters = 0,
    )
}

/** Raw SQL for the transcript FTS sidecar (see [ensureTranscriptSearchSchema]). */
object TranscriptSearchSql {
    val UPSERT_DOC: String =
        """
        INSERT INTO transcript_search_doc(task_id, body, source_mtime, source_len, complete)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(task_id) DO UPDATE SET
          body = excluded.body,
          source_mtime = excluded.source_mtime,
          source_len = excluded.source_len,
          complete = excluded.complete
        """.trimIndent()

    const val DELETE_FTS_FOR_TASK: String =
        "DELETE FROM transcript_fts WHERE task_id = ?"

    const val INSERT_FTS: String =
        "INSERT INTO transcript_fts(task_id, body) VALUES (?, ?)"

    const val DELETE_DOC: String =
        "DELETE FROM transcript_search_doc WHERE task_id = ?"

    const val SELECT_DOC: String =
        "SELECT body, source_mtime, source_len, complete FROM transcript_search_doc WHERE task_id = ?"

    const val SELECT_INCOMPLETE_TASK_IDS: String =
        "SELECT task_id FROM transcript_search_doc WHERE complete = 0"

    val SEARCH_FTS: String =
        """
        SELECT transcript_fts.task_id, d.body
        FROM transcript_fts
        JOIN transcript_search_doc AS d ON d.task_id = transcript_fts.task_id
        WHERE transcript_fts MATCH ?
        ORDER BY d.source_mtime DESC
        LIMIT ?
        """.trimIndent()
}

/**
 * The board used to be a singleton. There is no reliable project to attribute that data to,
 * so old-shaped tables are intentionally dropped before creating the project-keyed schema.
 */
private fun migrateLegacyKanbanTable(driver: SqlDriver) {
    val columns = driver.executeQuery(
        identifier = null,
        sql = "PRAGMA table_info(kanban_board)",
        mapper = { cursor ->
            val names = mutableListOf<String>()
            while (cursor.next().value) {
                cursor.getString(1)?.let(names::add)
            }
            QueryResult.Value(names)
        },
        parameters = 0,
    ).value
    if (columns.isNotEmpty() && "project_id" !in columns) {
        driver.execute(
            identifier = null,
            sql = "DROP TABLE kanban_board",
            parameters = 0,
        )
    }
}
