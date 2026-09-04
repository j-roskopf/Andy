package app.andy.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveSplitTreeTest {
    @Test
    fun splitWrapsLeafIntoTwoChildSplit() {
        val leaf = LivePaneNode.Leaf("leaf-a", targetId = "device-a")
        val next = leaf.split(
            leafId = "leaf-a",
            newSplitId = "split-1",
            axis = SplitAxis.Row,
            newLeaf = LivePaneNode.Leaf("leaf-b"),
        )
        val split = next as LivePaneNode.Split
        assertEquals(SplitAxis.Row, split.axis)
        assertEquals(listOf("leaf-a", "leaf-b"), split.flattenLeaves().map { it.id })
        assertEquals(listOf(0.5f, 0.5f), split.weights)
        assertEquals("device-a", split.findLeaf("leaf-a")?.targetId)
        assertNull(split.findLeaf("leaf-b")?.targetId)
    }

    @Test
    fun closeLeafCollapsesSplitToSurvivor() {
        val tree = LivePaneNode.Leaf("leaf-a", targetId = "a")
            .split("leaf-a", "split-1", SplitAxis.Column, LivePaneNode.Leaf("leaf-b", targetId = "b"))
        val remaining = tree.closeLeaf("leaf-a") as LivePaneNode.Leaf
        assertEquals("leaf-b", remaining.id)
        assertEquals("b", remaining.targetId)
    }

    @Test
    fun closeLastLeafYieldsNull() {
        val tree = LivePaneNode.Leaf("leaf-a")
        assertNull(tree.closeLeaf("leaf-a"))
    }

    @Test
    fun mapLeafUpdatesTargetWithoutTouchingSiblings() {
        val tree = LivePaneNode.Leaf("leaf-a", targetId = "a")
            .split("leaf-a", "split-1", SplitAxis.Row, LivePaneNode.Leaf("leaf-b", targetId = "b"))
            .mapLeaf("leaf-b") { it.copy(targetId = "c", title = "Pixel") }
        assertEquals("a", tree.findLeaf("leaf-a")?.targetId)
        assertEquals("c", tree.findLeaf("leaf-b")?.targetId)
        assertEquals("Pixel", tree.findLeaf("leaf-b")?.title)
    }

    @Test
    fun targetIdsListsBoundDevices() {
        val tree = LivePaneNode.Leaf("leaf-a", targetId = "a")
            .split("leaf-a", "split-1", SplitAxis.Row, LivePaneNode.Leaf("leaf-b"))
            .mapLeaf("leaf-b") { it.copy(targetId = "a") }
        assertEquals(listOf("a", "a"), tree.targetIds())
    }

    @Test
    fun updateWeightsPersistsOnMatchingSplit() {
        val tree = LivePaneNode.Leaf("leaf-a")
            .split("leaf-a", "split-1", SplitAxis.Row, LivePaneNode.Leaf("leaf-b"))
            .updateWeights("split-1", listOf(0.3f, 0.7f)) as LivePaneNode.Split
        assertEquals(listOf(0.3f, 0.7f), tree.weights)
    }
}
