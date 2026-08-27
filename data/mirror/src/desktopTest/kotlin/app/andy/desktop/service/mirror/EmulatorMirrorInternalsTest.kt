package app.andy.desktop.service.mirror

import app.andy.desktop.service.EMULATOR_VSYNC_RATE_ENV
import app.andy.desktop.service.emulatorGuestRefreshShellCommands
import app.andy.desktop.service.emulatorVsyncRate
import app.andy.desktop.service.emulator.EmulatorGrpcProto
import app.andy.desktop.service.emulator.PHYSICAL_TYPE_ROTATION
import app.andy.desktop.service.emulator.ROTATION_DEGREES
import app.andy.desktop.service.emulator.quarterTurnForRoll
import app.andy.desktop.service.mirror.EmulatorDisplaySize
import app.andy.desktop.service.mirror.ScrcpyServerLocator
import app.andy.desktop.service.mirror.effectiveEmulatorTouchDisplaySize
import app.andy.desktop.service.mirror.emulatorRgb888ToArgb
import app.andy.desktop.service.mirror.scaledEmulatorTouchPoint
import app.andy.service.MirrorFrame
import java.io.File
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EmulatorMirrorInternalsTest {
    @Test
    fun scrcpyServerLocatorUsesBundledResource() {
        val originalHome = System.getProperty("user.home")
        val testHome = kotlin.io.path.createTempDirectory("andy-scrcpy-test-home").toFile()
        val serverInfo = try {
            System.setProperty("user.home", testHome.absolutePath)
            ScrcpyServerLocator.find()?.let { server ->
                Triple(server.absolutePath, server.isFile, server.length())
            }
        } finally {
            System.setProperty("user.home", originalHome)
            testHome.deleteRecursively()
        }

        assertNotNull(serverInfo)
        assertTrue(serverInfo.second)
        assertTrue(serverInfo.third > 50_000)
        assertEquals(File(testHome, ".andy/scrcpy/andy-scrcpy-server-v4").absolutePath, serverInfo.first)
    }
    @Test
    fun emulatorVsyncRateDefaultsTo120AndHonorsEnvOverride() {
        assertEquals(120, emulatorVsyncRate { null })
        assertEquals(120, emulatorVsyncRate { "" })
        assertEquals(120, emulatorVsyncRate { "0" })
        assertEquals(120, emulatorVsyncRate { "nope" })
        assertEquals(90, emulatorVsyncRate { key ->
            if (key == EMULATOR_VSYNC_RATE_ENV) "90" else null
        })
    }

    @Test
    fun emulatorGuestRefreshShellCommandsForcePeakAndMin() {
        assertEquals(
            listOf(
                listOf("settings", "put", "system", "peak_refresh_rate", "120"),
                listOf("settings", "put", "system", "min_refresh_rate", "120"),
            ),
            emulatorGuestRefreshShellCommands(120),
        )
        assertEquals(
            listOf(
                listOf("settings", "put", "system", "peak_refresh_rate", "120"),
                listOf("settings", "put", "system", "min_refresh_rate", "120"),
                listOf("cmd", "display", "set-user-preferred-display-mode", "1080", "2424", "120", "0", "false"),
            ),
            emulatorGuestRefreshShellCommands(120, displayWidth = 1080, displayHeight = 2424),
        )
    }

    @Test
    fun emulatorGrpcProtoRequestsRgbFramesAndParsesImage() {
        assertContentEquals(
            byteArrayOf(8, 2, 24, 0xd0.toByte(), 5, 32, 0xd0.toByte(), 5),
            EmulatorGrpcProto.imageFormat(720),
        )
        val format = EmulatorGrpcProto.ProtoWriter().apply {
            varint(1, 2)
            varint(3, 2)
            varint(4, 2)
        }
        val imageBytes = byteArrayOf(
            0, 0, 255.toByte(),
            255.toByte(), 255.toByte(), 255.toByte(),
            255.toByte(), 0, 0,
            0, 255.toByte(), 0,
        )
        val image = EmulatorGrpcProto.ProtoWriter().apply {
            bytes(1, format.toByteArray())
            bytes(4, imageBytes)
            varint(5, 7)
            varint(6, 123)
        }

        val parsed = EmulatorGrpcProto.parseImage(image.toByteArray())

        assertEquals(2, parsed.width)
        assertEquals(2, parsed.height)
        assertEquals(7, parsed.seq)
        assertEquals(123, parsed.timestampUs)
        assertContentEquals(imageBytes, parsed.pixels)
    }

    @Test
    fun emulatorGrpcProtoRoundTripsEveryQuarterTurnOnTheZAxis() {
        // Each turn must read back as the same turn, or the Rotate button cannot advance
        // from the sensor's current position. X and Y only tilt the device — a rotation
        // written there leaves the screen facing the way it already was.
        ROTATION_DEGREES.forEachIndexed { turn, degrees ->
            val encoded = EmulatorGrpcProto.physicalModelValue(
                PHYSICAL_TYPE_ROTATION,
                listOf(0f, 0f, degrees),
            )

            val parsed = EmulatorGrpcProto.parsePhysicalModelValue(encoded)

            assertEquals(PHYSICAL_TYPE_ROTATION, parsed.physicalType)
            assertEquals(listOf(0f, 0f, degrees), parsed.values)
            assertEquals(turn, quarterTurnForRoll(parsed.values[2]))
        }
    }

    @Test
    fun quarterTurnForRollNormalisesNegativeAndOverflowingAngles() {
        assertEquals(0, quarterTurnForRoll(0f))
        assertEquals(2, quarterTurnForRoll(180f))
        assertEquals(3, quarterTurnForRoll(270f))
        assertEquals(0, quarterTurnForRoll(360f))
        // The emulator reports the rotation it is holding, which can come back negative.
        assertEquals(3, quarterTurnForRoll(-90f))
        assertEquals(1, quarterTurnForRoll(89.6f))
    }

    @Test
    fun emulatorRgbFramesKeepTopDownOrientation() {
        val rgb = ByteBuffer.wrap(
            byteArrayOf(
                255.toByte(), 0, 0, // top-left red
                0, 255.toByte(), 0, // top-right green
                0, 0, 255.toByte(), // bottom-left blue
                255.toByte(), 255.toByte(), 255.toByte(), // bottom-right white
            ),
        )

        val argb = emulatorRgb888ToArgb(width = 2, height = 2, rgb = rgb)

        assertContentEquals(
            intArrayOf(
                0xffff0000.toInt(),
                0xff00ff00.toInt(),
                0xff0000ff.toInt(),
                0xffffffff.toInt(),
            ),
            argb,
        )
        assertEquals(0, rgb.position(), "conversion should not mutate caller-owned ByteBuffer position")
    }

    @Test
    fun emulatorGrpcTouchCoordinatesScaleFromMirrorFrameToDisplaySize() {
        val frame = MirrorFrame(
            width = 324,
            height = 720,
            argb = IntArray(324 * 720),
            frameNumber = 1,
        )

        val middle = scaledEmulatorTouchPoint(
            x = 162,
            y = 360,
            frame = frame,
            displaySize = EmulatorDisplaySize(1080, 2400),
        )
        val clamped = scaledEmulatorTouchPoint(
            x = 999,
            y = 999,
            frame = frame,
            displaySize = EmulatorDisplaySize(1080, 2400),
        )

        assertEquals(540, middle.x)
        assertEquals(1200, middle.y)
        assertEquals(1076, clamped.x)
        assertEquals(2396, clamped.y)
    }

    @Test
    fun emulatorGrpcTouchUsesFrameAspectWhenDisplaySizeIsStalePortrait() {
        val landscapeFrame = MirrorFrame(
            width = 1080,
            height = 674,
            argb = IntArray(1080 * 674),
            frameNumber = 1,
        )
        // Stale physical panel size after rotate — aspect disagrees with the live frame.
        val stalePortrait = EmulatorDisplaySize(1200, 1920)
        val effective = effectiveEmulatorTouchDisplaySize(landscapeFrame, stalePortrait)
        assertNotNull(effective)
        assertTrue(effective!!.width > effective.height, "effective size must follow landscape frame")

        val middle = scaledEmulatorTouchPoint(
            x = 540,
            y = 337,
            frame = landscapeFrame,
            displaySize = stalePortrait,
        )
        assertEquals(effective.width / 2, middle.x)
        assertTrue(kotlin.math.abs(middle.y - effective.height / 2) <= 1)
    }
}
