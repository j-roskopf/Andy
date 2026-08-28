package app.andy.ui.shell

import app.andy.model.AndroidDevice
import app.andy.model.DeviceConnectionState
import app.andy.model.IosTarget

internal data class DeviceSelection(
    val selectedSerial: String?,
    val selectedIosUdid: String?,
)

/**
 * Keeps Live/device chrome pointed at a live target when ADB membership changes:
 * - disconnect → next online Android (or empty)
 * - plug into an empty slate (no prior online Android, nothing selected) → auto-select
 * - preserves a still-valid iOS or Android selection
 */
internal fun reconcileDeviceSelection(
    previousOnlineAndroidSerials: Set<String>,
    devices: List<AndroidDevice>,
    iosTargets: List<IosTarget>,
    selectedSerial: String?,
    selectedIosUdid: String?,
): DeviceSelection {
    fun iosAvailable(udid: String) = iosTargets.any { it.udid == udid && it.isLiveReady }
    fun androidAvailable(serial: String) =
        devices.any { it.serial == serial && it.state == DeviceConnectionState.Online }
    val onlineAndroid = devices
        .filter { it.state == DeviceConnectionState.Online }
        .map { it.serial }

    val ios = selectedIosUdid
    if (ios != null) {
        if (iosAvailable(ios)) return DeviceSelection(selectedSerial = null, selectedIosUdid = ios)
        return DeviceSelection(selectedSerial = onlineAndroid.firstOrNull(), selectedIosUdid = null)
    }

    val android = selectedSerial
    if (android != null) {
        if (androidAvailable(android)) return DeviceSelection(selectedSerial = android, selectedIosUdid = null)
        return DeviceSelection(selectedSerial = onlineAndroid.firstOrNull(), selectedIosUdid = null)
    }

    // Nothing selected: only auto-pick when the online Android set was previously empty
    // (first plug / first emulator online), so we don't steal an intentional empty selection
    // while other devices are already present.
    if (previousOnlineAndroidSerials.isEmpty() && onlineAndroid.isNotEmpty()) {
        return DeviceSelection(selectedSerial = onlineAndroid.first(), selectedIosUdid = null)
    }
    return DeviceSelection(selectedSerial = null, selectedIosUdid = null)
}
