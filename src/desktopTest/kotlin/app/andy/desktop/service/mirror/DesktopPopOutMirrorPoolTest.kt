package app.andy.desktop.service.mirror

import app.andy.desktop.service.CommandRunner
import app.andy.desktop.service.DesktopDeviceService
import app.andy.desktop.service.SdkLocator
import app.andy.desktop.service.ios.DesktopIosDeviceService
import app.andy.model.WorkspaceState
import app.andy.service.CommandResult
import app.andy.service.RoutingMirrorEngine
import app.andy.service.WorkspaceStore
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopPopOutMirrorPoolTest {
    @Test
    fun acquireReturnsDedicatedEnginePerTarget() {
        val runner = CommandRunner { _, _ -> CommandResult.success("") }
        val store = object : WorkspaceStore {
            override suspend fun load() = WorkspaceState()
            override suspend fun save(state: WorkspaceState) = Unit
        }
        val pool = DesktopPopOutMirrorPool(
            runner = runner,
            devices = DesktopDeviceService(runner, SdkLocator(), store),
            iosDevices = DesktopIosDeviceService(runner),
        )

        val first = pool.acquire("device-a")
        val second = pool.acquire("device-b")
        val firstAgain = pool.acquire("device-a")

        assertSame(first, firstAgain)
        assertNotSame(first, second)
        assertTrue(first is RoutingMirrorEngine)
    }
}
