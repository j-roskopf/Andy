package app.andy.ui.bugs

import app.andy.model.DefaultSelectionRadiusMillis
import app.andy.model.InvestigationEvent

/** Hard cap on how many timeline events one "Investigate selection…" action carries. */
private const val MaxSelectedEvents = 40

/**
 * The slice of the currently filtered timeline a contextual "Investigate selection…" action
 * covers: every visible event within [radiusMillis] of the scrub position, closest first when
 * the window holds more than [limit] events.
 */
internal fun investigationSelectionAround(
    events: List<InvestigationEvent>,
    centerMillis: Long,
    radiusMillis: Long = DefaultSelectionRadiusMillis,
    limit: Int = MaxSelectedEvents,
): List<InvestigationEvent> {
    val inWindow = events.filter { event ->
        val distance = event.atMillis - centerMillis
        distance >= -radiusMillis && distance <= radiusMillis
    }
    if (inWindow.size <= limit) return inWindow
    val kept = inWindow
        .sortedBy { event -> kotlin.math.abs(event.atMillis - centerMillis) }
        .take(limit)
        .mapTo(mutableSetOf()) { it.id }
    return inWindow.filter { it.id in kept }
}

/** One-line description of a selection, shown in the confirmation sheet's prompt. */
internal fun investigationSelectionSummary(events: List<InvestigationEvent>): String {
    if (events.isEmpty()) return "No events in the selected window."
    val kinds = events.groupingBy { it.kind }.eachCount()
        .entries.sortedByDescending { it.value }
        .joinToString(", ") { (kind, count) -> "$kind×$count" }
    return "${events.size} event(s): $kinds"
}
