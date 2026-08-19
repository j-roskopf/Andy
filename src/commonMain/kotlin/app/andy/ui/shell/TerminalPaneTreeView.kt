package app.andy.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.RunningAction
import app.andy.service.AndyServices
import app.andy.ui.actions.ProjectTerminalSurface
import app.andy.ui.actions.actionIconMarker
import app.andy.ui.components.EmptyState
import app.andy.ui.components.HorizontalPaneDivider
import app.andy.ui.components.PaneDivider
import app.andy.ui.components.TabBarItem
import app.andy.ui.components.TabBarRow
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.TextSecondary

/**
 * A leaf's own tab strip duplicates the dock strip when the workspace is a single unsplit
 * pane with one session. Show it when the dock strip is collapsed (it's then the only
 * chrome), when the leaf holds multiple sessions, or when the workspace is split — each
 * pane needs its own strip and split/close controls.
 */
internal fun terminalLeafChromeVisible(
    tree: TerminalPaneNode,
    dockStripCollapsed: Boolean,
): Boolean = when (tree) {
    is TerminalPaneNode.Split -> true
    is TerminalPaneNode.Leaf -> dockStripCollapsed || tree.tabs.size > 1
}

/**
 * Renders one top-level terminal [tab]'s [TerminalPaneNode] tree — its recursive split/leaf
 * layout. With [addKindMenu] on, a leaf's "+" opens the dock's Live/Terminal menu instead of
 * adding a session outright — for when the dock collapsed its own strip and that "+" is the
 * only add button on screen.
 */
@Composable
internal fun TerminalPaneTreeView(
    services: AndyServices,
    tab: DockTab,
    running: List<RunningAction>,
    terminalBackground: Color,
    callbacks: TerminalPaneCallbacks,
    modifier: Modifier = Modifier,
    addKindMenu: Boolean = false,
    showChatInAddMenu: Boolean = true,
) {
    val tree = tab.terminalTree ?: return
    TerminalPaneNodeView(
        services = services,
        node = tree,
        focusedLeafId = tab.focusedTerminalLeafId,
        running = running,
        terminalBackground = terminalBackground,
        topLevelTabId = tab.id,
        callbacks = callbacks,
        modifier = modifier,
        addKindMenu = addKindMenu,
        showChatInAddMenu = showChatInAddMenu,
        showLeafChrome = terminalLeafChromeVisible(tree, dockStripCollapsed = addKindMenu),
    )
}

@Composable
private fun TerminalPaneNodeView(
    services: AndyServices,
    node: TerminalPaneNode,
    focusedLeafId: String?,
    running: List<RunningAction>,
    terminalBackground: Color,
    topLevelTabId: String,
    callbacks: TerminalPaneCallbacks,
    modifier: Modifier = Modifier,
    addKindMenu: Boolean = false,
    showChatInAddMenu: Boolean = true,
    showLeafChrome: Boolean,
) {
    when (node) {
        is TerminalPaneNode.Leaf -> TerminalLeafView(
            services = services,
            leaf = node,
            running = running,
            terminalBackground = terminalBackground,
            topLevelTabId = topLevelTabId,
            callbacks = callbacks,
            modifier = modifier,
            addKindMenu = addKindMenu,
            showChatInAddMenu = showChatInAddMenu,
            showChrome = showLeafChrome,
        )
        is TerminalPaneNode.Split -> TerminalSplitView(
            services = services,
            node = node,
            focusedLeafId = focusedLeafId,
            running = running,
            terminalBackground = terminalBackground,
            topLevelTabId = topLevelTabId,
            callbacks = callbacks,
            modifier = modifier,
            addKindMenu = addKindMenu,
            showChatInAddMenu = showChatInAddMenu,
            showLeafChrome = showLeafChrome,
        )
    }
}

@Composable
private fun TerminalSplitView(
    services: AndyServices,
    node: TerminalPaneNode.Split,
    focusedLeafId: String?,
    running: List<RunningAction>,
    terminalBackground: Color,
    topLevelTabId: String,
    callbacks: TerminalPaneCallbacks,
    modifier: Modifier = Modifier,
    addKindMenu: Boolean = false,
    showChatInAddMenu: Boolean = true,
    showLeafChrome: Boolean,
) {
    // Local weights drive the live drag feel; committed to the tree via onWeightsChanged on
    // drag end (same local-then-persist pattern as the dock's own width/height dividers).
    var weights by remember(node.id) { mutableStateOf(node.weights) }
    if (weights.size != node.weights.size) weights = node.weights

    BoxWithConstraints(modifier) {
        val totalDp = if (node.axis == SplitAxis.Row) maxWidth.value else maxHeight.value

        fun adjust(index: Int, deltaDp: Float) {
            if (totalDp <= 0f) return
            val delta = (deltaDp / totalDp).coerceIn(-0.4f, 0.4f)
            val next = weights.toMutableList()
            val newLeft = (next[index] + delta).coerceIn(0.12f, 0.88f)
            val applied = newLeft - next[index]
            next[index] = newLeft
            next[index + 1] = (next[index + 1] - applied).coerceIn(0.12f, 0.88f)
            weights = next
        }

        when (node.axis) {
            SplitAxis.Row -> Row(Modifier.fillMaxSize()) {
                node.children.forEachIndexed { index, child ->
                    Box(Modifier.weight(weights.getOrElse(index) { 1f }.coerceAtLeast(0.05f))) {
                        TerminalPaneNodeView(
                            services = services,
                            node = child,
                            focusedLeafId = focusedLeafId,
                            running = running,
                            terminalBackground = terminalBackground,
                            topLevelTabId = topLevelTabId,
                            callbacks = callbacks,
                            modifier = Modifier.fillMaxSize(),
                            addKindMenu = addKindMenu,
                            showChatInAddMenu = showChatInAddMenu,
                            showLeafChrome = showLeafChrome,
                        )
                    }
                    if (index < node.children.lastIndex) {
                        PaneDivider(
                            onDrag = { dx -> adjust(index, dx) },
                            onDragEnd = { callbacks.onWeightsChanged(topLevelTabId, node.id, weights) },
                        )
                    }
                }
            }
            SplitAxis.Column -> Column(Modifier.fillMaxSize()) {
                node.children.forEachIndexed { index, child ->
                    Box(Modifier.weight(weights.getOrElse(index) { 1f }.coerceAtLeast(0.05f))) {
                        TerminalPaneNodeView(
                            services = services,
                            node = child,
                            focusedLeafId = focusedLeafId,
                            running = running,
                            terminalBackground = terminalBackground,
                            topLevelTabId = topLevelTabId,
                            callbacks = callbacks,
                            modifier = Modifier.fillMaxSize(),
                            addKindMenu = addKindMenu,
                            showChatInAddMenu = showChatInAddMenu,
                            showLeafChrome = showLeafChrome,
                        )
                    }
                    if (index < node.children.lastIndex) {
                        HorizontalPaneDivider(
                            onDrag = { dy -> adjust(index, dy) },
                            onDragEnd = { callbacks.onWeightsChanged(topLevelTabId, node.id, weights) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalLeafView(
    services: AndyServices,
    leaf: TerminalPaneNode.Leaf,
    running: List<RunningAction>,
    terminalBackground: Color,
    topLevelTabId: String,
    callbacks: TerminalPaneCallbacks,
    modifier: Modifier = Modifier,
    addKindMenu: Boolean = false,
    showChatInAddMenu: Boolean = true,
    showChrome: Boolean,
) {
    var addMenuExpanded by remember { mutableStateOf(false) }
    Column(
        modifier
            .background(terminalBackground)
            // Observed on the Initial pass, press-only, so this never consumes the click the
            // terminal canvas or a tab item needs for its own click/keyboard-focus handling.
            .pointerInput(leaf.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) callbacks.onFocusLeaf(topLevelTabId, leaf.id)
                    }
                }
            },
    ) {
        val terminalTabs = leaf.tabs
        if (showChrome) {
            TabBarRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, top = 6.dp, end = 8.dp),
                scrollTabs = true,
                showDivider = false,
                trailing = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DockIconChromeButton(
                            label = if (addKindMenu) "Add tab" else "Add terminal tab",
                            onClick = {
                                if (!addKindMenu) {
                                    callbacks.onAddTab(topLevelTabId, leaf.id)
                                } else {
                                    addMenuExpanded = !addMenuExpanded
                                }
                            },
                            onBleedSurface = true,
                        ) {
                            Text("+", color = TextSecondary, fontFamily = MonoFont, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        SplitAxisIcon(axis = SplitAxis.Row, onClick = { callbacks.onSplit(topLevelTabId, leaf.id, SplitAxis.Row) })
                        SplitAxisIcon(axis = SplitAxis.Column, onClick = { callbacks.onSplit(topLevelTabId, leaf.id, SplitAxis.Column) })
                        DockIconChromeButton(label = "Close pane", onClick = { callbacks.onCloseLeaf(topLevelTabId, leaf.id) }, onBleedSurface = true) {
                            Text("×", color = TextSecondary, fontFamily = MonoFont, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                },
            ) {
                leaf.tabs.forEach { tab ->
                    val runningAction = tab.runId?.let { id -> running.firstOrNull { it.runId == id } }
                    val accent = dockActionStatusColor(runningAction?.status)
                    val selected = tab.id == leaf.activeTabId
                    TabBarItem(
                        label = tab.title ?: dockTerminalTabLabel(tab, terminalTabs, runningAction),
                        selected = selected,
                        onClick = { callbacks.onSelectTab(topLevelTabId, leaf.id, tab.id) },
                        onRename = { title -> callbacks.onRenameTab(topLevelTabId, tab.id, title) },
                        modifier = Modifier.pointerInput(tab.id) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Press && event.buttons.isTertiaryPressed) {
                                        callbacks.onCloseTab(topLevelTabId, tab.id)
                                    }
                                }
                            }
                        },
                        indicatorColor = accent,
                        leading = {
                            Text(
                                actionIconMarker(runningAction?.icon.orEmpty()),
                                color = if (selected) accent else accent.copy(alpha = 0.6f),
                                fontFamily = MonoFont,
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                            )
                        },
                        trailing = { hovered ->
                            Text(
                                "×",
                                color = if (hovered) Red else Color.Transparent,
                                fontFamily = MonoFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                lineHeight = 14.sp,
                                modifier = Modifier
                                    .semantics { contentDescription = "Close tab"; role = Role.Button }
                                    .clickable(onClick = { callbacks.onCloseTab(topLevelTabId, tab.id) }),
                            )
                        },
                    )
                }
            }
        }
        if (showChrome && addKindMenu) {
            ChromeFlyout(visible = addMenuExpanded) {
                DockLandingPanel(
                    onSelect = { kind ->
                        addMenuExpanded = false
                        // Terminal lands in this leaf so the pane keeps its single
                        // strip; Live has to become a dock tab of its own.
                        if (kind == DockTabKind.Terminal) callbacks.onAddTab(topLevelTabId, leaf.id)
                        else callbacks.onAddPaneKind(kind)
                    },
                    showChat = showChatInAddMenu,
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxSize()) {
            val runId = leaf.activeTab?.runId
            if (runId == null) {
                EmptyState("No terminal session")
            } else {
                ProjectTerminalSurface(services, runId, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
internal fun SplitAxisIcon(axis: SplitAxis, onClick: () -> Unit) {
    DockIconChromeButton(
        label = if (axis == SplitAxis.Row) "Split side by side" else "Split stacked",
        onClick = onClick,
        onBleedSurface = true,
    ) {
        Canvas(Modifier.size(15.dp)) {
            val stroke = Stroke(width = 1.3.dp.toPx())
            drawRoundRect(
                color = TextSecondary,
                topLeft = Offset(size.width * 0.08f, size.height * 0.08f),
                size = Size(size.width * 0.84f, size.height * 0.84f),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                style = stroke,
            )
            if (axis == SplitAxis.Row) {
                drawLine(
                    TextSecondary,
                    Offset(size.width * 0.5f, size.height * 0.14f),
                    Offset(size.width * 0.5f, size.height * 0.86f),
                    strokeWidth = stroke.width,
                )
            } else {
                drawLine(
                    TextSecondary,
                    Offset(size.width * 0.14f, size.height * 0.5f),
                    Offset(size.width * 0.86f, size.height * 0.5f),
                    strokeWidth = stroke.width,
                )
            }
        }
    }
}
