package app.andy.service

import app.andy.model.BatteryHealth
import app.andy.model.EmulatorSensor
import app.andy.model.GeoFix
import app.andy.model.GsmDataType

interface EmulatorControls {
    suspend fun sendGeoFix(serial: String, fix: GeoFix): CommandResult
    suspend fun setSensor(serial: String, sensor: EmulatorSensor, values: List<Float>): CommandResult
    suspend fun setBatteryLevel(serial: String, percent: Int): CommandResult
    suspend fun setBatteryCharging(serial: String, charging: Boolean): CommandResult
    suspend fun setBatteryHealth(serial: String, health: BatteryHealth): CommandResult
    suspend fun resetBattery(serial: String): CommandResult
    suspend fun setThermalStatus(serial: String, status: Int): CommandResult
    suspend fun simulateIncomingCall(serial: String, number: String): CommandResult
    suspend fun sendSms(serial: String, number: String, message: String): CommandResult
    suspend fun setNetworkType(serial: String, type: GsmDataType): CommandResult
    suspend fun setDeviceLocale(
        serial: String,
        tag: String,
        allowFrameworkRestart: Boolean = false,
    ): LocaleChangeResult
}

data class LocaleChangeResult(
    val result: CommandResult,
    val method: LocaleMethod,
)

enum class LocaleMethod(val label: String) {
    CmdLocale("cmd locale set-locales"),
    SetpropRestart("setprop + am restart"),
    AppLocale("cmd locale set-app-locales"),
}
