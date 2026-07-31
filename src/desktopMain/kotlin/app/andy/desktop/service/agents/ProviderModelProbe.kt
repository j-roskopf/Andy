package app.andy.desktop.service.agents

import app.andy.desktop.service.LoginShellEnvironment
import app.andy.model.AgentKind
import app.andy.model.AgentModelOption
import app.andy.model.parseAntigravityModels
import app.andy.model.parseCursorModels
import app.andy.model.parseOpenCodeModels
import app.andy.model.parsePiModels
import app.andy.model.parseHermesModels
import app.andy.model.parseOpenClawModels
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
        AgentKind.OpenCode -> runModelsCommand(binary, listOf("models"))?.let(::parseOpenCodeModels)?.takeIf { it.isNotEmpty() }
            ?: runModelsCommand(binary, listOf("stats", "--models"))?.let(::parseOpenCodeModels)?.takeIf { it.isNotEmpty() }
        AgentKind.Pi -> runModelsCommand(binary, listOf("--list-models"))?.let(::parsePiModels)?.takeIf { it.isNotEmpty() }
        AgentKind.Hermes -> runModelsCommand(binary, listOf("models", "list", "--offline", "--json"))?.let(::parseHermesModels)?.takeIf { it.isNotEmpty() }
            ?: runModelsCommand(binary, listOf("models", "list", "--json"))?.let(::parseHermesModels)?.takeIf { it.isNotEmpty() }
        AgentKind.OpenClaw -> runModelsCommand(binary, listOf("models", "list", "--json"))?.let(::parseOpenClawModels)?.takeIf { it.isNotEmpty() }
        AgentKind.ClaudeCode, AgentKind.Codex -> null
    }

    private fun runModelsCommand(binary: String, args: List<String>): String? = runCatching {
        if (!File(binary).canExecute()) return null
        val process = ProcessBuilder(listOf(binary) + args)
            .directory(File(System.getProperty("user.home")))
            .redirectErrorStream(true)
            .also { it.environment().putAll(LoginShellEnvironment.current()) }
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
