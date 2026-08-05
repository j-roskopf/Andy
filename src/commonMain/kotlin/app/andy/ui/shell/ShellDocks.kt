package app.andy.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import app.andy.service.AndyServices
import app.andy.service.AppService
import app.andy.service.LogcatService
import app.andy.ui.actions.ProjectTerminalSurface
import app.andy.ui.actions.actionIconMarker
import app.andy.ui.components.EmptyState
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PanelCard
import app.andy.ui.live.DeviceLivePanel
import app.andy.ui.logcat.LogcatPanel
import app.andy.ui.logcat.LogcatState
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
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

/** Kind of surface shown inside a dock tab. */
internal enum class DockTabKind { Live, Terminal, Logs }

/** One tab inside a right/bottom dock pane. */
internal data class DockTab(
    val id: String,
    val kind: DockTabKind,
    val runId: String? = null,
) {
    companion object {
        fun live(): DockTab = DockTab(id = "live", kind = DockTabKind.Live)
        fun logs(): DockTab = DockTab(id = "logs", kind = DockTabKind.Logs)
        fun terminal(runId: String): DockTab =
            DockTab(id = "terminal:$runId", kind = DockTabKind.Terminal, runId = runId)
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
        val clearedOther = when (placement) {
            DockPlacement.Right -> copy(bottom = bottom.withoutKind(DockTabKind.Live))
            DockPlacement.Bottom -> copy(right = right.withoutKind(DockTabKind.Live))
        }
        return clearedOther.update(placement) { it.withTab(DockTab.live()) }
    }

    fun withTerminalExclusive(placement: DockPlacement, runId: String): ShellDocks {
        val tab = DockTab.terminal(runId)
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
            if (placement == DockPlacement.Right) {
                val x = this.size.width * 0.72f
                val top = this.size.height * 0.28f
                val bottom = this.size.height * 0.72f
                drawLine(color, Offset(x, top), Offset(x, bottom), strokeWidth = indicatorStroke)
            } else {
                val y = this.size.height * 0.72f
                val left = this.size.width * 0.28f
                val right = this.size.width * 0.72f
                drawLine(color, Offset(left, y), Offset(right, y), strokeWidth = indicatorStroke)
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
    onOpenKind: (DockTabKind, newTerminal: Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
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
    PanelCard(
        modifier.graphicsLayer {
            alpha = 0.72f + reveal.value * 0.28f
            if (placement == DockPlacement.Right) translationX = (1f - reveal.value) * 28f
            else translationY = (1f - reveal.value) * 28f
        },
        borderColor = Color.Transparent,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (active?.kind) {
                        DockTabKind.Terminal -> "Terminal"
                        DockTabKind.Live -> "Live"
                        DockTabKind.Logs -> "Logs"
                        null -> "Pane"
                    },
                    color = TextPrimary,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when (active?.kind) {
                        DockTabKind.Terminal ->
                            if (active.runId == null) "Run an action to start a shell"
                            else "Interactive project shell"
                        DockTabKind.Live ->
                            device?.displayName ?: targetDisplayName ?: serial ?: "Select a device in the toolbar"
                        DockTabKind.Logs ->
                            serial ?: "Select a device in the toolbar"
                        null -> "Choose Live or Terminal"
                    },
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                )
            }
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val terminalTabs = pane.tabs.filter { it.kind == DockTabKind.Terminal }
            pane.tabs.forEach { tab ->
                val runningAction = tab.runId?.let { id -> running.firstOrNull { it.runId == id } }
                DockTabPill(
                    text = when (tab.kind) {
                        DockTabKind.Live -> "live"
                        DockTabKind.Logs -> "logs"
                        DockTabKind.Terminal -> dockTerminalTabLabel(tab, terminalTabs, runningAction)
                    },
                    selected = tab.id == active?.id,
                    color = when (tab.kind) {
                        DockTabKind.Live -> Cyan
                        DockTabKind.Logs -> Green
                        DockTabKind.Terminal -> dockActionStatusColor(runningAction?.status)
                    },
                    icon = when (tab.kind) {
                        DockTabKind.Live -> "▣"
                        DockTabKind.Logs -> "≡"
                        DockTabKind.Terminal -> actionIconMarker(runningAction?.icon.orEmpty())
                    },
                    onClick = { onSelectTab(tab.id) },
                    onClose = { onCloseTab(tab.id) },
                )
            }
            Box {
                Box(
                    Modifier
                        .size(28.dp)
                        .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.Control))
                        .border(1.dp, Border, RoundedCornerShape(AndyRadius.Control))
                        .semantics { contentDescription = "Add pane tab"; role = Role.Button }
                        .clickable(onClick = {
                            if (!addMenuExpanded) {
                                HeavyweightOverlayRegistry.push()
                            }
                            addMenuExpanded = true
                        }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+", color = TextSecondary, fontFamily = MonoFont, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                DockLandingMenu(
                    expanded = addMenuExpanded,
                    onDismiss = ::dismissAddMenu,
                    onSelect = { kind ->
                        dismissAddMenu()
                        onOpenKind(kind, true)
                    },
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
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
private fun DockTabPill(
    text: String,
    selected: Boolean,
    color: Color,
    icon: String,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val shape = RoundedCornerShape(AndyRadius.Control)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .height(28.dp)
            .hoverable(interaction)
            .background(if (selected) color.copy(alpha = 0.26f) else AndyColors.Neutral850, shape)
            .border(1.dp, if (selected) color.copy(alpha = 0.70f) else Border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            icon,
            color = if (selected) AndyColors.Neutral100 else AndyColors.Neutral300,
            fontFamily = MonoFont,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
        Text(
            text.lowercase(),
            color = if (selected) AndyColors.Neutral100 else AndyColors.Neutral300,
            fontFamily = MonoFont,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
        if (hovered) {
            Text(
                "×",
                color = Red,
                fontFamily = MonoFont,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                lineHeight = 14.sp,
                modifier = Modifier
                    .semantics { contentDescription = "Close tab"; role = Role.Button }
                    .clickable(onClick = onClose)
                    .padding(start = 2.dp),
            )
        }
    }
}

private fun dockTerminalTabLabel(
    tab: DockTab,
    terminalTabs: List<DockTab>,
    runningAction: RunningAction?,
): String {
    val base = runningAction?.actionName ?: "terminal"
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
