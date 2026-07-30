package app.andy.domain

import app.andy.model.BugAction
import app.andy.model.BugReport
import app.andy.model.InvestigationEvent
import app.andy.model.InvestigationEventKind
import app.andy.model.InvestigationEventSeverity
import app.andy.model.InvestigationInlinePayload
import app.andy.model.InvestigationTimeline
import app.andy.model.InvestigationTimelineSchemaVersion

/**
 * Lazy v1 → investigation adapter. Never rewrites on-disk reports; builds an
 * in-memory timeline from legacy actions (and optionally approximate log lines).
 */
fun investigationTimelineFor(
    report: BugReport,
    loadedTimeline: InvestigationTimeline?,
    logcatText: String = "",
): InvestigationTimeline {
    if (loadedTimeline != null) return loadedTimeline
    return migrateV1BugReportToTimeline(report, logcatText.lines().filter { it.isNotBlank() })
}

fun migrateV1BugReportToTimeline(
    report: BugReport,
    logcatLines: List<String> = emptyList(),
): InvestigationTimeline {
    val actionEvents = report.actions.map { it.toInvestigationEvent() }
    val logEvents = logcatLines.mapIndexed { index, line ->
        InvestigationEvent(
            id = "legacy-log-$index",
            atMillis = approximateLogMillis(report, index, logcatLines.size),
            kind = InvestigationEventKind.LogLine,
            summary = line.take(160),
            severity = severityForLogLine(line),
            detail = line,
            inline = InvestigationInlinePayload(
                text = line,
                approximatelyTimed = true,
            ),
            correlationIds = mapOf("source" to "legacy-logcat"),
        )
    }
    val origin = report.windowStartedAtMillis
    val ended = report.windowEndedAtMillis.takeIf { it >= origin } ?: report.capturedAtMillis
    return InvestigationTimeline(
        schemaVersion = InvestigationTimelineSchemaVersion,
        originMillis = origin,
        endedAtMillis = ended,
        events = (actionEvents + logEvents).sortedBy(InvestigationEvent::atMillis),
    )
}

fun BugAction.toInvestigationEvent(): InvestigationEvent {
    val kind = when (kind.lowercase()) {
        "input", "nav", "action" -> InvestigationEventKind.Action
        "screen", "screen_transition", "activity" -> InvestigationEventKind.ScreenTransition
        "note", "marker", "user" -> InvestigationEventKind.UserMarker
        "log" -> InvestigationEventKind.LogLine
        "crash" -> InvestigationEventKind.Crash
        "anr" -> InvestigationEventKind.Anr
        "network" -> InvestigationEventKind.NetworkExchange
        "metric", "metrics" -> InvestigationEventKind.MetricSample
        "hierarchy" -> InvestigationEventKind.HierarchySnapshot
        "screenshot" -> InvestigationEventKind.Screenshot
        "video" -> InvestigationEventKind.VideoMarker
        else -> InvestigationEventKind.Action
    }
    return InvestigationEvent(
        id = id,
        atMillis = timestampMillis,
        kind = kind,
        summary = label,
        detail = detail,
        severity = InvestigationEventSeverity.Info,
        correlationIds = mapOf("actionId" to id, "legacyKind" to this.kind),
        inline = InvestigationInlinePayload(text = detail),
    )
}

/** Convert timeline action-like events back to [BugAction] for legacy overlay/export paths. */
fun InvestigationEvent.toBugActionOrNull(): BugAction? {
    if (kind != InvestigationEventKind.Action &&
        kind != InvestigationEventKind.ScreenTransition &&
        kind != InvestigationEventKind.UserMarker
    ) {
        return null
    }
    val legacyKind = correlationIds["legacyKind"] ?: when (kind) {
        InvestigationEventKind.Action -> "action"
        InvestigationEventKind.ScreenTransition -> "screen"
        InvestigationEventKind.UserMarker -> "note"
        else -> "action"
    }
    return BugAction(
        id = id,
        timestampMillis = atMillis,
        kind = legacyKind,
        label = summary,
        detail = detail ?: inline?.text,
    )
}

fun InvestigationTimeline.legacyActions(): List<BugAction> =
    events.mapNotNull(InvestigationEvent::toBugActionOrNull)

private fun approximateLogMillis(report: BugReport, index: Int, total: Int): Long {
    val start = report.windowStartedAtMillis
    val end = report.windowEndedAtMillis.takeIf { it >= start } ?: report.capturedAtMillis
    if (total <= 1 || end <= start) return end
    val progress = index.toDouble() / (total - 1).coerceAtLeast(1)
    return start + ((end - start) * progress).toLong()
}

private fun severityForLogLine(line: String): InvestigationEventSeverity {
    val upper = line.uppercase()
    return when {
        " E/" in upper || upper.contains(" FATAL ") || upper.contains("ANDROIDRUNTIME") ->
            InvestigationEventSeverity.Error
        " W/" in upper -> InvestigationEventSeverity.Warning
        else -> InvestigationEventSeverity.Info
    }
}
