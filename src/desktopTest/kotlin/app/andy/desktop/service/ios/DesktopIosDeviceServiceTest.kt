package app.andy.desktop.service.ios

import app.andy.desktop.service.CommandRunner
import app.andy.service.CommandResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
