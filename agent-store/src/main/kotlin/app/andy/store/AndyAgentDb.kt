package app.andy.store

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties

fun openAndyAgentDatabase(dbFile: File): AndyAgentDatabase {
    dbFile.parentFile?.mkdirs()
    val fresh = !dbFile.exists()
    val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties())
    if (fresh) {
        AndyAgentDatabase.Schema.create(driver)
    } else {
        // Schema.create aborts on the first already-existing table, so additive tables
        // (e.g. kanban_board) never land on older agents.db files. Apply IF NOT EXISTS
        // migrations for schema additions instead.
        ensureAdditiveSchema(driver)
    }
    return AndyAgentDatabase(driver)
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
