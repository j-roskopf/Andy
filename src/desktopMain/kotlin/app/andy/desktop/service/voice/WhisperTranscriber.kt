package app.andy.desktop.service.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

fun interface WhisperProcessRunner {
    fun run(command: List<String>, workingDir: File?, environment: Map<String, String>): ProcessResult
}

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

interface WhisperTranscriber {
    suspend fun transcribe(wav: File): Result<String>
}

class CliWhisperTranscriber(
    private val binary: File,
    private val model: File,
    private val libDir: File,
    private val backendFile: File? = null,
    private val processRunner: WhisperProcessRunner = DefaultWhisperProcessRunner,
) : WhisperTranscriber {
    override suspend fun transcribe(wav: File): Result<String> = withContext(Dispatchers.IO) {
        val outBase = File(wav.parentFile ?: File(System.getProperty("java.io.tmpdir")), "andy-whisper-${System.currentTimeMillis()}")
        val txt = File(outBase.absolutePath + ".txt")
        try {
            if (!binary.isFile) return@withContext Result.failure(IllegalStateException("whisper-cli missing"))
            if (!model.isFile) return@withContext Result.failure(IllegalStateException("whisper model missing"))
            val command = listOf(
                binary.absolutePath,
                "-m", model.absolutePath,
                "-f", wav.absolutePath,
                "-nt",
                "-otxt",
                "-of", outBase.absolutePath,
            )
            val env = buildMap {
                val libPath = libDir.absolutePath
                if (libDir.isDirectory) {
                    put("DYLD_LIBRARY_PATH", prependPath(System.getenv("DYLD_LIBRARY_PATH"), libPath))
                    put("LD_LIBRARY_PATH", prependPath(System.getenv("LD_LIBRARY_PATH"), libPath))
                    put("PATH", prependPath(System.getenv("PATH"), libDir.absolutePath))
                }
                backendFile?.takeIf { it.isFile }?.let { put("GGML_BACKEND_PATH", it.absolutePath) }
            }
            val result = processRunner.run(command, workingDir = libDir.takeIf { it.isDirectory }, environment = env)
            if (result.exitCode != 0) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "whisper-cli exited ${result.exitCode}: ${result.stderr.ifBlank { result.stdout }.take(400)}",
                    ),
                )
            }
            val text = when {
                txt.isFile -> txt.readText()
                else -> result.stdout
            }.trim()
            Result.success(text)
        } finally {
            runCatching { wav.delete() }
            runCatching { txt.delete() }
            runCatching { File(outBase.absolutePath + ".json").delete() }
            runCatching { File(outBase.absolutePath + ".vtt").delete() }
            runCatching { File(outBase.absolutePath + ".srt").delete() }
        }
    }

    private fun prependPath(existing: String?, first: String): String =
        if (existing.isNullOrBlank()) first else "$first${File.pathSeparator}$existing"
}

internal class TimedWhisperProcessRunner(
    private val timeoutSeconds: Long = 120,
) : WhisperProcessRunner {
    override fun run(
        command: List<String>,
        workingDir: File?,
        environment: Map<String, String>,
    ): ProcessResult {
        val pb = ProcessBuilder(command).redirectErrorStream(false)
        workingDir?.let { pb.directory(it) }
        val env = pb.environment()
        environment.forEach { (k, v) -> env[k] = v }
        val process = pb.start()
        // Drain stdout/stderr concurrently while waiting — sequential readText() blocks on EOF
        // and would make waitFor unreachable for a hung whisper-cli.
        val stdoutBuffer = StringBuilder()
        val stderrBuffer = StringBuilder()
        val stdoutThread = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { stdoutBuffer.append(it.readText()) }
            }
        }.apply { isDaemon = true; name = "whisper-stdout"; start() }
        val stderrThread = Thread {
            runCatching {
                process.errorStream.bufferedReader().use { stderrBuffer.append(it.readText()) }
            }
        }.apply { isDaemon = true; name = "whisper-stderr"; start() }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            stdoutThread.join(1_000)
            stderrThread.join(1_000)
            return ProcessResult(-1, stdoutBuffer.toString(), "whisper-cli timed out")
        }
        stdoutThread.join(5_000)
        stderrThread.join(5_000)
        return ProcessResult(process.exitValue(), stdoutBuffer.toString(), stderrBuffer.toString())
    }
}

internal val DefaultWhisperProcessRunner: WhisperProcessRunner = TimedWhisperProcessRunner()
