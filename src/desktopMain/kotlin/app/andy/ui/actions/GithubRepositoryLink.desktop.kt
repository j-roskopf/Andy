package app.andy.ui.actions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

internal actual suspend fun detectGithubRepositoryUrl(contextDir: String): String? = withContext(Dispatchers.IO) {
    val directory = File(contextDir)
    if (!directory.isDirectory) return@withContext null

    val process = runCatching {
        ProcessBuilder("git", "-C", directory.absolutePath, "remote", "get-url", "origin")
            .redirectErrorStream(true)
            .start()
    }.getOrNull() ?: return@withContext null

    if (!process.waitFor(3, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return@withContext null
    }
    if (process.exitValue() != 0) return@withContext null

    githubRepositoryUrl(process.inputStream.bufferedReader().use { it.readText() })
}
