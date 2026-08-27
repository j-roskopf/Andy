package app.andy.desktop.service.mirror

import app.andy.desktop.service.CommandRunner
import app.andy.desktop.service.DesktopDeviceService
import app.andy.desktop.service.SdkLocator
import app.andy.desktop.service.ios.DesktopIosDeviceService
import app.andy.desktop.service.ios.DesktopIosMirrorEngine
import app.andy.model.WorkspaceState
import app.andy.service.CommandResult
import app.andy.service.MirrorBackend
import app.andy.service.MirrorBackendKind
import app.andy.service.MirrorEngine
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.service.MirrorRendererMode
import app.andy.service.MirrorSession
import app.andy.service.MirrorVideoConfig
import app.andy.service.RoutingMirrorEngine
import app.andy.service.WorkspaceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
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
        val devices = DesktopDeviceService(runner, SdkLocator(), store)
        val iosDevices = DesktopIosDeviceService(runner)
        val primary = RoutingMirrorEngine(
            DesktopMirrorEngine(runner, devices),
            DesktopIosMirrorEngine(iosDevices),
        )
        val pool = DesktopPopOutMirrorPool(
            primary = primary,
            newAndroid = { DesktopMirrorEngine(runner, devices) },
            newIos = { DesktopIosMirrorEngine(iosDevices) },
        )

        val first = pool.acquire("device-a")
        val second = pool.acquire("device-b")
        val firstAgain = pool.acquire("device-a")

        assertSame(first, firstAgain)
        assertNotSame(first, second)
        assertTrue(first is RoutingMirrorEngine)
    }

    @Test
    fun takeOverPrimaryAndroidMovesLiveEngineWithoutClearingItsSession() = runBlocking {
        val liveAndroid = TrackingSessionMirrorEngine()
        val primary = RoutingMirrorEngine(liveAndroid, TrackingSessionMirrorEngine())
        primary.connect("emulator-5554", MirrorVideoConfig())
        assertEquals("emulator-5554", primary.session.value?.serial)

        var androidFactoryCalls = 0
        val pool = DesktopPopOutMirrorPool(
            primary = primary,
            newAndroid = {
                androidFactoryCalls++
                TrackingSessionMirrorEngine()
            },
            newIos = { TrackingSessionMirrorEngine() },
        )

        val popOut = pool.takeOverPrimaryAndroid("emulator-5554")
        assertSame(popOut, pool.acquire("emulator-5554"))
        assertTrue(androidFactoryCalls >= 1, "Primary must receive a fresh Android engine")
        assertNull(primary.session.value, "Live must release the Android serial after take-over")
        assertEquals(
            "emulator-5554",
            liveAndroid.session.value?.serial,
            "Transferred engine must keep its live session",
        )

        // Live can connect to another device without touching the popped-out engine.
        primary.connect("emulator-5556", MirrorVideoConfig())
        assertEquals("emulator-5556", primary.session.value?.serial)
        assertEquals(
            "emulator-5554",
            liveAndroid.session.value?.serial,
            "Pop-out session must survive Live connecting elsewhere",
        )
        assertEquals(0, liveAndroid.disconnectCount, "Take-over must not disconnect the live scrcpy owner")
    }
}

/** Minimal MirrorEngine that retains session across connect for take-over tests. */
private class TrackingSessionMirrorEngine : MirrorEngine {
    var disconnectCount = 0
    override val session = MutableStateFlow<MirrorSession?>(null)
    override val frames = MutableStateFlow(MirrorFrame(1, 1, intArrayOf(0xff000000.toInt())))
    override val status = MutableStateFlow("ready")

    override suspend fun connect(serial: String, config: MirrorVideoConfig): CommandResult {
        session.value = MirrorSession(serial, config.rendererMode, MirrorBackend(MirrorBackendKind.NativeHardware))
        return CommandResult.success("connected")
    }

    override suspend fun disconnect(immediate: Boolean) {
        disconnectCount++
        session.value = null
    }

    override suspend fun sendInput(input: MirrorInput) = CommandResult.success()
    override suspend fun screenshot(serial: String): ByteArray? = null
}
