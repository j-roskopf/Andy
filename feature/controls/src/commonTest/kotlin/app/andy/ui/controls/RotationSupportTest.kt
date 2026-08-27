package app.andy.ui.controls

import app.andy.model.AndroidDevice
import app.andy.model.MdnsService
import app.andy.model.SdkDiscovery
import app.andy.service.CommandResult
import app.andy.service.DeviceService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RotationSupportTest {
    @Test
    fun emulatorRotateTurnsTheSensorAndLeavesAppOrientationPolicyFree() = runBlocking {
        val fake = RecordingDeviceService(
            display0SizeQueue = ArrayDeque(listOf("1080x2400", "2400x1080")),
            wmSizeQueue = ArrayDeque(listOf("1080x2400")),
            sensorRotation = 0,
        )
        val result = fake.rotateDeviceDisplay("emulator-5554", isEmulator = true)
        assertTrue(result.isSuccess)
        assertEquals(listOf(1), fake.grpcRotations, "should advance the sensor to landscape")
        assertTrue(fake.emuCommands.isEmpty(), "sensor path should not need the console")
        assertTrue(fake.shellCommands.contains(listOf("wm", "fixed-to-user-rotation", "disabled")))
        assertTrue(fake.shellCommands.contains(listOf("wm", "user-rotation", "free")))
        assertTrue(fake.shellCommands.contains(listOf("settings", "put", "system", "accelerometer_rotation", "1")))
        assertFalse(fake.shellCommands.contains(listOf("wm", "fixed-to-user-rotation", "enabled")))
        assertEquals("Rotated to landscape", result.stdout)
    }

    @Test
    fun emulatorRotateKeepsAPortraitLockedAppPortrait() = runBlocking {
        val fake = RecordingDeviceService(
            display0SizeQueue = ArrayDeque(listOf("1080x2400", "1080x2400")),
            wmSizeQueue = ArrayDeque(listOf("1080x2400")),
            sensorRotation = 0,
        )
        val result = fake.rotateDeviceDisplay("emulator-5554", isEmulator = true)
        assertTrue(result.isSuccess)
        assertEquals(listOf(1), fake.grpcRotations)
        assertEquals("Rotated device toward landscape · current app remains portrait", result.stdout)
        assertFalse(fake.shellCommands.any { it.take(3) == listOf("wm", "user-rotation", "lock") })
    }

    @Test
    fun emulatorRotateUsesNaturalLandscapeForPixelTablet() = runBlocking {
        val fake = RecordingDeviceService(
            display0SizeQueue = ArrayDeque(listOf("2400x1080", "1080x2400")),
            wmSizeQueue = ArrayDeque(listOf("2560x1600", "2560x1600")),
            shellResponses = mapOf(
                listOf("wm", "user-rotation", "lock", "3") to CommandResult.success(""),
            ),
            sensorRotation = 0,
        )
        val result = fake.rotateDeviceDisplay("emulator-5554", isEmulator = true)
        assertTrue(result.isSuccess)
        assertTrue(fake.grpcRotations.isEmpty(), "tablet rotation should use the authoritative display frame")
        assertTrue(fake.shellCommands.contains(listOf("wm", "user-rotation", "lock", "3")))
        assertEquals("Locked portrait", result.stdout)
    }

    @Test
    fun emulatorRotateFollowsSensorPostureWhenLockedAppAspectDisagrees() = runBlocking {
        val fake = RecordingDeviceService(
            display0SizeQueue = ArrayDeque(listOf("1080x2400", "1080x2400")),
            wmSizeQueue = ArrayDeque(listOf("1080x2400")),
            sensorRotation = 1,
        )

        val result = fake.rotateDeviceDisplay("emulator-5554", isEmulator = true)

        assertTrue(result.isSuccess)
        assertEquals(listOf(0), fake.grpcRotations)
        assertEquals("Rotated device toward portrait · current app remains portrait", result.stdout)
    }

    @Test
    fun emulatorRotateFailsWhenTheSensorWriteDoesNotStick() = runBlocking {
        val fake = RecordingDeviceService(
            display0SizeQueue = ArrayDeque(listOf("1080x2400")),
            wmSizeQueue = ArrayDeque(listOf("1080x2400")),
            sensorRotation = 0,
            sensorAcceptsWrites = false,
        )
        val result = fake.rotateDeviceDisplay("emulator-5554", isEmulator = true)
        assertFalse(result.isSuccess)
        assertTrue(result.stderr.contains("sensor did not move"))
        assertFalse(fake.shellCommands.any { it.take(3) == listOf("wm", "user-rotation", "lock") })
    }

    @Test
    fun emulatorRotateWaitsForSensorStateToSettle() = runBlocking {
        val fake = RecordingDeviceService(
            display0SizeQueue = ArrayDeque(listOf("1080x2400", "2400x1080")),
            wmSizeQueue = ArrayDeque(listOf("1080x2400")),
            sensorRotation = 0,
            sensorReadLagAfterWrite = 3,
        )

        val result = fake.rotateDeviceDisplay("emulator-5554", isEmulator = true)

        assertTrue(result.isSuccess, result.stderr)
        assertEquals(listOf(1), fake.grpcRotations)
        assertEquals("Rotated to landscape", result.stdout)
    }

    @Test
    fun rotateTogglesPortraitAndLandscapeWithoutStoppingAtUpsideDown() {
        // Android will not put most apps in ROTATION_180, so a 4-step cycle leaves the
        // screen in landscape for turn 2 and the button looks dead.
        assertEquals(1, toggledQuarterTurn(0))
        assertEquals(0, toggledQuarterTurn(1))
        assertEquals(1, toggledQuarterTurn(2))
        assertEquals(0, toggledQuarterTurn(3))
        assertEquals(1, quarterTurnForAspect(wantLandscape = true))
        assertEquals(0, quarterTurnForAspect(wantLandscape = false))
        assertEquals(3, quarterTurnForAspect(wantLandscape = false, naturalLandscape = true))
        assertEquals(0, quarterTurnForAspect(wantLandscape = true, naturalLandscape = true))
    }

    @Test
    fun emulatorRotateFallsBackToConsoleWithoutASensor() = runBlocking {
        val fake = RecordingDeviceService(
            display0SizeQueue = ArrayDeque(listOf("2400x1080", "1080x2400")),
            wmSizeQueue = ArrayDeque(listOf("1080x2400")),
            shellResponses = mapOf(listOf("wm", "user-rotation") to CommandResult.success("lock 1\n")),
            sensorRotation = null,
        )
        val result = fake.rotateDeviceDisplay("emulator-5554", isEmulator = true)
        assertTrue(result.isSuccess)
        assertEquals("Rotated to portrait", result.stdout)
        assertEquals(listOf(listOf("rotate")), fake.emuCommands)
        assertFalse(fake.shellCommands.contains(listOf("wm", "fixed-to-user-rotation", "enabled")))
    }

    @Test
    fun ensureEmulatorOrientationLocksPortraitDirectlyFromLandscape() = runBlocking {
        val fake = RecordingDeviceService(
            display0SizeQueue = ArrayDeque(
                listOf(
                    "2400x1080", // ensure: landscape, want portrait
                    "1080x2400", // after direct lock 0
                    "1080x2400", // ensure: verify after delay
                ),
            ),
            shellResponses = mapOf(
                listOf("wm", "fixed-to-user-rotation", "enabled") to CommandResult.success(""),
                listOf("wm", "user-rotation", "lock", "0") to CommandResult.success(""),
            ),
        )
        val result = fake.ensureEmulatorOrientation("emulator-5554", "portrait")
        assertTrue(result.isSuccess)
        assertTrue(fake.emuCommands.isEmpty())
        assertEquals("Oriented portrait", result.stdout)
        assertTrue(fake.shellCommands.contains(listOf("wm", "user-rotation", "lock", "0")))
    }

    @Test
    fun ensureEmulatorOrientationUsesRotationThreeForPortraitOnPixelTablet() = runBlocking {
        val fake = RecordingDeviceService(
            display0SizeQueue = ArrayDeque(listOf("2560x1600", "1600x2560", "1600x2560")),
            wmSizeQueue = ArrayDeque(listOf("2560x1600")),
            shellResponses = mapOf(
                listOf("wm", "fixed-to-user-rotation", "enabled") to CommandResult.success(""),
                listOf("wm", "user-rotation", "lock", "3") to CommandResult.success(""),
            ),
        )

        val result = fake.ensureEmulatorOrientation("emulator-5554", "portrait")

        assertTrue(result.isSuccess)
        assertEquals("Oriented portrait", result.stdout)
        assertTrue(fake.shellCommands.contains(listOf("wm", "user-rotation", "lock", "3")))
        assertFalse(fake.shellCommands.contains(listOf("wm", "user-rotation", "lock", "0")))
    }

    @Test
    fun physicalRotatePrefersWmUserRotation() = runBlocking {
        val fake = RecordingDeviceService(
            display0SizeQueue = ArrayDeque(listOf("1080x2400", "2400x1080")),
            shellResponses = mapOf(
                listOf("wm", "user-rotation") to CommandResult.success("lock 0\n"),
                listOf("wm", "user-rotation", "lock", "1") to CommandResult.success(""),
            ),
        )
        val result = fake.rotateDeviceDisplay("serial", isEmulator = false)
        assertTrue(result.isSuccess)
        assertTrue(fake.emuCommands.isEmpty())
        assertTrue(fake.shellCommands.contains(listOf("wm", "user-rotation", "lock", "1")))
        assertTrue(fake.shellCommands.contains(listOf("settings", "put", "system", "user_rotation", "1")))
        assertFalse(
            fake.shellCommands.contains(listOf("wm", "fixed-to-user-rotation", "enabled")),
            "physical devices get pillarboxed by the override too",
        )
        assertEquals("Rotated to landscape", result.stdout)
    }

    @Test
    fun physicalRotateFallsBackToSettingsWhenWmFails() = runBlocking {
        val fake = RecordingDeviceService(
            display0SizeQueue = ArrayDeque(listOf("1080x2400", "2400x1080")),
            shellResponses = mapOf(
                listOf("wm", "user-rotation") to CommandResult.success("lock 0\n"),
                listOf("wm", "user-rotation", "lock", "1") to CommandResult.failure("unknown"),
                listOf("settings", "put", "system", "accelerometer_rotation", "0") to CommandResult.success(""),
                listOf("settings", "put", "system", "user_rotation", "1") to CommandResult.success(""),
            ),
        )
        val result = fake.rotateDeviceDisplay("serial", isEmulator = false)
        assertTrue(result.isSuccess)
        assertEquals("Rotated to landscape", result.stdout)
        assertTrue(fake.shellCommands.contains(listOf("settings", "put", "system", "user_rotation", "1")))
    }

    @Test
    fun ensureEmulatorOrientationWaitsForLandscapeReconfiguration() = runBlocking {
        val fake = RecordingDeviceService(
            display0SizeQueue = ArrayDeque(
                listOf(
                    "1080x2400", // ensure: still portrait
                    "1080x2400", // direct lock 1: reconfiguration still pending
                    "2400x1080", // direct lock 1: landscape after polling
                    "2400x1080", // ensure: verify after delay
                ),
            ),
            shellResponses = mapOf(
                listOf("wm", "fixed-to-user-rotation", "enabled") to CommandResult.success(""),
                listOf("wm", "user-rotation", "lock", "1") to CommandResult.success(""),
                listOf("wm", "user-rotation", "lock", "3") to CommandResult.success(""),
            ),
        )
        val result = fake.ensureEmulatorOrientation("emulator-5554", "landscape")
        assertTrue(result.isSuccess)
        assertTrue(fake.emuCommands.isEmpty())
        assertEquals("Oriented landscape", result.stdout)
        assertTrue(fake.shellCommands.contains(listOf("wm", "user-rotation", "lock", "1")))
        assertFalse(fake.shellCommands.contains(listOf("wm", "user-rotation", "lock", "3")))
    }

    @Test
    fun ensureEmulatorOrientationNoopsWhenAlreadyMatching() = runBlocking {
        val fake = RecordingDeviceService(
            display0SizeQueue = ArrayDeque(listOf("2400x1080")),
        )
        val result = fake.ensureEmulatorOrientation("emulator-5554", "landscape")
        assertTrue(result.isSuccess)
        assertTrue(fake.emuCommands.isEmpty())
        assertEquals("Already landscape", result.stdout)
    }

    @Test
    fun ensureEmulatorOrientationPrefersDumpsysLogicalSizeOverPhysicalWmSize() = runBlocking {
        val fake = RecordingDeviceService(
            // Physical panel stays portrait; logical display is already landscape.
            wmSizeQueue = ArrayDeque(listOf("1200x1920")),
            display0SizeQueue = ArrayDeque(listOf("1920x1200")),
        )
        val result = fake.ensureEmulatorOrientation("emulator-5554", "landscape")
        assertTrue(result.isSuccess)
        assertTrue(fake.emuCommands.isEmpty())
        assertEquals("Already landscape", result.stdout)
    }

    @Test
    fun userRotationLabelCoversQuarterTurns() {
        assertEquals("portrait", userRotationLabel(0))
        assertEquals("landscape", userRotationLabel(1))
        assertEquals("reverse portrait", userRotationLabel(2))
        assertEquals("reverse landscape", userRotationLabel(3))
    }

    @Test
    fun parseWmUserRotationReadsLockAndTreatsFreeAsUnknown() {
        assertEquals(1, parseWmUserRotation("lock 1"))
        assertNull(parseWmUserRotation("free"))
        assertEquals(3, parseWmUserRotation("lock 3\n"))
        assertEquals(2, parseWmUserRotation("2"))
    }

    @Test
    fun quarterTurnFromLogicalSizeFollowsAspect() {
        assertEquals(0, quarterTurnFromLogicalSize(1080 to 2400))
        assertEquals(1, quarterTurnFromLogicalSize(2400 to 1080))
        assertEquals(0, quarterTurnFromLogicalSize(null))
    }

    @Test
    fun physicalRotateFromFreeUsesCurrentAspect() = runBlocking {
        val fake = RecordingDeviceService(
            display0SizeQueue = ArrayDeque(listOf("2400x1080", "2400x1080", "1080x2400")),
            shellResponses = mapOf(
                listOf("wm", "user-rotation") to CommandResult.success("free\n"),
                listOf("wm", "user-rotation", "lock", "0") to CommandResult.success(""),
            ),
        )
        val result = fake.rotateDeviceDisplay("serial", isEmulator = false)
        assertTrue(result.isSuccess)
        // Landscape + free must target portrait (0), not re-lock landscape (1).
        assertTrue(fake.shellCommands.contains(listOf("wm", "user-rotation", "lock", "0")))
        assertFalse(fake.shellCommands.contains(listOf("wm", "user-rotation", "lock", "1")))
    }

    @Test
    fun logicalOrientationChangedDetectsAspectFlip() {
        assertTrue(logicalOrientationChanged(1080 to 2400, 2400 to 1080))
        assertFalse(logicalOrientationChanged(1080 to 2400, 1080 to 2400))
        assertFalse(logicalOrientationChanged(null, 2400 to 1080))
    }

    private class RecordingDeviceService(
        private val shellResponses: Map<List<String>, CommandResult> = emptyMap(),
        private val wmSizeQueue: ArrayDeque<String> = ArrayDeque(),
        private val display0SizeQueue: ArrayDeque<String> = ArrayDeque(),
        val grpcRotations: MutableList<Int> = mutableListOf(),
        /** null models a device with no reachable virtual accelerometer. */
        private var sensorRotation: Int? = null,
        /** false models a sensor that accepts the write but never moves. */
        private val sensorAcceptsWrites: Boolean = true,
        /** Number of post-write reads that still report the old physical-model value. */
        private val sensorReadLagAfterWrite: Int = 0,
    ) : DeviceService {
        val emuCommands = mutableListOf<List<String>>()
        val shellCommands = mutableListOf<List<String>>()
        private var lastDisplay0Size: String? = null
        private var pendingSensorRotation: Int? = null
        private var sensorReadsUntilSettled = 0

        override suspend fun discoverSdk(): SdkDiscovery =
            SdkDiscovery(null, null, null, null, null, emptyList())
        override suspend fun listDevices(): List<AndroidDevice> = emptyList()
        override suspend fun shell(serial: String, command: List<String>): CommandResult {
            shellCommands += command
            if (command == listOf("dumpsys", "window", "displays") &&
                (display0SizeQueue.isNotEmpty() || lastDisplay0Size != null)
            ) {
                // The display keeps reporting its last size once the script runs out —
                // rotationOutcome polls until the aspect settles rather than reading once.
                val size = display0SizeQueue.removeFirstOrNull()?.also { lastDisplay0Size = it }
                    ?: lastDisplay0Size!!
                return CommandResult.success(
                    """
                    Display: mDisplayId=0 (organized)
                      init=$size cur=$size app=$size
                    """.trimIndent(),
                )
            }
            if (command == listOf("wm", "size") && wmSizeQueue.isNotEmpty()) {
                return CommandResult.success("Physical size: ${wmSizeQueue.removeFirst()}\n")
            }
            return shellResponses[command] ?: CommandResult.success("OK\n")
        }
        override suspend fun emu(serial: String, command: List<String>): CommandResult {
            emuCommands += command
            return CommandResult.success("OK\n")
        }
        override suspend fun applyEmulatorDisplayRotation(serial: String, quarterTurn: Int): CommandResult {
            if (sensorRotation == null) return CommandResult.failure("No emulator gRPC")
            grpcRotations += quarterTurn
            if (sensorAcceptsWrites && sensorReadLagAfterWrite > 0) {
                pendingSensorRotation = quarterTurn
                sensorReadsUntilSettled = sensorReadLagAfterWrite
            } else if (sensorAcceptsWrites) {
                sensorRotation = quarterTurn
            }
            return CommandResult.success("Rotated emulator framebuffer")
        }
        override suspend fun readEmulatorDisplayRotation(serial: String): Int? {
            if (sensorReadsUntilSettled > 0) {
                sensorReadsUntilSettled--
                return sensorRotation
            }
            pendingSensorRotation?.let {
                sensorRotation = it
                pendingSensorRotation = null
            }
            return sensorRotation
        }
        override suspend fun pair(host: String, port: Int, code: String): CommandResult =
            CommandResult.success("ok")
        override suspend fun connect(host: String, port: Int): CommandResult = CommandResult.success("ok")
        override suspend fun disconnect(serial: String): CommandResult = CommandResult.success("ok")
        override suspend fun listMdnsServices(): List<MdnsService> = emptyList()
        override suspend fun mdnsAvailable(): Boolean = false
        override suspend fun generatePairingQr(content: String): ByteArray? = null
    }
}
