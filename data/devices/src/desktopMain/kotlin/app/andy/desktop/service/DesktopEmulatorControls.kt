package app.andy.desktop.service

import app.andy.model.BatteryHealth
import app.andy.model.EmulatorSensor
import app.andy.model.GeoFix
import app.andy.model.GsmDataType
import app.andy.service.AppService
import app.andy.service.CommandResult
import app.andy.service.DeviceService
import app.andy.service.EmulatorControls
import app.andy.service.LocaleChangeResult
import app.andy.ui.controls.resetBattery
import app.andy.ui.controls.sendGeoFix
import app.andy.ui.controls.sendSms
import app.andy.ui.controls.setBatteryCharging
import app.andy.ui.controls.setBatteryHealth
import app.andy.ui.controls.setBatteryLevel
import app.andy.ui.controls.setDeviceLocale
import app.andy.ui.controls.setNetworkType
import app.andy.ui.controls.setSensor
import app.andy.ui.controls.setThermalStatus
import app.andy.ui.controls.simulateIncomingCall

class DesktopEmulatorControls(
    private val devices: DeviceService,
    private val apps: AppService,
) : EmulatorControls {
    override suspend fun sendGeoFix(serial: String, fix: GeoFix): CommandResult =
        devices.sendGeoFix(serial, fix)

    override suspend fun setSensor(serial: String, sensor: EmulatorSensor, values: List<Float>): CommandResult =
        devices.setSensor(serial, sensor, values)

    override suspend fun setBatteryLevel(serial: String, percent: Int): CommandResult =
        devices.setBatteryLevel(serial, percent)

    override suspend fun setBatteryCharging(serial: String, charging: Boolean): CommandResult =
        devices.setBatteryCharging(serial, charging)

    override suspend fun setBatteryHealth(serial: String, health: BatteryHealth): CommandResult =
        devices.setBatteryHealth(serial, health)

    override suspend fun resetBattery(serial: String): CommandResult =
        devices.resetBattery(serial)

    override suspend fun setThermalStatus(serial: String, status: Int): CommandResult =
        devices.setThermalStatus(serial, status)

    override suspend fun simulateIncomingCall(serial: String, number: String): CommandResult =
        devices.simulateIncomingCall(serial, number)

    override suspend fun sendSms(serial: String, number: String, message: String): CommandResult =
        devices.sendSms(serial, number, message)

    override suspend fun setNetworkType(serial: String, type: GsmDataType): CommandResult =
        devices.setNetworkType(serial, type)

    override suspend fun setDeviceLocale(
        serial: String,
        tag: String,
        allowFrameworkRestart: Boolean,
    ): LocaleChangeResult =
        devices.setDeviceLocale(serial, tag, apps = apps, allowFrameworkRestart = allowFrameworkRestart)
}
