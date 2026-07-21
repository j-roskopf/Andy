package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentModelOption
import app.andy.model.parseAntigravityModels
import app.andy.model.parseCursorModels
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Asks installed provider CLIs for their current model lists so Andy's composer
 * can stay current without a hardcoded catalog bump for every release.
 *
 * Claude Code and Codex do not expose a stable non-interactive list command yet,
 * so those providers keep using [app.andy.model.AgentModelCatalog].
 */
internal class ProviderModelProbe {
    fun query(agent: AgentKind, binary: String): List<AgentModelOption>? = when (agent) {
        AgentKind.Antigravity -> runModelsCommand(binary, listOf("models"))?.let(::parseAntigravityModels)?.takeIf { it.isNotEmpty() }
        AgentKind.Cursor -> runModelsCommand(binary, listOf("models"))?.let(::parseCursorModels)?.takeIf { it.isNotEmpty() }
        AgentKind.ClaudeCode, AgentKind.Codex -> null
    }

    private fun runModelsCommand(binary: String, args: List<String>): String? = runCatching {
        if (!File(binary).canExecute()) return null
        val process = ProcessBuilder(listOf(binary) + args)
            .directory(File(System.getProperty("user.home")))
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val output = StringBuffer()
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().use { stream -> output.append(stream.readText()) }
            }
        }, "andy-provider-models").apply { isDaemon = true }
        reader.start()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
            reader.join(1_000)
            return null
        }
        reader.join(1_000)
        if (process.exitValue() != 0) return null
        output.toString().takeIf { it.isNotBlank() }
    }.getOrNull()
}
