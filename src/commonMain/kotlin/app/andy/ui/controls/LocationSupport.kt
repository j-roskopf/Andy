package app.andy.ui.controls

import app.andy.service.CommandResult
import app.andy.service.DeviceService
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

data class GeoFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
)

data class GeoPreset(
    val label: String,
    val fix: GeoFix,
)

val GEO_PRESETS: List<GeoPreset> = listOf(
    GeoPreset("Null Island", GeoFix(0.0, 0.0, 0.0)),
    GeoPreset("San Francisco", GeoFix(37.7749, -122.4194)),
    GeoPreset("London", GeoFix(51.5074, -0.1278)),
    GeoPreset("Tokyo", GeoFix(35.6762, 139.6503)),
)

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

/** Emit the index of each applied point; cancel the collector to stop. */
fun DeviceService.playRoute(
    serial: String,
    points: List<GeoFix>,
    intervalMillis: Long,
): Flow<Int> = flow {
    for ((index, point) in points.withIndex()) {
        if (!currentCoroutineContext().isActive) return@flow
        val result = sendGeoFix(serial, point)
        if (!result.isSuccess) {
            emit(index)
            return@flow
        }
        emit(index)
        if (index < points.lastIndex) {
            delay(intervalMillis.coerceAtLeast(50L))
        }
    }
}

fun parseGpxTrack(xml: String): List<GeoFix> {
    val points = mutableListOf<GeoFix>()
    val trkpt = Regex(
        """<trkpt\s+([^>]+)>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    val latAttr = Regex("""lat\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    val lonAttr = Regex("""lon\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    val eleTag = Regex("""<ele>\s*([^<]+)\s*</ele>""", RegexOption.IGNORE_CASE)
    for (match in trkpt.findAll(xml)) {
        val attrs = match.groupValues[1]
        val lat = latAttr.find(attrs)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: continue
        val lon = lonAttr.find(attrs)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: continue
        val blockEnd = xml.indexOf("</trkpt>", match.range.last, ignoreCase = true)
        val block = if (blockEnd > match.range.last) {
            xml.substring(match.range.first, blockEnd)
        } else {
            attrs
        }
        val alt = eleTag.find(block)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        points += GeoFix(latitude = lat, longitude = lon, altitudeMeters = alt)
    }
    return points
}

fun parseKmlLineString(xml: String): List<GeoFix> {
    val coordsBlock = Regex(
        """<coordinates>\s*([\s\S]*?)\s*</coordinates>""",
        RegexOption.IGNORE_CASE,
    ).find(xml)?.groupValues?.getOrNull(1) ?: return emptyList()
    return coordsBlock
        .trim()
        .split(Regex("""\s+"""))
        .mapNotNull { token ->
            val parts = token.split(',')
            if (parts.size < 2) return@mapNotNull null
            val lon = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            val alt = parts.getOrNull(2)?.toDoubleOrNull()
            GeoFix(latitude = lat, longitude = lon, altitudeMeters = alt)
        }
}

/** Locale-independent decimal formatting for the emulator console. */
internal fun formatGeoCoordinate(value: Double): String {
    val rounded = (value * 1_000_000.0).roundToInt() / 1_000_000.0
    val asLong = rounded.toLong()
    return if (rounded == asLong.toDouble()) asLong.toString() else rounded.toString()
}
