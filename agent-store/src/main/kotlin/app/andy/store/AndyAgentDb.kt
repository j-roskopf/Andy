package app.andy.store

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
        runCatching { AndyAgentDatabase.Schema.create(driver) }
    }
    return AndyAgentDatabase(driver)
}
