package app.andy.desktop.service.mirror

import app.andy.service.MirrorInput
import app.andy.service.MirrorRendererMode
import app.andy.service.MirrorVideoConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndyMirrorProtocolTest {
    @Test
    fun startCommandCarriesRendererPolicyAndCaptureConfiguration() {
        val line = AndyMirrorProtocol.start(
            serial = "R3CXB056ZZB",
            config = MirrorVideoConfig(
                maxSize = 720,
                bitRate = 4_000_000,
                maxFps = 60,
                rendererMode = MirrorRendererMode.Accelerated,
            ),
        )

        assertTrue(line.endsWith("\n").not(), "Protocol writes one JSON object per line")
        assertTrue(line.contains("\"type\":\"start\""))
        assertTrue(line.contains("\"requestedMode\":\"accelerated\""))
        assertTrue(line.contains("\"maxFps\":60"))
    }

    @Test
    fun inputCommandUsesSourceCoordinatesWithoutAwtObjects() {
        val line = AndyMirrorProtocol.input(MirrorInput.Swipe(1, 2, 30, 40, 250))

        assertTrue(line.contains("\"type\":\"input\""))
        assertTrue(line.contains("\"startX\":1"))
        assertTrue(line.contains("\"durationMillis\":250"))
    }

    @Test
    fun readyEventReportsVerifiedHardwareBackend() {
        val event = AndyMirrorProtocol.decodeEvent(
            """{"type":"ready","decoder":"VideoToolbox","renderer":"Metal","decoderHardwareBacked":true,"rendererHardwareBacked":true,"hardwareBacked":true,"width":720,"height":1600}""",
        )

        assertEquals("ready", event.type)
        assertEquals("VideoToolbox", event.decoder)
        assertEquals("Metal", event.renderer)
        assertTrue(event.decoderHardwareBacked == true)
        assertTrue(event.rendererHardwareBacked == true)
        assertTrue(event.hardwareBacked == true)
        assertFalse(event.failureReason != null)
        assertTrue(event.isVerifiedHardwareReady())
    }

    @Test
    fun readyEventRejectsRendererOnlyAccelerationClaim() {
        val event = AndyMirrorProtocol.decodeEvent(
            """{"type":"ready","decoder":"FFmpeg H264","renderer":"Metal","decoderHardwareBacked":false,"rendererHardwareBacked":true,"hardwareBacked":true}""",
        )

        assertFalse(event.isVerifiedHardwareReady())
    }
}
