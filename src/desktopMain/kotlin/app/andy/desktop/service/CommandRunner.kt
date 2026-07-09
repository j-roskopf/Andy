package app.andy.desktop.service

import app.andy.service.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CommandRunner(
    private val defaultTimeoutSeconds: Long = 20,
    private val javaHomeProvider: () -> String? = { JavaHomeLocator.find() },
    private val executor: (suspend (command: List<String>, timeoutSeconds: Long) -> CommandResult)? = null,
) {
    suspend fun run(command: List<String>, timeoutSeconds: Long = defaultTimeoutSeconds): CommandResult = withContext(Dispatchers.IO) {
        executor?.let { return@withContext it(command, timeoutSeconds) }
        if (command.isEmpty()) return@withContext CommandResult.failure("No command supplied")
        runCatching {
            val process = ProcessBuilder(command)
                .redirectErrorStream(false)
                .also { builder -> ensureJavaHome(builder.environment()) }
                .start()
            val readerPool = Executors.newFixedThreadPool(2)
            val stdoutFuture = readerPool.submit(Callable { process.inputStream.bufferedReader().readText() })
            val stderrFuture = readerPool.submit(Callable { process.errorStream.bufferedReader().readText() })
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                readerPool.shutdownNow()
                return@withContext CommandResult.failure("Command timed out: ${command.joinToString(" ")}", 124)
            }
            readerPool.shutdown()
            CommandResult(
                exitCode = process.exitValue(),
                stdout = stdoutFuture.get(1, TimeUnit.SECONDS),
                stderr = stderrFuture.get(1, TimeUnit.SECONDS),
            )
        }.getOrElse { error ->
            CommandResult.failure(error.message ?: "Command failed")
        }
    }

    private fun ensureJavaHome(environment: MutableMap<String, String>) {
        val current = environment["JAVA_HOME"]
        if (!current.isNullOrBlank() && JavaHomeLocator.isUsableJavaHome(current)) return
        javaHomeProvider()?.takeIf { JavaHomeLocator.isUsableJavaHome(it) }?.let { environment["JAVA_HOME"] = it }
    }

    fun existingExecutable(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        return file.takeIf { it.exists() && it.canExecute() }?.absolutePath
    }
}
