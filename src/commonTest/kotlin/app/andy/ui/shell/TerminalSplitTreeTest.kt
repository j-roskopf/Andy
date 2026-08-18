package app.andy.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalSplitTreeTest {
    private fun leaf(id: String, runId: String) =
        TerminalPaneNode.Leaf(id, listOf(DockTab.terminal(runId)), "terminal:$runId")

    @Test
    fun splitWrapsLeafInTwoChildSplit() {
        val tree: TerminalPaneNode = leaf("leaf-1", "run-1")
            .split("leaf-1", "split-1", SplitAxis.Row, leaf("leaf-2", "run-2"))

        val split = tree as TerminalPaneNode.Split
        assertEquals(SplitAxis.Row, split.axis)
        assertEquals(listOf("leaf-1", "leaf-2"), split.children.map { it.id })
        assertEquals(listOf(0.5f, 0.5f), split.weights)
    }

    @Test
    fun closingOneChildCollapsesSplitToSibling() {
        val tree: TerminalPaneNode = leaf("leaf-1", "run-1")
            .split("leaf-1", "split-1", SplitAxis.Row, leaf("leaf-2", "run-2"))

        val next = tree.closeLeaf("leaf-2")
        assertNotNull(next)
        assertTrue(next is TerminalPaneNode.Leaf)
        assertEquals("leaf-1", next.id)
    }

    @Test
    fun closingLastTabInOnlyLeafNullsTree() {
        val tree: TerminalPaneNode = leaf("leaf-1", "run-1")
        assertNull(tree.closeTab("terminal:run-1"))
    }

    @Test
    fun closingLastTabInOneLeafOfSplitCollapsesToSibling() {
        val tree: TerminalPaneNode = leaf("leaf-1", "run-1")
            .split("leaf-1", "split-1", SplitAxis.Column, leaf("leaf-2", "run-2"))

        val next = tree.closeTab("terminal:run-2")
        assertNotNull(next)
        assertTrue(next is TerminalPaneNode.Leaf)
        assertEquals("leaf-1", next.id)
    }

    @Test
    fun pruneRunsDropsDeadRunAcrossThreeLeaves() {
        // Mirrors the 3-pane layout from the reference screenshots: leaf-1 | (leaf-3 / leaf-4)
        val tree: TerminalPaneNode = leaf("leaf-1", "run-1")
            .split("leaf-1", "split-1", SplitAxis.Row, leaf("leaf-3", "run-3"))
            .split("leaf-3", "split-2", SplitAxis.Column, leaf("leaf-4", "run-4"))

        val pruned = tree.pruneRuns(setOf("run-1", "run-4"))
        assertNotNull(pruned)
        val leaves = pruned.flattenLeaves()
        assertEquals(setOf("run-1", "run-4"), leaves.flatMap { it.tabs }.mapNotNull { it.runId }.toSet())
        // run-3's leaf had no other tabs, so it should have collapsed out of the split entirely.
        assertEquals(2, leaves.size)
    }

    @Test
    fun weightsRenormalizeAfterSiblingDrops() {
        val tree = TerminalPaneNode.Split(
            id = "split-1",
            axis = SplitAxis.Row,
            children = listOf(leaf("leaf-1", "run-1"), leaf("leaf-2", "run-2"), leaf("leaf-3", "run-3")),
            weights = listOf(0.2f, 0.3f, 0.5f),
        )

        val next = tree.closeLeaf("leaf-2")
        assertNotNull(next)
        val split = next as TerminalPaneNode.Split
        assertEquals(listOf("leaf-1", "leaf-3"), split.children.map { it.id })
        // Surviving weights (0.2, 0.5) renormalize to sum to 1.
        assertEquals(1f, split.weights.sum(), absoluteTolerance = 1e-6f)
        assertEquals(0.2f / 0.7f, split.weights[0], absoluteTolerance = 1e-6f)
    }

    @Test
    fun updateWeightsIgnoresMismatchedSize() {
        val tree = TerminalPaneNode.Split(
            id = "split-1",
            axis = SplitAxis.Row,
            children = listOf(leaf("leaf-1", "run-1"), leaf("leaf-2", "run-2")),
            weights = listOf(0.5f, 0.5f),
        )

        val next = tree.updateWeights("split-1", listOf(0.3f, 0.3f, 0.4f)) as TerminalPaneNode.Split
        assertEquals(listOf(0.5f, 0.5f), next.weights)
    }

    @Test
    fun addTabAndSelectTabTargetTheRightLeaf() {
        val tree: TerminalPaneNode = leaf("leaf-1", "run-1")
            .split("leaf-1", "split-1", SplitAxis.Row, leaf("leaf-2", "run-2"))

        val withNewTab = tree.addTab("leaf-1", DockTab.terminal("run-5"))
        val updatedLeaf1 = withNewTab.findLeaf("leaf-1")
        assertNotNull(updatedLeaf1)
        assertEquals(2, updatedLeaf1.tabs.size)
        assertEquals("terminal:run-5", updatedLeaf1.activeTabId)

        val reselected = withNewTab.selectTab("leaf-1", "terminal:run-1")
        assertEquals("terminal:run-1", reselected.findLeaf("leaf-1")?.activeTabId)
    }

    @Test
    fun leafOwningRunFindsTheCorrectLeaf() {
        val tree: TerminalPaneNode = leaf("leaf-1", "run-1")
            .split("leaf-1", "split-1", SplitAxis.Row, leaf("leaf-2", "run-2"))

        assertEquals("leaf-2", tree.leafOwningRun("run-2")?.id)
        assertNull(tree.leafOwningRun("run-missing"))
    }

    @Test
    fun leafChromeStaysHiddenWhenDockStripAlreadyNamesTheSession() {
        val tree = leaf("leaf-1", "run-1")
        assertFalse(terminalLeafChromeVisible(tree, dockStripCollapsed = false))
    }

    @Test
    fun leafChromeShowsWhenDockStripIsCollapsed() {
        val tree = leaf("leaf-1", "run-1")
        assertTrue(terminalLeafChromeVisible(tree, dockStripCollapsed = true))
    }

    @Test
    fun leafChromeShowsOnceTheWorkspaceIsSplit() {
        val tree: TerminalPaneNode = leaf("leaf-1", "run-1")
            .split("leaf-1", "split-1", SplitAxis.Row, leaf("leaf-2", "run-2"))
        assertTrue(terminalLeafChromeVisible(tree, dockStripCollapsed = false))
    }

    @Test
    fun leafChromeShowsWhenTheLeafHasMultipleSessions() {
        val tree = leaf("leaf-1", "run-1").addTab("leaf-1", DockTab.terminal("run-2"))
        assertTrue(terminalLeafChromeVisible(tree, dockStripCollapsed = false))
    }
}

private fun assertEquals(expected: Float, actual: Float, absoluteTolerance: Float) {
    assertTrue(kotlin.math.abs(expected - actual) <= absoluteTolerance, "expected $expected but was $actual")
}
