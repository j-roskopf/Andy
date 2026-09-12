package app.andy.desktop.service.remote

import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A private `SSH_ASKPASS` endpoint for one background probe, answering every prompt with one
 * already-saved secret.
 *
 * Deliberately *not* [SshAskpassBroker]: that one is a process-wide singleton whose active target
 * and keychain fallback belong to the interactive connect flow, and it opens a GUI dialog when it
 * runs out of answers. A scan must never do either — so it gets its own socket, answers from the
 * keychain, and lets `ssh` fail when that secret is wrong.
 */
class SshScanAskpass private constructor(
    private val socket: File,
    private val server: ServerSocketChannel,
) : AutoCloseable {
    val environment: Map<String, String> get() = SshAskpass.environmentEntriesFor(socket.absolutePath)

    override fun close() {
        runCatching { server.close() }
        socket.delete()
    }

    companion object {
        private val seq = AtomicLong(1)

        /** @return null when the socket could not be created — callers fall back to BatchMode. */
        fun open(secret: String): SshScanAskpass? = runCatching {
            val file = File("/tmp", "andy-scan-ap-${ProcessHandle.current().pid()}-${seq.getAndIncrement()}.sock")
            file.delete()
            val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            channel.bind(UnixDomainSocketAddress.of(file.toPath()))
            runCatching {
                Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
            }
            val reply = (secret + "\n").toByteArray(StandardCharsets.UTF_8)
            thread(name = "andy-ssh-scan-askpass", isDaemon = true) {
                while (true) {
                    val client = runCatching { channel.accept() }.getOrNull() ?: break
                    // The prompt text is irrelevant: there is exactly one credential in play, and
                    // answering repeatedly is safe because this socket serves a single probe.
                    runCatching { client.use { it.write(ByteBuffer.wrap(reply.copyOf())) } }
                }
            }
            SshScanAskpass(file, channel)
        }.getOrNull()
    }
}
