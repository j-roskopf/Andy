package app.andy.desktop.service

import app.andy.service.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class CommandRunner(
    private val defaultTimeoutSeconds: Long = 20,
    private val executor: (suspend (command: List<String>, timeoutSeconds: Long) -> CommandResult)? = null,
) {
    suspend fun run(command: List<String>, timeoutSeconds: Long = defaultTimeoutSeconds): CommandResult = withContext(Dispatchers.IO) {
        executor?.let { return@withContext it(command, timeoutSeconds) }
        if (command.isEmpty()) return@withContext CommandResult.failure("No command supplied")
        runCatching {
            val process = ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()
            val readerPool = Executors.newFixedThreadPool(2)
            val stdoutFuture = readerPool.submit(Callable { process.inputStream.bufferedReader().readText() })
            val stderrFuture = readerPool.submit(Callable { process.errorStream.bufferedReader().readText() })
            try {
                val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
                while (true) {
                    coroutineContext.ensureActive()
                    val remainingNs = deadlineNs - System.nanoTime()
                    if (remainingNs <= 0L) {
                        process.destroyForcibly()
                        readerPool.shutdownNow()
                        return@withContext CommandResult.failure("Command timed out: ${command.joinToString(" ")}", 124)
                    }
                    val sliceMs = minOf(250L, TimeUnit.NANOSECONDS.toMillis(remainingNs).coerceAtLeast(1L))
                    if (process.waitFor(sliceMs, TimeUnit.MILLISECONDS)) break
                }
                readerPool.shutdown()
                CommandResult(
                    exitCode = process.exitValue(),
                    stdout = stdoutFuture.get(1, TimeUnit.SECONDS),
                    stderr = stderrFuture.get(1, TimeUnit.SECONDS),
                )
            } catch (cancelled: CancellationException) {
                process.destroyForcibly()
                readerPool.shutdownNow()
                throw cancelled
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            CommandResult.failure(error.message ?: "Command failed")
        }
    }

    fun existingExecutable(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        return file.takeIf { it.exists() && it.canExecute() }?.absolutePath
    }
}
