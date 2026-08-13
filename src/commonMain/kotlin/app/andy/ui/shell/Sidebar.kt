package app.andy.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import app.andy.ui.components.AndyHorizontalDivider
import app.andy.ui.components.rightBorder
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.AndyDestination
import app.andy.availableWithIosTarget
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.andy_robot
import app.andy.model.SdkDiscovery
import app.andy.service.AppUpdateService
import app.andy.service.AppUpdateState
import app.andy.ui.components.StatusRow
import app.andy.ui.agents.ProjectActivityIndicator
import app.andy.ui.agents.UnreadDot
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyMotion
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun Sidebar(
    current: AndyDestination,
    destinations: List<AndyDestination>,
    deviceCount: Int,
    iosSelectionActive: Boolean = false,
    hasUnreadAgentTasks: Boolean,
    hasUnreadProjectAgentTasks: Boolean,
    hasActiveProjectAgentTasks: Boolean,
    hasBlockedAgentTasks: Boolean,
    hasBlockedProjectAgentTasks: Boolean,
    logcatLive: Boolean,
    onSelect: (AndyDestination) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    statusExpanded: Boolean,
    onStatusExpandedChange: (Boolean) -> Unit,
    sdk: SdkDiscovery,
    updates: AppUpdateService?,
    mcpRunning: Boolean,
    mcpPort: Int,
    /** Clears traffic-light / title-bar chrome while keeping SidebarBg full-bleed underneath. */
    contentTopPadding: Dp = 0.dp,
) {
    val updateState by if (updates != null) {
        updates.state.collectAsState()
    } else {
        remember { mutableStateOf<AppUpdateState>(AppUpdateState.Idle) }
    }
    val scope = rememberCoroutineScope()
    val spatialSpec = tween<androidx.compose.ui.unit.Dp>(
        durationMillis = AndyMotion.SpatialMs,
        easing = FastOutSlowInEasing,
    )
    val fadeSpec = tween<Float>(
        durationMillis = AndyMotion.StandardMs,
        easing = FastOutSlowInEasing,
    )
    val sidebarWidth by animateDpAsState(
        targetValue = if (expanded) AndyLayout.SidebarWidth else AndyLayout.SidebarCollapsedWidth,
        animationSpec = spatialSpec,
        label = "workspaceSidebarWidth",
    )
    val horizontalPadding by animateDpAsState(
        targetValue = if (expanded) AndySpace.Space3 else AndySpace.Space2,
        animationSpec = spatialSpec,
        label = "workspaceSidebarPadding",
    )
    val labelAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = fadeSpec,
        label = "workspaceSidebarLabelAlpha",
    )
    val labelGap by animateDpAsState(
        targetValue = if (expanded) AndySpace.Space3 else 0.dp,
        animationSpec = spatialSpec,
        label = "workspaceSidebarLabelGap",
    )

    Column(
        Modifier
            .width(sidebarWidth)
            .fillMaxHeight()
            .background(AndyColors.SidebarBg)
            .padding(top = contentTopPadding)
            .padding(horizontal = horizontalPadding, vertical = AndySpace.Space3),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(
                start = AndySpace.Space1,
                top = AndySpace.Space2,
                end = AndySpace.Space1,
                bottom = AndySpace.Space4,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (labelAlpha > 0.01f) Arrangement.spacedBy(AndySpace.Space3) else Arrangement.Center,
        ) {
            AndyRobotIcon(Modifier.size(AndyLayout.ControlHeightSm))
            if (labelAlpha > 0.01f) {
                Column {
                    Text(
                        "Andy",
                        color = TextPrimary.copy(alpha = labelAlpha),
                        fontFamily = DisplayFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                    Text(
                        "Workspace",
                        color = AndyColors.TextTertiary.copy(alpha = labelAlpha),
                        fontFamily = DisplayFont,
                        fontWeight = FontWeight.Normal,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        WorkspaceSidebarToggle(expanded = expanded, onClick = { onExpandedChange(!expanded) })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
        ) {
            // Keep nav rows at SidebarRowHeight; Material's 48dp min touch target was
            // expanding the selected Settings (and other) rows into oversized pills.
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            destinations.forEach { item ->
                val disabledForIos = iosSelectionActive && !item.availableWithIosTarget()
                val active = item == current
                Box(
                    Modifier.fillMaxWidth()
                        .height(AndyLayout.SidebarRowHeight)
                        .clip(RoundedCornerShape(AndyRadius.Control))
                        .background(
                            if (active) AndyColors.SurfaceSelected else Color.Transparent,
                        )
                        .clickable(enabled = !disabledForIos) {
                            if (disabledForIos) onSelect(AndyDestination.Live) else onSelect(item)
                        },
                ) {
                    if (active) {
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .width(AndyLayout.NavAccentBar)
                                .height(AndyLayout.SidebarRowHeight - 8.dp)
                                .background(Rust, RoundedCornerShape(AndyRadius.Pill)),
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = AndySpace.Space3),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (labelAlpha > 0.01f) Arrangement.Start else Arrangement.Center,
                    ) {
                    Text(
                        navMark(item),
                        color = when {
                            active -> Rust
                            disabledForIos -> AndyColors.TextDisabled
                            else -> TextSecondary
                        },
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                    )
                    if (labelAlpha > 0.01f) {
                        Spacer(Modifier.width(labelGap))
                        Text(
                            item.label,
                            color = (if (active) TextPrimary else TextSecondary)
                                .copy(alpha = if (disabledForIos) labelAlpha * 0.35f else labelAlpha),
                            fontFamily = DisplayFont,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item == AndyDestination.Devices) {
                            Text(
                                "$deviceCount",
                                color = AndyColors.TextTertiary.copy(alpha = labelAlpha),
                                fontFamily = DisplayFont,
                                fontSize = 11.sp,
                            )
                        }
                        if (item == AndyDestination.Logcat) {
                            Text(
                                if (logcatLive) "Live" else "Paused",
                                color = AndyColors.TextTertiary.copy(alpha = labelAlpha),
                                fontFamily = DisplayFont,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    val blocked = (item == AndyDestination.Agents && hasBlockedAgentTasks) ||
                        (item == AndyDestination.Actions && hasBlockedProjectAgentTasks)
                    if (
                        blocked ||
                        (item == AndyDestination.Agents && hasUnreadAgentTasks) ||
                        (item == AndyDestination.Actions && (
                            hasUnreadProjectAgentTasks || hasActiveProjectAgentTasks
                        ))
                    ) {
                        Spacer(Modifier.width(AndySpace.Space2))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                        ) {
                            if (blocked) {
                                Box(Modifier.size(6.dp).background(Red, CircleShape))
                            } else if (
                                (item == AndyDestination.Agents && hasUnreadAgentTasks) ||
                                (item == AndyDestination.Actions && hasUnreadProjectAgentTasks)
                            ) UnreadDot()
                            if (item == AndyDestination.Actions && hasActiveProjectAgentTasks) {
                                ProjectActivityIndicator(20.dp)
                            }
                        }
                    }
                    }
                }
            }
            }
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth()
                    .padding(horizontal = AndySpace.Space1, vertical = AndySpace.Space2),
                verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onStatusExpandedChange(!statusExpanded) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                ) {
                    Text(
                        "v${app.andy.updates.AndyBuildInfo.versionName}",
                        color = AndyColors.TextTertiary,
                        fontFamily = DisplayFont,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (statusExpanded) "▾" else "▸",
                        color = AndyColors.TextTertiary,
                        fontFamily = DisplayFont,
                        fontSize = 10.sp,
                    )
                }
                AnimatedVisibility(
                    visible = statusExpanded,
                    enter = expandVertically(animationSpec = tween(AndyMotion.StandardMs, easing = FastOutSlowInEasing)) +
                        fadeIn(tween(AndyMotion.FastMs)),
                    exit = shrinkVertically(animationSpec = tween(AndyMotion.SmallMinMs, easing = FastOutSlowInEasing)) +
                        fadeOut(tween(AndyMotion.MicroMinMs)),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(AndySpace.Space2)) {
                        Text(
                            "H.264 embedded",
                            color = AndyColors.TextTertiary,
                            fontFamily = DisplayFont,
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                        if (updates != null) {
                            StatusRow("ADB server", if (sdk.hasAdb) "ready" else "missing", sdk.hasAdb)
                            StatusRow("AVD tools", if (sdk.hasEmulatorTools) "ready" else "missing", sdk.hasEmulatorTools)
                            StatusRow("Proxy CA", "local", true)
                            StatusRow("MCP server", if (mcpRunning) "running :$mcpPort" else "stopped", mcpRunning)
                        } else {
                            StatusRow("Web ADB", if (deviceCount > 0) "connected" else "disconnected", deviceCount > 0)
                            StatusRow("Local only", "port 10000", true)
                        }

                        AndyHorizontalDivider(color = Border, modifier = Modifier.padding(vertical = AndySpace.Space1))

                        if (updates != null) {
                            val updateText = when (updateState) {
                                AppUpdateState.Idle -> "Check for updates"
                                AppUpdateState.Checking -> "Checking for updates..."
                                AppUpdateState.Current -> "Andy is up to date"
                                is AppUpdateState.Available -> "Update to v${(updateState as AppUpdateState.Available).update.versionName}"
                                is AppUpdateState.Installing -> (updateState as AppUpdateState.Installing).let {
                                    val pct = it.progress?.let { p -> " ${(p * 100).toInt()}%" } ?: ""
                                    "${it.message}$pct"
                                }
                                is AppUpdateState.Failed -> (updateState as AppUpdateState.Failed).message
                            }

                            val isActionable = updateState is AppUpdateState.Idle || updateState is AppUpdateState.Available || updateState is AppUpdateState.Failed
                            val updateColor = when (updateState) {
                                is AppUpdateState.Available -> Rust
                                is AppUpdateState.Failed -> Red
                                else -> TextSecondary
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (isActionable) Modifier.clickable {
                                        scope.launch {
                                            if (updateState is AppUpdateState.Available) {
                                                updates.installAvailableUpdate()
                                            } else {
                                                updates.checkForUpdates()
                                            }
                                        }
                                    } else Modifier)
                                    .padding(vertical = AndySpace.Space1),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = updateText,
                                    color = updateColor,
                                    fontSize = 11.sp,
                                    fontFamily = DisplayFont,
                                    fontWeight = if (updateState is AppUpdateState.Available) FontWeight.Medium else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceSidebarToggle(expanded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .height(AndyLayout.ControlHeightMd)
            .padding(bottom = AndySpace.Space2),
        horizontalArrangement = if (expanded) Arrangement.End else Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(AndyLayout.ToolbarButtonSize)
                .background(Color.Transparent, RoundedCornerShape(AndyRadius.Control))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (expanded) "‹‹" else "››",
                color = TextSecondary,
                fontFamily = DisplayFont,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun AndyRobotIcon(modifier: Modifier = Modifier) {
    Box(
        modifier,
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(Res.drawable.andy_robot),
            contentDescription = "Andy",
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun navMark(item: AndyDestination): String = when (item) {
    AndyDestination.Devices -> "[]"
    AndyDestination.Catalog -> "<>"
    AndyDestination.Live -> ">>"
    AndyDestination.Apps -> "::"
    AndyDestination.Logcat -> "##"
    AndyDestination.Intents -> "->"
    AndyDestination.Files -> "/_"
    AndyDestination.ComputerFiles -> "//"
    AndyDestination.Network -> "~~"
    AndyDestination.Actions -> "|>"
    AndyDestination.Agents -> "@>"
    AndyDestination.Snapshots -> "[]"
    AndyDestination.Controls -> "+-"
    AndyDestination.Performance -> "/^"
    AndyDestination.Tracing -> "~*"
    AndyDestination.Design -> "%%"
    AndyDestination.Inspector -> "{}"
    AndyDestination.Bugs -> "!!"
    AndyDestination.Recordings -> ">o"
    AndyDestination.Settings -> "*:"
}
