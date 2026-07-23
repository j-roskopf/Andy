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
    fun prepareEmbeddedMirrorLaunchesSimulatorWithoutDeviceResetArgs() = runBlocking {
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
        assertEquals(listOf("open", "-g", "-a", "Simulator"), openCommand)
        assertFalse(openCommand.contains("-CurrentDeviceUDID"))
        assertFalse(openCommand.contains("--args"))
    }

    @Test
    fun prepareEmbeddedMirrorSkipsOpenWhenSimulatorAlreadyRunning() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val runner = CommandRunner { command, _ ->
            commands += command
            CommandResult.success()
        }
        val service = DesktopIosDeviceService(runner, simulatorAppRunning = { true })
        val result = service.prepareEmbeddedMirror("already-running")
        assertTrue(result.isSuccess)
        assertTrue(commands.none { it.firstOrNull() == "open" })
    }
}
