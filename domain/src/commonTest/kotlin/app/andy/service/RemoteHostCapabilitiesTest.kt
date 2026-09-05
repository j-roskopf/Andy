package app.andy.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteHostCapabilitiesTest {
    @Test
    fun parseUnameRecognizesDarwinAndLinux() {
        assertEquals(RemoteHostOs.Mac, RemoteHostCapabilityProbe.parseUname("Darwin\n"))
        assertEquals(RemoteHostOs.Linux, RemoteHostCapabilityProbe.parseUname("Linux"))
        assertEquals(RemoteHostOs.Unknown, RemoteHostCapabilityProbe.parseUname("FreeBSD"))
    }

    @Test
    fun parseVncListeningFromLsofFields() {
        val listening = """
            p4242
            cAppleVNCServer
            n*:5900
        """.trimIndent()
        assertTrue(RemoteHostCapabilityProbe.parseVncListening(listening))
        assertFalse(
            RemoteHostCapabilityProbe.parseVncListening(
                """
                p1
                cssh
                n127.0.0.1:22
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun parseVncListeningFromPlainLsofLine() {
        val plain = "AppleVNCS 123 user 8u IPv6 0x0 0t0 TCP *:5900 (LISTEN)"
        assertTrue(RemoteHostCapabilityProbe.parseVncListening(plain))
    }

    @Test
    fun parseScreenshotToolPrefersScreencapture() {
        assertEquals(
            "/usr/sbin/screencapture",
            RemoteHostCapabilityProbe.parseScreenshotTool(
                "/usr/sbin/screencapture /usr/bin/grim",
            ),
        )
        assertEquals(
            "/usr/bin/grim",
            RemoteHostCapabilityProbe.parseScreenshotTool("/usr/bin/grim\n/usr/bin/scrot"),
        )
        assertNull(RemoteHostCapabilityProbe.parseScreenshotTool(""))
    }

    @Test
    fun macNeedsEnablingWhenNotListening() {
        val caps = RemoteHostCapabilityProbe.fromProbeOutputs(
            unameStdout = "Darwin",
            lsofVncStdout = "",
            screenshotCommandV = "/usr/sbin/screencapture",
            localVncClient = "open",
        )
        assertEquals(RemoteHostOs.Mac, caps.os)
        assertEquals(RemoteScreenAvailability.NeedsEnabling, caps.screenAvailability)
        assertTrue(caps.enablementHint!!.contains("Screen Sharing"))
        assertEquals("/usr/sbin/screencapture", caps.screenshotTool)
    }

    @Test
    fun macAvailableWhenListening() {
        val caps = RemoteHostCapabilityProbe.fromProbeOutputs(
            unameStdout = "Darwin",
            lsofVncStdout = "p1\ncAppleVNCServer\nn*:5900",
            screenshotCommandV = "/usr/sbin/screencapture",
            localVncClient = "open",
        )
        assertEquals(RemoteScreenAvailability.Available, caps.screenAvailability)
        assertNull(caps.enablementHint)
    }

    @Test
    fun macAvailableFromNetstatWhenLsofEmpty() {
        // Unprivileged SSH often cannot lsof root/launchd Screen Sharing sockets.
        val caps = RemoteHostCapabilityProbe.fromProbeOutputs(
            unameStdout = "Darwin",
            lsofVncStdout = "",
            screenshotCommandV = "/usr/sbin/screencapture",
            localVncClient = "open",
            netstatStdout = "tcp4       0      0  *.5900                 *.*                    LISTEN",
        )
        assertEquals(RemoteScreenAvailability.Available, caps.screenAvailability)
        assertNull(caps.enablementHint)
    }

    @Test
    fun macAvailableFromConnectProbeWhenLsofEmpty() {
        val caps = RemoteHostCapabilityProbe.fromProbeOutputs(
            unameStdout = "Darwin",
            lsofVncStdout = "",
            screenshotCommandV = "/usr/sbin/screencapture",
            localVncClient = "open",
            connectProbeStdout = "ANDY_VNC_OPEN\n",
        )
        assertTrue(caps.vncServerListening)
        assertEquals(RemoteScreenAvailability.Available, caps.screenAvailability)
    }

    @Test
    fun macAvailableFromLaunchctlWhenPortProbesEmpty() {
        val launchctl = """
            system/com.apple.screensharing = {
            	path = /System/Library/LaunchDaemons/com.apple.screensharing.plist
            	state = active
            	program = /System/Library/CoreServices/RemoteManagement/AppleVNCServer.bundle/Contents/MacOS/AppleVNCServer
            }
        """.trimIndent()
        val caps = RemoteHostCapabilityProbe.fromProbeOutputs(
            unameStdout = "Darwin",
            lsofVncStdout = "",
            screenshotCommandV = "/usr/sbin/screencapture",
            localVncClient = "open",
            macLaunchctlStdout = launchctl,
        )
        assertEquals(RemoteScreenAvailability.Available, caps.screenAvailability)
    }

    @Test
    fun macLaunchctlMissingStillNeedsEnabling() {
        assertFalse(
            RemoteHostCapabilityProbe.parseMacScreensharingLaunchd(
                "Bad request.\nCould not find service \"com.apple.screensharing\" in domain for system",
            ),
        )
    }

    @Test
    fun parseNetstatListeningMatchesMacStyle() {
        assertTrue(
            RemoteHostCapabilityProbe.parseNetstatListening(
                "tcp4       0      0  *.5900                 *.*                    LISTEN",
            ),
        )
        assertFalse(
            RemoteHostCapabilityProbe.parseNetstatListening(
                "tcp4       0      0  *.22                   *.*                    LISTEN",
            ),
        )
    }

    @Test
    fun linuxWaylandHintWhenNoVncBinary() {
        val caps = RemoteHostCapabilityProbe.fromProbeOutputs(
            unameStdout = "Linux",
            lsofVncStdout = "",
            screenshotCommandV = "/usr/bin/grim",
            linuxVncCommandV = "",
            waylandDisplay = "wayland-0",
        )
        assertEquals(RemoteScreenAvailability.NeedsEnabling, caps.screenAvailability)
        assertTrue(caps.enablementHint!!.contains("wayvnc", ignoreCase = true))
    }

    @Test
    fun linuxWithInstalledVncBinaryButNotListening() {
        val caps = RemoteHostCapabilityProbe.fromProbeOutputs(
            unameStdout = "Linux",
            lsofVncStdout = "",
            screenshotCommandV = "scrot",
            linuxVncCommandV = "/usr/bin/x11vnc",
        )
        assertTrue(caps.enablementHint!!.contains("x11vnc"))
    }
}

class HostScreenshotCommandTest {
    @Test
    fun argvTableForKnownTools() {
        assertEquals(
            listOf("/usr/sbin/screencapture", "-x", "-t", "png", "/tmp/out.png"),
            HostScreenshotCommand.argv("/usr/sbin/screencapture", "/tmp/out.png"),
        )
        assertEquals(
            listOf("grim", "/tmp/out.png"),
            HostScreenshotCommand.argv("grim", "/tmp/out.png"),
        )
        assertEquals(
            listOf("/usr/bin/scrot", "-o", "/tmp/out.png"),
            HostScreenshotCommand.argv("/usr/bin/scrot", "/tmp/out.png"),
        )
        assertEquals(
            listOf("import", "-window", "root", "/tmp/out.png"),
            HostScreenshotCommand.argv("import", "/tmp/out.png"),
        )
        assertNull(HostScreenshotCommand.argv("ffmpeg", "/tmp/out.png"))
    }

    @Test
    fun resolveToolPrefersOrder() {
        assertEquals(
            "/bin/screencapture",
            HostScreenshotCommand.resolveTool(listOf("/usr/bin/grim", "/bin/screencapture")),
        )
        assertEquals(
            "scrot",
            HostScreenshotCommand.resolveTool(listOf("scrot", "import")),
        )
    }
}
