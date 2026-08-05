package app.andy.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellDocksTest {
    @Test
    fun withTabOpensPaneAndSelectsTab() {
        val pane = DockPane().withTab(DockTab.logs())
        assertTrue(pane.visible)
        assertEquals("logs", pane.activeTabId)
        assertEquals(1, pane.tabs.size)
    }

    @Test
    fun liveAndLogsAreSingletonPerPane() {
        val pane = DockPane()
            .withTab(DockTab.live())
            .withTab(DockTab.logs())
            .withTab(DockTab.live())
        assertEquals(2, pane.tabs.size)
        assertEquals("live", pane.activeTabId)
    }

    @Test
    fun closingLastTabClearsPane() {
        val pane = DockPane().withTab(DockTab.logs()).closeTab("logs")
        assertFalse(pane.visible)
        assertTrue(pane.tabs.isEmpty())
        assertNull(pane.activeTabId)
    }

    @Test
    fun liveIsExclusiveAcrossPlacements() {
        val docks = ShellDocks()
            .withLiveExclusive(DockPlacement.Right)
            .withLiveExclusive(DockPlacement.Bottom)
        assertTrue(docks.bottom.visible)
        assertTrue(docks.bottom.tabs.any { it.kind == DockTabKind.Live })
        assertFalse(docks.right.tabs.any { it.kind == DockTabKind.Live })
    }

    @Test
    fun multipleTerminalTabsCoexistInPane() {
        val pane = DockPane()
            .withTab(DockTab.terminal("run-1"))
            .withTab(DockTab.terminal("run-2"))
        assertEquals(2, pane.tabs.size)
        assertEquals("terminal:run-2", pane.activeTabId)
    }

    @Test
    fun terminalMovesBetweenPlacements() {
        val docks = ShellDocks()
            .withTerminalExclusive(DockPlacement.Right, "run-1")
            .withTerminalExclusive(DockPlacement.Bottom, "run-1")
        assertTrue(docks.bottom.tabs.any { it.runId == "run-1" })
        assertFalse(docks.right.tabs.any { it.runId == "run-1" })
        assertTrue(docks.bottom.visible)
    }

    @Test
    fun hideKeepsTabsForLater() {
        val pane = DockPane().withTab(DockTab.logs()).hide()
        assertFalse(pane.visible)
        assertEquals(1, pane.tabs.size)
    }
}
