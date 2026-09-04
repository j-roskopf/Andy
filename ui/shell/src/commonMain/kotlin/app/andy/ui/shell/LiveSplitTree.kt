package app.andy.ui.shell

/**
 * Recursive split tree for device mirrors inside one Live [DockTab]. A [Leaf] is one
 * mirror surface (optional [targetId]); a [Split] arranges children along [SplitAxis.Row]
 * or [SplitAxis.Column]. Unlike terminals, leaves have no inner tab strip — each pane is
 * exactly one device.
 */
internal sealed class LivePaneNode {
    abstract val id: String

    data class Leaf(
        override val id: String,
        val targetId: String? = null,
        val title: String? = null,
    ) : LivePaneNode()

    data class Split(
        override val id: String,
        val axis: SplitAxis,
        val children: List<LivePaneNode>,
        val weights: List<Float>,
    ) : LivePaneNode()
}

internal fun LivePaneNode.findLeaf(leafId: String): LivePaneNode.Leaf? = when (this) {
    is LivePaneNode.Leaf -> takeIf { id == leafId }
    is LivePaneNode.Split -> children.firstNotNullOfOrNull { it.findLeaf(leafId) }
}

internal fun LivePaneNode.flattenLeaves(): List<LivePaneNode.Leaf> = when (this) {
    is LivePaneNode.Leaf -> listOf(this)
    is LivePaneNode.Split -> children.flatMap { it.flattenLeaves() }
}

internal fun LivePaneNode.firstLeafId(): String = flattenLeaves().first().id

internal fun LivePaneNode.targetIds(): List<String> =
    flattenLeaves().mapNotNull { it.targetId }

internal fun LivePaneNode.mapLeaf(
    leafId: String,
    transform: (LivePaneNode.Leaf) -> LivePaneNode.Leaf,
): LivePaneNode = when (this) {
    is LivePaneNode.Leaf -> if (id == leafId) transform(this) else this
    is LivePaneNode.Split -> copy(children = children.map { it.mapLeaf(leafId, transform) })
}

/** Removes [leafId], collapsing parent splits left with a single child. */
internal fun LivePaneNode.closeLeaf(leafId: String): LivePaneNode? = rebuild { it.id == leafId }

/** Wraps [leafId] in a new 2-child split holding the original leaf and [newLeaf]. */
internal fun LivePaneNode.split(
    leafId: String,
    newSplitId: String,
    axis: SplitAxis,
    newLeaf: LivePaneNode.Leaf,
): LivePaneNode = when (this) {
    is LivePaneNode.Leaf -> if (id == leafId) {
        LivePaneNode.Split(
            id = newSplitId,
            axis = axis,
            children = listOf(this, newLeaf),
            weights = listOf(0.5f, 0.5f),
        )
    } else {
        this
    }
    is LivePaneNode.Split -> copy(children = children.map { it.split(leafId, newSplitId, axis, newLeaf) })
}

internal fun LivePaneNode.updateWeights(splitId: String, weights: List<Float>): LivePaneNode = when (this) {
    is LivePaneNode.Leaf -> this
    is LivePaneNode.Split -> if (id == splitId) {
        if (weights.size == children.size) copy(weights = weights) else this
    } else {
        copy(children = children.map { it.updateWeights(splitId, weights) })
    }
}

private fun LivePaneNode.rebuild(dropLeaf: (LivePaneNode.Leaf) -> Boolean): LivePaneNode? = when (this) {
    is LivePaneNode.Leaf -> if (dropLeaf(this)) null else this
    is LivePaneNode.Split -> {
        val kept = children.mapIndexedNotNull { index, child ->
            child.rebuild(dropLeaf)?.let { it to weights[index] }
        }
        when (kept.size) {
            0 -> null
            1 -> kept[0].first
            else -> {
                val total = kept.sumOf { it.second.toDouble() }.toFloat().takeIf { it > 0f } ?: kept.size.toFloat()
                copy(children = kept.map { it.first }, weights = kept.map { it.second / total })
            }
        }
    }
}
