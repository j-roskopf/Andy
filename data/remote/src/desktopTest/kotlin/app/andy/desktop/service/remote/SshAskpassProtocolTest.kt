package app.andy.desktop.service.remote

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SshAskpassProtocolTest {
    @Test
    fun singleLinePromptProtocolRoundTrip() {
        val dir = Files.createTempDirectory("andy-askpass-proto")
        val sock = dir.resolve("t.sock").toFile()
        sock.deleteOnExit()
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(sock.toPath()))
        val gotLine = AtomicReference<String?>(null)
        val done = CountDownLatch(1)
        thread(name = "askpass-proto-server", isDaemon = true) {
            server.use { srv ->
                srv.accept().use { client ->
                    val buf = ByteBuffer.allocate(4096)
                    val builder = StringBuilder()
                    while (true) {
                        buf.clear()
                        val n = client.read(buf)
                        if (n < 0) break
                        buf.flip()
                        while (buf.hasRemaining()) {
                            val c = buf.get().toInt().toChar()
                            if (c == '\n') {
                                gotLine.set(builder.toString())
                                client.write(ByteBuffer.wrap("secret\n".toByteArray(StandardCharsets.UTF_8)))
                                done.countDown()
                                return@use
                            }
                            if (c != '\r') builder.append(c)
                        }
                    }
                }
            }
        }
        SocketChannel.open(StandardProtocolFamily.UNIX).use { client ->
            client.connect(UnixDomainSocketAddress.of(sock.toPath()))
            client.write(
                ByteBuffer.wrap("user@host's password:\n".toByteArray(StandardCharsets.UTF_8)),
            )
            val reply = ByteArray(64)
            val n = client.read(ByteBuffer.wrap(reply))
            assertTrue(n > 0)
            assertEquals("secret", String(reply, 0, n, StandardCharsets.UTF_8).trim())
        }
        assertTrue(done.await(3, TimeUnit.SECONDS), "server did not finish")
        assertEquals("user@host's password:", gotLine.get())
        sock.delete()
        dir.toFile().deleteRecursively()
    }
}
