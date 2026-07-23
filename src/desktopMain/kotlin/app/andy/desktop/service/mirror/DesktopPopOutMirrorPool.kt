package app.andy.desktop.service.mirror

import app.andy.desktop.service.CommandRunner
import app.andy.desktop.service.DesktopDeviceService
import app.andy.desktop.service.ios.DesktopIosDeviceService
import app.andy.desktop.service.ios.DesktopIosMirrorEngine
import app.andy.service.MirrorEngine
import app.andy.service.RoutingMirrorEngine

/**
 * Dedicated mirror engines for device pop-out windows. The primary [RoutingMirrorEngine]
 * in [app.andy.desktop.service.createDesktopServices] owns the shared Metal presenter;
 * secondary pop-outs use CPU presentation so multiple devices can mirror at once.
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
