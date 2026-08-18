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
                val prepared = prepareCommand(spec, binary, preflight = true)
                val process = ProcessBuilder(prepared.command)
                    .redirectErrorStream(true)
                    .discardAcpPreflightStdin()
                    .applyLaunchEnv(env, prepared.command, prepared.nodeBinary)
                    .start()
                // If stdin stayed a pipe, adapters that ignore `--version` (pi-acp) wait forever.
                runCatching { process.outputStream.close() }
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
                prepared.command
            }
        }

    suspend fun launch(
        spec: AcpLaunchSpec,
        binary: String?,
        cwd: String,
        env: Map<String, String>,
    ): AcpProcess = withContext(Dispatchers.IO) {
        val prepared = prepareCommand(spec, binary, preflight = false)
        val process = ProcessBuilder(prepared.command)
            .directory(File(cwd))
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .applyLaunchEnv(env, prepared.command, prepared.nodeBinary)
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
        AcpProcess(process, prepared.command)
    }

    private fun prepareCommand(spec: AcpLaunchSpec, binary: String?, preflight: Boolean): PreparedAcpCommand =
        when (spec) {
            is AcpLaunchSpec.Native -> PreparedAcpCommand(
                command = if (preflight) {
                    listOf(binary ?: spec.command, "--version")
                } else {
                    listOf(binary ?: spec.command) + spec.args
                },
                nodeBinary = null,
            )
            is AcpLaunchSpec.Npx -> {
                val runtime = nodeLocator.locate() ?: throw IOException("Node.js/npx was not found")
                val packageArgs = listOf("-y", "${spec.packageName}@${spec.version}") +
                    if (preflight) listOf("--version") else spec.extraArgs
                PreparedAcpCommand(
                    command = acpNpxCommand(runtime, packageArgs),
                    nodeBinary = runtime.node,
                )
            }
        }
}

private data class PreparedAcpCommand(
    val command: List<String>,
    val nodeBinary: String?,
)

/**
 * ACP adapters speak JSON-RPC on stdin. `pi-acp` (and similar) ignore `--version`
 * and hang on an open pipe — the previous preflight default — until we time out.
 * Feed EOF from process start so a version probe can actually exit.
 */
internal fun ProcessBuilder.discardAcpPreflightStdin(): ProcessBuilder {
    val os = System.getProperty("os.name").orEmpty()
    if (!os.startsWith("Windows", ignoreCase = true)) {
        redirectInput(File("/dev/null"))
    }
    return this
}

private fun ProcessBuilder.applyLaunchEnv(
    env: Map<String, String>,
    command: List<String>,
    nodeBinary: String? = null,
): ProcessBuilder {
    if (env.isNotEmpty()) {
        environment().putAll(env)
    }
    ensureNodeDirOnPath(environment(), command, nodeBinary)
    return this
}

/**
 * Builds the argv for an npx-backed ACP agent.
 *
 * Homebrew/nvm `npx` is usually a `#!/usr/bin/env node` script. GUI-launched JVMs often
 * lack `/opt/homebrew/bin` (and similar) on PATH, so exec'ing that script alone fails with
 * `env: node: No such file or directory`. For those JS entry points, invoke the absolute
 * node binary with the npx script as its argument.
 *
 * asdf/Volta-style native or shell shims are not valid Node scripts — wrapping them with
 * `node` fails — so those stay direct invocations. [ensureNodeDirOnPath] still prepends
 * the node directory so nested `env node` lookups succeed. Windows `.cmd`/`.ps1` shims
 * also stay direct — `node npx.cmd` is not valid there.
 */
internal fun acpNpxCommand(
    runtime: NodeRuntime,
    packageArgs: List<String>,
    isNodeScript: (String) -> Boolean = ::npxLooksLikeNodeScript,
): List<String> {
    val npx = runtime.npx
    val lower = npx.lowercase()
    if (lower.endsWith(".cmd") || lower.endsWith(".bat") || lower.endsWith(".ps1")) {
        return listOf(npx) + packageArgs
    }
    return if (isNodeScript(npx)) {
        listOf(runtime.node, npx) + packageArgs
    } else {
        listOf(npx) + packageArgs
    }
}

/** True when [path] is a Node-executed script (shebang or plain JS), not a native/shell shim. */
internal fun npxLooksLikeNodeScript(path: String): Boolean {
    val file = File(path)
    if (!file.isFile) {
        // Missing/unreadable path: keep the historical unix wrap (Homebrew/nvm).
        return true
    }
    val firstLine = runCatching {
        file.bufferedReader().use { it.readLine() }
    }.getOrNull() ?: return true
    if (firstLine.startsWith("#!")) {
        return firstLine.contains("node", ignoreCase = true)
    }
    val trimmed = firstLine.trimStart()
    return trimmed.startsWith("//") ||
        trimmed.startsWith("/*") ||
        trimmed.startsWith("'use strict'") ||
        trimmed.startsWith("\"use strict\"") ||
        trimmed.startsWith("import ") ||
        trimmed.contains("require(") ||
        trimmed.contains("module.exports")
}

/**
 * Ensures the directory containing `node` is on PATH for any nested `env node` shebang.
 *
 * [nodeBinary] covers direct npx-shim launches where argv[0] is not `node`.
 */
internal fun ensureNodeDirOnPath(
    environment: MutableMap<String, String>,
    command: List<String>,
    nodeBinary: String? = null,
) {
    val nodePath = nodeBinary
        ?: command.firstOrNull()
            ?.takeIf { File(it).nameWithoutExtension.equals("node", ignoreCase = true) }
        ?: return
    val nodeDir = File(nodePath).absoluteFile.parent ?: return
    val pathKey = environment.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
    val existing = environment[pathKey].orEmpty()
    val parts = existing.split(File.pathSeparator).filter { it.isNotEmpty() }
    if (parts.any { File(it).absoluteFile == File(nodeDir).absoluteFile }) return
    environment[pathKey] = nodeDir + if (existing.isEmpty()) "" else File.pathSeparator + existing
}
