package app.andy.terminal

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Thin pty4j wrapper so the Rust terminal path does not depend on BossTerm. */
interface AndyPtyHandle {
    suspend fun read(): String?
    suspend fun writeBytes(bytes: ByteArray)
    suspend fun resize(cols: Int, rows: Int)
    suspend fun kill()
    suspend fun waitFor(): Int
    fun isAlive(): Boolean
    fun getPid(): Long
}

object AndyPty {
    fun spawn(
        command: String,
        arguments: List<String>,
        environment: Map<String, String>,
        workingDirectory: String?,
        cols: Int,
        rows: Int,
    ): AndyPtyHandle {
        val argv = (listOf(command) + arguments).toTypedArray()
        val env = HashMap(environment)
        val process = PtyProcessBuilder(argv)
            .setEnvironment(env)
            .setDirectory(workingDirectory)
            .setInitialColumns(cols.coerceAtLeast(1))
            .setInitialRows(rows.coerceAtLeast(1))
            .setRedirectErrorStream(true)
            .start()
        return Pty4jHandle(process)
    }
}

private class Pty4jHandle(
    private val process: PtyProcess,
) : AndyPtyHandle {
    private val closed = AtomicBoolean(false)
    private val input: InputStream = process.inputStream
    private val readBuf = ByteArray(64 * 1024)

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        if (!isAlive() && input.available() <= 0) return@withContext null
        val n = try {
            // Blocking read; returns -1 at EOF.
            input.read(readBuf)
        } catch (_: Exception) {
            -1
        }
        if (n <= 0) return@withContext null
        String(readBuf, 0, n, StandardCharsets.UTF_8)
    }

    override suspend fun writeBytes(bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (bytes.isEmpty() || closed.get()) return@withContext
        runCatching {
            process.outputStream.write(bytes)
            process.outputStream.flush()
        }
        Unit
    }

    override suspend fun resize(cols: Int, rows: Int) = withContext(Dispatchers.IO) {
        runCatching {
            process.setWinSize(WinSize(cols.coerceAtLeast(1), rows.coerceAtLeast(1)))
        }
        Unit
    }

    override suspend fun kill() = withContext(Dispatchers.IO) {
        if (!closed.compareAndSet(false, true)) return@withContext
        runCatching { process.outputStream.close() }
        runCatching { input.close() }
        runCatching { process.destroyForcibly() }
        runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }
        Unit
    }

    override suspend fun waitFor(): Int = withContext(Dispatchers.IO) {
        runCatching { process.waitFor() }.getOrElse { -1 }
    }

    override fun isAlive(): Boolean = !closed.get() && process.isAlive

    override fun getPid(): Long = runCatching { process.pid() }.getOrDefault(-1L)
}
