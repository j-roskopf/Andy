package app.andy.desktop.service.agents.acp

import java.io.File
import java.util.concurrent.TimeUnit

data class NodeRuntime(
    val node: String,
    val npx: String,
)

/** Resolves the Node runtime visible to the user's login shell, as GUI launches need. */
class NodeRuntimeLocator(
    private val environment: Map<String, String> = System.getenv(),
) {
    fun locate(): NodeRuntime? {
        val shell = environment["SHELL"]?.takeIf { it.isNotBlank() } ?: "/bin/sh"
        val resolved = runCatching {
            val process = ProcessBuilder(
                shell,
                "-lc",
                "printf 'node=%s\\nnpx=%s\\n' \"\$(command -v node || true)\" \"\$(command -v npx || true)\"",
            ).redirectErrorStream(true).start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            process.inputStream.bufferedReader().readLines()
                .mapNotNull { line ->
                    val index = line.indexOf('=')
                    if (index <= 0) null else line.substring(0, index) to line.substring(index + 1).trim()
                }
                .toMap()
        }.getOrNull().orEmpty()
        val node = resolved["node"]?.takeIf { File(it).canExecute() } ?: findOnPath("node") ?: return null
        val npx = resolved["npx"]?.takeIf { File(it).canExecute() } ?: findOnPath("npx") ?: return null
        return NodeRuntime(node, npx)
    }

    private fun findOnPath(name: String): String? {
        val path = environment["PATH"].orEmpty()
        return path.split(File.pathSeparator)
            .map { File(it, name) }
            .firstOrNull { it.canExecute() }
            ?.path
    }
}
