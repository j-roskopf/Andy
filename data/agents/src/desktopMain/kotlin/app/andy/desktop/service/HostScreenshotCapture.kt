package app.andy.desktop.service

import app.andy.service.HostScreenshotCommand
import java.io.File
import java.util.concurrent.TimeUnit

/** Captures the whole host desktop via the OS screenshot binary. */
object HostScreenshotCapture {
    fun capturePngBytes(): ByteArray {
        val tool = detectTool()
            ?: error(
                "No host screenshot tool found (tried screencapture, grim, scrot, import). " +
                    "Install one and retry.",
            )
        val output = File.createTempFile("andy-host-screenshot-", ".png")
        try {
            val argv = HostScreenshotCommand.argv(tool, output.absolutePath)
                ?: error("Unsupported screenshot tool: $tool")
            val process = ProcessBuilder(argv).redirectErrorStream(true).start()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                error("Host screenshot timed out using $tool")
            }
            if (process.exitValue() != 0) {
                val err = process.inputStream.bufferedReader().readText().take(300)
                error(
                    "Host screenshot failed (exit ${process.exitValue()}): " +
                        err.ifBlank { tool },
                )
            }
            check(output.isFile && output.length() > 0L) {
                "Host screenshot produced no image. On macOS, Screen Recording permission " +
                    "may be required for the Andy/andyd process (System Settings → Privacy & Security)."
            }
            return output.readBytes()
        } finally {
            output.delete()
        }
    }

    fun detectTool(): String? {
        val candidates = listOf("screencapture", "grim", "scrot", "import")
        val found = candidates.mapNotNull { name ->
            runCatching {
                val process = ProcessBuilder("sh", "-c", "command -v $name")
                    .redirectErrorStream(true)
                    .start()
                val out = process.inputStream.bufferedReader().readText().trim()
                out.takeIf { process.waitFor() == 0 && it.isNotBlank() }
            }.getOrNull()
        }
        return HostScreenshotCommand.resolveTool(found)
    }
}
