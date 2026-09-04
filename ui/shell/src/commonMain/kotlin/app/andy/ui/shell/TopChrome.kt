package app.andy.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.AndyDestination
import app.andy.showsSideChat
import app.andy.model.ActionProject
import app.andy.model.ActionsConfig
import app.andy.model.AndroidDevice
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.ProjectAction
import app.andy.ui.actions.ActionIcon
import app.andy.ui.components.AndyDropdownTrigger
import app.andy.ui.components.GhostButton
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.TextButton
import app.andy.ui.components.TopNav
import app.andy.ui.components.TopNavHeading
import app.andy.ui.components.accentTextButtonColors
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.network.GlowingDot
import app.andy.ui.components.Lucide
import app.andy.ui.components.LucideIcon

@Composable
internal fun TopChrome(
    destination: AndyDestination,
    selectedDevice: AndroidDevice?,
    devices: List<AndroidDevice>,
    iosTargets: List<IosTarget>,
    selectedIosTarget: IosTarget?,
    /** Friendly display names keyed by serial/udid (§C.5), shown in the device picker when set. */
    deviceLabels: Map<String, String> = emptyMap(),
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
    onProxyClick: () -> Unit = {},
    showLocalServers: Boolean = false,
    localServersContent: @Composable (
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
    ) -> Unit = { _, _ -> },
    localServersFlyout: @Composable (onDismiss: () -> Unit) -> Unit = {},
    rightPaneOpen: Boolean = false,
    bottomPaneOpen: Boolean = false,
    projectPaneOpen: Boolean = true,
    dockLandingFor: DockPlacement? = null,
    onPlacementIconClick: (DockPlacement) -> Unit = {},
    onProjectPaneClick: () -> Unit = {},
    onDismissDockLanding: () -> Unit = {},
    onOpenDockKind: (DockPlacement, DockTabKind) -> Unit = { _, _ -> },
    dockLayoutMenu: DockLayoutMenu? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val hasActionRunnerControls = actionConfig.projects.any { it.actions.isNotEmpty() }
    val actionProject = remember(actionConfig.projects, selectedActionProjectId) {
        actionConfig.projects.firstOrNull { it.id == selectedActionProjectId } ?: actionConfig.projects.firstOrNull()
    }
    val action = remember(actionProject?.actions, selectedActionId) {
        actionProject?.actions?.firstOrNull { it.id == selectedActionId } ?: actionProject?.actions?.firstOrNull()
    }
    var flyout by remember { mutableStateOf<ChromeFlyoutKind?>(null) }

    // Dock landing is owned by ShellState (placement icons); keep local flyouts exclusive with it.
    val effectiveFlyout = if (dockLandingFor != null) ChromeFlyoutKind.DockLanding else flyout
    // Hold the last open kind so exit animation still has content to measure (when null the
    // branch would otherwise collapse to empty and jump shut).
    var renderedFlyout by remember { mutableStateOf<ChromeFlyoutKind?>(null) }
    if (effectiveFlyout != null) renderedFlyout = effectiveFlyout

    fun openFlyout(kind: ChromeFlyoutKind) {
        if (dockLandingFor != null) onDismissDockLanding()
        flyout = if (flyout == kind) null else kind
    }

    fun closeFlyout() {
        flyout = null
        if (dockLandingFor != null) onDismissDockLanding()
    }

    val headingTitle = if (selectedIosTarget != null && destination == AndyDestination.Logcat) {
        "Logs"
    } else {
        destination.label
    }
    val headingSubtitle = selectedIosTarget?.let { deviceLabels[it.udid] ?: it.displayName }
        ?: selectedDevice?.let {
            "${deviceLabels[it.serial] ?: it.displayName} · API ${it.apiLevel ?: "—"} · ${it.abi ?: "—"}"
        }
        ?: "No device selected"

    Column(Modifier.fillMaxWidth().background(AndyColors.ContentBg)) {
        TopNav(
            modifier = Modifier.fillMaxWidth(),
            heading = {
                TopNavHeading(
                    title = headingTitle,
                    subtitle = headingSubtitle,
                )
            },
            startContent = {},
            endContent = {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AndySpace.Space1),
                ) {
                    actions()
                    if (showLocalServers) {
                        localServersContent(
                            effectiveFlyout == ChromeFlyoutKind.LocalServers,
                        ) { expanded ->
                            if (expanded) openFlyout(ChromeFlyoutKind.LocalServers)
                            else if (flyout == ChromeFlyoutKind.LocalServers) flyout = null
                        }
                    }
                    if (destination != AndyDestination.Network && proxyRunning) {
                        ProxyToolbarIndicator(onClick = onProxyClick)
                    }
                    if (hasActionRunnerControls) {
                        ActionRunnerSelector(
                            project = actionProject,
                            action = action,
                            expandedFlyout = effectiveFlyout,
                            onToggleFlyout = ::openFlyout,
                            onSelectionChange = onActionSelectionChange,
                            onRunAction = onRunAction,
                        )
                    }
                    if (selectedDevice?.kind == DeviceKind.Emulator && selectedDevice.state == DeviceConnectionState.Online) {
                        OutlinedButton(
                            onClick = { onStopEmulator(selectedDevice) },
                            enabled = stoppingEmulatorSerial != selectedDevice.serial,
                            shape = AndyShape.Interactive,
                            contentPadding = PaddingValues(horizontal = AndySpace.Space4, vertical = AndySpace.Space2),
                        ) {
                            Text(
                                if (stoppingEmulatorSerial == selectedDevice.serial) "Stopping" else "Stop Emulator",
                                fontFamily = DisplayFont,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    GhostButton(
                        onClick = onRefresh,
                        contentPadding = PaddingValues(horizontal = AndySpace.Space4, vertical = AndySpace.Space2),
                    ) {
                        Text("Refresh", color = TextPrimary, fontFamily = DisplayFont, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    DevicePickerMenu(
                        selectedDevice = selectedDevice,
                        selectedIosTarget = selectedIosTarget,
                        deviceLabels = deviceLabels,
                        expandedFlyout = effectiveFlyout,
                        onToggleFlyout = ::openFlyout,
                    )
                    if (destination == AndyDestination.Actions) {
                        ProjectPaneToggle(
                            selected = projectPaneOpen,
                            onClick = onProjectPaneClick,
                        )
                    }
                    PanePlacementToggle(
                        placement = DockPlacement.Bottom,
                        selected = bottomPaneOpen,
                        onClick = {
                            flyout = null
                            onPlacementIconClick(DockPlacement.Bottom)
                        },
                    )
                    PanePlacementToggle(
                        placement = DockPlacement.Right,
                        selected = rightPaneOpen,
                        onClick = {
                            flyout = null
                            onPlacementIconClick(DockPlacement.Right)
                        },
                    )
                }
            },
        )

        ChromeFlyout(
            visible = effectiveFlyout != null,
            contentAlignment = if (renderedFlyout == ChromeFlyoutKind.DockLanding) {
                Alignment.End
            } else {
                Alignment.Start
            },
            contentKey = renderedFlyout,
        ) {
            when (renderedFlyout) {
                ChromeFlyoutKind.LocalServers -> localServersFlyout(::closeFlyout)
                ChromeFlyoutKind.DockLanding -> DockLandingPanel(
                    onSelect = { kind ->
                        val placement = dockLandingFor ?: return@DockLandingPanel
                        onOpenDockKind(placement, kind)
                        closeFlyout()
                    },
                    showChat = destination.showsSideChat,
                    layoutMenu = dockLayoutMenu?.let { menu ->
                        menu.copy(
                            onLoad = { id -> menu.onLoad(id); closeFlyout() },
                            onSave = { name -> menu.onSave(name); closeFlyout() },
                        )
                    },
                )
                ChromeFlyoutKind.DevicePicker -> DevicePickerPanel(
                    devices = devices,
                    iosTargets = iosTargets,
                    deviceLabels = deviceLabels,
                    showPopOut = showDevicePopOut,
                    onSelectDevice = { serial ->
                        onSelectDevice(serial)
                        closeFlyout()
                    },
                    onSelectIosTarget = { udid ->
                        onSelectIosTarget(udid)
                        closeFlyout()
                    },
                    onPopOutDevice = { id, title ->
                        closeFlyout()
                        onPopOutDevice(id, title)
                    },
                )
                ChromeFlyoutKind.ActionProjectPicker -> ActionProjectPickerPanel(
                    projects = actionConfig.projects,
                    onSelect = { project ->
                        onActionSelectionChange(project.id, null)
                        closeFlyout()
                    },
                )
                ChromeFlyoutKind.ActionPicker -> ActionPickerPanel(
                    project = actionProject,
                    onSelect = { selected ->
                        val projectId = actionProject?.id
                        if (projectId != null) onActionSelectionChange(projectId, selected.id)
                        closeFlyout()
                    },
                )
                null -> Unit
            }
        }
    }
}

@Composable
private fun ProxyToolbarIndicator(onClick: () -> Unit) {
    Row(
        Modifier
            .height(AndyLayout.ControlHeightMd)
            .background(AndyColors.SurfaceHover, AndyShape.Interactive)
            .clickable(onClick = onClick)
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
    project: ActionProject?,
    action: ProjectAction?,
    expandedFlyout: ChromeFlyoutKind?,
    onToggleFlyout: (ChromeFlyoutKind) -> Unit,
    onSelectionChange: (projectId: String, actionId: String?) -> Unit,
    onRunAction: (ActionProject, ProjectAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        AndyDropdownTrigger(
            label = project?.name ?: "Project",
            expanded = expandedFlyout == ChromeFlyoutKind.ActionProjectPicker,
            onClick = { onToggleFlyout(ChromeFlyoutKind.ActionProjectPicker) },
            modifier = Modifier.widthIn(min = 132.dp, max = 210.dp),
            prefix = {
                Text("Prj", color = Rust, fontFamily = DisplayFont, fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 11.sp)
                Spacer(Modifier.width(AndySpace.Space2))
            },
            contentDescription = "Select project",
        )

        AndyDropdownTrigger(
            label = action?.name ?: "No actions",
            expanded = expandedFlyout == ChromeFlyoutKind.ActionPicker,
            enabled = project?.actions?.isNotEmpty() == true,
            onClick = { onToggleFlyout(ChromeFlyoutKind.ActionPicker) },
            modifier = Modifier.widthIn(min = 142.dp, max = 230.dp),
            prefix = {
                if (action != null) {
                    ActionIcon(action.icon, Rust, Modifier.size(12.dp))
                } else {
                    Text("—", color = Rust, fontFamily = MonoFont, fontSize = 11.sp, lineHeight = 11.sp)
                }
                Spacer(Modifier.width(AndySpace.Space2))
            },
            contentDescription = "Select action",
        )

        TextButton(
            onClick = {
                val selectedProject = project
                val selectedAction = action
                if (selectedProject != null && selectedAction != null) {
                    onSelectionChange(selectedProject.id, selectedAction.id)
                    onRunAction(selectedProject, selectedAction)
                }
            },
            enabled = project != null && action != null,
            colors = accentTextButtonColors(),
        ) {
            Text("Run", fontSize = 12.sp)
        }
    }
}

@Composable
private fun ActionProjectPickerPanel(
    projects: List<ActionProject>,
    onSelect: (ActionProject) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        ChromeFlyoutSectionLabel("Projects")
        if (projects.isEmpty()) {
            ChromeFlyoutEmpty("No projects")
        } else {
            projects.forEach { project ->
                ChromeFlyoutRow(
                    label = project.name,
                    onClick = { onSelect(project) },
                )
            }
        }
    }
}

@Composable
private fun ActionPickerPanel(
    project: ActionProject?,
    onSelect: (ProjectAction) -> Unit,
) {
    val actions = project?.actions.orEmpty()
    Column(Modifier.fillMaxWidth()) {
        ChromeFlyoutSectionLabel("Actions")
        if (actions.isEmpty()) {
            ChromeFlyoutEmpty("No actions")
        } else {
            actions.forEach { action ->
                ChromeFlyoutRow(
                    label = action.name,
                    onClick = { onSelect(action) },
                    leading = {
                        ActionIcon(action.icon, Rust, Modifier.size(16.dp))
                    },
                )
            }
        }
    }
}

@Composable
private fun DevicePickerMenu(
    selectedDevice: AndroidDevice?,
    selectedIosTarget: IosTarget?,
    deviceLabels: Map<String, String>,
    expandedFlyout: ChromeFlyoutKind?,
    onToggleFlyout: (ChromeFlyoutKind) -> Unit,
) {
    val label = selectedIosTarget?.let { deviceLabels[it.udid] ?: it.displayName }
        ?: selectedDevice?.let { deviceLabels[it.serial] ?: it.displayName }
        ?: "No device"
    AndyDropdownTrigger(
        label = label,
        expanded = expandedFlyout == ChromeFlyoutKind.DevicePicker,
        onClick = { onToggleFlyout(ChromeFlyoutKind.DevicePicker) },
        modifier = Modifier.widthIn(min = 140.dp, max = 240.dp),
        prefix = {
            Text("•", color = Green, fontSize = 16.sp)
            Spacer(Modifier.width(AndySpace.Space1))
        },
        contentDescription = "Select device",
    )
}

@Composable
private fun DevicePickerPanel(
    devices: List<AndroidDevice>,
    iosTargets: List<IosTarget>,
    deviceLabels: Map<String, String>,
    showPopOut: Boolean,
    onSelectDevice: (String) -> Unit,
    onSelectIosTarget: (String) -> Unit,
    onPopOutDevice: (String, String) -> Unit,
) {
    val activeDevices = remember(devices) {
        devices.filter { it.state == DeviceConnectionState.Online }
    }
    val activeIosTargets = remember(iosTargets) {
        iosTargets.filter { it.isLiveReady }
    }

    Column(Modifier.fillMaxWidth()) {
        if (activeDevices.isEmpty() && activeIosTargets.isEmpty()) {
            ChromeFlyoutEmpty("No devices online")
        } else {
            if (activeDevices.isNotEmpty()) {
                ChromeFlyoutSectionLabel("Android")
                activeDevices.forEach { device ->
                    val title = deviceLabels[device.serial] ?: device.displayName
                    ChromeFlyoutRow(
                        label = title,
                        supporting = deviceLabels[device.serial]?.let { device.displayName },
                        onClick = { onSelectDevice(device.serial) },
                        trailing = if (showPopOut) {
                            {
                                DevicePopOutButton {
                                    onPopOutDevice(device.serial, title)
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
            if (activeIosTargets.isNotEmpty()) {
                ChromeFlyoutSectionLabel("iOS")
                activeIosTargets.forEach { target ->
                    val subtitle = when (target.kind) {
                        IosTargetKind.Physical -> "USB"
                        IosTargetKind.Simulator -> "Booted"
                    }
                    val title = deviceLabels[target.udid] ?: target.displayName
                    ChromeFlyoutRow(
                        label = title,
                        supporting = subtitle,
                        onClick = { onSelectIosTarget(target.udid) },
                        trailing = if (showPopOut) {
                            {
                                DevicePopOutButton {
                                    onPopOutDevice(target.udid, target.displayName)
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DevicePopOutButton(onPopOut: () -> Unit) {
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
        LucideIcon(
            Lucide.SquareArrowOutUpRight,
            Cyan,
            Modifier.size(AndyLayout.IconMd),
            contentDescription = "Pop out mirror",
        )
    }
}
