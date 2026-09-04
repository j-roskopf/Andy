package app.andy.desktop.service.mirror

import app.andy.service.MirrorEngine
import app.andy.service.RoutingMirrorEngine

/**
 * Dedicated mirror engines for device pop-out windows and dock Live tabs, one per device serial.
 *
 * Each engine drives its own [GpuMirrorPipeline] (keyed by serial in
 * [app.andy.desktop.service.mirror.GpuMirrorSessions]), so multiple *different* devices mirror on
 * the GPU simultaneously. Consumers (pop-outs, dock Live tabs) share an engine for the same
 * [targetId] via refcounted [acquire] / [release].
 *
 * Popping out the device currently shown in Live **takes over** that Android [DesktopMirrorEngine]
 * (see [takeOverPrimaryAndroid]) so the running scrcpy session keeps feeding the pop-out while Live
 * gets a fresh engine for the next device. Sharing the primary [RoutingMirrorEngine] after pop-out
 * would black the window the moment Live connected elsewhere.
 */
class DesktopPopOutMirrorPool(
    private val primary: RoutingMirrorEngine,
    private val newAndroid: () -> MirrorEngine,
    private val newIos: () -> MirrorEngine,
) {
    private val engines = mutableMapOf<String, RoutingMirrorEngine>()
    private val holds = mutableMapOf<String, Int>()

    fun holdCount(targetId: String): Int = holds[targetId] ?: 0

    fun acquire(targetId: String): MirrorEngine {
        holds[targetId] = holdCount(targetId) + 1
        return engines.getOrPut(targetId) {
            RoutingMirrorEngine(newAndroid(), newIos())
        }
    }

    /** Returns the pooled engine for [targetId] without changing the hold count. */
    fun engine(targetId: String): MirrorEngine? = engines[targetId]

    /**
     * Moves Live's running Android mirror engine into the pop-out pool for [targetId] and installs
     * a fresh Android engine on the primary. The scrcpy process and GPU pipeline stay alive on the
     * transferred engine. Does not take a hold — callers must [acquire] (e.g. the pop-out window).
     */
    fun takeOverPrimaryAndroid(targetId: String): MirrorEngine {
        engines[targetId]?.let { return it }
        val liveAndroid = primary.replaceAndroidEngine(newAndroid())
        (liveAndroid as? DesktopMirrorEngine)?.cancelPendingRelease()
        val popOut = RoutingMirrorEngine(liveAndroid, newIos())
        engines[targetId] = popOut
        return popOut
    }

    suspend fun release(targetId: String) {
        val remaining = holdCount(targetId) - 1
        if (remaining > 0) {
            holds[targetId] = remaining
            return
        }
        holds.remove(targetId)
        engines.remove(targetId)?.disconnect(immediate = true)
    }

    suspend fun releaseAll() {
        holds.clear()
        engines.keys.toList().forEach { id ->
            engines.remove(id)?.disconnect(immediate = true)
        }
    }
}
