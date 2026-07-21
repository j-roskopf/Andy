package app.andy.ui.live

import app.andy.model.AndroidDevice
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.DeviceTransport
import app.andy.service.MirrorFrame
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveMirrorSourceSizeTest {
    @Test
    fun prefersLiveFrameOverDeviceScreenSize() {
        val device = device(screenSize = "1080x1920")
        val frame = MirrorFrame(486, 1080, IntArray(0), frameNumber = 3)

        assertEquals(MirrorSourceSize(486, 1080), liveMirrorSourceSize(device, frame))
    }

    @Test
    fun usesDeviceScreenSizeWhenFrameIsMissingOrSentinel() {
        val device = device(screenSize = "1080x2400")

        assertEquals(MirrorSourceSize(1080, 2400), liveMirrorSourceSize(device, null))
        assertEquals(
            MirrorSourceSize(1080, 2400),
            liveMirrorSourceSize(device, MirrorFrame(1, 1, intArrayOf(0xff000000.toInt()))),
        )
    }

    @Test
    fun fallsBackToTallPhoneDefault() {
        assertEquals(MirrorSourceSize(1080, 2400), liveMirrorSourceSize(null, null))
    }

    private fun device(screenSize: String) = AndroidDevice(
        serial = "emulator-5554",
        displayName = "Pixel_8",
        kind = DeviceKind.Emulator,
        state = DeviceConnectionState.Online,
        transport = DeviceTransport.Unknown,
        screenSize = screenSize,
    )
}
