package app.andy.desktop.service.ios

import app.andy.desktop.service.CommandRunner
import app.andy.model.LogLevel
import app.andy.service.CommandResult
import app.andy.service.LogcatFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopIosLogcatServiceTest {
    private val ndjson = listOf(
        """{"timestamp":"t1","messageType":"Info","eventMessage":"App started","subsystem":"com.example.myapp","category":"lifecycle","processID":100,"threadID":1}""",
        """{"timestamp":"t2","messageType":"Error","eventMessage":"Boom","subsystem":"com.example.myapp","category":"network","processID":100,"threadID":2}""",
        "",
    ).joinToString("\n")

    @Test
    fun snapshotRunsLogShowWithLastWindowAndParsesEntries() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val runner = CommandRunner { command, _ ->
            commands += command
            CommandResult.success(ndjson)
        }
        val service = DesktopIosLogcatService(runner)

        val entries = service.snapshot("udid", LogcatFilter(), limit = 50)

        assertEquals(2, entries.size)
        assertEquals("App started", entries.first().message)
        assertEquals(
            listOf(
                "xcrun", "simctl", "spawn", "udid", "log", "show",
                "--style", "ndjson", "--level", "info", "--last", "5m",
            ),
            commands.single(),
        )
    }

    @Test
    fun snapshotFiltersByLevelAndSearch() = runBlocking {
        val runner = CommandRunner { _, _ -> CommandResult.success(ndjson) }
        val service = DesktopIosLogcatService(runner)

        val errorsOnly = service.snapshot("udid", LogcatFilter(levels = setOf(LogLevel.Error)), limit = 50)
        assertEquals(1, errorsOnly.size)
        assertEquals("Boom", errorsOnly.single().message)

        val searched = service.snapshot("udid", LogcatFilter(search = "started"), limit = 50)
        assertEquals(1, searched.size)
        assertEquals("App started", searched.single().message)
    }

    @Test
    fun snapshotTruncatesToLimit() = runBlocking {
        val runner = CommandRunner { _, _ -> CommandResult.success(ndjson) }
        val service = DesktopIosLogcatService(runner)

        val truncated = service.snapshot("udid", LogcatFilter(), limit = 1)

        assertEquals(1, truncated.size)
        assertEquals("Boom", truncated.single().message)
    }

    @Test
    fun snapshotReturnsEmptyWhenCommandFails() = runBlocking {
        val runner = CommandRunner { _, _ -> CommandResult.failure("no such device") }
        val service = DesktopIosLogcatService(runner)

        assertTrue(service.snapshot("udid", LogcatFilter(), limit = 50).isEmpty())
    }

    @Test
    fun clearIsNoOp() = runBlocking {
        val runner = CommandRunner { _, _ -> CommandResult.failure("should not be called") }
        val service = DesktopIosLogcatService(runner)
        service.clear("udid")
    }
}
