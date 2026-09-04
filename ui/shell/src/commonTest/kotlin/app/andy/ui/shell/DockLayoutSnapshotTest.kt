package app.andy.ui.shell

import app.andy.model.SavedDockLayout
import app.andy.model.SavedDockPane
import app.andy.model.SavedDockTab
import app.andy.model.SavedDockTabKind
import app.andy.model.SavedLiveNode
import app.andy.model.SavedSplitAxis
import app.andy.model.SavedTerminalNode
import app.andy.model.SavedTerminalSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DockLayoutSnapshotTest {
    private var seq = 0
    private var runSeq = 0
    private fun nextId(prefix: String) = "$prefix-${seq++}"
    private fun openShell(projectId: String?): String = "run-${runSeq++}"

    @Test
    fun roundTripsThreeTabsAcrossBothPanesWithOrderAndActiveTab() {
        val rightLive = DockTab.live("live-1", "leaf-1", targetId = "dev-1", title = "Device 1")
        val rightLogs = DockTab.logs()
        val termTree = TerminalPaneNode.Leaf("leaf-t", listOf(DockTab.terminal("run-10", "T1")), "terminal:run-10")
        val rightTerm = DockTab.terminalWorkspace("term-1", termTree, "leaf-t", "Terminal 1")

        val bottomBrowser = DockTab.browser("browser-1", "Andy Browser")
        val bottomChat = DockTab.chat("chat-1", agentTaskId = "task-1", parentChatTaskId = "parent-1", title = "Side Chat")

        val docks = ShellDocks(
            right = DockPane(tabs = listOf(rightLive, rightLogs, rightTerm), activeTabId = rightLogs.id, visible = true),
            bottom = DockPane(tabs = listOf(bottomBrowser, bottomChat), activeTabId = bottomChat.id, visible = true),
        )

        val saved = docks.toSavedLayout(
            id = "layout-1",
            name = "My Layout",
            savedAtMillis = 1000L,
            rightPaneWidth = 480f,
            bottomPaneHeight = 320f,
            browserUrlOf = { if (it == "browser-1") "https://andy.app" else null },
            projectIdOfRun = { if (it == "run-10") "proj-1" else null },
        )

        assertEquals("layout-1", saved.id)
        assertEquals("My Layout", saved.name)
        assertEquals(1, saved.right.activeTabIndex)
        assertEquals(3, saved.right.tabs.size)
        assertEquals(1, saved.bottom.activeTabIndex)
        assertEquals(2, saved.bottom.tabs.size)
        assertEquals(480f, saved.rightPaneWidth)
        assertEquals(320f, saved.bottomPaneHeight)

        val restore = saved.toRestore(
            nextId = ::nextId,
            openShell = ::openShell,
            isTargetAvailable = { true },
            isAgentTaskAlive = { true },
        )

        assertEquals(3, restore.docks.right.tabs.size)
        assertEquals(DockTabKind.Live, restore.docks.right.tabs[0].kind)
        assertEquals(DockTabKind.Logs, restore.docks.right.tabs[1].kind)
        assertEquals(DockTabKind.Terminal, restore.docks.right.tabs[2].kind)
        assertEquals(restore.docks.right.tabs[1].id, restore.docks.right.activeTabId)

        assertEquals(2, restore.docks.bottom.tabs.size)
        assertEquals(DockTabKind.Browser, restore.docks.bottom.tabs[0].kind)
        assertEquals(DockTabKind.Chat, restore.docks.bottom.tabs[1].kind)
        assertEquals(restore.docks.bottom.tabs[1].id, restore.docks.bottom.activeTabId)

        val restoredBrowserId = restore.docks.bottom.tabs[0].id
        assertEquals("https://andy.app", restore.browserPanes[restoredBrowserId]?.url)

        val restoredChat = restore.docks.bottom.tabs[1]
        assertEquals("task-1", restoredChat.agentTaskId)
        assertEquals("parent-1", restoredChat.parentChatTaskId)
        assertEquals("Side Chat", restoredChat.title)
    }

    @Test
    fun roundTripsTerminalSplitTreeWithWeightsInnerTabsAndTitles() {
        val leafA = TerminalPaneNode.Leaf(
            id = "leaf-a",
            tabs = listOf(DockTab.terminal("run-a1", "Tab A1"), DockTab.terminal("run-a2", "Tab A2")),
            activeTabId = "terminal:run-a2",
        )
        val leafB = TerminalPaneNode.Leaf(
            id = "leaf-b",
            tabs = listOf(DockTab.terminal("run-b1", "Tab B1")),
            activeTabId = "terminal:run-b1",
        )
        val splitTree = TerminalPaneNode.Split(
            id = "split-root",
            axis = SplitAxis.Row,
            children = listOf(leafA, leafB),
            weights = listOf(0.4f, 0.6f),
        )
        val termTab = DockTab.terminalWorkspace("term-tab", splitTree, "leaf-b", "Workspace")
        val docks = ShellDocks(right = DockPane(tabs = listOf(termTab), activeTabId = termTab.id, visible = true))

        val saved = docks.toSavedLayout(
            id = "layout-term",
            name = "Term Split",
            savedAtMillis = 2000L,
            rightPaneWidth = 500f,
            bottomPaneHeight = 250f,
            browserUrlOf = { null },
            projectIdOfRun = { "project-x" },
        )

        val restore = saved.toRestore(
            nextId = ::nextId,
            openShell = ::openShell,
            isTargetAvailable = { true },
            isAgentTaskAlive = { true },
        )

        val restoredTab = restore.docks.right.tabs.single()
        val restoredTree = restoredTab.terminalTree as TerminalPaneNode.Split
        assertEquals(SplitAxis.Row, restoredTree.axis)
        assertEquals(listOf(0.4f, 0.6f), restoredTree.weights)
        assertEquals(2, restoredTree.children.size)

        val restoredLeafA = restoredTree.children[0] as TerminalPaneNode.Leaf
        val restoredLeafB = restoredTree.children[1] as TerminalPaneNode.Leaf

        assertEquals(2, restoredLeafA.tabs.size)
        assertEquals("Tab A1", restoredLeafA.tabs[0].title)
        assertEquals("Tab A2", restoredLeafA.tabs[1].title)
        assertEquals(restoredLeafA.tabs[1].id, restoredLeafA.activeTabId)

        assertEquals(1, restoredLeafB.tabs.size)
        assertEquals("Tab B1", restoredLeafB.tabs[0].title)
        assertEquals(restoredLeafB.id, restoredTab.focusedTerminalLeafId)
    }

    @Test
    fun roundTripsLiveSplitTreeAndFocusedLeaf() {
        val leaf1 = LivePaneNode.Leaf("live-leaf-1", targetId = "device-1", title = "Device 1")
        val leaf2 = LivePaneNode.Leaf("live-leaf-2", targetId = "device-2", title = "Device 2")
        val liveTree = LivePaneNode.Split(
            id = "live-split-1",
            axis = SplitAxis.Column,
            children = listOf(leaf1, leaf2),
            weights = listOf(0.5f, 0.5f),
        )
        val liveTab = DockTab(
            id = "live-tab",
            kind = DockTabKind.Live,
            targetId = "device-2",
            title = "Device 2",
            liveTree = liveTree,
            focusedLiveLeafId = "live-leaf-2",
        )
        val docks = ShellDocks(right = DockPane(tabs = listOf(liveTab), activeTabId = liveTab.id, visible = true))

        val saved = docks.toSavedLayout(
            id = "layout-live",
            name = "Live Split",
            savedAtMillis = 3000L,
            rightPaneWidth = 460f,
            bottomPaneHeight = 300f,
            browserUrlOf = { null },
            projectIdOfRun = { null },
        )

        val restore = saved.toRestore(
            nextId = ::nextId,
            openShell = ::openShell,
            isTargetAvailable = { true },
            isAgentTaskAlive = { true },
        )

        val restoredTab = restore.docks.right.tabs.single()
        val restoredTree = restoredTab.liveTree as LivePaneNode.Split
        assertEquals(SplitAxis.Column, restoredTree.axis)
        val restoredLeaf1 = restoredTree.children[0] as LivePaneNode.Leaf
        val restoredLeaf2 = restoredTree.children[1] as LivePaneNode.Leaf
        assertEquals("device-1", restoredLeaf1.targetId)
        assertEquals("device-2", restoredLeaf2.targetId)
        assertEquals(restoredLeaf2.id, restoredTab.focusedLiveLeafId)
        assertEquals("device-2", restoredTab.targetId)
        assertEquals("Device 2", restoredTab.title)
        assertEquals(listOf("device-1", "device-2"), restore.boundLiveTargetIds)
    }

    @Test
    fun restoreLeavesLiveLeafUnboundWhenDeviceUnavailable() {
        val layout = SavedDockLayout(
            id = "layout-unbound",
            name = "Unbound Live",
            right = SavedDockPane(
                tabs = listOf(
                    SavedDockTab(
                        kind = SavedDockTabKind.Live,
                        liveTree = SavedLiveNode.Leaf(targetId = "offline-device", title = "Offline Phone"),
                    ),
                ),
                activeTabIndex = 0,
                visible = true,
            ),
        )

        val restore = layout.toRestore(
            nextId = ::nextId,
            openShell = ::openShell,
            isTargetAvailable = { false },
            isAgentTaskAlive = { true },
        )

        assertTrue(restore.boundLiveTargetIds.isEmpty())
        val tab = restore.docks.right.tabs.single()
        assertNull(tab.targetId)
        assertNull(tab.title)
        val leaf = tab.liveTree as LivePaneNode.Leaf
        assertNull(leaf.targetId)
        assertNull(leaf.title)
    }

    @Test
    fun restoreSpawnsOneShellPerSavedSession() {
        val spawnedProjects = mutableListOf<String?>()
        val layout = SavedDockLayout(
            id = "layout-spawn",
            name = "Spawn Test",
            right = SavedDockPane(
                tabs = listOf(
                    SavedDockTab(
                        kind = SavedDockTabKind.Terminal,
                        terminalTree = SavedTerminalNode.Leaf(
                            sessions = listOf(
                                SavedTerminalSession("proj-a", "S1"),
                                SavedTerminalSession("proj-b", "S2"),
                                SavedTerminalSession(null, "S3"),
                            ),
                            activeSessionIndex = 0,
                        ),
                    ),
                ),
                activeTabIndex = 0,
                visible = true,
            ),
        )

        val restore = layout.toRestore(
            nextId = ::nextId,
            openShell = { proj ->
                spawnedProjects.add(proj)
                "fresh-run-${runSeq++}"
            },
            isTargetAvailable = { true },
            isAgentTaskAlive = { true },
        )

        assertEquals(listOf("proj-a", "proj-b", null), spawnedProjects)
        val tab = restore.docks.right.tabs.single()
        val leaf = tab.terminalTree as TerminalPaneNode.Leaf
        assertEquals(3, leaf.tabs.size)
        val runIds = leaf.tabs.mapNotNull { it.runId }
        assertEquals(3, runIds.distinct().size)
    }

    @Test
    fun restoreDropsTerminalTabWhenNoShellCanSpawn() {
        val layout = SavedDockLayout(
            id = "layout-no-shell",
            name = "No Shell",
            right = SavedDockPane(
                tabs = listOf(
                    SavedDockTab(
                        kind = SavedDockTabKind.Terminal,
                        terminalTree = SavedTerminalNode.Leaf(
                            sessions = listOf(SavedTerminalSession("proj-none", "S1")),
                        ),
                    ),
                ),
                activeTabIndex = 0,
                visible = true,
            ),
        )

        val restore = layout.toRestore(
            nextId = ::nextId,
            openShell = { null },
            isTargetAvailable = { true },
            isAgentTaskAlive = { true },
        )

        assertTrue(restore.docks.right.tabs.isEmpty())
        assertFalse(restore.docks.right.visible)
    }

    @Test
    fun restoreKeepsOnlyOneBrowserTabAcrossPanesAndCarriesUrl() {
        val layout = SavedDockLayout(
            id = "layout-browser",
            name = "Browser Test",
            right = SavedDockPane(
                tabs = listOf(
                    SavedDockTab(kind = SavedDockTabKind.Browser, browserUrl = "https://right.com", title = "Right"),
                ),
                activeTabIndex = 0,
                visible = true,
            ),
            bottom = SavedDockPane(
                tabs = listOf(
                    SavedDockTab(kind = SavedDockTabKind.Browser, browserUrl = "https://bottom.com", title = "Bottom"),
                    SavedDockTab(kind = SavedDockTabKind.Logs),
                ),
                activeTabIndex = 0,
                visible = true,
            ),
        )

        val restore = layout.toRestore(
            nextId = ::nextId,
            openShell = ::openShell,
            isTargetAvailable = { true },
            isAgentTaskAlive = { true },
        )

        assertEquals(1, restore.docks.right.tabs.size)
        assertEquals(DockTabKind.Browser, restore.docks.right.tabs.single().kind)
        val rightBrowserId = restore.docks.right.tabs.single().id
        assertEquals("https://right.com", restore.browserPanes[rightBrowserId]?.url)

        assertEquals(1, restore.docks.bottom.tabs.size)
        assertEquals(DockTabKind.Logs, restore.docks.bottom.tabs.single().kind)
        assertEquals(1, restore.browserPanes.size)
    }

    @Test
    fun restoreUnbindsChatTabWhenTaskIsGone() {
        val layout = SavedDockLayout(
            id = "layout-chat-dead",
            name = "Dead Chat",
            right = SavedDockPane(
                tabs = listOf(
                    SavedDockTab(
                        kind = SavedDockTabKind.Chat,
                        agentTaskId = "dead-task",
                        parentChatTaskId = "dead-parent",
                        title = "Saved Side Chat",
                    ),
                ),
                activeTabIndex = 0,
                visible = true,
            ),
        )

        val restore = layout.toRestore(
            nextId = ::nextId,
            openShell = ::openShell,
            isTargetAvailable = { true },
            isAgentTaskAlive = { false },
        )

        val chatTab = restore.docks.right.tabs.single()
        assertEquals(DockTabKind.Chat, chatTab.kind)
        assertNull(chatTab.agentTaskId)
        assertNull(chatTab.parentChatTaskId)
        assertEquals("Saved Side Chat", chatTab.title)
    }

    @Test
    fun restoreFallsBackToLastTabWhenActiveTabDropped() {
        val layout = SavedDockLayout(
            id = "layout-active-fallback",
            name = "Fallback Active",
            right = SavedDockPane(
                tabs = listOf(
                    SavedDockTab(
                        kind = SavedDockTabKind.Terminal,
                        terminalTree = SavedTerminalNode.Leaf(
                            sessions = listOf(SavedTerminalSession("proj-1", "Term")),
                        ),
                    ),
                    SavedDockTab(kind = SavedDockTabKind.Logs),
                ),
                activeTabIndex = 0,
                visible = true,
            ),
        )

        val restore = layout.toRestore(
            nextId = ::nextId,
            openShell = { null }, // terminal will drop
            isTargetAvailable = { true },
            isAgentTaskAlive = { true },
        )

        assertEquals(1, restore.docks.right.tabs.size)
        assertEquals("logs", restore.docks.right.tabs.single().id)
        assertEquals("logs", restore.docks.right.activeTabId)
    }

    @Test
    fun restoreNormalizesBadWeightsAndCollapsesSingleChildSplits() {
        val layout = SavedDockLayout(
            id = "layout-normalize",
            name = "Normalize Weights",
            right = SavedDockPane(
                tabs = listOf(
                    SavedDockTab(
                        kind = SavedDockTabKind.Terminal,
                        terminalTree = SavedTerminalNode.Split(
                            axis = SavedSplitAxis.Row,
                            children = listOf(
                                SavedTerminalNode.Split(
                                    axis = SavedSplitAxis.Column,
                                    children = listOf(
                                        SavedTerminalNode.Leaf(
                                            sessions = listOf(SavedTerminalSession("p1", "Single Child")),
                                        ),
                                    ),
                                    weights = listOf(1f),
                                ),
                                SavedTerminalNode.Leaf(
                                    sessions = listOf(SavedTerminalSession("p2", "Second Child")),
                                ),
                            ),
                            weights = listOf(-1f, 0f), // bad weights
                        ),
                    ),
                ),
                activeTabIndex = 0,
                visible = true,
            ),
        )

        val restore = layout.toRestore(
            nextId = ::nextId,
            openShell = ::openShell,
            isTargetAvailable = { true },
            isAgentTaskAlive = { true },
        )

        val termTab = restore.docks.right.tabs.single()
        val rootSplit = termTab.terminalTree as TerminalPaneNode.Split
        assertEquals(listOf(0.5f, 0.5f), rootSplit.weights)
        // First child was a 1-child split; it should collapse directly to Leaf
        assertTrue(rootSplit.children[0] is TerminalPaneNode.Leaf)
        assertEquals("Single Child", (rootSplit.children[0] as TerminalPaneNode.Leaf).tabs[0].title)
        assertTrue(rootSplit.children[1] is TerminalPaneNode.Leaf)
    }

    @Test
    fun restoreRejectsNonFiniteSplitWeightsAndNormalizes() {
        val layout = SavedDockLayout(
            id = "layout-non-finite",
            name = "Non Finite Weights",
            right = SavedDockPane(
                tabs = listOf(
                    SavedDockTab(
                        kind = SavedDockTabKind.Terminal,
                        terminalTree = SavedTerminalNode.Split(
                            axis = SavedSplitAxis.Row,
                            children = listOf(
                                SavedTerminalNode.Leaf(sessions = listOf(SavedTerminalSession("p1", "T1"))),
                                SavedTerminalNode.Leaf(sessions = listOf(SavedTerminalSession("p2", "T2"))),
                            ),
                            weights = listOf(Float.POSITIVE_INFINITY, 1f),
                        ),
                    ),
                    SavedDockTab(
                        kind = SavedDockTabKind.Live,
                        liveTree = SavedLiveNode.Split(
                            axis = SavedSplitAxis.Column,
                            children = listOf(
                                SavedLiveNode.Leaf(targetId = "dev-1", title = "D1"),
                                SavedLiveNode.Leaf(targetId = "dev-2", title = "D2"),
                            ),
                            weights = listOf(Float.NaN, 0.5f),
                        ),
                    ),
                ),
                activeTabIndex = 0,
                visible = true,
            ),
        )

        val restore = layout.toRestore(
            nextId = ::nextId,
            openShell = ::openShell,
            isTargetAvailable = { true },
            isAgentTaskAlive = { true },
        )

        val termTab = restore.docks.right.tabs[0]
        val termSplit = termTab.terminalTree as TerminalPaneNode.Split
        assertEquals(listOf(0.5f, 0.5f), termSplit.weights)

        val liveTab = restore.docks.right.tabs[1]
        val liveSplit = liveTab.liveTree as LivePaneNode.Split
        assertEquals(listOf(0.5f, 0.5f), liveSplit.weights)
    }

    @Test
    fun restoreClampsOutOfRangeIndices() {
        val layout = SavedDockLayout(
            id = "layout-clamp",
            name = "Clamp Test",
            right = SavedDockPane(
                tabs = listOf(
                    SavedDockTab(
                        kind = SavedDockTabKind.Terminal,
                        terminalTree = SavedTerminalNode.Split(
                            axis = SavedSplitAxis.Row,
                            children = listOf(
                                SavedTerminalNode.Leaf(
                                    sessions = listOf(
                                        SavedTerminalSession("p1", "S1"),
                                        SavedTerminalSession("p2", "S2"),
                                    ),
                                    activeSessionIndex = 99,
                                ),
                                SavedTerminalNode.Leaf(
                                    sessions = listOf(SavedTerminalSession("p3", "S3")),
                                    activeSessionIndex = -1,
                                ),
                            ),
                            weights = listOf(0.5f, 0.5f),
                        ),
                        focusedLeafIndex = 99,
                    ),
                    SavedDockTab(kind = SavedDockTabKind.Logs),
                ),
                activeTabIndex = 99,
                visible = true,
            ),
        )

        val restore = layout.toRestore(
            nextId = ::nextId,
            openShell = ::openShell,
            isTargetAvailable = { true },
            isAgentTaskAlive = { true },
        )

        // activeTabIndex 99 clamped to index 1 (Logs)
        assertEquals("logs", restore.docks.right.activeTabId)
        val termTab = restore.docks.right.tabs[0]
        val tree = termTab.terminalTree as TerminalPaneNode.Split
        val leaf1 = tree.children[0] as TerminalPaneNode.Leaf
        val leaf2 = tree.children[1] as TerminalPaneNode.Leaf
        // activeSessionIndex 99 clamped to S2
        assertEquals(leaf1.tabs[1].id, leaf1.activeTabId)
        // focusedLeafIndex 99 clamped to leaf2
        assertEquals(leaf2.id, termTab.focusedTerminalLeafId)
    }

    @Test
    fun summaryLineGroupsKindsPerPane() {
        val layout1 = SavedDockLayout(
            id = "1",
            name = "Test 1",
            right = SavedDockPane(
                tabs = listOf(
                    SavedDockTab(kind = SavedDockTabKind.Live),
                    SavedDockTab(kind = SavedDockTabKind.Terminal),
                    SavedDockTab(kind = SavedDockTabKind.Terminal),
                ),
            ),
            bottom = SavedDockPane(
                tabs = listOf(
                    SavedDockTab(kind = SavedDockTabKind.Logs),
                ),
            ),
        )
        assertEquals("Right: Live, Terminal ×2 · Bottom: Logs", layout1.summaryLine())

        val layout2 = SavedDockLayout(
            id = "2",
            name = "Test 2",
            right = SavedDockPane(),
            bottom = SavedDockPane(
                tabs = listOf(SavedDockTab(kind = SavedDockTabKind.Logs)),
            ),
        )
        assertEquals("Bottom: Logs", layout2.summaryLine())

        val layoutEmpty = SavedDockLayout(id = "3", name = "Test 3")
        assertEquals("Empty", layoutEmpty.summaryLine())
    }
}
