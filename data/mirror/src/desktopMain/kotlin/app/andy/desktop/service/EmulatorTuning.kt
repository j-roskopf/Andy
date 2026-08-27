package app.andy.desktop.service

/** Default guest vsync; matches Andy's Live maxFps ceiling. Override with ANDY_EMULATOR_VSYNC_RATE. */
const val DEFAULT_EMULATOR_VSYNC_RATE = 120

const val EMULATOR_VSYNC_RATE_ENV = "ANDY_EMULATOR_VSYNC_RATE"

/** Guest display refresh in Hz for `-vsync-rate` / `hw.lcd.vsync`. */
fun emulatorVsyncRate(env: (String) -> String? = System::getenv): Int {
    val override = env(EMULATOR_VSYNC_RATE_ENV)?.trim()?.toIntOrNull()
    return override?.takeIf { it > 0 } ?: DEFAULT_EMULATOR_VSYNC_RATE
}

/** Shell commands to raise guest peak/min refresh before scrcpy starts. */
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
