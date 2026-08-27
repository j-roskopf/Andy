package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosMirrorCoordinateTest {
    @Test
    fun physicalUsbDeviceIsLiveReadyWhenPaired() {
        val target = IosTarget(
            udid = "00008140-00026112260B001D",
            displayName = "iPhone",
            kind = IosTargetKind.Physical,
            state = IosTargetState.Unknown,
            transport = IosTransport.Usb,
        )
        assertTrue(target.isMirrorable)
        assertTrue(target.isLiveReady)
    }

    @Test
    fun bootedSimulatorIsLiveReady() {
        val target = IosTarget(
            udid = "sim-udid",
            displayName = "iPhone 17 Pro",
            kind = IosTargetKind.Simulator,
            state = IosTargetState.Booted,
        )
        assertTrue(target.isLiveReady)
    }

    @Test
    fun shutdownSimulatorIsNotLiveReady() {
        val target = IosTarget(
            udid = "sim-udid",
            displayName = "iPhone 17 Pro",
            kind = IosTargetKind.Simulator,
            state = IosTargetState.Shutdown,
        )
        assertTrue(target.isMirrorable)
        assertFalse(target.isLiveReady)
    }

    @Test
    fun pixelCoordinatesNormalizeAgainstFramebufferDimensions() {
        val (nx, ny) = iosNormalizedTouchCoordinates(603, 1311, 1206, 2622)
        assertEquals(0.5f, nx)
        assertEquals(0.5f, ny)
    }

    @Test
    fun centerTapNormalizesToHalf() {
        val (nx, ny) = iosNormalizedTouchCoordinates(603, 1311, 1206, 2622)
        assertEquals(0.5f, nx)
        assertEquals(0.5f, ny)
    }
}
