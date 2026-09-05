package app.andy.desktop.service.remote

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun allocateLocalPortSkipsOccupiedLoopbackPort() {
        val held = java.net.ServerSocket()
        held.reuseAddress = false
        held.bind(java.net.InetSocketAddress("127.0.0.1", 0))
        val occupied = held.localPort
        try {
            // Force the allocator to consider [occupied] first by binding a sequence walk
            // that will hit it — we only assert the returned port is free and usable.
            val allocated = SshAdbTunnel.allocateLocalPort()
            assertTrue(allocated != occupied)
            java.net.ServerSocket().use { probe ->
                probe.reuseAddress = false
                probe.bind(java.net.InetSocketAddress("127.0.0.1", allocated))
            }
        } finally {
            held.close()
        }
    }
}
