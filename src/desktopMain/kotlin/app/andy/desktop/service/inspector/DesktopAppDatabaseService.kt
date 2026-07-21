package app.andy.desktop.service.inspector

import app.andy.desktop.service.CommandRunner
import app.andy.desktop.service.DesktopDeviceService
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

class DesktopAppDatabaseService(
    private val runner: CommandRunner,
    private val devices: DesktopDeviceService,
    private val queriesDir: File = File(System.getProperty("user.home"), ".andy/db-queries"),
    private val sqliteLocator: () -> String? = ::locateHostSqlite3,
) : AppDatabaseService {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override suspend fun listDatabases(serial: String, packageName: String): Result<List<AppDatabaseInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureRunAs(serial, packageName)
                val discovered = mutableListOf<AppDatabaseInfo>()
                for (dir in listOf("databases", "no_backup")) {
                    val listing = runAs(serial, packageName, "ls", dir)
                    if (!listing.isSuccess) {
                        if (isMissingPath(listing)) continue
                        error(listing.stderr.ifBlank { listing.stdout }.ifBlank { "Unable to list $dir" })
                    }
                    val names = listing.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                    val dbNames = names.filter { name ->
                        !name.endsWith("-wal") &&
                            !name.endsWith("-shm") &&
                            !name.endsWith("-journal") &&
                            !name.endsWith(".lck")
                    }.sorted()
                    for (name in dbNames) {
                        val relative = "$dir/$name"
                        discovered += AppDatabaseInfo(
                            name = if (dir == "databases") name else relative,
                            path = relative,
                            hasWal = names.contains("$name-wal"),
                            hasShm = names.contains("$name-shm"),
                        )
                    }
                }
                discovered.sortedBy { it.path }
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
            val local = pullWorkingCopy(serial, packageName, dbName)
            try {
                val sqlite = sqliteLocator() ?: error("Host sqlite3 not found")
                val names = if (tables.isNotEmpty()) {
                    tables
                } else {
                    val listed = runner.run(
                        listOf(
                            sqlite,
                            "-header",
                            "-csv",
                            local.absolutePath,
                            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name;",
                        ),
                        60,
                    )
                    if (!listed.isSuccess) {
                        error(listed.stderr.ifBlank { listed.stdout }.ifBlank { "Failed to list tables" })
                    }
                    parseCsv(listed.stdout).rows.mapNotNull { it.firstOrNull() }
                }
                if (names.isEmpty()) return@runCatching emptyMap()
                val sql = names.joinToString(separator = " UNION ALL ") { name ->
                    "SELECT ${DbCellUpdate.sqlLiteral(name)} AS name, COUNT(*) AS c FROM ${DbCellUpdate.quoteIdent(name)}"
                } + ";"
                val counted = runner.run(listOf(sqlite, "-header", "-csv", local.absolutePath, sql), 60)
                if (!counted.isSuccess) {
                    error(counted.stderr.ifBlank { counted.stdout }.ifBlank { "Failed to count rows" })
                }
                parseCsv(counted.stdout).rows.associate { row ->
                    val name = row.getOrNull(0).orEmpty()
                    val count = row.getOrNull(1)?.toLongOrNull() ?: 0L
                    name to count
                }
            } finally {
                local.parentFile?.deleteRecursively()
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
            DbTableInfo(
                name = tableName,
                columns = columns,
                hasRowId = withoutRowId.rows.isEmpty(),
            )
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
                val limited = if (trimmed.contains(" limit ", ignoreCase = true)) {
                    "$trimmed;"
                } else {
                    "$trimmed LIMIT $limit;"
                }
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
            val working = pullWorkingCopy(serial, packageName, dbName)
            try {
                val target = File(localPath)
                target.parentFile?.mkdirs()
                working.copyTo(target, overwrite = true)
                CommandResult.success("Saved ${target.absolutePath}")
            } finally {
                working.parentFile?.deleteRecursively()
            }
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

    private suspend fun runSqlite(
        serial: String,
        packageName: String,
        dbName: String,
        sql: String,
    ): DbQueryResult {
        val local = pullWorkingCopy(serial, packageName, dbName)
        try {
            val sqlite = sqliteLocator() ?: error("Host sqlite3 not found")
            val result = runner.run(
                listOf(sqlite, "-header", "-csv", local.absolutePath, sql),
                60,
            )
            if (!result.isSuccess) {
                error(result.stderr.ifBlank { result.stdout }.ifBlank { "sqlite3 query failed" })
            }
            return parseCsv(result.stdout)
        } finally {
            local.parentFile?.deleteRecursively()
        }
    }

    private suspend fun runSqliteWrite(
        serial: String,
        packageName: String,
        dbName: String,
        sql: String,
    ): Int {
        val local = pullWorkingCopy(serial, packageName, dbName)
        try {
            val sqlite = sqliteLocator() ?: error("Host sqlite3 not found")
            val result = runner.run(listOf(sqlite, local.absolutePath, sql), 60)
            if (!result.isSuccess) {
                error(result.stderr.ifBlank { result.stdout }.ifBlank { "sqlite3 write failed" })
            }
            // Ensure WAL is merged before push so the device gets a single consistent main DB.
            runner.run(listOf(sqlite, local.absolutePath, "PRAGMA wal_checkpoint(TRUNCATE);"), 30)
            File(local.parentFile, "${local.name}-wal").delete()
            File(local.parentFile, "${local.name}-shm").delete()
            pushWorkingCopy(serial, packageName, dbName, local)
            val changes = runner.run(listOf(sqlite, local.absolutePath, "SELECT changes();"), 10)
            return changes.stdout.trim().toIntOrNull() ?: 0
        } finally {
            local.parentFile?.deleteRecursively()
        }
    }

    /**
     * Pulls a working copy of the on-device SQLite database.
     *
     * Room/SQLite apps often keep recent rows only in the WAL. A torn or mismatched
     * `-shm` alongside the WAL can make host sqlite3 ignore the WAL and report empty
     * tables. We pull the main DB + WAL, drop any `-shm`, then checkpoint so queries
     * always see committed rows.
     */
    private suspend fun pullWorkingCopy(serial: String, packageName: String, dbName: String): File {
        ensureRunAs(serial, packageName)
        val safePath = requireDbPath(dbName)
        val fileName = safePath.substringAfterLast('/')
        val dir = File(System.getProperty("java.io.tmpdir"), "andy-db-${UUID.randomUUID()}").also { it.mkdirs() }
        val local = File(dir, fileName)
        pullDbFile(serial, packageName, safePath, local, allowEmpty = false)
        // Always attempt WAL pull. Do not gate on `test -f` via `adb shell sh -c` —
        // adb re-parses arguments and breaks `sh -c`, which made us skip WAL and show
        // empty Room tables (data lived only in the WAL).
        val walFile = File(dir, "$fileName-wal")
        try {
            pullDbFile(serial, packageName, "$safePath-wal", walFile, allowEmpty = true)
        } catch (_: Throwable) {
            walFile.delete()
        }
        // Never reuse a device -shm: it can disagree with a non-atomic db/wal pull and
        // cause SQLite to skip WAL frames (empty-looking tables).
        File(dir, "$fileName-shm").delete()

        if (walFile.exists() && walFile.length() > 0L) {
            val sqlite = sqliteLocator() ?: error("Host sqlite3 not found")
            runner.run(
                listOf(sqlite, local.absolutePath, "PRAGMA wal_checkpoint(TRUNCATE);"),
                30,
            )
        }
        return local
    }

    private suspend fun pushWorkingCopy(serial: String, packageName: String, dbName: String, local: File) {
        val adb = devices.adbPath() ?: error("ADB not found")
        val safePath = requireDbPath(dbName)
        val remoteTmp = "/data/local/tmp/andy-db-${UUID.randomUUID()}.db"
        val push = runner.run(listOf(adb, "-s", serial, "push", local.absolutePath, remoteTmp), 60)
        if (!push.isSuccess) error(push.stderr.ifBlank { "Failed to push database" })
        val copy = runAs(serial, packageName, "cp", remoteTmp, safePath)
        runner.run(listOf(adb, "-s", serial, "shell", "rm", "-f", remoteTmp), 10)
        if (!copy.isSuccess) error(copy.stderr.ifBlank { copy.stdout }.ifBlank { "Failed to write database" })
        // Best-effort: drop stale WAL so the app reloads the replaced DB file.
        runAs(serial, packageName, "rm", "-f", "$safePath-wal", "$safePath-shm")
    }

    private suspend fun pullDbFile(
        serial: String,
        packageName: String,
        remotePath: String,
        local: File,
        allowEmpty: Boolean,
    ) {
        val adb = devices.adbPath() ?: error("ADB not found")
        local.parentFile?.mkdirs()
        val process = ProcessBuilder(listOf(adb, "-s", serial, "exec-out", "run-as", packageName, "cat", remotePath))
            .redirectErrorStream(false)
            .start()
        local.outputStream().use { out -> process.inputStream.copyTo(out) }
        val code = process.waitFor()
        val err = process.errorStream.bufferedReader().readText()
        if (code != 0 || !local.exists()) {
            error(err.ifBlank { "Failed to pull $remotePath" })
        }
        if (!allowEmpty && local.length() == 0L) {
            error("Pulled empty file for $remotePath")
        }
    }

    private suspend fun ensureRunAs(serial: String, packageName: String) {
        val probe = runAs(serial, packageName, "id")
        if (!probe.isSuccess) {
            error(
                probe.stderr.ifBlank { probe.stdout }
                    .ifBlank { "run-as failed — package must be debuggable" },
            )
        }
    }

    private suspend fun runAs(serial: String, packageName: String, vararg command: String): CommandResult {
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        return runner.run(listOf(adb, "-s", serial, "shell", "run-as", packageName, *command), 20)
    }

    private fun packageDir(packageName: String): File =
        File(queriesDir, packageName.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    private fun requireDbPath(dbName: String): String {
        val trimmed = dbName.trim().trimStart('/')
        require(trimmed.isNotEmpty() && !trimmed.contains("..") && trimmed.none { it == '\\' || it == '\'' }) {
            "Invalid database name"
        }
        return if (trimmed.contains('/')) trimmed else "databases/$trimmed"
    }

    private fun isMissingPath(result: CommandResult): Boolean =
        result.stderr.contains("No such file", ignoreCase = true) ||
            result.stdout.contains("No such file", ignoreCase = true)

    private fun parseCsv(text: String): DbQueryResult {
        val lines = text.lineSequence().filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return DbQueryResult(emptyList(), emptyList())
        val columns = parseCsvLine(lines.first())
        val rows = lines.drop(1).map { line ->
            val cells = parseCsvLine(line)
            columns.indices.map { index -> cells.getOrNull(index) }
        }
        return DbQueryResult(columns, rows)
    }

    private fun parseCsvLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
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
                else -> current.append(ch)
            }
            i++
        }
        values += current.toString()
        return values
    }
}

internal fun locateHostSqlite3(): String? {
    val candidates = listOfNotNull(
        System.getenv("ANDROID_HOME")?.let { "$it/platform-tools/sqlite3" },
        System.getenv("ANDROID_SDK_ROOT")?.let { "$it/platform-tools/sqlite3" },
        "/usr/bin/sqlite3",
        "/opt/homebrew/bin/sqlite3",
        "/usr/local/bin/sqlite3",
    )
    return candidates.firstOrNull { File(it).canExecute() }
}
