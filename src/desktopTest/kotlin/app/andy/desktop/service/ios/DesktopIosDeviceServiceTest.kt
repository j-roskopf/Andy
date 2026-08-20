package app.andy.desktop.service.ios

import app.andy.desktop.service.CommandRunner
import app.andy.service.CommandResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopIosDeviceServiceTest {
    @Test
    fun bootStartsSimulatorHeadlessly() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val runner = CommandRunner { command, _ ->
            commands += command
            CommandResult.success()
        }
        val service = DesktopIosDeviceService(runner, simulatorAppRunning = { false })
        val udid = "CA4B2892-6294-4CD4-AA5A-6031551226BA"

        val result = service.boot(udid)

        assertTrue(result.isSuccess)
        assertEquals(listOf(listOf("xcrun", "simctl", "boot", udid)), commands)
    }

    @Test
    fun prepareEmbeddedMirrorLaunchesSimulatorHiddenWithoutDeviceResetArgs() = runBlocking {
        val commands = mutableListOf<List<String>>()
        var running = false
        val runner = CommandRunner { command, _ ->
            commands += command
            if (command.firstOrNull() == "open") running = true
            CommandResult.success()
        }
        val service = DesktopIosDeviceService(runner, simulatorAppRunning = { running })
        val result = service.prepareEmbeddedMirror("CA4B2892-6294-4CD4-AA5A-6031551226BA")
        assertTrue(result.isSuccess, result.stderr.ifBlank { result.stdout })
        val openCommand = commands.single { it.firstOrNull() == "open" }
        assertEquals(listOf("open", "-g", "-j", "-a", "Simulator"), openCommand)
        assertFalse(openCommand.contains("-CurrentDeviceUDID"))
        assertFalse(openCommand.contains("--args"))
    }

    @Test
    fun prepareEmbeddedMirrorSkipsOpenWhenSimulatorAlreadyRunning() = runBlocking {
        val commands = mutableListOf<List<String>>()
        var hideCalls = 0
        val runner = CommandRunner { command, _ ->
            commands += command
            CommandResult.success()
        }
        val service = DesktopIosDeviceService(
            runner = runner,
            simulatorAppRunning = { true },
            hideSimulator = { hideCalls++ },
        )
        val result = service.prepareEmbeddedMirror("already-running")
        assertTrue(result.isSuccess)
        assertTrue(commands.none { it.firstOrNull() == "open" })
        assertEquals(1, hideCalls)
    }

    @Test
    fun hasVisibleSimulatorDeviceWindowUsesInjectedChecker() {
        val service = DesktopIosDeviceService(
            runner = CommandRunner { _, _ -> CommandResult.success() },
            visibleSimulatorDeviceWindow = { name -> name == "iPhone 17 Pro" },
        )
        assertTrue(service.hasVisibleSimulatorDeviceWindow("iPhone 17 Pro"))
        assertFalse(service.hasVisibleSimulatorDeviceWindow("iPad Pro"))
        assertFalse(service.hasVisibleSimulatorDeviceWindow(null))
    }

    @Test
    fun hideSimulatorAppUsesInjectedAction() {
        var hideCalls = 0
        val service = DesktopIosDeviceService(
            runner = CommandRunner { _, _ -> CommandResult.success() },
            hideSimulator = { hideCalls++ },
        )
        service.hideSimulatorApp()
        assertEquals(1, hideCalls)
    }

    @Test
    fun simctlRunsXcrunSimctlWithArgs() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val runner = CommandRunner { command, _ ->
            commands += command
            CommandResult.success("ok")
        }
        val service = DesktopIosDeviceService(runner)
        val result = service.simctl(listOf("ui", "UDID", "appearance", "dark"))
        assertTrue(result.isSuccess)
        assertEquals(listOf(listOf("xcrun", "simctl", "ui", "UDID", "appearance", "dark")), commands)
    }

    @Test
    fun listDeviceTypesParsesSimctlOutput() = runBlocking {
        val output = """
            {"devicetypes":[{"identifier":"com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro","name":"iPhone 17 Pro","productFamily":"iPhone"}]}
        """.trimIndent()
        val runner = CommandRunner { _, _ -> CommandResult.success(output) }
        val service = DesktopIosDeviceService(runner)
        val types = service.listDeviceTypes()
        assertEquals(1, types.size)
        assertEquals("iPhone 17 Pro", types.single().name)
    }

    @Test
    fun listRuntimesParsesSimctlOutput() = runBlocking {
        val output = """
            {"runtimes":[{"identifier":"com.apple.CoreSimulator.SimRuntime.iOS-26-5","name":"iOS 26.5","isAvailable":true,"version":"26.5"}]}
        """.trimIndent()
        val runner = CommandRunner { _, _ -> CommandResult.success(output) }
        val service = DesktopIosDeviceService(runner)
        val runtimes = service.listRuntimes()
        assertEquals(1, runtimes.size)
        assertEquals("iOS 26.5", runtimes.single().name)
    }

    @Test
    fun createSimulatorRunsSimctlCreateWithOptionalRuntime() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val runner = CommandRunner { command, _ ->
            commands += command
            CommandResult.success("NEW-UDID")
        }
        val service = DesktopIosDeviceService(runner)
        val result = service.createSimulator(
            "My iPhone",
            "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro",
            "com.apple.CoreSimulator.SimRuntime.iOS-26-5",
        )
        assertTrue(result.isSuccess)
        assertEquals("NEW-UDID", result.stdout)
        assertEquals(
            listOf(
                "xcrun", "simctl", "create", "My iPhone",
                "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro",
                "com.apple.CoreSimulator.SimRuntime.iOS-26-5",
            ),
            commands.single(),
        )
    }

    @Test
    fun createSimulatorOmitsRuntimeWhenNull() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val runner = CommandRunner { command, _ ->
            commands += command
            CommandResult.success("NEW-UDID")
        }
        val service = DesktopIosDeviceService(runner)
        service.createSimulator("My iPhone", "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro")
        assertEquals(
            listOf("xcrun", "simctl", "create", "My iPhone", "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro"),
            commands.single(),
        )
    }

    @Test
    fun cloneEraseRenameDeleteRunExpectedSimctlCommands() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val runner = CommandRunner { command, _ ->
            commands += command
            CommandResult.success()
        }
        val service = DesktopIosDeviceService(runner)
        val udid = "CA4B2892-6294-4CD4-AA5A-6031551226BA"

        service.cloneSimulator(udid, "Clone Name")
        service.eraseSimulator(udid)
        service.renameSimulator(udid, "New Name")
        service.deleteSimulator(udid)
        service.deleteUnavailableSimulators()
        service.deleteUnusedRuntimes(30)

        assertEquals(
            listOf(
                listOf("xcrun", "simctl", "clone", udid, "Clone Name"),
                listOf("xcrun", "simctl", "erase", udid),
                listOf("xcrun", "simctl", "rename", udid, "New Name"),
                listOf("xcrun", "simctl", "delete", udid),
                listOf("xcrun", "simctl", "delete", "unavailable"),
                listOf("xcrun", "simctl", "runtime", "delete", "--notUsedSinceDays", "30"),
            ),
            commands,
        )
    }

    @Test
    fun developerModeStatusParsesDevicectlJsonOutputFile() = runBlocking {
        val runner = CommandRunner { command, _ ->
            val outputIndex = command.indexOf("--json-output")
            if (outputIndex >= 0) {
                val path = command[outputIndex + 1]
                java.io.File(path).writeText(
                    """{"result":{"deviceProperties":{"developerModeStatus":"disabled","ddiServicesAvailable":true}}}""",
                )
            }
            CommandResult.success()
        }
        val service = DesktopIosDeviceService(runner)
        val status = service.developerModeStatus("00008140-00026112260B001C")
        assertFalse(status!!.enabled)
        assertTrue(status.ddiServicesAvailable)
    }

    @Test
    fun developerModeStatusReturnsNullWhenDevicectlFails() = runBlocking {
        val runner = CommandRunner { _, _ -> CommandResult.failure("device not found") }
        val service = DesktopIosDeviceService(runner)
        assertNull(service.developerModeStatus("unknown"))
    }
}
