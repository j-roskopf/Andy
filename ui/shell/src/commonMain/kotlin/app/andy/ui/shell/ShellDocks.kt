package app.andy.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import app.andy.model.SavedDockLayout
import app.andy.ui.components.AndyHorizontalDivider
import app.andy.ui.components.FieldChromeStyle
import app.andy.ui.components.TextField
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.BrowserNavCommand
import app.andy.model.ActionRunStatus
import app.andy.model.AndroidDevice
import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.RunningAction
import app.andy.model.TerminalThemePreset
import app.andy.model.WorkspaceState
import app.andy.model.palette
import app.andy.resignEmbeddedBrowserKey
import app.andy.service.AndyServices
import app.andy.service.AppService
import app.andy.service.LogcatService
import app.andy.service.MirrorEngine
import app.andy.ui.actions.ActionIcon
import app.andy.ui.components.Lucide
import app.andy.ui.components.LucideIcon
import app.andy.ui.components.EmptyState
import app.andy.LocalSuppressHeavyweightSurfaces
import app.andy.ui.components.PanelCard
import app.andy.ui.components.TabBarItem
import app.andy.ui.components.TabBarRow
import app.andy.ui.logcat.LogcatPanel
import app.andy.ui.logcat.LogcatState
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.PaneDividerTint
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Yellow

/** Where an auxiliary surface docks relative to the main workspace. */
internal enum class DockPlacement { Right, Bottom }

/** Breathing room under dock content so the card's rounded bottom never clips it. */
private val DockContentCornerInset = AndySpace.Space2
private enum class PaneToggleEdge { Left, Right, Bottom }

/** Kind of surface shown inside a dock tab. */
internal enum class DockTabKind { Live, Terminal, Logs, Browser, Chat }

/**
 * One tab inside a right/bottom dock pane. A Terminal-kind tab is itself a whole split
 * workspace: [terminalTree] holds its panes, [focusedTerminalLeafId] which one last had
 * focus. A Live-kind tab owns a [liveTree] of mirror panes (one leaf, or a row/column
 * split); [focusedLiveLeafId] / [targetId] track the focused leaf for the tab strip.
 * Unlike Logs/Browser, multiple Terminal/Live/Chat tabs can coexist at top level.
 */
internal data class DockTab(
    val id: String,
    val kind: DockTabKind,
    val runId: String? = null,
    val title: String? = null,
    val terminalTree: TerminalPaneNode? = null,
    val focusedTerminalLeafId: String? = null,
    /** Child [app.andy.model.AgentTask] shown in a Chat dock tab, once started. */
    val agentTaskId: String? = null,
    /** Parent chat the side-chat tab was opened against. */
    val parentChatTaskId: String? = null,
    /**
     * Focused Live leaf's Android serial / iOS udid — mirrored from [liveTree] for tab-strip
     * titles and quick lookups. Prefer the leaf in [liveTree] as source of truth when split.
     */
    val targetId: String? = null,
    /** Split tree of device panes inside a Live workspace tab. */
    val liveTree: LivePaneNode? = null,
    val focusedLiveLeafId: String? = null,
) {
    companion object {
        /** Independent Live mirror workspace — seeded with a single-leaf [liveTree]. */
        fun live(
            id: String,
            leafId: String,
            targetId: String? = null,
            title: String? = null,
        ): DockTab = DockTab(
            id = id,
            kind = DockTabKind.Live,
            targetId = targetId,
            title = title,
            liveTree = LivePaneNode.Leaf(id = leafId, targetId = targetId, title = title),
            focusedLiveLeafId = leafId,
        )
        fun logs(): DockTab = DockTab(id = "logs", kind = DockTabKind.Logs)
        /** A leaf-level session tab — lives inside a [TerminalPaneNode.Leaf]'s own tab strip. */
        fun terminal(runId: String, title: String? = null): DockTab =
            DockTab(id = "terminal:$runId", kind = DockTabKind.Terminal, runId = runId, title = title)
        /** A top-level terminal workspace tab, seeded with a single-leaf [tree]. */
        fun terminalWorkspace(id: String, tree: TerminalPaneNode, focusedLeafId: String, title: String? = null): DockTab =
            DockTab(id = id, kind = DockTabKind.Terminal, title = title, terminalTree = tree, focusedTerminalLeafId = focusedLeafId)
        /** A Browser dock tab. WKWebView is process-wide, so the shell keeps at most one. */
        fun browser(id: String, title: String? = null): DockTab =
            DockTab(id = id, kind = DockTabKind.Browser, title = title)
        /** A side-chat dock tab. Multiple can coexist; they merge only on an exact id match. */
        fun chat(
            id: String,
            agentTaskId: String? = null,
            parentChatTaskId: String? = null,
            title: String? = null,
        ): DockTab = DockTab(
            id = id,
            kind = DockTabKind.Chat,
            title = title,
            agentTaskId = agentTaskId,
            parentChatTaskId = parentChatTaskId,
        )
    }
}

/** Navigation state for one Browser dock tab, keyed by [DockTab.id] on [ShellState.browserPanes]. */
internal data class BrowserPaneState(
    val url: String = "",
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val title: String? = null,
)

/** Independent right or bottom dock with a tab strip. */
internal data class DockPane(
    val tabs: List<DockTab> = emptyList(),
    val activeTabId: String? = null,
    val visible: Boolean = false,
) {
    val activeTab: DockTab?
        get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.lastOrNull()

    fun withTab(tab: DockTab): DockPane {
        // Logs/Browser are singletons per pane (WKWebView is process-wide). Live/Terminal/Chat
        // are independent and merge only on an exact id match.
        val existing = when (tab.kind) {
            DockTabKind.Logs, DockTabKind.Browser -> tabs.firstOrNull { it.kind == tab.kind }
            DockTabKind.Live, DockTabKind.Terminal, DockTabKind.Chat -> tabs.firstOrNull { it.id == tab.id }
        }
        return if (existing != null) {
            copy(visible = true, activeTabId = existing.id)
        } else {
            copy(visible = true, tabs = tabs + tab, activeTabId = tab.id)
        }
    }

    fun selectTab(tabId: String): DockPane {
        if (tabs.none { it.id == tabId }) return this
        return copy(visible = true, activeTabId = tabId)
    }

    fun closeTab(tabId: String): DockPane {
        val remaining = tabs.filter { it.id != tabId }
        if (remaining.isEmpty()) return DockPane()
        val nextActive = when {
            activeTabId != tabId -> activeTabId
            else -> remaining.last().id
        }
        return copy(tabs = remaining, activeTabId = nextActive, visible = true)
    }

    fun renameTab(tabId: String, title: String): DockPane {
        val normalized = title.trim().takeIf { it.isNotEmpty() } ?: return this
        if (tabs.none { it.id == tabId }) return this
        return copy(tabs = tabs.map { tab -> if (tab.id == tabId) tab.copy(title = normalized) else tab })
    }

    fun updateTab(tabId: String, transform: (DockTab) -> DockTab): DockPane {
        if (tabs.none { it.id == tabId }) return this
        return copy(tabs = tabs.map { tab -> if (tab.id == tabId) transform(tab) else tab })
    }

    fun hide(): DockPane = copy(visible = false)

    /** Finds the top-level Terminal tab (if any) whose tree owns [runId]. */
    fun tabOwningRun(runId: String): DockTab? =
        tabs.firstOrNull { it.kind == DockTabKind.Terminal && it.terminalTree?.leafOwningRun(runId) != null }

    /** Drops every dead run from every Terminal tab's tree, dropping tabs left with no runs at all. */
    fun withoutTerminalRuns(aliveRunIds: Set<String>): DockPane {
        var changed = false
        val nextTabs = tabs.mapNotNull { tab ->
            if (tab.kind != DockTabKind.Terminal) return@mapNotNull tab
            val tree = tab.terminalTree ?: return@mapNotNull tab
            when (val next = tree.pruneRuns(aliveRunIds)) {
                tree -> tab
                null -> { changed = true; null }
                else -> {
                    changed = true
                    tab.copy(
                        terminalTree = next,
                        focusedTerminalLeafId = tab.focusedTerminalLeafId?.takeIf { next.findLeaf(it) != null } ?: next.firstLeafId(),
                    )
                }
            }
        }
        if (!changed) return this
        if (nextTabs.isEmpty()) return DockPane()
        val nextActive = if (nextTabs.any { it.id == activeTabId }) activeTabId else nextTabs.last().id
        return copy(tabs = nextTabs, activeTabId = nextActive)
    }

    fun withoutKind(kind: DockTabKind): DockPane {
        val remaining = tabs.filter { it.kind != kind }
        if (remaining.size == tabs.size) return this
        if (remaining.isEmpty()) return DockPane()
        val nextActive = when {
            remaining.any { it.id == activeTabId } -> activeTabId
            else -> remaining.last().id
        }
        return copy(tabs = remaining, activeTabId = nextActive)
    }

    /**
     * Unstarted side-chat tabs follow the chat currently on screen so opening the pane
     * before selecting a session still binds once one is.
     */
    fun bindUnstartedSideChats(parentId: String, title: String): DockPane {
        var changed = false
        val next = tabs.map { tab ->
            if (tab.kind == DockTabKind.Chat && tab.agentTaskId == null && tab.parentChatTaskId != parentId) {
                changed = true
                tab.copy(parentChatTaskId = parentId, title = title)
            } else {
                tab
            }
        }
        return if (changed) copy(tabs = next) else this
    }

    /**
     * Chat tabs are companions to Agents/Projects. Off those destinations they drop out of
     * the visible strip; [visible] is false when that would leave the pane empty.
     */
    fun forDisplay(showChat: Boolean): DockPane {
        if (showChat) return this
        val remaining = tabs.filter { it.kind != DockTabKind.Chat }
        if (remaining.size == tabs.size) return this
        if (remaining.isEmpty()) return DockPane()
        val nextActive = remaining.firstOrNull { it.id == activeTabId }?.id ?: remaining.last().id
        return copy(tabs = remaining, activeTabId = nextActive)
    }
}

/**
 * Global shell docks. Placement icons toggle a pane that already has tabs;
 * a landing menu picks Live / Terminal / Browser / Chat only when that pane is empty.
 */
internal data class ShellDocks(
    val right: DockPane = DockPane(),
    val bottom: DockPane = DockPane(),
    val landingFor: DockPlacement? = null,
) {
    fun pane(placement: DockPlacement): DockPane = when (placement) {
        DockPlacement.Right -> right
        DockPlacement.Bottom -> bottom
    }

    /**
     * Right/bottom chrome button: hide if the displayed pane is open, show if it
     * already has visible tabs, otherwise toggle the Live / Terminal / Browser / Chat
     * landing chooser. [showChat] matches destination chrome so chat-only panes
     * off Agents/Projects still open the chooser.
     */
    fun onPlacementIconClick(placement: DockPlacement, showChat: Boolean = true): ShellDocks {
        val pane = pane(placement).forDisplay(showChat)
        return when {
            pane.visible -> update(placement) { it.hide() }
            pane.tabs.isNotEmpty() -> update(placement) { it.copy(visible = true) }
            landingFor == placement -> copy(landingFor = null)
            else -> copy(landingFor = placement)
        }
    }

    fun update(placement: DockPlacement, transform: (DockPane) -> DockPane): ShellDocks {
        val next = transform(pane(placement))
        return when (placement) {
            DockPlacement.Right -> copy(right = next, landingFor = null)
            DockPlacement.Bottom -> copy(bottom = next, landingFor = null)
        }
    }

    /**
     * WKWebView is a single process-wide overlay — keep at most one Browser tab across
     * both panes, moving an existing tab if the user opens Browser on the other dock.
     */
    fun withBrowserExclusive(placement: DockPlacement, tab: DockTab): ShellDocks {
        val existing = pane(placement).tabs.firstOrNull { it.kind == DockTabKind.Browser }
            ?: pane(if (placement == DockPlacement.Right) DockPlacement.Bottom else DockPlacement.Right)
                .tabs.firstOrNull { it.kind == DockTabKind.Browser }
        val keep = existing ?: tab
        val clearedOther = when (placement) {
            DockPlacement.Right -> copy(bottom = bottom.withoutKind(DockTabKind.Browser))
            DockPlacement.Bottom -> copy(right = right.withoutKind(DockTabKind.Browser))
        }
        return clearedOther.update(placement) { it.withTab(keep) }
    }

    /**
     * Reveals [runId]'s terminal tab in [placement] — selecting it in place if it already
     * exists there, moving it over (as a fresh single-leaf tab) if it lived in the other
     * placement, or creating a brand-new top-level tab if it isn't open anywhere. This is
     * for *finding* a specific run's terminal, not for "open a fresh shell" (that's
     * [DockPane.withTab] with a freshly built [DockTab.terminalWorkspace] directly — see
     * `ShellState.openNewTerminalTab`, which never searches and always creates new).
     */
    fun withTerminalExclusive(
        placement: DockPlacement,
        runId: String,
        newTabId: String,
        newLeafId: String,
        title: String? = null,
    ): ShellDocks {
        val other = if (placement == DockPlacement.Right) DockPlacement.Bottom else DockPlacement.Right
        val foundInOther = pane(other).tabOwningRun(runId)
        val strippedOtherPane = if (foundInOther == null) {
            pane(other)
        } else {
            pane(other).let { p ->
                val next = foundInOther.terminalTree!!.withoutRun(runId)
                val nextTabs = if (next == null) {
                    p.tabs.filterNot { it.id == foundInOther.id }
                } else {
                    p.tabs.map {
                        if (it.id == foundInOther.id) {
                            it.copy(
                                terminalTree = next,
                                focusedTerminalLeafId = it.focusedTerminalLeafId?.takeIf { id -> next.findLeaf(id) != null } ?: next.firstLeafId(),
                            )
                        } else {
                            it
                        }
                    }
                }
                if (nextTabs.isEmpty()) {
                    DockPane()
                } else {
                    p.copy(tabs = nextTabs, activeTabId = if (nextTabs.any { it.id == p.activeTabId }) p.activeTabId else nextTabs.last().id)
                }
            }
        }
        val withOtherStripped = when (other) {
            DockPlacement.Right -> copy(right = strippedOtherPane)
            DockPlacement.Bottom -> copy(bottom = strippedOtherPane)
        }
        val target = withOtherStripped.pane(placement)
        val foundInTarget = target.tabOwningRun(runId)
        val updatedTarget = if (foundInTarget != null) {
            val tree = foundInTarget.terminalTree!!
            val leaf = tree.leafOwningRun(runId)!!
            val innerTabId = leaf.tabs.first { it.runId == runId }.id
            target.copy(
                visible = true,
                activeTabId = foundInTarget.id,
                tabs = target.tabs.map {
                    if (it.id == foundInTarget.id) it.copy(focusedTerminalLeafId = leaf.id, terminalTree = tree.selectTab(leaf.id, innerTabId))
                    else it
                },
            )
        } else {
            // Not found anywhere — preserve a title carried over from the other placement
            // (if this run just moved from there) unless the caller passed an explicit one.
            val carriedTitle = title
                ?: foundInOther?.terminalTree?.leafOwningRun(runId)?.tabs?.firstOrNull { it.runId == runId }?.title
            val tab = DockTab.terminal(runId, title = carriedTitle)
            val tree = TerminalPaneNode.Leaf(newLeafId, listOf(tab), tab.id)
            target.withTab(DockTab.terminalWorkspace(newTabId, tree, newLeafId, carriedTitle))
        }
        return when (placement) {
            DockPlacement.Right -> withOtherStripped.copy(right = updatedTarget, landingFor = null)
            DockPlacement.Bottom -> withOtherStripped.copy(bottom = updatedTarget, landingFor = null)
        }
    }

    /**
     * Browser tab to reuse for a new URL, if one is already open in either dock.
     * Prefers the tab the user is currently looking at, then any existing Browser tab
     * (right before bottom, newest last).
     */
    fun existingBrowserTab(): Pair<DockPlacement, String>? {
        if (right.visible) {
            right.activeTab?.takeIf { it.kind == DockTabKind.Browser }?.let {
                return DockPlacement.Right to it.id
            }
        }
        if (bottom.visible) {
            bottom.activeTab?.takeIf { it.kind == DockTabKind.Browser }?.let {
                return DockPlacement.Bottom to it.id
            }
        }
        right.tabs.lastOrNull { it.kind == DockTabKind.Browser }?.let {
            return DockPlacement.Right to it.id
        }
        bottom.tabs.lastOrNull { it.kind == DockTabKind.Browser }?.let {
            return DockPlacement.Bottom to it.id
        }
        return null
    }

    /**
     * Side-chat tab already opened against [parentTaskId], if any.
     * Prefers a visible pane's match, then right, then bottom.
     */
    fun existingChatTabForParent(parentTaskId: String): Pair<DockPlacement, String>? {
        fun DockPane.match(): DockTab? =
            tabs.lastOrNull { it.kind == DockTabKind.Chat && it.parentChatTaskId == parentTaskId }
        if (right.visible) right.match()?.let { return DockPlacement.Right to it.id }
        if (bottom.visible) bottom.match()?.let { return DockPlacement.Bottom to it.id }
        right.match()?.let { return DockPlacement.Right to it.id }
        bottom.match()?.let { return DockPlacement.Bottom to it.id }
        return null
    }
}

@Composable
internal fun PanePlacementToggle(
    placement: DockPlacement,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val label = buildString {
        append(if (selected) "Hide" else "Show")
        append(if (placement == DockPlacement.Right) " right pane" else " bottom pane")
    }
    PaneToggle(
        edge = if (placement == DockPlacement.Right) PaneToggleEdge.Right else PaneToggleEdge.Bottom,
        selected = selected,
        label = label,
        onClick = onClick,
    )
}

@Composable
internal fun ProjectPaneToggle(
    selected: Boolean,
    onClick: () -> Unit,
) {
    PaneToggle(
        edge = PaneToggleEdge.Left,
        selected = selected,
        label = if (selected) "Hide project pane" else "Show project pane",
        onClick = onClick,
    )
}

@Composable
private fun PaneToggle(
    edge: PaneToggleEdge,
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        selected -> AndyColors.SurfaceSelected
        hovered -> AndyColors.SurfaceHover
        else -> Color.Transparent
    }
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(AndyRadius.Control))
            .background(background, RoundedCornerShape(AndyRadius.Control))
            .semantics { contentDescription = label; role = Role.Button }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LucideIcon(
            when (edge) {
                PaneToggleEdge.Left -> Lucide.PanelLeft
                PaneToggleEdge.Right -> Lucide.PanelRight
                PaneToggleEdge.Bottom -> Lucide.PanelBottom
            },
            if (selected) TextPrimary else TextSecondary,
            Modifier.size(15.dp),
        )
    }
}

/** In-layout Live / Terminal / Browser / Chat chooser — no Popup, so docks reflow under it. */
@Composable
internal fun DockLandingPanel(
    onSelect: (DockTabKind) -> Unit,
    modifier: Modifier = Modifier,
    showChat: Boolean = true,
    layoutMenu: DockLayoutMenu? = null,
) {
    Column(modifier.fillMaxWidth()) {
        DockLandingItem(
            kind = DockTabKind.Live,
            label = "Live",
            onClick = { onSelect(DockTabKind.Live) },
        )
        DockLandingItem(
            kind = DockTabKind.Terminal,
            label = "Terminal",
            onClick = { onSelect(DockTabKind.Terminal) },
        )
        DockLandingItem(
            kind = DockTabKind.Browser,
            label = "Browser",
            onClick = { onSelect(DockTabKind.Browser) },
        )
        if (showChat) {
            DockLandingItem(
                kind = DockTabKind.Chat,
                label = "Chat",
                onClick = { onSelect(DockTabKind.Chat) },
            )
        }
        if (layoutMenu != null) {
            AndyHorizontalDivider()
            var naming by remember { mutableStateOf(false) }
            var draft by remember { mutableStateOf("") }
            val focusRequester = remember { FocusRequester() }

            fun commit() {
                val trimmed = draft.trim()
                if (trimmed.isNotEmpty()) {
                    layoutMenu.onSave(trimmed)
                }
                naming = false
            }

            if (!naming) {
                ChromeFlyoutRow(
                    label = "Save current layout…",
                    leading = { LucideIcon(Lucide.Save, TextSecondary, Modifier.size(16.dp)) },
                    enabled = layoutMenu.isSaveActionEnabled,
                    supporting = when {
                        layoutMenu.canSave && layoutMenu.atLimit -> "Limit reached (20)"
                        else -> null
                    },
                    onClick = {
                        draft = layoutMenu.defaultName()
                        naming = true
                    },
                )
            } else {
                LaunchedEffect(naming) {
                    if (naming) focusRequester.requestFocus()
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AndySpace.Space2, vertical = AndySpace.Space2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = draft,
                        onValueChange = { draft = it.take(40) },
                        singleLine = true,
                        chromeStyle = FieldChromeStyle.Standard,
                        placeholder = { Text("Layout name") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { commit() }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.Enter -> {
                                            commit()
                                            true
                                        }
                                        Key.Escape -> {
                                            naming = false
                                            true
                                        }
                                        else -> false
                                    }
                                } else {
                                    false
                                }
                            },
                    )
                }
            }

            layoutMenu.layouts.forEach { layout ->
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                ChromeFlyoutRow(
                    label = layout.name,
                    supporting = layout.summaryLine(),
                    leading = { LucideIcon(Lucide.LayoutGrid, TextSecondary, Modifier.size(16.dp)) },
                    modifier = Modifier.hoverable(interaction),
                    onClick = { layoutMenu.onLoad(layout.id) },
                    trailing = {
                        LucideIcon(
                            Lucide.X,
                            if (hovered) Red else Color.Transparent,
                            Modifier
                                .size(14.dp)
                                .semantics {
                                    contentDescription = "Delete layout ${layout.name}"
                                    role = Role.Button
                                }
                                .clickable { layoutMenu.onDelete(layout.id) },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun DockLandingItem(
    kind: DockTabKind,
    label: String,
    onClick: () -> Unit,
) {
    ChromeFlyoutRow(
        label = label,
        onClick = onClick,
        leading = {
            DockKindIcon(kind = kind, modifier = Modifier.size(16.dp))
        },
    )
}

@Composable
private fun DockKindIcon(kind: DockTabKind, modifier: Modifier = Modifier) {
    LucideIcon(
        when (kind) {
            DockTabKind.Live -> Lucide.Smartphone
            DockTabKind.Terminal -> Lucide.SquareTerminal
            DockTabKind.Logs -> Lucide.AlignJustify
            DockTabKind.Browser -> Lucide.Globe
            DockTabKind.Chat -> Lucide.MessageSquare
        },
        TextSecondary,
        modifier,
    )
}

/** Layouts section of [DockLandingPanel]; null hides the section entirely. */
internal data class DockLayoutMenu(
    val layouts: List<SavedDockLayout>,
    val canSave: Boolean,
    val atLimit: Boolean,
    val defaultName: () -> String,
    val onSave: (name: String) -> Unit,
    val onLoad: (layoutId: String) -> Unit,
    val onDelete: (layoutId: String) -> Unit,
) {
    val isSaveActionEnabled: Boolean
        get() = canSave
}

/**
 * Callbacks for a terminal workspace tab's split tree. Every callback is scoped by
 * [topLevelTabId] first — the id of the top-level Terminal [DockTab] the tree belongs to —
 * since multiple independent terminal workspaces can be open at once.
 */
internal data class TerminalPaneCallbacks(
    val onSelectTab: (topLevelTabId: String, leafId: String, tabId: String) -> Unit,
    val onCloseTab: (topLevelTabId: String, tabId: String) -> Unit,
    val onRenameTab: (topLevelTabId: String, tabId: String, title: String) -> Unit,
    val onAddTab: (topLevelTabId: String, leafId: String) -> Unit,
    val onSplit: (topLevelTabId: String, leafId: String, axis: SplitAxis) -> Unit,
    val onCloseLeaf: (topLevelTabId: String, leafId: String) -> Unit,
    val onFocusLeaf: (topLevelTabId: String, leafId: String) -> Unit,
    val onWeightsChanged: (topLevelTabId: String, splitId: String, weights: List<Float>) -> Unit,
    /** Opens a new top-level dock tab of [kind] — the Live half of a leaf's add menu. */
    val onAddPaneKind: (kind: DockTabKind) -> Unit,
)

@Composable
internal fun ShellDockDrawer(
    services: AndyServices,
    pane: DockPane,
    placement: DockPlacement,
    running: List<RunningAction>,
    serial: String?,
    device: AndroidDevice?,
    targetDisplayName: String?,
    logcat: LogcatService,
    appsService: AppService,
    selectedPackage: String?,
    onSelectedPackageChange: (String?) -> Unit,
    logcatState: LogcatState,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onRenameTab: (String, String) -> Unit,
    onOpenKind: (DockTabKind, newTerminal: Boolean) -> Unit,
    onClose: () -> Unit,
    terminalPaneCallbacks: TerminalPaneCallbacks,
    livePaneCallbacks: LivePaneCallbacks = LivePaneCallbacks(
        onSelectTarget = { _, _, _ -> },
        onFocusLeaf = { _, _ -> },
        onSplit = { _, _, _ -> },
        onCloseLeaf = { _, _ -> },
        onWeightsChanged = { _, _, _ -> },
    ),
    devices: List<AndroidDevice> = emptyList(),
    iosTargets: List<IosTarget> = emptyList(),
    deviceLabels: Map<String, String> = emptyMap(),
    liveMirrorFor: (targetId: String) -> MirrorEngine? = { null },
    liveTabPaused: (targetId: String?) -> Boolean = { false },
    liveTabPauseMessage: (targetId: String?) -> String = { "Live view pauses while another tab is open" },
    takenIosKinds: (targetId: String?) -> Set<IosTargetKind> = { emptySet() },
    browserPaneOf: (String) -> BrowserPaneState = { BrowserPaneState() },
    onBrowserNav: (tabId: String, BrowserNavCommand) -> Unit = { _, _ -> },
    onBrowserNavStateChanged: (tabId: String, title: String?, url: String, canGoBack: Boolean, canGoForward: Boolean, loading: Boolean) -> Unit = { _, _, _, _, _, _ -> },
    workspaceState: WorkspaceState = WorkspaceState(),
    onStartSideChat: (tabId: String, prompt: String, launch: SideChatLaunchConfig) -> Unit = { _, _, _ -> },
    sideChatLaunchingIds: Set<String> = emptySet(),
    showChatTabs: Boolean = true,
    viewedAgentTaskId: String? = null,
    modifier: Modifier = Modifier,
    terminalThemeId: String = TerminalThemePreset.Default.id,
    layoutMenu: DockLayoutMenu? = null,
) {
    val active = pane.activeTab
    val agentTasks by services.agentRuns.tasks.collectAsState()
    var addMenuExpanded by remember { mutableStateOf(false) }
    // Live device picker is a ChromeFlyout under the tab strip (same host as the add menu) so
    // Metal/SwingPanel mirrors reflow instead of painting over a Popup dropdown.
    var liveDevicePickerTabId by remember { mutableStateOf<String?>(null) }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, animationSpec = tween(170, easing = FastOutSlowInEasing))
    }
    // A Terminal tab paints its own theme background edge to edge, so the tab strip adopts
    // that same color and the pane reads as one surface instead of chrome above a terminal.
    // Light chrome keeps its own surface — theme backgrounds are dark and would strand the
    // tab labels on an unreadable header.
    val terminalHeader = active?.kind == DockTabKind.Terminal && !AndyColors.isLight
    val terminalBackground = remember(terminalThemeId) {
        Color(TerminalThemePreset.fromId(terminalThemeId).palette().background)
    }
    // Live and Chat have no theme of their own to borrow, so the strip drops to the canvas
    // the dock sits on — the pane then reads as the same surface as the main chat, not chrome.
    val liveHeader = active?.kind == DockTabKind.Live
    val chatHeader = active?.kind == DockTabKind.Chat
    val headerBackground = when {
        terminalHeader -> terminalBackground
        liveHeader || chatHeader -> AndyColors.ContentBg
        else -> AndyColors.SurfaceRaised
    }
    // One terminal workspace on its own needs no dock tab strip: the tree's own leaf strip
    // already names the session and carries its controls, so a second row above it is pure
    // chrome. The strip comes back the moment a second top-level tab exists — and in that
    // case the leaf strip stays hidden until the user actually splits (or opens a second
    // session in the leaf), so the dock row is the only header.
    val soloTerminalWorkspace = pane.tabs.size == 1 &&
        active != null &&
        active.kind == DockTabKind.Terminal &&
        active.terminalTree != null
    val hoistTerminalSplit = active
        ?.takeIf { it.kind == DockTabKind.Terminal }
        ?.let { tab ->
            val leaf = tab.terminalTree as? TerminalPaneNode.Leaf ?: return@let null
            leaf.takeUnless { terminalLeafChromeVisible(it, dockStripCollapsed = soloTerminalWorkspace) }
                ?.let { tab to it }
        }
    // Live split icons live on the dock strip only while this Live tab is a single leaf;
    // after a split, each leaf owns Terminal-style sub-tab chrome with its own split controls.
    // (+) still adds a separate top-level Live tab.
    val hoistLiveSplit = active
        ?.takeIf { it.kind == DockTabKind.Live }
        ?.takeIf { it.liveTree is LivePaneNode.Leaf || it.liveTree == null }
        ?.let { tab ->
            val leafId = tab.focusedLiveLeafId
                ?.takeIf { id -> tab.liveTree?.findLeaf(id) != null }
                ?: tab.liveTree?.firstLeafId()
                ?: return@let null
            tab to leafId
        }
    // Pad the tab strip only; the content below manages its own insets so Live/Terminal can
    // bleed to the card's side edges (PanelCard's default 20dp padding was leaving a visible
    // shelf under Live).
    PanelCard(
        modifier.graphicsLayer {
            alpha = 0.72f + reveal.value * 0.28f
            if (placement == DockPlacement.Right) translationX = (1f - reveal.value) * 28f
            else translationY = (1f - reveal.value) * 28f
        },
        background = headerBackground,
        borderColor = Color.Transparent,
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        if (!soloTerminalWorkspace) TabBarRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = AndySpace.Space5, top = AndySpace.Space4, end = AndySpace.Space5),
            scrollTabs = true,
            // Terminal (and Live) bleed into the card; a hairline under the tabs reintroduces
            // the chrome/content seam the matching header background was meant to erase.
            hasDivider = false,
            trailing = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    hoistTerminalSplit?.let { (tab, leaf) ->
                        SplitAxisIcon(
                            axis = SplitAxis.Row,
                            onClick = { terminalPaneCallbacks.onSplit(tab.id, leaf.id, SplitAxis.Row) },
                        )
                        SplitAxisIcon(
                            axis = SplitAxis.Column,
                            onClick = { terminalPaneCallbacks.onSplit(tab.id, leaf.id, SplitAxis.Column) },
                        )
                    }
                    hoistLiveSplit?.let { (tab, leafId) ->
                        SplitAxisIcon(
                            axis = SplitAxis.Row,
                            onClick = { livePaneCallbacks.onSplit(tab.id, leafId, SplitAxis.Row) },
                        )
                        SplitAxisIcon(
                            axis = SplitAxis.Column,
                            onClick = { livePaneCallbacks.onSplit(tab.id, leafId, SplitAxis.Column) },
                        )
                    }
                    DockIconChromeButton(
                        label = "Add pane tab",
                        onBleedSurface = terminalHeader || liveHeader || chatHeader,
                        onClick = {
                            liveDevicePickerTabId = null
                            addMenuExpanded = !addMenuExpanded
                        },
                        icon = { LucideIcon(Lucide.Plus, TextSecondary, Modifier.size(14.dp)) },
                    )
                    DockIconChromeButton(
                        label = if (placement == DockPlacement.Right) "Close right pane" else "Close bottom pane",
                        onBleedSurface = terminalHeader || liveHeader || chatHeader,
                        onClick = onClose,
                        icon = { LucideIcon(Lucide.X, TextSecondary, Modifier.size(14.dp)) },
                    )
                }
            },
        ) {
            val terminalTabs = pane.tabs.filter { it.kind == DockTabKind.Terminal }
            pane.tabs.forEach { tab ->
                val runningAction = if (tab.kind == DockTabKind.Terminal) tab.representativeRunningAction(running) else null
                val chatTask = if (tab.kind == DockTabKind.Chat) {
                    tab.agentTaskId?.let { id -> agentTasks.firstOrNull { it.id == id } }
                } else {
                    null
                }
                val accent = when (tab.kind) {
                    DockTabKind.Live -> Cyan
                    DockTabKind.Logs -> Green
                    DockTabKind.Terminal -> dockActionStatusColor(runningAction?.status)
                    DockTabKind.Browser -> Cyan
                    DockTabKind.Chat -> if (chatTask?.unread == true) Yellow else Cyan
                }
                val selected = tab.id == active?.id
                // Split Live tabs own per-pane device pickers; the dock tab is just a workspace label.
                val liveIsSplit = tab.kind == DockTabKind.Live && tab.liveTree is LivePaneNode.Split
                val liveLabel = if (tab.kind == DockTabKind.Live) {
                    when {
                        liveIsSplit -> "Devices"
                        else -> tab.title
                            ?: tab.targetId?.let { id ->
                                deviceLabels[id]
                                    ?: devices.firstOrNull { it.serial == id }?.displayName
                                    ?: iosTargets.firstOrNull { it.udid == id }?.displayName
                            }
                            ?: "Live"
                    }
                } else {
                    null
                }
                TabBarItem(
                    label = liveLabel ?: tab.title ?: when (tab.kind) {
                        DockTabKind.Live -> "Live"
                        DockTabKind.Logs -> "Logs"
                        DockTabKind.Terminal -> dockTerminalWorkspaceLabel(tab, terminalTabs)
                        DockTabKind.Browser ->
                            browserPaneOf(tab.id).title?.takeIf { it.isNotBlank() } ?: "Browser"
                        DockTabKind.Chat -> chatTask?.title?.takeIf { it.isNotBlank() } ?: "Side chat"
                    },
                    selected = selected,
                    onClick = {
                        resignEmbeddedBrowserKey()
                        if (tab.kind == DockTabKind.Live) {
                            addMenuExpanded = false
                            when {
                                liveIsSplit -> {
                                    liveDevicePickerTabId = null
                                    onSelectTab(tab.id)
                                }
                                selected && liveDevicePickerTabId == tab.id -> {
                                    liveDevicePickerTabId = null
                                }
                                else -> {
                                    onSelectTab(tab.id)
                                    liveDevicePickerTabId = tab.id
                                }
                            }
                        } else {
                            liveDevicePickerTabId = null
                            onSelectTab(tab.id)
                        }
                    },
                    onRename = if (tab.kind == DockTabKind.Live) {
                        null // Single-leaf click opens the device picker; skip rename on Live tabs.
                    } else {
                        { title -> onRenameTab(tab.id, title) }
                    },
                    modifier = Modifier.pointerInput(tab.id) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Press && event.buttons.isTertiaryPressed) {
                                    resignEmbeddedBrowserKey()
                                    onCloseTab(tab.id)
                                }
                            }
                        }
                    },
                    indicatorColor = accent,
                    leading = {
                        when (tab.kind) {
                            DockTabKind.Live -> LucideIcon(Lucide.Smartphone, if (selected) accent else accent.copy(alpha = 0.6f), Modifier.size(12.dp))
                            DockTabKind.Logs -> LucideIcon(Lucide.AlignJustify, if (selected) accent else accent.copy(alpha = 0.6f), Modifier.size(12.dp))
                            DockTabKind.Terminal -> ActionIcon(
                                runningAction?.icon.orEmpty(),
                                if (selected) accent else accent.copy(alpha = 0.6f),
                                Modifier.size(12.dp),
                            )
                            DockTabKind.Browser -> LucideIcon(Lucide.Globe, if (selected) accent else accent.copy(alpha = 0.6f), Modifier.size(12.dp))
                            DockTabKind.Chat -> LucideIcon(Lucide.MessageSquare, if (selected) accent else accent.copy(alpha = 0.6f), Modifier.size(12.dp))
                        }
                    },
                    trailing = { hovered ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (tab.kind == DockTabKind.Live && !liveIsSplit) {
                                LucideIcon(
                                    Lucide.ChevronDown,
                                    if (selected) accent else accent.copy(alpha = 0.55f),
                                    Modifier.size(10.dp),
                                )
                            }
                            LucideIcon(
                                Lucide.X,
                                if (hovered) Red else Color.Transparent,
                                Modifier
                                    .size(14.dp)
                                    .semantics { contentDescription = "Close tab"; role = Role.Button }
                                    .clickable(onClick = {
                                        resignEmbeddedBrowserKey()
                                        onCloseTab(tab.id)
                                    }),
                            )
                        }
                    },
                )
            }
        }
        ChromeFlyout(visible = addMenuExpanded) {
            DockLandingPanel(
                onSelect = { kind ->
                    addMenuExpanded = false
                    onOpenKind(kind, true)
                },
                showChat = showChatTabs,
                layoutMenu = layoutMenu?.let { menu ->
                    menu.copy(
                        onLoad = { id -> menu.onLoad(id); addMenuExpanded = false },
                        onSave = { name -> menu.onSave(name); addMenuExpanded = false },
                    )
                },
            )
        }
        val livePickerTab = liveDevicePickerTabId?.let { id -> pane.tabs.firstOrNull { it.id == id } }
            ?.takeIf { it.kind == DockTabKind.Live }
        ChromeFlyout(
            visible = livePickerTab != null,
            contentKey = livePickerTab?.id,
        ) {
            LiveDevicePickerPanel(
                devices = devices,
                iosTargets = iosTargets,
                deviceLabels = deviceLabels,
                onSelectTarget = { targetId ->
                    val tab = livePickerTab ?: return@LiveDevicePickerPanel
                    val leafId = tab.focusedLiveLeafId
                        ?.takeIf { id -> tab.liveTree?.findLeaf(id) != null }
                        ?: tab.liveTree?.firstLeafId()
                        ?: return@LiveDevicePickerPanel
                    livePaneCallbacks.onSelectTarget(tab.id, leafId, targetId)
                    liveDevicePickerTabId = null
                },
            )
        }
        // Live, Terminal, and Chat fill their own background/theme, so bleed them to the
        // card edges rather than boxing them in — that's what makes them feel like a real
        // terminal/device/chat surface instead of a widget floating inside a card.
        val edgeToEdge = active?.kind == DockTabKind.Live || active?.kind == DockTabKind.Terminal ||
            active?.kind == DockTabKind.Browser || active?.kind == DockTabKind.Chat
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // Terminal owns its theme color, so carry it under the corner inset too —
                // otherwise the strip below the last text row exposes the chrome surface.
                // Chat uses the same canvas as the main Agents transcript.
                .then(
                    when (active?.kind) {
                        DockTabKind.Terminal -> Modifier.background(terminalBackground)
                        DockTabKind.Chat -> Modifier.background(AndyColors.ContentBg)
                        else -> Modifier
                    },
                )
                .padding(
                    start = if (edgeToEdge) 0.dp else AndySpace.Space5,
                    end = if (edgeToEdge) 0.dp else AndySpace.Space5,
                    top = when (active?.kind) {
                        // Breath under the tab/close chrome before the mirror surface.
                        DockTabKind.Live -> AndySpace.Space3
                        else -> if (edgeToEdge) 0.dp else AndySpace.Space4
                    },
                    // Browser clips itself to the card's rounded bottom; other kinds keep a
                    // hairline inset so Compose content doesn't sit on the corner arc.
                    // Chat sits on the same canvas as the main transcript, so keep the composer
                    // on that bottom edge instead of floating it above the card corner.
                    bottom = if (active?.kind == DockTabKind.Browser || active?.kind == DockTabKind.Chat) {
                        0.dp
                    } else {
                        DockContentCornerInset
                    },
                ),
        ) {
            when (active?.kind) {
                DockTabKind.Terminal -> {
                    if (active.terminalTree == null) {
                        EmptyState("Run an action to open its terminal")
                    } else {
                        TerminalPaneTreeView(
                            services = services,
                            tab = active,
                            running = running,
                            terminalBackground = terminalBackground,
                            callbacks = terminalPaneCallbacks,
                            modifier = Modifier.fillMaxSize(),
                            // Terminal leaves host the add menu (including saved layouts) whenever
                            // the dock strip is collapsed or layoutMenu is provided.
                            addKindMenu = soloTerminalWorkspace || layoutMenu != null,
                            dockStripCollapsed = soloTerminalWorkspace,
                            showChatInAddMenu = showChatTabs,
                            layoutMenu = layoutMenu,
                        )
                    }
                }
                DockTabKind.Live -> {
                    if (active.liveTree == null) {
                        EmptyState("Select a device to mirror")
                    } else {
                        LivePaneTreeView(
                            services = services,
                            tab = active,
                            devices = devices,
                            iosTargets = iosTargets,
                            deviceLabels = deviceLabels,
                            liveMirrorFor = liveMirrorFor,
                            liveTabPaused = liveTabPaused,
                            liveTabPauseMessage = liveTabPauseMessage,
                            takenIosKinds = takenIosKinds,
                            callbacks = livePaneCallbacks,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                DockTabKind.Logs -> {
                    LogcatPanel(
                        logcat = logcat,
                        appsService = appsService,
                        serial = serial,
                        selectedPackage = selectedPackage,
                        onSelectedPackageChange = onSelectedPackageChange,
                        modifier = Modifier.fillMaxSize(),
                        compact = true,
                        embedded = true,
                        state = logcatState,
                    )
                }
                // Browser is retained below so switching to Live/Terminal doesn't
                // dispose WKWebView and reload the page on the way back.
                DockTabKind.Browser -> Unit
                DockTabKind.Chat -> SideChatPaneView(
                    services = services,
                    tab = active,
                    workspaceState = workspaceState,
                    launching = active.id in sideChatLaunchingIds,
                    dictationActive = true,
                    viewedAgentTaskId = viewedAgentTaskId,
                    onStart = { prompt, launch -> onStartSideChat(active.id, prompt, launch) },
                    modifier = Modifier.fillMaxSize(),
                )
                null -> EmptyState("Choose Live, Terminal, Browser, or Chat")
            }
            RetainedBrowserDockContent(
                services = services,
                pane = pane,
                active = active,
                browserPaneOf = browserPaneOf,
                onBrowserNav = onBrowserNav,
                onBrowserNavStateChanged = onBrowserNavStateChanged,
            )
        }
    }
}

/**
 * Keeps the last Browser tab in composition while the user is on Live/Terminal/Logs
 * so the WKWebView overlay is hidden rather than destroyed. A blank new Browser tab
 * still uncomposes [BrowserSurface] (empty-state branch); the platform host must
 * therefore also survive dispose without reloading the same URL.
 */
@Composable
private fun RetainedBrowserDockContent(
    services: AndyServices,
    pane: DockPane,
    active: DockTab?,
    browserPaneOf: (String) -> BrowserPaneState,
    onBrowserNav: (tabId: String, BrowserNavCommand) -> Unit,
    onBrowserNavStateChanged: (tabId: String, title: String?, url: String, canGoBack: Boolean, canGoForward: Boolean, loading: Boolean) -> Unit,
) {
    var lastBrowserTabId by remember { mutableStateOf<String?>(null) }
    val activeBrowserId = active?.takeIf { it.kind == DockTabKind.Browser }?.id
    val retainedBrowserTabId = (activeBrowserId ?: lastBrowserTabId)
        ?.takeIf { id -> pane.tabs.any { it.id == id && it.kind == DockTabKind.Browser } }
    SideEffect { lastBrowserTabId = retainedBrowserTabId }
    val browserTab = pane.tabs.firstOrNull { it.id == retainedBrowserTabId } ?: return
    val browserActive = active?.id == browserTab.id
    val parentSuppressHeavyweight = LocalSuppressHeavyweightSurfaces.current
    Box(if (browserActive) Modifier.fillMaxSize() else Modifier.size(0.dp).clipToBounds()) {
        CompositionLocalProvider(
            LocalSuppressHeavyweightSurfaces provides (!browserActive || parentSuppressHeavyweight),
        ) {
            BrowserPaneView(
                services = services,
                state = browserPaneOf(browserTab.id),
                onNav = { command -> onBrowserNav(browserTab.id, command) },
                onNavStateChanged = { title, url, canBack, canForward, loading ->
                    onBrowserNavStateChanged(browserTab.id, title, url, canBack, canForward, loading)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun DockIconChromeButton(
    label: String,
    onClick: () -> Unit,
    onBleedSurface: Boolean = false,
    icon: @Composable () -> Unit,
) {
    // On a header that has taken on the terminal theme or the shell canvas, the neutral chrome
    // fill either reads as a patch of a different theme or vanishes into the background, so lift
    // the button off whatever it sits on instead of replacing the color.
    val lifted = onBleedSurface && !AndyColors.isLight
    val fill = when {
        lifted -> Color.White.copy(alpha = 0.06f)
        onBleedSurface -> AndyColors.SurfaceRaised
        else -> AndyColors.Neutral850
    }
    val stroke = if (lifted) Color.White.copy(alpha = 0.12f) else PaneDividerTint
    Box(
        Modifier
            .size(28.dp)
            .background(fill, RoundedCornerShape(AndyRadius.Control))
            .border(1.dp, stroke, RoundedCornerShape(AndyRadius.Control))
            .semantics { contentDescription = label; role = Role.Button }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
}

internal fun dockTerminalTabLabel(
    tab: DockTab,
    terminalTabs: List<DockTab>,
    runningAction: RunningAction?,
): String {
    val base = runningAction?.actionName ?: "Terminal"
    if (terminalTabs.size <= 1 || runningAction?.actionId != "terminal") return base
    val index = terminalTabs.indexOfFirst { it.id == tab.id }
    return if (index < 0) base else "$base ${index + 1}"
}

internal fun dockActionStatusColor(status: ActionRunStatus?): Color = when (status) {
    ActionRunStatus.Starting -> Yellow
    ActionRunStatus.Running -> Green
    ActionRunStatus.Exited -> Cyan
    ActionRunStatus.Failed -> Red
    ActionRunStatus.Stopped -> Rust
    null -> Rust
}

/** Outer-strip label for a top-level terminal workspace tab — "Terminal", "Terminal 2", ... */
internal fun dockTerminalWorkspaceLabel(tab: DockTab, terminalTabs: List<DockTab>): String {
    if (terminalTabs.size <= 1) return "Terminal"
    val index = terminalTabs.indexOfFirst { it.id == tab.id }
    return if (index <= 0) "Terminal" else "Terminal ${index + 1}"
}

/** The run backing whichever leaf/tab last had focus — used for the outer tab's status dot. */
internal fun DockTab.representativeRunningAction(running: List<RunningAction>): RunningAction? {
    val tree = terminalTree ?: return null
    val leaf = focusedTerminalLeafId?.let { tree.findLeaf(it) } ?: tree.flattenLeaves().firstOrNull()
    val runId = leaf?.activeTab?.runId ?: return null
    return running.firstOrNull { it.runId == runId }
}
