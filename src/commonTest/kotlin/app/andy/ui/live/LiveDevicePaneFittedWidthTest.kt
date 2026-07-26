package app.andy.ui.live

import androidx.compose.ui.unit.dp
import app.andy.model.AndroidDevice
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.DeviceTransport
import app.andy.service.MirrorFrame
import kotlin.test.Test
import kotlin.test.assertTrue

class LiveDevicePaneFittedWidthTest {
    @Test
    fun fittedWidthTracksMirrorAspectNotWorkspaceDefault() {
        val device = AndroidDevice(
            serial = "emulator-5554",
            displayName = "Pixel_8",
            kind = DeviceKind.Emulator,
            state = DeviceConnectionState.Online,
            transport = DeviceTransport.Unknown,
            screenSize = "1080x2400",
        )
        val frame = MirrorFrame(486, 1080, IntArray(0), frameNumber = 3)
        val fitted = liveDevicePaneFittedWidth(
            maxPaneHeight = 800.dp,
            device = device,
            frame = frame,
            showHardwareControls = true,
            showDeviceHeader = true,
            showChromeControls = true,
        )

        assertTrue(fitted.value < 500f)
        assertTrue(fitted.value > 200f)
    }
}
