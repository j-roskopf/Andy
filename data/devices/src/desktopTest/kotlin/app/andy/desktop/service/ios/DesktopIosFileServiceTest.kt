package app.andy.desktop.service.ios

import app.andy.desktop.service.CommandRunner
import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.IosTargetState
import app.andy.service.CommandResult
import app.andy.service.IosTargetRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private const val PhysicalUdid = "00008140-00026112260B001C"

class DesktopIosFileServiceTest {
    private val tempDirs = mutableListOf<File>()
    private val udid = "AAAAAAAAAAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"

    @Test
    fun hostPathFromSimctlStripsFileUrl() {
        assertEquals(
            "/Users/joer/Library/Developer/CoreSimulator/Devices/UDID/data",
            hostPathFromSimctl("file:///Users/joer/Library/Developer/CoreSimulator/Devices/UDID/data"),
        )
        assertEquals(
            "/plain/path",
            hostPathFromSimctl("/plain/path"),
        )
    }

    private fun registerPhysicalDevice() {
        IosTargetRegistry.update(
            listOf(
                IosTarget(
                    udid = PhysicalUdid,
                    displayName = "iPhone 16 Pro",
                    kind = IosTargetKind.Physical,
                    state = IosTargetState.Unknown,
                ),
            ),
        )
    }

    /** Mimics devicectl: writes the JSON payload to the `--json-output` path, not stdout. */
    private fun devicectlRunner(
        commands: MutableList<List<String>>,
        payloads: (List<String>) -> Pair<CommandResult, String?>,
    ) = CommandRunner { command, _ ->
        commands += command
        val (result, json) = payloads(command)
        val outputIndex = command.indexOf("--json-output")
        if (json != null && outputIndex >= 0) File(command[outputIndex + 1]).writeText(json)
        result
    }

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
        IosTargetRegistry.update(emptyList())
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

    @Test
    fun physicalRootListsInstalledAppsAsSyntheticDirectories() = runBlocking {
        registerPhysicalDevice()
        val commands = mutableListOf<List<String>>()
        val runner = devicectlRunner(commands) {
            CommandResult.success() to """
                {"result":{"apps":[
                  {"bundleIdentifier":"com.example.myapp","name":"My App"},
                  {"bundleIdentifier":"com.apple.Maps","name":"Maps","defaultApp":true}
                ]}}
            """.trimIndent()
        }

        val entries = DesktopIosFileService(runner).list(PhysicalUdid, "/")

        assertEquals(listOf("Maps", "My App"), entries.map { it.name })
        assertEquals(listOf("/com.apple.Maps", "/com.example.myapp"), entries.map { it.path })
        assertTrue(entries.all { it.isDirectory })
        assertTrue(commands.single().contains("--include-all-apps"))
    }

    @Test
    fun physicalSubdirectoryListingScopesToTheAppDataContainer() = runBlocking {
        registerPhysicalDevice()
        val commands = mutableListOf<List<String>>()
        val runner = devicectlRunner(commands) {
            CommandResult.success() to """
                {"result":{"files":[
                  {"name":"cache.db","path":"cache.db","type":"regularFile","size":64},
                  {"name":"images","path":"images","type":"directory"}
                ]}}
            """.trimIndent()
        }

        val entries = DesktopIosFileService(runner).list(PhysicalUdid, "/com.example.myapp/Library/Caches")

        assertEquals(listOf("images", "cache.db"), entries.map { it.name })
        assertEquals(
            listOf("/com.example.myapp/Library/Caches/images", "/com.example.myapp/Library/Caches/cache.db"),
            entries.map { it.path },
        )
        val command = commands.single()
        assertEquals(
            listOf(
                "xcrun", "devicectl", "device", "info", "files", "--device", PhysicalUdid,
                "--domain-type", "appDataContainer", "--domain-identifier", "com.example.myapp",
                "--subdirectory", "Library/Caches", "--no-recurse",
            ),
            command.dropLast(2),
        )
    }

    @Test
    fun physicalPullAndPushUseDeviceCopyWithContainerRelativePaths() = runBlocking {
        registerPhysicalDevice()
        val commands = mutableListOf<List<String>>()
        val runner = devicectlRunner(commands) { CommandResult.success() to null }
        val service = DesktopIosFileService(runner)

        val pulled = service.pull(PhysicalUdid, "/com.example.myapp/Documents/notes.txt", "/tmp/notes.txt")
        val pushed = service.push(PhysicalUdid, "/tmp/notes.txt", "/com.example.myapp/Documents/notes.txt")

        assertTrue(pulled.isSuccess, pulled.stderr)
        assertTrue(pushed.isSuccess, pushed.stderr)
        assertEquals(
            listOf(
                listOf(
                    "xcrun", "devicectl", "device", "copy", "from", "--device", PhysicalUdid,
                    "--domain-type", "appDataContainer", "--domain-identifier", "com.example.myapp",
                    "--source", "Documents/notes.txt", "--destination", "/tmp/notes.txt",
                ),
                listOf(
                    "xcrun", "devicectl", "device", "copy", "to", "--device", PhysicalUdid,
                    "--domain-type", "appDataContainer", "--domain-identifier", "com.example.myapp",
                    "--source", "/tmp/notes.txt", "--destination", "Documents/notes.txt",
                ),
            ),
            commands.map { it.dropLast(2) },
        )
    }

    @Test
    fun physicalDeleteIsRejectedBeforeTouchingTheDevice() = runBlocking {
        registerPhysicalDevice()
        val commands = mutableListOf<List<String>>()
        val runner = devicectlRunner(commands) { CommandResult.success() to null }

        val result = DesktopIosFileService(runner).delete(PhysicalUdid, "/com.example.myapp/Documents/notes.txt")

        assertFalse(result.isSuccess)
        assertTrue(result.stderr.contains("not supported"), result.stderr)
        assertTrue(commands.isEmpty())
    }
}
