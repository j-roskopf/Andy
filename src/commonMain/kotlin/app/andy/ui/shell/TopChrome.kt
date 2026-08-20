package app.andy.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import app.andy.ui.actions.actionIconMarker
import app.andy.ui.components.Button
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.components.secondaryButtonColors
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.hardware_pop_out
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
import org.jetbrains.compose.resources.painterResource

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
    actions: @Composable RowScope.() -> Unit = {},
) {
    val hasActionRunnerControls = actionConfig.projects.any { it.actions.isNotEmpty() }
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

    Column(Modifier.fillMaxWidth().background(AndyColors.ContentBg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(AndyLayout.ToolbarHeight)
                .padding(horizontal = AndySpace.Space5),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier
                    .widthIn(min = 72.dp, max = 180.dp)
                    .padding(end = AndySpace.Space4)
                    .clickable(onClick = ::closeFlyout),
            ) {
                Text(
                    if (selectedIosTarget != null && destination == AndyDestination.Logcat) {
                        "Logs"
                    } else {
                        destination.label
                    },
                    color = TextPrimary,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    selectedIosTarget?.let { deviceLabels[it.udid] ?: it.displayName }
                        ?: selectedDevice?.let {
                            "${deviceLabels[it.serial] ?: it.displayName} · API ${it.apiLevel ?: "—"} · ${it.abi ?: "—"}"
                        }
                        ?: "No device selected",
                    color = AndyColors.TextTertiary,
                    fontFamily = DisplayFont,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Keep controls flush-right when they fit; scroll instead of compressing on narrow widths.
            Box(
                Modifier.weight(1f),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actions()
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                    Spacer(Modifier.width(AndySpace.Space3))
                    if (showLocalServers) {
                        localServersContent(
                            effectiveFlyout == ChromeFlyoutKind.LocalServers,
                        ) { expanded ->
                            if (expanded) openFlyout(ChromeFlyoutKind.LocalServers)
                            else if (flyout == ChromeFlyoutKind.LocalServers) flyout = null
                        }
                        Spacer(Modifier.width(AndySpace.Space3))
                    }
                    if (destination != AndyDestination.Network && proxyRunning) {
                        ProxyToolbarIndicator(onClick = onProxyClick)
                        Spacer(Modifier.width(AndySpace.Space3))
                    }
                    if (hasActionRunnerControls) {
                        ActionRunnerSelector(
                            config = actionConfig,
                            selectedProjectId = selectedActionProjectId,
                            selectedActionId = selectedActionId,
                            onSelectionChange = onActionSelectionChange,
                            onRunAction = onRunAction,
                            onProjectClick = { openFlyout(ChromeFlyoutKind.Project) },
                            onActionClick = { openFlyout(ChromeFlyoutKind.Action) },
                        )
                        Spacer(Modifier.width(AndySpace.Space3))
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
                        Spacer(Modifier.width(AndySpace.Space3))
                    }
                    Button(
                        onClick = onRefresh,
                        colors = secondaryButtonColors(),
                        shape = AndyShape.Interactive,
                        contentPadding = PaddingValues(horizontal = AndySpace.Space4, vertical = AndySpace.Space2),
                    ) {
                        Text("Refresh", color = TextPrimary, fontFamily = DisplayFont, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(AndySpace.Space3))
                    DevicePickerButton(
                        selectedDevice = selectedDevice,
                        selectedIosTarget = selectedIosTarget,
                        deviceLabels = deviceLabels,
                        onClick = { openFlyout(ChromeFlyoutKind.Device) },
                    )
                }
            }
        }

        ChromeFlyout(
            visible = effectiveFlyout != null,
            contentAlignment = if (renderedFlyout == ChromeFlyoutKind.DockLanding) {
                Alignment.End
            } else {
                Alignment.Start
            },
        ) {
            when (renderedFlyout) {
                ChromeFlyoutKind.Project -> ProjectFlyoutContent(
                    config = actionConfig,
                    onSelect = { item ->
                        onActionSelectionChange(item.id, item.actions.firstOrNull()?.id)
                        closeFlyout()
                    },
                )
                ChromeFlyoutKind.Action -> {
                    val project = actionConfig.projects.firstOrNull { it.id == selectedActionProjectId }
                        ?: actionConfig.projects.firstOrNull()
                    ActionFlyoutContent(
                        actions = project?.actions.orEmpty(),
                        onSelect = { item ->
                            val projectId = project?.id
                            if (projectId != null) onActionSelectionChange(projectId, item.id)
                            closeFlyout()
                        },
                    )
                }
                ChromeFlyoutKind.Device -> DeviceFlyoutContent(
                    devices = devices,
                    iosTargets = iosTargets,
                    deviceLabels = deviceLabels,
                    showPopOut = showDevicePopOut,
                    onSelect = {
                        onSelectDevice(it)
                        closeFlyout()
                    },
                    onSelectIos = {
                        onSelectIosTarget(it)
                        closeFlyout()
                    },
                    onPopOut = { id, name ->
                        closeFlyout()
                        onPopOutDevice(id, name)
                    },
                )
                ChromeFlyoutKind.LocalServers -> localServersFlyout(::closeFlyout)
                ChromeFlyoutKind.DockLanding -> DockLandingPanel(
                    onSelect = { kind ->
                        val placement = dockLandingFor ?: return@DockLandingPanel
                        onOpenDockKind(placement, kind)
                        closeFlyout()
                    },
                    showChat = destination.showsSideChat,
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
    config: ActionsConfig,
    selectedProjectId: String?,
    selectedActionId: String?,
    onSelectionChange: (projectId: String, actionId: String?) -> Unit,
    onRunAction: (ActionProject, ProjectAction) -> Unit,
    onProjectClick: () -> Unit,
    onActionClick: () -> Unit,
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
        Button(
            onClick = onProjectClick,
            colors = secondaryButtonColors(),
            shape = AndyShape.Interactive,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = AndySpace.Space2),
            modifier = Modifier.widthIn(min = 132.dp, max = 210.dp),
        ) {
            Text("Prj", color = Rust, fontFamily = DisplayFont, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(AndySpace.Space2))
            Text(
                project?.name ?: "Project",
                color = TextPrimary,
                fontFamily = DisplayFont,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Button(
            onClick = onActionClick,
            enabled = project?.actions?.isNotEmpty() == true,
            colors = secondaryButtonColors(),
            shape = AndyShape.Interactive,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = AndySpace.Space2),
            modifier = Modifier.widthIn(min = 142.dp, max = 230.dp),
        ) {
            Text(action?.let { actionIconMarker(it.icon) } ?: "—", color = Rust, fontFamily = MonoFont, fontSize = 11.sp)
            Spacer(Modifier.width(AndySpace.Space2))
            Text(
                action?.name ?: "No actions",
                color = TextPrimary,
                fontFamily = DisplayFont,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
            shape = AndyShape.Interactive,
            contentPadding = PaddingValues(horizontal = AndySpace.Space4, vertical = AndySpace.Space2),
        ) {
            Text("Run", fontFamily = DisplayFont, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ProjectFlyoutContent(
    config: ActionsConfig,
    onSelect: (ActionProject) -> Unit,
) {
    if (config.projects.isEmpty()) {
        ChromeFlyoutEmpty("No projects")
        return
    }
    config.projects.forEach { item ->
        ChromeFlyoutRow(
            label = item.name,
            onClick = { onSelect(item) },
        )
    }
}

@Composable
private fun ActionFlyoutContent(
    actions: List<ProjectAction>,
    onSelect: (ProjectAction) -> Unit,
) {
    if (actions.isEmpty()) {
        ChromeFlyoutEmpty("No actions")
        return
    }
    actions.forEach { item ->
        ChromeFlyoutRow(
            label = item.name,
            onClick = { onSelect(item) },
            leading = {
                Text(actionIconMarker(item.icon), color = Rust, fontFamily = MonoFont, fontSize = 11.sp)
            },
        )
    }
}

@Composable
private fun DevicePickerButton(
    selectedDevice: AndroidDevice?,
    selectedIosTarget: IosTarget?,
    deviceLabels: Map<String, String> = emptyMap(),
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        colors = secondaryButtonColors(),
        shape = AndyShape.Interactive,
        contentPadding = PaddingValues(horizontal = AndySpace.Space4, vertical = AndySpace.Space2),
    ) {
        Text("•", color = Green, fontSize = 16.sp)
        Spacer(Modifier.width(AndySpace.Space2))
        Text(
            selectedIosTarget?.let { deviceLabels[it.udid] ?: it.displayName }
                ?: selectedDevice?.let { deviceLabels[it.serial] ?: it.displayName }
                ?: "No device",
            color = TextPrimary,
            fontFamily = DisplayFont,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DeviceFlyoutContent(
    devices: List<AndroidDevice>,
    iosTargets: List<IosTarget>,
    deviceLabels: Map<String, String> = emptyMap(),
    showPopOut: Boolean = false,
    onSelect: (String) -> Unit,
    onSelectIos: (String) -> Unit,
    onPopOut: (String, String) -> Unit = { _, _ -> },
) {
    val activeDevices = remember(devices) {
        devices.filter { it.state == DeviceConnectionState.Online }
    }
    val activeIosTargets = remember(iosTargets) {
        iosTargets.filter { it.isLiveReady }
    }
    if (activeDevices.isEmpty() && activeIosTargets.isEmpty()) {
        ChromeFlyoutEmpty("No devices online")
        return
    }
    if (activeDevices.isNotEmpty()) {
        ChromeFlyoutSectionLabel("Android")
        activeDevices.forEach { device ->
            val title = deviceLabels[device.serial] ?: device.displayName
            ChromeFlyoutRow(
                label = title,
                supporting = deviceLabels[device.serial]?.let { device.displayName },
                onClick = { onSelect(device.serial) },
                trailing = if (showPopOut) {
                    {
                        DevicePopOutButton {
                            onPopOut(device.serial, device.displayName)
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
            ChromeFlyoutRow(
                label = deviceLabels[target.udid] ?: target.displayName,
                supporting = subtitle,
                onClick = { onSelectIos(target.udid) },
                trailing = if (showPopOut) {
                    {
                        DevicePopOutButton {
                            onPopOut(target.udid, target.displayName)
                        }
                    }
                } else {
                    null
                },
            )
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
        Image(
            painter = painterResource(Res.drawable.hardware_pop_out),
            contentDescription = "Pop out mirror",
            modifier = Modifier.size(AndyLayout.IconMd),
            colorFilter = ColorFilter.tint(Cyan),
        )
    }
}
