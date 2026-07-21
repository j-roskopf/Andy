package app.andy.ui.live

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ComposeUiTest
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
import app.andy.service.MirrorSession
import app.andy.service.MirrorVideoConfig
import app.andy.transfer.DeviceTransferCoordinator
import app.andy.ui.logcat.LogcatState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MirrorLifecycleTest {
    @Test
    fun liveScreenKeepsMirrorConnectedWhenRemovedFromComposition() = withComposeMirrorRenderer {
        // Prior Compose desktop tests can leave uncaught Job failures that poison the next
        // runTest scope. Consume that once, then assert the lifecycle behavior.
        runDesktopComposeUiTestDrainingPriorFailures {
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
                        onRecordingSaved = {},
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
            // Give composition teardown a moment; disconnect must not fire on leave.
            runBlocking { delay(250) }

            assertEquals(1, mirror.connectCalls)
            assertEquals(0, mirror.disconnectCalls)
        }
    }

    @Test
    fun embeddedLivePanelKeepsMirrorConnectedWhenRemovedFromComposition() = withComposeMirrorRenderer {
        runDesktopComposeUiTestDrainingPriorFailures {
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
            runBlocking { delay(250) }

            assertEquals(1, mirror.connectCalls)
            assertEquals(0, mirror.disconnectCalls)
        }
    }

    private fun runDesktopComposeUiTestDrainingPriorFailures(block: ComposeUiTest.() -> Unit) {
        try {
            runDesktopComposeUiTest(block = block)
        } catch (error: IllegalStateException) {
            if (error.message?.contains("uncaught exceptions before the test started") != true) throw error
            runDesktopComposeUiTest(block = block)
        }
    }

    private inline fun <T> withComposeMirrorRenderer(block: () -> T): T {
        val previous = System.getProperty("andy.screenshot.renderer")
        System.setProperty("andy.screenshot.renderer", "compose")
        return try {
            block()
        } finally {
            if (previous == null) System.clearProperty("andy.screenshot.renderer") else System.setProperty("andy.screenshot.renderer", previous)
        }
    }

    private class TrackingMirror : MirrorEngine {
        private val mutableFrames = MutableSharedFlow<MirrorFrame>()
        private val mutableStatus = MutableStateFlow("Disconnected")
        override val session = MutableStateFlow<MirrorSession?>(null)

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

        override suspend fun disconnect(immediate: Boolean) {
            disconnectCalls += 1
            mutableStatus.value = "Disconnected"
        }

        override suspend fun sendInput(input: MirrorInput) = CommandResult.success()

        override suspend fun screenshot(serial: String): ByteArray? = null
    }
}
