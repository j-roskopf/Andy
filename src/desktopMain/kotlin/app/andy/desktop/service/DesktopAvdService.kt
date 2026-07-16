package app.andy.desktop.service

import app.andy.desktop.parser.AndroidParsers
import app.andy.model.AvdCreationConfig
import app.andy.model.AvdProfile
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.EmulatorSnapshot
import app.andy.model.SystemImage
import app.andy.model.VirtualDevice
import app.andy.model.VirtualDeviceType
import app.andy.service.AvdService
import app.andy.service.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.math.roundToInt

class DesktopAvdService(
    private val runner: CommandRunner,
    private val locator: SdkLocator,
    private val listAvdsFromDisk: () -> List<VirtualDevice> = { AvdHomeScanner.listVirtualDevices() },
    private val resolveAvdHome: () -> File = { AvdHomeScanner.avdHome() },
    private val preferredSdkPath: suspend () -> String? = { null },
) : AvdService {
    private fun getDirectorySize(dir: File): Long {
        var size = 0L
        try {
            if (dir.exists() && dir.isDirectory) {
                dir.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        size += file.length()
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return size
    }

    private fun estimateSize(packageId: String): Long {
        val id = packageId.lowercase()
        return when {
            "automotive-playstore" in id || "auto-playstore" in id -> 1_503_238_553L // ~1.4 GB
            "automotive" in id || "auto" in id -> 1_395_864_371L // ~1.3 GB
            "desktop" in id -> 873_809_510L // ~833.7 MB
            "wear-signed" in id || "wear" in id -> 1_288_490_188L // ~1.2 GB
            "google-tv" in id || "android-tv" in id || "tv-ps16k" in id -> 995_500_000L // ~925 - 949 MB
            "aosp_std" in id || "aosp_atd" in id -> 712_000_000L // ~679 MB
            "default" in id -> 810_800_000L // ~773.2 MB
            "google_apis_playstore" in id || "playstore" in id -> 1_932_735_283L // ~1.8 - 2.1 GB
            "google_apis" in id -> 1_825_361_100L // ~1.7 - 2.0 GB
            else -> 1_500_000_000L // ~1.4 GB
        }
    }

    override suspend fun listSystemImages(): List<SystemImage> = withContext(Dispatchers.IO) {
        val sdk = locator.discover(preferredSdkPath())
        val sdkManager = sdk.sdkManagerPath
            ?: throw IllegalStateException("Android SDK cmdline-tools (sdkmanager) not found")
        val installedResult = runner.run(listOf(sdkManager, "--list_installed"), 30)
        val availableResult = runner.run(listOf(sdkManager, "--list"), 45)
        if (!installedResult.isSuccess && !availableResult.isSuccess) {
            val detail = listOf(installedResult.stderr, availableResult.stderr, installedResult.stdout, availableResult.stdout)
                .firstOrNull { it.isNotBlank() }
                ?.lineSequence()?.firstOrNull { it.isNotBlank() }
                ?.trim()
            throw IllegalStateException(detail ?: "sdkmanager failed to list system images")
        }
        val parsedInstalled = AndroidParsers.parseSystemImages(installedResult.stdout).map { img ->
            val dir = File(sdk.sdkPath, img.packageId.replace(';', File.separatorChar))
            val size = if (dir.exists()) getDirectorySize(dir) else 0L
            img.copy(installed = true, sizeOnDisk = size)
        }
        val parsedAvailable = AndroidParsers.parseSystemImages(availableResult.stdout).map { img ->
            img.copy(sizeOnDisk = estimateSize(img.packageId))
        }
        (parsedInstalled + parsedAvailable).distinctBy { it.packageId }
    }

    override suspend fun listProfiles(): List<AvdProfile> {
        val avdManager = locator.discover(preferredSdkPath()).avdManagerPath ?: return emptyList()
        val result = runner.run(listOf(avdManager, "list", "device"), 20)
        if (!result.isSuccess) return emptyList()
        return AndroidParsers.parseProfiles(result.stdout)
    }

    override suspend fun listVirtualDevices(): List<VirtualDevice> {
        val avds = listAvdsFromDisk()
        val running = runningEmulatorNames()
        return avds.map { avd ->
            val config = avd.config.ifEmpty { loadAvdConfig(avd).orEmpty() }
            val graphics = emulatorGraphicsInfo(emulatorLaunchLogFile(avd.name))
            avd.copy(
                running = running.any { namesMatch(it, avd.name) },
                apiLevel = avd.apiLevel
                    ?: Regex("""android-(\d+)""").find(config["image.sysdir.1"].orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull(),
                deviceType = if (avd.deviceType != VirtualDeviceType.Unknown) {
                    avd.deviceType
                } else {
                    AndroidParsers.classifyVirtualDevice(avd.name, avd.target.orEmpty(), config)
                },
                config = config,
                graphicsBackend = graphics?.backend,
                graphicsRenderer = graphics?.renderer,
                graphicsSoftwareRendered = graphics?.softwareRendered == true,
            )
        }
    }

    override suspend fun createVirtualDevice(name: String, profileId: String, systemImagePackage: String): CommandResult {
        val avdManager = locator.discover(preferredSdkPath()).avdManagerPath ?: return CommandResult.failure("avdmanager not found")
        return runner.run(listOf(avdManager, "create", "avd", "-n", name, "-k", systemImagePackage, "-d", profileId), 120)
    }

    override suspend fun createVirtualDevice(config: AvdCreationConfig): CommandResult {
        val created = createVirtualDevice(config.name, config.profileId, config.systemImagePackage)
        if (!created.isSuccess) return created
        val avd = listVirtualDevices().firstOrNull { namesMatch(it.name, config.name) }
        val configFile = avd?.path?.let { File(it, "config.ini") }
        val writeMessage = configFile?.let { writeAvdConfig(it, config) }.orEmpty()
        if (config.startAfterCreate) {
            val started = startVirtualDevice(config.name)
            return if (started.isSuccess) {
                CommandResult.success(listOf(created.stdout, writeMessage, started.stdout).filter { it.isNotBlank() }.joinToString("\n"))
            } else {
                CommandResult(created.exitCode, created.stdout + "\n" + writeMessage, started.stderr.ifBlank { started.stdout })
            }
        }
        return CommandResult.success(listOf(created.stdout, writeMessage).filter { it.isNotBlank() }.joinToString("\n"))
    }

    override suspend fun startVirtualDevice(name: String): CommandResult {
        return launchVirtualDevice(name, extraArgs = listOf("-no-snapshot-load", "-no-snapshot-save"))
    }

    override suspend fun coldBootVirtualDevice(name: String): CommandResult {
        val runningSerial = findRunningEmulatorSerial(name)
        if (runningSerial != null) {
            val adb = locator.discover(preferredSdkPath()).adbPath ?: return CommandResult.failure("ADB not found")
            runner.run(listOf(adb, "-s", runningSerial, "emu", "kill"), 8)
        }
        return launchVirtualDevice(name, extraArgs = listOf("-no-snapshot-load", "-no-snapshot-save"))
    }

    override suspend fun wipeVirtualDevice(name: String): CommandResult {
        findRunningEmulatorSerial(name)?.let {
            val stop = stopVirtualDevice(name)
            if (!stop.isSuccess) return stop
        }
        return launchVirtualDevice(name, extraArgs = listOf("-wipe-data", "-no-snapshot-load", "-no-snapshot-save"))
    }

    private suspend fun launchVirtualDevice(name: String, extraArgs: List<String> = emptyList()): CommandResult {
        val emulator = locator.discover(preferredSdkPath()).emulatorPath ?: return CommandResult.failure("emulator not found")
        return withContext(Dispatchers.IO) {
            val ports = allocateEmulatorLaunchPorts()
                ?: return@withContext CommandResult.failure("No free emulator port pair found")
            val logFile = emulatorLaunchLogFile(name)
            ProcessBuilder(emulatorStudioStyleLaunchCommand(emulator, name, extraArgs, ports))
                .redirectErrorStream(true)
                // A log describes one launch only. Appending would let a stale renderer from a
                // previous launch be reported as the renderer selected for this process.
                .redirectOutput(ProcessBuilder.Redirect.to(logFile))
                .start()
            val graphics = awaitEmulatorGraphicsInfo(logFile)
            val graphicsSummary = graphics?.let { info ->
                buildString {
                    append("; graphics backend ${info.backend}")
                    info.renderer?.let { append(" ($it)") }
                }
            }.orEmpty()
            CommandResult.success("Starting $name with hidden emulator window and local gRPC control on ${ports.grpc}$graphicsSummary")
        }
    }

    override suspend fun stopVirtualDevice(name: String): CommandResult {
        val sdk = locator.discover(preferredSdkPath())
        val adb = sdk.adbPath ?: return CommandResult.failure("ADB not found")
        val devices = AndroidParsers.parseAdbDevices(runner.run(listOf(adb, "devices", "-l"), 8).stdout)
        val emulator = devices.firstOrNull { device ->
            device.kind == DeviceKind.Emulator &&
                device.state == DeviceConnectionState.Online &&
                namesMatch(resolveAvdName(adb, device.serial) ?: device.displayName, name)
        } ?: return CommandResult.failure("No running emulator found for $name")
        val result = runner.run(listOf(adb, "-s", emulator.serial, "emu", "kill"), 8)
        if (!result.isSuccess) return result
        // `emu kill` returns as soon as the console accepts it, but the emulator takes
        // a moment to actually leave `adb devices`. Wait for it to disappear so a caller
        // that refreshes right after doesn't still report the device as running.
        repeat(20) {
            val stillOnline = AndroidParsers.parseAdbDevices(runner.run(listOf(adb, "devices", "-l"), 8).stdout)
                .any { it.serial == emulator.serial && it.state == DeviceConnectionState.Online }
            if (!stillOnline) return CommandResult.success("Stopped $name (${emulator.serial})")
            delay(300)
        }
        return CommandResult.success("Stopped $name (${emulator.serial})")
    }

    override suspend fun deleteVirtualDevice(name: String): CommandResult {
        val avdManager = locator.discover(preferredSdkPath()).avdManagerPath ?: return CommandResult.failure("avdmanager not found")
        return runner.run(listOf(avdManager, "delete", "avd", "-n", name), 60)
    }

    override suspend fun cloneVirtualDevice(sourceName: String, newName: String): CommandResult = withContext(Dispatchers.IO) {
        if (newName.isBlank()) return@withContext CommandResult.failure("Enter a clone name")
        val source = listVirtualDevices().firstOrNull { namesMatch(it.name, sourceName) }
            ?: return@withContext CommandResult.failure("Source AVD not found: $sourceName")
        val sourceDir = source.path?.let(::File)?.takeIf { it.isDirectory }
            ?: return@withContext CommandResult.failure("Source AVD folder not found")
        val sourceIni = resolveIniFile(source)
            ?: return@withContext CommandResult.failure("Source AVD .ini not found")
        val targetDir = File(sourceDir.parentFile, "$newName.avd")
        val targetIni = File(sourceIni.parentFile, "$newName.ini")
        if (targetDir.exists() || targetIni.exists()) return@withContext CommandResult.failure("AVD already exists: $newName")
        runCatching {
            sourceDir.copyRecursively(targetDir, overwrite = false)
            sourceIni.copyTo(targetIni, overwrite = false)
            rewriteAvdReferences(targetDir, sourceName, newName, sourceDir.absolutePath, targetDir.absolutePath)
            rewriteAvdReferences(targetIni, sourceName, newName, sourceDir.absolutePath, targetDir.absolutePath)
            CommandResult.success("Cloned $sourceName to $newName")
        }.getOrElse { CommandResult.failure(it.message ?: "Clone failed") }
    }

    override suspend fun installSystemImage(packageId: String): CommandResult {
        val sdkManager = locator.discover(preferredSdkPath()).sdkManagerPath ?: return CommandResult.failure("sdkmanager not found")
        return runner.run(listOf(sdkManager, "--install", packageId), 600)
    }

    override suspend fun uninstallSystemImage(packageId: String): CommandResult {
        val sdkManager = locator.discover(preferredSdkPath()).sdkManagerPath ?: return CommandResult.failure("sdkmanager not found")
        return runner.run(listOf(sdkManager, "--uninstall", packageId), 600)
    }

    override suspend fun listSnapshots(avdName: String): List<EmulatorSnapshot> = withContext(Dispatchers.IO) {
        val serial = findRunningEmulatorSerial(avdName)
        val snapshots = mutableListOf<EmulatorSnapshot>()
        val compatibleNames = mutableSetOf<String>()
        if (serial != null) {
            val adb = locator.discover(preferredSdkPath()).adbPath ?: return@withContext emptyList()
            val result = runner.run(listOf(adb, "-s", serial, "emu", "avd", "snapshot", "list"), 12)
            if (result.isSuccess) {
                val parsed = AndroidParsers.parseSnapshots(result.stdout, avdName)
                snapshots += parsed
                compatibleNames += parsed.map { it.name }
            }
        }
        val avd = listVirtualDevices().firstOrNull { namesMatch(it.name, avdName) } ?: return@withContext emptyList()
        val snapshotsDir = avd.path?.let(::File)?.resolve("snapshots") ?: return@withContext emptyList()
        snapshots += snapshotsDir.listFiles()
            ?.filter { it.isDirectory || it.extension == "qcow2" || it.extension == "img" }
            ?.map { EmulatorSnapshot(it.nameWithoutExtension, avd.name, "disk") }
            .orEmpty()

        snapshots
            .distinctBy { it.name }
            .map { populateSnapshotMetadata(avd, it, snapshotsDir, compatibleNames, serial) }
            .sortedBy { it.name }
    }

    private fun populateSnapshotMetadata(
        avd: VirtualDevice,
        snapshot: EmulatorSnapshot,
        snapshotsDir: File,
        compatibleNames: Set<String>,
        serial: String?
    ): EmulatorSnapshot {
        val folder = snapshotsDir.resolve(snapshot.name)
        val isFolder = folder.exists() && folder.isDirectory

        val totalSize: Long
        val lastModified: Long
        val screenshotPath: String?

        if (isFolder) {
            val files = folder.listFiles().orEmpty()
            totalSize = files.sumOf { it.length() }
            val latestFile = files.maxOfOrNull { it.lastModified() } ?: folder.lastModified()
            lastModified = latestFile
            val screenshotFile = folder.resolve("screenshot.png")
            screenshotPath = if (screenshotFile.exists() && screenshotFile.isFile) screenshotFile.absolutePath else null
        } else {
            val file = listOf(
                snapshotsDir.resolve("${snapshot.name}.qcow2"),
                snapshotsDir.resolve("${snapshot.name}.img")
            ).firstOrNull { it.exists() && it.isFile }

            if (file != null) {
                totalSize = file.length()
                lastModified = file.lastModified()
                screenshotPath = null
            } else {
                totalSize = 0L
                lastModified = System.currentTimeMillis()
                screenshotPath = null
            }
        }

        val isCompatible = if (serial != null) {
            snapshot.name in compatibleNames
        } else {
            true
        }

        return snapshot.copy(
            size = formatSize(totalSize),
            createdTime = formatRelativeTime(lastModified),
            screenshotPath = screenshotPath,
            compatible = isCompatible
        )
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return if (digitGroups == 0) {
            "$bytes B"
        } else {
            val formatted = ((value * 10).roundToInt() / 10.0).toString()
            "$formatted ${units[digitGroups]}"
        }
    }

    private fun formatRelativeTime(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> "${days}d ago"
            hours > 0 -> {
                val remMinutes = minutes % 60
                if (remMinutes > 0) "${hours}h${remMinutes}m ago" else "${hours}h ago"
            }
            minutes > 0 -> "${minutes}m ago"
            else -> "just now"
        }
    }

    override suspend fun saveSnapshot(avdName: String, snapshotName: String): CommandResult {
        val serial = findRunningEmulatorSerial(avdName) ?: return CommandResult.failure("Start $avdName before saving a snapshot")
        val adb = locator.discover(preferredSdkPath()).adbPath ?: return CommandResult.failure("ADB not found")
        val result = runner.run(listOf(adb, "-s", serial, "emu", "avd", "snapshot", "save", snapshotName), 180)
        repeat(20) {
            if (listSnapshots(avdName).any { it.name == snapshotName }) {
                return CommandResult.success("Saved snapshot $snapshotName")
            }
            delay(500)
        }
        if (!result.isSuccess) return result
        return CommandResult.success("Snapshot save command finished; $snapshotName may still be indexing. Refresh in a moment.")
    }

    override suspend fun restoreSnapshot(avdName: String, snapshotName: String): CommandResult {
        val serial = findRunningEmulatorSerial(avdName)
        if (serial != null) {
            val adb = locator.discover(preferredSdkPath()).adbPath ?: return CommandResult.failure("ADB not found")
            return runner.run(listOf(adb, "-s", serial, "emu", "avd", "snapshot", "load", snapshotName), 60)
        }
        return launchVirtualDevice(avdName, extraArgs = listOf("-snapshot", snapshotName, "-no-snapshot-save"))
    }

    override suspend fun deleteSnapshot(avdName: String, snapshotName: String): CommandResult {
        val serial = findRunningEmulatorSerial(avdName)
        if (serial != null) {
            val adb = locator.discover(preferredSdkPath()).adbPath ?: return CommandResult.failure("ADB not found")
            return runner.run(listOf(adb, "-s", serial, "emu", "avd", "snapshot", "delete", snapshotName), 60)
        }
        val avd = listVirtualDevices().firstOrNull { namesMatch(it.name, avdName) } ?: return CommandResult.failure("AVD not found")
        val snapshotsDir = avd.path?.let(::File)?.resolve("snapshots") ?: return CommandResult.failure("Snapshots folder not found")
        val matches = snapshotsDir.listFiles()?.filter { it.nameWithoutExtension == snapshotName }.orEmpty()
        if (matches.isEmpty()) return CommandResult.failure("Snapshot not found: $snapshotName")
        matches.forEach { it.deleteRecursively() }
        return CommandResult.success("Deleted snapshot $snapshotName")
    }

    override suspend fun renameSnapshot(avdName: String, oldName: String, newName: String): CommandResult = withContext(Dispatchers.IO) {
        val avd = listVirtualDevices().firstOrNull { namesMatch(it.name, avdName) } ?: return@withContext CommandResult.failure("AVD not found")
        val snapshotsDir = avd.path?.let(::File)?.resolve("snapshots") ?: return@withContext CommandResult.failure("Snapshots folder not found")
        val matches = snapshotsDir.listFiles()?.filter { it.nameWithoutExtension == oldName }.orEmpty()
        if (matches.isEmpty()) return@withContext CommandResult.failure("Snapshot not found: $oldName")
        val moves = matches.map { file ->
            val ext = file.extension
            val newFile = if (ext.isEmpty()) {
                File(file.parentFile, newName)
            } else {
                File(file.parentFile, "$newName.$ext")
            }
            file to newFile
        }
        val collision = moves.firstOrNull { (file, newFile) ->
            newFile.exists() && newFile.canonicalFile != file.canonicalFile
        }
        if (collision != null) {
            return@withContext CommandResult.failure("Snapshot already exists: $newName")
        }
        var allSuccess = true
        moves.forEach { (file, newFile) ->
            if (!file.renameTo(newFile)) {
                allSuccess = false
            }
        }
        if (allSuccess) {
            CommandResult.success("Renamed snapshot $oldName to $newName")
        } else {
            CommandResult.failure("Failed to rename some or all snapshot files")
        }
    }

    private suspend fun runningEmulatorNames(): Set<String> = withContext(Dispatchers.IO) {
        val sdk = locator.discover(preferredSdkPath())
        val adb = sdk.adbPath ?: return@withContext emptySet()
        AndroidParsers.parseAdbDevices(runner.run(listOf(adb, "devices", "-l"), 8).stdout)
            .filter { it.kind == DeviceKind.Emulator && it.state == DeviceConnectionState.Online }
            .mapNotNull { device -> resolveAvdName(adb, device.serial) ?: device.displayName }
            .toSet()
    }

    private suspend fun findRunningEmulatorSerial(name: String): String? {
        val sdk = locator.discover(preferredSdkPath())
        val adb = sdk.adbPath ?: return null
        val devices = AndroidParsers.parseAdbDevices(runner.run(listOf(adb, "devices", "-l"), 8).stdout)
        return devices.firstOrNull { device ->
            device.kind == DeviceKind.Emulator &&
                device.state == DeviceConnectionState.Online &&
                namesMatch(resolveAvdName(adb, device.serial) ?: device.displayName, name)
        }?.serial
    }

    private fun loadAvdConfig(avd: VirtualDevice): Map<String, String>? {
        val configFile = avd.path?.let { File(it, "config.ini") }?.takeIf { it.exists() } ?: return null
        return configFile.readLines()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank() || trimmed.startsWith("#") || "=" !in trimmed) null
                else trimmed.substringBefore("=") to trimmed.substringAfter("=")
            }
            .toMap()
    }

    private fun writeAvdConfig(configFile: File, config: AvdCreationConfig): String {
        val values = linkedMapOf(
            "hw.initialOrientation" to config.orientation,
            "hw.ramSize" to config.ramMb?.toString(),
            "disk.dataPartition.size" to config.storageMb?.let { "${it}M" },
            "hw.cpu.ncore" to config.cpuCores?.toString(),
            "hw.gpu.mode" to config.gpuMode,
            "hw.camera.back" to config.backCamera.configValue,
            "hw.camera.front" to config.frontCamera.configValue,
            "locale" to config.locale.takeIf { it.isNotBlank() },
            "hw.keyboard" to config.hardwareKeyboard.toString(),
        ).filterValues { it != null }.mapValues { it.value.orEmpty() }
        configFile.parentFile?.mkdirs()
        val existing = if (configFile.exists()) configFile.readLines().toMutableList() else mutableListOf()
        val keys = values.keys
        val seen = mutableSetOf<String>()
        val updated = existing.map { line ->
            val key = line.substringBefore("=", "")
            if (key in keys) {
                seen += key
                "$key=${values.getValue(key)}"
            } else {
                line
            }
        }.toMutableList()
        values.filterKeys { it !in seen }.forEach { (key, value) -> updated += "$key=$value" }
        configFile.writeText(updated.joinToString("\n") + "\n")
        return "Wrote ${values.size} config values"
    }

    private fun resolveIniFile(avd: VirtualDevice): File? {
        val path = avd.path?.let(::File) ?: return null
        return listOf(
            File(path.parentFile, "${avd.name}.ini"),
            File(resolveAvdHome(), "${avd.name}.ini"),
        ).firstOrNull { it.exists() }
    }

    private fun rewriteAvdReferences(target: File, oldName: String, newName: String, oldPath: String, newPath: String) {
        if (target.isDirectory) {
            target.walkTopDown().filter { it.isFile && it.extension in setOf("ini", "txt", "cfg") }.forEach {
                rewriteAvdReferences(it, oldName, newName, oldPath, newPath)
            }
            return
        }
        val text = target.readText()
            .replace(oldPath, newPath)
            .replace(oldName, newName)
        target.writeText(text)
    }

    private suspend fun resolveAvdName(adb: String, serial: String): String? {
        val result = runner.run(listOf(adb, "-s", serial, "shell", "getprop"), 8)
        if (!result.isSuccess) return null
        return result.stdout.lineSequence()
            .mapNotNull { line ->
                val match = Regex("""\[(.+)]\:\s+\[(.*)]""").find(line) ?: return@mapNotNull null
                match.groupValues[1] to match.groupValues[2]
            }
            .toMap()
            .let { props -> props["ro.boot.qemu.avd_name"] ?: props["ro.kernel.qemu.avd_name"] }
    }

    private fun namesMatch(left: String, right: String): Boolean {
        return normalizeName(left) == normalizeName(right)
    }

    private fun normalizeName(value: String): String {
        return value.replace('_', ' ').trim().lowercase()
    }

    private fun allocateEmulatorLaunchPorts(): EmulatorLaunchPorts? {
        for (console in 5554..5682 step 2) {
            val adb = console + 1
            val grpc = console + 3000
            if (isLocalTcpPortAvailable(console) && isLocalTcpPortAvailable(adb) && isLocalTcpPortAvailable(grpc)) {
                return EmulatorLaunchPorts(console = console, adb = adb, grpc = grpc)
            }
        }
        return null
    }

    private fun isLocalTcpPortAvailable(port: Int): Boolean {
        return runCatching {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress("127.0.0.1", port))
            }
            true
        }.getOrDefault(false)
    }

    private fun emulatorLaunchLogFile(name: String): File {
        val safeName = name.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        return File(System.getProperty("user.home"), ".andy/emulator/$safeName.log").also { file ->
            file.parentFile?.mkdirs()
        }
    }

    private suspend fun awaitEmulatorGraphicsInfo(logFile: File): EmulatorGraphicsInfo? {
        repeat(15) {
            emulatorGraphicsInfo(logFile)?.let { return it }
            delay(100)
        }
        return null
    }
}

internal data class EmulatorGraphicsInfo(
    val backend: String,
    val renderer: String? = null,
    val softwareRendered: Boolean = false,
)

private val emulatorGraphicsBackendPattern =
    Regex("""Graphics backend:\s*(.+)""", RegexOption.IGNORE_CASE)
private val emulatorGraphicsRendererPattern =
    Regex("""(?:GPU Renderer=\[|Graphics Adapter\s+)(.+?)(?:])?$""", RegexOption.IGNORE_CASE)

/**
 * `-gpu auto` is a request, not proof of host acceleration. gfxstream may resolve to
 * SwiftShader, swangle, lavapipe, or llvmpipe, all of which must be reported as software.
 */
internal fun emulatorGraphicsAreSoftware(backend: String, renderer: String?): Boolean {
    val description = listOfNotNull(backend, renderer).joinToString(" ").lowercase()
    return listOf("swiftshader", "swangle", "lavapipe", "llvmpipe", "software").any(description::contains)
}

/**
 * The emulator writes these after resolving `-gpu auto`. Keep the last occurrence because
 * a single launch may recreate its renderer while booting.
 */
internal fun emulatorGraphicsInfo(logFile: File): EmulatorGraphicsInfo? {
    if (!logFile.isFile) return null
    var backend: String? = null
    var renderer: String? = null
    val success = runCatching {
        logFile.useLines { lines ->
            lines.forEach { line ->
                emulatorGraphicsBackendPattern.find(line)
                    ?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty)
                    ?.let { backend = it }
                emulatorGraphicsRendererPattern.find(line)
                    ?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty)
                    ?.let { renderer = it }
            }
        }
    }.isSuccess
    val resolvedBackend = backend?.takeIf { success } ?: return null
    return EmulatorGraphicsInfo(
        backend = resolvedBackend,
        renderer = renderer,
        softwareRendered = emulatorGraphicsAreSoftware(resolvedBackend, renderer),
    )
}

internal data class EmulatorLaunchPorts(
    val console: Int,
    val adb: Int,
    val grpc: Int,
)

internal fun emulatorStudioStyleLaunchCommand(
    emulator: String,
    name: String,
    extraArgs: List<String> = emptyList(),
    ports: EmulatorLaunchPorts = EmulatorLaunchPorts(console = 5554, adb = 5555, grpc = 8554),
): List<String> {
    return listOf(
        emulator,
        "-avd", name,
        "-qt-hide-window",
        "-ports", "${ports.console},${ports.adb}",
        "-grpc", ports.grpc.toString(),
        "-idle-grpc-timeout", "300",
    ) + extraArgs + listOf(
        "-no-boot-anim",
        "-gpu", "auto",
        "-writable-system",
    )
}
