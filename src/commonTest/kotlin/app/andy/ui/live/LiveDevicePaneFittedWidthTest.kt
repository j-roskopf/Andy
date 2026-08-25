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

        assertTrue(fitted.value >= 560f, "header chips need a wider floor than a tall-phone mirror fit")
        assertTrue(fitted.value < 900f)
    }

    @Test
    fun landscapeFittedWidthIsCappedSoSidePanelRemainsVisible() {
        val device = AndroidDevice(
            serial = "emulator-5554",
            displayName = "Nexus_7",
            kind = DeviceKind.Emulator,
            state = DeviceConnectionState.Online,
            transport = DeviceTransport.Unknown,
            screenSize = "1920x1200",
        )
        val frame = MirrorFrame(1080, 674, IntArray(0), frameNumber = 3)
        val fitted = liveDevicePaneFittedWidth(
            maxPaneHeight = 900.dp,
            device = device,
            frame = frame,
            showHardwareControls = true,
            showDeviceHeader = true,
            showChromeControls = true,
        )
        // Uncapped landscape height-fit is very wide; LiveScreen must leave room for the
        // side panel using LiveSidePanelMinWidth.
        val rowWidth = 1400.dp
        val maxLeft = (rowWidth - LiveSidePanelMinWidth - LivePaneDividerAllowance).coerceAtLeast(400.dp)
        val capped = minOf(fitted, maxLeft)
        assertTrue(fitted.value > maxLeft.value, "precondition: landscape fit exceeds the row budget")
        assertTrue(capped.value <= maxLeft.value)
        assertTrue(capped.value + LiveSidePanelMinWidth.value <= rowWidth.value + 0.1f)
    }

    @Test
    fun dragMinWidthIsBelowLandscapeFittedWidth() {
        val fitted = liveDevicePaneFittedWidth(
            maxPaneHeight = 900.dp,
            device = AndroidDevice(
                serial = "emulator-5554",
                displayName = "Nexus_7",
                kind = DeviceKind.Emulator,
                state = DeviceConnectionState.Online,
                transport = DeviceTransport.Unknown,
                screenSize = "1920x1200",
            ),
            frame = MirrorFrame(1080, 674, IntArray(0), frameNumber = 3),
            showHardwareControls = true,
            showDeviceHeader = true,
            showChromeControls = true,
        )
        val minWidth = liveDevicePaneMinWidth(showSideToolbar = true)
        assertTrue(minWidth.value < fitted.value)
        assertTrue(minWidth.value >= 200f)
    }
}
