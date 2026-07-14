package app.andy.ui.live

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.andy.ScreenshotServices
import app.andy.model.AndroidDevice
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.DeviceTransport
import app.andy.service.CommandResult
import app.andy.service.MirrorEngine
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.service.MirrorVideoConfig
import app.andy.transfer.DeviceTransferCoordinator
import app.andy.ui.logcat.LogcatState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MirrorLifecycleTest {
    @Test
    fun liveScreenDisconnectsMirrorWhenRemovedFromComposition() = runDesktopComposeUiTest {
        val visible = mutableStateOf(true)
        val mirror = TrackingMirror()
        val services = ScreenshotServices.create().copy(mirror = mirror)
        val device = AndroidDevice(
            serial = "device-1",
            displayName = "Test device",
            kind = DeviceKind.Physical,
            state = DeviceConnectionState.Online,
            transport = DeviceTransport.Usb,
        )

        setContent {
            if (visible.value) {
                LiveScreen(
                    services = services,
                    serial = device.serial,
                    device = device,
                    devicePaneWidth = 680f,
                    controlsPaneHeight = 220f,
                    onStopEmulator = {},
                    stoppingEmulatorSerial = null,
                    stopStatus = "",
                    onDevicePaneWidthChange = {},
                    onControlsPaneHeightChange = {},
                    onBugSaved = {},
                    logcatState = LogcatState(),
                    onPopOutMirror = {},
                    selectedPackage = null,
                    onSelectedPackageChange = {},
                    transfer = DeviceTransferCoordinator(),
                )
            }
        }

        waitUntil(timeoutMillis = 5_000) { mirror.connectCalls == 1 }
        runOnUiThread { visible.value = false }
        waitUntil(timeoutMillis = 5_000) { mirror.disconnectCalls == 1 }

        assertEquals(1, mirror.disconnectCalls)
    }

    @Test
    fun embeddedLivePanelDisconnectsMirrorWhenRemovedFromComposition() = runDesktopComposeUiTest {
        val visible = mutableStateOf(true)
        val mirror = TrackingMirror()
        val services = ScreenshotServices.create().copy(mirror = mirror)

        setContent {
            if (visible.value) {
                DeviceLivePanel(services = services, serial = "device-1", device = null)
            }
        }

        waitUntil(timeoutMillis = 5_000) { mirror.connectCalls == 1 }
        runOnUiThread { visible.value = false }
        waitUntil(timeoutMillis = 5_000) { mirror.disconnectCalls == 1 }

        assertEquals(1, mirror.disconnectCalls)
    }

    private class TrackingMirror : MirrorEngine {
        private val mutableFrames = MutableSharedFlow<MirrorFrame>()
        private val mutableStatus = MutableStateFlow("Disconnected")

        var connectCalls = 0
            private set
        var disconnectCalls = 0
            private set

        override val frames: Flow<MirrorFrame> = mutableFrames
        override val status: Flow<String> = mutableStatus

        override suspend fun connect(serial: String, config: MirrorVideoConfig): CommandResult {
            connectCalls += 1
            mutableStatus.value = "Connected"
            return CommandResult.success("Connected")
        }

        override suspend fun disconnect() {
            disconnectCalls += 1
            mutableStatus.value = "Disconnected"
        }

        override suspend fun sendInput(input: MirrorInput) = CommandResult.success()

        override suspend fun screenshot(serial: String): ByteArray? = null
    }
}
