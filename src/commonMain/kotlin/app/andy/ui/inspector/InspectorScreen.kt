package app.andy.ui.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.domain.HierarchyDiffEntry
import app.andy.domain.HierarchyDiffKind
import app.andy.domain.resolveHighlightBounds
import app.andy.domain.diffHierarchyTrees
import app.andy.domain.filterBySearch
import app.andy.domain.findBestNodeAt
import app.andy.domain.resolveHierarchyDisplaySize
import app.andy.model.AccessibilityNode
import app.andy.model.AndroidDevice
import app.andy.model.HierarchyOptions
import app.andy.model.HierarchySnapshot
import app.andy.model.HierarchySource
import app.andy.model.InvestigationEventKind
import app.andy.model.explainNodeRequest
import app.andy.service.AndyServices
import app.andy.service.MirrorSession
import app.andy.ui.agents.ContextualAiActionHost
import app.andy.ui.agents.ExplainActionButton
import app.andy.ui.agents.contextualAiActionsEnabled
import app.andy.ui.agents.findLatestInvestigationEvent
import app.andy.ui.agents.rememberContextualAiActionState
import app.andy.ui.components.DetailRow
import app.andy.ui.components.DetailSection
import app.andy.ui.components.FilterPill
import app.andy.ui.components.PaneDivider
import app.andy.ui.components.PanelCard
import app.andy.ui.components.Toolbar
import app.andy.ui.components.fieldColors
import app.andy.ui.hierarchy.HierarchyDetailsPanel
import app.andy.ui.hierarchy.HierarchyNodeRow
import app.andy.ui.hierarchy.countNodes
import app.andy.ui.hierarchy.findEquivalentNode
import app.andy.ui.hierarchy.flattenHierarchyTree
import app.andy.ui.hierarchy.isInterestingHierarchyNode
import app.andy.ui.live.LiveDevicePane
import app.andy.ui.live.LiveMirrorSettings
import app.andy.ui.live.MirrorFrameContent
import app.andy.ui.live.rememberMirrorInputSender
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.Green
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Yellow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Unified view-hierarchy inspector: tree, properties, live mirror overlay, accessibility checks,
 * merged/unmerged capture, and structural diff. Bounds overlays refresh on explicit Recapture.
 */
@Composable
internal fun InspectorScreen(
    services: AndyServices,
    serial: String?,
    device: AndroidDevice?,
    treePaneWidth: Float,
    onTreePaneWidthChange: (Float) -> Unit,
    state: InspectorState = remember { InspectorState() },
) {
    val scope = rememberCoroutineScope()
    var localTreePaneWidth by remember(treePaneWidth) { mutableStateOf(treePaneWidth.coerceIn(420f, 1400f)) }
    var mirrorStatus by remember { mutableStateOf("Disconnected") }
    var mirrorSession by remember { mutableStateOf<MirrorSession?>(null) }
    var connectResult by remember { mutableStateOf("") }
    val sendMirrorInput = rememberMirrorInputSender(services, serial, enabled = !state.interactionMode)

    val searchedRoot = remember(state.snapshot, state.searchQuery) {
        state.snapshot?.root?.filterBySearch(state.searchQuery)
    }
    val flattenedNodes = remember(searchedRoot, state.collapsedNodes.toMap(), state.interestingOnly) {
        val rows = searchedRoot?.flattenHierarchyTree(state.collapsedNodes).orEmpty()
        if (state.interestingOnly) rows.filter { it.node.isInterestingHierarchyNode() } else rows
    }
    val treeListState = rememberLazyListState()
    val diffEntries = remember(state.baseline, state.snapshot, state.showDiff) {
        if (state.showDiff) diffHierarchyTrees(state.baseline?.root, state.snapshot?.root) else emptyList()
    }
    val hierarchyDisplaySize = remember(state.snapshot, device?.screenSize) {
        state.snapshot?.let { snapshot ->
            resolveHierarchyDisplaySize(
                rootBounds = snapshot.root.bounds,
                wmWidth = snapshot.displayWidth,
                wmHeight = snapshot.displayHeight,
                deviceScreenSize = device?.screenSize,
            )
        }
    }
    val highlightBounds = remember(
        state.hoveredBounds,
        state.selectedNode,
        state.snapshot?.root,
        state.snapshot?.capturedAtMillis,
    ) {
        resolveHighlightBounds(
            bounds = state.hoveredBounds ?: state.selectedNode?.bounds,
            root = state.snapshot?.root,
            node = state.selectedNode,
        )
    }
    var refreshInFlight by remember { mutableStateOf(false) }

    fun applySnapshot(snapshot: HierarchySnapshot, previousSelection: AccessibilityNode?) {
        state.snapshot = snapshot
        val nextSelected = previousSelection?.let { snapshot.root.findEquivalentNode(it) } ?: snapshot.root
        state.selectedNode = nextSelected
        state.hoveredBounds = nextSelected.bounds
        state.status = buildString {
            append("Hierarchy loaded · ${snapshot.root.countNodes()} nodes · ${snapshot.source.name.lowercase()}")
            append(" · recapture after scrolling to refresh bounds")
        }
    }

    suspend fun captureHierarchy(): Result<HierarchySnapshot> {
        if (serial == null) return Result.failure(IllegalStateException("No device selected"))
        val options = HierarchyOptions(
            includeInvisible = state.includeInvisible,
            unmergedSemantics = state.unmergedSemantics,
        )
        val primary = services.viewHierarchy.capture(serial, options)
        if (primary.isSuccess) return primary
        val fallbackRoot = services.accessibility.dump(serial)
            ?: return primary
        val screenSizeParts = device?.screenSize?.split('x')?.mapNotNull { it.trim().toIntOrNull() }
        return Result.success(
            HierarchySnapshot(
                root = fallbackRoot,
                capturedAtMillis = System.currentTimeMillis(),
                displayWidth = screenSizeParts?.getOrNull(0) ?: 0,
                displayHeight = screenSizeParts?.getOrNull(1) ?: 0,
                source = HierarchySource.Uiautomator,
            ),
        )
    }

    suspend fun refreshHierarchy() {
        if (serial == null || refreshInFlight) return
        refreshInFlight = true
        val previousSelection = state.selectedNode
        try {
            state.isLoading = true
            state.status = "Capturing hierarchy..."
            captureHierarchy().fold(
                onSuccess = { snapshot -> applySnapshot(snapshot, previousSelection) },
                onFailure = { error -> state.status = error.message ?: "Capture failed" },
            )
        } finally {
            refreshInFlight = false
            state.isLoading = false
            state.isInitialCaptureDone = true
        }
    }

    fun capture() {
        if (serial == null) return
        scope.launch { refreshHierarchy() }
    }

    LaunchedEffect(serial) {
        if (serial != state.lastSerial) {
            state.snapshot = null
            state.status = "No capture loaded"
            state.hoveredBounds = null
            state.selectedNode = null
            state.interactionMode = false
            state.isInitialCaptureDone = false
            state.isLoading = false
            state.lastSerial = serial
            state.collapsedNodes.clear()
            state.baseline = null
            state.showDiff = false
            state.interestingOnly = false
            state.layoutBounds = false
        }
        if (serial != null) {
            val result = services.devices.shell(serial, listOf("getprop", "debug.layout"))
            if (result.isSuccess) {
                state.layoutBounds = result.stdout.trim() == "true"
            }
        }
    }

    LaunchedEffect(serial, state.isInitialCaptureDone) {
        if (serial != null && !state.isInitialCaptureDone && !state.isLoading) capture()
    }

    LaunchedEffect(state.selectedNode?.id, flattenedNodes.size) {
        val selectedId = state.selectedNode?.id ?: return@LaunchedEffect
        val index = flattenedNodes.indexOfFirst { it.node.id == selectedId }
        if (index >= 0) treeListState.animateScrollToItem(index)
    }

    LaunchedEffect(Unit) { services.mirror.status.collectLatest { mirrorStatus = it } }
    LaunchedEffect(services.mirror, serial) {
        services.mirror.session.collectLatest { session -> mirrorSession = session?.takeIf { it.serial == serial } }
    }
    LaunchedEffect(serial) {
        if (serial != null) {
            val result = services.mirror.connect(serial, LiveMirrorSettings.config.value)
            connectResult = if (result.isSuccess) result.stdout else result.stderr
        }
    }

    val contextualActions = rememberContextualAiActionState()
    val explainAvailable = contextualAiActionsEnabled(services)

    /** Attaches the newest saved hierarchy snapshot when one exists; otherwise prompt-only. */
    fun explainSelectedNode() {
        val node = state.selectedNode ?: return
        scope.launch {
            val location = findLatestInvestigationEvent(services.bugs, InvestigationEventKind.HierarchySnapshot)
            contextualActions.open(
                explainNodeRequest(
                    nodeId = node.id,
                    className = node.className,
                    resourceId = node.resourceId,
                    text = node.text,
                    contentDescription = node.contentDescription,
                    bounds = node.bounds,
                    packageName = node.packageName,
                    investigationId = location?.investigationId,
                    eventId = location?.eventId,
                    atMillis = location?.atMillis,
                ),
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Toolbar(
            "Inspector",
            state.status,
            onPrimary = { capture() },
            primaryLabel = if (state.snapshot != null) "Recapture" else "Capture",
        )
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
            FilterPill("Include invisible", state.includeInvisible, Yellow) {
                state.includeInvisible = !state.includeInvisible
                capture()
            }
            FilterPill("Unmerged view tree", state.unmergedSemantics, Green) {
                state.unmergedSemantics = !state.unmergedSemantics
                capture()
            }
            FilterPill("Diff vs. snapshot", state.showDiff, Rust, enabled = state.baseline != null) { state.showDiff = !state.showDiff }
            FilterPill("Set snapshot baseline", state.baseline != null, Green) { state.baseline = state.snapshot }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = state.searchQuery,
                onValueChange = { state.searchQuery = it },
                placeholder = { Text("Filter by text, id, or class", color = TextSecondary) },
                singleLine = true,
                modifier = Modifier.widthIn(max = 380.dp).defaultMinSize(minHeight = AndyLayout.FieldHeight),
                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace),
                colors = fieldColors(),
            )
            if (explainAvailable) {
                Spacer(Modifier.width(8.dp))
                ExplainActionButton("Explain node…", enabled = state.selectedNode != null) { explainSelectedNode() }
            }
        }
        Row(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.width(localTreePaneWidth.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().weight(1f).background(PanelSoft, RoundedCornerShape(8.dp)).padding(10.dp)) {
                    if (state.isLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Rust)
                        }
                    } else if (flattenedNodes.isNotEmpty()) {
                        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                            LazyColumn(state = treeListState, modifier = Modifier.widthIn(min = 980.dp).fillMaxHeight()) {
                                itemsIndexed(flattenedNodes, key = { _, row -> row.node.id }) { _, row ->
                                    HierarchyNodeRow(
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
                                        },
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            if (state.searchQuery.isNotBlank()) "No nodes match \"${state.searchQuery}\"." else "Capture a hierarchy to inspect nodes.",
                            color = TextSecondary,
                        )
                    }
                }
                PanelCard(Modifier.fillMaxWidth().height(320.dp)) {
                    if (state.showDiff) {
                        InspectorDiffPanel(diffEntries)
                    } else {
                        HierarchyDetailsPanel(state.selectedNode)
                    }
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
                    mirrorSession = mirrorSession,
                    connectResult = connectResult,
                    modifier = Modifier.fillMaxSize().padding(start = 6.dp),
                    highlightBounds = highlightBounds,
                    boundsDisplayWidth = hierarchyDisplaySize?.first,
                    boundsDisplayHeight = hierarchyDisplaySize?.second,
                    showHardwareControls = false,
                    showPopOut = false,
                    passThroughInput = !state.interactionMode,
                        onDevicePointClick = { x, y ->
                            searchedRoot?.findBestNodeAt(x, y)?.let {
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
    ContextualAiActionHost(services, contextualActions)
    }
}

@Composable
private fun InspectorDiffPanel(entries: List<HierarchyDiffEntry>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        DetailSection("Structural diff vs. snapshot")
        if (entries.isEmpty()) {
            Text("No structural differences from the pinned snapshot.", color = TextSecondary, fontSize = 12.sp)
            return@Column
        }
        entries.forEach { entry ->
            val (label, color) = when (entry.kind) {
                HierarchyDiffKind.Added -> "+ ADDED" to Green
                HierarchyDiffKind.Removed -> "- REMOVED" to Red
                HierarchyDiffKind.Changed -> "~ CHANGED" to Yellow
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                    Text(label, color = androidx.compose.ui.graphics.Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    entry.node.className?.substringAfterLast('.') ?: entry.path,
                    color = TextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            entry.changes.forEach { change -> DetailRow("·", change) }
        }
    }
}
