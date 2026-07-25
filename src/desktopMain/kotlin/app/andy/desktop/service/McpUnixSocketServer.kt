package app.andy.desktop.service

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
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
        try {
            val input = Channels.newInputStream(client)
            val output = Channels.newOutputStream(client)
            val transport = StdioServerTransport(
                input = input.asSource().buffered(),
                output = output.asSink().buffered(),
            )
            val server = createServer()
            server.createSession(transport)
            while (client.isOpen && started.get() && scope.isActive) {
                kotlinx.coroutines.delay(500)
            }
        } catch (_: Exception) {
            // Client disconnect is normal.
        } finally {
            runCatching { client.close() }
        }
    }
}
