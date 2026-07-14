package app.andy.ui.live

import app.andy.service.MirrorFrame
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MirrorMetadataTest {
    @Test
    fun webMetadataFramesUpdateEverySample() {
        val previous = MirrorFrame(664, 1440, IntArray(0), frameNumber = 17, decodedFps = 24f)
        val next = MirrorFrame(664, 1440, IntArray(0), frameNumber = 43, decodedFps = 26f)

        assertTrue(shouldUpdateMirrorMetadata(previous, next))
    }

    @Test
    fun pixelFramesRemainThrottledBetweenIntervals() {
        val previous = MirrorFrame(720, 1280, IntArray(1), frameNumber = 1)
        val next = MirrorFrame(720, 1280, IntArray(1), frameNumber = 2)

        assertFalse(shouldUpdateMirrorMetadata(previous, next))
    }
}
