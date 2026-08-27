package app.andy.ui.controls

import app.andy.domain.parseGpxTrack
import app.andy.domain.parseKmlLineString
import app.andy.model.GeoFix
import app.andy.service.DeviceService
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

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

fun parseRouteFile(text: String, isKml: Boolean): List<GeoFix> =
    if (isKml) parseKmlLineString(text) else parseGpxTrack(text)
