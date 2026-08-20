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
    private val udid = "AAAAAAAAAAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"

    private fun newTempDir(): File =
        File.createTempFile("andy-ios-file-test", "").also {
            it.delete()
            it.mkdirs()
            tempDirs += it
        }

    /** Paths under this root are treated as the selected simulator's device tree. */
    private fun simulatorHarness(): Pair<File, File> {
        val devicesRoot = newTempDir()
        val deviceDir = File(devicesRoot, udid).also { it.mkdirs() }
        return devicesRoot to deviceDir
    }

    private fun service(
        runner: CommandRunner = CommandRunner { _, _ -> CommandResult.success() },
        devicesRoot: File,
    ) = DesktopIosFileService(runner, simulatorDevicesRoot = devicesRoot)

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
        val (devicesRoot, deviceDir) = simulatorHarness()
        File(deviceDir, "b-file.txt").writeText("hello")
        File(deviceDir, "a-dir").mkdirs()

        val entries = service(devicesRoot = devicesRoot).list(udid, deviceDir.absolutePath)

        assertEquals(2, entries.size)
        assertTrue(entries.first().isDirectory)
        assertEquals("a-dir", entries.first().name)
        assertEquals("b-file.txt", entries.last().name)
        assertEquals(5L, entries.last().sizeBytes)
    }

    @Test
    fun listReturnsEmptyForMissingDirectory() = runBlocking {
        val (devicesRoot, deviceDir) = simulatorHarness()
        val entries = service(devicesRoot = devicesRoot)
            .list(udid, File(deviceDir, "missing").absolutePath)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun listRejectsPathsOutsideTheSelectedSimulator() = runBlocking {
        val (devicesRoot, _) = simulatorHarness()
        val outside = newTempDir()
        val entries = service(devicesRoot = devicesRoot).list(udid, outside.absolutePath)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun pullCopiesFileFromHostToLocalPath() = runBlocking {
        val (devicesRoot, deviceDir) = simulatorHarness()
        val source = File(deviceDir, "source.txt").also { it.writeText("payload") }
        val target = File(newTempDir(), "dest/copy.txt")

        val result = service(devicesRoot = devicesRoot).pull(udid, source.absolutePath, target.absolutePath)

        assertTrue(result.isSuccess)
        assertEquals("payload", target.readText())
    }

    @Test
    fun pullFailsWhenSourceMissing() = runBlocking {
        val (devicesRoot, deviceDir) = simulatorHarness()
        val result = service(devicesRoot = devicesRoot).pull(
            udid,
            File(deviceDir, "missing.txt").absolutePath,
            File(newTempDir(), "dest.txt").absolutePath,
        )
        assertFalse(result.isSuccess)
    }

    @Test
    fun pullRejectsPathsOutsideTheSelectedSimulator() = runBlocking {
        val (devicesRoot, _) = simulatorHarness()
        val outside = File(newTempDir(), "secret.txt").also { it.writeText("nope") }
        val result = service(devicesRoot = devicesRoot).pull(
            udid,
            outside.absolutePath,
            File(newTempDir(), "dest.txt").absolutePath,
        )
        assertFalse(result.isSuccess)
        assertTrue(result.stderr.contains("outside", ignoreCase = true) || result.stdout.contains("outside", ignoreCase = true))
    }

    @Test
    fun pushCopiesFileFromLocalToHostPath() = runBlocking {
        val (devicesRoot, deviceDir) = simulatorHarness()
        val source = File(newTempDir(), "local.txt").also { it.writeText("uploaded") }
        val target = File(deviceDir, "remote/copy.txt")

        val result = service(devicesRoot = devicesRoot).push(udid, source.absolutePath, target.absolutePath)

        assertTrue(result.isSuccess)
        assertEquals("uploaded", target.readText())
    }

    @Test
    fun deleteRemovesFileAndFailsWhenMissing() = runBlocking {
        val (devicesRoot, deviceDir) = simulatorHarness()
        val file = File(deviceDir, "to-delete.txt").also { it.writeText("x") }
        val svc = service(devicesRoot = devicesRoot)

        val deleted = svc.delete(udid, file.absolutePath)
        assertTrue(deleted.isSuccess)
        assertFalse(file.exists())

        val missing = svc.delete(udid, file.absolutePath)
        assertFalse(missing.isSuccess)
    }

    @Test
    fun deleteRejectsPathsOutsideTheSelectedSimulator() = runBlocking {
        val (devicesRoot, _) = simulatorHarness()
        val outside = File(newTempDir(), "victim.txt").also { it.writeText("keep") }
        val result = service(devicesRoot = devicesRoot).delete(udid, outside.absolutePath)
        assertFalse(result.isSuccess)
        assertTrue(outside.exists())
    }
}
