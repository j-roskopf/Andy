package app.andy.domain

import app.andy.model.BugReport
import app.andy.model.InvestigationEvent
import app.andy.model.InvestigationEventKind
import app.andy.model.InvestigationTimeline

internal const val InvestigationEventHighlightWindowMillis = BugActionHighlightWindowMillis

/** Filter set for the investigation timeline pane. Empty = show all kinds. */
data class InvestigationTimelineFilters(
    val kinds: Set<InvestigationEventKind> = emptySet(),
) {
    fun accepts(event: InvestigationEvent): Boolean =
        kinds.isEmpty() || event.kind in kinds

    fun withToggle(kind: InvestigationEventKind): InvestigationTimelineFilters {
        val next = kinds.toMutableSet()
        if (!next.add(kind)) next.remove(kind)
        return copy(kinds = next)
    }
}

fun InvestigationTimeline.filtered(filters: InvestigationTimelineFilters): List<InvestigationEvent> =
    events.filter(filters::accepts)

/**
 * Index of the nearest visible event within the highlight window, or -1.
 * Mirrors [activeBugActionIndex] for the generalized timeline.
 */
fun activeInvestigationEventIndex(
    events: List<InvestigationEvent>,
    playbackMillis: Long,
    windowMillis: Long = InvestigationEventHighlightWindowMillis,
): Int {
    return events
        .mapIndexed { index, event -> index to kotlin.math.abs(event.atMillis - playbackMillis) }
        .filter { (_, distance) -> distance <= windowMillis }
        .minByOrNull { (_, distance) -> distance }
        ?.first
        ?: -1
}

fun activeInvestigationEvent(
    events: List<InvestigationEvent>,
    playbackMillis: Long,
    windowMillis: Long = InvestigationEventHighlightWindowMillis,
): InvestigationEvent? {
    val index = activeInvestigationEventIndex(events, playbackMillis, windowMillis)
    return events.getOrNull(index)
}

/** Events whose timestamps fall inside an inclusive time window. */
fun InvestigationTimeline.eventsInWindow(startMillis: Long, endMillis: Long): List<InvestigationEvent> {
    val lo = minOf(startMillis, endMillis)
    val hi = maxOf(startMillis, endMillis)
    return events.filter { it.atMillis in lo..hi }
}

/**
 * Prefer events near [focusedEventId]; otherwise take the window around [centerMillis].
 */
fun InvestigationTimeline.selectEvidenceWindow(
    focusedEventId: String?,
    centerMillis: Long,
    windowRadiusMillis: Long,
    maxEvents: Int = 200,
): List<InvestigationEvent> {
    val focused = focusedEventId?.let { id -> events.firstOrNull { it.id == id } }
    val center = focused?.atMillis ?: centerMillis
    val start = center - windowRadiusMillis
    val end = center + windowRadiusMillis
    val inWindow = eventsInWindow(start, end)
    if (inWindow.size <= maxEvents) return inWindow
    // Keep focused event and nearest neighbors by time distance.
    return inWindow
        .sortedBy { kotlin.math.abs(it.atMillis - center) }
        .take(maxEvents)
        .sortedBy(InvestigationEvent::atMillis)
}

/** Playback millis for a video frame — same axis as [bugPlaybackMillis]. */
fun investigationPlaybackMillis(report: BugReport, frameIndex: Int, frameCount: Int): Long =
    bugPlaybackMillis(report, frameIndex, frameCount)

/**
 * Inverse of [investigationPlaybackMillis] — the frame index whose playback millis is closest to
 * [targetMillis]. Lets the timeline pane seek the video scrubber when an event is clicked.
 */
fun nearestBugFrameIndex(report: BugReport, targetMillis: Long, frameCount: Int): Int {
    if (frameCount <= 0) return 0
    val timestamps = report.videoFrameTimestampsMillis
    if (timestamps.isNotEmpty()) {
        return timestamps.indices.minByOrNull { index -> kotlin.math.abs(timestamps[index] - targetMillis) } ?: 0
    }
    val start = report.videoStartedAtMillis
    val end = report.videoEndedAtMillis
    if (start != null && end != null && end > start && frameCount > 1) {
        val progress = ((targetMillis - start).toDouble() / (end - start)).coerceIn(0.0, 1.0)
        return (progress * (frameCount - 1)).toInt().coerceIn(0, frameCount - 1)
    }
    return 0
}

/** Action-like events used for pointer overlays during replay. */
fun InvestigationTimeline.actionEventsForOverlay(): List<app.andy.model.BugAction> =
    events.mapNotNull { event ->
        if (event.kind == InvestigationEventKind.Action ||
            event.kind == InvestigationEventKind.UserMarker ||
            event.kind == InvestigationEventKind.ScreenTransition
        ) {
            event.toBugActionOrNull()
        } else {
            null
        }
    }
