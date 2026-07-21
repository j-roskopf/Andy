package app.andy.ui.actions

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import app.andy.ui.components.EmptyState
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PanelCard
import app.andy.ui.live.DeviceLivePanel
import app.andy.ui.shell.LocalSuppressHeavyweightSurfaces
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

/** Where an auxiliary surface docks relative to the main workspace. */
internal enum class DockPlacement { Right, Bottom }

/** What an auxiliary dock shows. Terminal and Live are each exclusive across placements. */
internal enum class DockKind { Terminal, Live }

/**
 * Independent right/bottom docks. Opening a kind in one placement moves it from the other
 * (a terminal widget and the mirror session can each live in only one place).
 */
internal data class AuxDocks(
    val right: DockKind? = null,
    val bottom: DockKind? = null,
) {
    operator fun get(placement: DockPlacement): DockKind? = when (placement) {
        DockPlacement.Right -> right
        DockPlacement.Bottom -> bottom
    }

    fun placementOf(kind: DockKind): DockPlacement? = when {
        right == kind -> DockPlacement.Right
        bottom == kind -> DockPlacement.Bottom
        else -> null
    }

    fun toggle(placement: DockPlacement, kind: DockKind): AuxDocks {
        if (this[placement] == kind) return clear(placement)
        return show(placement, kind)
    }

    fun show(placement: DockPlacement, kind: DockKind): AuxDocks {
        // Live is a tall phone surface — keep it on the right, not the bottom strip.
        val resolvedPlacement =
            if (kind == DockKind.Live && placement == DockPlacement.Bottom) DockPlacement.Right
            else placement
        val cleared = clearKind(kind)
        return when (resolvedPlacement) {
            DockPlacement.Right -> cleared.copy(right = kind)
            DockPlacement.Bottom -> cleared.copy(bottom = kind)
        }
    }

    fun clear(placement: DockPlacement): AuxDocks = when (placement) {
        DockPlacement.Right -> copy(right = null)
        DockPlacement.Bottom -> copy(bottom = null)
    }

    fun clearKind(kind: DockKind): AuxDocks = copy(
        right = right.takeUnless { it == kind },
        bottom = bottom.takeUnless { it == kind },
    )
}

@Composable
internal fun DockToggleRow(
    docks: AuxDocks,
    onToggle: (DockPlacement, DockKind) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        DockToggle(
            placement = DockPlacement.Bottom,
            kind = DockKind.Terminal,
            selected = docks.bottom == DockKind.Terminal,
            onClick = { onToggle(DockPlacement.Bottom, DockKind.Terminal) },
        )
        DockToggle(
            placement = DockPlacement.Right,
            kind = DockKind.Terminal,
            selected = docks.right == DockKind.Terminal,
            onClick = { onToggle(DockPlacement.Right, DockKind.Terminal) },
        )
        DockToggle(
            placement = DockPlacement.Right,
            kind = DockKind.Live,
            selected = docks.right == DockKind.Live,
            onClick = { onToggle(DockPlacement.Right, DockKind.Live) },
        )
    }
}

@Composable
internal fun TerminalDockToggleRow(
    terminalPlacement: DockPlacement?,
    onToggle: (DockPlacement) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        DockToggle(
            placement = DockPlacement.Bottom,
            kind = DockKind.Terminal,
            selected = terminalPlacement == DockPlacement.Bottom,
            onClick = { onToggle(DockPlacement.Bottom) },
        )
        DockToggle(
            placement = DockPlacement.Right,
            kind = DockKind.Terminal,
            selected = terminalPlacement == DockPlacement.Right,
            onClick = { onToggle(DockPlacement.Right) },
        )
    }
}

@Composable
internal fun DockToggle(
    placement: DockPlacement,
    kind: DockKind,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val label = buildString {
        append(if (selected) "Hide" else "Show")
        append(' ')
        append(if (kind == DockKind.Terminal) "terminal" else "live view")
        append(if (placement == DockPlacement.Right) " on right" else " at bottom")
    }
    val accent = if (kind == DockKind.Terminal) Rust else Cyan
    val selectedFill = if (kind == DockKind.Terminal) AndyColors.OrangeSubtle else Cyan.copy(alpha = 0.16f)
    val selectedBorder = if (kind == DockKind.Terminal) AndyColors.OrangeBorder else Cyan.copy(alpha = 0.55f)
    Box(
        Modifier
            .size(28.dp)
            .background(if (selected) selectedFill else AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R2))
            .border(1.dp, if (selected) selectedBorder else Border, RoundedCornerShape(AndyRadius.R2))
            .semantics { contentDescription = label; role = Role.Button }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(15.dp)) {
            val stroke = Stroke(width = 1.4.dp.toPx())
            val color = if (selected) accent else TextSecondary
            drawRect(color = color, style = stroke)
            val split = if (placement == DockPlacement.Right) {
                Offset(size.width * 0.32f, 0f) to Offset(size.width * 0.32f, size.height)
            } else {
                Offset(0f, size.height * 0.68f) to Offset(size.width, size.height * 0.68f)
            }
            drawLine(color, start = split.first, end = split.second, strokeWidth = stroke.width)
            if (kind == DockKind.Live) {
                val phone = if (placement == DockPlacement.Right) {
                    Size(size.width * 0.42f, size.height * 0.62f) to Offset(size.width * 0.48f, size.height * 0.19f)
                } else {
                    Size(size.width * 0.34f, size.height * 0.42f) to Offset(size.width * 0.33f, size.height * 0.12f)
                }
                drawRoundRect(
                    color = color,
                    topLeft = phone.second,
                    size = phone.first,
                    cornerRadius = CornerRadius(1.2.dp.toPx(), 1.2.dp.toPx()),
                    style = stroke,
                )
            }
        }
    }
}

@Composable
internal fun TerminalDockDrawer(
    services: AndyServices,
    terminalTabs: List<RunningAction>,
    activeRunId: String?,
    placement: DockPlacement,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        accent = Rust,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Terminal", color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold)
                Text(
                    if (activeRunId == null) "Run an action to start a shell" else "Interactive project shell",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                )
            }
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
        if (terminalTabs.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                terminalTabs.forEach { terminal ->
                    TerminalTabPill(
                        text = terminal.actionName,
                        selected = terminal.runId == activeRunId,
                        color = dockActionStatusColor(terminal.status),
                        icon = actionIconMarker(terminal.icon),
                        onClick = { onSelectTab(terminal.runId) },
                        onClose = { onCloseTab(terminal.runId) },
                    )
                }
            }
        }
        if (activeRunId == null) {
            EmptyState("Run an action to open its terminal")
        } else {
            // Right-docked SwingPanel can cover chrome menus; bottom placement cannot.
            val suppressForChromeMenus =
                LocalSuppressHeavyweightSurfaces.current && placement == DockPlacement.Right
            CompositionLocalProvider(LocalSuppressHeavyweightSurfaces provides suppressForChromeMenus) {
                ProjectTerminalSurface(services, activeRunId, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
internal fun LiveDockDrawer(
    services: AndyServices,
    serial: String?,
    device: AndroidDevice?,
    placement: DockPlacement,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        accent = Cyan,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Live", color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold)
                Text(
                    device?.displayName ?: serial ?: "Select a device in the toolbar",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                )
            }
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
        DeviceLivePanel(
            services = services,
            serial = serial,
            device = device,
            modifier = Modifier.fillMaxSize(),
            showChromeControls = false,
            showDeviceHeader = false,
        )
    }
}

@Composable
private fun TerminalTabPill(
    text: String,
    selected: Boolean,
    color: Color,
    icon: String,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val shape = RoundedCornerShape(AndyRadius.R2)
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
        Text(icon, color = if (selected) AndyColors.Neutral100 else AndyColors.Neutral300, fontFamily = MonoFont, fontSize = 10.sp, lineHeight = 14.sp)
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

private fun dockActionStatusColor(status: ActionRunStatus): Color = when (status) {
    ActionRunStatus.Running -> app.andy.ui.theme.Green
    ActionRunStatus.Exited -> Cyan
    ActionRunStatus.Failed -> Red
    ActionRunStatus.Stopped -> Rust
}
