package app.andy.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.components.secondaryButtonColors
import app.andy.ui.network.GlowingDot
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

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
    actionConfig: ActionsConfig,
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
        Modifier.fillMaxWidth().height(62.dp)
            .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R3))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(260.dp)) {
            Text(destination.label.lowercase(), color = AndyColors.Neutral100, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp)
            Text(selectedIosTarget?.displayName ?: selectedDevice?.let { "${it.displayName} / api ${it.apiLevel ?: "-"} / ${it.abi ?: "-"}" } ?: "no device selected", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        actions()
        if (destination != AndyDestination.Network && proxyRunning) {
            ProxyToolbarIndicator()
            Spacer(Modifier.width(10.dp))
        }
        if (hasActionRunnerControls) {
            ActionRunnerSelector(
                config = actionConfig,
                onRunAction = onRunAction,
                projectExpanded = projectMenuExpanded,
                onProjectExpandedChange = { projectMenuExpanded = it },
                actionExpanded = actionMenuExpanded,
                onActionExpandedChange = { actionMenuExpanded = it },
            )
            Spacer(Modifier.width(10.dp))
        }
        if (selectedDevice?.kind == DeviceKind.Emulator && selectedDevice.state == DeviceConnectionState.Online) {
            OutlinedButton(
                onClick = { onStopEmulator(selectedDevice) },
                enabled = stoppingEmulatorSerial != selectedDevice.serial,
                shape = RoundedCornerShape(AndyRadius.R2),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(if (stoppingEmulatorSerial == selectedDevice.serial) "Stopping" else "Stop emulator", fontSize = 12.sp)
            }
            Spacer(Modifier.width(10.dp))
        }
        Button(onClick = onRefresh, colors = primaryButtonColors(), shape = RoundedCornerShape(AndyRadius.R2), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Refresh", color = TextPrimary, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        DevicePicker(
            devices = devices,
            selectedDevice = selectedDevice,
            iosTargets = iosTargets,
            selectedIosTarget = selectedIosTarget,
            expanded = deviceMenuExpanded,
            onExpandedChange = { deviceMenuExpanded = it },
            onSelect = onSelectDevice,
            onSelectIos = onSelectIosTarget,
        )
    }
}

@Composable
private fun ProxyToolbarIndicator() {
    Row(
        Modifier.height(30.dp)
            .background(Green.copy(alpha = 0.12f), RoundedCornerShape(AndyRadius.Pill))
            .border(1.dp, Green.copy(alpha = 0.42f), RoundedCornerShape(AndyRadius.Pill))
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        GlowingDot(isGreen = true, modifier = Modifier.size(14.dp))
        Text("proxy", color = AndyColors.GreenSoft, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
    }
}

@Composable
private fun ActionRunnerSelector(
    config: ActionsConfig,
    onRunAction: (ActionProject, ProjectAction) -> Unit,
    projectExpanded: Boolean,
    onProjectExpandedChange: (Boolean) -> Unit,
    actionExpanded: Boolean,
    onActionExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedProjectId by remember { mutableStateOf<String?>(null) }
    var selectedActionId by remember { mutableStateOf<String?>(null) }

    val project = remember(config.projects, selectedProjectId) {
        config.projects.firstOrNull { it.id == selectedProjectId } ?: config.projects.firstOrNull()
    }
    val action = remember(project?.actions, selectedActionId) {
        project?.actions?.firstOrNull { it.id == selectedActionId } ?: project?.actions?.firstOrNull()
    }
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            Button(
                onClick = { onProjectExpandedChange(true) },
                colors = secondaryButtonColors(),
                shape = RoundedCornerShape(AndyRadius.R2),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.widthIn(min = 132.dp, max = 210.dp),
            ) {
                Text("prj", color = Rust, fontFamily = MonoFont, fontSize = 10.sp)
                Spacer(Modifier.width(6.dp))
                Text(project?.name ?: "project", color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = projectExpanded, onDismissRequest = { onProjectExpandedChange(false) }, containerColor = AndyColors.Neutral750) {
                config.projects.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.name, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            selectedProjectId = item.id
                            selectedActionId = item.actions.firstOrNull()?.id
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
                shape = RoundedCornerShape(AndyRadius.R2),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.widthIn(min = 142.dp, max = 230.dp),
            ) {
                Text(action?.let { actionIconMarker(it.icon) } ?: "--", color = Rust, fontFamily = MonoFont, fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
                Text(action?.name ?: "no actions", color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = actionExpanded, onDismissRequest = { onActionExpandedChange(false) }, containerColor = AndyColors.Neutral750) {
                project?.actions.orEmpty().forEach { item ->
                    DropdownMenuItem(
                        text = { Text("${actionIconMarker(item.icon)}  ${item.name}", color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            selectedActionId = item.id
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
                    onRunAction(selectedProject, selectedAction)
                }
            },
            enabled = project != null && action != null,
            colors = primaryButtonColors(),
            shape = RoundedCornerShape(AndyRadius.R2),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text("run", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
) {
    val activeDevices = remember(devices) {
        devices.filter { it.state == DeviceConnectionState.Online }
    }
    val activeIosTargets = remember(iosTargets) {
        iosTargets.filter { it.isLiveReady }
    }
    Box {
        Button(onClick = { onExpandedChange(true) }, colors = secondaryButtonColors(), shape = RoundedCornerShape(AndyRadius.R2), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
            Text("•", color = Green, fontSize = 18.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                selectedIosTarget?.displayName ?: selectedDevice?.displayName ?: "no device",
                color = TextPrimary,
                fontFamily = MonoFont,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }, containerColor = AndyColors.Neutral750) {
            if (activeDevices.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Android", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp) },
                    onClick = {},
                    enabled = false,
                )
                activeDevices.forEach { device ->
                    DropdownMenuItem(text = { Text(device.displayName, color = TextPrimary) }, onClick = {
                        onSelect(device.serial)
                        onExpandedChange(false)
                    })
                }
            }
            if (activeIosTargets.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("iOS", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp) },
                    onClick = {},
                    enabled = false,
                )
                activeIosTargets.forEach { target ->
                    val subtitle = when (target.kind) {
                        IosTargetKind.Physical -> "usb"
                        IosTargetKind.Simulator -> "booted"
                    }
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(target.displayName, color = TextPrimary)
                                Text(subtitle, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                            }
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
