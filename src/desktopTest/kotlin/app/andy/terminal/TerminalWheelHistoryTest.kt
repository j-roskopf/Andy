package app.andy.terminal

import java.awt.Component
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalWheelHistoryTest {
    @Test
    fun wheelTowardHistoryMatchesKetraNegatedPreciseRotation() {
        val towardHistory = mouseWheel(preciseRotation = -1.0)
        val towardLive = mouseWheel(preciseRotation = 1.0)

        assertEquals(3.0, terminalWheelScrollDelta(towardHistory))
        assertEquals(-3.0, terminalWheelScrollDelta(towardLive))
        assertTrue(terminalWheelTowardHistory(towardHistory))
        assertFalse(terminalWheelTowardHistory(towardLive))
        assertFalse(terminalWheelTowardHistory(mouseWheel(preciseRotation = 0.0)))
    }

    @Test
    fun trackpadPrecisionIsScaledByTheEventsLineAmount() {
        assertEquals(
            0.75,
            terminalWheelScrollDelta(mouseWheel(preciseRotation = -0.25)),
        )
    }

    @Test
    fun historyOpeningCoalescesWheelGesturesIntoOneRevealInsteadOfJumping() {
        val pending = PendingHistoryScroll()
        pending.add(0.75)
        pending.add(3.0)
        pending.add(1.5)
        pending.add(-3.0)

        assertEquals(3.0, pending.drain())
        assertEquals(0.0, pending.drain())
    }

    @Test
    fun nestedChildCountsAsWithinAncestor() {
        val root = JPanel()
        val child = JPanel()
        val leaf = JPanel()
        root.add(child)
        child.add(leaf)

        assertTrue(isComponentWithin(leaf, root))
        assertTrue(isComponentWithin(root, root))
        assertFalse(isComponentWithin(root, leaf))
        assertFalse(isComponentWithin(null as Component?, root))
    }

    @Test
    fun altScreenWheelUpOpensHistoryPeekInsteadOfLocking() {
        assertEquals(
            LiveTerminalWheelAction.OpenHistoryPeek,
            resolveLiveTerminalWheelAction(
                delta = 1.0,
                historySize = 0,
                atLiveViewport = true,
                canOpenHistoryPeek = true,
                canReturnToLive = false,
            ),
        )
    }

    @Test
    fun emulatorHistoryScrollsViewportWithoutPeek() {
        assertEquals(
            LiveTerminalWheelAction.ScrollViewport,
            resolveLiveTerminalWheelAction(
                delta = 1.0,
                historySize = 40,
                atLiveViewport = true,
                canOpenHistoryPeek = true,
                canReturnToLive = false,
            ),
        )
    }

    @Test
    fun completedReplayAlwaysRoutesWheelUpToItsViewport() {
        assertEquals(
            LiveTerminalWheelAction.ScrollViewport,
            resolveLiveTerminalWheelAction(
                delta = 1.0,
                historySize = 80,
                atLiveViewport = true,
                canOpenHistoryPeek = false,
                canReturnToLive = false,
            ),
        )
    }

    @Test
    fun historyPeekWheelDownAtBottomReturnsToLive() {
        assertEquals(
            LiveTerminalWheelAction.ReturnToLive,
            resolveLiveTerminalWheelAction(
                delta = -1.0,
                historySize = 80,
                atLiveViewport = true,
                canOpenHistoryPeek = false,
                canReturnToLive = true,
            ),
        )
        assertEquals(
            LiveTerminalWheelAction.ScrollViewport,
            resolveLiveTerminalWheelAction(
                delta = -1.0,
                historySize = 80,
                atLiveViewport = false,
                canOpenHistoryPeek = false,
                canReturnToLive = true,
            ),
        )
    }

    @Test
    fun zeroDeltaIsConsumedSoAltScreenDoesNotSeeIt() {
        assertEquals(
            LiveTerminalWheelAction.Consume,
            resolveLiveTerminalWheelAction(
                delta = 0.0,
                historySize = 0,
                atLiveViewport = true,
                canOpenHistoryPeek = true,
                canReturnToLive = false,
            ),
        )
    }

    @Test
    fun displacingWheelListenersPreventsPriorHandlersFromRunning() {
        // Mirrors LiveTerminalWheelHandler: remove existing listeners, install Andy's.
        val host = JPanel()
        var priorRan = false
        val prior = MouseWheelListener { priorRan = true }
        host.addMouseWheelListener(prior)

        val displaced = host.mouseWheelListeners
        displaced.forEach(host::removeMouseWheelListener)
        var andyRan = false
        host.addMouseWheelListener { andyRan = true }

        host.mouseWheelListeners.forEach { it.mouseWheelMoved(mouseWheel(preciseRotation = -1.0)) }

        assertTrue(andyRan)
        assertFalse(priorRan)
        assertEquals(1, host.mouseWheelListeners.size)

        // Restore like uninstall().
        host.mouseWheelListeners.forEach(host::removeMouseWheelListener)
        displaced.forEach(host::addMouseWheelListener)
        assertEquals(1, host.mouseWheelListeners.size)
        assertTrue(host.mouseWheelListeners.contains(prior))
    }

    private fun mouseWheel(
        preciseRotation: Double,
        scrollAmount: Int = 3,
    ): MouseWheelEvent {
        val source = JPanel()
        return MouseWheelEvent(
            source,
            MouseWheelEvent.MOUSE_WHEEL,
            System.currentTimeMillis(),
            0,
            0,
            0,
            0,
            0,
            0,
            false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL,
            scrollAmount,
            preciseRotation.toInt(),
            preciseRotation,
        )
    }
}
