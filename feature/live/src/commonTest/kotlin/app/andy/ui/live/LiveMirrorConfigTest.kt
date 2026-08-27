package app.andy.ui.live

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveMirrorConfigTest {
    @Test
    fun zeroMaxEdgeRequestsNativeResolution() {
        val config = mirrorVideoConfig("0", "16", "60")

        assertEquals(0, config.maxSize)
        assertEquals(16_000_000, config.bitRate)
        assertEquals(60, config.maxFps)
    }

    @Test
    fun manualMirrorValuesAreBounded() {
        val config = mirrorVideoConfig("99999", "200", "5")

        assertEquals(4_320, config.maxSize)
        assertEquals(80_000_000, config.bitRate)
        assertEquals(15, config.maxFps)
    }
}
