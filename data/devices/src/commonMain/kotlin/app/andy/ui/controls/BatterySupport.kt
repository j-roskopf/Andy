package app.andy.ui.controls

import app.andy.model.BatteryHealth
import app.andy.model.ThermalStatus
import app.andy.service.CommandResult
import app.andy.service.DeviceService

suspend fun DeviceService.setBatteryLevel(serial: String, percent: Int): CommandResult {
    val level = percent.coerceIn(0, 100)
    val result = shell(serial, listOf("dumpsys", "battery", "set", "level", level.toString()))
    return if (result.isSuccess) {
        CommandResult.success("Battery level $level%")
    } else {
        CommandResult.failure(result.stderr.ifBlank { result.stdout }.ifBlank { "battery set failed" })
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
        CommandResult.failure(result.stderr.ifBlank { result.stdout }.ifBlank { "battery health set failed" })
    }
}

suspend fun DeviceService.resetBattery(serial: String): CommandResult {
    val result = shell(serial, listOf("dumpsys", "battery", "reset"))
    return if (result.isSuccess) {
        CommandResult.success("Battery reset")
    } else {
        CommandResult.failure(result.stderr.ifBlank { result.stdout }.ifBlank { "battery reset failed" })
    }
}

suspend fun DeviceService.setThermalStatus(serial: String, status: Int): CommandResult {
    val code = status.coerceIn(ThermalStatus.None.code, ThermalStatus.Shutdown.code)
    val result = shell(serial, listOf("cmd", "thermalservice", "override-status", code.toString()))
    return if (result.isSuccess) {
        CommandResult.success("Thermal status $code")
    } else {
        CommandResult.failure(result.stderr.ifBlank { result.stdout }.ifBlank { "thermal override failed" })
    }
}
