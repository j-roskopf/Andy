package app.andy.model

data class GeoFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
)
