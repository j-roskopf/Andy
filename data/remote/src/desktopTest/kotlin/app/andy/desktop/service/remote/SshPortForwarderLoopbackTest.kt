package app.andy.desktop.service.remote

import app.andy.desktop.test.OptInGates
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Opt-in: real `ssh` ControlMaster to 127.0.0.1, forward a throwaway listener, assert
 * connect-through + cleanup. Skipped (assumeTrue) unless `ANDY_SSH_LOOPBACK=1`.
 */
class SshPortForwarderLoopbackTest {
    private var master: Process? = null
    private var controlPath: File? = null
    private var listener: ServerSocket? = null
    private val acceptorStop = AtomicBoolean(false)

    @AfterTest
    fun tearDown() {
        acceptorStop.set(true)
        runCatching { listener?.close() }
        controlPath?.let { path ->
            runCatching {
                ProcessBuilder(
                    "ssh",
                    "-o", "ControlPath=${path.absolutePath}",
                    "-O", "exit",
                    "127.0.0.1",
                ).redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS)
            }
            path.delete()
        }
        master?.destroyForcibly()
    }

    @Test
    fun forwardsLoopbackPortAndReleases() = runBlocking {
        OptInGates.requireSshLoopback()

        val remotePort = ServerSocket(0).use { it.localPort }
        listener = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("127.0.0.1", remotePort))
        }
        val accepted = AtomicBoolean(false)
        Thread({
            while (!acceptorStop.get()) {
                runCatching {
                    listener?.accept()?.use { accepted.set(true) }
                }
            }
        }, "andy-ssh-loopback-acceptor").apply { isDaemon = true; start() }

        val path = File(
            System.getProperty("java.io.tmpdir"),
            "andy-ssh-loopback-${UUID.randomUUID()}.sock",
        )
        controlPath = path
        path.delete()

        val masterProcess = ProcessBuilder(
            "ssh",
            "-N",
            "-o", "ControlMaster=yes",
            "-o", "ControlPersist=60",
            "-o", "ControlPath=${path.absolutePath}",
            "-o", "BatchMode=yes",
            "-o", "StrictHostKeyChecking=accept-new",
            "-o", "ConnectTimeout=8",
            "127.0.0.1",
        ).redirectErrorStream(true).start()
        master = masterProcess

        withTimeout(15_000) {
            while (!path.exists() || !masterProcess.isAlive) {
                if (!masterProcess.isAlive) {
                    val err = masterProcess.inputStream.bufferedReader().readText()
                    error("ssh master exited early: $err")
                }
                delay(50)
            }
        }

        // Occupy the preferred local port so forwarder must allocate a fallback.
        val blocker = ServerSocket(remotePort)
        try {
            val forwarder = SshPortForwarder(
                target = "127.0.0.1",
                controlPath = path,
            )
            val localPort = forwarder.forward(remotePort)
            assertTrue(localPort != remotePort, "expected collision fallback local port")
            assertEquals(localPort, forwarder.localPortFor(remotePort))

            withTimeout(10_000) {
                while (true) {
                    val connected = runCatching {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress("127.0.0.1", localPort), 500)
                            true
                        }
                    }.getOrDefault(false)
                    if (connected) break
                    delay(50)
                }
            }
            withTimeout(5_000) {
                while (!accepted.get()) delay(25)
            }

            forwarder.release(remotePort)
            assertTrue(forwarder.mapping().isEmpty())

            // After cancel, new connects to the mapped local port should fail.
            withTimeout(10_000) {
                while (true) {
                    val stillOpen = runCatching {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress("127.0.0.1", localPort), 300)
                            true
                        }
                    }.getOrDefault(false)
                    if (!stillOpen) break
                    delay(50)
                }
            }
        } finally {
            blocker.close()
        }
        Unit
    }
}
