package app.andy.ui.shell

/** Layout axis for a [TerminalPaneNode.Split]. */
internal enum class SplitAxis { Row, Column }

/**
 * Recursive split tree for the terminal panes inside one [DockPane]. A [Leaf] owns its
 * own strip of terminal [DockTab]s (so a pane can itself hold multiple sessions); a
 * [Split] arranges its children along [SplitAxis.Row] (side by side) or
 * [SplitAxis.Column] (stacked). The split UI only ever creates 2-child splits, but the
 * shape stays generic-N so collapse/rebuild logic doesn't need a special binary case.
 */
internal sealed class TerminalPaneNode {
    abstract val id: String

    data class Leaf(
        override val id: String,
        val tabs: List<DockTab> = emptyList(),
        val activeTabId: String? = null,
    ) : TerminalPaneNode() {
        val activeTab: DockTab?
            get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.lastOrNull()
    }

    data class Split(
        override val id: String,
        val axis: SplitAxis,
        val children: List<TerminalPaneNode>,
        val weights: List<Float>,
    ) : TerminalPaneNode()
}

internal fun TerminalPaneNode.findLeaf(leafId: String): TerminalPaneNode.Leaf? = when (this) {
    is TerminalPaneNode.Leaf -> takeIf { id == leafId }
    is TerminalPaneNode.Split -> children.firstNotNullOfOrNull { it.findLeaf(leafId) }
}

internal fun TerminalPaneNode.flattenLeaves(): List<TerminalPaneNode.Leaf> = when (this) {
    is TerminalPaneNode.Leaf -> listOf(this)
    is TerminalPaneNode.Split -> children.flatMap { it.flattenLeaves() }
}

internal fun TerminalPaneNode.flattenTabs(): List<DockTab> = flattenLeaves().flatMap { it.tabs }

internal fun TerminalPaneNode.leafOwningTab(tabId: String): TerminalPaneNode.Leaf? =
    flattenLeaves().firstOrNull { leaf -> leaf.tabs.any { it.id == tabId } }

internal fun TerminalPaneNode.leafOwningRun(runId: String): TerminalPaneNode.Leaf? =
    flattenLeaves().firstOrNull { leaf -> leaf.tabs.any { it.runId == runId } }

/** First leaf in traversal order. Only safe to call on a non-empty tree. */
internal fun TerminalPaneNode.firstLeafId(): String = flattenLeaves().first().id

internal fun TerminalPaneNode.addTab(leafId: String, tab: DockTab): TerminalPaneNode = mapLeaf(leafId) { leaf ->
    if (leaf.tabs.any { it.id == tab.id }) {
        leaf.copy(activeTabId = tab.id)
    } else {
        leaf.copy(tabs = leaf.tabs + tab, activeTabId = tab.id)
    }
}

internal fun TerminalPaneNode.selectTab(leafId: String, tabId: String): TerminalPaneNode = mapLeaf(leafId) { leaf ->
    if (leaf.tabs.none { it.id == tabId }) leaf else leaf.copy(activeTabId = tabId)
}

internal fun TerminalPaneNode.renameTab(tabId: String, title: String): TerminalPaneNode {
    val normalized = title.trim().takeIf { it.isNotEmpty() } ?: return this
    return mapLeaves { leaf ->
        if (leaf.tabs.none { it.id == tabId }) {
            leaf
        } else {
            leaf.copy(tabs = leaf.tabs.map { tab -> if (tab.id == tabId) tab.copy(title = normalized) else tab })
        }
    }
}

/** Removes [tabId] from whichever leaf owns it; collapses empty leaves/splits. */
internal fun TerminalPaneNode.closeTab(tabId: String): TerminalPaneNode? = mapLeaves { leaf ->
    if (leaf.tabs.none { it.id == tabId }) {
        leaf
    } else {
        val remaining = leaf.tabs.filter { it.id != tabId }
        val nextActive = if (leaf.activeTabId == tabId) remaining.lastOrNull()?.id else leaf.activeTabId
        leaf.copy(tabs = remaining, activeTabId = nextActive)
    }
}.rebuild { it.tabs.isEmpty() }

/** Removes the whole [leafId] pane, collapsing its parent split if only one child survives. */
internal fun TerminalPaneNode.closeLeaf(leafId: String): TerminalPaneNode? = rebuild { it.id == leafId }

/** Removes any tab backed by [runId] (used when a run is refocused into another dock). */
internal fun TerminalPaneNode.withoutRun(runId: String): TerminalPaneNode? = mapLeaves { leaf ->
    if (leaf.tabs.none { it.runId == runId }) {
        leaf
    } else {
        val remaining = leaf.tabs.filter { it.runId != runId }
        val nextActive = if (leaf.activeTab?.runId == runId) remaining.lastOrNull()?.id else leaf.activeTabId
        leaf.copy(tabs = remaining, activeTabId = nextActive)
    }
}.rebuild { it.tabs.isEmpty() }

/** Drops every tab whose run is no longer alive, across every leaf. */
internal fun TerminalPaneNode.pruneRuns(aliveRunIds: Set<String>): TerminalPaneNode? = mapLeaves { leaf ->
    val remaining = leaf.tabs.filter { it.runId == null || it.runId in aliveRunIds }
    if (remaining.size == leaf.tabs.size) {
        leaf
    } else {
        val nextActive = if (remaining.any { it.id == leaf.activeTabId }) leaf.activeTabId else remaining.lastOrNull()?.id
        leaf.copy(tabs = remaining, activeTabId = nextActive)
    }
}.rebuild { it.tabs.isEmpty() }

/** Wraps [leafId] in a brand-new 2-child split holding the original leaf and [newLeaf]. */
internal fun TerminalPaneNode.split(
    leafId: String,
    newSplitId: String,
    axis: SplitAxis,
    newLeaf: TerminalPaneNode.Leaf,
): TerminalPaneNode = when (this) {
    is TerminalPaneNode.Leaf -> if (id == leafId) {
        TerminalPaneNode.Split(id = newSplitId, axis = axis, children = listOf(this, newLeaf), weights = listOf(0.5f, 0.5f))
    } else {
        this
    }
    is TerminalPaneNode.Split -> copy(children = children.map { it.split(leafId, newSplitId, axis, newLeaf) })
}

internal fun TerminalPaneNode.updateWeights(splitId: String, weights: List<Float>): TerminalPaneNode = when (this) {
    is TerminalPaneNode.Leaf -> this
    is TerminalPaneNode.Split -> if (id == splitId) {
        if (weights.size == children.size) copy(weights = weights) else this
    } else {
        copy(children = children.map { it.updateWeights(splitId, weights) })
    }
}

private fun TerminalPaneNode.mapLeaf(
    leafId: String,
    transform: (TerminalPaneNode.Leaf) -> TerminalPaneNode.Leaf,
): TerminalPaneNode = when (this) {
    is TerminalPaneNode.Leaf -> if (id == leafId) transform(this) else this
    is TerminalPaneNode.Split -> copy(children = children.map { it.mapLeaf(leafId, transform) })
}

private fun TerminalPaneNode.mapLeaves(
    transform: (TerminalPaneNode.Leaf) -> TerminalPaneNode.Leaf,
): TerminalPaneNode = when (this) {
    is TerminalPaneNode.Leaf -> transform(this)
    is TerminalPaneNode.Split -> copy(children = children.map { it.mapLeaves(transform) })
}

/** Rebuilds the tree, dropping any leaf matched by [dropLeaf] and collapsing splits left with one child. */
private fun TerminalPaneNode.rebuild(dropLeaf: (TerminalPaneNode.Leaf) -> Boolean): TerminalPaneNode? = when (this) {
    is TerminalPaneNode.Leaf -> if (dropLeaf(this)) null else this
    is TerminalPaneNode.Split -> {
        val kept = children.mapIndexedNotNull { index, child -> child.rebuild(dropLeaf)?.let { it to weights[index] } }
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
