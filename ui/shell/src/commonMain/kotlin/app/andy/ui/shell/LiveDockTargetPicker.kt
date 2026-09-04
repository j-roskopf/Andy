package app.andy.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.andy.model.AndroidDevice
import app.andy.model.DeviceConnectionState
import app.andy.model.IosTarget
import app.andy.model.IosTargetKind

/**
 * Device list for a Live dock tab / leaf, rendered inside [ChromeFlyout] so the menu lives in
 * the layout tree and never paints under SwingPanel/Metal mirrors.
 */
@Composable
internal fun LiveDevicePickerPanel(
    devices: List<AndroidDevice>,
    iosTargets: List<IosTarget>,
    deviceLabels: Map<String, String>,
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
                        onClick = { onSelectTarget(target.udid) },
                    )
                }
            }
        }
    }
}
