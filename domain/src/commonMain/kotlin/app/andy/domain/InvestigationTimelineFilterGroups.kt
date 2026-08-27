package app.andy.domain

import app.andy.model.InvestigationEventKind

/**
 * UI-facing grouping over [InvestigationEventKind] for the investigation timeline pane's filter
 * pills (§3). Kept separate from [InvestigationTimelineFilters] so the underlying filter model
 * stays a plain per-kind set while the pane only ever toggles whole groups at once.
 */
enum class InvestigationFilterGroup(val label: String, val kinds: Set<InvestigationEventKind>) {
    Actions(
        "Actions",
        setOf(
            InvestigationEventKind.Action,
            InvestigationEventKind.ScreenTransition,
            InvestigationEventKind.UserMarker,
            InvestigationEventKind.VideoMarker,
        ),
    ),
    Logs("Logs", setOf(InvestigationEventKind.LogLine)),
    Network("Network", setOf(InvestigationEventKind.NetworkExchange, InvestigationEventKind.ProxyWarning)),
    Metrics("Metrics", setOf(InvestigationEventKind.MetricSample)),
    Crashes("Crashes", setOf(InvestigationEventKind.Crash, InvestigationEventKind.Anr)),
    Hierarchy("Hierarchy", setOf(InvestigationEventKind.HierarchySnapshot)),
    Screenshots("Screenshots", setOf(InvestigationEventKind.Screenshot)),
    ;

    companion object {
        val AllKinds: Set<InvestigationEventKind> = entries.flatMap { it.kinds }.toSet()
    }
}

/** True when any of [group]'s kinds are visible under [filters] (empty set = show everything). */
fun InvestigationTimelineFilters.groupActive(group: InvestigationFilterGroup): Boolean =
    kinds.isEmpty() || group.kinds.any { it in kinds }

/**
 * Toggles a whole filter group. Starting from "show everything" (empty [InvestigationTimelineFilters.kinds]),
 * the first click narrows down to just that group; later clicks add/remove other groups from the
 * explicit set. Ending up with every group (or none) selected collapses back to the empty
 * "show everything" state.
 */
fun InvestigationTimelineFilters.withGroupToggled(group: InvestigationFilterGroup): InvestigationTimelineFilters {
    if (kinds.isEmpty()) return copy(kinds = group.kinds)
    val next = kinds.toMutableSet()
    if (group.kinds.all { it in next }) {
        next -= group.kinds
    } else {
        next += group.kinds
    }
    return copy(kinds = if (next.isEmpty() || next.size == InvestigationFilterGroup.AllKinds.size) emptySet() else next)
}
