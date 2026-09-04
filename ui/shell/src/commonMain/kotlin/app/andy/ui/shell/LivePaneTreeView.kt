package app.andy.ui.shell

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.andy.model.AndroidDevice
import app.andy.model.IosTarget
import app.andy.service.AndyServices
import app.andy.service.MirrorEngine
import app.andy.ui.components.EmptyState
import app.andy.ui.components.HorizontalPaneDivider
import app.andy.ui.components.Lucide
import app.andy.ui.components.LucideIcon
import app.andy.ui.components.PaneDivider
import app.andy.ui.components.TabBarItem
import app.andy.ui.components.TabBarRow
import app.andy.ui.live.DeviceLivePanel
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Red
import app.andy.ui.theme.TextSecondary

/**
 * Renders one Live dock tab's [LivePaneNode] tree. Split creates sibling panes under this
 * single top-level Live tab (not another dock tab). Each split leaf gets a Terminal-style
 * sub-tab strip for its device; an unsplit leaf keeps chrome on the dock tab strip.
 */
@Composable
internal fun LivePaneTreeView(
    services: AndyServices,
    tab: DockTab,
    devices: List<AndroidDevice>,
    iosTargets: List<IosTarget>,
    deviceLabels: Map<String, String>,
    liveMirrorFor: (targetId: String) -> MirrorEngine?,
    liveTabPaused: (targetId: String?) -> Boolean,
    liveTabPauseMessage: (targetId: String?) -> String,
    callbacks: LivePaneCallbacks,
    modifier: Modifier = Modifier,
) {
    val tree = tab.liveTree ?: return
    LivePaneNodeView(
        services = services,
        node = tree,
        focusedLeafId = tab.focusedLiveLeafId,
        topLevelTabId = tab.id,
        devices = devices,
        iosTargets = iosTargets,
        deviceLabels = deviceLabels,
        liveMirrorFor = liveMirrorFor,
        liveTabPaused = liveTabPaused,
        liveTabPauseMessage = liveTabPauseMessage,
        callbacks = callbacks,
        modifier = modifier,
        showLeafChrome = tree is LivePaneNode.Split,
    )
}

@Composable
private fun LivePaneNodeView(
    services: AndyServices,
    node: LivePaneNode,
    focusedLeafId: String?,
    topLevelTabId: String,
    devices: List<AndroidDevice>,
    iosTargets: List<IosTarget>,
    deviceLabels: Map<String, String>,
    liveMirrorFor: (targetId: String) -> MirrorEngine?,
    liveTabPaused: (targetId: String?) -> Boolean,
    liveTabPauseMessage: (targetId: String?) -> String,
    callbacks: LivePaneCallbacks,
    modifier: Modifier = Modifier,
    showLeafChrome: Boolean,
) {
    when (node) {
        is LivePaneNode.Leaf -> LiveLeafView(
            services = services,
            leaf = node,
            focused = node.id == focusedLeafId,
            topLevelTabId = topLevelTabId,
            devices = devices,
            iosTargets = iosTargets,
            deviceLabels = deviceLabels,
            liveMirrorFor = liveMirrorFor,
            liveTabPaused = liveTabPaused,
            liveTabPauseMessage = liveTabPauseMessage,
            callbacks = callbacks,
            modifier = modifier,
            showLeafChrome = showLeafChrome,
        )
        is LivePaneNode.Split -> LiveSplitView(
            services = services,
            node = node,
            focusedLeafId = focusedLeafId,
            topLevelTabId = topLevelTabId,
            devices = devices,
            iosTargets = iosTargets,
            deviceLabels = deviceLabels,
            liveMirrorFor = liveMirrorFor,
            liveTabPaused = liveTabPaused,
            liveTabPauseMessage = liveTabPauseMessage,
            callbacks = callbacks,
            modifier = modifier,
        )
    }
}

@Composable
private fun LiveSplitView(
    services: AndyServices,
    node: LivePaneNode.Split,
    focusedLeafId: String?,
    topLevelTabId: String,
    devices: List<AndroidDevice>,
    iosTargets: List<IosTarget>,
    deviceLabels: Map<String, String>,
    liveMirrorFor: (targetId: String) -> MirrorEngine?,
    liveTabPaused: (targetId: String?) -> Boolean,
    liveTabPauseMessage: (targetId: String?) -> String,
    callbacks: LivePaneCallbacks,
    modifier: Modifier = Modifier,
) {
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
                        LivePaneNodeView(
                            services = services,
                            node = child,
                            focusedLeafId = focusedLeafId,
                            topLevelTabId = topLevelTabId,
                            devices = devices,
                            iosTargets = iosTargets,
                            deviceLabels = deviceLabels,
                            liveMirrorFor = liveMirrorFor,
                            liveTabPaused = liveTabPaused,
                            liveTabPauseMessage = liveTabPauseMessage,
                            callbacks = callbacks,
                            modifier = Modifier.fillMaxSize(),
                            showLeafChrome = true,
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
                        LivePaneNodeView(
                            services = services,
                            node = child,
                            focusedLeafId = focusedLeafId,
                            topLevelTabId = topLevelTabId,
                            devices = devices,
                            iosTargets = iosTargets,
                            deviceLabels = deviceLabels,
                            liveMirrorFor = liveMirrorFor,
                            liveTabPaused = liveTabPaused,
                            liveTabPauseMessage = liveTabPauseMessage,
                            callbacks = callbacks,
                            modifier = Modifier.fillMaxSize(),
                            showLeafChrome = true,
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
private fun LiveLeafView(
    services: AndyServices,
    leaf: LivePaneNode.Leaf,
    focused: Boolean,
    topLevelTabId: String,
    devices: List<AndroidDevice>,
    iosTargets: List<IosTarget>,
    deviceLabels: Map<String, String>,
    liveMirrorFor: (targetId: String) -> MirrorEngine?,
    liveTabPaused: (targetId: String?) -> Boolean,
    liveTabPauseMessage: (targetId: String?) -> String,
    callbacks: LivePaneCallbacks,
    modifier: Modifier = Modifier,
    showLeafChrome: Boolean,
) {
    val targetId = leaf.targetId
    var pickerOpen by remember(leaf.id) { mutableStateOf(false) }
    val label = liveLeafLabel(leaf, devices, iosTargets, deviceLabels)
    Column(
        modifier
            .background(AndyColors.ContentBg)
            .pointerInput(leaf.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) {
                            callbacks.onFocusLeaf(topLevelTabId, leaf.id)
                        }
                    }
                }
            },
    ) {
        if (showLeafChrome) {
            TabBarRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, top = 6.dp, end = 8.dp, bottom = 8.dp),
                scrollTabs = true,
                hasDivider = false,
                trailing = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SplitAxisIcon(
                            axis = SplitAxis.Row,
                            onClick = { callbacks.onSplit(topLevelTabId, leaf.id, SplitAxis.Row) },
                        )
                        SplitAxisIcon(
                            axis = SplitAxis.Column,
                            onClick = { callbacks.onSplit(topLevelTabId, leaf.id, SplitAxis.Column) },
                        )
                        DockIconChromeButton(
                            label = "Close pane",
                            onClick = { callbacks.onCloseLeaf(topLevelTabId, leaf.id) },
                            onBleedSurface = true,
                        ) {
                            LucideIcon(Lucide.X, TextSecondary, Modifier.size(14.dp))
                        }
                    }
                },
            ) {
                TabBarItem(
                    label = label,
                    selected = focused,
                    onClick = {
                        callbacks.onFocusLeaf(topLevelTabId, leaf.id)
                        pickerOpen = !pickerOpen
                    },
                    onRename = null,
                    indicatorColor = Cyan,
                    leading = {
                        LucideIcon(
                            Lucide.Smartphone,
                            if (focused) Cyan else Cyan.copy(alpha = 0.6f),
                            Modifier.size(12.dp),
                        )
                    },
                    trailing = { hovered ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LucideIcon(
                                Lucide.ChevronDown,
                                if (focused) Cyan else Cyan.copy(alpha = 0.55f),
                                Modifier.size(10.dp),
                            )
                            LucideIcon(
                                Lucide.X,
                                if (hovered) Red else Color.Transparent,
                                Modifier
                                    .size(14.dp)
                                    .semantics { contentDescription = "Close pane"; role = Role.Button }
                                    .clickable(onClick = {
                                        callbacks.onCloseLeaf(topLevelTabId, leaf.id)
                                    }),
                            )
                        }
                    },
                )
            }
            ChromeFlyout(visible = pickerOpen, contentKey = leaf.id) {
                LiveDevicePickerPanel(
                    devices = devices,
                    iosTargets = iosTargets,
                    deviceLabels = deviceLabels,
                    onSelectTarget = { id ->
                        callbacks.onSelectTarget(topLevelTabId, leaf.id, id)
                        pickerOpen = false
                    },
                )
            }
        } else if (targetId == null) {
            // Unsplit empty leaf: local flyout so first-run doesn't require the dock chevron.
            ChromeFlyout(visible = pickerOpen, contentKey = leaf.id) {
                LiveDevicePickerPanel(
                    devices = devices,
                    iosTargets = iosTargets,
                    deviceLabels = deviceLabels,
                    onSelectTarget = { id ->
                        callbacks.onSelectTarget(topLevelTabId, leaf.id, id)
                        pickerOpen = false
                    },
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                targetId == null -> {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(
                                if (!showLeafChrome) {
                                    Modifier.pointerInput(leaf.id) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                if (event.type == PointerEventType.Press) {
                                                    pickerOpen = true
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        EmptyState("Select a device to mirror")
                    }
                }
                liveTabPaused(targetId) -> EmptyState(liveTabPauseMessage(targetId))
                else -> {
                    val mirror = liveMirrorFor(targetId)
                    if (mirror == null) {
                        // Dock Live must use a pooled engine — falling back to services.mirror
                        // would share one stream across every pane.
                        EmptyState("Connecting mirror…")
                    } else {
                        val liveDevice = devices.firstOrNull { it.serial == targetId }
                        key(leaf.id, targetId) {
                            DeviceLivePanel(
                                services = services,
                                serial = targetId,
                                device = liveDevice,
                                displayName = label,
                                mirror = mirror,
                                modifier = Modifier.fillMaxSize(),
                                showChromeControls = false,
                                showDeviceHeader = false,
                                showPopOut = false,
                                showContainerChrome = false,
                                deviceBorderWidth = 0.dp,
                                deviceCornerRadius = 0.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun liveLeafLabel(
    leaf: LivePaneNode.Leaf,
    devices: List<AndroidDevice>,
    iosTargets: List<IosTarget>,
    deviceLabels: Map<String, String>,
): String {
    val targetId = leaf.targetId ?: return leaf.title ?: "Live"
    return deviceLabels[targetId]
        ?: devices.firstOrNull { it.serial == targetId }?.displayName
        ?: iosTargets.firstOrNull { it.udid == targetId }?.displayName
        ?: leaf.title
        ?: "Live"
}
