package app.andy.desktop.service.mirror

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GpuMirrorSessionsTest {
    @AfterTest
    fun tearDown() {
        GpuMirrorSessions.clear()
    }

    @Test
    fun createAndUnbindPipeline() {
        if (!GpuMirrorJni.isAvailable()) return
        val key = Any()
        val pipeline = GpuMirrorSessions.createAndBind(key)
        assertNotNull(pipeline)
        assertNotNull(GpuMirrorSessions.get(key))
        val presenter = pipeline.createPresenter()
        assertNotNull(presenter)
        presenter.close()
        GpuMirrorSessions.release(key)
        assertNull(GpuMirrorSessions.get(key))
    }

    @Test
    fun acquireReusesPipelineWithoutRecreating() {
        if (!GpuMirrorJni.isAvailable()) return
        val key = "emulator-5554"
        val first = GpuMirrorSessions.acquire(key)
        val second = GpuMirrorSessions.acquire(key)
        assertNotNull(first)
        assertSame(first, second)
        GpuMirrorSessions.release(key)
        GpuMirrorSessions.release(key)
    }

    @Test
    fun decodedFrameIsReadyBeforeAHiddenPresenterCanPresentIt() {
        if (!GpuMirrorJni.isAvailable()) return
        val key = "loading-ready"
        val pipeline = GpuMirrorSessions.createAndBind(key)
        assertNotNull(pipeline)
        assertTrue(pipeline.presentSolidBgra(8, 8, blue = 0, green = 0, red = 255))
        assertTrue(pipeline.hasDecodedFrame())
    }

    @Test
    fun pipelineReportsDecodedSizeChangesWithoutCopyingPixels() {
        if (!GpuMirrorJni.isAvailable()) return
        val key = "dynamic-size"
        val pipeline = GpuMirrorSessions.createAndBind(key)
        assertNotNull(pipeline)

        assertTrue(pipeline.presentSolidBgra(8, 12, blue = 0, green = 0, red = 255))
        assertEquals(8 to 12, pipeline.latestFrameSize())

        assertTrue(pipeline.presentSolidBgra(12, 8, blue = 0, green = 255, red = 0))
        assertEquals(12 to 8, pipeline.latestFrameSize())
    }

    @Test
    fun pipelineSurvivesUntilLastHolderReleases() {
        if (!GpuMirrorJni.isAvailable()) return
        // Models Live + a same-device pop-out sharing one decoder: releasing one holder (Live
        // switching devices) must NOT tear the pipeline out from under the other presenter.
        val key = "shared-serial"
        val live = GpuMirrorSessions.acquire(key)
        val popOut = GpuMirrorSessions.acquire(key)
        assertNotNull(live)
        assertSame(live, popOut)

        GpuMirrorSessions.release(key) // Live releases.
        assertSame(live, GpuMirrorSessions.get(key), "Pipeline must survive while the pop-out holds it")

        GpuMirrorSessions.release(key) // Pop-out releases.
        assertNull(GpuMirrorSessions.get(key), "Pipeline must close once the final holder releases")
    }

    @Test
    fun closingAndroidPipelineDoesNotClearIosDecoderBinding() {
        if (!GpuMirrorJni.isAvailable()) return
        val ios = GpuMirrorSessions.createAndBind("ios-guard")!!
        val android = GpuMirrorSessions.createAndBind("android-guard")!!
        assertNotEquals(ios.decoderId, android.decoderId, "Distinct devices must own distinct decoders")
        try {
            ios.bindIosCapture(simulator = false)
            assertEquals(
                ios.decoderId,
                GpuMirrorJni.iosDecoder(simulator = false),
                "iOS capture must route to the physical-device decoder slot",
            )

            // Regression: an unrelated (Android) pipeline tearing down used to null the iOS
            // routing slot, blanking a live iOS mirror bound to a different decoder.
            GpuMirrorSessions.release("android-guard")
            assertEquals(
                ios.decoderId,
                GpuMirrorJni.iosDecoder(simulator = false),
                "Closing the Android pipeline must not clear the iOS decoder binding",
            )
        } finally {
            GpuMirrorSessions.release("ios-guard")
        }
        assertEquals(
            0L,
            GpuMirrorJni.iosDecoder(simulator = false),
            "Closing the iOS pipeline must clear its own binding",
        )
    }

    @Test
    fun physicalAndSimulatorIosCapturesBindIndependentDecoderSlots() {
        if (!GpuMirrorJni.isAvailable()) return
        val physical = GpuMirrorSessions.createAndBind("ios-physical")!!
        val simulator = GpuMirrorSessions.createAndBind("ios-simulator")!!
        assertNotEquals(physical.decoderId, simulator.decoderId)
        try {
            physical.bindIosCapture(simulator = false)
            simulator.bindIosCapture(simulator = true)
            assertEquals(physical.decoderId, GpuMirrorJni.iosDecoder(simulator = false))
            assertEquals(simulator.decoderId, GpuMirrorJni.iosDecoder(simulator = true))

            // Re-binding one source must not clobber the other.
            physical.bindIosCapture(simulator = false)
            assertEquals(simulator.decoderId, GpuMirrorJni.iosDecoder(simulator = true))
        } finally {
            GpuMirrorSessions.release("ios-physical")
            GpuMirrorSessions.release("ios-simulator")
        }
        assertEquals(0L, GpuMirrorJni.iosDecoder(simulator = false))
        assertEquals(0L, GpuMirrorJni.iosDecoder(simulator = true))
    }
}
