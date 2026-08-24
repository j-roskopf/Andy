package app.andy.ui.live

import app.andy.service.MirrorVideoConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteMirrorTuningTest {
    @Test
    fun capsHighLocalPresetForRemoteTunnel() {
        val tuned = MirrorVideoConfig(maxSize = 1080, bitRate = 8_000_000, maxFps = 60).forRemoteTunnel()
        assertEquals(
            MirrorVideoConfig(
                maxSize = RemoteMirrorTuning.MAX_SIZE,
                bitRate = RemoteMirrorTuning.BIT_RATE,
                maxFps = RemoteMirrorTuning.MAX_FPS,
            ),
            tuned,
        )
    }

    @Test
    fun replacesNativeMaxSizeOnRemoteTunnel() {
        val tuned = MirrorVideoConfig(maxSize = 0, bitRate = 16_000_000, maxFps = 120).forRemoteTunnel()
        assertEquals(RemoteMirrorTuning.MAX_SIZE, tuned.maxSize)
        assertEquals(RemoteMirrorTuning.BIT_RATE, tuned.bitRate)
        assertEquals(RemoteMirrorTuning.MAX_FPS, tuned.maxFps)
    }

    @Test
    fun leavesLowRemotePresetUntouched() {
        val config = MirrorVideoConfig(maxSize = 540, bitRate = 2_000_000, maxFps = 30)
        assertEquals(config, config.forRemoteTunnel())
    }
}
