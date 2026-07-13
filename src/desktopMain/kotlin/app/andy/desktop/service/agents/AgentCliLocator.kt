package app.andy.desktop.service.agents

import app.andy.model.AgentCliStatus
import app.andy.model.AgentKind
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Finds the vendor CLI binaries. GUI-launched JVMs get a minimal PATH, so we
 * ask the user's login shell first, then probe known install locations, then
 * honor explicit per-agent overrides from agents.toml.
 */
class AgentCliLocator {
    fun locateAll(overrides: Map<String, String>): List<AgentCliStatus> {
        val fromShell = lookupViaLoginShell()
        return AgentKind.entries.map { kind ->
            val discoveredBinary = overrides[kind.cliName]?.takeIf { File(it).canExecute() }
                ?: fromShell[kind.cliName]
                ?: probeKnownLocations(kind)
            val binary = discoveredBinary?.let(::canonicalExecutable)
            AgentCliStatus(
                kind = kind,
                binaryPath = binary,
                version = binary?.let(::probeVersion),
                issue = discoveredBinary?.let { source -> diagnoseInstallation(kind, source, binary) },
            )
        }
    }

    /**
     * App launchers commonly put a symlink in ~/.local/bin. Resolving it makes
     * providers that look for sibling helper binaries run from their real bundle.
     */
    private fun canonicalExecutable(path: String): String = runCatching {
        File(path).canonicalFile.path
    }.getOrDefault(path)

    private fun diagnoseInstallation(kind: AgentKind, sourcePath: String, binaryPath: String?): app.andy.model.AgentCliIssue? {
        if (kind != AgentKind.Codex || binaryPath == null) return null
        // A user may deliberately point Codex at a wrapper; only inspect the
        // packaged Codex executable whose code-mode helper contract is known.
        if (File(binaryPath).name != AgentKind.Codex.cliName) return null
        val bundledHost = File(File(binaryPath).parentFile, "codex-code-mode-host")
        if (!bundledHost.canExecute()) {
            return app.andy.model.AgentCliIssue(
                title = "Codex installation needs repair",
                detail = "Codex's code-mode helper is missing. Update or reinstall Codex before starting a chat.",
                blocksTasks = true,
            )
        }

        val sourceHost = File(File(sourcePath).parentFile, "codex-code-mode-host")
        if (sourcePath != binaryPath && !sourceHost.canExecute()) {
            return app.andy.model.AgentCliIssue(
                title = "Codex Terminal link needs repair",
                detail = "Andy will use Codex's bundled executable, but running codex in Terminal can still fail because its code-mode helper is not linked beside it.",
                repairCommand = "ln -sf ${shellQuote(bundledHost.path)} ${shellQuote(sourceHost.path)}",
            )
        }
        return null
    }

    private fun lookupViaLoginShell(): Map<String, String> {
        val osName = System.getProperty("os.name")?.lowercase().orEmpty()
        if (osName.contains("win")) return emptyMap()
        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/sh"
        val names = AgentKind.entries.joinToString(" ") { it.cliName }
        val script = "for c in $names; do printf '%s=%s\\n' \"\$c\" \"\$(command -v \"\$c\" || true)\"; done"
        val output = runCatching {
            // Merge stderr too: a noisy shell profile must not fill an unread pipe
            // and prevent the timeout from ever being reached.
            val process = ProcessBuilder(shell, "-lc", script).redirectErrorStream(true).start()
            readOutputWithin(process, timeoutSeconds = 10) ?: return emptyMap()
        }.getOrElse { return emptyMap() }
        return output.lines().mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) return@mapNotNull null
            val path = line.drop(index + 1).trim()
            if (path.isBlank() || !File(path).canExecute()) null else line.take(index) to path
        }.toMap()
    }

    private fun probeKnownLocations(kind: AgentKind): String? {
        val home = System.getProperty("user.home")
        val common = listOf(
            "/opt/homebrew/bin/${kind.cliName}",
            "/usr/local/bin/${kind.cliName}",
            "$home/.local/bin/${kind.cliName}",
            "$home/.npm-global/bin/${kind.cliName}",
        )
        val specific = when (kind) {
            AgentKind.ClaudeCode -> listOfNotNull(newestClaudeDesktopBinary(home))
            AgentKind.Codex -> listOf("/Applications/ChatGPT.app/Contents/Resources/codex")
            AgentKind.Cursor -> listOf("$home/.local/share/cursor-agent/versions").flatMap { root ->
                File(root).listFiles().orEmpty().sortedByDescending { it.name }.map { "${it.path}/cursor-agent" }
            }
            AgentKind.Antigravity -> emptyList()
        }
        return (common + specific).firstOrNull { File(it).canExecute() }
    }

    /** The Claude desktop app manages versioned claude-code installs; pick the newest. */
    private fun newestClaudeDesktopBinary(home: String): String? {
        val root = File(home, "Library/Application Support/Claude/claude-code")
        val versions = root.listFiles { file -> file.isDirectory }.orEmpty()
        return versions
            .sortedWith { a, b -> compareVersionNames(b.name, a.name) }
            .map { File(it, "claude.app/Contents/MacOS/claude") }
            .firstOrNull { it.canExecute() }
            ?.path
    }

    private fun probeVersion(binary: String): String? = runCatching {
        val process = ProcessBuilder(binary, "--version").redirectErrorStream(true).start()
        val text = readOutputWithin(process, timeoutSeconds = 10) ?: return null
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.truncateForSummary(60)
    }.getOrNull()

    /** Drains stdout in parallel so a hung process cannot bypass the caller's timeout. */
    private fun readOutputWithin(process: Process, timeoutSeconds: Long): String? {
        val output = StringBuffer()
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().use { stream -> output.append(stream.readText()) }
            }
        }, "andy-cli-output-reader").apply { isDaemon = true }
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

internal fun compareVersionNames(a: String, b: String): Int {
    val left = a.split('.').map { it.toIntOrNull() ?: -1 }
    val right = b.split('.').map { it.toIntOrNull() ?: -1 }
    for (index in 0 until maxOf(left.size, right.size)) {
        val diff = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
        if (diff != 0) return diff
    }
    return 0
}
