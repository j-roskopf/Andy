package app.andy.desktop.service.agents.acp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AcpProcess(
    val process: Process,
    val command: List<String>,
)

/** Spawns ACP over stdio and continuously drains stderr so a noisy agent cannot deadlock. */
class AcpProcessLauncher(
    private val nodeLocator: NodeRuntimeLocator = NodeRuntimeLocator(),
    private val onDiagnostics: (String) -> Unit = {},
) {
    fun withDiagnostics(callback: (String) -> Unit): AcpProcessLauncher =
        AcpProcessLauncher(nodeLocator, callback)

    suspend fun preflight(
        spec: AcpLaunchSpec,
        binary: String? = null,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = 20_000,
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val command = commandFor(spec, binary, preflight = true)
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .applyLaunchEnv(env, command)
                    .start()
                val output = StringBuilder()
                val reader = Thread {
                    runCatching { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { output.appendLine(it) } } }
                }.apply { isDaemon = true; start() }
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    error("ACP preflight timed out")
                }
                reader.join(1_000)
                if (process.exitValue() != 0) {
                    error(output.toString().trim().ifBlank { "ACP preflight exited with ${process.exitValue()}" })
                }
                command
            }
        }

    suspend fun launch(
        spec: AcpLaunchSpec,
        binary: String?,
        cwd: String,
        env: Map<String, String>,
    ): AcpProcess = withContext(Dispatchers.IO) {
        val command = commandFor(spec, binary, preflight = false)
        val process = ProcessBuilder(command)
            .directory(File(cwd))
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .applyLaunchEnv(env, command)
            .start()
        Thread({
            runCatching {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> onDiagnostics("stderr: $line\n") }
                }
            }
        }, "andy-acp-stderr-${File(cwd).name}").apply {
            isDaemon = true
            start()
        }
        AcpProcess(process, command)
    }

    private fun commandFor(spec: AcpLaunchSpec, binary: String?, preflight: Boolean): List<String> = when (spec) {
        is AcpLaunchSpec.Native -> if (preflight) {
            listOf(binary ?: spec.command, "--version")
        } else {
            listOf(binary ?: spec.command) + spec.args
        }
        is AcpLaunchSpec.Npx -> {
            val runtime = nodeLocator.locate() ?: throw IOException("Node.js/npx was not found")
            val packageArgs = listOf("-y", "${spec.packageName}@${spec.version}") +
                if (preflight) listOf("--version") else emptyList()
            acpNpxCommand(runtime, packageArgs)
        }
    }
}

private fun ProcessBuilder.applyLaunchEnv(env: Map<String, String>, command: List<String>): ProcessBuilder {
    if (env.isNotEmpty()) {
        environment().putAll(env)
    }
    ensureNodeDirOnPath(environment(), command)
    return this
}

/**
 * Builds the argv for an npx-backed ACP agent.
 *
 * Homebrew/nvm `npx` is a `#!/usr/bin/env node` script. GUI-launched JVMs often lack
 * `/opt/homebrew/bin` (and similar) on PATH, so exec'ing npx alone fails with
 * `env: node: No such file or directory`. Invoking the absolute node binary with the
 * npx script as its argument bypasses that shebang lookup. Windows `.cmd`/`.ps1` shims
 * stay direct invocations — `node npx.cmd` is not valid there.
 */
internal fun acpNpxCommand(runtime: NodeRuntime, packageArgs: List<String>): List<String> {
    val npx = runtime.npx
    val lower = npx.lowercase()
    return if (lower.endsWith(".cmd") || lower.endsWith(".bat") || lower.endsWith(".ps1")) {
        listOf(npx) + packageArgs
    } else {
        listOf(runtime.node, npx) + packageArgs
    }
}

/** Ensures the directory containing `node` is on PATH for any nested `env node` shebang. */
internal fun ensureNodeDirOnPath(environment: MutableMap<String, String>, command: List<String>) {
    val nodePath = command.firstOrNull()
        ?.takeIf { File(it).nameWithoutExtension.equals("node", ignoreCase = true) }
        ?: return
    val nodeDir = File(nodePath).absoluteFile.parent ?: return
    val pathKey = environment.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
    val existing = environment[pathKey].orEmpty()
    val parts = existing.split(File.pathSeparator).filter { it.isNotEmpty() }
    if (parts.any { File(it).absoluteFile == File(nodeDir).absoluteFile }) return
    environment[pathKey] = nodeDir + if (existing.isEmpty()) "" else File.pathSeparator + existing
}
