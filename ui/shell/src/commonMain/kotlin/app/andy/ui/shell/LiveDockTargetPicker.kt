package app.andy.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.andy.model.AndroidDevice
import app.andy.model.AndroidDisplay
import app.andy.model.DeviceConnectionState
import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.MirrorTarget

/**
 * Device list for a Live dock tab / leaf, rendered inside [ChromeFlyout] so the menu lives in
 * the layout tree and never paints under SwingPanel/Metal mirrors.
 */
@Composable
internal fun LiveDevicePickerPanel(
    devices: List<AndroidDevice>,
    iosTargets: List<IosTarget>,
    deviceLabels: Map<String, String>,
    deviceDisplays: Map<String, List<AndroidDisplay>> = emptyMap(),
    takenIosKinds: Set<IosTargetKind> = emptySet(),
    onSelectTarget: (String) -> Unit,
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
                        supporting = deviceLabels[device.serial]?.let { device.displayName }
                            ?: device.serial.takeIf { it != title },
                        onClick = { onSelectTarget(device.serial) },
                    )
                    // Dual-screen devices expose extra logical displays. Listing them here is the
                    // only way back to a secondary pane after closing it: the dock split creates
                    // the pane, but the picker must still be able to rebind it.
                    deviceDisplays[device.serial]
                        ?.filter { it.id != 0 && it.isEnabled }
                        ?.forEach { display ->
                            ChromeFlyoutRow(
                                label = "$title · Display ${display.id}",
                                supporting = display.name?.takeIf { it.isNotBlank() }
                                    ?: "Secondary screen",
                                modifier = Modifier.padding(start = 16.dp),
                                onClick = {
                                    onSelectTarget(MirrorTarget.id(device.serial, display.id))
                                },
                            )
                        }
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
                    // The native hub has one decoder slot per iOS kind, so a second same-kind
                    // source cannot run alongside the one already bound elsewhere in the dock.
                    val unavailable = target.kind in takenIosKinds
                    ChromeFlyoutRow(
                        label = title,
                        supporting = if (unavailable) "$subtitle · already in use" else subtitle,
                        enabled = !unavailable,
                        onClick = { onSelectTarget(target.udid) },
                    )
                }
            }
        }
    }
}
