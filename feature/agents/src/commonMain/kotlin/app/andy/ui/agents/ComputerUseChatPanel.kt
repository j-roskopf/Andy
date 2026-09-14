package app.andy.ui.agents

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.currentTimeMillis
import app.andy.model.ComputerUseHudState
import app.andy.service.AndyServices
import app.andy.ui.components.Button
import app.andy.ui.components.Card
import app.andy.ui.components.CardElevation
import app.andy.ui.components.HoverTooltip
import app.andy.ui.components.Lucide
import app.andy.ui.components.LucideIcon
import app.andy.ui.components.TextButton
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ComputerUseCardWidth = 248.dp

/**
 * Floating Computer Use chip — same top-right chat-pane dock as [WorktreeEnvironmentPanel].
 * Tucks to an edge tag when the expanded card would cover transcript text.
 */
@Composable
internal fun ComputerUseChatPanel(
    services: AndyServices,
    state: ComputerUseHudState,
    paneWidth: Dp,
    contentFullBleed: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val elapsedSec = rememberElapsedSeconds(state.startedAtEpochMs)
    val log by services.computerUse.actionLog.collectAsState()
    val entries = log.ifEmpty { state.actions }.takeLast(12)

    var pinnedOpen by remember(state.sessionId) { mutableStateOf(false) }
    val shouldAutoTuck = environmentCardWouldEclipseContent(
        paneWidth = paneWidth,
        contentFullBleed = contentFullBleed,
        cardWidth = ComputerUseCardWidth,
    )
    LaunchedEffect(shouldAutoTuck) {
        if (!shouldAutoTuck) pinnedOpen = false
    }
    val showExpanded = !shouldAutoTuck || pinnedOpen

    AnimatedContent(
        targetState = showExpanded,
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it / 4 } + fadeOut())
            } else {
                (slideInHorizontally { it / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it / 3 } + fadeOut())
            }.using(SizeTransform(clip = false))
        },
        label = "computer-use-dock",
        modifier = modifier,
    ) { expanded ->
        if (expanded) {
            ComputerUseExpandedCard(
                scopeLabel = state.scopeAppNames.joinToString().ifBlank { "whole desktop" },
                elapsedSec = elapsedSec,
                entries = entries.map { it.summary },
                showTuckControl = shouldAutoTuck,
                onTuck = { pinnedOpen = false },
                onStop = onStop,
            )
        } else {
            ComputerUseEdgeTag(
                elapsedSec = elapsedSec,
                onClick = { pinnedOpen = true },
            )
        }
    }
}

@Composable
private fun ComputerUseEdgeTag(
    elapsedSec: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    HoverTooltip(text = "Show Computer Use") {
        Card(
            modifier = modifier
                .pointerHoverIcon(PointerIcon.Hand)
                .semantics {
                    contentDescription = "Show Computer Use"
                    role = Role.Button
                }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                )
                .testTag("chat-computer-use-tag"),
            elevation = CardElevation.Med,
            shape = AndyShape.Menu,
            backgroundColor = AndyColors.SurfaceRaised,
            contentPadding = PaddingValues(horizontal = AndySpace.Space2, vertical = AndySpace.Space2),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LucideIcon(Lucide.Monitor, Rust, Modifier.size(14.dp))
                Text(
                    "CU",
                    color = if (hovered) TextPrimary else Rust,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                )
                Text(
                    "${elapsedSec}s",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun ComputerUseExpandedCard(
    scopeLabel: String,
    elapsedSec: Long,
    entries: List<String>,
    showTuckControl: Boolean,
    onTuck: () -> Unit,
    onStop: () -> Unit,
) {
    Card(
        modifier = Modifier
            .width(ComputerUseCardWidth)
            .heightIn(max = 280.dp)
            .testTag("chat-computer-use"),
        elevation = CardElevation.Med,
        shape = AndyShape.Menu,
        backgroundColor = AndyColors.SurfaceRaised,
        contentPadding = PaddingValues(horizontal = AndySpace.Space2, vertical = AndySpace.Space2),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .padding(horizontal = AndySpace.Space1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Computer Use",
                color = Rust,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${elapsedSec}s",
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            if (showTuckControl) {
                ComputerUseIconButton(
                    path = Lucide.ChevronRight,
                    contentDescription = "Hide Computer Use",
                    onClick = onTuck,
                )
            }
            ComputerUseIconButton(
                path = Lucide.X,
                contentDescription = "Stop computer use",
                onClick = onStop,
                tint = Rust,
                modifier = Modifier.testTag("chat-computer-use-stop"),
            )
        }

        Text(
            scopeLabel,
            color = TextSecondary,
            fontFamily = MonoFont,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AndySpace.Space1, vertical = 2.dp),
        )

        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 140.dp)
                .padding(horizontal = AndySpace.Space1)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (entries.isEmpty()) {
                Text(
                    "Waiting for actions…",
                    color = TextSecondary.copy(alpha = 0.7f),
                    fontFamily = DisplayFont,
                    fontSize = 12.sp,
                )
            } else {
                entries.forEach { summary ->
                    Text(
                        summary,
                        color = TextSecondary,
                        fontFamily = MonoFont,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComputerUseIconButton(
    path: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = TextSecondary,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(28.dp)
            .clip(AndyShape.Interactive)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        LucideIcon(path, tint, Modifier.size(14.dp))
    }
}

/** Ticking elapsed seconds since [startedAtEpochMs]. */
@Composable
private fun rememberElapsedSeconds(startedAtEpochMs: Long): Long {
    var elapsed by remember(startedAtEpochMs) { mutableStateOf(0L) }
    LaunchedEffect(startedAtEpochMs) {
        while (true) {
            elapsed = ((currentTimeMillis() - startedAtEpochMs) / 1000).coerceAtLeast(0)
            delay(500)
        }
    }
    return elapsed
}

/**
 * Global always-on-top computer-use surface, independent of the task-detail pane.
 * Renders the pending arm approval, the deferred high-consequence confirmation, and the
 * live session HUD with its Stop control.
 */
@Composable
fun ComputerUseGlobalHud(
    services: AndyServices,
    modifier: Modifier = Modifier,
) {
    val pendingArm by services.computerUse.pendingArm.collectAsState()
    val hud by services.computerUse.hud.collectAsState()
    val log by services.computerUse.actionLog.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier.padding(AndySpace.Space3),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        horizontalAlignment = Alignment.End,
    ) {
        pendingArm?.let { arm ->
            ComputerUseDialogCard(
                title = "Allow computer use?",
                body = "An agent wants to control " +
                    arm.scope.appNames.joinToString().ifBlank { "the whole desktop" } +
                    ". Approve for this run?",
                confirmLabel = "Allow",
                dismissLabel = "Deny",
                onConfirm = { services.computerUse.decideArm(arm.requestId, true) },
                onDismiss = { services.computerUse.decideArm(arm.requestId, false) },
                testTag = "computer-use-arm",
            )
        }
        val state = hud
        if (state != null) {
            val elapsedSec = rememberElapsedSeconds(state.startedAtEpochMs)
            state.pendingConfirmation?.let { reason ->
                ComputerUseDialogCard(
                    title = "Confirm action",
                    body = reason,
                    confirmLabel = "Confirm",
                    dismissLabel = "Cancel",
                    onConfirm = { coroutineScope.launch { services.computerUse.confirmPendingAction() } },
                    onDismiss = { coroutineScope.launch { services.computerUse.discardPendingAction() } },
                    testTag = "computer-use-confirm",
                )
            }
            ComputerUseExpandedCard(
                scopeLabel = state.scopeAppNames.joinToString().ifBlank { "whole desktop" },
                elapsedSec = elapsedSec,
                entries = log.ifEmpty { state.actions }.takeLast(12).map { it.summary },
                showTuckControl = false,
                onTuck = {},
                onStop = { services.computerUse.panic("hud stop") },
            )
        }
    }
}

@Composable
private fun ComputerUseDialogCard(
    title: String,
    body: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    testTag: String,
) {
    Card(
        modifier = Modifier
            .width(ComputerUseCardWidth)
            .testTag(testTag),
        elevation = CardElevation.Med,
        shape = AndyShape.Menu,
        backgroundColor = AndyColors.SurfaceRaised,
        contentPadding = PaddingValues(AndySpace.Space3),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        Text(
            title,
            color = Rust,
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 17.sp,
        )
        Text(
            body,
            color = TextPrimary,
            fontFamily = DisplayFont,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text(dismissLabel, fontSize = 12.sp)
            }
            Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                Text(confirmLabel, fontSize = 12.sp)
            }
        }
    }
}

