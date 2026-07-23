package app.andy.desktop.parser

import app.andy.model.IosTargetKind
import app.andy.model.IosTargetState
import app.andy.model.IosTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosParsersTest {
    @Test
    fun parsesSimctlDevicesIncludingShutdownAndUnavailable() {
        val output = """
            {
              "devices": {
                "com.apple.CoreSimulator.SimRuntime.iOS-26-5": [
                  {
                    "udid": "CA4B2892-6294-4CD4-AA5A-6031551226BA",
                    "name": "iPhone 17 Pro",
                    "state": "Booted",
                    "isAvailable": true,
                    "deviceTypeIdentifier": "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro"
                  },
                  {
                    "udid": "11111111-2222-3333-4444-555555555555",
                    "name": "Unavailable Sim",
                    "state": "Shutdown",
                    "isAvailable": false
                  },
                  {
                    "udid": "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE",
                    "name": "Shutdown Sim",
                    "state": "Shutdown",
                    "isAvailable": true
                  }
                ]
              }
            }
        """.trimIndent()

        val targets = IosParsers.parseSimctlDevices(output)

        assertEquals(3, targets.size)
        val booted = targets.first { it.udid == "CA4B2892-6294-4CD4-AA5A-6031551226BA" }
        assertEquals("iPhone 17 Pro", booted.displayName)
        assertEquals(IosTargetKind.Simulator, booted.kind)
        assertEquals(IosTargetState.Booted, booted.state)
        assertTrue(booted.isMirrorable)

        val unavailable = targets.first { it.udid == "11111111-2222-3333-4444-555555555555" }
        assertEquals(IosTargetState.Unavailable, unavailable.state)
        assertFalse(unavailable.isMirrorable)

        val shutdown = targets.first { it.udid == "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE" }
        assertEquals(IosTargetState.Shutdown, shutdown.state)
        assertTrue(shutdown.isMirrorable)
    }

    @Test
    fun parsesDevicectlUsbAndNetworkDevices() {
        val output = """
            {
              "result": {
                "devices": [
                  {
                    "deviceProperties": { "name": "iPhone 16 Pro Max", "osVersionNumber": "26.5" },
                    "hardwareProperties": {
                      "udid": "00008140-00026112260B001C",
                      "marketingName": "iPhone 16 Pro Max"
                    },
                    "connectionProperties": {
                      "transportType": "localNetwork",
                      "pairingState": "paired"
                    }
                  },
                  {
                    "deviceProperties": { "name": "USB iPhone" },
                    "hardwareProperties": { "udid": "00008140-00026112260B001D" },
                    "identifier": "A7F2D2B4-34D1-5E2A-8D46-F83C24E9CE03",
                    "connectionProperties": {
                      "transportType": "wired",
                      "pairingState": "paired"
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val targets = IosParsers.parseDevicectlDevices(output)

        assertEquals(2, targets.size)
        val network = targets.first { it.udid == "00008140-00026112260B001C" }
        assertEquals(IosTransport.Network, network.transport)
        assertFalse(network.isMirrorable)

        val usb = targets.first { it.udid == "00008140-00026112260B001D" }
        assertEquals(IosTransport.Usb, usb.transport)
        assertEquals("A7F2D2B4-34D1-5E2A-8D46-F83C24E9CE03", usb.coreDeviceIdentifier)
        assertTrue(usb.isMirrorable)
    }
}
