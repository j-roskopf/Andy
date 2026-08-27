package app.andy.desktop.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentNotificationDedupTest {
    @Test
    fun suppressesDuplicateSameKindWithinWindow() {
        AgentNotificationDedup.clearForTests()
        assertTrue(AgentNotificationDedup.tryMarkNotified("task-1", "Done"))
        assertFalse(AgentNotificationDedup.tryMarkNotified("task-1", "Done"))
        assertTrue(AgentNotificationDedup.tryMarkNotified("task-1", "Blocked"))
    }

    @Test
    fun differentKindsNotifyIndependently() {
        AgentNotificationDedup.clearForTests()
        assertTrue(AgentNotificationDedup.tryMarkNotified("task-2", "Done"))
        assertTrue(AgentNotificationDedup.tryMarkNotified("task-2", "Error"))
    }

    @Test
    fun taskKeyWithoutKindUsesSeparateBucket() {
        AgentNotificationDedup.clearForTests()
        assertTrue(AgentNotificationDedup.tryMarkNotified("task-3"))
        assertFalse(AgentNotificationDedup.tryMarkNotified("task-3"))
        assertTrue(AgentNotificationDedup.tryMarkNotified("task-3", "Done"))
    }
}
