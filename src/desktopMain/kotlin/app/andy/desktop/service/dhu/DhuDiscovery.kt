package app.andy.desktop.service.dhu

import app.andy.service.DhuCheckStatus
import app.andy.service.DhuFixedConfig
import app.andy.service.DhuHostKind
import app.andy.service.DhuLinkTransport
import app.andy.service.DhuReadiness
import app.andy.service.DhuReadinessCheck
import java.io.File

/** Filesystem / OS probes used by [DhuDiscovery] (injectable for unit tests). */
internal interface DhuFs {
    fun isDirectory(path: String): Boolean
    fun isExecutable(path: String): Boolean
    fun isFile(path: String): Boolean
    fun listNames(dir: String): List<String>
}

internal class JavaDhuFs : DhuFs {
    override fun isDirectory(path: String): Boolean = File(path).isDirectory
    override fun isExecutable(path: String): Boolean {
        val file = File(path)
        return file.isFile && (file.canExecute() || file.name.endsWith(".exe", ignoreCase = true))
    }
    override fun isFile(path: String): Boolean = File(path).isFile
    override fun listNames(dir: String): List<String> =
        File(dir).list()?.toList().orEmpty()
}

internal data class DhuHostEnvironment(
    val hostKind: DhuHostKind,
    val isWindows: Boolean,
    val capturePermissionGranted: Boolean,
    val capturePermissionDetail: String,
)

/**
 * Discovers SDK `extras/google/auto` and builds readiness without modifying the SDK.
 */
internal object DhuDiscovery {
    fun autoDir(sdkPath: String?): String? {
        if (sdkPath.isNullOrBlank()) return null
        return File(sdkPath, "extras/google/auto").absolutePath
    }

    fun executableName(isWindows: Boolean): String =
        if (isWindows) "desktop-head-unit.exe" else "desktop-head-unit"

    /**
     * Exact names the Google auto package has shipped. Prefer these first; [isLibusbFileName]
     * also accepts versioned variants. The macOS SDK package commonly includes Linux-style
     * `libusb-1.0.so` (not a `.dylib`).
     */
    fun libusbNames(isWindows: Boolean, hostKind: DhuHostKind): List<String> = when {
        isWindows -> listOf("libusb-1.0.dll", "libusb-1.0.so", "libusb-1.0.dylib")
        hostKind == DhuHostKind.MacOs -> listOf("libusb-1.0.so", "libusb-1.0.dylib", "libusb-1.0.0.dylib", "libusb-1.0.so.0")
        else -> listOf("libusb-1.0.so", "libusb-1.0.so.0", "libusb-1.0.dylib")
    }

    /** True for `libusb-1.0.so`, `libusb-1.0.so.0`, `libusb-1.0.dylib`, `libusb-1.0.dll`, … */
    fun isLibusbFileName(name: String): Boolean =
        name.lowercase().matches(Regex("""libusb[-_]?1\.0(\.\d+)?\.(so|dylib|dll)(\.\d+)*"""))

    fun findExecutable(autoDir: String?, isWindows: Boolean, fs: DhuFs): String? {
        if (autoDir.isNullOrBlank() || !fs.isDirectory(autoDir)) return null
        val name = executableName(isWindows)
        val path = File(autoDir, name).absolutePath
        return path.takeIf { fs.isExecutable(it) }
    }

    fun findLibusb(autoDir: String?, isWindows: Boolean, hostKind: DhuHostKind, fs: DhuFs): String? {
        if (autoDir.isNullOrBlank() || !fs.isDirectory(autoDir)) return null
        val names = fs.listNames(autoDir)
        val preferred = libusbNames(isWindows, hostKind)
        val exact = preferred.firstOrNull { candidate ->
            names.any { it.equals(candidate, ignoreCase = true) }
        }
        val matched = exact ?: names.firstOrNull(::isLibusbFileName)
        return matched
            ?.let { fileName -> names.first { it.equals(fileName, ignoreCase = true) } }
            ?.let { File(autoDir, it).absolutePath }
            ?.takeIf { fs.isFile(it) }
    }

    /**
     * Parse `/proc/net/tcp` / `tcp6` text for local listening ports.
     * Local address is `IPHEX:PORTHHEX`; port is big-endian hex (5277 → `149D`).
     */
    fun listeningTcpPortsFromProcNet(procNetText: String): Set<Int> {
        val ports = mutableSetOf<Int>()
        // TCP_LISTEN = 0A in /proc/net/tcp state column.
        val linePattern = Regex(
            """^\s*\d+:\s+[0-9A-Fa-f]+:([0-9A-Fa-f]{4})\s+[0-9A-Fa-f]+:[0-9A-Fa-f]{4}\s+0A\b""",
            RegexOption.MULTILINE,
        )
        for (match in linePattern.findAll(procNetText)) {
            match.groupValues[1].toIntOrNull(16)?.let { ports += it }
        }
        return ports
    }

    fun isDevicePortListening(procNetText: String, port: Int = DhuFixedConfig.DevicePort): Boolean =
        port in listeningTcpPortsFromProcNet(procNetText)

    const val HeadUnitServerRemediation =
        "For wireless/emulator ADB: start the Android Auto Head Unit Server on the device " +
            "(Settings → Connected devices → Connection preferences → Android Auto, or the Android Auto app → " +
            "tap Version 10× → ⋮ → Start head unit server). USB phones should use DHU --usb (Andy does this " +
            "automatically). Andy does not change Android Auto settings for you."

    fun evaluate(
        sdkPath: String?,
        adbPath: String?,
        serial: String?,
        deviceOnline: Boolean,
        env: DhuHostEnvironment,
        fs: DhuFs = JavaDhuFs(),
        /** null omits the check (USB link, or probe inconclusive). */
        headUnitServerListening: Boolean? = null,
        linkTransport: DhuLinkTransport? = null,
    ): DhuReadiness {
        val checks = mutableListOf<DhuReadinessCheck>()
        val auto = autoDir(sdkPath)
        val executable = findExecutable(auto, env.isWindows, fs)
        val libusb = findLibusb(auto, env.isWindows, env.hostKind, fs)

        checks += when (env.hostKind) {
            DhuHostKind.Unsupported -> DhuReadinessCheck(
                id = "host",
                label = "Desktop host",
                status = DhuCheckStatus.Unsupported,
                detail = "This host cannot launch Android Auto DHU.",
                remediation = "Use Andy Desktop on macOS, Windows, or Linux.",
            )
            else -> DhuReadinessCheck(
                id = "host",
                label = "Desktop host",
                status = DhuCheckStatus.Ok,
                detail = env.hostKind.name,
            )
        }

        checks += if (sdkPath.isNullOrBlank()) {
            DhuReadinessCheck(
                id = "sdk",
                label = "Android SDK",
                status = DhuCheckStatus.Missing,
                detail = "No Android SDK path discovered.",
                remediation = "Install Android Studio / cmdline-tools and set ANDROID_HOME, or select an SDK in Andy Settings.",
            )
        } else {
            DhuReadinessCheck(
                id = "sdk",
                label = "Android SDK",
                status = DhuCheckStatus.Ok,
                detail = sdkPath,
            )
        }

        checks += when {
            auto == null || !fs.isDirectory(auto) -> DhuReadinessCheck(
                id = "auto_extra",
                label = "SDK extras/google/auto",
                status = DhuCheckStatus.Missing,
                detail = auto ?: "extras/google/auto not found",
                remediation = "Install the Android Auto Desktop Head Unit package via sdkmanager: extras;google;auto. Andy will not install it for you.",
            )
            else -> DhuReadinessCheck(
                id = "auto_extra",
                label = "SDK extras/google/auto",
                status = DhuCheckStatus.Ok,
                detail = auto,
            )
        }

        checks += if (executable == null) {
            DhuReadinessCheck(
                id = "executable",
                label = "DHU executable",
                status = DhuCheckStatus.Missing,
                detail = "Missing ${executableName(env.isWindows)} under extras/google/auto.",
                remediation = "Reinstall extras;google;auto so desktop-head-unit is present and executable.",
            )
        } else {
            DhuReadinessCheck(
                id = "executable",
                label = "DHU executable",
                status = DhuCheckStatus.Ok,
                detail = executable,
            )
        }

        checks += if (libusb == null) {
            DhuReadinessCheck(
                id = "libusb",
                label = "Colocated libusb",
                status = DhuCheckStatus.Missing,
                detail = "libusb not found next to the DHU binary.",
                remediation = "Ensure the SDK auto package includes libusb beside desktop-head-unit (do not move the binary alone).",
            )
        } else {
            DhuReadinessCheck(
                id = "libusb",
                label = "Colocated libusb",
                status = DhuCheckStatus.Ok,
                detail = libusb,
            )
        }

        checks += DhuReadinessCheck(
            id = "capture_permission",
            label = "DHU display",
            status = DhuCheckStatus.Ok,
            detail = "Separate desktop-head-unit window (not embedded in Andy)",
        )

        checks += if (adbPath.isNullOrBlank()) {
            DhuReadinessCheck(
                id = "adb",
                label = "ADB",
                status = DhuCheckStatus.Missing,
                detail = "ADB not found in the selected SDK.",
                remediation = "Install platform-tools (adb) into the Android SDK.",
            )
        } else {
            DhuReadinessCheck(
                id = "adb",
                label = "ADB",
                status = DhuCheckStatus.Ok,
                detail = adbPath,
            )
        }

        checks += when {
            serial.isNullOrBlank() -> DhuReadinessCheck(
                id = "device",
                label = "Selected device",
                status = DhuCheckStatus.Missing,
                detail = "No Android device selected.",
                remediation = "Select an online Android phone or emulator in Devices.",
            )
            !deviceOnline -> DhuReadinessCheck(
                id = "device",
                label = "Selected device",
                status = DhuCheckStatus.Missing,
                detail = "Device $serial is not online.",
                remediation = "Reconnect the device over USB/Wi‑Fi and wait until ADB reports it online.",
            )
            else -> DhuReadinessCheck(
                id = "device",
                label = "Selected device",
                status = DhuCheckStatus.Ok,
                detail = serial,
            )
        }

        if (deviceOnline && !serial.isNullOrBlank() && linkTransport != null) {
            checks += when (linkTransport) {
                DhuLinkTransport.Usb -> DhuReadinessCheck(
                    id = "link",
                    label = "DHU link",
                    status = DhuCheckStatus.Ok,
                    detail = "USB transport (--usb=$serial); Head Unit Server not required",
                )
                DhuLinkTransport.Adb -> DhuReadinessCheck(
                    id = "link",
                    label = "DHU link",
                    status = DhuCheckStatus.Ok,
                    detail = "ADB transport; requires Head Unit Server on TCP ${DhuFixedConfig.DevicePort}",
                )
            }
        }

        if (linkTransport != DhuLinkTransport.Usb &&
            deviceOnline &&
            !serial.isNullOrBlank() &&
            headUnitServerListening != null
        ) {
            checks += if (headUnitServerListening) {
                DhuReadinessCheck(
                    id = "head_unit_server",
                    label = "Head Unit Server",
                    status = DhuCheckStatus.Ok,
                    detail = "Device listening on TCP ${DhuFixedConfig.DevicePort}",
                )
            } else {
                DhuReadinessCheck(
                    id = "head_unit_server",
                    label = "Head Unit Server",
                    status = DhuCheckStatus.Missing,
                    detail = "Nothing is listening on device port ${DhuFixedConfig.DevicePort}.",
                    remediation = HeadUnitServerRemediation,
                )
            }
        }

        return DhuReadiness(
            hostKind = env.hostKind,
            checks = checks,
            autoDir = auto?.takeIf { fs.isDirectory(it) },
            executablePath = executable,
            adbPath = adbPath,
            serial = serial,
        )
    }

    fun writeConfigFile(dir: File): File {
        dir.mkdirs()
        val file = File(dir, "andy-dhu.ini")
        file.writeText(DhuFixedConfig.iniContents())
        return file
    }
}
