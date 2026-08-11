package app.andy.desktop.service

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Accepts MCP sessions over a Unix domain socket using [StdioServerTransport]
 * framing (newline-delimited JSON-RPC) per connection.
 *
 * Bind/accept use blocking NIO on a dedicated thread — avoids nested
 * `runBlocking`/`withContext(IO)` deadlocks during daemon startup.
 */
class McpUnixSocketServer(
    private val socketPath: File,
    private val createServer: () -> Server,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    @Volatile private var serverChannel: ServerSocketChannel? = null
    @Volatile private var acceptThread: Thread? = null

    val isRunning: Boolean get() = started.get()

    /** Blocking bind. Call from a worker thread (not the main runBlocking event loop). */
    fun startBlocking() {
        check(started.compareAndSet(false, true)) { "unix MCP server already started" }
        try {
            socketPath.parentFile?.mkdirs()
            if (socketPath.exists()) {
                check(socketPath.delete()) {
                    "could not delete stale socket ${socketPath.absolutePath}"
                }
            }
            val address = UnixDomainSocketAddress.of(socketPath.toPath())
            val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            channel.configureBlocking(true)
            channel.bind(address)
            serverChannel = channel
            check(socketPath.exists()) {
                "unix socket bind succeeded but ${socketPath.absolutePath} is missing"
            }
            val thread = Thread({
                acceptLoop(channel)
            }, "andyd-mcp-unix-accept")
            thread.isDaemon = true
            acceptThread = thread
            thread.start()
            System.err.println("andyd: unix MCP listening on ${socketPath.absolutePath}")
        } catch (error: Throwable) {
            started.set(false)
            runCatching { serverChannel?.close() }
            serverChannel = null
            runCatching { socketPath.delete() }
            throw error
        }
    }

    fun stopBlocking() {
        if (!started.compareAndSet(true, false)) return
        ChatSubscribeRegistry.cancelAll()
        runCatching { serverChannel?.close() }
        serverChannel = null
        acceptThread?.interrupt()
        acceptThread = null
        runCatching { socketPath.delete() }
        scope.cancel()
    }

    private fun acceptLoop(channel: ServerSocketChannel) {
        while (started.get()) {
            val client = try {
                channel.accept()
            } catch (_: ClosedChannelException) {
                break
            } catch (_: InterruptedException) {
                break
            } catch (error: Exception) {
                if (!started.get()) break
                System.err.println("andyd unix accept error: ${error.message}")
                break
            }
            scope.launch { handleClient(client) }
        }
    }

    private suspend fun handleClient(client: SocketChannel) {
        var transport: StdioServerTransport? = null
        var session: ServerSession? = null
        try {
            val sessionClosed = CompletableDeferred<Unit>()
            val disconnected = AtomicBoolean(false)
            fun onPeerDisconnect() {
                if (!disconnected.compareAndSet(false, true)) return
                session?.sessionId?.let { ChatSubscribeRegistry.cancelSession(it) }
                sessionClosed.complete(Unit)
            }

            val input = EofNotifyingInputStream(Channels.newInputStream(client), ::onPeerDisconnect)
            val output = PeerDisconnectNotifyingOutputStream(
                FlushOnWriteOutputStream(Channels.newOutputStream(client)),
                ::onPeerDisconnect,
            )
            transport = StdioServerTransport(
                input = input.asSource().buffered(),
                output = output.asSink().buffered(),
            )
            val server = createServer()
            // Peer disconnect does not flip SocketChannel.isOpen — wait on session close
            // (transport EOF) so in-flight tools like chat.subscribe are cancelled.
            session = server.createSession(transport).also { created ->
                created.onClose {
                    // SDK leaves in-flight tool coroutines running on transport EOF;
                    // cancel chat.subscribe collectors registered for this session.
                    onPeerDisconnect()
                }
            }
            sessionClosed.await()
        } catch (_: Exception) {
            // Client disconnect is normal.
        } finally {
            session?.sessionId?.let { ChatSubscribeRegistry.cancelSession(it) }
            runCatching { session?.close() }
            runCatching { transport?.close() }
            runCatching { client.close() }
        }
    }
}

/** Flushes after every write so peer-close surfaces as IOException on the next push. */
private class FlushOnWriteOutputStream(
    private val delegate: OutputStream,
) : OutputStream() {
    override fun write(b: Int) {
        delegate.write(b)
        delegate.flush()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        delegate.flush()
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()
}

private class PeerDisconnectNotifyingOutputStream(
    private val delegate: OutputStream,
    private val onDisconnect: () -> Unit,
) : OutputStream() {
    private fun onIoError(error: IOException): Nothing {
        onDisconnect()
        throw error
    }

    override fun write(b: Int) {
        try {
            delegate.write(b)
        } catch (error: IOException) {
            onIoError(error)
        }
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        try {
            delegate.write(b, off, len)
        } catch (error: IOException) {
            onIoError(error)
        }
    }

    override fun flush() {
        try {
            delegate.flush()
        } catch (error: IOException) {
            onIoError(error)
        }
    }

    override fun close() = delegate.close()
}

private class EofNotifyingInputStream(
    private val delegate: InputStream,
    private val onEof: () -> Unit,
) : InputStream() {
    private val eofNotified = AtomicBoolean(false)

    private fun notifyEof() {
        if (eofNotified.compareAndSet(false, true)) {
            onEof()
        }
    }

    override fun read(): Int {
        val value = delegate.read()
        if (value == -1) notifyEof()
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val count = delegate.read(b, off, len)
        if (count == -1) notifyEof()
        return count
    }

    override fun available(): Int = delegate.available()

    override fun close() = delegate.close()
}
