package app.andy.desktop.service

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Resolves the host `git` binary. GUI apps and Flatpak sandboxes often inherit a
 * minimal PATH that omits `/usr/bin`, so bare `ProcessBuilder("git", …)` fails even
 * when the host binary is reachable at a well-known absolute path.
 */
object GitLocator {
    private val cached = AtomicReference<String?>()

    /** Resolves once per process; memoized after the first successful lookup. */
    fun resolve(): String? {
        cached.get()?.let { return it }
        val resolved = locate()
        cached.set(resolved)
        return resolved
    }

    internal fun clearCacheForTests() {
        cached.set(null)
    }

    internal fun locate(
        processPath: String? = System.getenv("PATH"),
        loginShellEnv: Map<String, String> = LoginShellEnvironment.current(),
        knownPaths: List<String> = defaultKnownPaths(),
        runShell: (List<String>) -> String? = ::runShellLookup,
        osName: String = System.getProperty("os.name").orEmpty(),
    ): String? {
        val windows = osName.contains("win", ignoreCase = true)
        fromPath(processPath, windows)?.let { return it }
        fromPath(loginShellEnv["PATH"], windows)?.let { return it }
        firstExecutable(knownPaths)?.let { return it }
        if (windows) return null
        return lookupViaLoginShell(runShell)
    }

    internal fun fromPath(pathEnv: String?, windows: Boolean = false): String? {
        if (pathEnv.isNullOrBlank()) return null
        val names = if (windows) {
            // PATHEXT normally resolves bare "git", but File(...).isFile does not.
            listOf("git.exe", "git.cmd", "git.bat", "git")
        } else {
            listOf("git")
        }
        return pathEnv.split(File.pathSeparatorChar)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { dir -> names.asSequence().map { name -> File(dir, name) } }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.path
    }

    internal fun defaultKnownPaths(
        osName: String = System.getProperty("os.name").orEmpty(),
    ): List<String> {
        val home = System.getProperty("user.home").orEmpty()
        if (osName.contains("win", ignoreCase = true)) {
            val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
            val programFilesX86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
            val localAppData = System.getenv("LOCALAPPDATA").orEmpty()
            return listOf(
                "$programFiles\\Git\\cmd\\git.exe",
                "$programFiles\\Git\\bin\\git.exe",
                "$programFilesX86\\Git\\cmd\\git.exe",
                "$programFilesX86\\Git\\bin\\git.exe",
                "$localAppData\\Programs\\Git\\cmd\\git.exe",
            ).filter { it.isNotBlank() && !it.startsWith("\\") }
        }
        return listOf(
            "/usr/bin/git",
            "/usr/local/bin/git",
            "/opt/homebrew/bin/git",
            "$home/.local/bin/git",
        )
    }

    private fun firstExecutable(paths: List<String>): String? =
        paths.firstOrNull { File(it).canExecute() }

    private fun lookupViaLoginShell(runShell: (List<String>) -> String?): String? {
        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/sh"
        val output = runShell(listOf(shell, "-lc", "command -v git || true")) ?: return null
        val path = output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        return path.takeIf { File(it).canExecute() }
    }

    private fun runShellLookup(command: List<String>): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        readOutputWithin(process, timeoutSeconds = 10)
    }.getOrNull()

    private fun readOutputWithin(process: Process, timeoutSeconds: Long): String? {
        val output = StringBuffer()
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().use { stream -> output.append(stream.readText()) }
            }
        }, "andy-git-locator-reader").apply { isDaemon = true }
        reader.start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
            reader.join(1_000)
            return null
        }
        reader.join(1_000)
        return output.toString()
    }
}
