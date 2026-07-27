package app.andy.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.AndyDestination
import app.andy.model.ActionProject
import app.andy.model.ActionsConfig
import app.andy.model.AndroidDevice
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.ProjectAction
import app.andy.ui.actions.actionIconMarker
import app.andy.ui.components.Button
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.bottomBorder
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.components.secondaryButtonColors
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.hardware_pop_out
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.network.GlowingDot
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun TopChrome(
    destination: AndyDestination,
    selectedDevice: AndroidDevice?,
    devices: List<AndroidDevice>,
    iosTargets: List<IosTarget>,
    selectedIosTarget: IosTarget?,
    onSelectDevice: (String) -> Unit,
    onSelectIosTarget: (String) -> Unit,
    onRefresh: () -> Unit,
    onStopEmulator: (AndroidDevice) -> Unit,
    stoppingEmulatorSerial: String?,
    showDevicePopOut: Boolean = false,
    onPopOutDevice: (String, String) -> Unit = { _, _ -> },
    actionConfig: ActionsConfig,
    selectedActionProjectId: String? = null,
    selectedActionId: String? = null,
    onActionSelectionChange: (projectId: String, actionId: String?) -> Unit = { _, _ -> },
    onRunAction: (ActionProject, ProjectAction) -> Unit,
    proxyRunning: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val hasActionRunnerControls = actionConfig.projects.any { it.actions.isNotEmpty() }
    var projectMenuExpanded by remember { mutableStateOf(false) }
    var actionMenuExpanded by remember { mutableStateOf(false) }
    var deviceMenuExpanded by remember { mutableStateOf(false) }
    val anyMenuExpanded = projectMenuExpanded || actionMenuExpanded || deviceMenuExpanded
    SideEffect {
        onMenuExpandedChange(anyMenuExpanded)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(AndyLayout.ToolbarHeight)
            .background(AndyColors.ContentBg)
            .bottomBorder(Border)
            .padding(horizontal = AndySpace.Space5),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = AndySpace.Space4)) {
            Text(
                destination.label,
                color = TextPrimary,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 18.sp,
            )
            Text(
                selectedIosTarget?.displayName
                    ?: selectedDevice?.let { "${it.displayName} · API ${it.apiLevel ?: "—"} · ${it.abi ?: "—"}" }
                    ?: "No device selected",
                color = AndyColors.TextTertiary,
                fontFamily = DisplayFont,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        actions()
        if (destination != AndyDestination.Network && proxyRunning) {
            ProxyToolbarIndicator()
            Spacer(Modifier.width(AndySpace.Space3))
        }
        if (hasActionRunnerControls) {
            ActionRunnerSelector(
                config = actionConfig,
                selectedProjectId = selectedActionProjectId,
                selectedActionId = selectedActionId,
                onSelectionChange = onActionSelectionChange,
                onRunAction = onRunAction,
                projectExpanded = projectMenuExpanded,
                onProjectExpandedChange = { projectMenuExpanded = it },
                actionExpanded = actionMenuExpanded,
                onActionExpandedChange = { actionMenuExpanded = it },
            )
            Spacer(Modifier.width(AndySpace.Space3))
        }
        if (selectedDevice?.kind == DeviceKind.Emulator && selectedDevice.state == DeviceConnectionState.Online) {
            OutlinedButton(
                onClick = { onStopEmulator(selectedDevice) },
                enabled = stoppingEmulatorSerial != selectedDevice.serial,
                shape = RoundedCornerShape(AndyRadius.Row),
                contentPadding = PaddingValues(horizontal = AndySpace.Space4, vertical = AndySpace.Space2),
            ) {
                Text(
                    if (stoppingEmulatorSerial == selectedDevice.serial) "Stopping" else "Stop Emulator",
                    fontFamily = DisplayFont,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.width(AndySpace.Space3))
        }
        Button(
            onClick = onRefresh,
            colors = secondaryButtonColors(),
            shape = RoundedCornerShape(AndyRadius.Row),
            contentPadding = PaddingValues(horizontal = AndySpace.Space4, vertical = AndySpace.Space2),
        ) {
            Text("Refresh", color = TextPrimary, fontFamily = DisplayFont, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(AndySpace.Space3))
        DevicePicker(
            devices = devices,
            selectedDevice = selectedDevice,
            iosTargets = iosTargets,
            selectedIosTarget = selectedIosTarget,
            expanded = deviceMenuExpanded,
            onExpandedChange = { deviceMenuExpanded = it },
            onSelect = onSelectDevice,
            onSelectIos = onSelectIosTarget,
            showPopOut = showDevicePopOut,
            onPopOut = onPopOutDevice,
        )
    }
}

@Composable
private fun ProxyToolbarIndicator() {
    Row(
        Modifier
            .height(AndyLayout.ControlHeightMd)
            .background(AndyColors.SurfaceHover, RoundedCornerShape(AndyRadius.Control))
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        GlowingDot(isGreen = true, modifier = Modifier.size(AndyLayout.IconMd))
        Text(
            "Proxy",
            color = Green,
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun ActionRunnerSelector(
    config: ActionsConfig,
    selectedProjectId: String?,
    selectedActionId: String?,
    onSelectionChange: (projectId: String, actionId: String?) -> Unit,
    onRunAction: (ActionProject, ProjectAction) -> Unit,
    projectExpanded: Boolean,
    onProjectExpandedChange: (Boolean) -> Unit,
    actionExpanded: Boolean,
    onActionExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val project = remember(config.projects, selectedProjectId) {
        config.projects.firstOrNull { it.id == selectedProjectId } ?: config.projects.firstOrNull()
    }
    val action = remember(project?.actions, selectedActionId) {
        project?.actions?.firstOrNull { it.id == selectedActionId } ?: project?.actions?.firstOrNull()
    }
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        Box {
            Button(
                onClick = { onProjectExpandedChange(true) },
                colors = secondaryButtonColors(),
                shape = RoundedCornerShape(AndyRadius.Control),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = AndySpace.Space2),
                modifier = Modifier.widthIn(min = 132.dp, max = 210.dp),
            ) {
                Text("Prj", color = Rust, fontFamily = DisplayFont, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(AndySpace.Space2))
                Text(project?.name ?: "Project", color = TextPrimary, fontFamily = DisplayFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(
                expanded = projectExpanded,
                onDismissRequest = { onProjectExpandedChange(false) },
                containerColor = AndyColors.SurfaceRaised,
                shape = RoundedCornerShape(AndyRadius.Menu),
            ) {
                config.projects.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.name, color = TextPrimary, fontFamily = DisplayFont, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            val nextActionId = item.actions.firstOrNull()?.id
                            onSelectionChange(item.id, nextActionId)
                            onProjectExpandedChange(false)
                        },
                    )
                }
            }
        }

        Box {
            Button(
                onClick = { onActionExpandedChange(true) },
                enabled = project?.actions?.isNotEmpty() == true,
                colors = secondaryButtonColors(),
                shape = RoundedCornerShape(AndyRadius.Control),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = AndySpace.Space2),
                modifier = Modifier.widthIn(min = 142.dp, max = 230.dp),
            ) {
                Text(action?.let { actionIconMarker(it.icon) } ?: "—", color = Rust, fontFamily = MonoFont, fontSize = 11.sp)
                Spacer(Modifier.width(AndySpace.Space2))
                Text(action?.name ?: "No actions", color = TextPrimary, fontFamily = DisplayFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(
                expanded = actionExpanded,
                onDismissRequest = { onActionExpandedChange(false) },
                containerColor = AndyColors.SurfaceRaised,
                shape = RoundedCornerShape(AndyRadius.Menu),
            ) {
                project?.actions.orEmpty().forEach { item ->
                    DropdownMenuItem(
                        text = { Text("${actionIconMarker(item.icon)}  ${item.name}", color = TextPrimary, fontFamily = DisplayFont, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            val projectId = project?.id
                            if (projectId != null) onSelectionChange(projectId, item.id)
                            onActionExpandedChange(false)
                        },
                    )
                }
            }
        }

        Button(
            onClick = {
                val selectedProject = project
                val selectedAction = action
                if (selectedProject != null && selectedAction != null) {
                    onSelectionChange(selectedProject.id, selectedAction.id)
                    onRunAction(selectedProject, selectedAction)
                }
            },
            enabled = project != null && action != null,
            colors = primaryButtonColors(),
            shape = RoundedCornerShape(AndyRadius.Row),
            contentPadding = PaddingValues(horizontal = AndySpace.Space4, vertical = AndySpace.Space2),
        ) {
            Text("Run", color = TextPrimary, fontFamily = DisplayFont, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun DevicePicker(
    devices: List<AndroidDevice>,
    selectedDevice: AndroidDevice?,
    iosTargets: List<IosTarget>,
    selectedIosTarget: IosTarget?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onSelectIos: (String) -> Unit,
    showPopOut: Boolean = false,
    onPopOut: (String, String) -> Unit = { _, _ -> },
) {
    val activeDevices = remember(devices) {
        devices.filter { it.state == DeviceConnectionState.Online }
    }
    val activeIosTargets = remember(iosTargets) {
        iosTargets.filter { it.isLiveReady }
    }
    Box {
        Button(
            onClick = { onExpandedChange(true) },
            colors = secondaryButtonColors(),
            shape = RoundedCornerShape(AndyRadius.Control),
            contentPadding = PaddingValues(horizontal = AndySpace.Space4, vertical = AndySpace.Space2),
        ) {
            Text("•", color = Green, fontSize = 16.sp)
            Spacer(Modifier.width(AndySpace.Space2))
            Text(
                selectedIosTarget?.displayName ?: selectedDevice?.displayName ?: "No device",
                color = TextPrimary,
                fontFamily = DisplayFont,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            containerColor = AndyColors.SurfaceRaised,
            shape = RoundedCornerShape(AndyRadius.Menu),
        ) {
            if (activeDevices.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Android", color = TextSecondary, fontFamily = DisplayFont, fontSize = 11.sp) },
                    onClick = {},
                    enabled = false,
                )
                activeDevices.forEach { device ->
                    DropdownMenuItem(
                        text = {
                            DeviceMenuRow(
                                title = device.displayName,
                                subtitle = null,
                                showPopOut = showPopOut,
                                onPopOut = {
                                    onExpandedChange(false)
                                    onPopOut(device.serial, device.displayName)
                                },
                            )
                        },
                        onClick = {
                            onSelect(device.serial)
                            onExpandedChange(false)
                        },
                    )
                }
            }
            if (activeIosTargets.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("iOS", color = TextSecondary, fontFamily = DisplayFont, fontSize = 11.sp) },
                    onClick = {},
                    enabled = false,
                )
                activeIosTargets.forEach { target ->
                    val subtitle = when (target.kind) {
                        IosTargetKind.Physical -> "USB"
                        IosTargetKind.Simulator -> "Booted"
                    }
                    DropdownMenuItem(
                        text = {
                            DeviceMenuRow(
                                title = target.displayName,
                                subtitle = subtitle,
                                showPopOut = showPopOut,
                                onPopOut = {
                                    onExpandedChange(false)
                                    onPopOut(target.udid, target.displayName)
                                },
                            )
                        },
                        onClick = {
                            onSelectIos(target.udid)
                            onExpandedChange(false)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceMenuRow(
    title: String,
    subtitle: String?,
    showPopOut: Boolean,
    onPopOut: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontFamily = DisplayFont)
            if (subtitle != null) {
                Text(subtitle, color = TextSecondary, fontFamily = DisplayFont, fontSize = 11.sp)
            }
        }
        if (showPopOut) {
            Box(
                Modifier
                    .size(AndyLayout.ToolbarButtonSize)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onPopOut,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(Res.drawable.hardware_pop_out),
                    contentDescription = "Pop out mirror",
                    modifier = Modifier.size(AndyLayout.IconMd),
                    colorFilter = ColorFilter.tint(Cyan),
                )
            }
        }
    }
}
