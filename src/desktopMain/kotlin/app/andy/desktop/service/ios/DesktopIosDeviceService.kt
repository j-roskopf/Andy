package app.andy.desktop.service.ios

import app.andy.desktop.parser.IosParsers
import app.andy.desktop.service.CommandRunner
import app.andy.model.IosTarget
import app.andy.model.IosTargetState
import app.andy.service.CommandResult
import app.andy.service.IosDeviceService
import java.io.File
import kotlinx.coroutines.delay

class DesktopIosDeviceService(
    private val runner: CommandRunner,
    private val simulatorAppRunning: () -> Boolean = Companion::isSimulatorAppRunning,
) : IosDeviceService {
    override suspend fun listTargets(): List<IosTarget> {
        val sims = runCatching {
            val result = runner.run(listOf("xcrun", "simctl", "list", "devices", "-j"))
            if (!result.isSuccess) emptyList() else IosParsers.parseSimctlDevices(result.stdout)
        }.getOrDefault(emptyList())
        val physical = runCatching {
            val temp = File.createTempFile("andy-devicectl", ".json")
            val result = runner.run(listOf("xcrun", "devicectl", "list", "devices", "--json-output", temp.absolutePath))
            val output = if (result.isSuccess) temp.readText() else ""
            temp.delete()
            if (output.isBlank()) emptyList() else IosParsers.parseDevicectlDevices(output)
        }.getOrDefault(emptyList())
        return (sims + physical).distinctBy { it.udid }
    }

    override suspend fun boot(udid: String): CommandResult {
        val result = runner.run(listOf("xcrun", "simctl", "boot", udid), timeoutSeconds = 120)
        if (!result.isSuccess) return result
        // simctl boots the runtime headlessly. SimulatorKit screen capture can attach without
        // Simulator.app, but LegacyHIDClient / Indigo injection needs that process. Launch it
        // in the background so Devices → Live touch works without an extra click.
        ensureSimulatorAppRunning()
        return CommandResult.success("Booted $udid")
    }

    override suspend fun shutdown(udid: String): CommandResult {
        val result = runner.run(listOf("xcrun", "simctl", "shutdown", udid))
        return if (result.isSuccess) CommandResult.success("Shutdown $udid") else result
    }

    override suspend fun openInSimulatorApp(udid: String): CommandResult {
        val result = runner.run(listOf("open", "-a", "Simulator", "--args", "-CurrentDeviceUDID", udid))
        return if (result.isSuccess) CommandResult.success("Opened Simulator for $udid") else result
    }

    override suspend fun prepareEmbeddedMirror(udid: String): CommandResult {
        // Launch Simulator.app *before* SimulatorKit IO attaches. Opening it mid-session races the
        // display pipeline and leaves Live black; HID still needs the process for Indigo.
        val alreadyRunning = simulatorAppRunning()
        val launch = ensureSimulatorAppRunning()
        if (!launch.isSuccess) return launch
        val deadline = System.nanoTime() + SIMULATOR_APP_WAIT_NANOS
        while (!simulatorAppRunning() && System.nanoTime() < deadline) {
            delay(100)
        }
        if (!simulatorAppRunning()) {
            return CommandResult.failure("Simulator.app did not start")
        }
        if (!alreadyRunning) {
            // Brief settle so CoreSimulator finishes wiring before we open SimDeviceIO.
            delay(SIMULATOR_APP_SETTLE_MILLIS)
        }
        return CommandResult.success("Simulator.app ready")
    }

    /**
     * Starts Simulator.app without activating it (`open -g`) so embedded Live can inject HID
     * after a headless [boot]. Safe to call when the app is already running.
     */
    private suspend fun ensureSimulatorAppRunning(): CommandResult {
        if (simulatorAppRunning()) {
            return CommandResult.success("Simulator.app already running")
        }
        // Avoid `--args -CurrentDeviceUDID`: launching with it can reset the booted device's
        // display just as Live tries to attach capture. Boot already selected the runtime via simctl.
        val result = runner.run(listOf("open", "-g", "-a", "Simulator"))
        return if (result.isSuccess) CommandResult.success("Simulator.app launching") else result
    }

    companion object {
        private const val SIMULATOR_APP_WAIT_NANOS = 15_000_000_000L
        private const val SIMULATOR_APP_SETTLE_MILLIS = 400L

        internal fun isSimulatorAppRunning(): Boolean =
            runCatching {
                ProcessBuilder("pgrep", "-x", "Simulator")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor() == 0
            }.getOrDefault(false)
    }

    override suspend fun iosSimAvailable(): Boolean = NativeIosSimJni.isAvailable()

    override suspend fun iosSimDiagnostic(): String = NativeIosSimJni.diagnostic()
}

internal fun refreshedIosTargets(service: IosDeviceService): List<IosTarget> {
    return runCatching { kotlinx.coroutines.runBlocking { service.listTargets() } }.getOrDefault(emptyList())
}

internal fun IosTarget.withBootedState(): IosTarget =
    if (state == IosTargetState.Shutdown) copy(state = IosTargetState.Booted) else this
