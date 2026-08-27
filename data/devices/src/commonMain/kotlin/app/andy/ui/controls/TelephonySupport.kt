package app.andy.ui.controls

import app.andy.model.GsmDataType
import app.andy.model.GsmRegistration
import app.andy.service.CommandResult
import app.andy.service.DeviceService

suspend fun DeviceService.simulateIncomingCall(serial: String, number: String): CommandResult {
    val result = emu(serial, listOf("gsm", "call", number))
    return result.emuOkOrFail("Incoming call $number")
}

suspend fun DeviceService.acceptCall(serial: String, number: String): CommandResult {
    val result = emu(serial, listOf("gsm", "accept", number))
    return result.emuOkOrFail("Accepted $number")
}

suspend fun DeviceService.cancelCall(serial: String, number: String): CommandResult {
    val result = emu(serial, listOf("gsm", "cancel", number))
    return result.emuOkOrFail("Cancelled $number")
}

suspend fun DeviceService.sendSms(serial: String, number: String, message: String): CommandResult {
    val result = emu(serial, listOf("sms", "send", number, message))
    return result.emuOkOrFail("SMS to $number")
}

suspend fun DeviceService.setGsmRegistration(serial: String, registration: GsmRegistration): CommandResult {
    val result = emu(serial, listOf("gsm", "data", registration.emuValue))
    return result.emuOkOrFail("GSM ${registration.label}")
}

suspend fun DeviceService.setNetworkType(serial: String, type: GsmDataType): CommandResult {
    val result = emu(serial, listOf("gsm", "data", type.emuValue))
    return result.emuOkOrFail("Network ${type.label}")
}

suspend fun DeviceService.setSignalStrength(serial: String, bars: Int): CommandResult {
    val profile = bars.coerceIn(0, 4)
    val result = emu(serial, listOf("gsm", "signal-profile", profile.toString()))
    return result.emuOkOrFail("Signal $profile bars")
}
