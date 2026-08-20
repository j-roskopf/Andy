package app.andy.desktop.service.ios

import app.andy.desktop.service.CommandRunner
import app.andy.desktop.service.inspector.locateHostSqlite3
import app.andy.model.AppDatabaseInfo
import app.andy.model.DbCellUpdate
import app.andy.model.DbColumnInfo
import app.andy.model.DbQueryResult
import app.andy.model.DbTableInfo
import app.andy.model.SavedSqlQuery
import app.andy.service.AppDatabaseService
import app.andy.service.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * iOS App Databases (Phase 2.2). Simulator SQLite files sit directly in the host app data
 * container — query them with the host `sqlite3` binary, no pull/push/`run-as` needed at all
 * (strictly simpler than the Android backend).
 */
class DesktopIosAppDatabaseService(
    private val runner: CommandRunner,
    private val queriesDir: File = File(System.getProperty("user.home"), ".andy/db-queries"),
    private val sqliteLocator: () -> String? = ::locateHostSqlite3,
) : AppDatabaseService {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override suspend fun listDatabases(serial: String, packageName: String): Result<List<AppDatabaseInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val container = resolveContainer(serial, packageName)
                    ?: error("Could not resolve app container for $packageName")
                val root = File(container)
                findDatabaseFiles(root).map { file ->
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    val wal = File(file.parentFile, "${file.name}-wal").isFile
                    val shm = File(file.parentFile, "${file.name}-shm").isFile
                    AppDatabaseInfo(name = relative, path = relative, hasWal = wal, hasShm = shm)
                }.sortedBy { it.path }
            }
        }

    override suspend fun listTables(serial: String, packageName: String, dbName: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val result = runSqlite(
                    serial,
                    packageName,
                    dbName,
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name;",
                )
                result.rows.mapNotNull { it.firstOrNull() }.sorted()
            }
        }

    override suspend fun tableRowCounts(
        serial: String,
        packageName: String,
        dbName: String,
        tables: List<String>,
    ): Result<Map<String, Long>> = withContext(Dispatchers.IO) {
        runCatching {
            val names = tables.ifEmpty { listTables(serial, packageName, dbName).getOrThrow() }
            if (names.isEmpty()) return@runCatching emptyMap()
            val sql = names.joinToString(separator = " UNION ALL ") { name ->
                "SELECT ${DbCellUpdate.sqlLiteral(name)} AS name, COUNT(*) AS c FROM ${DbCellUpdate.quoteIdent(name)}"
            } + ";"
            val counted = runSqlite(serial, packageName, dbName, sql)
            counted.rows.associate { row ->
                val name = row.getOrNull(0).orEmpty()
                val count = row.getOrNull(1)?.toLongOrNull() ?: 0L
                name to count
            }
        }
    }

    override suspend fun tableInfo(
        serial: String,
        packageName: String,
        dbName: String,
        tableName: String,
    ): Result<DbTableInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val pragma = runSqlite(serial, packageName, dbName, "PRAGMA table_info(${DbCellUpdate.quoteIdent(tableName)});")
            val columns = pragma.rows.map { row ->
                DbColumnInfo(
                    name = row.getOrNull(1).orEmpty(),
                    type = row.getOrNull(2).orEmpty(),
                    primaryKey = (row.getOrNull(5)?.toIntOrNull() ?: 0) > 0,
                )
            }
            val withoutRowId = runSqlite(
                serial,
                packageName,
                dbName,
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=${DbCellUpdate.sqlLiteral(tableName)} AND sql LIKE '%WITHOUT ROWID%';",
            )
            DbTableInfo(name = tableName, columns = columns, hasRowId = withoutRowId.rows.isEmpty())
        }
    }

    override suspend fun browseTable(
        serial: String,
        packageName: String,
        dbName: String,
        tableName: String,
        limit: Int,
        offset: Int,
    ): Result<DbQueryResult> = withContext(Dispatchers.IO) {
        runCatching {
            val info = tableInfo(serial, packageName, dbName, tableName).getOrThrow()
            val select = buildString {
                append("SELECT ")
                if (info.hasRowId) append("rowid AS __rowid__, ")
                append("* FROM ${DbCellUpdate.quoteIdent(tableName)} LIMIT $limit OFFSET $offset;")
            }
            runSqlite(serial, packageName, dbName, select)
        }
    }

    override suspend fun query(
        serial: String,
        packageName: String,
        dbName: String,
        sql: String,
        limit: Int,
    ): Result<DbQueryResult> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = sql.trim().trimEnd(';')
            val isSelect = trimmed.startsWith("select", ignoreCase = true) ||
                trimmed.startsWith("pragma", ignoreCase = true) ||
                trimmed.startsWith("with", ignoreCase = true)
            if (isSelect) {
                val limited = if (trimmed.contains(" limit ", ignoreCase = true)) "$trimmed;" else "$trimmed LIMIT $limit;"
                runSqlite(serial, packageName, dbName, limited)
            } else {
                val affected = runSqliteWrite(serial, packageName, dbName, "$trimmed;")
                DbQueryResult(columns = emptyList(), rows = emptyList(), rowsAffected = affected, message = "OK")
            }
        }
    }

    override suspend fun updateCell(
        serial: String,
        packageName: String,
        dbName: String,
        tableName: String,
        column: String,
        newValue: String?,
        rowId: Long?,
        primaryKeyColumn: String?,
        primaryKeyValue: String?,
    ): CommandResult = withContext(Dispatchers.IO) {
        val sql = DbCellUpdate.buildUpdateSql(tableName, column, newValue, rowId, primaryKeyColumn, primaryKeyValue)
            ?: return@withContext CommandResult.failure("Row is not editable without rowid or a single primary key")
        runCatching {
            runSqliteWrite(serial, packageName, dbName, sql)
            CommandResult.success("Updated")
        }.getOrElse { CommandResult.failure(it.message ?: "Update failed") }
    }

    override suspend fun pullToHost(
        serial: String,
        packageName: String,
        dbName: String,
        localPath: String,
    ): CommandResult = withContext(Dispatchers.IO) {
        runCatching {
            val file = resolveDbFile(serial, packageName, dbName) ?: error("Could not resolve app container for $packageName")
            if (!file.isFile) error("Database not found: $dbName")
            val target = File(localPath)
            target.parentFile?.mkdirs()
            file.copyTo(target, overwrite = true)
            CommandResult.success("Saved ${target.absolutePath}")
        }.getOrElse { CommandResult.failure(it.message ?: "Pull failed") }
    }

    override suspend fun listSavedQueries(packageName: String): List<SavedSqlQuery> = withContext(Dispatchers.IO) {
        packageDir(packageName).listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file -> runCatching { json.decodeFromString<SavedSqlQuery>(file.readText()) }.getOrNull() }
            ?.sortedByDescending { it.updatedAtMillis }
            .orEmpty()
    }

    override suspend fun saveQuery(packageName: String, name: String, sql: String): CommandResult =
        withContext(Dispatchers.IO) {
            val dir = packageDir(packageName).also { it.mkdirs() }
            val id = UUID.randomUUID().toString()
            val query = SavedSqlQuery(
                id = id,
                name = name.ifBlank { "Query" },
                sql = sql,
                packageName = packageName,
                updatedAtMillis = System.currentTimeMillis(),
            )
            File(dir, "$id.json").writeText(json.encodeToString(query))
            CommandResult.success(id)
        }

    override suspend fun deleteQuery(packageName: String, id: String): Boolean = withContext(Dispatchers.IO) {
        File(packageDir(packageName), "$id.json").delete()
    }

    private suspend fun runSqlite(serial: String, packageName: String, dbName: String, sql: String): DbQueryResult {
        val file = resolveDbFile(serial, packageName, dbName) ?: error("Could not resolve app container for $packageName")
        if (!file.isFile) error("Database not found: $dbName")
        val sqlite = sqliteLocator() ?: error("Host sqlite3 not found")
        val result = runner.run(
            listOf(sqlite, "-header", "-csv", "-nullvalue", IosSqliteNullMarker, file.absolutePath, sql),
            HOST_SQLITE_TIMEOUT_SECONDS,
        )
        if (!result.isSuccess) error(result.stderr.ifBlank { result.stdout }.ifBlank { "sqlite3 query failed" })
        return parseCsv(result.stdout)
    }

    private suspend fun runSqliteWrite(serial: String, packageName: String, dbName: String, sql: String): Int {
        val file = resolveDbFile(serial, packageName, dbName) ?: error("Could not resolve app container for $packageName")
        if (!file.isFile) error("Database not found: $dbName")
        val sqlite = sqliteLocator() ?: error("Host sqlite3 not found")
        val result = runner.run(listOf(sqlite, file.absolutePath, sql), HOST_SQLITE_TIMEOUT_SECONDS)
        if (!result.isSuccess) error(result.stderr.ifBlank { result.stdout }.ifBlank { "sqlite3 write failed" })
        runner.run(listOf(sqlite, file.absolutePath, "PRAGMA wal_checkpoint(TRUNCATE);"), 30)
        val changes = runner.run(listOf(sqlite, file.absolutePath, "SELECT changes();"), 10)
        return changes.stdout.trim().toIntOrNull() ?: 0
    }

    private suspend fun resolveContainer(serial: String, packageName: String): String? {
        val result = runner.run(listOf("xcrun", "simctl", "get_app_container", serial, packageName, "data"))
        return result.stdout.trim().takeIf { result.isSuccess && it.isNotBlank() }
    }

    private suspend fun resolveDbFile(serial: String, packageName: String, dbName: String): File? {
        val container = resolveContainer(serial, packageName) ?: return null
        return File(container, requireDbPath(dbName))
    }

    private fun findDatabaseFiles(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in DbExtensions }
            .toList()
    }

    private fun requireDbPath(dbName: String): String {
        val trimmed = dbName.trim().trimStart('/')
        require(trimmed.isNotEmpty() && !trimmed.contains("..")) { "Invalid database name" }
        return trimmed
    }

    private fun packageDir(packageName: String): File =
        File(queriesDir, packageName.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    private fun parseCsv(text: String): DbQueryResult {
        val records = parseCsvRecords(text)
        if (records.isEmpty()) return DbQueryResult(emptyList(), emptyList())
        val columns = records.first()
        val rows = records.drop(1).map { cells ->
            columns.indices.map { index ->
                when (val cell = cells.getOrNull(index)) {
                    null -> null
                    IosSqliteNullMarker -> null
                    else -> cell
                }
            }
        }
        return DbQueryResult(columns, rows)
    }

    /** Record-oriented CSV parse so quoted fields may contain embedded newlines. */
    private fun parseCsvRecords(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < text.length && text[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == ',' && !inQuotes -> {
                    values += current.toString()
                    current.clear()
                }
                (ch == '\n' || ch == '\r') && !inQuotes -> {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    values += current.toString()
                    current.clear()
                    if (values.size > 1 || values.singleOrNull()?.isNotEmpty() == true) {
                        records += values.toList()
                    }
                    values.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        if (current.isNotEmpty() || values.isNotEmpty()) {
            values += current.toString()
            if (values.size > 1 || values.singleOrNull()?.isNotEmpty() == true) {
                records += values.toList()
            }
        }
        return records
    }

    companion object {
        private const val HOST_SQLITE_TIMEOUT_SECONDS = 120L
        private val DbExtensions = setOf("sqlite", "sqlite3", "db")
    }
}

private const val IosSqliteNullMarker = "\u0001NULL\u0001"
