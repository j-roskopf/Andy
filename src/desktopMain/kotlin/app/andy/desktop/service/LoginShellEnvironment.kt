package app.andy.desktop.service

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Captures the user's real login-shell environment (PATH, JAVA_HOME, NVM_DIR, custom exports
 * from `.zshrc`/`.bashrc`, etc.) so processes Andy launches see what a normal terminal would —
 * not the minimal environment a GUI app gets from Finder/Dock/launchd.
 *
 * zsh only sources `.zshrc` for *interactive* shells (bash's default `.bashrc` often early-
 * returns for non-interactive ones too), so this must run the shell with `-i`, not just `-l`
 * like [AgentCliLocator]'s narrower binary-path lookup.
 */
object LoginShellEnvironment {
    private const val ENV_ENTRY_SEPARATOR = '\u0000'

    private val cached = AtomicReference<Map<String, String>?>(null)

    /** Captures once per process (forks a real shell); memoized after the first call. */
    fun current(): Map<String, String> {
        cached.get()?.let { return it }
        val resolved = capture()
        cached.set(resolved)
        return resolved
    }

    internal fun capture(
        shell: String? = System.getenv("SHELL"),
        osName: String = System.getProperty("os.name").orEmpty(),
        runShell: (List<String>) -> String? = ::runShellCapture,
    ): Map<String, String> {
        if (osName.contains("win", ignoreCase = true)) return emptyMap()
        val resolvedShell = shell?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
        val begin = "__ANDY_ENV_BEGIN__"
        val end = "__ANDY_ENV_END__"
        // Markers isolate the `env -0` payload from shell-profile noise (motd, oh-my-zsh
        // banners, powerlevel10k instant-prompt warnings) that also prints during an
        // interactive shell's startup.
        val script = "printf '%s\\n' $begin; env -0; printf '%s\\n' $end"
        val output = runShell(listOf(resolvedShell, "-ilc", script)) ?: return emptyMap()
        return parseMarkedEnv(output, begin, end)
    }

    internal fun parseMarkedEnv(raw: String, begin: String, end: String): Map<String, String> {
        val beginIdx = raw.indexOf(begin)
        if (beginIdx < 0) return emptyMap()
        val endIdx = raw.indexOf(end, beginIdx)
        if (endIdx < 0 || endIdx <= beginIdx) return emptyMap()
        val block = raw.substring(beginIdx + begin.length, endIdx)
        return block.split(ENV_ENTRY_SEPARATOR)
            .asSequence()
            .map { it.removePrefix("\n") }
            .filter { it.isNotEmpty() }
            .mapNotNull { entry ->
                val idx = entry.indexOf('=')
                if (idx <= 0) null else entry.substring(0, idx) to entry.substring(idx + 1)
            }
            .toMap()
    }

    /**
     * Merges stderr into stdout and drains both concurrently: a noisy shell profile must not
     * fill an unread pipe and prevent the timeout below from ever being reached (same failure
     * mode `AgentCliLocator.lookupViaLoginShell` guards against).
     */
    private fun runShellCapture(command: List<String>): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = StringBuffer()
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().use { stream -> output.append(stream.readText()) }
            }
        }, "andy-login-shell-env-reader").apply { isDaemon = true }
        reader.start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
            reader.join(1_000)
            return null
        }
        reader.join(1_000)
        output.toString()
    }.getOrNull()
}
