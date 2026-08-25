package app.andy.desktop.service

import app.andy.desktop.service.mirror.GpuMirrorJni
import app.andy.desktop.test.OptInGates
import app.andy.model.DeviceConnectionState
import app.andy.service.MirrorFrame
import app.andy.service.MirrorRendererMode
import app.andy.service.MirrorVideoConfig
import app.andy.ui.controls.parseWmSizePx
import app.andy.ui.controls.parseWmUserRotation
import app.andy.ui.controls.readLogicalDisplaySize
import app.andy.ui.controls.rotateDeviceDisplay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Live regression for Android rotation. Gated behind `ANDY_DEVICE_SMOKE=1`.
 *
 * The test deliberately does not restart the mirror. Android can replace VideoToolbox's decoded
 * pixel-buffer dimensions in the same scrcpy session, and Live must follow that change directly.
 * It also leaves phone WindowManager policy free so a locked launcher may stay portrait, while a
 * naturally landscape tablet exercises its explicit display-frame lock in both directions.
 */
class DesktopRotationVerification {
    @Test
    fun emulatorRespectsAppOrientationAndMirrorFollowsDecodedSize() = runBlocking {
        OptInGates.requireDeviceSmoke()
        assumeTrue("Native/GPU mirror JNI is required", GpuMirrorJni.isAvailable())
        val services = createDesktopServices()
        val requestedSerial = System.getenv("ANDY_DEVICE_SERIAL")?.takeIf { it.isNotBlank() }
        val serial = services.devices.listDevices()
            .firstOrNull { device ->
                device.state == DeviceConnectionState.Online &&
                    device.serial.startsWith("emulator-") &&
                    (requestedSerial == null || device.serial == requestedSerial)
            }
            ?.serial
        assumeTrue("Needs a running emulator (set ANDY_DEVICE_SERIAL to pick one)", serial != null)
        requireNotNull(serial)

        val originalSensor = services.devices.readEmulatorDisplayRotation(serial)
        val originalFixed = services.devices.shell(serial, listOf("wm", "fixed-to-user-rotation")).stdout
        val originalUserRotation = services.devices.shell(serial, listOf("wm", "user-rotation")).stdout
        val originalAutoRotate = services.devices
            .shell(serial, listOf("settings", "get", "system", "accelerometer_rotation"))
            .stdout
            .trim()
        val physical = services.devices.shell(serial, listOf("wm", "size"))
        val naturalSize = parseWmSizePx(physical.stdout.ifBlank { physical.stderr })
            ?: fail("Could not read physical display size: ${physical.stdout} ${physical.stderr}")
        val naturallyLandscape = naturalSize.first > naturalSize.second

        suspend fun displaySize(): Pair<Int, Int> =
            services.devices.readLogicalDisplaySize(serial) ?: fail("Could not read logical display size")

        fun landscape(size: Pair<Int, Int>): Boolean = size.first > size.second

        suspend fun awaitDisplayAspect(wantLandscape: Boolean, timeoutMillis: Long = 8_000): Pair<Int, Int>? {
            return withTimeoutOrNull(timeoutMillis) {
                while (true) {
                    val current = displaySize()
                    if (landscape(current) == wantLandscape) return@withTimeoutOrNull current
                    delay(100)
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
        }

        suspend fun awaitMirrorAspect(wantLandscape: Boolean): MirrorFrame {
            var latest = MirrorFrame(1, 1, IntArray(0))
            return withTimeoutOrNull(15_000) {
                services.mirror.frames.first { frame ->
                    latest = frame
                    frame.width > 1 && frame.height > 1 && landscape(frame.width to frame.height) == wantLandscape
                }
            } ?: fail(
                "mirror did not reach ${if (wantLandscape) "landscape" else "portrait"}; " +
                    "display=${displaySize()} frame=${latest.width}x${latest.height} " +
                    "session=${services.mirror.session.value?.width}x${services.mirror.session.value?.height} " +
                    "status=${services.mirror.status.first()}",
            )
        }

        try {
            services.devices.shell(serial, listOf("wm", "fixed-to-user-rotation", "disabled"))
            services.devices.shell(serial, listOf("wm", "user-rotation", "free"))
            services.devices.shell(serial, listOf("settings", "put", "system", "accelerometer_rotation", "1"))
            assertTrue(
                services.devices.applyEmulatorDisplayRotation(serial, 0).isSuccess,
                "Could not reset the virtual sensor to its natural orientation",
            )
            services.devices.shell(serial, listOf("input", "keyevent", "KEYCODE_HOME"))
            delay(2_500)

            val initial = displaySize()
            val mirrorConfig = MirrorVideoConfig(
                maxSize = 480,
                maxFps = 30,
                rendererMode = MirrorRendererMode.Accelerated,
            )
            val connected = services.mirror.connect(serial, mirrorConfig)
            assertTrue(connected.isSuccess, connected.stderr.ifBlank { connected.stdout })
            awaitMirrorAspect(landscape(initial))

            val firstRotation = services.devices.rotateDeviceDisplay(serial, isEmulator = true)
            assertTrue(firstRotation.isSuccess, firstRotation.stderr.ifBlank { firstRotation.stdout })
            val wantedLandscape = !landscape(initial)
            val acceptedByLauncher = awaitDisplayAspect(wantedLandscape, timeoutMillis = 3_000)
            if (naturallyLandscape) {
                val windowRotation = services.devices.shell(serial, listOf("dumpsys", "window")).stdout
                    .lineSequence()
                    .filter { line ->
                        line.contains("mRotation=") ||
                            line.contains("mProposedRotation=") ||
                            line.contains("mFixedToUserRotation=") ||
                            line.contains("mCurrentAppOrientation=")
                    }
                    .take(8)
                    .joinToString(" | ") { it.trim() }
                assertTrue(
                    acceptedByLauncher != null && !landscape(acceptedByLauncher),
                    "A naturally landscape tablet must rotate to portrait; " +
                        "result=${firstRotation.stdout} display=${displaySize()} " +
                        "sensor=${services.devices.readEmulatorDisplayRotation(serial)} " +
                        "fixed=${services.devices.shell(serial, listOf("wm", "fixed-to-user-rotation")).stdout.trim()} " +
                        windowRotation,
                )
                awaitMirrorAspect(wantLandscape = false)
            } else if (acceptedByLauncher == null) {
                assertTrue(firstRotation.stdout.contains("current app remains portrait"), firstRotation.stdout)
                awaitMirrorAspect(landscape(initial))
            } else {
                awaitMirrorAspect(wantedLandscape)
            }

            // Settings accepts both aspects. The next accepted device aspect and both frame/session
            // dimensions must update in-place without restartForDisplayChange().
            services.devices.shell(serial, listOf("am", "start", "-a", "android.settings.SETTINGS"))
            val settingsSize = awaitDisplayAspect(wantedLandscape)
                ?: fail("Settings did not accept the current sensor orientation; display=${displaySize()}")
            val settingsFrame = awaitMirrorAspect(wantedLandscape)
            assertEquals(landscape(settingsSize), landscape(settingsFrame.width to settingsFrame.height))
            assertEquals(settingsFrame.width, services.mirror.session.value?.width)
            assertEquals(settingsFrame.height, services.mirror.session.value?.height)

            val secondRotation = services.devices.rotateDeviceDisplay(serial, isEmulator = true)
            assertTrue(secondRotation.isSuccess, secondRotation.stderr.ifBlank { secondRotation.stdout })
            val finalDisplay = awaitDisplayAspect(!wantedLandscape)
                ?: fail("Second rotation did not toggle Settings; display=${displaySize()}")
            val finalFrame = awaitMirrorAspect(!wantedLandscape)
            assertEquals(landscape(finalDisplay), landscape(finalFrame.width to finalFrame.height))
            assertEquals(finalFrame.width, services.mirror.session.value?.width)
            assertEquals(finalFrame.height, services.mirror.session.value?.height)
        } finally {
            services.mirror.disconnect(immediate = true)
            originalSensor?.let { services.devices.applyEmulatorDisplayRotation(serial, it) }
            val fixedMode = when {
                originalFixed.contains("enabled", ignoreCase = true) -> "enabled"
                originalFixed.contains("disabled", ignoreCase = true) -> "disabled"
                else -> "default"
            }
            services.devices.shell(serial, listOf("wm", "fixed-to-user-rotation", fixedMode))
            parseWmUserRotation(originalUserRotation)?.let { rotation ->
                if (originalUserRotation.trim().startsWith("lock")) {
                    services.devices.shell(serial, listOf("wm", "user-rotation", "lock", rotation.toString()))
                } else {
                    services.devices.shell(serial, listOf("wm", "user-rotation", "free"))
                }
            }
            if (originalAutoRotate == "0" || originalAutoRotate == "1") {
                services.devices.shell(
                    serial,
                    listOf("settings", "put", "system", "accelerometer_rotation", originalAutoRotate),
                )
            }
        }
    }

    @Test
    fun physicalRotationUpdatesAcceleratedMirrorWithoutReconnect() = runBlocking {
        OptInGates.requireDeviceSmoke()
        assumeTrue("Native/GPU mirror JNI is required", GpuMirrorJni.isAvailable())
        val services = createDesktopServices()
        val requestedSerial = System.getenv("ANDY_DEVICE_SERIAL")?.takeIf { it.isNotBlank() }
        val serial = services.devices.listDevices()
            .firstOrNull { device ->
                device.state == DeviceConnectionState.Online &&
                    !device.serial.startsWith("emulator-") &&
                    (requestedSerial == null || device.serial == requestedSerial)
            }
            ?.serial
        assumeTrue("Needs a connected physical Android device", serial != null)
        requireNotNull(serial)

        val originalUserRotation = services.devices.shell(serial, listOf("wm", "user-rotation")).stdout
        val originalAutoRotate = services.devices
            .shell(serial, listOf("settings", "get", "system", "accelerometer_rotation"))
            .stdout
            .trim()
        val originalSettingRotation = services.devices
            .shell(serial, listOf("settings", "get", "system", "user_rotation"))
            .stdout
            .trim()

        suspend fun displaySize(): Pair<Int, Int> =
            services.devices.readLogicalDisplaySize(serial) ?: fail("Could not read logical display size")

        fun landscape(size: Pair<Int, Int>): Boolean = size.first > size.second

        suspend fun awaitDisplayAspect(wantLandscape: Boolean): Pair<Int, Int> =
            withTimeoutOrNull(8_000) {
                while (true) {
                    val current = displaySize()
                    if (landscape(current) == wantLandscape) return@withTimeoutOrNull current
                    delay(100)
                }
                @Suppress("UNREACHABLE_CODE")
                displaySize()
            } ?: fail("Physical display did not reach ${if (wantLandscape) "landscape" else "portrait"}")

        suspend fun awaitMirrorAspect(wantLandscape: Boolean): MirrorFrame =
            withTimeoutOrNull(15_000) {
                services.mirror.frames.first { frame ->
                    frame.width > 1 && frame.height > 1 && landscape(frame.width to frame.height) == wantLandscape
                }
            } ?: fail(
                "Physical mirror did not follow display=${displaySize()}; " +
                    "session=${services.mirror.session.value?.width}x${services.mirror.session.value?.height}",
            )

        try {
            services.devices.shell(serial, listOf("am", "start", "-a", "android.settings.SETTINGS"))
            delay(1_500)
            val initial = displaySize()
            val connected = services.mirror.connect(
                serial,
                MirrorVideoConfig(maxSize = 480, maxFps = 30, rendererMode = MirrorRendererMode.Accelerated),
            )
            assertTrue(connected.isSuccess, connected.stderr.ifBlank { connected.stdout })
            awaitMirrorAspect(landscape(initial))

            val rotation = services.devices.rotateDeviceDisplay(serial, isEmulator = false)
            assertTrue(rotation.isSuccess, rotation.stderr.ifBlank { rotation.stdout })
            val rotatedDisplay = awaitDisplayAspect(!landscape(initial))
            val rotatedFrame = awaitMirrorAspect(!landscape(initial))
            assertEquals(landscape(rotatedDisplay), landscape(rotatedFrame.width to rotatedFrame.height))
            assertEquals(rotatedFrame.width, services.mirror.session.value?.width)
            assertEquals(rotatedFrame.height, services.mirror.session.value?.height)
        } finally {
            services.mirror.disconnect(immediate = true)
            if (originalSettingRotation.toIntOrNull() != null) {
                services.devices.shell(
                    serial,
                    listOf("settings", "put", "system", "user_rotation", originalSettingRotation),
                )
            }
            val lockedRotation = parseWmUserRotation(originalUserRotation)
            if (originalUserRotation.trim().startsWith("lock") && lockedRotation != null) {
                services.devices.shell(
                    serial,
                    listOf("wm", "user-rotation", "lock", lockedRotation.toString()),
                )
            } else {
                services.devices.shell(serial, listOf("wm", "user-rotation", "free"))
            }
            if (originalAutoRotate == "0" || originalAutoRotate == "1") {
                services.devices.shell(
                    serial,
                    listOf("settings", "put", "system", "accelerometer_rotation", originalAutoRotate),
                )
            }
            services.devices.shell(serial, listOf("input", "keyevent", "KEYCODE_HOME"))
        }
    }
}
