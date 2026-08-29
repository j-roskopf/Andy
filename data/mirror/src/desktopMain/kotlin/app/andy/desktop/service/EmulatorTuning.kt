package app.andy.desktop.service

/**
 * Emulator launch / AVD mode ceiling (`-vsync-rate`, `hw.lcd.vsync`).
 * Kept at 120 so Live can raise guest peak/min to 120 without restarting the emulator.
 * Guest render rate still follows Live maxFps (default 60) via [emulatorGuestRefreshShellCommands] —
 * `-vsync-rate` alone does not force Android to run at 120.
 * Override with [EMULATOR_VSYNC_RATE_ENV].
 */
const val DEFAULT_EMULATOR_VSYNC_RATE = 120

const val EMULATOR_VSYNC_RATE_ENV = "ANDY_EMULATOR_VSYNC_RATE"

/** Guest display refresh in Hz for `-vsync-rate` / `hw.lcd.vsync`. */
fun emulatorVsyncRate(env: (String) -> String? = System::getenv): Int {
    val override = env(EMULATOR_VSYNC_RATE_ENV)?.trim()?.toIntOrNull()
    return override?.takeIf { it > 0 } ?: DEFAULT_EMULATOR_VSYNC_RATE
}

/** Shell commands to set guest peak/min refresh before scrcpy starts. */
fun emulatorGuestRefreshShellCommands(
    rateHz: Int,
    displayWidth: Int = 0,
    displayHeight: Int = 0,
): List<List<String>> {
    val hz = rateHz.coerceIn(1, 240)
    val commands = mutableListOf(
        listOf("settings", "put", "system", "peak_refresh_rate", hz.toString()),
        listOf("settings", "put", "system", "min_refresh_rate", hz.toString()),
    )
    if (displayWidth > 0 && displayHeight > 0) {
        commands += listOf(
            "cmd", "display", "set-user-preferred-display-mode",
            displayWidth.toString(), displayHeight.toString(), hz.toString(),
            "0", "false",
        )
    }
    return commands
}
