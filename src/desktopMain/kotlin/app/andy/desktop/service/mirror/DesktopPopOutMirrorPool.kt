package app.andy.desktop.service.mirror

import app.andy.desktop.service.CommandRunner
import app.andy.desktop.service.DesktopDeviceService
import app.andy.desktop.service.ios.DesktopIosDeviceService
import app.andy.desktop.service.ios.DesktopIosMirrorEngine
import app.andy.service.MirrorEngine
import app.andy.service.RoutingMirrorEngine

/**
 * Dedicated mirror engines for device pop-out windows, one per popped-out device serial.
 *
 * Each engine drives its own [GpuMirrorPipeline] (keyed by serial in
 * [app.andy.desktop.service.mirror.GpuMirrorSessions]), so multiple *different* devices mirror on
 * the GPU simultaneously. A pop-out of the device already shown in the main Live pane instead
 * reuses the shared primary [RoutingMirrorEngine] (see `Main.kt`), fanning a second presenter out
 * of the same decoder rather than opening a second engine.
 */
class DesktopPopOutMirrorPool(
    private val runner: CommandRunner,
    private val devices: DesktopDeviceService,
    private val iosDevices: DesktopIosDeviceService,
) {
    private val engines = mutableMapOf<String, RoutingMirrorEngine>()

    fun acquire(targetId: String): MirrorEngine =
        engines.getOrPut(targetId) {
            RoutingMirrorEngine(
                DesktopMirrorEngine(runner, devices),
                DesktopIosMirrorEngine(iosDevices),
            )
        }

    suspend fun release(targetId: String) {
        engines.remove(targetId)?.disconnect(immediate = true)
    }

    suspend fun releaseAll() {
        engines.keys.toList().forEach { release(it) }
    }
}
