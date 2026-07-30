package app.andy.ui.controls

import app.andy.service.CommandResult
import app.andy.service.DeviceService

/** `emu gsm data` registration / operator state. */
enum class GsmRegistration(val emuValue: String, val label: String) {
    Unregistered("unregistered", "Unregistered"),
    Home("home", "Home"),
    Roaming("roaming", "Roaming"),
    Searching("searching", "Searching"),
    Denied("denied", "Denied"),
}

/** `emu gsm data` radio access technology. */
enum class GsmDataType(val emuValue: String, val label: String) {
    Gprs("gprs", "GPRS"),
    Edge("edge", "EDGE"),
    Umts("umts", "UMTS"),
    Lte("lte", "LTE"),
    Nr("nr", "NR (5G)"),
}

suspend fun DeviceService.simulateIncomingCall(serial: String, number: String): CommandResult {
    val result = emu(serial, listOf("gsm", "call", number))
    return emuOkOrFail(result, "Incoming call $number")
}

suspend fun DeviceService.acceptCall(serial: String, number: String): CommandResult {
    val result = emu(serial, listOf("gsm", "accept", number))
    return emuOkOrFail(result, "Accepted $number")
}

suspend fun DeviceService.cancelCall(serial: String, number: String): CommandResult {
    val result = emu(serial, listOf("gsm", "cancel", number))
    return emuOkOrFail(result, "Cancelled $number")
}

/**
 * `emu sms send <number> <text>` — text is the rest of the line.
 * Pass the full message as a single trailing argv token (do not shell-quote).
 */
suspend fun DeviceService.sendSms(serial: String, number: String, message: String): CommandResult {
    val result = emu(serial, listOf("sms", "send", number, message))
    return emuOkOrFail(result, "SMS to $number")
}

suspend fun DeviceService.setGsmRegistration(serial: String, registration: GsmRegistration): CommandResult {
    val result = emu(serial, listOf("gsm", "data", registration.emuValue))
    return emuOkOrFail(result, "GSM ${registration.label}")
}

suspend fun DeviceService.setNetworkType(serial: String, type: GsmDataType): CommandResult {
    val result = emu(serial, listOf("gsm", "data", type.emuValue))
    return emuOkOrFail(result, "Network ${type.label}")
}

suspend fun DeviceService.setSignalStrength(serial: String, bars: Int): CommandResult {
    val profile = bars.coerceIn(0, 4)
    val result = emu(serial, listOf("gsm", "signal-profile", profile.toString()))
    return emuOkOrFail(result, "Signal $profile bars")
}

private fun emuOkOrFail(result: CommandResult, okLabel: String): CommandResult =
    if (result.emulatorConsoleOk()) {
        CommandResult.success(okLabel)
    } else {
        CommandResult.failure(
            result.stderr.ifBlank { result.stdout }.ifBlank { "$okLabel failed" },
        )
    }
