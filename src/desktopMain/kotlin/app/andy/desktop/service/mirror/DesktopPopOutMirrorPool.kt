package app.andy.desktop.service.mirror

import app.andy.service.MirrorEngine
import app.andy.service.RoutingMirrorEngine

/**
 * Dedicated mirror engines for device pop-out windows, one per popped-out device serial.
 *
 * Each engine drives its own [GpuMirrorPipeline] (keyed by serial in
 * [app.andy.desktop.service.mirror.GpuMirrorSessions]), so multiple *different* devices mirror on
 * the GPU simultaneously.
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

    fun acquire(targetId: String): MirrorEngine =
        engines.getOrPut(targetId) {
            RoutingMirrorEngine(newAndroid(), newIos())
        }

    /**
     * Moves Live's running Android mirror engine into the pop-out pool for [targetId] and installs
     * a fresh Android engine on the primary. The scrcpy process and GPU pipeline stay alive on the
     * transferred engine.
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
        engines.remove(targetId)?.disconnect(immediate = true)
    }

    suspend fun releaseAll() {
        engines.keys.toList().forEach { release(it) }
    }
}
