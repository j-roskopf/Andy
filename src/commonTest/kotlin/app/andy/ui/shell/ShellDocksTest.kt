package app.andy.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellDocksTest {
    private fun leaf(id: String, runId: String) =
        TerminalPaneNode.Leaf(id, listOf(DockTab.terminal(runId)), "terminal:$runId")

    private fun terminalWorkspaceTab(id: String, runId: String, leafId: String, title: String? = null) =
        DockTab.terminalWorkspace(id, leaf(leafId, runId), leafId, title)

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
    fun terminalTabsAreIndependentNotSingleton() {
        val pane = DockPane()
            .withTab(terminalWorkspaceTab("tab-1", "run-1", "leaf-1"))
            .withTab(terminalWorkspaceTab("tab-2", "run-2", "leaf-2"))
        assertEquals(2, pane.tabs.count { it.kind == DockTabKind.Terminal })
        assertEquals("tab-2", pane.activeTabId)
    }

    @Test
    fun browserTabIsSingletonPerPane() {
        val pane = DockPane()
            .withTab(DockTab.browser("browser-1"))
            .withTab(DockTab.browser("browser-2"))
        assertEquals(1, pane.tabs.count { it.kind == DockTabKind.Browser })
        assertEquals("browser-1", pane.activeTabId)
    }

    @Test
    fun reopeningSameBrowserTabIdSelectsInPlace() {
        val pane = DockPane()
            .withTab(DockTab.browser("browser-1"))
            .withTab(DockTab.logs())
            .withTab(DockTab.browser("browser-1"))
        assertEquals(2, pane.tabs.size)
        assertEquals("browser-1", pane.activeTabId)
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
    fun browserIsExclusiveAcrossPlacements() {
        val docks = ShellDocks()
            .withBrowserExclusive(DockPlacement.Right, DockTab.browser("browser-1"))
            .withBrowserExclusive(DockPlacement.Bottom, DockTab.browser("browser-2"))
        assertTrue(docks.bottom.tabs.any { it.id == "browser-1" && it.kind == DockTabKind.Browser })
        assertFalse(docks.right.tabs.any { it.kind == DockTabKind.Browser })
        assertEquals(1, (docks.right.tabs + docks.bottom.tabs).count { it.kind == DockTabKind.Browser })
    }

    @Test
    fun focusingDistinctNewRunsCreatesSeparateTopLevelTabs() {
        val docks = ShellDocks()
            .withTerminalExclusive(DockPlacement.Right, "run-1", newTabId = "tab-1", newLeafId = "leaf-1")
            .withTerminalExclusive(DockPlacement.Right, "run-2", newTabId = "tab-2", newLeafId = "leaf-2")
        val pane = docks.right
        assertEquals(2, pane.tabs.count { it.kind == DockTabKind.Terminal })
        assertEquals(
            setOf("run-1", "run-2"),
            pane.tabs.flatMap { it.terminalTree?.flattenTabs().orEmpty() }.mapNotNull { it.runId }.toSet(),
        )
    }

    @Test
    fun focusingAnAlreadyOpenRunSelectsItInPlace() {
        val docks = ShellDocks()
            .withTerminalExclusive(DockPlacement.Right, "run-1", newTabId = "tab-1", newLeafId = "leaf-1")
            .withTerminalExclusive(DockPlacement.Right, "run-2", newTabId = "tab-2", newLeafId = "leaf-2")
            .withTerminalExclusive(DockPlacement.Right, "run-1", newTabId = "tab-3", newLeafId = "leaf-3")
        // Refocusing run-1 must not spawn a third tab — it selects the existing tab-1.
        assertEquals(2, docks.right.tabs.count { it.kind == DockTabKind.Terminal })
        assertEquals("tab-1", docks.right.activeTabId)
    }

    @Test
    fun terminalMovesBetweenPlacements() {
        val docks = ShellDocks()
            .withTerminalExclusive(DockPlacement.Right, "run-1", newTabId = "tab-1", newLeafId = "leaf-1")
            .withTerminalExclusive(DockPlacement.Bottom, "run-1", newTabId = "tab-2", newLeafId = "leaf-2")
        assertTrue(docks.bottom.tabOwningRun("run-1") != null)
        assertTrue(docks.right.tabs.none { it.kind == DockTabKind.Terminal })
        assertTrue(docks.bottom.visible)
    }

    @Test
    fun hideKeepsTabsForLater() {
        val pane = DockPane().withTab(DockTab.logs()).hide()
        assertFalse(pane.visible)
        assertEquals(1, pane.tabs.size)
    }

    @Test
    fun renameTabKeepsIdentityAndNormalizesTitle() {
        val pane = DockPane()
            .withTab(DockTab.logs())
            .renameTab("logs", "  Output  ")

        assertEquals("logs", pane.activeTabId)
        assertEquals("Output", pane.activeTab?.title)
    }

    @Test
    fun movingLivePreservesCustomTitle() {
        val docks = ShellDocks()
            .withLiveExclusive(DockPlacement.Right)
            .update(DockPlacement.Right) { it.renameTab("live", "Phone") }
            .withLiveExclusive(DockPlacement.Bottom)

        assertEquals("Phone", docks.bottom.tabs.single { it.kind == DockTabKind.Live }.title)
        assertFalse(docks.right.tabs.any { it.kind == DockTabKind.Live })
    }

    @Test
    fun movingTerminalPreservesCustomTitle() {
        val opened = ShellDocks()
            .withTerminalExclusive(DockPlacement.Right, "run-1", newTabId = "tab-1", newLeafId = "leaf-1", title = "Build")
        val moved = opened.withTerminalExclusive(DockPlacement.Bottom, "run-1", newTabId = "tab-2", newLeafId = "leaf-2")

        assertEquals("Build", moved.bottom.tabOwningRun("run-1")?.terminalTree?.leafOwningRun("run-1")?.activeTab?.title)
        assertTrue(moved.right.tabs.none { it.kind == DockTabKind.Terminal })
    }

    @Test
    fun existingBrowserTabPrefersVisibleActiveBrowser() {
        val docks = ShellDocks(
            right = DockPane().withTab(DockTab.browser("browser-right")),
            bottom = DockPane().withTab(DockTab.browser("browser-bottom")),
        )
        assertEquals(DockPlacement.Right to "browser-right", docks.existingBrowserTab())
    }

    @Test
    fun existingBrowserTabUsesBottomWhenRightHasNoBrowser() {
        val docks = ShellDocks(
            right = DockPane().withTab(DockTab.logs()),
            bottom = DockPane().withTab(DockTab.browser("browser-bottom")),
        )
        assertEquals(DockPlacement.Bottom to "browser-bottom", docks.existingBrowserTab())
    }

    @Test
    fun existingBrowserTabFindsHiddenBrowserInsteadOfCreating() {
        val docks = ShellDocks(
            right = DockPane().withTab(DockTab.browser("browser-hidden")).hide(),
            bottom = DockPane().withTab(DockTab.logs()),
        )
        assertEquals(DockPlacement.Right to "browser-hidden", docks.existingBrowserTab())
    }

    @Test
    fun existingBrowserTabPrefersNewestWhenActiveIsNotBrowser() {
        val docks = ShellDocks(
            right = DockPane()
                .withTab(DockTab.browser("browser-only"))
                .withTab(DockTab.logs()),
        )
        assertEquals(DockPlacement.Right to "browser-only", docks.existingBrowserTab())
    }

    @Test
    fun existingBrowserTabIsNullWhenNoneOpen() {
        val docks = ShellDocks(right = DockPane().withTab(DockTab.logs()))
        assertNull(docks.existingBrowserTab())
    }

    @Test
    fun selectingExistingBrowserTabRevealsHiddenPane() {
        val docks = ShellDocks(
            right = DockPane().withTab(DockTab.browser("browser-1")).hide(),
        )
        val revealed = docks.update(DockPlacement.Right) { it.selectTab("browser-1") }
        assertTrue(revealed.right.visible)
        assertEquals("browser-1", revealed.right.activeTabId)
    }

    @Test
    fun landingForCanToggleClearWithoutOpeningPane() {
        // Placement icon opens a landing chooser first; pressing again clears it without
        // making the pane visible (mirrors ShellState.onPlacementIconClick).
        val opened = ShellDocks().onPlacementIconClick(DockPlacement.Right)
        assertEquals(DockPlacement.Right, opened.landingFor)
        assertFalse(opened.right.visible)
        val dismissed = opened.onPlacementIconClick(DockPlacement.Right)
        assertNull(dismissed.landingFor)
        assertFalse(dismissed.right.visible)
    }

    @Test
    fun placementIconReopensHiddenPaneWhenTabsExist() {
        val hidden = ShellDocks(right = DockPane().withTab(DockTab.logs()).hide())
        val shown = hidden.onPlacementIconClick(DockPlacement.Right)
        assertTrue(shown.right.visible)
        assertEquals("logs", shown.right.activeTabId)
        assertNull(shown.landingFor)
        assertEquals(1, shown.right.tabs.size)
    }

    @Test
    fun placementIconHidesVisiblePaneWithoutDroppingTabs() {
        val open = ShellDocks(right = DockPane().withTab(DockTab.browser("browser-1")))
        val hidden = open.onPlacementIconClick(DockPlacement.Right)
        assertFalse(hidden.right.visible)
        assertEquals(1, hidden.right.tabs.size)
        assertNull(hidden.landingFor)
    }

    @Test
    fun placementIconShowsLandingOnlyWhenPaneIsEmpty() {
        val shown = ShellDocks().onPlacementIconClick(DockPlacement.Bottom)
        assertEquals(DockPlacement.Bottom, shown.landingFor)
        assertFalse(shown.bottom.visible)
    }
}
