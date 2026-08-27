package app.andy

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val displayDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.getDefault())

actual fun hostTimeZoneId(): String = ZoneId.systemDefault().id

actual fun formatDisplayDateTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "-"
    return displayDateTimeFormatter.format(
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()),
    )
}
