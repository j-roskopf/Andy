package app.andy.service

import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.IosTargetState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

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

        val collected = mutableListOf<MirrorFrame>()
        val job = launch {
            routing.frames.take(1).collect { collected += it }
        }
        routing.connect("ios-udid", MirrorVideoConfig())
        job.join()

        assertEquals(1206, collected.single().width)
        assertEquals(2622, collected.single().height)
    }
}

private class TrackingMirrorEngine(
    private val label: String,
) : MirrorEngine {
    var connectCount = 0
    var disconnectCount = 0
    var inputCount = 0

    override val session = MutableStateFlow<MirrorSession?>(null)
    override val frames = MutableStateFlow(MirrorFrame(1, 1, intArrayOf(0xff000000.toInt())))
    override val status = MutableStateFlow("ready")

    override suspend fun connect(serial: String, config: MirrorVideoConfig): CommandResult {
        connectCount++
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
