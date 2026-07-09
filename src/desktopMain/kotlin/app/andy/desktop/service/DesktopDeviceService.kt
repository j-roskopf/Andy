package app.andy.desktop.service

import app.andy.desktop.parser.AndroidParsers
import app.andy.model.AndroidDevice
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.MdnsService
import app.andy.model.SdkDiscovery
import app.andy.model.dedupeWifiDeviceAliases
import app.andy.service.CommandResult
import app.andy.service.DeviceService
import app.andy.service.WorkspaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

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
                    hardwareId = props["ro.serialno"]
                        ?: props["ro.boot.serialno"]
                        ?: base.hardwareId,
                    batteryPercent = AndroidParsers.parseBatteryPercent(runner.run(listOf(adb, "-s", base.serial, "shell", "dumpsys", "battery"), 6).stdout),
                    screenSize = AndroidParsers.parseWmSize(runner.run(listOf(adb, "-s", base.serial, "shell", "wm", "size"), 6).stdout),
                    storageSummary = AndroidParsers.parseStorage(runner.run(listOf(adb, "-s", base.serial, "shell", "df", "-h", "/data"), 6).stdout),
                )
            }
            .let(::dedupeWifiDeviceAliases)
    }

    override suspend fun shell(serial: String, command: List<String>): CommandResult {
        val adb = discoverSdk().adbPath ?: return CommandResult.failure("ADB not found")
        return runner.run(listOf(adb, "-s", serial, "shell") + command)
    }

    override suspend fun pair(host: String, port: Int, code: String): CommandResult {
        val adb = discoverSdk().adbPath ?: return CommandResult.failure("ADB not found")
        val result = runner.run(listOf(adb, "pair", "$host:$port", code), 30)
        val combined = "${result.stdout}\n${result.stderr}"
        return if (combined.contains("Successfully paired", ignoreCase = true)) {
            CommandResult.success(result.stdout.ifBlank { "Successfully paired to $host:$port" })
        } else {
            CommandResult.failure(
                message = result.stderr.ifBlank { result.stdout }.ifBlank { "Pairing failed for $host:$port" },
                exitCode = if (result.exitCode == 0) 1 else result.exitCode,
            )
        }
    }

    override suspend fun connect(host: String, port: Int): CommandResult {
        val adb = discoverSdk().adbPath ?: return CommandResult.failure("ADB not found")
        val result = runner.run(listOf(adb, "connect", "$host:$port"), 15)
        val combined = "${result.stdout}\n${result.stderr}".lowercase()
        val connected = combined.contains("connected to") &&
            !combined.contains("failed") &&
            !combined.contains("cannot")
        return if (connected) {
            CommandResult.success(result.stdout.ifBlank { "connected to $host:$port" })
        } else {
            CommandResult.failure(
                message = result.stderr.ifBlank { result.stdout }.ifBlank { "Failed to connect to $host:$port" },
                exitCode = if (result.exitCode == 0) 1 else result.exitCode,
            )
        }
    }

    override suspend fun disconnect(serial: String): CommandResult {
        val adb = discoverSdk().adbPath ?: return CommandResult.failure("ADB not found")
        return runner.run(listOf(adb, "disconnect", serial), 10)
    }

    override suspend fun listMdnsServices(): List<MdnsService> {
        val adb = discoverSdk().adbPath ?: return emptyList()
        val result = runner.run(listOf(adb, "mdns", "services"), 8)
        if (!result.isSuccess && result.stdout.isBlank()) return emptyList()
        return AndroidParsers.parseMdnsServices(result.stdout.ifBlank { result.stderr })
    }

    override suspend fun mdnsAvailable(): Boolean {
        val adb = discoverSdk().adbPath ?: return false
        val result = runner.run(listOf(adb, "mdns", "check"), 8)
        return result.isSuccess
    }

    override suspend fun generatePairingQr(content: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
                content,
                com.google.zxing.BarcodeFormat.QR_CODE,
                512,
                512,
            )
            val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    image.setRGB(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                }
            }
            val out = ByteArrayOutputStream()
            ImageIO.write(image, "PNG", out)
            out.toByteArray()
        }.getOrNull()
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
