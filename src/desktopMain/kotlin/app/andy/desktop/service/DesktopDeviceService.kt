package app.andy.desktop.service

import app.andy.desktop.parser.AndroidParsers
import app.andy.model.AndroidDevice
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.SdkDiscovery
import app.andy.service.CommandResult
import app.andy.service.DeviceService
import app.andy.service.WorkspaceStore

class DesktopDeviceService(
    private val runner: CommandRunner,
    private val locator: SdkLocator,
    private val store: WorkspaceStore,
) : DeviceService {
    override suspend fun discoverSdk(): SdkDiscovery {
        return locator.discover(store.load().selectedSdkPath)
    }

    override suspend fun listDevices(): List<AndroidDevice> {
        val sdk = discoverSdk()
        val adb = sdk.adbPath ?: return emptyList()
        val result = runner.run(listOf(adb, "devices", "-l"))
        if (!result.isSuccess) return emptyList()
        return AndroidParsers.parseAdbDevices(result.stdout)
            .filterNot { it.kind == DeviceKind.Emulator && it.state == DeviceConnectionState.Offline }
            .map { base ->
                if (base.state != DeviceConnectionState.Online) return@map base
                val props = getProps(adb, base.serial)
                val avdName = props["ro.boot.qemu.avd_name"] ?: props["ro.kernel.qemu.avd_name"]
                base.copy(
                    displayName = if (base.kind == DeviceKind.Emulator) avdName ?: props["ro.product.model"] ?: base.displayName else props["ro.product.model"] ?: base.displayName,
                    apiLevel = props["ro.build.version.sdk"],
                    abi = props["ro.product.cpu.abi"],
                    model = props["ro.product.model"] ?: base.model,
                    product = props["ro.product.name"] ?: base.product,
                    batteryPercent = AndroidParsers.parseBatteryPercent(runner.run(listOf(adb, "-s", base.serial, "shell", "dumpsys", "battery"), 6).stdout),
                    screenSize = AndroidParsers.parseWmSize(runner.run(listOf(adb, "-s", base.serial, "shell", "wm", "size"), 6).stdout),
                    storageSummary = AndroidParsers.parseStorage(runner.run(listOf(adb, "-s", base.serial, "shell", "df", "-h", "/data"), 6).stdout),
                )
            }
    }

    override suspend fun shell(serial: String, command: List<String>): CommandResult {
        val adb = discoverSdk().adbPath ?: return CommandResult.failure("ADB not found")
        return runner.run(listOf(adb, "-s", serial, "shell") + command)
    }

    suspend fun adbPath(): String? = discoverSdk().adbPath

    private suspend fun getProps(adb: String, serial: String): Map<String, String> {
        val result = runner.run(listOf(adb, "-s", serial, "shell", "getprop"), 8)
        return result.stdout.lineSequence().mapNotNull { line ->
            val match = Regex("""\[(.+)]\:\s+\[(.*)]""").find(line) ?: return@mapNotNull null
            match.groupValues[1] to match.groupValues[2]
        }.toMap()
    }
}
