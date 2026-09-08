package app.andy.desktop.service.mirror

import app.andy.service.MirrorEngine
import app.andy.service.RoutingMirrorEngine

/**
 * Dedicated mirror engines for device pop-out windows and dock Live tabs, one per device serial.
 *
 * Each engine drives its own [GpuMirrorPipeline] (keyed by serial in
 * [app.andy.desktop.service.mirror.GpuMirrorSessions]), so multiple *different* devices mirror on
 * the GPU simultaneously. Consumers (pop-outs, dock Live tabs) share an engine for the same
 * [targetId] via refcounted [acquire] / [releaseHold].
 *
 * Popping out the device currently shown in Live **takes over** that Android [DesktopMirrorEngine]
 * (see [takeOverPrimaryAndroid]) so the running scrcpy session keeps feeding the pop-out while Live
 * gets a fresh engine for the next device. Sharing the primary [RoutingMirrorEngine] after pop-out
 * would black the window the moment Live connected elsewhere.
 *
 * Hold counts move synchronously on the caller thread so a dock Live pause → main Live → unpause
 * handoff cannot leave the pane stuck on "Connecting mirror…" (async release used to remove the
 * engine after [acquire] had already restored it, with no Compose invalidation to recover).
 */
class DesktopPopOutMirrorPool(
    private val primary: RoutingMirrorEngine,
    private val newAndroid: () -> MirrorEngine,
    private val newIos: () -> MirrorEngine,
) {
    private val lock = Any()
    private val engines = mutableMapOf<String, RoutingMirrorEngine>()
    private val holds = mutableMapOf<String, Int>()

    fun holdCount(targetId: String): Int = synchronized(lock) { holds[targetId] ?: 0 }

    fun acquire(targetId: String): MirrorEngine = synchronized(lock) {
        holds[targetId] = (holds[targetId] ?: 0) + 1
        engines.getOrPut(targetId) {
            RoutingMirrorEngine(newAndroid(), newIos())
        }
    }

    /** Returns the pooled engine for [targetId] without changing the hold count. */
    fun engine(targetId: String): MirrorEngine? = synchronized(lock) { engines[targetId] }

    /**
     * Moves Live's running Android mirror engine into the pop-out pool for [targetId] and installs
     * a fresh Android engine on the primary. The scrcpy process and GPU pipeline stay alive on the
     * transferred engine. Does not take a hold — callers must [acquire] (e.g. the pop-out window).
     */
    fun takeOverPrimaryAndroid(targetId: String): MirrorEngine = synchronized(lock) {
        engines[targetId]?.let { return it }
        val liveAndroid = primary.replaceAndroidEngine(newAndroid())
        (liveAndroid as? DesktopMirrorEngine)?.cancelPendingRelease()
        val popOut = RoutingMirrorEngine(liveAndroid, newIos())
        engines[targetId] = popOut
        popOut
    }

    /**
     * Drops one hold immediately. When this was the last hold, removes the engine from the pool and
     * returns it so the caller can [MirrorEngine.disconnect] off the UI thread. A no-op when [targetId]
     * is not held (stale release after an unpause re-acquire).
     */
    fun releaseHold(targetId: String): MirrorEngine? = synchronized(lock) {
        val current = holds[targetId] ?: return null
        val remaining = current - 1
        if (remaining > 0) {
            holds[targetId] = remaining
            return null
        }
        holds.remove(targetId)
        engines.remove(targetId)
    }

    suspend fun release(targetId: String) {
        releaseHold(targetId)?.disconnect(immediate = true)
    }

    suspend fun releaseAll() {
        val closing = synchronized(lock) {
            holds.clear()
            engines.values.toList().also { engines.clear() }
        }
        closing.forEach { it.disconnect(immediate = true) }
    }
}
