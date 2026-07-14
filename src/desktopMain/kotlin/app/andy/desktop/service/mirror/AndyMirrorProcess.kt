package app.andy.desktop.service.mirror

import app.andy.service.MirrorInput
import app.andy.service.MirrorVideoConfig
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/**
 * Product-owned companion transport. The executable owns accelerated presentation (including a
 * Wayland pop-out); Kotlin only sends low-frequency control and receives verified telemetry.
 */
internal class AndyMirrorProcess(
    private val executable: File,
    private val onEvent: (AndyMirrorEvent) -> Unit,
    /**
     * Product-owned launch inputs. A sidecar must use these rather than discover a globally
     * installed scrcpy server or adb client, which would break Andy's source-pinned protocol.
     */
    private val environment: Map<String, String> = emptyMap(),
) : Closeable {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null
    private val ready = CompletableDeferred<AndyMirrorEvent>()
    @Volatile var failureReason: String? = null
        private set

    suspend fun start(serial: String, config: MirrorVideoConfig): AndyMirrorEvent? = withContext(Dispatchers.IO) {
        check(process == null) { "andy-mirror is already running" }
        val child = ProcessBuilder(executable.absolutePath, "--protocol")
            .apply { environment().putAll(environment) }
            .start()
        process = child
        writer = BufferedWriter(OutputStreamWriter(child.outputStream, Charsets.UTF_8))
        stdoutJob = scope.launch {
            try {
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isBlank()) return@forEach
                        val event = runCatching { AndyMirrorProtocol.decodeEvent(line) }.getOrElse { error ->
                            failureReason = "Invalid andy-mirror event: ${error.message ?: line.take(120)}"
                            return@forEach
                        }
                        if (event.type == "ready" && !ready.isCompleted) ready.complete(event)
                        if (event.type == "failure") {
                            failureReason = event.failureReason ?: "andy-mirror failed without a reason"
                            if (!ready.isCompleted) ready.complete(event)
                        }
                        onEvent(event)
                    }
                }
            } catch (error: Throwable) {
                // Closing a child process closes both pipes. That is an expected shutdown,
                // not an uncaught coroutine error that can poison later Compose tests.
                if (!closed.get()) {
                    failureReason = "andy-mirror output failed: ${error.message ?: error::class.simpleName}"
                }
            }
            if (!closed.get() && !ready.isCompleted) {
                ready.complete(
                    AndyMirrorEvent(
                        type = "failure",
                        failureReason = failureReason ?: "andy-mirror exited before reporting ready",
                    ),
                )
            }
        }
        stderrJob = scope.launch {
            try {
                child.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) failureReason = line.take(300)
                    }
                }
            } catch (error: Throwable) {
                if (!closed.get()) {
                    failureReason = "andy-mirror diagnostics failed: ${error.message ?: error::class.simpleName}"
                }
            }
        }
        sendRaw(AndyMirrorProtocol.start(serial, config))
        try {
            withTimeout(5_000) { ready.await() }
        } catch (error: Throwable) {
            close()
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            failureReason = if (error is TimeoutCancellationException) {
                "andy-mirror did not report ready within 5 seconds"
            } else {
                "andy-mirror startup failed: ${error.message ?: error::class.simpleName}"
            }
            null
        }
    }

    fun attach(host: String) = sendRaw(AndyMirrorProtocol.attach(host))

    fun resize(width: Int, height: Int) = sendRaw(AndyMirrorProtocol.resize(width, height))

    fun overlay(value: JsonObject) = sendRaw(AndyMirrorProtocol.overlay(value))

    fun inspect() = sendRaw(AndyMirrorProtocol.inspect())

    fun input(value: MirrorInput) = sendRaw(AndyMirrorProtocol.input(value))

    private fun sendRaw(line: String): Boolean {
        val currentWriter = writer ?: return false
        return runCatching {
            synchronized(currentWriter) {
                currentWriter.write(line)
                currentWriter.newLine()
                currentWriter.flush()
            }
            true
        }.getOrElse { error ->
            failureReason = "andy-mirror control write failed: ${error.message ?: error::class.simpleName}"
            false
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { sendRaw(AndyMirrorProtocol.stop()) }
        runCatching { writer?.close() }
        writer = null
        process?.destroy()
        process = null
        scope.launch {
            stdoutJob?.cancelAndJoin()
            stderrJob?.cancelAndJoin()
        }
    }
}
