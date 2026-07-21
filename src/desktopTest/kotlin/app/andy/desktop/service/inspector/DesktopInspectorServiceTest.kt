package app.andy.desktop.service.inspector

import app.andy.desktop.service.MockAndroidDeviceEnvironment
import app.andy.model.PrefEntry
import app.andy.model.PrefType
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopSharedPrefsServiceTest {
    @Test
    fun listsReadsAndUpsertsDebuggablePrefs() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val service = DesktopSharedPrefsService(env.runner, env.devices)

        val files = service.listFiles("emulator-5554", "com.example.app").getOrThrow()
        assertEquals(listOf("demo.xml"), files)

        val entries = service.read("emulator-5554", "com.example.app", "demo.xml").getOrThrow()
        assertEquals("hello", entries.first { it.key == "greeting" }.value)

        val upsert = service.upsert(
            "emulator-5554",
            "com.example.app",
            "demo.xml",
            PrefEntry("greeting", PrefType.String, "hola"),
        )
        assertTrue(upsert.isSuccess, upsert.stderr)
        val updated = service.read("emulator-5554", "com.example.app", "demo.xml").getOrThrow()
        assertEquals("hola", updated.first { it.key == "greeting" }.value)
    }

    @Test
    fun rejectsUndebuggablePackage() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val service = DesktopSharedPrefsService(env.runner, env.devices)
        val result = service.listFiles("emulator-5554", "com.undebuggable.app")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("debuggable", ignoreCase = true))
    }
}

class DesktopAppDatabaseServiceTest {
    @Test
    fun listsDatabasesAndPersistsQueriesPerPackage() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val root = createTempDirectory("andy-db-queries").toFile()
        val service = DesktopAppDatabaseService(env.runner, env.devices, queriesDir = root)

        val dbs = service.listDatabases("emulator-5554", "com.example.app").getOrThrow()
        assertEquals(1, dbs.size)
        assertEquals("app.db", dbs.first().name)
        assertEquals("databases/app.db", dbs.first().path)
        assertTrue(dbs.first().hasWal)

        val save = service.saveQuery("com.example.app", "Active users", "SELECT * FROM users")
        assertTrue(save.isSuccess)
        assertEquals(1, service.listSavedQueries("com.example.app").size)
        assertEquals(0, service.listSavedQueries("com.other.app").size)

        val id = service.listSavedQueries("com.example.app").first().id
        assertTrue(service.deleteQuery("com.example.app", id))
        assertEquals(0, service.listSavedQueries("com.example.app").size)
    }

    @Test
    fun listsNoBackupDatabasesWhenPresent() = runBlocking {
        val env = MockAndroidDeviceEnvironment().also {
            it.databaseListing = "app.db\napp.db-wal\n"
            it.noBackupListing = "work.db\nwork.db-wal\nwork.db-shm\n"
        }
        val service = DesktopAppDatabaseService(env.runner, env.devices)

        val dbs = service.listDatabases("emulator-5554", "com.example.app").getOrThrow()
        assertEquals(2, dbs.size)
        assertEquals("app.db", dbs[0].name)
        assertEquals("databases/app.db", dbs[0].path)
        assertEquals("no_backup/work.db", dbs[1].name)
        assertEquals("no_backup/work.db", dbs[1].path)
        assertTrue(dbs[1].hasWal)
        assertTrue(dbs[1].hasShm)
    }

    @Test
    fun listTablesDoesNotCrashWhenDeviceSqliteIsMissing() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val service = DesktopAppDatabaseService(env.runner, env.devices)

        // Devices without /system/bin/sqlite3 used to NPE when caching a null path in
        // ConcurrentHashMap, which surfaced in the UI as a blank "Failed to list tables".
        val result = service.listTables("emulator-5554", "com.example.app", "app.db")
        assertTrue(
            result.exceptionOrNull() !is NullPointerException,
            result.exceptionOrNull()?.stackTraceToString().orEmpty(),
        )
    }

    @Test
    fun walCheckpointIsSafeOnPopulatedDatabase() {
        // Host sqlite3 CLIs differ on whether a one-shot process leaves a -wal sibling
        // behind after exit. Production pullWorkingCopy only checkpoints when a WAL file
        // is present; this test just proves the checkpoint pragma is safe against a
        // populated DB on whatever sqlite3 CI provides.
        val sqlite = locateHostSqlite3() ?: return
        val dir = createTempDirectory("andy-wal").toFile()
        val db = File(dir, "hot.db")
        fun run(sql: String) {
            val process = ProcessBuilder(listOf(sqlite, db.absolutePath, sql)).start()
            val err = process.errorStream.bufferedReader().readText()
            process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), err)
        }
        fun query(sql: String): String {
            val process = ProcessBuilder(listOf(sqlite, db.absolutePath, sql)).start()
            val out = process.inputStream.bufferedReader().readText().trim()
            assertEquals(0, process.waitFor())
            return out
        }

        run("PRAGMA journal_mode=WAL;")
        run("CREATE TABLE items(id INTEGER PRIMARY KEY, name TEXT);")
        run("INSERT INTO items(name) VALUES ('alpha'), ('beta');")
        run("PRAGMA wal_checkpoint(TRUNCATE);")
        File(dir, "hot.db-wal").delete()
        File(dir, "hot.db-shm").delete()
        assertEquals("2", query("SELECT COUNT(*) FROM items;"))
    }

    @Test
    fun sqliteNullMarkerDistinguishesNullFromEmptyString() {
        val sqlite = locateHostSqlite3() ?: return
        val dir = createTempDirectory("andy-null-csv").toFile()
        val db = File(dir, "nulls.db")
        fun exec(vararg args: String): String {
            val process = ProcessBuilder(listOf(sqlite, *args)).start()
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), err)
            return out
        }
        exec(db.absolutePath, "CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT); INSERT INTO t(v) VALUES (NULL), ('');")
        val csv = exec(
            "-header",
            "-csv",
            "-nullvalue",
            SQLITE_NULL_MARKER,
            db.absolutePath,
            "SELECT v FROM t ORDER BY id;",
        )
        val lines = csv.lineSequence().filter { it.isNotEmpty() }.toList()
        // sqlite3 -csv quotes empty strings as "", while -nullvalue emits the marker raw.
        assertEquals(listOf("v", SQLITE_NULL_MARKER, "\"\""), lines)
    }
}
