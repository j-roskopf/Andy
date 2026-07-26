package app.andy.desktop.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentNotificationDedupTest {
    @Test
    fun suppressesSecondNotificationWithinWindow() {
        assertTrue(AgentNotificationDedup.tryMarkNotified("task-1"))
        assertFalse(AgentNotificationDedup.tryMarkNotified("task-1"))
        assertTrue(AgentNotificationDedup.shouldSuppress("task-1"))
    }

    @Test
    fun differentKindsNotifySeparately() {
        assertTrue(AgentNotificationDedup.tryMarkNotified("task-2", "Done"))
        assertTrue(AgentNotificationDedup.tryMarkNotified("task-2", "Error"))
    }
}
