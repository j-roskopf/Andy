package app.andy.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.service.RemoteScreenAvailability
import app.andy.service.RemoteSessionService
import app.andy.service.RemoteSessionState
import app.andy.service.RemoteSessionStatus
import app.andy.ui.components.AndyCheckbox
import app.andy.ui.components.AndyHorizontalDivider
import app.andy.ui.components.Button
import app.andy.ui.components.CodeFieldTextStyle
import app.andy.ui.components.Lucide
import app.andy.ui.components.LucideIcon
import app.andy.ui.components.StatusDot
import app.andy.ui.components.StatusDotVariant
import app.andy.ui.components.TextButton
import app.andy.ui.components.TextField
import app.andy.ui.components.Tooltip
import app.andy.ui.components.mutedTextButtonColors
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.andyTokens
import kotlinx.coroutines.launch

/** Connection lifecycle collapsed to the four states the panel actually renders. */
internal enum class HostPhase { Local, Switching, Connected, Failed }

/** @param busy true while this panel drives a switch the service has not reported yet. */
internal fun hostPhaseOf(session: RemoteSessionState, busy: Boolean): HostPhase = when {
    busy || session.status == RemoteSessionStatus.Connecting -> HostPhase.Switching
    session.status == RemoteSessionStatus.Connected -> HostPhase.Connected
    // A dropped tunnel lands back on Local carrying the reason — still worth surfacing.
    session.status == RemoteSessionStatus.Error || session.error != null -> HostPhase.Failed
    else -> HostPhase.Local
}

/** Collapsed-header suffix — names the host, never the failure. */
internal fun hostHeaderDetail(session: RemoteSessionState, phase: HostPhase): String = when {
    phase == HostPhase.Switching -> "Switching…"
    session.status == RemoteSessionStatus.Connected -> session.target ?: "Remote"
    session.status == RemoteSessionStatus.Error -> "Not connected"
    else -> "Local"
}

/** Card title — distinguishes a failed connect from a tunnel that dropped under us. */
internal fun hostStatusLabel(session: RemoteSessionState, phase: HostPhase): String = when {
    phase == HostPhase.Connected -> "Connected"
    phase == HostPhase.Switching -> "Connecting"
    session.status == RemoteSessionStatus.Error -> "Connection failed"
    phase == HostPhase.Failed -> "Remote disconnected"
    else -> "Local"
}

private val HostRowHeight = 28.dp
private val HostDotSize = 8.dp
private val HostRowIconBox = 16.dp
private val HostRowIcon = 11.dp

/**
 * Bottom-left host switcher: Local + saved SSH remotes.
 *
 * Layout is three fixed bands so the connected state reads the same as the local one —
 * a status card (only once the session leaves Local), the host list, then the add-host
 * form folded behind a disclosure row.
 */
@Composable
internal fun RemoteSessionSidebarControls(
    remoteSession: RemoteSessionService,
    session: RemoteSessionState,
    expanded: Boolean,
    panelOpen: Boolean,
    onPanelOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var draftTarget by remember { mutableStateOf("") }
    var savePassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val hasSavedTargets = session.savedTargets.isNotEmpty()
    // Nothing saved yet means the form *is* the panel; adding the first host folds it away.
    var addOpen by remember(hasSavedTargets) { mutableStateOf(!hasSavedTargets) }

    fun runSwitch(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { block() }
            busy = false
        }
    }

    val switching = busy || session.status == RemoteSessionStatus.Connecting
    val phase = hostPhaseOf(session, busy)
    val headerDetail = hostHeaderDetail(session, phase)

    if (!expanded) {
        HostRail(phase = phase, target = session.target, modifier = modifier)
        return
    }

    SidebarCollapsiblePanel(
        title = "Host",
        detail = headerDetail,
        detailColor = if (phase == HostPhase.Connected) TextPrimary else TextSecondary,
        detailLeading = { HostDot(phase = phase) },
        expanded = panelOpen,
        onExpandedChange = onPanelOpenChange,
        showTopDivider = true,
        modifier = modifier,
    ) {
        if (phase != HostPhase.Local) {
            ConnectionCard(
                phase = phase,
                session = session,
                busy = busy,
                onReconnect = {
                    runSwitch { remoteSession.reconnect(rememberPassword = savePassword) }
                },
                onOpenScreen = { onResult ->
                    runSwitch {
                        val result = remoteSession.openRemoteScreen()
                        onResult(
                            result.getOrElse { error ->
                                error.message ?: "Could not open remote screen"
                            },
                        )
                    }
                },
            )
        }

        HostRow(
            label = "Local",
            selected = session.status == RemoteSessionStatus.Local ||
                (session.status == RemoteSessionStatus.Error && !session.isRemote),
            enabled = !switching,
            onClick = {
                if (session.isRemote || session.status == RemoteSessionStatus.Connecting) {
                    runSwitch { remoteSession.disconnect() }
                }
            },
        )

        session.savedTargets.forEach { saved ->
            val selected = session.isRemote && session.target == saved
            HostRow(
                label = saved,
                selected = selected,
                enabled = !switching,
                // Removing the host you are sitting on would strand the panel mid-session.
                onRemove = if (selected) {
                    null
                } else {
                    { scope.launch { remoteSession.removeSavedTarget(saved) } }
                },
                onClick = {
                    if (!selected) {
                        runSwitch { remoteSession.connect(saved, rememberPassword = savePassword) }
                    }
                },
            )
        }

        if (addOpen) {
            AddHostForm(
                draftTarget = draftTarget,
                onDraftChange = { draftTarget = it },
                savePassword = savePassword,
                onSavePasswordChange = { savePassword = it },
                busy = busy,
                showCancel = hasSavedTargets,
                onCancel = {
                    addOpen = false
                    draftTarget = ""
                },
                onSubmit = {
                    val target = draftTarget.trim()
                    if (target.isNotEmpty()) {
                        runSwitch {
                            remoteSession.addSavedTarget(target)
                            remoteSession.connect(target, rememberPassword = savePassword)
                            if (remoteSession.state.value.isRemote) {
                                draftTarget = ""
                            }
                        }
                    }
                },
            )
        } else {
            PanelActionRow(
                icon = Lucide.Plus,
                label = "Add host",
                onClick = { addOpen = true },
            )
        }
    }
}

/** Live status plus the two actions that only exist off-box, grouped on one raised surface. */
@Composable
private fun ConnectionCard(
    phase: HostPhase,
    session: RemoteSessionState,
    busy: Boolean,
    onReconnect: () -> Unit,
    onOpenScreen: (onResult: (String) -> Unit) -> Unit,
) {
    val tokens = andyTokens()
    val caps = session.hostCapabilities
    val availability = caps?.screenAvailability ?: RemoteScreenAvailability.Unsupported
    var statusMessage by remember(session.target, availability) { mutableStateOf<String?>(null) }
    val statusLabel = hostStatusLabel(session, phase)
    val error = session.error?.takeIf { phase == HostPhase.Failed }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(AndyShape.Menu)
            .background(AndyColors.SurfaceRaised)
            .padding(AndySpace.Space2),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space1),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            StatusDot(
                variant = when (phase) {
                    HostPhase.Failed -> StatusDotVariant.Error
                    else -> StatusDotVariant.Info
                },
                pulsing = phase == HostPhase.Switching,
            )
            Text(
                statusLabel,
                color = if (phase == HostPhase.Failed) tokens.error else TextPrimary,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        // While connected the header and the selected list row already name the host;
        // only the transitional states need it spelled out here.
        session.target?.takeIf { phase != HostPhase.Connected }?.let { target ->
            Tooltip(target, modifier = Modifier.fillMaxWidth()) {
                Text(
                    target,
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        error?.let { message ->
            Tooltip(message, modifier = Modifier.fillMaxWidth()) {
                Text(
                    message,
                    color = tokens.error,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (session.isRemote || phase == HostPhase.Failed) {
            AndyHorizontalDivider()
            PanelActionRow(
                icon = Lucide.RefreshCw,
                label = if (phase == HostPhase.Failed) "Retry" else "Reconnect",
                enabled = !busy,
                onClick = onReconnect,
            )
            if (session.isRemote) {
                val screenHint = caps?.enablementHint
                when (availability) {
                    RemoteScreenAvailability.Available -> PanelActionRow(
                        icon = Lucide.Monitor,
                        label = "Open screen",
                        enabled = !busy,
                        onClick = { onOpenScreen { statusMessage = it } },
                    )
                    RemoteScreenAvailability.NeedsEnabling -> PanelActionRow(
                        icon = Lucide.Monitor,
                        label = "Screen sharing off",
                        enabled = false,
                        tooltip = screenHint
                            ?: "Enable Screen Sharing on the remote host, then reconnect.",
                        onClick = {},
                    )
                    RemoteScreenAvailability.Unsupported -> PanelActionRow(
                        icon = Lucide.Monitor,
                        label = "No screen sharing",
                        enabled = false,
                        tooltip = screenHint
                            ?: "Remote screen sharing is not available on this host.",
                        onClick = {},
                    )
                }
            }
            statusMessage?.let { message ->
                Tooltip(message, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        message,
                        color = TextSecondary,
                        fontFamily = MonoFont,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun AddHostForm(
    draftTarget: String,
    onDraftChange: (String) -> Unit,
    savePassword: Boolean,
    onSavePasswordChange: (Boolean) -> Unit,
    busy: Boolean,
    showCancel: Boolean,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = AndySpace.Space1),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        TextField(
            value = draftTarget,
            onValueChange = onDraftChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            textStyle = CodeFieldTextStyle(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            placeholder = {
                Text(
                    "user@host",
                    color = AndyColors.TextTertiary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                )
            },
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            AndyCheckbox(
                checked = savePassword,
                onCheckedChange = onSavePasswordChange,
                enabled = !busy,
            )
            Text(
                "Save password",
                color = TextSecondary,
                fontFamily = DisplayFont,
                fontSize = 11.sp,
                modifier = Modifier.clickable(enabled = !busy) { onSavePasswordChange(!savePassword) },
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2, Alignment.End),
        ) {
            if (showCancel) {
                TextButton(
                    onClick = onCancel,
                    enabled = !busy,
                    colors = mutedTextButtonColors(),
                ) { Text("Cancel", fontSize = 11.sp) }
            }
            Button(
                onClick = onSubmit,
                enabled = !busy && draftTarget.isNotBlank(),
            ) { Text("Connect", fontSize = 11.sp) }
        }
    }
}

@Composable
private fun HostRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    val tokens = andyTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        selected -> tokens.accentMuted
        hovered && enabled -> AndyColors.SurfaceHover
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(HostRowHeight)
            .clip(AndyShape.Interactive)
            .background(background)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = AndySpace.Space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        HostDot(phase = if (selected) HostPhase.Connected else HostPhase.Local)
        Text(
            label,
            color = if (selected) TextPrimary else TextSecondary,
            fontFamily = MonoFont,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Reserved so the label never reflows as the remove affordance fades in.
        Box(Modifier.size(HostRowIconBox), contentAlignment = Alignment.Center) {
            if (onRemove != null && hovered && enabled) {
                Box(
                    Modifier
                        .size(HostRowIconBox)
                        .clip(AndyShape.Interactive)
                        .clickable(onClick = onRemove)
                        .semantics { contentDescription = "Remove $label" },
                    contentAlignment = Alignment.Center,
                ) {
                    LucideIcon(
                        path = Lucide.X,
                        tint = AndyColors.TextTertiary,
                        modifier = Modifier.size(HostRowIcon),
                    )
                }
            }
        }
    }
}

/** Row-shaped action so card actions share the host list's alignment and hit target. */
@Composable
private fun PanelActionRow(
    icon: String,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tooltip: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val contentColor = if (enabled) TextSecondary else AndyColors.TextTertiary
    val row = @Composable {
        Row(
            Modifier
                .fillMaxWidth()
                .height(HostRowHeight)
                .clip(AndyShape.Interactive)
                .background(if (hovered && enabled) AndyColors.SurfaceHover else Color.Transparent)
                .clickable(
                    enabled = enabled,
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = AndySpace.Space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            Box(Modifier.size(HostDotSize), contentAlignment = Alignment.Center) {
                LucideIcon(
                    path = icon,
                    tint = contentColor,
                    modifier = Modifier.size(HostRowIcon),
                )
            }
            Text(
                label,
                color = contentColor,
                fontFamily = DisplayFont,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (tooltip != null) {
        Tooltip(tooltip, modifier = Modifier.fillMaxWidth()) { row() }
    } else {
        row()
    }
}

/** 8dp state mark — filled on the active host, hollow elsewhere. */
@Composable
private fun HostDot(phase: HostPhase, modifier: Modifier = Modifier) {
    val tokens = andyTokens()
    val filled = phase == HostPhase.Connected
    val color = when (phase) {
        HostPhase.Connected, HostPhase.Switching -> tokens.accent
        HostPhase.Failed -> tokens.error
        HostPhase.Local -> AndyColors.TextTertiary
    }
    Box(
        modifier
            .size(HostDotSize)
            .clip(CircleShape)
            .then(
                if (filled) {
                    Modifier.background(color)
                } else {
                    Modifier.border(1.dp, color, CircleShape)
                },
            ),
    )
}

/** Collapsed rail: one mark, full host in the tooltip. */
@Composable
private fun HostRail(phase: HostPhase, target: String?, modifier: Modifier = Modifier) {
    val tooltip = when (phase) {
        HostPhase.Connected -> target?.let { "Connected · $it" } ?: "Connected"
        HostPhase.Switching -> "Switching host…"
        HostPhase.Failed -> "Not connected"
        HostPhase.Local -> "Local host"
    }
    Tooltip(tooltip, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(AndyLayout.SidebarRowHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space1, Alignment.CenterHorizontally),
        ) {
            HostDot(phase = phase)
            Text(
                text = when (phase) {
                    HostPhase.Connected -> "R"
                    HostPhase.Switching -> "…"
                    else -> "L"
                },
                color = if (phase == HostPhase.Connected) TextPrimary else TextSecondary,
                fontFamily = MonoFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
            )
        }
    }
}
