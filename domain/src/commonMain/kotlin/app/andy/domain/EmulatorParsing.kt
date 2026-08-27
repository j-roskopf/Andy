package app.andy.domain

import app.andy.model.GeoFix
import app.andy.model.ThermalStatus

fun parseThermalStatus(output: String): String? {
    Regex("""Thermal Status:\s*(\d+)""", RegexOption.IGNORE_CASE)
        .find(output)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.let { code -> return ThermalStatus.entries.firstOrNull { it.code == code }?.label ?: code.toString() }
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

fun parseSensorStatus(output: String): Map<String, List<Float>> {
    val map = linkedMapOf<String, List<Float>>()
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
