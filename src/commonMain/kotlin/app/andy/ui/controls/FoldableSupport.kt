package app.andy.ui.controls

import app.andy.model.AndroidDevice
import app.andy.model.DeviceKind
import app.andy.model.VirtualDevice
import app.andy.model.VirtualDeviceType
import app.andy.service.CommandResult
import app.andy.service.DeviceService
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Discrete foldable postures matching Android Emulator Extended Controls. */
enum class FoldablePosture(
    val label: String,
    val emuValue: Int,
    val defaultAngle: Float,
) {
    Closed("Closed", emuValue = 1, defaultAngle = 0f),
    Opened("Open", emuValue = 3, defaultAngle = 180f),
}

fun foldablePostureForAngle(angle: Float): FoldablePosture =
    if (angle.coerceIn(0f, 180f) < 90f) FoldablePosture.Closed else FoldablePosture.Opened

private val foldableNamePattern = Regex("""\bfold(?:able)?\b""", RegexOption.IGNORE_CASE)

/**
 * True when the selected device is a foldable AVD that supports `adb emu` hinge controls.
 * Physical foldables are excluded — hinge/posture commands are emulator-only.
 */
fun isFoldableEmulator(
    device: AndroidDevice?,
    virtualDevices: List<VirtualDevice> = emptyList(),
): Boolean {
    if (device == null || device.kind != DeviceKind.Emulator) return false
    val avdName = device.displayName.trim()
    val avd = virtualDevices.firstOrNull { it.name.equals(avdName, ignoreCase = true) }
    if (avd != null) {
        if (avd.deviceType == VirtualDeviceType.Foldable) return true
        if (foldableDisplayProfile(avd) != null) return true
        if (avd.deviceType != VirtualDeviceType.Unknown) return false
    }
    val haystack = listOfNotNull(device.displayName, device.model, device.product)
        .joinToString(" ")
        .replace('_', ' ')
    return foldableNamePattern.containsMatchIn(haystack)
}

fun formatHingeAngle(angle: Float): String = "${angle.roundToInt()}°"

/**
 * `adb emu` often prints `KO: …` while still exiting 0. Treat that as failure.
 */
internal fun CommandResult.emulatorConsoleOk(): Boolean {
    if (exitCode != 0) return false
    val text = "$stdout\n$stderr"
    return !text.contains("KO:", ignoreCase = true)
}

internal fun parseWmSizePx(stdout: String): Pair<Int, Int>? {
    val match = Regex("""(\d+)\s*[x×]\s*(\d+)""", RegexOption.IGNORE_CASE).find(stdout) ?: return null
    val width = match.groupValues[1].toIntOrNull() ?: return null
    val height = match.groupValues[2].toIntOrNull() ?: return null
    if (width <= 0 || height <= 0) return null
    return width to height
}

/**
 * Logical size of the default display (`mDisplayId=0`), including rotation.
 * `wm size` often only reports Physical size (unrotated panel), which leaves Live stuck in
 * portrait after `adb emu rotate` even though scrcpy has already switched to landscape.
 */
internal fun parseDisplay0CurrentSize(stdout: String): Pair<Int, Int>? {
    val display0 = Regex(
        """Display:\s*mDisplayId=0\b[\s\S]*?(?=Display:\s*mDisplayId=|\z)""",
        RegexOption.IGNORE_CASE,
    ).find(stdout)?.value ?: return null
    val match = Regex("""\bcur=(\d+)x(\d+)\b""", RegexOption.IGNORE_CASE).find(display0) ?: return null
    val width = match.groupValues[1].toIntOrNull() ?: return null
    val height = match.groupValues[2].toIntOrNull() ?: return null
    if (width <= 0 || height <= 0) return null
    return width to height
}

/** Reads the current logical display size, preferring rotation-aware dumpsys over `wm size`. */
internal suspend fun DeviceService.readLogicalDisplaySize(serial: String): Pair<Int, Int>? {
    val dumpsys = shell(serial, listOf("dumpsys", "window", "displays"))
    parseDisplay0CurrentSize(dumpsys.stdout.ifBlank { dumpsys.stderr })?.let { return it }
    val wm = shell(serial, listOf("wm", "size"))
    return parseWmSizePx(wm.stdout.ifBlank { wm.stderr })
}

/** Expected physical pixels for [posture] from an AVD profile, when known. */
fun FoldableDisplayProfile.sizeForPosture(posture: FoldablePosture): Pair<Int, Int> =
    if (posture == FoldablePosture.Closed) {
        outerWidth to outerHeight
    } else {
        innerWidth to innerHeight
    }

/** Outer cover displays are tall/narrow; inner unfolded displays are closer to square. */
internal fun isOuterFoldableDisplay(width: Int, height: Int): Boolean =
    width.toFloat() / height.toFloat() < 0.72f

suspend fun DeviceService.setFoldablePosture(serial: String, posture: FoldablePosture): CommandResult {
    emu(serial, listOf("posture", posture.emuValue.toString()))
    return setFoldableHingeAngle(serial, posture.defaultAngle).let { hinge ->
        if (hinge.isSuccess) {
            CommandResult.success("Posture ${posture.label} · ${formatHingeAngle(posture.defaultAngle)} · ${hinge.stdout}")
        } else {
            hinge
        }
    }
}

suspend fun DeviceService.setFoldableHingeAngle(serial: String, angleDegrees: Float): CommandResult {
    val angle = angleDegrees.coerceIn(0f, 180f)
    val formatted = formatHingeAngleNumber(angle)
    val posture = foldablePostureForAngle(angle)
    val wantFolded = posture == FoldablePosture.Closed

    // Order matches Studio: posture, hinge sensor, then explicit fold/unfold.
    val postureResult = emu(serial, listOf("posture", posture.emuValue.toString()))
    val sensor = emu(serial, listOf("sensor", "set", "hinge-angle0", formatted))
    val display = emu(serial, listOf(if (wantFolded) "fold" else "unfold"))

    val commanded =
        postureResult.emulatorConsoleOk() ||
            sensor.emulatorConsoleOk() ||
            display.emulatorConsoleOk()
    if (!commanded) {
        return CommandResult.failure(
            sensor.stderr.ifBlank { sensor.stdout }
                .ifBlank { display.stderr.ifBlank { display.stdout } }
                .ifBlank { postureResult.stderr.ifBlank { postureResult.stdout } }
                .ifBlank { "Foldable hinge control failed" },
        )
    }

    val switched = waitForFoldableDisplay(serial, folded = wantFolded)
    val size = currentWmSizeLabel(serial)
    return if (switched) {
        CommandResult.success(
            buildString {
                append("Hinge ").append(formatHingeAngle(angle))
                append(if (wantFolded) " · folded" else " · unfolded")
                if (size != null) append(" · ").append(size)
            },
        )
    } else {
        CommandResult.failure(
            "Emulator did not switch display " +
                "(wanted ${if (wantFolded) "outer/folded" else "inner/unfolded"}" +
                (size?.let { ", still $it" } ?: "") +
                "). Check that this AVD supports fold/unfold.",
        )
    }
}

private suspend fun DeviceService.waitForFoldableDisplay(serial: String, folded: Boolean): Boolean {
    repeat(20) {
        val size = parseWmSizePx(shell(serial, listOf("wm", "size")).stdout)
        if (size != null) {
            val outer = isOuterFoldableDisplay(size.first, size.second)
            if (folded && outer) return true
            if (!folded && !outer) return true
        }
        delay(100)
    }
    return false
}

private suspend fun DeviceService.currentWmSizeLabel(serial: String): String? {
    val size = parseWmSizePx(shell(serial, listOf("wm", "size")).stdout) ?: return null
    return "${size.first}×${size.second}"
}

private fun formatHingeAngleNumber(angle: Float): String {
    val rounded = (angle * 10f).roundToInt() / 10f
    return if (rounded == rounded.toInt().toFloat()) {
        rounded.toInt().toString()
    } else {
        rounded.toString()
    }
}
