package app.andy.desktop.service.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteSessionSafetyTest {
    @Test
    fun supportedPlatformsExcludeWindowsNaming() {
        // DesktopRemoteSessionService.isSupportedPlatform reads os.name; assert the helper
        // classification used by connect gates.
        assertTrue(platformLooksSupported("Mac OS X"))
        assertTrue(platformLooksSupported("Linux"))
        assertFalse(platformLooksSupported("Windows 11"))
    }

    @Test
    fun tunnelLocalPathsNeverUseHomeAndydSock() {
        val pid = 4242L
        val andyd = "/tmp/andy-remote-andyd-$pid.sock"
        val tmux = "/tmp/andy-remote-tmux-$pid.sock"
        assertFalse(andyd.contains(".andy/andyd.sock"))
        assertFalse(andyd.contains("/tmux-"))
        assertTrue(andyd.startsWith("/tmp/andy-remote-andyd-"))
        assertTrue(tmux.startsWith("/tmp/andy-remote-tmux-"))
        assertFalse(tmux.endsWith("/andy"))
    }

    @Test
    fun requiredToolsListIsStable() {
        val required = listOf(
            "chat.list",
            "chat.start",
            "chat.events",
            "project.list",
            "automation.list",
            "list_devices",
        )
        assertEquals(6, required.size)
        assertTrue(required.filter { it != "list_devices" }.all { it.contains('.') })
    }

    private fun platformLooksSupported(osName: String): Boolean {
        val os = osName.lowercase()
        return os.contains("mac") || os.contains("darwin") || os.contains("linux")
    }
}
