package app.andy

import app.andy.service.AndyPlatform
import app.andy.service.PlatformCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebPlatformContractTest {
    @Test
    fun webNavigationContainsOnlyDeviceCapabilities() {
        assertEquals(AndyPlatform.Web, PlatformCapabilities.Web.platform)
        assertEquals(
            listOf(
                AndyDestination.Devices,
                AndyDestination.Live,
                AndyDestination.Apps,
                AndyDestination.Logcat,
                AndyDestination.Intents,
                AndyDestination.Files,
                AndyDestination.Controls,
                AndyDestination.Performance,
                AndyDestination.Design,
                AndyDestination.Accessibility,
                AndyDestination.Bugs,
                AndyDestination.Settings,
            ),
            PlatformCapabilities.Web.destinations,
        )
        assertFalse(PlatformCapabilities.Web.avdManagement)
        assertFalse(PlatformCapabilities.Web.wifiPairing)
        assertFalse(PlatformCapabilities.Web.hostAutomation)
        assertFalse(PlatformCapabilities.Web.proxy)
        assertFalse(PlatformCapabilities.Web.mcp)
        assertFalse(PlatformCapabilities.Web.updates)
        assertTrue(PlatformCapabilities.Web.acceleratedMirror)
        assertFalse(PlatformCapabilities.Desktop.acceleratedMirror)
        assertTrue(PlatformCapabilities.Desktop.destinations.containsAll(AndyDestination.entries))
    }
}
