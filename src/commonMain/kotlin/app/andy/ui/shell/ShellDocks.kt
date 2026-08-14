package app.andy.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import app.andy.model.ActionRunStatus
import app.andy.model.AndroidDevice
import app.andy.model.RunningAction
import app.andy.model.TerminalThemePreset
import app.andy.model.palette
import app.andy.service.AndyServices
import app.andy.service.AppService
import app.andy.service.LogcatService
import app.andy.ui.actions.ProjectTerminalSurface
import app.andy.ui.actions.actionIconMarker
import app.andy.ui.components.EmptyState
import app.andy.ui.components.PanelCard
import app.andy.ui.components.TabBarItem
import app.andy.ui.components.TabBarRow
import app.andy.ui.live.DeviceLivePanel
import app.andy.ui.logcat.LogcatPanel
import app.andy.ui.logcat.LogcatState
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

/** Where an auxiliary surface docks relative to the main workspace. */
internal enum class DockPlacement { Right, Bottom }
private enum class PaneToggleEdge { Left, Right, Bottom }

/** Kind of surface shown inside a dock tab. */
internal enum class DockTabKind { Live, Terminal, Logs }

/** One tab inside a right/bottom dock pane. */
internal data class DockTab(
    val id: String,
    val kind: DockTabKind,
    val runId: String? = null,
    val title: String? = null,
) {
    companion object {
        fun live(title: String? = null): DockTab =
            DockTab(id = "live", kind = DockTabKind.Live, title = title)
        fun logs(): DockTab = DockTab(id = "logs", kind = DockTabKind.Logs)
        fun terminal(runId: String, title: String? = null): DockTab =
            DockTab(id = "terminal:$runId", kind = DockTabKind.Terminal, runId = runId, title = title)
    }
}

/** Independent right or bottom dock with a tab strip. */
internal data class DockPane(
    val tabs: List<DockTab> = emptyList(),
    val activeTabId: String? = null,
    val visible: Boolean = false,
) {
    val activeTab: DockTab?
        get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.lastOrNull()

    fun withTab(tab: DockTab): DockPane {
        val existing = when (tab.kind) {
            DockTabKind.Live, DockTabKind.Logs -> tabs.firstOrNull { it.kind == tab.kind }
            DockTabKind.Terminal -> tabs.firstOrNull { it.runId == tab.runId }
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

    fun hide(): DockPane = copy(visible = false)

    fun withoutTerminalRuns(aliveRunIds: Set<String>): DockPane {
        val remaining = tabs.filter { tab ->
            tab.kind != DockTabKind.Terminal || tab.runId in aliveRunIds
        }
        if (remaining.size == tabs.size) return this
        if (remaining.isEmpty()) return DockPane()
        val nextActive = when {
            remaining.any { it.id == activeTabId } -> activeTabId
            else -> remaining.last().id
        }
        return copy(tabs = remaining, activeTabId = nextActive)
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
}

/**
 * Global shell docks. Placement icons toggle visibility; a landing menu picks Live / Terminal.
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

    fun update(placement: DockPlacement, transform: (DockPane) -> DockPane): ShellDocks {
        val next = transform(pane(placement))
        return when (placement) {
            DockPlacement.Right -> copy(right = next, landingFor = null)
            DockPlacement.Bottom -> copy(bottom = next, landingFor = null)
        }
    }

    /** Live is a single mirror session — keep at most one Live tab across both panes. */
    fun withLiveExclusive(placement: DockPlacement): ShellDocks {
        val existingTitle = tabTitle(DockTabKind.Live)
        val clearedOther = when (placement) {
            DockPlacement.Right -> copy(bottom = bottom.withoutKind(DockTabKind.Live))
            DockPlacement.Bottom -> copy(right = right.withoutKind(DockTabKind.Live))
        }
        return clearedOther.update(placement) { it.withTab(DockTab.live(title = existingTitle)) }
    }

    fun withTerminalExclusive(placement: DockPlacement, runId: String): ShellDocks {
        val existingTitle = right.tabs.firstOrNull { it.runId == runId }?.title
            ?: bottom.tabs.firstOrNull { it.runId == runId }?.title
        val tab = DockTab.terminal(runId, title = existingTitle)
        val withoutElsewhere = when (placement) {
            DockPlacement.Right -> copy(bottom = bottom.closeTab(tab.id).let { if (it.tabs.isEmpty()) DockPane(visible = false) else it })
            DockPlacement.Bottom -> copy(right = right.closeTab(tab.id).let { if (it.tabs.isEmpty()) DockPane(visible = false) else it })
        }
        // closeTab on empty-other keeps visibility quirks; normalize empty panes
        val normalized = withoutElsewhere.copy(
            right = withoutElsewhere.right.let { if (it.tabs.isEmpty()) DockPane() else it },
            bottom = withoutElsewhere.bottom.let { if (it.tabs.isEmpty()) DockPane() else it },
        )
        return normalized.update(placement) { it.withTab(tab) }
    }

    private fun tabTitle(kind: DockTabKind): String? =
        right.tabs.firstOrNull { it.kind == kind }?.title
            ?: bottom.tabs.firstOrNull { it.kind == kind }?.title
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
    Box(
        Modifier
            .size(28.dp)
            .background(
                if (selected) AndyColors.Neutral800 else AndyColors.Neutral850,
                RoundedCornerShape(AndyRadius.Control),
            )
            .border(
                1.dp,
                if (selected) TextSecondary.copy(alpha = 0.55f) else Border,
                RoundedCornerShape(AndyRadius.Control),
            )
            .semantics { contentDescription = label; role = Role.Button }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(15.dp)) {
            val stroke = Stroke(width = 1.35.dp.toPx())
            val color = if (selected) TextPrimary else TextSecondary
            val inset = 0.5.dp.toPx()
            drawRoundRect(
                color = color,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - inset * 2, this.size.height - inset * 2),
                cornerRadius = CornerRadius(2.2.dp.toPx(), 2.2.dp.toPx()),
                style = stroke,
            )
            val indicatorStroke = 1.6.dp.toPx()
            when (edge) {
                PaneToggleEdge.Left, PaneToggleEdge.Right -> {
                    val x = this.size.width * if (edge == PaneToggleEdge.Left) 0.28f else 0.72f
                    val top = this.size.height * 0.28f
                    val bottom = this.size.height * 0.72f
                    drawLine(color, Offset(x, top), Offset(x, bottom), strokeWidth = indicatorStroke)
                }
                PaneToggleEdge.Bottom -> {
                    val y = this.size.height * 0.72f
                    val left = this.size.width * 0.28f
                    val right = this.size.width * 0.72f
                    drawLine(color, Offset(left, y), Offset(right, y), strokeWidth = indicatorStroke)
                }
            }
        }
    }
}

@Composable
internal fun DockLandingMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSelect: (DockTabKind) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .width(220.dp)
            .background(AndyColors.Neutral900, RoundedCornerShape(AndyRadius.Control)),
    ) {
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
    }
}

@Composable
private fun DockLandingItem(
    kind: DockTabKind,
    label: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DockKindIcon(kind = kind, modifier = Modifier.size(16.dp))
                Text(label, color = TextPrimary, fontFamily = DisplayFont, fontSize = 13.sp)
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun DockKindIcon(kind: DockTabKind, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = 1.3.dp.toPx())
        val color = TextSecondary
        when (kind) {
            DockTabKind.Live -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.22f, size.height * 0.08f),
                    size = Size(size.width * 0.56f, size.height * 0.84f),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                    style = stroke,
                )
                drawLine(
                    color,
                    Offset(size.width * 0.38f, size.height * 0.18f),
                    Offset(size.width * 0.62f, size.height * 0.18f),
                    strokeWidth = stroke.width,
                )
            }
            DockTabKind.Terminal -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.08f, size.height * 0.14f),
                    size = Size(size.width * 0.84f, size.height * 0.72f),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                    style = stroke,
                )
                val prompt = Path().apply {
                    moveTo(size.width * 0.24f, size.height * 0.38f)
                    lineTo(size.width * 0.36f, size.height * 0.50f)
                    lineTo(size.width * 0.24f, size.height * 0.62f)
                }
                drawPath(prompt, color = color, style = stroke)
                drawLine(
                    color,
                    Offset(size.width * 0.42f, size.height * 0.62f),
                    Offset(size.width * 0.68f, size.height * 0.62f),
                    strokeWidth = stroke.width,
                )
            }
            DockTabKind.Logs -> {
                val left = size.width * 0.18f
                val right = size.width * 0.82f
                listOf(0.28f, 0.50f, 0.72f).forEach { yFrac ->
                    drawLine(
                        color,
                        Offset(left, size.height * yFrac),
                        Offset(right, size.height * yFrac),
                        strokeWidth = stroke.width,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ShellDockDrawer(
    services: AndyServices,
    pane: DockPane,
    placement: DockPlacement,
    running: List<RunningAction>,
    serial: String?,
    device: AndroidDevice?,
    targetDisplayName: String?,
    liveActive: Boolean,
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
    modifier: Modifier = Modifier,
    terminalThemeId: String = TerminalThemePreset.Default.id,
) {
    val active = pane.activeTab
    var addMenuExpanded by remember { mutableStateOf(false) }
    val addMenuExpandedState = rememberUpdatedState(addMenuExpanded)
    fun dismissAddMenu() {
        if (!addMenuExpanded) return
        addMenuExpanded = false
        HeavyweightOverlayRegistry.pop()
    }
    DisposableEffect(Unit) {
        onDispose {
            if (addMenuExpandedState.value) {
                HeavyweightOverlayRegistry.pop()
            }
        }
    }
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
    // Pad the tab strip only. Content is flush to the card bottom so the right/bottom
    // dock shares a baseline with the project composer (PanelCard's default 20dp bottom
    // padding was leaving a visible shelf under Live).
    PanelCard(
        modifier.graphicsLayer {
            alpha = 0.72f + reveal.value * 0.28f
            if (placement == DockPlacement.Right) translationX = (1f - reveal.value) * 28f
            else translationY = (1f - reveal.value) * 28f
        },
        background = if (terminalHeader) terminalBackground else AndyColors.SurfaceRaised,
        borderColor = Color.Transparent,
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        TabBarRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = AndySpace.Space5, top = AndySpace.Space4, end = AndySpace.Space5),
            scrollTabs = true,
            // Terminal (and Live) bleed into the card; a hairline under the tabs reintroduces
            // the chrome/content seam the matching header background was meant to erase.
            showDivider = false,
            trailing = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        DockChromeButton(
                            glyph = "+",
                            label = "Add pane tab",
                            onTerminalSurface = terminalHeader,
                            onClick = {
                                if (!addMenuExpanded) {
                                    HeavyweightOverlayRegistry.push()
                                }
                                addMenuExpanded = true
                            },
                        )
                        DockLandingMenu(
                            expanded = addMenuExpanded,
                            onDismiss = ::dismissAddMenu,
                            onSelect = { kind ->
                                dismissAddMenu()
                                onOpenKind(kind, true)
                            },
                        )
                    }
                    DockChromeButton(
                        glyph = "×",
                        label = if (placement == DockPlacement.Right) "Close right pane" else "Close bottom pane",
                        onTerminalSurface = terminalHeader,
                        onClick = onClose,
                    )
                }
            },
        ) {
            val terminalTabs = pane.tabs.filter { it.kind == DockTabKind.Terminal }
            pane.tabs.forEach { tab ->
                val runningAction = tab.runId?.let { id -> running.firstOrNull { it.runId == id } }
                val accent = when (tab.kind) {
                    DockTabKind.Live -> Cyan
                    DockTabKind.Logs -> Green
                    DockTabKind.Terminal -> dockActionStatusColor(runningAction?.status)
                }
                val selected = tab.id == active?.id
                TabBarItem(
                    label = tab.title ?: when (tab.kind) {
                        DockTabKind.Live -> "Live"
                        DockTabKind.Logs -> "Logs"
                        DockTabKind.Terminal -> dockTerminalTabLabel(tab, terminalTabs, runningAction)
                    },
                    selected = selected,
                    onClick = { onSelectTab(tab.id) },
                    onRename = { title -> onRenameTab(tab.id, title) },
                    modifier = Modifier.pointerInput(tab.id) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Press && event.buttons.isTertiaryPressed) {
                                    onCloseTab(tab.id)
                                }
                            }
                        }
                    },
                    indicatorColor = accent,
                    leading = {
                        Text(
                            when (tab.kind) {
                                DockTabKind.Live -> "▣"
                                DockTabKind.Logs -> "≡"
                                DockTabKind.Terminal -> actionIconMarker(runningAction?.icon.orEmpty())
                            },
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
                                .clickable(onClick = { onCloseTab(tab.id) }),
                        )
                    },
                )
            }
        }
        // Live and Terminal panels fill their own background/theme, so bleed them to the
        // card edges rather than boxing them in — that's what makes them feel like a real
        // terminal/device surface instead of a widget floating inside a card.
        val edgeToEdge = active?.kind == DockTabKind.Live || active?.kind == DockTabKind.Terminal
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(
                    start = if (edgeToEdge) 0.dp else AndySpace.Space5,
                    end = if (edgeToEdge) 0.dp else AndySpace.Space5,
                    top = if (edgeToEdge) 0.dp else AndySpace.Space4,
                ),
        ) {
            when (active?.kind) {
                DockTabKind.Terminal -> {
                    val runId = active.runId
                    if (runId == null) {
                        EmptyState("Run an action to open its terminal")
                    } else {
                        ProjectTerminalSurface(services, runId, Modifier.fillMaxSize())
                    }
                }
                DockTabKind.Live -> {
                    if (liveActive) {
                        DeviceLivePanel(
                            services = services,
                            serial = serial,
                            device = device,
                            displayName = device?.displayName ?: targetDisplayName,
                            modifier = Modifier.fillMaxSize(),
                            showChromeControls = false,
                            showDeviceHeader = false,
                            showPopOut = false,
                            // Dock drawer already has PanelCard chrome; skip the nested pane surface.
                            showContainerChrome = false,
                            deviceBorderWidth = 0.dp,
                            deviceCornerRadius = 0.dp,
                        )
                    } else {
                        EmptyState("Live view pauses while another tab is open")
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
                null -> EmptyState("Choose Live or Terminal")
            }
        }
    }
}

@Composable
private fun DockChromeButton(
    glyph: String,
    label: String,
    onClick: () -> Unit,
    onTerminalSurface: Boolean = false,
) {
    // Over a terminal-tinted header the neutral chrome fill would read as a patch of a
    // different theme, so lift the button off the terminal color instead of replacing it.
    val fill = if (onTerminalSurface) Color.White.copy(alpha = 0.06f) else AndyColors.Neutral850
    val stroke = if (onTerminalSurface) Color.White.copy(alpha = 0.12f) else Border
    Box(
        Modifier
            .size(28.dp)
            .background(fill, RoundedCornerShape(AndyRadius.Control))
            .border(1.dp, stroke, RoundedCornerShape(AndyRadius.Control))
            .semantics { contentDescription = label; role = Role.Button }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = TextSecondary,
            fontFamily = MonoFont,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun dockTerminalTabLabel(
    tab: DockTab,
    terminalTabs: List<DockTab>,
    runningAction: RunningAction?,
): String {
    val base = runningAction?.actionName ?: "Terminal"
    if (terminalTabs.size <= 1 || runningAction?.actionId != "terminal") return base
    val index = terminalTabs.indexOfFirst { it.id == tab.id }
    return if (index < 0) base else "$base ${index + 1}"
}

private fun dockActionStatusColor(status: ActionRunStatus?): Color = when (status) {
    ActionRunStatus.Running -> Green
    ActionRunStatus.Exited -> Cyan
    ActionRunStatus.Failed -> Red
    ActionRunStatus.Stopped -> Rust
    null -> Rust
}
