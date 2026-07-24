package app.andy.desktop

/**
 * Tracks iOS Simulator.app handoff: Andy stops mirroring while the device is shown in
 * Simulator, then resumes once that device window disappears.
 *
 * [seenWindow] must become true before a missing window counts as "closed", so a slow
 * Simulator launch does not bounce Live back immediately.
 */
internal data class ExternalSimulatorMirrorWatch(
    val targetId: String,
    val displayName: String,
    val seenWindow: Boolean = false,
)

/**
 * Advances handoff watches for one poll. Targets whose device window was seen and then
 * closed are dropped from the returned map.
 */
internal fun reconcileExternalSimulatorMirrors(
    watches: Map<String, ExternalSimulatorMirrorWatch>,
    hasVisibleWindow: (displayName: String) -> Boolean,
): Map<String, ExternalSimulatorMirrorWatch> {
    if (watches.isEmpty()) return watches
    val next = linkedMapOf<String, ExternalSimulatorMirrorWatch>()
    for ((id, watch) in watches) {
        val visible = hasVisibleWindow(watch.displayName)
        when {
            visible -> next[id] = watch.copy(seenWindow = true)
            !watch.seenWindow -> next[id] = watch
            // Window was shown, then closed → resume Andy mirroring for this target.
            else -> Unit
        }
    }
    return next
}
