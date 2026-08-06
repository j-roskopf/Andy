package app.andy.store

import app.cash.sqldelight.db.SqlDriver
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
    driver.execute(
        identifier = null,
        sql = """
            CREATE TABLE IF NOT EXISTS kanban_board (
              id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
              updated_at_millis INTEGER NOT NULL,
              payload TEXT NOT NULL
            )
        """.trimIndent(),
        parameters = 0,
    )
}
