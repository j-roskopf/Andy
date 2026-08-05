package app.andy.ui.shell

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeavyweightOverlayRegistryTest {
    @Test
    fun pushPopTracksOpenOverlays() {
        while (HeavyweightOverlayRegistry.anyActive) {
            HeavyweightOverlayRegistry.pop()
        }
        assertFalse(HeavyweightOverlayRegistry.anyActive)
        HeavyweightOverlayRegistry.push()
        assertTrue(HeavyweightOverlayRegistry.anyActive)
        HeavyweightOverlayRegistry.pop()
        assertFalse(HeavyweightOverlayRegistry.anyActive)
    }

    @Test
    fun popNeverDropsBelowZero() {
        while (HeavyweightOverlayRegistry.anyActive) {
            HeavyweightOverlayRegistry.pop()
        }
        HeavyweightOverlayRegistry.pop()
        assertFalse(HeavyweightOverlayRegistry.anyActive)
    }
}
