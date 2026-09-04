package app.andy.ui.shell

import app.andy.model.SavedDockLayout
import app.andy.model.SavedDockPane
import app.andy.model.SavedDockTab
import app.andy.model.SavedDockTabKind
import app.andy.model.SavedLiveNode
import app.andy.model.SavedSplitAxis
import app.andy.model.SavedTerminalNode
import app.andy.model.SavedTerminalSession

internal fun ShellDocks.toSavedLayout(
    id: String,
    name: String,
    savedAtMillis: Long,
    rightPaneWidth: Float,
    bottomPaneHeight: Float,
    browserUrlOf: (tabId: String) -> String?,
    projectIdOfRun: (runId: String) -> String?,
): SavedDockLayout {
    return SavedDockLayout(
        id = id,
        name = name,
        savedAtMillis = savedAtMillis,
        right = right.toSavedPane(browserUrlOf, projectIdOfRun),
        bottom = bottom.toSavedPane(browserUrlOf, projectIdOfRun),
        rightPaneWidth = rightPaneWidth,
        bottomPaneHeight = bottomPaneHeight,
    )
}

private fun DockPane.toSavedPane(
    browserUrlOf: (tabId: String) -> String?,
    projectIdOfRun: (runId: String) -> String?,
): SavedDockPane {
    val savedTabs = mutableListOf<SavedDockTab>()
    var activeIndex = -1
    for (tab in tabs) {
        val saved = tab.toSavedTab(browserUrlOf, projectIdOfRun) ?: continue
        if (tab.id == activeTabId) {
            activeIndex = savedTabs.size
        }
        savedTabs.add(saved)
    }
    return SavedDockPane(
        tabs = savedTabs,
        activeTabIndex = activeIndex,
        visible = visible,
    )
}

private fun DockTab.toSavedTab(
    browserUrlOf: (tabId: String) -> String?,
    projectIdOfRun: (runId: String) -> String?,
): SavedDockTab? = when (kind) {
    DockTabKind.Live -> {
        val tree = liveTree
        val focusedIndex = if (tree != null) {
            val leaves = tree.flattenLeaves()
            leaves.indexOfFirst { it.id == focusedLiveLeafId }.coerceAtLeast(0)
        } else 0
        SavedDockTab(
            kind = SavedDockTabKind.Live,
            title = title,
            liveTree = tree?.toSavedNode(),
            focusedLeafIndex = focusedIndex,
        )
    }
    DockTabKind.Terminal -> {
        val tree = terminalTree ?: return null
        val leaves = tree.flattenLeaves()
        val focusedIndex = leaves.indexOfFirst { it.id == focusedTerminalLeafId }.coerceAtLeast(0)
        SavedDockTab(
            kind = SavedDockTabKind.Terminal,
            title = title,
            terminalTree = tree.toSavedNode(projectIdOfRun),
            focusedLeafIndex = focusedIndex,
        )
    }
    DockTabKind.Logs -> SavedDockTab(
        kind = SavedDockTabKind.Logs,
    )
    DockTabKind.Browser -> SavedDockTab(
        kind = SavedDockTabKind.Browser,
        title = title,
        browserUrl = browserUrlOf(id)?.takeIf { it.isNotBlank() },
    )
    DockTabKind.Chat -> SavedDockTab(
        kind = SavedDockTabKind.Chat,
        title = title,
        agentTaskId = agentTaskId,
        parentChatTaskId = parentChatTaskId,
    )
}

private fun LivePaneNode.toSavedNode(): SavedLiveNode = when (this) {
    is LivePaneNode.Leaf -> SavedLiveNode.Leaf(
        targetId = targetId,
        title = title,
    )
    is LivePaneNode.Split -> SavedLiveNode.Split(
        axis = when (axis) {
            SplitAxis.Row -> SavedSplitAxis.Row
            SplitAxis.Column -> SavedSplitAxis.Column
        },
        children = children.map { it.toSavedNode() },
        weights = weights,
    )
}

private fun TerminalPaneNode.toSavedNode(projectIdOfRun: (runId: String) -> String?): SavedTerminalNode = when (this) {
    is TerminalPaneNode.Leaf -> SavedTerminalNode.Leaf(
        sessions = tabs.map { sessionTab ->
            SavedTerminalSession(
                projectId = projectIdOfRun(sessionTab.runId.orEmpty()),
                title = sessionTab.title,
            )
        },
        activeSessionIndex = tabs.indexOfFirst { it.id == activeTabId },
    )
    is TerminalPaneNode.Split -> SavedTerminalNode.Split(
        axis = when (axis) {
            SplitAxis.Row -> SavedSplitAxis.Row
            SplitAxis.Column -> SavedSplitAxis.Column
        },
        children = children.map { it.toSavedNode(projectIdOfRun) },
        weights = weights,
    )
}

internal data class DockRestore(
    val docks: ShellDocks,
    val browserPanes: Map<String, BrowserPaneState>,
    val focusedRunId: String?,
    val focusedTerminalPlacement: DockPlacement?,
)

internal fun SavedDockLayout.toRestore(
    nextId: (prefix: String) -> String,
    openShell: (projectId: String?) -> String?,
    isTargetAvailable: (targetId: String) -> Boolean,
    isAgentTaskAlive: (taskId: String) -> Boolean,
): DockRestore {
    var hasRestoredBrowser = false
    val browserPanes = mutableMapOf<String, BrowserPaneState>()

    val restoredRight = restorePane(
        savedPane = right,
        nextId = nextId,
        openShell = openShell,
        isTargetAvailable = isTargetAvailable,
        isAgentTaskAlive = isAgentTaskAlive,
        browserPanes = browserPanes,
        hasRestoredBrowser = { hasRestoredBrowser },
        onBrowserRestored = { hasRestoredBrowser = true },
    )

    val restoredBottom = restorePane(
        savedPane = bottom,
        nextId = nextId,
        openShell = openShell,
        isTargetAvailable = isTargetAvailable,
        isAgentTaskAlive = isAgentTaskAlive,
        browserPanes = browserPanes,
        hasRestoredBrowser = { hasRestoredBrowser },
        onBrowserRestored = { hasRestoredBrowser = true },
    )

    fun findFocusedRun(pane: DockPane): String? {
        val activeTab = pane.activeTab ?: return null
        if (activeTab.kind != DockTabKind.Terminal) return null
        val tree = activeTab.terminalTree ?: return null
        val leaf = activeTab.focusedTerminalLeafId?.let { tree.findLeaf(it) }
            ?: tree.flattenLeaves().firstOrNull()
        return leaf?.activeTab?.runId
    }

    var focusedRunId: String? = null
    var focusedTerminalPlacement: DockPlacement? = null

    val rightRunId = findFocusedRun(restoredRight)
    if (rightRunId != null) {
        focusedRunId = rightRunId
        focusedTerminalPlacement = DockPlacement.Right
    } else {
        val bottomRunId = findFocusedRun(restoredBottom)
        if (bottomRunId != null) {
            focusedRunId = bottomRunId
            focusedTerminalPlacement = DockPlacement.Bottom
        }
    }

    return DockRestore(
        docks = ShellDocks(right = restoredRight, bottom = restoredBottom, landingFor = null),
        browserPanes = browserPanes,
        focusedRunId = focusedRunId,
        focusedTerminalPlacement = focusedTerminalPlacement,
    )
}

private fun restorePane(
    savedPane: SavedDockPane,
    nextId: (prefix: String) -> String,
    openShell: (projectId: String?) -> String?,
    isTargetAvailable: (targetId: String) -> Boolean,
    isAgentTaskAlive: (taskId: String) -> Boolean,
    browserPanes: MutableMap<String, BrowserPaneState>,
    hasRestoredBrowser: () -> Boolean,
    onBrowserRestored: () -> Unit,
): DockPane {
    if (savedPane.tabs.isEmpty()) return DockPane()

    val clampedActiveIndex = when {
        savedPane.activeTabIndex < 0 -> -1
        else -> savedPane.activeTabIndex.coerceIn(0, savedPane.tabs.lastIndex)
    }

    val survivors = mutableListOf<DockTab>()
    var targetActiveTabId: String? = null
    var paneHasLogs = false

    savedPane.tabs.forEachIndexed { index, savedTab ->
        val restoredTab = when (savedTab.kind) {
            SavedDockTabKind.Terminal -> restoreTerminalTab(savedTab, nextId, openShell)
            SavedDockTabKind.Live -> restoreLiveTab(savedTab, nextId, isTargetAvailable)
            SavedDockTabKind.Logs -> {
                if (paneHasLogs) null
                else {
                    paneHasLogs = true
                    DockTab.logs()
                }
            }
            SavedDockTabKind.Browser -> {
                if (hasRestoredBrowser()) null
                else {
                    onBrowserRestored()
                    val browserTabId = nextId("browser")
                    browserPanes[browserTabId] = BrowserPaneState(url = savedTab.browserUrl.orEmpty())
                    DockTab.browser(browserTabId, title = savedTab.title)
                }
            }
            SavedDockTabKind.Chat -> {
                val chatTabId = nextId("chat")
                DockTab.chat(
                    id = chatTabId,
                    agentTaskId = savedTab.agentTaskId?.takeIf(isAgentTaskAlive),
                    parentChatTaskId = savedTab.parentChatTaskId?.takeIf(isAgentTaskAlive),
                    title = savedTab.title,
                )
            }
        }
        if (restoredTab != null) {
            survivors.add(restoredTab)
            if (index == clampedActiveIndex) {
                targetActiveTabId = restoredTab.id
            }
        }
    }

    if (survivors.isEmpty()) return DockPane()

    val activeTabId = targetActiveTabId ?: survivors.last().id
    return DockPane(
        tabs = survivors,
        activeTabId = activeTabId,
        visible = savedPane.visible,
    )
}

private fun restoreTerminalTab(
    tab: SavedDockTab,
    nextId: (prefix: String) -> String,
    openShell: (projectId: String?) -> String?,
): DockTab? {
    val tree = tab.terminalTree?.let { restoreTerminalNode(it, nextId, openShell) } ?: return null
    val survivingLeaves = tree.flattenLeaves()
    if (survivingLeaves.isEmpty()) return null
    val clampedLeafIndex = tab.focusedLeafIndex.coerceIn(0, survivingLeaves.lastIndex)
    val focusedLeafId = survivingLeaves[clampedLeafIndex].id
    return DockTab.terminalWorkspace(
        id = nextId("terminal-tab"),
        tree = tree,
        focusedLeafId = focusedLeafId,
        title = tab.title,
    )
}

private fun restoreTerminalNode(
    node: SavedTerminalNode,
    nextId: (prefix: String) -> String,
    openShell: (projectId: String?) -> String?,
    depth: Int = 0,
): TerminalPaneNode? {
    if (depth >= 12) return null
    return when (node) {
        is SavedTerminalNode.Leaf -> {
            val clampedActiveSession = when {
                node.activeSessionIndex < 0 -> -1
                node.sessions.isEmpty() -> -1
                else -> node.activeSessionIndex.coerceIn(0, node.sessions.lastIndex)
            }
            val survivingTabs = mutableListOf<DockTab>()
            var targetActiveTabId: String? = null
            node.sessions.forEachIndexed { index, session ->
                val runId = openShell(session.projectId)?.takeIf { it.isNotBlank() }
                if (runId != null) {
                    val tab = DockTab.terminal(runId, title = session.title)
                    survivingTabs.add(tab)
                    if (index == clampedActiveSession) {
                        targetActiveTabId = tab.id
                    }
                }
            }
            if (survivingTabs.isEmpty()) return null
            val activeTabId = targetActiveTabId ?: survivingTabs.last().id
            TerminalPaneNode.Leaf(
                id = nextId("leaf"),
                tabs = survivingTabs,
                activeTabId = activeTabId,
            )
        }
        is SavedTerminalNode.Split -> {
            if (node.children.isEmpty()) return null
            val rawWeights = if (node.weights.size == node.children.size && node.weights.all { it > 0f && it.isFinite() }) {
                node.weights
            } else {
                List(node.children.size) { 1f / node.children.size }
            }
            val kept = node.children.mapIndexedNotNull { index, child ->
                restoreTerminalNode(child, nextId, openShell, depth + 1)?.let { it to rawWeights[index] }
            }
            when (kept.size) {
                0 -> null
                1 -> kept[0].first
                else -> {
                    val total = kept.sumOf { it.second.toDouble() }.toFloat().takeIf { it > 0f && it.isFinite() } ?: kept.size.toFloat()
                    val normalizedWeights = kept.map { it.second / total }
                    val axis = when (node.axis) {
                        SavedSplitAxis.Row -> SplitAxis.Row
                        SavedSplitAxis.Column -> SplitAxis.Column
                    }
                    TerminalPaneNode.Split(
                        id = nextId("split"),
                        axis = axis,
                        children = kept.map { it.first },
                        weights = normalizedWeights,
                    )
                }
            }
        }
    }
}

private fun restoreLiveTab(
    tab: SavedDockTab,
    nextId: (prefix: String) -> String,
    isTargetAvailable: (targetId: String) -> Boolean,
): DockTab {
    val liveTree = tab.liveTree?.let { restoreLiveNode(it, nextId, isTargetAvailable) }
        ?: LivePaneNode.Leaf(id = nextId("live-leaf"), targetId = null, title = null)
    val leaves = liveTree.flattenLeaves()
    val clampedIndex = tab.focusedLeafIndex.coerceIn(0, leaves.lastIndex)
    val focusedLeaf = leaves[clampedIndex]
    return DockTab(
        id = nextId("live"),
        kind = DockTabKind.Live,
        targetId = focusedLeaf.targetId,
        title = focusedLeaf.title,
        liveTree = liveTree,
        focusedLiveLeafId = focusedLeaf.id,
    )
}

private fun restoreLiveNode(
    node: SavedLiveNode,
    nextId: (prefix: String) -> String,
    isTargetAvailable: (targetId: String) -> Boolean,
    depth: Int = 0,
): LivePaneNode? {
    if (depth >= 12) return null
    return when (node) {
        is SavedLiveNode.Leaf -> {
            val availableTargetId = node.targetId?.takeIf(isTargetAvailable)
            LivePaneNode.Leaf(
                id = nextId("live-leaf"),
                targetId = availableTargetId,
                title = if (availableTargetId != null) node.title else null,
            )
        }
        is SavedLiveNode.Split -> {
            if (node.children.isEmpty()) return null
            val rawWeights = if (node.weights.size == node.children.size && node.weights.all { it > 0f && it.isFinite() }) {
                node.weights
            } else {
                List(node.children.size) { 1f / node.children.size }
            }
            val kept = node.children.mapIndexedNotNull { index, child ->
                restoreLiveNode(child, nextId, isTargetAvailable, depth + 1)?.let {
                    it to rawWeights[index]
                }
            }
            when (kept.size) {
                0 -> null
                1 -> kept[0].first
                else -> {
                    val total = kept.sumOf { it.second.toDouble() }.toFloat().takeIf { it > 0f && it.isFinite() } ?: kept.size.toFloat()
                    val normalizedWeights = kept.map { it.second / total }
                    val axis = when (node.axis) {
                        SavedSplitAxis.Row -> SplitAxis.Row
                        SavedSplitAxis.Column -> SplitAxis.Column
                    }
                    LivePaneNode.Split(
                        id = nextId("live-split"),
                        axis = axis,
                        children = kept.map { it.first },
                        weights = normalizedWeights,
                    )
                }
            }
        }
    }
}

internal fun SavedDockLayout.summaryLine(): String {
    val rightSummary = right.summaryLine(label = "Right")
    val bottomSummary = bottom.summaryLine(label = "Bottom")
    val parts = listOfNotNull(rightSummary, bottomSummary)
    return if (parts.isEmpty()) "Empty" else parts.joinToString(" · ")
}

private fun SavedDockPane.summaryLine(label: String): String? {
    if (tabs.isEmpty()) return null
    val counts = linkedMapOf<SavedDockTabKind, Int>()
    for (tab in tabs) {
        counts[tab.kind] = (counts[tab.kind] ?: 0) + 1
    }
    val content = counts.entries.joinToString(", ") { (kind, count) ->
        val name = when (kind) {
            SavedDockTabKind.Live -> "Live"
            SavedDockTabKind.Terminal -> "Terminal"
            SavedDockTabKind.Logs -> "Logs"
            SavedDockTabKind.Browser -> "Browser"
            SavedDockTabKind.Chat -> "Chat"
        }
        if (count > 1) "$name \u00D7$count" else name
    }
    return "$label: $content"
}
