package app.andy.ui.live

import app.andy.service.MirrorRendererMode
import app.andy.service.MirrorVideoConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveMirrorSettingsTest {
    @Test
    fun updateReplacesPreferredConfig() {
        val previous = LiveMirrorSettings.config.value
        try {
            val next = MirrorVideoConfig(
                maxSize = 720,
                bitRate = 4_000_000,
                maxFps = 30,
                rendererMode = MirrorRendererMode.Legacy,
            )
            LiveMirrorSettings.update(next)
            assertEquals(next, LiveMirrorSettings.config.value)
        } finally {
            LiveMirrorSettings.update(previous)
        }
    }
}
