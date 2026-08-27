package app.andy.ui.controls

import app.andy.domain.parseSensorStatus
import app.andy.model.EmulatorSensor
import app.andy.service.CommandResult
import app.andy.service.DeviceService

/** Flat on table — gravity along −Z. */
val ACCEL_PRESET_FLAT: List<Float> = listOf(0f, 0f, 9.81f)

/** Portrait upright — gravity along −Y. */
val ACCEL_PRESET_PORTRAIT: List<Float> = listOf(0f, 9.81f, 0f)

suspend fun DeviceService.setSensor(
    serial: String,
    sensor: EmulatorSensor,
    values: List<Float>,
): CommandResult {
    val formatted = values.take(sensor.axes).joinToString(separator = ":") { formatSensorValue(it) }
    val result = emu(serial, listOf("sensor", "set", sensor.emuName, formatted))
    return if (result.emulatorConsoleOk()) {
        CommandResult.success("${sensor.name} = $formatted")
    } else {
        CommandResult.failure(result.stderr.ifBlank { result.stdout }.ifBlank { "sensor set failed" })
    }
}

suspend fun DeviceService.readSensors(serial: String): Map<String, List<Float>> {
    val result = emu(serial, listOf("sensor", "status"))
    if (!result.emulatorConsoleOk() && result.exitCode != 0) return emptyMap()
    return parseSensorStatus(result.stdout.ifBlank { result.stderr })
}

fun formatSensorValue(value: Float): String {
    val rounded = (value * 1000f).toInt() / 1000f
    val asInt = rounded.toInt()
    return if (rounded == asInt.toFloat()) asInt.toString() else rounded.toString()
}
