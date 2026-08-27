package app.andy.desktop.parser

import app.andy.model.IosTargetKind
import app.andy.model.IosTargetState
import app.andy.model.IosTransport
import app.andy.model.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun parsesRememberedUnpluggedDeviceAsUnavailableNotUnknown() {
        // `devicectl list devices` returns remembered pairings, not connected devices. An
        // unplugged iPhone still comes back `pairingState: paired` but with no transportType —
        // Andy must not list it as present (Phase 6.2 phantom-device fix).
        val output = """
            {
              "result": {
                "devices": [
                  {
                    "deviceProperties": { "name": "Unplugged iPhone" },
                    "hardwareProperties": {
                      "udid": "00008140-00026112260B001E",
                      "marketingName": "iPhone 14 Pro Max"
                    },
                    "identifier": "B7F2D2B4-34D1-5E2A-8D46-F83C24E9CE04",
                    "connectionProperties": {
                      "pairingState": "paired"
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val targets = IosParsers.parseDevicectlDevices(output)

        assertEquals(1, targets.size)
        val remembered = targets.single()
        assertEquals(IosTargetState.Unavailable, remembered.state)
        assertEquals(IosTransport.Unknown, remembered.transport)
        assertFalse(remembered.isMirrorable)
    }

    @Test
    fun wiredDeviceWithDisconnectedTunnelRemainsMirrorable() {
        // USB mirroring needs transportType only. tunnelState=disconnected is normal for a
        // trusted phone without Developer Mode / DDI services and must not hide the device.
        val output = """
            {
              "result": {
                "devices": [
                  {
                    "deviceProperties": { "name": "USB iPhone" },
                    "hardwareProperties": { "udid": "00008140-00026112260B001F" },
                    "connectionProperties": {
                      "transportType": "wired",
                      "pairingState": "paired",
                      "tunnelState": "disconnected"
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val targets = IosParsers.parseDevicectlDevices(output)

        val usb = targets.single()
        assertEquals(IosTargetState.Unknown, usb.state)
        assertEquals(IosTransport.Usb, usb.transport)
        assertTrue(usb.isMirrorable)
        assertTrue(usb.isLiveReady)
    }

    @Test
    fun parsesDeviceTypes() {
        val output = """
            {
              "devicetypes": [
                {
                  "identifier": "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro",
                  "name": "iPhone 17 Pro",
                  "productFamily": "iPhone"
                },
                {
                  "identifier": "com.apple.CoreSimulator.SimDeviceType.iPad-Pro-13-inch",
                  "name": "iPad Pro 13-inch",
                  "productFamily": "iPad"
                }
              ]
            }
        """.trimIndent()

        val types = IosParsers.parseDeviceTypes(output)

        assertEquals(2, types.size)
        assertEquals("iPhone 17 Pro", types[0].name)
        assertEquals("iPhone", types[0].productFamily)
    }

    @Test
    fun parsesRuntimes() {
        val output = """
            {
              "runtimes": [
                {
                  "buildversion": "23A339",
                  "identifier": "com.apple.CoreSimulator.SimRuntime.iOS-26-5",
                  "isAvailable": true,
                  "name": "iOS 26.5",
                  "version": "26.5"
                },
                {
                  "buildversion": "21A340",
                  "identifier": "com.apple.CoreSimulator.SimRuntime.iOS-17-0",
                  "isAvailable": false,
                  "name": "iOS 17.0",
                  "version": "17.0"
                }
              ]
            }
        """.trimIndent()

        val runtimes = IosParsers.parseRuntimes(output)

        assertEquals(2, runtimes.size)
        val current = runtimes.first { it.identifier.endsWith("iOS-26-5") }
        assertEquals("iOS 26.5", current.name)
        assertEquals("23A339", current.buildVersion)
        assertTrue(current.isAvailable)
        val old = runtimes.first { it.identifier.endsWith("iOS-17-0") }
        assertFalse(old.isAvailable)
    }

    @Test
    fun parsesDeveloperModeStatusEnabled() {
        val output = """
            {
              "result": {
                "deviceProperties": {
                  "name": "iPhone",
                  "developerModeStatus": "enabled",
                  "ddiServicesAvailable": true
                }
              }
            }
        """.trimIndent()

        val status = IosParsers.parseDeveloperModeStatus(output)

        assertTrue(status!!.enabled)
        assertTrue(status.ddiServicesAvailable)
    }

    @Test
    fun parsesDeveloperModeStatusDisabled() {
        val output = """
            {
              "result": {
                "deviceProperties": {
                  "name": "iPhone",
                  "developerModeStatus": "disabled",
                  "ddiServicesAvailable": false
                }
              }
            }
        """.trimIndent()

        val status = IosParsers.parseDeveloperModeStatus(output)

        assertFalse(status!!.enabled)
        assertFalse(status.ddiServicesAvailable)
        assertTrue(status.message.contains("Developer Mode"))
    }

    @Test
    fun parsesDeveloperModeStatusReturnsNullWhenMissing() {
        val status = IosParsers.parseDeveloperModeStatus("""{"result": {"deviceProperties": {"name": "iPhone"}}}""")
        assertNull(status)
    }

    @Test
    fun parsesIpsReportExtractingProcessTimestampExceptionAndSimulatorUdid() {
        val header = """{"app_name":"MyApp","timestamp":"2026-08-19 21:00:00.123456 -0500","app_version":"1.0"}"""
        val body = """
            {
              "procName": "MyApp",
              "procPath": "/Users/me/Library/Developer/CoreSimulator/Devices/CA4B2892-6294-4CD4-AA5A-6031551226BA/data/Containers/Bundle/Application/ABC/MyApp.app/MyApp",
              "exception": {"type": "EXC_BREAKPOINT", "signal": "SIGTRAP"},
              "terminationDescription": "Namespace SIGNAL, Code 0x5",
              "binaryImages": [{"path": "/Users/me/Library/Developer/CoreSimulator/Devices/CA4B2892-6294-4CD4-AA5A-6031551226BA/data/Containers/Bundle/Application/ABC/MyApp.app/MyApp"}]
            }
        """.trimIndent()
        val text = "$header\n$body"

        val report = IosParsers.parseIpsReport("crash-1.ips", text)

        assertEquals("MyApp", report!!.processName)
        assertEquals("EXC_BREAKPOINT", report.exceptionType)
        assertEquals("CA4B2892-6294-4CD4-AA5A-6031551226BA", report.simulatorUdid)
        assertTrue(report.timestampMillis > 0L)
        assertTrue(report.summary.contains("MyApp"))
    }

    @Test
    fun parsesIpsReportReturnsNullForMalformedInput() {
        assertNull(IosParsers.parseIpsReport("bad.ips", "not a crash report"))
        assertNull(IosParsers.parseIpsReport("bad2.ips", "{\"app_name\":\"X\"}"))
    }

    @Test
    fun parsesIpsReportFallsBackToIdAndUnknownExceptionWhenBodyIsMissingFields() {
        val header = """{"proc_name":"OtherApp","timestamp":"2026-08-19 21:00:00.000000 -0500"}"""
        val body = """{"procName": "OtherApp"}"""
        val report = IosParsers.parseIpsReport("crash-2.ips", "$header\n$body")

        assertEquals("OtherApp", report!!.processName)
        assertNull(report.exceptionType)
        assertNull(report.simulatorUdid)
    }

    @Test
    fun parsesLogStreamLineFoldsSubsystemAndCategoryIntoTag() {
        val line = """
            {"timestamp":"2026-08-19 21:00:00.123456-0500","messageType":"Error","eventMessage":"Boom",
             "subsystem":"com.apple.UIKit","category":"Rendering","processID":123,"threadID":456}
        """.trimIndent()

        val entry = IosParsers.parseLogStreamLine(line)

        assertEquals("Boom", entry!!.message)
        assertEquals(LogLevel.Error, entry.level)
        assertEquals("com.apple.UIKit:Rendering", entry.tag)
        assertEquals("123", entry.pid)
        assertEquals("456", entry.tid)
    }

    @Test
    fun parsesLogStreamLineFallsBackToProcessImagePathWhenNoSubsystemOrCategory() {
        val line = """{"messageType":"Fault","eventMessage":"Crashed","processImagePath":"/usr/bin/springboard"}"""

        val entry = IosParsers.parseLogStreamLine(line)

        assertEquals(LogLevel.Fatal, entry!!.level)
        assertEquals("springboard", entry.tag)
    }

    @Test
    fun parsesLogStreamLineReturnsNullForNonJsonOrMissingMessage() {
        assertNull(IosParsers.parseLogStreamLine(""))
        assertNull(IosParsers.parseLogStreamLine("not json"))
        assertNull(IosParsers.parseLogStreamLine("""{"messageType":"Info"}"""))
    }
}
