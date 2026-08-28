package app.andy.ui.shell

import app.andy.model.AndroidDevice
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.IosTargetState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceSelectionTest {
    @Test
    fun keepsValidAndroidSelection() {
        val devices = listOf(online("A"), online("B"))
        val result = reconcileDeviceSelection(
            previousOnlineAndroidSerials = setOf("A", "B"),
            devices = devices,
            iosTargets = emptyList(),
            selectedSerial = "A",
            selectedIosUdid = null,
        )
        assertEquals("A", result.selectedSerial)
        assertNull(result.selectedIosUdid)
    }

    @Test
    fun failsoverWhenSelectedAndroidDisconnects() {
        val result = reconcileDeviceSelection(
            previousOnlineAndroidSerials = setOf("A", "B"),
            devices = listOf(online("B")),
            iosTargets = emptyList(),
            selectedSerial = "A",
            selectedIosUdid = null,
        )
        assertEquals("B", result.selectedSerial)
    }

    @Test
    fun clearsSelectionWhenLastAndroidDisconnects() {
        val result = reconcileDeviceSelection(
            previousOnlineAndroidSerials = setOf("A"),
            devices = emptyList(),
            iosTargets = emptyList(),
            selectedSerial = "A",
            selectedIosUdid = null,
        )
        assertNull(result.selectedSerial)
        assertNull(result.selectedIosUdid)
    }

    @Test
    fun autoSelectsWhenPluggingIntoEmptySlate() {
        val result = reconcileDeviceSelection(
            previousOnlineAndroidSerials = emptySet(),
            devices = listOf(online("NEW")),
            iosTargets = emptyList(),
            selectedSerial = null,
            selectedIosUdid = null,
        )
        assertEquals("NEW", result.selectedSerial)
    }

    @Test
    fun doesNotStealEmptySelectionWhenOtherDevicesAlreadyOnline() {
        val result = reconcileDeviceSelection(
            previousOnlineAndroidSerials = setOf("A"),
            devices = listOf(online("A"), online("B")),
            iosTargets = emptyList(),
            selectedSerial = null,
            selectedIosUdid = null,
        )
        assertNull(result.selectedSerial)
    }

    @Test
    fun preservesIosSelection() {
        val ios = bootedIos("UDID")
        val result = reconcileDeviceSelection(
            previousOnlineAndroidSerials = emptySet(),
            devices = listOf(online("A")),
            iosTargets = listOf(ios),
            selectedSerial = null,
            selectedIosUdid = "UDID",
        )
        assertNull(result.selectedSerial)
        assertEquals("UDID", result.selectedIosUdid)
    }

    @Test
    fun failsoverFromIosToAndroidWhenSimulatorStops() {
        val result = reconcileDeviceSelection(
            previousOnlineAndroidSerials = setOf("A"),
            devices = listOf(online("A")),
            iosTargets = listOf(shutdownIos("UDID")),
            selectedSerial = null,
            selectedIosUdid = "UDID",
        )
        assertEquals("A", result.selectedSerial)
        assertNull(result.selectedIosUdid)
    }

    private fun online(serial: String) = AndroidDevice(
        serial = serial,
        displayName = serial,
        kind = DeviceKind.Physical,
        state = DeviceConnectionState.Online,
    )

    private fun bootedIos(udid: String) = IosTarget(
        udid = udid,
        displayName = "iPhone",
        kind = IosTargetKind.Simulator,
        state = IosTargetState.Booted,
    )

    private fun shutdownIos(udid: String) = IosTarget(
        udid = udid,
        displayName = "iPhone",
        kind = IosTargetKind.Simulator,
        state = IosTargetState.Shutdown,
    )
}
