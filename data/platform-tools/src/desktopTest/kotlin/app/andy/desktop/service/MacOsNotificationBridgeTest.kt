package app.andy.desktop.service

import app.andy.service.OpenAgentTaskRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacOsNotificationBridgeTest {
    @Test
    fun resourcePathUsesArchSpecificDylib() {
        assertEquals(
            "andy-notifications/macos-arm64/andy-notifications-jni.dylib",
            MacOsNotificationBridge.resourcePath("Mac OS X", "aarch64"),
        )
        assertEquals(
            "andy-notifications/macos-x86_64/andy-notifications-jni.dylib",
            MacOsNotificationBridge.resourcePath("Mac OS X", "x86_64"),
        )
        assertNull(MacOsNotificationBridge.resourcePath("Linux", "amd64"))
    }

    @Test
    fun activateStoresRequestAndInvokesHandler() {
        var opened = false
        PendingAgentTaskOpen.setActivationHandler { opened = true }
        try {
            PendingAgentTaskOpen.activate(OpenAgentTaskRequest("task-1", "project-1"))
            assertTrue(opened)
            assertEquals(OpenAgentTaskRequest("task-1", "project-1"), PendingAgentTaskOpen.consume())
            assertNull(PendingAgentTaskOpen.consume())
        } finally {
            PendingAgentTaskOpen.setActivationHandler(null)
            PendingAgentTaskOpen.consume()
        }
    }
}
