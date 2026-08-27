package app.andy.ui.controls

import app.andy.service.CommandResult

/** `adb emu` often prints `KO: …` while still exiting 0. Treat that as failure. */
fun CommandResult.emulatorConsoleOk(): Boolean {
    if (exitCode != 0) return false
    val text = "$stdout\n$stderr"
    return !text.contains("KO:", ignoreCase = true)
}

internal fun CommandResult.emuOkOrFail(okLabel: String): CommandResult =
    if (emulatorConsoleOk()) {
        CommandResult.success(okLabel)
    } else {
        CommandResult.failure(
            stderr.ifBlank { stdout }.ifBlank { "$okLabel failed" },
        )
    }
