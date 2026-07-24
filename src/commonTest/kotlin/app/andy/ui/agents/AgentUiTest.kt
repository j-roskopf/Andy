package app.andy.ui.agents

import app.andy.model.AgentSessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentUiTest {
    @Test
    fun formatsCostsWithoutFloatingPointTruncation() {
        assertEquals("$0.5700", formatCost(0.57))
        assertEquals("~$0.0421", formatCost(0.0421, estimated = true))
        assertNull(formatCost(Double.NaN))
    }

    @Test
    fun elapsedFreezesWhenSessionIsDoneOrTaskInactive() {
        assertTrue(isElapsedLive(isActive = true, sessionStatus = null))
        assertTrue(isElapsedLive(isActive = true, sessionStatus = AgentSessionStatus.Working))
        assertFalse(isElapsedLive(isActive = true, sessionStatus = AgentSessionStatus.Idle))
        assertFalse(isElapsedLive(isActive = true, sessionStatus = AgentSessionStatus.Done))
        assertFalse(isElapsedLive(isActive = false, sessionStatus = null))
        assertFalse(isElapsedLive(isActive = false, sessionStatus = AgentSessionStatus.Done))
    }

    @Test
    fun sessionSpinnerStopsWhenIdleBlockedOrDone() {
        assertTrue(isSessionWorking(isActive = true, sessionStatus = null))
        assertTrue(isSessionWorking(isActive = true, sessionStatus = AgentSessionStatus.Working))
        assertFalse(isSessionWorking(isActive = true, sessionStatus = AgentSessionStatus.Idle))
        assertFalse(isSessionWorking(isActive = true, sessionStatus = AgentSessionStatus.Done))
        assertFalse(isSessionWorking(isActive = true, sessionStatus = AgentSessionStatus.Blocked))
        assertFalse(isSessionWorking(isActive = false, sessionStatus = AgentSessionStatus.Working))
    }

    @Test
    fun formatElapsedUsesEndMillisWhenProvided() {
        assertEquals("5s", formatElapsed(startMillis = 1_000, endMillis = 6_000, nowMillis = 99_000))
        assertEquals("1m 30s", formatElapsed(startMillis = 0, endMillis = 90_000, nowMillis = 999_000))
        assertEquals("10s", formatElapsed(startMillis = 0, endMillis = null, nowMillis = 10_000))
    }
}
