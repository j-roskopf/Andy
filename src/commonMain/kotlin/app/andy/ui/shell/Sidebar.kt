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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.AndyDestination
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.andy_robot
import app.andy.model.SdkDiscovery
import app.andy.service.AppUpdateService
import app.andy.service.AppUpdateState
import app.andy.ui.components.StatusRow
import app.andy.ui.agents.ProjectActivityIndicator
import app.andy.ui.agents.UnreadDot
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun Sidebar(
    current: AndyDestination,
    destinations: List<AndyDestination>,
    deviceCount: Int,
    hasUnreadAgentTasks: Boolean,
    hasUnreadProjectAgentTasks: Boolean,
    hasActiveProjectAgentTasks: Boolean,
    onSelect: (AndyDestination) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    statusExpanded: Boolean,
    onStatusExpandedChange: (Boolean) -> Unit,
    sdk: SdkDiscovery,
    updates: AppUpdateService?,
    mcpRunning: Boolean,
    mcpPort: Int
) {
    val updateState by if (updates != null) {
        updates.state.collectAsState()
    } else {
        remember { mutableStateOf<AppUpdateState>(AppUpdateState.Idle) }
    }
    val scope = rememberCoroutineScope()
    val sidebarWidth by animateDpAsState(
        targetValue = if (expanded) 246.dp else 64.dp,
        animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
        label = "workspaceSidebarWidth",
    )
    val horizontalPadding by animateDpAsState(
        targetValue = if (expanded) AndySpace.S3 else AndySpace.S2,
        animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
        label = "workspaceSidebarPadding",
    )
    val labelAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = 130, easing = FastOutSlowInEasing),
        label = "workspaceSidebarLabelAlpha",
    )
    val labelGap by animateDpAsState(
        targetValue = if (expanded) 8.dp else 0.dp,
        animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
        label = "workspaceSidebarLabelGap",
    )

    Column(
        Modifier.width(sidebarWidth).fillMaxHeight()
            .background(Brush.verticalGradient(listOf(AndyColors.Neutral750, AndyColors.Neutral850)), RoundedCornerShape(AndyRadius.R4))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R4))
            .padding(horizontal = horizontalPadding, vertical = AndySpace.S3),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(AndySpace.S1, AndySpace.S2, AndySpace.S1, AndySpace.S4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (labelAlpha > 0.01f) Arrangement.spacedBy(AndySpace.S2) else Arrangement.Center,
        ) {
            AndyRobotIcon(Modifier.size(28.dp))
            if (labelAlpha > 0.01f) {
                Column {
                    Text("andy", color = AndyColors.Neutral100.copy(alpha = labelAlpha), fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("workspace", color = TextSecondary.copy(alpha = labelAlpha), fontFamily = MonoFont, fontWeight = FontWeight.Medium, fontSize = 10.sp)
                }
            }
        }
        WorkspaceSidebarToggle(expanded = expanded, onClick = { onExpandedChange(!expanded) })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
        ) {
            destinations.forEach { item ->
                val active = item == current
                Row(
                    Modifier.fillMaxWidth()
                        .height(34.dp)
                        .background(if (active) AndyColors.OrangeSubtle else Color.Transparent, RoundedCornerShape(AndyRadius.R2))
                        .then(if (active) Modifier.border(1.dp, AndyColors.OrangeBorder.copy(alpha = 0.52f), RoundedCornerShape(AndyRadius.R2)) else Modifier)
                        .clickable { onSelect(item) }
                        .padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (labelAlpha > 0.01f) Arrangement.Start else Arrangement.Center,
                ) {
                    Text(navMark(item), color = if (active) Rust else TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
                    if (labelAlpha > 0.01f) {
                        Spacer(Modifier.width(labelGap))
                        Text(
                            item.label.lowercase(),
                            color = (if (active) AndyColors.Neutral100 else AndyColors.Neutral300).copy(alpha = labelAlpha),
                            fontFamily = MonoFont,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item == AndyDestination.Devices) Text("$deviceCount", color = TextSecondary.copy(alpha = labelAlpha), fontFamily = MonoFont, fontSize = 11.sp)
                        if (item == AndyDestination.Logcat) Text("live", color = TextSecondary.copy(alpha = labelAlpha), fontFamily = MonoFont, fontSize = 10.sp)
                    }
                    if (
                        (item == AndyDestination.Agents && hasUnreadAgentTasks) ||
                        (item == AndyDestination.Actions && (
                            hasUnreadProjectAgentTasks || hasActiveProjectAgentTasks
                        ))
                    ) {
                        Spacer(Modifier.width(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (
                                (item == AndyDestination.Agents && hasUnreadAgentTasks) ||
                                (item == AndyDestination.Actions && hasUnreadProjectAgentTasks)
                            ) UnreadDot()
                            if (item == AndyDestination.Actions && hasActiveProjectAgentTasks) {
                                ProjectActivityIndicator(10.dp)
                            }
                        }
                    }
                }
            }
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth()
                    .background(AndyColors.Neutral900.copy(alpha = 0.56f), RoundedCornerShape(AndyRadius.R3))
                    .border(1.dp, Border, RoundedCornerShape(AndyRadius.R4))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onStatusExpandedChange(!statusExpanded) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "v${app.andy.updates.AndyBuildInfo.versionName}",
                        color = TextSecondary,
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (statusExpanded) "▾" else "▸",
                        color = TextSecondary,
                        fontFamily = MonoFont,
                        fontSize = 10.sp,
                    )
                }
                AnimatedVisibility(
                    visible = statusExpanded,
                    enter = expandVertically(animationSpec = tween(170, easing = FastOutSlowInEasing)) + fadeIn(tween(120)),
                    exit = shrinkVertically(animationSpec = tween(140, easing = FastOutSlowInEasing)) + fadeOut(tween(90)),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "h.264 embedded",
                            color = TextSecondary,
                            fontFamily = MonoFont,
                            fontSize = 10.sp,
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

                        HorizontalDivider(color = Border, thickness = 1.dp, modifier = Modifier.padding(vertical = 2.dp))

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
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = updateText,
                                    color = updateColor,
                                    fontSize = 11.sp,
                                    fontFamily = MonoFont,
                                    fontWeight = if (updateState is AppUpdateState.Available) FontWeight.Bold else FontWeight.Normal
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
            .height(30.dp)
            .padding(bottom = 6.dp),
        horizontalArrangement = if (expanded) Arrangement.End else Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(24.dp)
                .background(AndyColors.Neutral900.copy(alpha = 0.50f), RoundedCornerShape(AndyRadius.R2))
                .border(1.dp, Border, RoundedCornerShape(AndyRadius.R2))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (expanded) "<<" else ">>",
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun AndyRobotIcon(modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(
                Brush.verticalGradient(listOf(AndyColors.Neutral600, AndyColors.Neutral850)),
                RoundedCornerShape(AndyRadius.R3),
            )
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(AndyRadius.R3))
            .padding(3.dp),
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
    AndyDestination.Accessibility -> "13"
    AndyDestination.Bugs -> "!!"
    AndyDestination.Recordings -> ">o"
    AndyDestination.Settings -> "*:"
}
