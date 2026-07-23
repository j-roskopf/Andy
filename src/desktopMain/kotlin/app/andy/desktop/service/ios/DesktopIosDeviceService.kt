package app.andy.desktop.service.ios

import app.andy.desktop.parser.IosParsers
import app.andy.desktop.service.CommandRunner
import app.andy.model.IosTarget
import app.andy.model.IosTargetState
import app.andy.service.CommandResult
import app.andy.service.IosDeviceService
import java.io.File

class DesktopIosDeviceService(
    private val runner: CommandRunner,
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
        return if (result.isSuccess) CommandResult.success("Booted $udid") else result
    }

    override suspend fun shutdown(udid: String): CommandResult {
        val result = runner.run(listOf("xcrun", "simctl", "shutdown", udid))
        return if (result.isSuccess) CommandResult.success("Shutdown $udid") else result
    }

    override suspend fun openInSimulatorApp(udid: String): CommandResult {
        val result = runner.run(listOf("open", "-a", "Simulator", "--args", "-CurrentDeviceUDID", udid))
        return if (result.isSuccess) CommandResult.success("Opened Simulator for $udid") else result
    }

    override suspend fun iosSimAvailable(): Boolean = NativeIosSimJni.isAvailable()

    override suspend fun iosSimDiagnostic(): String = NativeIosSimJni.diagnostic()
}

internal fun refreshedIosTargets(service: IosDeviceService): List<IosTarget> {
    return runCatching { kotlinx.coroutines.runBlocking { service.listTargets() } }.getOrDefault(emptyList())
}

internal fun IosTarget.withBootedState(): IosTarget =
    if (state == IosTargetState.Shutdown) copy(state = IosTargetState.Booted) else this
