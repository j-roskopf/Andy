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
import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.IosTargetState
import app.andy.service.CommandResult
import app.andy.service.MirrorEngine
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.service.MirrorSession
import app.andy.service.MirrorVideoConfig
import app.andy.transfer.DeviceTransferCoordinator
import app.andy.ui.logcat.LogcatState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
    fun embeddedLivePanelDisconnectsMirrorWhenSerialBecomesNull() = withComposeMirrorRenderer {
        runDesktopComposeUiTestDrainingPriorFailures {
            val serial = mutableStateOf<String?>("device-1")
            val mirror = TrackingMirror()
            val services = ScreenshotServices.create().copy(mirror = mirror)

            setContent {
                DeviceLivePanel(services = services, serial = serial.value, device = null)
            }

            waitUntil(timeoutMillis = 5_000) { mirror.connectCalls == 1 }
            runOnUiThread { serial.value = null }
            waitUntil(timeoutMillis = 5_000) { mirror.disconnectCalls == 1 }
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

    @Test
    fun deviceSwitchStartsFreshInputQueueWhenPreviousBackendIsStalled() = withComposeMirrorRenderer {
        runDesktopComposeUiTestDrainingPriorFailures {
            val serial = mutableStateOf("ios-sim")
            val mirror = BlockingInputMirror()
            val services = ScreenshotServices.create().copy(mirror = mirror)
            lateinit var sendInput: (MirrorInput) -> Unit

            setContent {
                sendInput = rememberMirrorInputSender(
                    services = services,
                    serial = serial.value,
                    mirror = mirror,
                    recordActions = false,
                )
            }

            try {
                runOnUiThread { sendInput(MirrorInput.Home) }
                waitUntil(timeoutMillis = 5_000) { mirror.stalledInputStarted }

                runOnUiThread { serial.value = "android-device" }
                waitForIdle()
                runOnUiThread { sendInput(MirrorInput.Back) }

                waitUntil(timeoutMillis = 5_000) { mirror.androidInputReceived }
            } finally {
                mirror.releaseStalledInput.complete(Unit)
            }
        }
    }

    @Test
    fun liveScreenSwitchesAndroidIosAndroidWithoutStaleDisconnect() = withComposeMirrorRenderer {
        runDesktopComposeUiTestDrainingPriorFailures {
            val android = AndroidDevice(
                serial = "android-device",
                displayName = "Android",
                kind = DeviceKind.Physical,
                state = DeviceConnectionState.Online,
                transport = DeviceTransport.Usb,
            )
            val ios = IosTarget(
                udid = "ios-sim",
                displayName = "iPhone",
                kind = IosTargetKind.Simulator,
                state = IosTargetState.Booted,
            )
            val serial = mutableStateOf(android.serial)
            val iosTarget = mutableStateOf<IosTarget?>(null)
            val mirror = TrackingMirror()
            val services = ScreenshotServices.create().copy(mirror = mirror)

            setContent {
                LiveScreen(
                    services = services,
                    serial = serial.value,
                    device = android.takeIf { iosTarget.value == null },
                    iosTarget = iosTarget.value,
                    devicePaneWidth = 680f,
                    controlsPaneHeight = 320f,
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

            waitUntil(timeoutMillis = 5_000) { mirror.connectedSerials == listOf(android.serial) }
            runOnUiThread {
                iosTarget.value = ios
                serial.value = ios.udid
            }
            waitUntil(timeoutMillis = 5_000) {
                mirror.connectedSerials == listOf(android.serial, ios.udid)
            }
            runOnUiThread {
                iosTarget.value = null
                serial.value = android.serial
            }
            waitUntil(timeoutMillis = 5_000) {
                mirror.connectedSerials == listOf(android.serial, ios.udid, android.serial)
            }

            assertEquals(0, mirror.disconnectCalls, "A cancelled target effect must not disconnect the new session")
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
        val connectedSerials = mutableListOf<String>()

        override val frames: Flow<MirrorFrame> = mutableFrames
        override val status: Flow<String> = mutableStatus

        override suspend fun connect(serial: String, config: MirrorVideoConfig): CommandResult {
            connectCalls += 1
            connectedSerials += serial
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

    private class BlockingInputMirror : MirrorEngine {
        override val session = MutableStateFlow<MirrorSession?>(null)
        override val frames = MutableSharedFlow<MirrorFrame>()
        override val status = MutableStateFlow("Connected")
        val releaseStalledInput = CompletableDeferred<Unit>()

        @Volatile
        var stalledInputStarted = false
            private set

        @Volatile
        var androidInputReceived = false
            private set

        override suspend fun connect(serial: String, config: MirrorVideoConfig) = CommandResult.success()

        override suspend fun disconnect(immediate: Boolean) = Unit

        override suspend fun sendInput(input: MirrorInput): CommandResult {
            if (input == MirrorInput.Home) {
                stalledInputStarted = true
                withContext(NonCancellable) { releaseStalledInput.await() }
            } else if (input == MirrorInput.Back) {
                androidInputReceived = true
            }
            return CommandResult.success()
        }

        override suspend fun screenshot(serial: String): ByteArray? = null
    }
}
