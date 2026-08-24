package app.andy.desktop.service.remote

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SshAdbTunnelTest {
    @Test
    fun injectAdbServerPortInsertsAfterAdbBinary() {
        val tunnel = SshAdbTunnel(
            target = "user@host",
            controlPath = File("/tmp/andy-test-mux"),
            localAdbPort = 15_432,
        )
        assertEquals(
            listOf("/sdk/platform-tools/adb", "-P", "15432", "-s", "SERIAL", "shell", "echo", "hi"),
            tunnel.injectAdbServerPort(
                listOf("/sdk/platform-tools/adb", "-s", "SERIAL", "shell", "echo", "hi"),
            ),
        )
    }

    @Test
    fun injectAdbServerPortIsIdempotentWhenPortAlreadyPresent() {
        val tunnel = SshAdbTunnel(
            target = "user@host",
            controlPath = File("/tmp/andy-test-mux"),
            localAdbPort = 15_432,
        )
        val already = listOf("adb", "-P", "15432", "devices")
        assertEquals(already, tunnel.injectAdbServerPort(already))
    }

    @Test
    fun injectAdbServerPortLeavesNonAdbCommandsAlone() {
        val tunnel = SshAdbTunnel(
            target = "user@host",
            controlPath = File("/tmp/andy-test-mux"),
            localAdbPort = 15_432,
        )
        val other = listOf("emulator", "-avd", "Pixel")
        assertEquals(other, tunnel.injectAdbServerPort(other))
    }
}
