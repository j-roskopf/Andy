package app.andy.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SavedDockTabKind { Live, Terminal, Logs, Browser, Chat }

@Serializable
enum class SavedSplitAxis { Row, Column }

/** One saved terminal session slot. [projectId] may be null → resolve the default project on load. */
@Serializable
data class SavedTerminalSession(
    val projectId: String? = null,
    val title: String? = null,
)

@Serializable
sealed interface SavedTerminalNode {
    @Serializable
    @SerialName("leaf")
    data class Leaf(
        val sessions: List<SavedTerminalSession> = emptyList(),
        /** Index into [sessions]; -1 → fall back to the last surviving session. */
        val activeSessionIndex: Int = -1,
    ) : SavedTerminalNode

    @Serializable
    @SerialName("split")
    data class Split(
        val axis: SavedSplitAxis,
        val children: List<SavedTerminalNode> = emptyList(),
        val weights: List<Float> = emptyList(),
    ) : SavedTerminalNode
}

@Serializable
sealed interface SavedLiveNode {
    @Serializable
    @SerialName("leaf")
    data class Leaf(
        val targetId: String? = null,
        val title: String? = null,
    ) : SavedLiveNode

    @Serializable
    @SerialName("split")
    data class Split(
        val axis: SavedSplitAxis,
        val children: List<SavedLiveNode> = emptyList(),
        val weights: List<Float> = emptyList(),
    ) : SavedLiveNode
}

@Serializable
data class SavedDockTab(
    val kind: SavedDockTabKind,
    val title: String? = null,
    /** Browser only. */
    val browserUrl: String? = null,
    /** Chat only — the child task shown in the tab, if it had been started. */
    val agentTaskId: String? = null,
    /** Chat only — the parent chat the side chat was opened against. */
    val parentChatTaskId: String? = null,
    val terminalTree: SavedTerminalNode? = null,
    val liveTree: SavedLiveNode? = null,
    /** DFS index into the tree's flattened leaves; clamped on load. */
    val focusedLeafIndex: Int = 0,
)

@Serializable
data class SavedDockPane(
    val tabs: List<SavedDockTab> = emptyList(),
    /** Index into [tabs]; -1 → fall back to the last surviving tab (matches DockPane.activeTab). */
    val activeTabIndex: Int = -1,
    val visible: Boolean = false,
)

@Serializable
data class SavedDockLayout(
    val id: String,
    val name: String,
    val savedAtMillis: Long = 0L,
    val right: SavedDockPane = SavedDockPane(),
    val bottom: SavedDockPane = SavedDockPane(),
    val rightPaneWidth: Float = 460f,
    val bottomPaneHeight: Float = 300f,
)
