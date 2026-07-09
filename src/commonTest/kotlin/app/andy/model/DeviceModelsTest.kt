package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceModelsTest {
    @Test
    fun extractMdnsHardwareIdParsesInstanceBody() {
        assertEquals(
            "5A080DLCH000UR-oVigq2",
            extractMdnsHardwareId("adb-5A080DLCH000UR-oVigq2._adb-tls-connect._tcp"),
        )
        assertEquals(
            "VAN10A203710441",
            extractMdnsHardwareId("adb-VAN10A203710441._adb-tls-connect._tcp"),
        )
        assertNull(extractMdnsHardwareId("192.168.86.150:35923"))
        assertNull(extractMdnsHardwareId("R3CXB056ZZB"))
    }

    @Test
    fun dedupeWifiAliasesRequiresStableHardwareId() {
        val ip = AndroidDevice(
            serial = "192.168.86.150:35923",
            displayName = "Pixel 10 Pro",
            kind = DeviceKind.Physical,
            state = DeviceConnectionState.Online,
            transport = DeviceTransport.Wifi,
            model = "Pixel_10_Pro",
            product = "blazer",
            hardwareId = "5A080DLCH000UR",
        )
        val matchingMdns = AndroidDevice(
            serial = "adb-5A080DLCH000UR-oVigq2._adb-tls-connect._tcp",
            displayName = "Pixel 10 Pro",
            kind = DeviceKind.Physical,
            state = DeviceConnectionState.Online,
            transport = DeviceTransport.Wifi,
            model = "Pixel_10_Pro",
            product = "blazer",
            hardwareId = "5A080DLCH000UR-oVigq2",
        )
        val sameModelDifferentPhone = AndroidDevice(
            serial = "adb-OTHERSERIAL._adb-tls-connect._tcp",
            displayName = "Pixel 10 Pro",
            kind = DeviceKind.Physical,
            state = DeviceConnectionState.Online,
            transport = DeviceTransport.Wifi,
            model = "Pixel_10_Pro",
            product = "blazer",
            hardwareId = "OTHERSERIAL",
        )

        val deduped = dedupeWifiDeviceAliases(listOf(ip, matchingMdns, sameModelDifferentPhone))
        assertEquals(listOf(ip.serial, sameModelDifferentPhone.serial), deduped.map { it.serial })
    }

    @Test
    fun dedupeDoesNotCollapseSameModelWithoutHardwareIds() {
        val ip = AndroidDevice(
            serial = "192.168.86.200:5555",
            displayName = "SM S921U",
            kind = DeviceKind.Physical,
            state = DeviceConnectionState.Online,
            transport = DeviceTransport.Wifi,
            model = "SM_S921U",
            product = "e3q",
        )
        val mdns = AndroidDevice(
            serial = "adb-OTHER._adb-tls-connect._tcp",
            displayName = "SM S921U",
            kind = DeviceKind.Physical,
            state = DeviceConnectionState.Online,
            transport = DeviceTransport.Wifi,
            model = "SM_S921U",
            product = "e3q",
            hardwareId = "OTHER",
        )

        val deduped = dedupeWifiDeviceAliases(listOf(ip, mdns))
        assertEquals(2, deduped.size)
    }

    @Test
    fun wirelessAdbSerialDetection() {
        assertTrue(isWirelessAdbSerial("192.168.1.20:5555"))
        assertTrue(isWirelessAdbSerial("adb-ABC._adb-tls-connect._tcp"))
        assertFalse(isWirelessAdbSerial("R3CXB056ZZB"))
        assertFalse(isWirelessAdbSerial("emulator-5554"))
    }
}
