package app.andy.service

import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.IosTargetState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RoutingMirrorEngineTest {
    @Test
    fun connectToIosDisconnectsAndroidFirst() = runBlocking {
        val android = TrackingMirrorEngine("android")
        val ios = TrackingMirrorEngine("ios")
        IosTargetRegistry.update(
            listOf(
                IosTarget(
                    udid = "ios-udid",
                    displayName = "iPhone",
                    kind = IosTargetKind.Simulator,
                    state = IosTargetState.Booted,
                ),
            ),
        )
        val routing = RoutingMirrorEngine(android, ios)

        routing.connect("android-serial", MirrorVideoConfig())
        routing.connect("ios-udid", MirrorVideoConfig())

        assertEquals(1, ios.connectCount)
        assertEquals(1, android.connectCount)
        assertEquals(1, android.disconnectCount)
    }

    @Test
    fun routesInputToActiveEngine() = runBlocking {
        val android = TrackingMirrorEngine("android")
        val ios = TrackingMirrorEngine("ios")
        IosTargetRegistry.update(
            listOf(
                IosTarget(
                    udid = "ios-udid",
                    displayName = "iPhone",
                    kind = IosTargetKind.Simulator,
                    state = IosTargetState.Booted,
                ),
            ),
        )
        val routing = RoutingMirrorEngine(android, ios)
        routing.connect("ios-udid", MirrorVideoConfig())
        routing.sendInput(MirrorInput.Home)

        assertEquals(1, ios.inputCount)
        assertEquals(0, android.inputCount)
    }

    @Test
    fun routesInputAfterAndroidIosAndroidSwitch() = runBlocking {
        val android = TrackingMirrorEngine("android")
        val ios = TrackingMirrorEngine("ios")
        IosTargetRegistry.update(
            listOf(
                IosTarget(
                    udid = "ios-udid",
                    displayName = "iPhone",
                    kind = IosTargetKind.Simulator,
                    state = IosTargetState.Booted,
                ),
            ),
        )
        val routing = RoutingMirrorEngine(android, ios)

        routing.connect("android-serial", MirrorVideoConfig())
        routing.sendInput(MirrorInput.Home)
        routing.connect("ios-udid", MirrorVideoConfig())
        routing.sendInput(MirrorInput.Home)
        routing.connect("android-serial", MirrorVideoConfig())
        routing.sendInput(MirrorInput.Home)

        assertEquals(2, android.inputCount, "Android input must resume after returning from iOS")
        assertEquals(1, ios.inputCount)
        assertEquals("android-serial", routing.session.value?.serial)
    }

    @Test
    fun statusComesOnlyFromActiveBackendAcrossSwitches() = runBlocking {
        val android = TrackingMirrorEngine("android")
        val ios = TrackingMirrorEngine("ios")
        IosTargetRegistry.update(
            listOf(
                IosTarget(
                    udid = "ios-udid",
                    displayName = "iPhone",
                    kind = IosTargetKind.Simulator,
                    state = IosTargetState.Booted,
                ),
            ),
        )
        val routing = RoutingMirrorEngine(android, ios)

        routing.connect("ios-udid", MirrorVideoConfig())
        ios.status.value = "iOS connected"
        android.status.value = "Disconnected"
        assertEquals("iOS connected", routing.status.first())

        routing.connect("android-serial", MirrorVideoConfig())
        android.status.value = "Android connected"
        ios.status.value = "Disconnected"
        assertEquals("Android connected", routing.status.first())
    }

    @Test
    fun exposesFramesFromActiveEngineOnly() = runBlocking {
        val android = TrackingMirrorEngine("android")
        val ios = TrackingMirrorEngine("ios")
        IosTargetRegistry.update(
            listOf(
                IosTarget(
                    udid = "ios-udid",
                    displayName = "iPhone",
                    kind = IosTargetKind.Simulator,
                    state = IosTargetState.Booted,
                ),
            ),
        )
        android.frames.value = MirrorFrame(1, 1, intArrayOf(0xff000000.toInt()), frameNumber = 0)
        ios.frames.value = MirrorFrame(1206, 2622, intArrayOf(), frameNumber = 1)
        val routing = RoutingMirrorEngine(android, ios)

        routing.connect("ios-udid", MirrorVideoConfig())
        val frame = routing.frames.first { it.width > 1 && it.height > 1 }

        assertEquals(1206, frame.width)
        assertEquals(2622, frame.height)
    }

    @Test
    fun replaceAndroidEngineHandsLiveSessionToReplacementOwner() = runBlocking {
        val android = TrackingMirrorEngine("android")
        val ios = TrackingMirrorEngine("ios")
        val routing = RoutingMirrorEngine(android, ios)

        routing.connect("android-serial", MirrorVideoConfig())
        assertEquals("android-serial", routing.session.value?.serial)

        val fresh = TrackingMirrorEngine("fresh")
        val previous = routing.replaceAndroidEngine(fresh)

        assertSame(android, previous)
        assertEquals(null, routing.session.value, "Primary routing must drop the transferred Android session")
        assertEquals("android-serial", previous.session.value?.serial)
        assertEquals(0, android.disconnectCount, "Replace must not tear down the live Android engine")

        routing.connect("other-serial", MirrorVideoConfig())
        assertEquals("other-serial", routing.session.value?.serial)
        assertEquals(1, fresh.connectCount)
        assertEquals("android-serial", previous.session.value?.serial)
    }

    @Test
    fun routesIosFramesBeforeOwnerConnectCompletes() = runBlocking {
        val android = TrackingMirrorEngine("android")
        val ios = TrackingMirrorEngine("ios")
        IosTargetRegistry.update(
            listOf(
                IosTarget(
                    udid = "ios-udid",
                    displayName = "iPhone",
                    kind = IosTargetKind.Simulator,
                    state = IosTargetState.Booted,
                ),
            ),
        )
        // Simulate SimulatorKit metadata arriving while connect() is still running.
        ios.emitFrameDuringConnect = MirrorFrame(1179, 2556, intArrayOf(), frameNumber = 7)
        val routing = RoutingMirrorEngine(android, ios)

        val pending = async {
            routing.frames.first { it.width > 1 }
        }
        routing.connect("ios-udid", MirrorVideoConfig())
        val frame = pending.await()

        assertEquals(1179, frame.width)
        assertEquals(2556, frame.height)
        assertEquals(1, ios.connectCount)
    }
}

private class TrackingMirrorEngine(
    private val label: String,
) : MirrorEngine {
    var connectCount = 0
    var disconnectCount = 0
    var inputCount = 0
    var emitFrameDuringConnect: MirrorFrame? = null

    override val session = MutableStateFlow<MirrorSession?>(null)
    override val frames = MutableStateFlow(MirrorFrame(1, 1, intArrayOf(0xff000000.toInt())))
    override val status = MutableStateFlow("ready")

    override suspend fun connect(serial: String, config: MirrorVideoConfig): CommandResult {
        connectCount++
        emitFrameDuringConnect?.let { frames.value = it }
        session.value = MirrorSession(serial, config.rendererMode, MirrorBackend(MirrorBackendKind.NativeHardware))
        return CommandResult.success("connected $label")
    }

    override suspend fun disconnect(immediate: Boolean) {
        disconnectCount++
        session.value = null
    }

    override suspend fun sendInput(input: MirrorInput): CommandResult {
        inputCount++
        return CommandResult.success("sent $label")
    }

    override suspend fun screenshot(serial: String): ByteArray? = null
}
