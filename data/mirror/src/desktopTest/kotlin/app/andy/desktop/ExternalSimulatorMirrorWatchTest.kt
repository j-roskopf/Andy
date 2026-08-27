package app.andy.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalSimulatorMirrorWatchTest {
    @Test
    fun waitsForWindowBeforeTreatingAbsenceAsClosed() {
        val watches = mapOf(
            "udid-1" to ExternalSimulatorMirrorWatch("udid-1", "iPhone 17 Pro"),
        )
        val stillWaiting = reconcileExternalSimulatorMirrors(watches) { false }
        assertEquals(watches.keys, stillWaiting.keys)
        assertFalse(stillWaiting.getValue("udid-1").seenWindow)
    }

    @Test
    fun marksWindowSeenWhenVisible() {
        val watches = mapOf(
            "udid-1" to ExternalSimulatorMirrorWatch("udid-1", "iPhone 17 Pro"),
        )
        val seen = reconcileExternalSimulatorMirrors(watches) { it == "iPhone 17 Pro" }
        assertTrue(seen.getValue("udid-1").seenWindow)
        assertEquals(setOf("udid-1"), seen.keys)
    }

    @Test
    fun dropsTargetAfterSeenWindowCloses() {
        val watches = mapOf(
            "udid-1" to ExternalSimulatorMirrorWatch("udid-1", "iPhone 17 Pro", seenWindow = true),
        )
        val resumed = reconcileExternalSimulatorMirrors(watches) { false }
        assertTrue(resumed.isEmpty())
    }

    @Test
    fun onlyDropsClosedTargetsWhenSeveralAreHandedOff() {
        val watches = mapOf(
            "phone" to ExternalSimulatorMirrorWatch("phone", "iPhone 17 Pro", seenWindow = true),
            "pad" to ExternalSimulatorMirrorWatch("pad", "iPad Pro", seenWindow = true),
        )
        val next = reconcileExternalSimulatorMirrors(watches) { it == "iPad Pro" }
        assertEquals(setOf("pad"), next.keys)
        assertTrue(next.getValue("pad").seenWindow)
    }
}
