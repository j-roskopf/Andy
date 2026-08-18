package app.andy.ui.shell

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentScrollBusyRegistryTest {
    @Test
    fun beginEndTracksBusyScroll() {
        while (ContentScrollBusyRegistry.anyBusy) {
            ContentScrollBusyRegistry.end()
        }
        assertFalse(ContentScrollBusyRegistry.anyBusy)
        ContentScrollBusyRegistry.begin()
        assertTrue(ContentScrollBusyRegistry.anyBusy)
        ContentScrollBusyRegistry.end()
        assertFalse(ContentScrollBusyRegistry.anyBusy)
    }

    @Test
    fun nestedBeginRequiresMatchingEnds() {
        while (ContentScrollBusyRegistry.anyBusy) {
            ContentScrollBusyRegistry.end()
        }
        ContentScrollBusyRegistry.begin()
        ContentScrollBusyRegistry.begin()
        assertTrue(ContentScrollBusyRegistry.anyBusy)
        ContentScrollBusyRegistry.end()
        assertTrue(ContentScrollBusyRegistry.anyBusy)
        ContentScrollBusyRegistry.end()
        assertFalse(ContentScrollBusyRegistry.anyBusy)
    }

    @Test
    fun endNeverDropsBelowZero() {
        while (ContentScrollBusyRegistry.anyBusy) {
            ContentScrollBusyRegistry.end()
        }
        ContentScrollBusyRegistry.end()
        assertFalse(ContentScrollBusyRegistry.anyBusy)
    }
}
