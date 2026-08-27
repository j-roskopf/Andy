package app.andy.desktop.service.ios

import app.andy.desktop.service.CommandRunner
import app.andy.model.PrefEntry
import app.andy.model.PrefType
import app.andy.service.CommandResult
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopIosSharedPrefsServiceTest {
    private val tempDirs = mutableListOf<File>()
    private val bundleId = "com.example.myapp"

    private fun newTempDir(): File =
        File.createTempFile("andy-ios-prefs-test", "").also {
            it.delete()
            it.mkdirs()
            tempDirs += it
        }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun List<String>.hasAll(vararg parts: String) = parts.all { contains(it) }

    @Test
    fun listFilesReturnsPlistNameWhenPresent() = runBlocking {
        val container = newTempDir()
        val prefsDir = File(container, "Library/Preferences").apply { mkdirs() }
        File(prefsDir, "$bundleId.plist").writeText("<plist/>")
        val runner = CommandRunner { command, _ ->
            if (command.contains("get_app_container")) CommandResult.success(container.absolutePath)
            else CommandResult.failure("unexpected: $command")
        }
        val service = DesktopIosSharedPrefsService(runner)

        val files = service.listFiles("udid", bundleId).getOrThrow()

        assertEquals(listOf("$bundleId.plist"), files)
    }

    @Test
    fun listFilesReturnsEmptyWhenPlistMissing() = runBlocking {
        val container = newTempDir()
        val runner = CommandRunner { command, _ ->
            if (command.contains("get_app_container")) CommandResult.success(container.absolutePath)
            else CommandResult.failure("unexpected: $command")
        }
        val service = DesktopIosSharedPrefsService(runner)

        assertTrue(service.listFiles("udid", bundleId).getOrThrow().isEmpty())
    }

    @Test
    fun readParsesPlistJsonIntoSortedPrefEntries() = runBlocking {
        val container = newTempDir()
        val prefsDir = File(container, "Library/Preferences").apply { mkdirs() }
        File(prefsDir, "$bundleId.plist").writeText("<plist/>")
        val runner = CommandRunner { command, _ ->
            when {
                command.contains("get_app_container") -> CommandResult.success(container.absolutePath)
                command.hasAll("-convert", "json") ->
                    CommandResult.success("""{"zzzLast":"end","username":"joe","count":3,"enabled":true}""")
                else -> CommandResult.failure("unexpected: $command")
            }
        }
        val service = DesktopIosSharedPrefsService(runner)

        val entries = service.read("udid", bundleId, "$bundleId.plist").getOrThrow()

        assertEquals(listOf("count", "enabled", "username", "zzzLast"), entries.map { it.key })
        val count = entries.first { it.key == "count" }
        assertEquals(PrefType.Int, count.type)
        assertEquals("3", count.value)
        val enabled = entries.first { it.key == "enabled" }
        assertEquals(PrefType.Boolean, enabled.type)
        assertEquals("true", enabled.value)
        val username = entries.first { it.key == "username" }
        assertEquals(PrefType.String, username.type)
        assertEquals("joe", username.value)
    }

    @Test
    fun readRejectsNonPlistFileNames() = runBlocking {
        val container = newTempDir()
        val runner = CommandRunner { command, _ ->
            if (command.contains("get_app_container")) CommandResult.success(container.absolutePath)
            else CommandResult.failure("unexpected: $command")
        }
        val service = DesktopIosSharedPrefsService(runner)

        val result = service.read("udid", bundleId, "../evil.txt")

        assertTrue(result.isFailure)
    }

    @Test
    fun upsertWritesJsonThenConvertsToXmlPlist() = runBlocking {
        val container = newTempDir()
        var capturedJson: String? = null
        val runner = CommandRunner { command, _ ->
            when {
                command.contains("get_app_container") -> CommandResult.success(container.absolutePath)
                command.hasAll("-convert", "xml1") -> {
                    capturedJson = File(command.last()).readText()
                    CommandResult.success()
                }
                else -> CommandResult.failure("unexpected: $command")
            }
        }
        val service = DesktopIosSharedPrefsService(runner)

        val result = service.upsert("udid", bundleId, "$bundleId.plist", PrefEntry("greeting", PrefType.String, "hi"))

        assertTrue(result.isSuccess)
        assertTrue(capturedJson.orEmpty().contains("greeting"))
        assertTrue(capturedJson.orEmpty().contains("hi"))
    }

    @Test
    fun deleteRemovesKeyThenConvertsToXmlPlist() = runBlocking {
        val container = newTempDir()
        val prefsDir = File(container, "Library/Preferences").apply { mkdirs() }
        File(prefsDir, "$bundleId.plist").writeText("<plist/>")
        var capturedJson: String? = null
        val runner = CommandRunner { command, _ ->
            when {
                command.contains("get_app_container") -> CommandResult.success(container.absolutePath)
                command.hasAll("-convert", "json") -> CommandResult.success("""{"keep":"a","drop":"b"}""")
                command.hasAll("-convert", "xml1") -> {
                    capturedJson = File(command.last()).readText()
                    CommandResult.success()
                }
                else -> CommandResult.failure("unexpected: $command")
            }
        }
        val service = DesktopIosSharedPrefsService(runner)

        val result = service.delete("udid", bundleId, "$bundleId.plist", "drop")

        assertTrue(result.isSuccess)
        assertTrue(capturedJson.orEmpty().contains("keep"))
        assertFalse(capturedJson.orEmpty().contains("drop"))
    }

    @Test
    fun upsertFailsWhenContainerCannotBeResolved() = runBlocking {
        val runner = CommandRunner { _, _ -> CommandResult.failure("app not found") }
        val service = DesktopIosSharedPrefsService(runner)

        val result = service.upsert("udid", bundleId, "$bundleId.plist", PrefEntry("k", PrefType.String, "v"))

        assertFalse(result.isSuccess)
    }
}
