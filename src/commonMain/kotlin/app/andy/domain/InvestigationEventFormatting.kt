package app.andy.domain

import app.andy.model.InvestigationEvent
import app.andy.model.InvestigationEventKind

/**
 * Kind-specific summary rows for the investigation timeline's detail pane (§3). Pure and
 * UI-agnostic so this is covered by common tests instead of requiring a Compose screenshot.
 */
fun InvestigationEvent.detailFields(): List<Pair<String, String>> = buildList {
    add("kind" to kind.name)
    add("time" to atMillis.toString())
    sourceTimestampMillis?.let { add("device time" to "$it (approximate)") }
    val payload = inline
    when (kind) {
        InvestigationEventKind.NetworkExchange -> {
            payload?.method?.let { add("method" to it) }
            payload?.url?.let { add("url" to it) }
            payload?.statusCode?.let { add("status" to it.toString()) }
            payload?.durationMillis?.let { add("duration" to "${it}ms") }
            payload?.tlsStatus?.let { add("tls" to it) }
            payload?.error?.let { add("error" to it) }
        }
        InvestigationEventKind.ProxyWarning -> {
            payload?.text?.let { add("message" to it) }
        }
        InvestigationEventKind.MetricSample -> {
            payload?.cpuPercent?.let { add("cpu" to "$it%") }
            payload?.memoryMb?.let { add("memory" to "${it}MB") }
            payload?.fps?.let { add("fps" to it.toString()) }
            payload?.batteryPercent?.let { add("battery" to "$it%") }
            payload?.thermalStatus?.let { add("thermal" to it) }
        }
        InvestigationEventKind.Crash, InvestigationEventKind.Anr -> {
            payload?.packageName?.let { add("package" to it) }
            payload?.crashKind?.let { add("crash kind" to it) }
        }
        InvestigationEventKind.HierarchySnapshot -> {
            payload?.hierarchySource?.let { add("source" to it) }
            payload?.packageName?.let { add("package" to it) }
            val width = payload?.displayWidth
            val height = payload?.displayHeight
            if (width != null && height != null) add("display" to "${width}x$height")
            payload?.nodeCount?.let { add("nodes" to it.toString()) }
        }
        InvestigationEventKind.Screenshot -> {
            val width = payload?.imageWidth
            val height = payload?.imageHeight
            if (width != null && height != null) add("image" to "${width}x$height")
        }
        InvestigationEventKind.LogLine -> {
            payload?.tag?.let { add("tag" to it) }
            payload?.level?.let { add("level" to it) }
        }
        InvestigationEventKind.Action, InvestigationEventKind.ScreenTransition, InvestigationEventKind.UserMarker,
        InvestigationEventKind.VideoMarker,
        -> Unit
    }
    detail?.takeIf { it.isNotBlank() && it != summary }?.let { add("detail" to it) }
    correlationIds.forEach { (key, value) -> add(key to value) }
    payloadRef?.let { add("sidecar" to it.relativePath) }
}

/** Compact 3-5 char tag for the timeline row, grouped the same way as the filter pills. */
fun InvestigationEventKind.shortTag(): String = when (this) {
    InvestigationEventKind.Action -> "ACT"
    InvestigationEventKind.ScreenTransition -> "NAV"
    InvestigationEventKind.UserMarker -> "MARK"
    InvestigationEventKind.VideoMarker -> "VID"
    InvestigationEventKind.LogLine -> "LOG"
    InvestigationEventKind.NetworkExchange -> "NET"
    InvestigationEventKind.ProxyWarning -> "NET!"
    InvestigationEventKind.MetricSample -> "PERF"
    InvestigationEventKind.Crash -> "CRASH"
    InvestigationEventKind.Anr -> "ANR"
    InvestigationEventKind.HierarchySnapshot -> "TREE"
    InvestigationEventKind.Screenshot -> "SHOT"
}
