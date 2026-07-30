package app.andy.ui.controls

import app.andy.service.CommandResult
import app.andy.service.DeviceService

enum class EmulatorSensor(val emuName: String, val axes: Int) {
    Accelerometer("acceleration", 3),
    Gyroscope("gyroscope", 3),
    Magnetometer("magnetic-field", 3),
    Orientation("orientation", 3),
    Proximity("proximity", 1),
    Light("light", 1),
    Pressure("pressure", 1),
    Humidity("humidity", 1),
    Temperature("temperature", 1),
}

/** Flat on table — gravity along −Z. */
val ACCEL_PRESET_FLAT: List<Float> = listOf(0f, 0f, 9.81f)

/** Portrait upright — gravity along −Y. */
val ACCEL_PRESET_PORTRAIT: List<Float> = listOf(0f, 9.81f, 0f)

suspend fun DeviceService.setSensor(
    serial: String,
    sensor: EmulatorSensor,
    values: List<Float>,
): CommandResult {
    require(values.isNotEmpty()) { "sensor values required" }
    val joined = values.joinToString(":") { formatSensorValue(it) }
    val result = emu(serial, listOf("sensor", "set", sensor.emuName, joined))
    return if (result.emulatorConsoleOk()) {
        CommandResult.success("Sensor ${sensor.emuName}=$joined")
    } else {
        CommandResult.failure(
            result.stderr.ifBlank { result.stdout }.ifBlank { "sensor set failed" },
        )
    }
}

suspend fun DeviceService.readSensors(serial: String): Map<String, List<Float>> {
    val result = emu(serial, listOf("sensor", "status"))
    if (!result.emulatorConsoleOk() && result.exitCode != 0) return emptyMap()
    return parseSensorStatus(result.stdout.ifBlank { result.stderr })
}

fun parseSensorStatus(output: String): Map<String, List<Float>> {
    val map = linkedMapOf<String, List<Float>>()
    // Examples:
    // acceleration = 0:0:9.81
    // light: 100
    val linePattern = Regex("""^\s*([A-Za-z0-9._-]+)\s*[=:]\s*(.+?)\s*$""")
    for (line in output.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.equals("OK", ignoreCase = true)) continue
        if (trimmed.startsWith("KO:", ignoreCase = true)) continue
        val match = linePattern.matchEntire(trimmed) ?: continue
        val name = match.groupValues[1]
        val raw = match.groupValues[2].trim()
        val values = raw.split(':').mapNotNull { it.trim().toFloatOrNull() }
        if (values.isNotEmpty()) map[name] = values
    }
    return map
}

internal fun formatSensorValue(value: Float): String {
    val rounded = (value * 1000f).toInt() / 1000f
    val asInt = rounded.toInt()
    return if (rounded == asInt.toFloat()) asInt.toString() else rounded.toString()
}
