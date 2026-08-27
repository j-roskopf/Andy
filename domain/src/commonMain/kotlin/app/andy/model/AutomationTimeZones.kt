package app.andy.model

data class AutomationTimeZoneOption(
    val id: String,
    val label: String,
)

val AutomationTimeZoneOptions: List<AutomationTimeZoneOption> = listOf(
    AutomationTimeZoneOption("UTC", "UTC"),
    AutomationTimeZoneOption("America/New_York", "Eastern (US)"),
    AutomationTimeZoneOption("America/Chicago", "Central (US)"),
    AutomationTimeZoneOption("America/Denver", "Mountain (US)"),
    AutomationTimeZoneOption("America/Phoenix", "Arizona"),
    AutomationTimeZoneOption("America/Los_Angeles", "Pacific (US)"),
    AutomationTimeZoneOption("America/Anchorage", "Alaska"),
    AutomationTimeZoneOption("Pacific/Honolulu", "Hawaii"),
    AutomationTimeZoneOption("America/Toronto", "Toronto"),
    AutomationTimeZoneOption("America/Sao_Paulo", "São Paulo"),
    AutomationTimeZoneOption("Europe/London", "London"),
    AutomationTimeZoneOption("Europe/Paris", "Paris"),
    AutomationTimeZoneOption("Europe/Berlin", "Berlin"),
    AutomationTimeZoneOption("Europe/Amsterdam", "Amsterdam"),
    AutomationTimeZoneOption("Asia/Kolkata", "India"),
    AutomationTimeZoneOption("Asia/Singapore", "Singapore"),
    AutomationTimeZoneOption("Asia/Shanghai", "China"),
    AutomationTimeZoneOption("Asia/Tokyo", "Tokyo"),
    AutomationTimeZoneOption("Australia/Sydney", "Sydney"),
)

private val TimeZoneAliases: Map<String, String> = mapOf(
    "utc" to "UTC",
    "gmt" to "UTC",
    "z" to "UTC",
    "eastern" to "America/New_York",
    "est" to "America/New_York",
    "edt" to "America/New_York",
    "et" to "America/New_York",
    "us/eastern" to "America/New_York",
    "central" to "America/Chicago",
    "cst" to "America/Chicago",
    "cdt" to "America/Chicago",
    "ct" to "America/Chicago",
    "us/central" to "America/Chicago",
    "mountain" to "America/Denver",
    "mst" to "America/Denver",
    "mdt" to "America/Denver",
    "mt" to "America/Denver",
    "us/mountain" to "America/Denver",
    "pacific" to "America/Los_Angeles",
    "pst" to "America/Los_Angeles",
    "pdt" to "America/Los_Angeles",
    "pt" to "America/Los_Angeles",
    "us/pacific" to "America/Los_Angeles",
    "arizona" to "America/Phoenix",
    "alaska" to "America/Anchorage",
    "hawaii" to "Pacific/Honolulu",
    "hst" to "Pacific/Honolulu",
)

fun resolveAutomationTimeZoneId(raw: String, fallback: String = "UTC"): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return fallback
    AutomationTimeZoneOptions.firstOrNull { it.id.equals(trimmed, ignoreCase = true) }?.let { return it.id }
    TimeZoneAliases[trimmed.lowercase()]?.let { return it }
    return trimmed
}

fun automationTimeZoneLabel(id: String): String =
    AutomationTimeZoneOptions.firstOrNull { it.id.equals(id, ignoreCase = true) }?.label ?: id

fun automationTimeZonePickerOptions(currentId: String): List<AutomationTimeZoneOption> {
    val resolved = resolveAutomationTimeZoneId(currentId)
    if (AutomationTimeZoneOptions.any { it.id == resolved }) return AutomationTimeZoneOptions
    return listOf(AutomationTimeZoneOption(resolved, resolved)) + AutomationTimeZoneOptions
}

fun hour24ToClock(hour24: Int): Pair<Int, Boolean> {
    val hour = hour24.coerceIn(0, 23)
    val isPm = hour >= 12
    val hour12 = when {
        hour == 0 || hour == 12 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return hour12 to isPm
}

fun clockToHour24(hour12: Int, isPm: Boolean): Int {
    val hour = hour12.coerceIn(1, 12)
    return when {
        !isPm && hour == 12 -> 0
        isPm && hour != 12 -> hour + 12
        else -> hour
    }
}
