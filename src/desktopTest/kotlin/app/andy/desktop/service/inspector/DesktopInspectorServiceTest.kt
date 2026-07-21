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
    fun walCheckpointMergesRowsIntoMainDatabase() {
        val sqlite = locateHostSqlite3() ?: return
        val dir = createTempDirectory("andy-wal").toFile()
        val db = File(dir, "hot.db")
        fun run(sql: String): Int {
            val process = ProcessBuilder(listOf(sqlite, db.absolutePath, sql)).start()
            process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            val code = process.waitFor()
            assertEquals(0, code, err)
            return code
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
        assertTrue(File(dir, "hot.db-wal").exists())

        // Stale shm + live WAL is the failure mode that makes tables look empty.
        File(dir, "hot.db-shm").writeBytes(ByteArray(32))
        File(dir, "hot.db-shm").delete()
        run("PRAGMA wal_checkpoint(TRUNCATE);")
        assertEquals("2", query("SELECT COUNT(*) FROM items;"))
    }
}
