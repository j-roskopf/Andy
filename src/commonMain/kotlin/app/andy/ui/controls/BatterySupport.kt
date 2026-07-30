package app.andy.ui.controls

import app.andy.service.CommandResult
import app.andy.service.DeviceService

enum class BatteryHealth(val dumpsysValue: String, val label: String) {
    Good("good", "Good"),
    Overheat("overheat", "Overheat"),
    Dead("dead", "Dead"),
    OverVoltage("overvoltage", "Over voltage"),
    Failure("failure", "Failure"),
    Cold("cold", "Cold"),
    Unknown("unknown", "Unknown"),
}

/** Thermal status codes for `cmd thermalservice override-status` (API 29+). */
enum class ThermalStatus(val code: Int, val label: String) {
    None(0, "None"),
    Light(1, "Light"),
    Moderate(2, "Moderate"),
    Severe(3, "Severe"),
    Critical(4, "Critical"),
    Emergency(5, "Emergency"),
    Shutdown(6, "Shutdown"),
}

suspend fun DeviceService.setBatteryLevel(serial: String, percent: Int): CommandResult {
    val level = percent.coerceIn(0, 100)
    val result = shell(serial, listOf("dumpsys", "battery", "set", "level", level.toString()))
    return if (result.isSuccess) {
        CommandResult.success("Battery level $level%")
    } else {
        CommandResult.failure(result.stderr.ifBlank { result.stdout }.ifBlank { "set battery level failed" })
    }
}

suspend fun DeviceService.setBatteryCharging(serial: String, charging: Boolean): CommandResult {
    return if (charging) {
        val ac = shell(serial, listOf("dumpsys", "battery", "set", "ac", "1"))
        val usb = shell(serial, listOf("dumpsys", "battery", "set", "usb", "1"))
        if (ac.isSuccess || usb.isSuccess) {
            CommandResult.success("Battery charging on")
        } else {
            CommandResult.failure(ac.stderr.ifBlank { usb.stderr }.ifBlank { "set charging failed" })
        }
    } else {
        shell(serial, listOf("dumpsys", "battery", "unplug")).let { result ->
            if (result.isSuccess) CommandResult.success("Battery unplugged")
            else CommandResult.failure(result.stderr.ifBlank { result.stdout }.ifBlank { "unplug failed" })
        }
    }
}

suspend fun DeviceService.setBatteryHealth(serial: String, health: BatteryHealth): CommandResult {
    val result = shell(serial, listOf("dumpsys", "battery", "set", "health", health.dumpsysValue))
    return if (result.isSuccess) {
        CommandResult.success("Battery health ${health.label}")
    } else {
        CommandResult.failure(result.stderr.ifBlank { result.stdout }.ifBlank { "set battery health failed" })
    }
}

suspend fun DeviceService.resetBattery(serial: String): CommandResult {
    val result = shell(serial, listOf("dumpsys", "battery", "reset"))
    return if (result.isSuccess) {
        CommandResult.success("Battery overrides reset")
    } else {
        CommandResult.failure(result.stderr.ifBlank { result.stdout }.ifBlank { "battery reset failed" })
    }
}

suspend fun DeviceService.setThermalStatus(serial: String, status: Int): CommandResult {
    val code = status.coerceIn(0, 6)
    val result = shell(serial, listOf("cmd", "thermalservice", "override-status", code.toString()))
    return if (result.isSuccess) {
        CommandResult.success("Thermal status $code")
    } else {
        CommandResult.failure(result.stderr.ifBlank { result.stdout }.ifBlank { "thermal override failed" })
    }
}

fun parseThermalStatus(output: String): String? {
    // cmd thermalservice get-status → "Thermal Status: 2"
    Regex("""Thermal Status:\s*(\d+)""", RegexOption.IGNORE_CASE)
        .find(output)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.let { code -> return ThermalStatus.entries.firstOrNull { it.code == code }?.label ?: code.toString() }
    // dumpsys thermalservice variants
    Regex("""(?:Current thermal status|mStatus|status)\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)
        .find(output)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.let { code -> return ThermalStatus.entries.firstOrNull { it.code == code }?.label ?: code.toString() }
    Regex("""(?:Current thermal status|status)\s*[:=]\s*([A-Za-z_]+)""", RegexOption.IGNORE_CASE)
        .find(output)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    return null
}
