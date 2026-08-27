package app.andy.ui.live

import app.andy.service.MirrorBackend
import app.andy.service.MirrorBackendKind
import app.andy.service.MirrorFrame
import app.andy.service.MirrorRendererMode
import app.andy.service.MirrorSession
import app.andy.service.MirrorStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveStreamChipsTest {
    @Test
    fun structuredSessionProducesReadableChips() {
        val session = MirrorSession(
            serial = "emulator-5554",
            requestedMode = MirrorRendererMode.Legacy,
            backend = MirrorBackend(
                kind = MirrorBackendKind.LegacyCpu,
                decoder = "FFmpeg software decode",
                renderer = "Swing BufferedImage",
            ),
            stats = MirrorStats(displayedFps = 60f, droppedFrames = 2),
            width = 1080,
            height = 2400,
        )

        val chips = liveStreamChips(session, frame = null, mirrorStatus = "Connected")

        assertEquals("1080×2400", chips.first().label)
        assertTrue(chips.any { it.label == "60 fps" && it.tone == LiveStreamChipTone.Active })
        assertTrue(chips.any { it.label == "FFmpeg software decode" })
        assertTrue(chips.any { it.label == "2 dropped" && it.tone == LiveStreamChipTone.Warning })
    }

    @Test
    fun fallsBackToStatusWhenNoSession() {
        val chips = liveStreamChips(session = null, frame = MirrorFrame(720, 1280, IntArray(0)), mirrorStatus = "Connecting")

        assertEquals("720×1280", chips.first().label)
    }
}
