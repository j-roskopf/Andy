package app.andy.ui.controls

import app.andy.currentTimeMillis
import app.andy.service.CommandResult
import app.andy.service.DeviceService
import kotlinx.coroutines.delay

/**
 * Rotates the device display by 90°.
 *
 * Phone emulators update the virtual accelerometer and leave WindowManager free to respect the
 * foreground app's orientation policy. A portrait-only launcher therefore stays portrait.
 * Landscape-native tablet AVDs use an explicit display-frame lock because their platform default
 * remains fixed during capture; Android large-screen policy then lays out the tablet at that size.
 *
 * Physical devices have no virtual sensor and are not forced underneath orientation-locked
 * apps, so they continue to use the ordinary user-rotation path.
 */
suspend fun DeviceService.rotateDeviceDisplay(serial: String, isEmulator: Boolean): CommandResult {
    if (isEmulator) {
        return rotateEmulatorDisplay(serial)
    }
    return rotatePhysicalDisplay(serial)
}

/**
 * Ensures an emulator's current display aspect matches [orientation]
 * (`portrait` / `landscape`). Used after creating an AVD that requested landscape —
 * `hw.initialOrientation` alone does not always rotate the framebuffer when Andy
 * launches with a hidden emulator window.
 */
suspend fun DeviceService.ensureEmulatorOrientation(
    serial: String,
    orientation: String,
    maxRotations: Int = 3,
): CommandResult {
    val wantLandscape = orientation.equals("landscape", ignoreCase = true)
    repeat(maxRotations) { attempt ->
        val size = readDisplaySize(serial) ?: return CommandResult.failure("Could not read display size")
        val isLandscape = size.first > size.second
        if (isLandscape == wantLandscape) {
            return CommandResult.success(
                if (attempt == 0) {
                    "Already ${orientationLabel(wantLandscape)}"
                } else {
                    "Oriented ${orientationLabel(wantLandscape)}"
                },
            )
        }
        val direct = lockEmulatorToAspect(serial, wantLandscape, size)
        if (direct.isSuccess) {
            delay(400)
            val after = readDisplaySize(serial)
            val afterLandscape = after != null && after.first > after.second
            if (afterLandscape == wantLandscape) {
                return CommandResult.success("Oriented ${orientationLabel(wantLandscape)}")
            }
        }
        val rotated = rotateDeviceDisplay(serial, isEmulator = true)
        if (!rotated.isSuccess) return rotated
        delay(400)
    }
    val size = readDisplaySize(serial)
    val isLandscape = size != null && size.first > size.second
    return if (isLandscape == wantLandscape) {
        CommandResult.success("Oriented ${orientationLabel(wantLandscape)}")
    } else {
        CommandResult.failure("Could not reach ${orientationLabel(wantLandscape)} orientation")
    }
}

private suspend fun DeviceService.rotateEmulatorDisplay(serial: String): CommandResult {
    val before = readLogicalDisplaySize(serial)
        ?: return CommandResult.failure("Could not read display size")
    val wantLandscape = before.first <= before.second
    val naturalSize = readPhysicalDisplaySize(serial)
    val naturalLandscape = naturalSize?.let { it.first > it.second } ?: false
    if (naturalLandscape) {
        return lockEmulatorToAspect(serial, wantLandscape, before)
    }
    val sensorTurn = readEmulatorDisplayRotation(serial)
    // The device posture, not the app's unchanged aspect, owns the next press. If a launcher
    // refuses landscape, the sensor is still landscape after the first press; pressing again
    // must return it to portrait instead of resending the same landscape posture forever.
    val next = toggledQuarterTurn(sensorTurn ?: readCurrentQuarterTurn(serial))

    // Clear fixed overrides left by explicit AVD startup-orientation handling (and older Andy
    // builds), then let the virtual sensor drive normal Android rotation policy. This keeps a
    // locked app portrait instead of putting it in a squeezed compatibility window.
    // `default` is still fixed on large-screen AVDs (Pixel Tablet reports
    // mFixedToUserRotation=true), so explicitly disable the display-wide lock. Foreground app
    // orientation policy remains authoritative; this only lets WindowManager observe the sensor.
    shell(serial, listOf("wm", "fixed-to-user-rotation", "disabled"))
    shell(serial, listOf("wm", "user-rotation", "free"))
    shell(serial, listOf("settings", "put", "system", "accelerometer_rotation", "1"))

    val rotated = if (sensorTurn != null) {
        applyEmulatorDisplayRotation(serial, next)
    } else {
        emu(serial, listOf("rotate"))
    }
    if (!rotated.isSuccess) return rotated
    if (sensorTurn != null) {
        if (!awaitEmulatorDisplayRotation(serial, next)) {
            return CommandResult.failure("Emulator rotation sensor did not move")
        }
    }
    return rotationOutcome(
        serial = serial,
        target = next,
        before = before,
        targetLabel = userRotationLabel(next),
    )
}

/** The emulator may publish a PhysicalModel write asynchronously, especially while decoding video. */
private suspend fun DeviceService.awaitEmulatorDisplayRotation(
    serial: String,
    expectedQuarterTurn: Int,
    timeoutMillis: Long = 2_000L,
    pollMillis: Long = 100L,
): Boolean {
    val expected = ((expectedQuarterTurn % 4) + 4) % 4
    val deadline = currentTimeMillis() + timeoutMillis
    do {
        if (readEmulatorDisplayRotation(serial) == expected) return true
        delay(pollMillis)
    } while (currentTimeMillis() < deadline)
    return readEmulatorDisplayRotation(serial) == expected
}

internal fun quarterTurnForAspect(
    wantLandscape: Boolean,
    naturalLandscape: Boolean = false,
): Int {
    // Landscape-native Android devices define their ordinary portrait as ROTATION_270;
    // ROTATION_90 is upside-down portrait and can be rejected even though its aspect is right.
    // Phone-natural devices use the familiar ROTATION_0 portrait / ROTATION_90 landscape pair.
    if (wantLandscape == naturalLandscape) return 0
    return if (naturalLandscape) 3 else 1
}

private suspend fun DeviceService.readPhysicalDisplaySize(serial: String): Pair<Int, Int>? {
    val wm = shell(serial, listOf("wm", "size"))
    return parseWmSizePx(wm.stdout.ifBlank { wm.stderr })
}

private suspend fun DeviceService.rotatePhysicalDisplay(serial: String): CommandResult {
    val before = readLogicalDisplaySize(serial)
        ?: return CommandResult.failure("Could not read display size")
    val next = toggledQuarterTurn(readCurrentQuarterTurn(serial))
    val wm = rotateViaWmUserRotation(serial, before, targetRotation = next)
    if (wm.isSuccess) {
        syncAndroidUserRotation(serial, next)
        return wm
    }
    val disableAuto = shell(serial, listOf("settings", "put", "system", "accelerometer_rotation", "0"))
    if (!disableAuto.isSuccess) {
        return CommandResult.failure(
            disableAuto.stderr.ifBlank { disableAuto.stdout }.ifBlank { "Could not disable auto-rotate" },
        )
    }
    val set = shell(serial, listOf("settings", "put", "system", "user_rotation", next.toString()))
    if (!set.isSuccess) {
        return CommandResult.failure(set.stderr.ifBlank { set.stdout }.ifBlank { "rotate failed" })
    }
    delay(400)
    val after = readLogicalDisplaySize(serial)
    return if (logicalOrientationChanged(before, after)) {
        rotationSuccess(after)
    } else {
        CommandResult.failure("Display did not rotate (still ${orientationLabelFromSize(before)})")
    }
}

private suspend fun DeviceService.readCurrentQuarterTurn(serial: String): Int {
    val wm = shell(serial, listOf("wm", "user-rotation"))
    parseWmUserRotation(wm.stdout.ifBlank { wm.stderr })?.let { return it }
    val settings = shell(serial, listOf("settings", "get", "system", "user_rotation"))
    return settings.stdout.trim().toIntOrNull()?.coerceIn(0, 3) ?: 0
}

private suspend fun DeviceService.syncAndroidUserRotation(serial: String, quarterTurn: Int) {
    val turn = quarterTurn.coerceIn(0, 3)
    shell(serial, listOf("settings", "put", "system", "accelerometer_rotation", "0"))
    shell(serial, listOf("settings", "put", "system", "user_rotation", turn.toString()))
}

/**
 * Forces a quarter-turn via WindowManager. Required for emulators started with
 * `-qt-hide-window`, where `adb emu rotate` is a no-op.
 *
 * Unlike the interactive rotate button this *does* set `fixed-to-user-rotation`, because
 * an AVD created with `hw.initialOrientation=landscape` and the interactive Rotate button are
 * both asking for a specific device-frame orientation regardless of foreground app preference.
 */
private suspend fun DeviceService.lockEmulatorToAspect(
    serial: String,
    wantLandscape: Boolean,
    before: Pair<Int, Int>,
): CommandResult {
    shell(serial, listOf("wm", "fixed-to-user-rotation", "enabled"))
    shell(serial, listOf("settings", "put", "system", "accelerometer_rotation", "0"))
    val naturalLandscape = readPhysicalDisplaySize(serial)?.let { it.first > it.second } ?: false
    val primaryTarget = quarterTurnForAspect(wantLandscape, naturalLandscape)
    val targets = listOf(primaryTarget, (primaryTarget + 2) % 4)
    for (target in targets) {
        val set = shell(serial, listOf("wm", "user-rotation", "lock", target.toString()))
        if (!set.isSuccess) continue
        val after = awaitDisplayAspect(serial, wantLandscape)
        val afterLandscape = after != null && after.first > after.second
        if (afterLandscape == wantLandscape && logicalOrientationChanged(before, after)) {
            syncAndroidUserRotation(serial, target)
            return CommandResult.success("Locked ${orientationLabel(wantLandscape)}")
        }
    }
    return CommandResult.failure("Could not lock ${orientationLabel(wantLandscape)} aspect")
}

/** Waits for WindowManager's asynchronous display reconfiguration, not just command success. */
private suspend fun DeviceService.awaitDisplayAspect(
    serial: String,
    wantLandscape: Boolean,
    timeoutMillis: Long = 2_500L,
    pollMillis: Long = 100L,
): Pair<Int, Int>? {
    val deadline = currentTimeMillis() + timeoutMillis
    var latest = readLogicalDisplaySize(serial)
    while ((latest == null || (latest.first > latest.second) != wantLandscape) &&
        currentTimeMillis() < deadline
    ) {
        delay(pollMillis)
        latest = readLogicalDisplaySize(serial)
    }
    return latest
}

/**
 * Rotates via WindowManager without `fixed-to-user-rotation`, so an app that pins its own
 * orientation keeps it rather than being pillarboxed inside a force-rotated display.
 */
private suspend fun DeviceService.rotateViaWmUserRotation(
    serial: String,
    before: Pair<Int, Int>,
    targetRotation: Int,
): CommandResult {
    val next = targetRotation.coerceIn(0, 3)
    val set = shell(serial, listOf("wm", "user-rotation", "lock", next.toString()))
    if (!set.isSuccess) {
        return CommandResult.failure(
            set.stderr.ifBlank { set.stdout }.ifBlank { "wm user-rotation failed" },
        )
    }
    delay(500)
    return rotationOutcome(serial, next, before)
}

/**
 * Describes where the device ended up. A flipped aspect is *not* the success condition:
 * an orientation-locked app correctly keeps its own aspect while the device turns, so the
 * rotation applying is what matters, and the message says which of the two happened.
 *
 * Android re-lays out asynchronously, so this samples until the display moves instead of
 * guessing a settle window — a fixed delay reports the pre-rotation aspect and mislabels
 * an app that did rotate as locked.
 */
private suspend fun DeviceService.rotationOutcome(
    serial: String,
    target: Int,
    before: Pair<Int, Int>?,
    targetLabel: String = userRotationLabel(target),
    settleMillis: Long = 2_000L,
    pollMillis: Long = 150L,
): CommandResult {
    val deadline = currentTimeMillis() + settleMillis
    var after = readLogicalDisplaySize(serial)
    while (!logicalOrientationChanged(before, after) && currentTimeMillis() < deadline) {
        delay(pollMillis)
        after = readLogicalDisplaySize(serial)
    }
    if (logicalOrientationChanged(before, after)) {
        return rotationSuccess(after)
    }
    return CommandResult.success(
        "Rotated device toward $targetLabel · current app remains " + orientationLabelFromSize(after),
    )
}

private suspend fun DeviceService.readDisplaySize(serial: String): Pair<Int, Int>? =
    readLogicalDisplaySize(serial)

private fun rotationSuccess(size: Pair<Int, Int>?): CommandResult =
    CommandResult.success("Rotated to ${orientationLabelFromSize(size)}")

internal fun orientationLabelFromSize(size: Pair<Int, Int>?): String {
    if (size == null) return "unknown orientation"
    return orientationLabel(size.first > size.second)
}

/**
 * Toggles between the portrait (0) and landscape (1) quarter turns.
 *
 * Not `(current + 1) % 4`: Android refuses `ROTATION_180` for apps that have not opted into
 * reverse portrait, so stepping through all four turns made the button appear dead for two
 * of every four presses — turn 2 leaves the screen in landscape, exactly where turn 1 left it.
 */
internal fun toggledQuarterTurn(current: Int): Int =
    if (current % 2 == 0) 1 else 0

internal fun userRotationLabel(value: Int): String = when (value) {
    0 -> "portrait"
    1 -> "landscape"
    2 -> "reverse portrait"
    3 -> "reverse landscape"
    else -> "rotation $value"
}

/** Parses `wm user-rotation` stdout (`free`, `lock 1`, or a bare `0`–`3`). */
internal fun parseWmUserRotation(stdout: String): Int? {
    val trimmed = stdout.trim()
    if (trimmed.isEmpty()) return null
    Regex("""\block\s+([0-3])\b""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.get(1)
        ?.toIntOrNull()
        ?.let { return it }
    Regex("""\b([0-3])\b""").find(trimmed)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    if (trimmed.equals("free", ignoreCase = true)) return 0
    return null
}

internal fun logicalOrientationChanged(before: Pair<Int, Int>?, after: Pair<Int, Int>?): Boolean {
    if (before == null || after == null) return false
    val beforeLandscape = before.first > before.second
    val afterLandscape = after.first > after.second
    return beforeLandscape != afterLandscape
}

private fun orientationLabel(landscape: Boolean): String =
    if (landscape) "landscape" else "portrait"
