package app.andy.ui.accessibility

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.domain.findBestNodeAt
import app.andy.domain.parseBounds
import app.andy.model.AccessibilityNode
import app.andy.model.AndroidDevice
import app.andy.service.AndyServices
import app.andy.ui.live.LiveDevicePane
import app.andy.ui.live.LiveMirrorSettings
import app.andy.ui.live.MirrorFrameContent
import app.andy.ui.live.rememberMirrorInputSender
import app.andy.ui.components.DetailRow
import app.andy.ui.components.DetailSection
import app.andy.ui.components.FilterPill
import app.andy.ui.components.PaneDivider
import app.andy.ui.components.PanelCard
import app.andy.ui.components.Toolbar
import app.andy.ui.theme.Green
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Yellow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
internal fun AccessibilityScreen(
    services: AndyServices,
    serial: String?,
    device: AndroidDevice?,
    treePaneWidth: Float,
    onTreePaneWidthChange: (Float) -> Unit,
    state: AccessibilityState = remember { AccessibilityState() }
) {
    val scope = rememberCoroutineScope()
    var localTreePaneWidth by remember(treePaneWidth) { mutableStateOf(treePaneWidth.coerceIn(420f, 1400f)) }
    var mirrorStatus by remember { mutableStateOf("Disconnected") }
    var connectResult by remember { mutableStateOf("") }
    val sendMirrorInput = rememberMirrorInputSender(services, serial, enabled = !state.interactionMode)
    val flattenedNodes = remember(state.root, state.collapsedNodes.toMap(), state.interestingOnly) {
        val rows = state.root?.flattenAccessibilityTree(state.collapsedNodes).orEmpty()
        if (state.interestingOnly) rows.filter { it.node.isInterestingAccessibilityNode() } else rows
    }
    val treeListState = rememberLazyListState()

    LaunchedEffect(serial) {
        if (serial != state.lastSerial) {
            state.root = null
            state.status = "No dump loaded"
            state.hoveredBounds = null
            state.selectedNode = null
            state.interactionMode = false
            state.isInitialDumpDone = false
            state.isLoading = false
            state.lastSerial = serial
            state.layoutBounds = false
            state.interestingOnly = false
            state.collapsedNodes.clear()
        }
        if (serial != null) {
            val result = services.devices.shell(serial, listOf("getprop", "debug.layout"))
            if (result.isSuccess) {
                state.layoutBounds = result.stdout.trim() == "true"
            }
        }
    }

    fun dump() {
        if (serial == null) return
        scope.launch {
            state.isLoading = true
            state.status = "Dumping tree..."
            val result = services.accessibility.dump(serial)
            state.root = result
            state.selectedNode = result
            state.status = if (result == null) "No hierarchy returned" else "Hierarchy loaded · ${result.countNodes()} nodes"
            state.isLoading = false
            state.isInitialDumpDone = true
        }
    }

    LaunchedEffect(serial, state.isInitialDumpDone) {
        if (serial != null && !state.isInitialDumpDone && !state.isLoading) {
            dump()
        }
    }

    LaunchedEffect(state.selectedNode?.id, flattenedNodes.size) {
        val selectedId = state.selectedNode?.id ?: return@LaunchedEffect
        val index = flattenedNodes.indexOfFirst { it.node.id == selectedId }
        if (index >= 0) treeListState.animateScrollToItem(index)
    }

    LaunchedEffect(Unit) {
        services.mirror.status.collectLatest { mirrorStatus = it }
    }

    LaunchedEffect(serial) {
        if (serial != null) {
            val result = services.mirror.connect(serial, LiveMirrorSettings.config.value)
            connectResult = if (result.isSuccess) result.stdout else result.stderr
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Toolbar("Accessibility", state.status, onPrimary = { dump() }, primaryLabel = "Dump tree")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill("Inspect clicks", state.interactionMode, Rust) { state.interactionMode = !state.interactionMode }
            FilterPill("Interesting", state.interestingOnly, Green) { state.interestingOnly = !state.interestingOnly }
            FilterPill("Layout bounds", state.layoutBounds, Yellow) {
                val next = !state.layoutBounds
                state.layoutBounds = next
                if (serial != null) {
                    scope.launch {
                        services.devices.shell(serial, listOf("setprop", "debug.layout", next.toString()))
                        services.devices.shell(serial, listOf("service", "call", "activity", "1599295570"))
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.width(localTreePaneWidth.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.fillMaxWidth().weight(1f).background(PanelSoft, RoundedCornerShape(8.dp)).padding(10.dp)
                ) {
                    if (state.isLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Rust)
                        }
                    } else if (flattenedNodes.isNotEmpty()) {
                        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                            LazyColumn(state = treeListState, modifier = Modifier.widthIn(min = 980.dp).fillMaxHeight()) {
                                itemsIndexed(flattenedNodes, key = { _, row -> row.node.id }) { _, row ->
                                    AccessibilityNodeRow(
                                        row = row,
                                        hoveredBounds = state.hoveredBounds,
                                        selectedId = state.selectedNode?.id,
                                        isCollapsed = state.collapsedNodes[row.node.id] == true,
                                        onHover = { state.hoveredBounds = it },
                                        onSelect = {
                                            state.selectedNode = it
                                            state.hoveredBounds = it.bounds
                                        },
                                        onToggleCollapse = {
                                            val collapsed = state.collapsedNodes[row.node.id] == true
                                            state.collapsedNodes[row.node.id] = !collapsed
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        Text("Dump a tree to inspect nodes.", color = TextSecondary)
                    }
                }
                PanelCard(Modifier.fillMaxWidth().height(300.dp)) {
                    AccessibilityDetails(state.selectedNode)
                }
            }
            Spacer(Modifier.width(6.dp))
            PaneDivider(
                onDrag = { dragX -> localTreePaneWidth = (localTreePaneWidth + dragX).coerceIn(360f, 1600f) },
                onDragEnd = { onTreePaneWidthChange(localTreePaneWidth) },
            )
            MirrorFrameContent(services.mirror, serial) { frameFlow, frame ->
                LiveDevicePane(
                    serial = serial,
                    device = device,
                    frame = frame,
                    frameFlow = frameFlow,
                    mirrorStatus = mirrorStatus,
                    connectResult = connectResult,
                    modifier = Modifier.fillMaxSize().padding(start = 6.dp),
                    highlightBounds = state.hoveredBounds,
                    showHardwareControls = false,
                    passThroughInput = !state.interactionMode,
                    onDevicePointClick = { x, y ->
                        state.root?.findBestNodeAt(x, y)?.let {
                            state.selectedNode = it
                            state.hoveredBounds = it.bounds
                        }
                    },
                    onInput = sendMirrorInput,
                    onConnect = {
                        if (serial != null) scope.launch {
                            val result = services.mirror.connect(serial, LiveMirrorSettings.config.value)
                            connectResult = if (result.isSuccess) result.stdout else result.stderr
                        }
                    },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun AccessibilityNodeRow(
    row: AccessibilityTreeRow,
    hoveredBounds: String?,
    selectedId: String?,
    isCollapsed: Boolean,
    onHover: (String?) -> Unit,
    onSelect: (AccessibilityNode) -> Unit,
    onToggleCollapse: () -> Unit,
) {
    val node = row.node
    val active = node.bounds == hoveredBounds || node.id == selectedId
    val hasChildren = node.children.isNotEmpty()
    Row(
        Modifier.widthIn(min = 900.dp)
            .background(if (active) Rust.copy(alpha = 0.22f) else Color.Transparent, RoundedCornerShape(4.dp))
            .onPointerEvent(PointerEventType.Enter) { onHover(node.bounds) }
            .onPointerEvent(PointerEventType.Exit) { onHover(null) }
            .clickable { onSelect(node) }
            .padding(start = (row.depth * 12).dp, top = 2.dp, bottom = 2.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clickable(
                    enabled = hasChildren,
                    onClick = onToggleCollapse
                ),
            contentAlignment = Alignment.Center
        ) {
            if (hasChildren) {
                Text(
                    text = if (isCollapsed) ">" else "v",
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text("${node.className?.substringAfterLast('.') ?: "node"}  ${node.bounds ?: ""}", color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val label = listOfNotNull(node.resourceId, node.text, node.contentDescription).joinToString(" · ")
            if (label.isNotBlank()) Text(label, color = TextSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal data class AccessibilityTreeRow(val node: AccessibilityNode, val depth: Int)

internal fun AccessibilityNode.flattenAccessibilityTree(
    collapsedNodes: Map<String, Boolean>,
    depth: Int = 0
): List<AccessibilityTreeRow> {
    val row = AccessibilityTreeRow(this, depth)
    val isCollapsed = collapsedNodes[this.id] == true
    return if (isCollapsed) {
        listOf(row)
    } else {
        listOf(row) + children.flatMap { it.flattenAccessibilityTree(collapsedNodes, depth + 1) }
    }
}

internal fun AccessibilityNode.countNodes(): Int = 1 + children.sumOf { it.countNodes() }

internal fun AccessibilityNode.isInterestingAccessibilityNode(): Boolean {
    if (!visible || !enabled) return false
    if (packageName.isNullOrBlank() || packageName.startsWith("com.android.systemui")) return false
    val hasIdentity = !text.isNullOrBlank() || !contentDescription.isNullOrBlank() || !resourceId.isNullOrBlank()
    return hasIdentity || clickable || scrollable
}

@Composable
internal fun AccessibilityDetails(node: AccessibilityNode?) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("Selected", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text(node?.className ?: "No node", color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(node?.id?.let { "node[$it]" } ?: "-", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        if (node == null) return@Column
        val issues = buildList {
            if (node.clickable && node.text.isNullOrBlank() && node.contentDescription.isNullOrBlank()) add("No accessibility label")
            if (!node.visible) add("Not visible to user")
            if (!node.enabled) add("Disabled")
        }
        if (issues.isNotEmpty()) {
            Text("${issues.size} issues", color = Rust, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            issues.forEach { issue ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.background(Red, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("NAF", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(issue, color = TextPrimary, fontSize = 12.sp)
                }
            }
        }
        DetailSection("Identity")
        DetailRow("resource-id", node.resourceId)
        DetailRow("class", node.className?.substringAfterLast('.'))
        DetailRow("class-full", node.className)
        DetailRow("package", node.packageName)
        DetailRow("node-id", node.id)
        DetailRow("children", node.children.size.toString())
        DetailSection("Content")
        DetailRow("text", node.text)
        DetailRow("content-desc", node.contentDescription)
        DetailRow("hint", node.hint)
        DetailSection("Geometry")
        DetailRow("bounds", node.bounds)
        DetailRow("size", parseBounds(node.bounds)?.let { "${it[2] - it[0]}x${it[3] - it[1]}" })
        DetailSection("State")
        DetailRow("clickable", node.clickable.toString())
        DetailRow("long-clickable", node.longClickable.toString())
        DetailRow("focusable", node.focusable.toString())
        DetailRow("focused", node.focused.toString())
        DetailRow("enabled", node.enabled.toString())
        DetailRow("selected", node.selected.toString())
        DetailRow("checkable", node.checkable.toString())
        DetailRow("checked", node.checked.toString())
        DetailRow("scrollable", node.scrollable.toString())
        DetailRow("password", node.password.toString())
        DetailRow("visible", node.visible.toString())
        DetailSection("Computed")
        DetailRow("contrast", "-")
        DetailRow("label", node.contentDescription ?: node.text ?: node.hint)
        if (node.attributes.isNotEmpty()) {
            DetailSection("Raw Dump")
            node.attributes.entries.sortedBy { it.key }.forEach { (key, value) ->
                DetailRow(key, value)
            }
        }
    }
}
