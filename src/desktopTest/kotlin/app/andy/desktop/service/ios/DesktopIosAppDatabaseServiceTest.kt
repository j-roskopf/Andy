package app.andy.desktop.service.ios

import app.andy.desktop.service.CommandRunner
import app.andy.service.CommandResult
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopIosAppDatabaseServiceTest {
    private val tempDirs = mutableListOf<File>()
    private val bundleId = "com.example.myapp"
    private val fakeSqlite = "/usr/bin/sqlite3-fake"

    private fun newTempDir(): File =
        File.createTempFile("andy-ios-db-test", "").also {
            it.delete()
            it.mkdirs()
            tempDirs += it
        }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun containerRunner(
        container: File,
        onSqlite: (command: List<String>) -> CommandResult = { CommandResult.failure("unexpected sqlite call") },
    ) = CommandRunner { command, _ ->
        if (command.contains("get_app_container")) CommandResult.success(container.absolutePath)
        else if (command.firstOrNull() == fakeSqlite) onSqlite(command)
        else CommandResult.failure("unexpected: $command")
    }

    @Test
    fun listDatabasesFindsSqliteFilesAndDetectsWalShmSidecars() = runBlocking {
        val container = newTempDir()
        File(container, "app.sqlite").writeText("db")
        File(container, "app.sqlite-wal").writeText("wal")
        File(container, "app.sqlite-shm").writeText("shm")
        val nested = File(container, "nested").apply { mkdirs() }
        File(nested, "cache.db").writeText("db")
        File(container, "not-a-db.txt").writeText("ignore")

        val service = DesktopIosAppDatabaseService(containerRunner(container), sqliteLocator = { fakeSqlite })

        val databases = service.listDatabases("udid", bundleId).getOrThrow()

        assertEquals(2, databases.size)
        val app = databases.first { it.path == "app.sqlite" }
        assertTrue(app.hasWal)
        assertTrue(app.hasShm)
        val nestedDb = databases.first { it.path == "nested/cache.db" }
        assertFalse(nestedDb.hasWal)
        assertFalse(nestedDb.hasShm)
    }

    @Test
    fun listDatabasesFailsWhenContainerCannotBeResolved() = runBlocking {
        val runner = CommandRunner { _, _ -> CommandResult.failure("not installed") }
        val service = DesktopIosAppDatabaseService(runner, sqliteLocator = { fakeSqlite })

        val result = service.listDatabases("udid", bundleId)

        assertTrue(result.isFailure)
    }

    @Test
    fun listTablesParsesCsvOutputFromSqlite() = runBlocking {
        val container = newTempDir()
        File(container, "app.sqlite").writeText("db")
        val service = DesktopIosAppDatabaseService(
            containerRunner(container) { CommandResult.success("name\r\nusers\r\nposts\r\n") },
            sqliteLocator = { fakeSqlite },
        )

        val tables = service.listTables("udid", bundleId, "app.sqlite").getOrThrow()

        assertEquals(listOf("posts", "users"), tables)
    }

    @Test
    fun listTablesFailsWhenDatabaseFileMissing() = runBlocking {
        val container = newTempDir()
        val service = DesktopIosAppDatabaseService(containerRunner(container), sqliteLocator = { fakeSqlite })

        val result = service.listTables("udid", bundleId, "missing.sqlite")

        assertTrue(result.isFailure)
    }

    @Test
    fun tableInfoParsesPragmaTableInfoOutput() = runBlocking {
        val container = newTempDir()
        File(container, "app.sqlite").writeText("db")
        var callCount = 0
        val service = DesktopIosAppDatabaseService(
            containerRunner(container) {
                callCount++
                if (callCount == 1) {
                    CommandResult.success(
                        "cid,name,type,notnull,dflt_value,pk\r\n0,id,INTEGER,0,,1\r\n1,name,TEXT,0,,0\r\n",
                    )
                } else {
                    CommandResult.success("")
                }
            },
            sqliteLocator = { fakeSqlite },
        )

        val info = service.tableInfo("udid", bundleId, "app.sqlite", "users").getOrThrow()

        assertEquals("users", info.name)
        assertEquals(2, info.columns.size)
        assertTrue(info.columns.first { it.name == "id" }.primaryKey)
        assertFalse(info.columns.first { it.name == "name" }.primaryKey)
        assertTrue(info.hasRowId)
    }

    @Test
    fun queryRunsSelectWithLimitAppended() = runBlocking {
        val container = newTempDir()
        File(container, "app.sqlite").writeText("db")
        val commands = mutableListOf<List<String>>()
        val service = DesktopIosAppDatabaseService(
            containerRunner(container) { command ->
                commands += command
                CommandResult.success("id\r\n1\r\n")
            },
            sqliteLocator = { fakeSqlite },
        )

        val result = service.query("udid", bundleId, "app.sqlite", "select * from users", limit = 50).getOrThrow()

        assertEquals(listOf("id"), result.columns)
        assertEquals(listOf(listOf("1")), result.rows)
        assertTrue(commands.single().last().contains("LIMIT 50"))
    }

    @Test
    fun queryRunsWriteStatementAndReturnsRowsAffected() = runBlocking {
        val container = newTempDir()
        File(container, "app.sqlite").writeText("db")
        var callCount = 0
        val service = DesktopIosAppDatabaseService(
            containerRunner(container) {
                callCount++
                when (callCount) {
                    1 -> CommandResult.success()
                    2 -> CommandResult.success()
                    else -> CommandResult.success("2")
                }
            },
            sqliteLocator = { fakeSqlite },
        )

        val result = service.query("udid", bundleId, "app.sqlite", "update users set name='x'", limit = 50).getOrThrow()

        assertEquals(2, result.rowsAffected)
        assertEquals("OK", result.message)
    }

    @Test
    fun pullToHostCopiesResolvedDatabaseFile() = runBlocking {
        val container = newTempDir()
        File(container, "app.sqlite").writeText("payload")
        val dest = File(container, "exported/app.sqlite")
        val service = DesktopIosAppDatabaseService(containerRunner(container), sqliteLocator = { fakeSqlite })

        val result = service.pullToHost("udid", bundleId, "app.sqlite", dest.absolutePath)

        assertTrue(result.isSuccess)
        assertEquals("payload", dest.readText())
    }

    @Test
    fun saveListAndDeleteQueriesRoundTrip() = runBlocking {
        val queriesDir = newTempDir()
        val service = DesktopIosAppDatabaseService(
            CommandRunner { _, _ -> CommandResult.failure("unused") },
            queriesDir = queriesDir,
            sqliteLocator = { fakeSqlite },
        )

        val saveResult = service.saveQuery(bundleId, "My Query", "select 1")
        assertTrue(saveResult.isSuccess)
        val id = saveResult.stdout

        val saved = service.listSavedQueries(bundleId)
        assertEquals(1, saved.size)
        assertEquals("My Query", saved.single().name)
        assertEquals("select 1", saved.single().sql)

        val deleted = service.deleteQuery(bundleId, id)
        assertTrue(deleted)
        assertTrue(service.listSavedQueries(bundleId).isEmpty())
    }
}
