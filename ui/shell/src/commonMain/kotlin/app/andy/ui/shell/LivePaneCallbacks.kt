package app.andy.ui.shell

/** Callbacks for Live dock tabs — in-tab visual splits and device binding. */
internal data class LivePaneCallbacks(
    val onSelectTarget: (tabId: String, leafId: String, targetId: String) -> Unit,
    val onFocusLeaf: (tabId: String, leafId: String) -> Unit,
    val onSplit: (tabId: String, leafId: String, axis: SplitAxis) -> Unit,
    val onCloseLeaf: (tabId: String, leafId: String) -> Unit,
    val onWeightsChanged: (tabId: String, splitId: String, weights: List<Float>) -> Unit,
)
