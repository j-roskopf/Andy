package app.andy.desktop.service.mirror

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopMirrorCodecOptionsTest {
    @Test
    fun enablesQualcommLowLatencyWithoutOverridingExplicitOptions() {
        assertEquals(
            "vendor.qti-ext-enc-low-latency.enable=1",
            videoCodecOptionsForDevice("QTI", null),
        )
        assertEquals(
            "latency=1,vendor.qti-ext-enc-low-latency.enable=1",
            videoCodecOptionsForDevice("qualcomm", "latency=1"),
        )
        assertEquals(
            "vendor.qti-ext-enc-low-latency.enable=0",
            videoCodecOptionsForDevice("qti", "vendor.qti-ext-enc-low-latency.enable=0"),
        )
    }

    @Test
    fun leavesOtherDevicesAndBlankOverridesUntouched() {
        assertEquals(null, videoCodecOptionsForDevice("google", null))
        assertEquals("latency=1", videoCodecOptionsForDevice("mediatek", " latency=1 "))
    }

    @Test
    fun zeroMaxSizeKeepsNativeCaptureDimensions() {
        assertEquals(1080 to 2340, scaledCaptureSize(1080, 2340, maxSize = 0))
        assertEquals(1440 to 3200, scaledCaptureSize(1440, 3200, maxSize = 0))
    }

    @Test
    fun positiveMaxSizeScalesTheLongEdge() {
        assertEquals(332 to 720, scaledCaptureSize(1080, 2340, maxSize = 720))
        assertEquals(1080 to 2340, scaledCaptureSize(1080, 2340, maxSize = 2400))
    }
}
