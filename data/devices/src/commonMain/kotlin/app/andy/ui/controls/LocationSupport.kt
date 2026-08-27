package app.andy.ui.controls

import app.andy.model.GeoFix
import app.andy.service.CommandResult
import app.andy.service.DeviceService
import kotlin.math.roundToInt

/**
 * Emulator console: `geo fix <longitude> <latitude> [altitude]`.
 * Longitude comes first — the classic argument-order bug.
 */
suspend fun DeviceService.sendGeoFix(serial: String, fix: GeoFix): CommandResult {
    val args = buildList {
        add("geo")
        add("fix")
        add(formatGeoCoordinate(fix.longitude))
        add(formatGeoCoordinate(fix.latitude))
        fix.altitudeMeters?.let { add(formatGeoCoordinate(it)) }
    }
    val result = emu(serial, args)
    return if (result.emulatorConsoleOk()) {
        CommandResult.success(
            "Geo fix ${formatGeoCoordinate(fix.latitude)}, ${formatGeoCoordinate(fix.longitude)}" +
                (fix.altitudeMeters?.let { " alt=${formatGeoCoordinate(it)}" } ?: ""),
        )
    } else {
        CommandResult.failure(
            result.stderr.ifBlank { result.stdout }.ifBlank { "geo fix failed" },
        )
    }
}

/** Locale-independent decimal formatting for the emulator console. */
fun formatGeoCoordinate(value: Double): String {
    val rounded = (value * 1_000_000.0).roundToInt() / 1_000_000.0
    val asLong = rounded.toLong()
    return if (rounded == asLong.toDouble()) asLong.toString() else rounded.toString()
}
