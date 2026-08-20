package app.andy.desktop.service.ios

import app.andy.desktop.service.CommandRunner
import app.andy.service.CommandResult
import java.io.File
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopIosFileServiceTest {
    private val tempDirs = mutableListOf<File>()

    private fun newTempDir(): File =
        File.createTempFile("andy-ios-file-test", "").also {
            it.delete()
            it.mkdirs()
            tempDirs += it
        }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    @Test
    fun parseAppContainersJsonExtractsDataAndGroupContainers() {
        val service = DesktopIosFileService(CommandRunner { _, _ -> CommandResult.success() })
        val output = """
            {
              "com.example.myapp": {
                "CFBundleDisplayName": "My App",
                "DataContainer": "/path/to/data",
                "GroupContainers": {
                  "group.com.example.shared": "/path/to/group"
                }
              }
            }
        """.trimIndent()

        val containers = service.parseAppContainersJson(output)

        assertEquals(1, containers.size)
        val container = containers.single()
        assertEquals("com.example.myapp", container.bundleId)
        assertEquals("My App", container.displayName)
        assertEquals("/path/to/data", container.dataContainerPath)
        assertEquals(mapOf("group.com.example.shared" to "/path/to/group"), container.groupContainers)
    }

    @Test
    fun parseAppContainersJsonFallsBackToBundleIdWhenNoDisplayName() {
        val service = DesktopIosFileService(CommandRunner { _, _ -> CommandResult.success() })
        val output = """{"com.example.nolabel": {}}"""

        val container = service.parseAppContainersJson(output).single()

        assertEquals("com.example.nolabel", container.displayName)
        assertNull(container.dataContainerPath)
        assertTrue(container.groupContainers.isEmpty())
    }

    @Test
    fun appDataContainerReturnsTrimmedPathOnSuccess() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val runner = CommandRunner { command, _ ->
            commands += command
            CommandResult.success("/containers/app-data\n")
        }
        val service = DesktopIosFileService(runner)

        val result = service.appDataContainer("udid", "com.example.myapp")

        assertEquals("/containers/app-data", result)
        assertEquals(
            listOf("xcrun", "simctl", "get_app_container", "udid", "com.example.myapp", "data"),
            commands.single(),
        )
    }

    @Test
    fun appDataContainerReturnsNullOnFailure() = runBlocking {
        val runner = CommandRunner { _, _ -> CommandResult.failure("not found") }
        val service = DesktopIosFileService(runner)
        assertNull(service.appDataContainer("udid", "com.example.myapp"))
    }

    @Test
    fun listReturnsDirectoryEntriesSortedDirectoriesFirst() = runBlocking {
        val dir = newTempDir()
        File(dir, "b-file.txt").writeText("hello")
        File(dir, "a-dir").mkdirs()

        val service = DesktopIosFileService(CommandRunner { _, _ -> CommandResult.success() })
        val entries = service.list("udid", dir.absolutePath)

        assertEquals(2, entries.size)
        assertTrue(entries.first().isDirectory)
        assertEquals("a-dir", entries.first().name)
        assertEquals("b-file.txt", entries.last().name)
        assertEquals(5L, entries.last().sizeBytes)
    }

    @Test
    fun listReturnsEmptyForMissingDirectory() = runBlocking {
        val service = DesktopIosFileService(CommandRunner { _, _ -> CommandResult.success() })
        val entries = service.list("udid", "/nonexistent/path/for/andy-tests")
        assertTrue(entries.isEmpty())
    }

    @Test
    fun pullCopiesFileFromHostToLocalPath() = runBlocking {
        val dir = newTempDir()
        val source = File(dir, "source.txt").also { it.writeText("payload") }
        val target = File(dir, "dest/copy.txt")

        val service = DesktopIosFileService(CommandRunner { _, _ -> CommandResult.success() })
        val result = service.pull("udid", source.absolutePath, target.absolutePath)

        assertTrue(result.isSuccess)
        assertEquals("payload", target.readText())
    }

    @Test
    fun pullFailsWhenSourceMissing() = runBlocking {
        val dir = newTempDir()
        val service = DesktopIosFileService(CommandRunner { _, _ -> CommandResult.success() })
        val result = service.pull("udid", File(dir, "missing.txt").absolutePath, File(dir, "dest.txt").absolutePath)
        assertFalse(result.isSuccess)
    }

    @Test
    fun pushCopiesFileFromLocalToHostPath() = runBlocking {
        val dir = newTempDir()
        val source = File(dir, "local.txt").also { it.writeText("uploaded") }
        val target = File(dir, "remote/copy.txt")

        val service = DesktopIosFileService(CommandRunner { _, _ -> CommandResult.success() })
        val result = service.push("udid", source.absolutePath, target.absolutePath)

        assertTrue(result.isSuccess)
        assertEquals("uploaded", target.readText())
    }

    @Test
    fun deleteRemovesFileAndFailsWhenMissing() = runBlocking {
        val dir = newTempDir()
        val file = File(dir, "to-delete.txt").also { it.writeText("x") }
        val service = DesktopIosFileService(CommandRunner { _, _ -> CommandResult.success() })

        val deleted = service.delete("udid", file.absolutePath)
        assertTrue(deleted.isSuccess)
        assertFalse(file.exists())

        val missing = service.delete("udid", file.absolutePath)
        assertFalse(missing.isSuccess)
    }
}
