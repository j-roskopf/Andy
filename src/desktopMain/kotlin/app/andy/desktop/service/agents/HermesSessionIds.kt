package app.andy.desktop.service.agents

import app.andy.model.AgentTask
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

internal object HermesSessionIds {
    private val banner = Regex("Session:\\s+([0-9]{8}_[0-9]{6}_[a-f0-9]+)", RegexOption.IGNORE_CASE)
    fun resolveForTask(task: AgentTask): String? = task.vendorSessionId?.takeIf { it.isNotBlank() }
    internal fun parseSessionListOutput(output: String): List<String> = runCatching {
        Json.parseToJsonElement(output).jsonArray.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
    }.getOrElse { banner.findAll(output).map { it.groupValues[1] }.toList().distinct() }
    fun findNewestSession(binary: String?, cwd: String?): String? = runCatching {
        if (binary.isNullOrBlank() || !File(binary).canExecute()) return null
        val process = ProcessBuilder(binary, "sessions", "list", "--json").directory(cwd?.let(::File)).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(8, TimeUnit.SECONDS) || process.exitValue() != 0) null else parseSessionListOutput(output).lastOrNull()
    }.getOrNull()
}
