package app.andy.service

import app.andy.model.DeviceKind
import app.andy.model.DeviceTransport

/**
 * Desktop Head Unit (Android Auto) models shared across platforms.
 * Web has an unavailable stub; desktop owns discovery, process lifecycle, and console.
 */

/** Fixed Andy-owned DHU display config (matches DHU documented defaults). */
object DhuFixedConfig {
    const val Width = 800
    const val Height = 480
    const val Dpi = 160
    const val FrameRate = 30
    const val InputMode = "touch"
    const val DevicePort = 5277
    const val HelpUrl = "https://developer.android.com/training/cars/testing/dhu"
    const val WindowTitleHint = "Desktop Head Unit"

    fun iniContents(): String = buildString {
        appendLine("[general]")
        appendLine("touch = true")
        appendLine("touchpad = false")
        appendLine("controller = false")
        appendLine("instrumentcluster = false")
        appendLine("resolution = ${Width}x${Height}")
        appendLine("dpi = $Dpi")
        appendLine("framerate = $FrameRate")
        appendLine()
        // Match SDK extras/google/auto/config/default.ini sensor defaults.
        appendLine("[sensors]")
        appendLine("location = true")
        appendLine("night_mode = true")
        appendLine("driving_status = true")
    }
}

enum class DhuHostKind {
    MacOs,
    Windows,
    LinuxX11,
    LinuxWayland,
    Unsupported,
}

/** How Andy launches DHU against the selected device. */
enum class DhuLinkTransport {
    /** Physical USB: `desktop-head-unit --usb=<serial>` (libusb; no Head Unit Server). */
    Usb,
    /** Emulator / wireless ADB: forward + `--adb=<localPort>` (needs Head Unit Server on 5277). */
    Adb,
}

enum class DhuCheckStatus {
    Ok,
    Missing,
    Unsupported,
    Unknown,
}

data class DhuReadinessCheck(
    val id: String,
    val label: String,
    val status: DhuCheckStatus,
    val detail: String,
    val remediation: String? = null,
)

data class DhuReadiness(
    val hostKind: DhuHostKind,
    val checks: List<DhuReadinessCheck>,
    val autoDir: String? = null,
    val executablePath: String? = null,
    val adbPath: String? = null,
    val serial: String? = null,
) {
    val ready: Boolean
        get() = checks.isNotEmpty() && checks.all { it.status == DhuCheckStatus.Ok }

    val blocking: List<DhuReadinessCheck>
        get() = checks.filter { it.status != DhuCheckStatus.Ok }

    fun diagnosticsText(): String = buildString {
        appendLine("Android Auto DHU readiness")
        appendLine("host=$hostKind")
        serial?.let { appendLine("serial=$it") }
        autoDir?.let { appendLine("autoDir=$autoDir") }
        executablePath?.let { appendLine("executable=$executablePath") }
        adbPath?.let { appendLine("adb=$adbPath") }
        checks.forEach { check ->
            appendLine("- [${check.status}] ${check.label}: ${check.detail}")
            check.remediation?.let { appendLine("  fix: $it") }
        }
    }
}

enum class DhuSessionPhase {
    Idle,
    Starting,
    Running,
    CaptureUnavailable,
    Stopping,
    Failed,
}

data class DhuSession(
    val serial: String,
    val localPort: Int,
    val phase: DhuSessionPhase,
    val message: String = "",
    val captureAvailable: Boolean = false,
    val processAlive: Boolean = false,
    val startedAtMillis: Long = 0L,
)

data class DhuCaptureFrame(
    val width: Int,
    val height: Int,
    /** Packed ARGB pixels, row-major. Empty when unavailable. */
    val argb: IntArray,
    val frameNumber: Long = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DhuCaptureFrame) return false
        return width == other.width &&
            height == other.height &&
            frameNumber == other.frameNumber &&
            argb.contentEquals(other.argb)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + frameNumber.hashCode()
        result = 31 * result + argb.contentHashCode()
        return result
    }
}

data class DhuConsoleState(
    val lines: List<String> = emptyList(),
    val history: List<String> = emptyList(),
    val historyIndex: Int = -1,
)

/** Pure helpers for command/config construction and console history (unit-tested). */
object DhuCommandFactory {
    /**
     * Prefer USB transport for physically attached phones (matches working
     * `./desktop-head-unit --usb`). Emulators and wireless ADB use the Head Unit Server path.
     */
    fun preferredLinkTransport(
        transport: DeviceTransport?,
        kind: DeviceKind? = null,
    ): DhuLinkTransport = when {
        kind == DeviceKind.Emulator -> DhuLinkTransport.Adb
        transport == DeviceTransport.Usb -> DhuLinkTransport.Usb
        else -> DhuLinkTransport.Adb
    }

    fun buildLaunchCommand(
        executable: String,
        configPath: String,
        link: DhuLinkTransport,
        serial: String,
        localAdbPort: Int = 0,
        inputMode: String = DhuFixedConfig.InputMode,
    ): List<String> {
        val transportFlag = when (link) {
            DhuLinkTransport.Usb -> "--usb=$serial"
            DhuLinkTransport.Adb -> "--adb=$localAdbPort"
        }
        return listOf(
            executable,
            "--config=$configPath",
            "--input=$inputMode",
            transportFlag,
        )
    }

    fun buildAdbForward(adb: String, serial: String, localPort: Int, devicePort: Int = DhuFixedConfig.DevicePort): List<String> =
        listOf(adb, "-s", serial, "forward", "tcp:$localPort", "tcp:$devicePort")

    fun buildAdbForwardRemove(adb: String, serial: String, localPort: Int): List<String> =
        listOf(adb, "-s", serial, "forward", "--remove", "tcp:$localPort")

    /** Exit a stale AOA session so the next DHU USB launch can renegotiate accessory mode. */
    fun buildClearUsbAccessory(adb: String, serial: String): List<List<String>> = listOf(
        listOf(adb, "-s", serial, "shell", "svc", "usb", "setFunctions", "none"),
        listOf(adb, "-s", serial, "shell", "svc", "usb", "setFunctions", "adb"),
    )

    fun buildRestoreUsbAdb(adb: String, serial: String): List<String> =
        listOf(adb, "-s", serial, "shell", "svc", "usb", "setFunctions", "adb")

    fun isUsbAccessoryMode(dumpsysUsb: String): Boolean =
        Regex("""current_functions\s*=\s*ACCESSORY\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(dumpsysUsb)
}

object DhuConsoleHistory {
    const val MaxLines = 2_000
    const val MaxHistory = 100

    fun appendLine(lines: List<String>, line: String): List<String> {
        val next = lines + line
        return if (next.size <= MaxLines) next else next.takeLast(MaxLines)
    }

    fun pushCommand(history: List<String>, command: String): List<String> {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return history
        val withoutDup = history.filterNot { it == trimmed }
        return (withoutDup + trimmed).takeLast(MaxHistory)
    }

    /**
     * Walk command history. [index] of `-1` means “editing a fresh line”.
     * Negative [delta] recalls older commands; positive recalls newer / clears when past the end.
     */
    fun recall(history: List<String>, index: Int, delta: Int): Pair<Int, String?> {
        if (history.isEmpty()) return -1 to null
        val resolved = when {
            index < 0 && delta < 0 -> history.lastIndex
            index < 0 && delta > 0 -> return -1 to null
            else -> {
                val next = index + delta
                if (next > history.lastIndex) return -1 to null
                next.coerceAtLeast(0)
            }
        }
        return resolved to history[resolved]
    }
}
