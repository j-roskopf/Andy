package app.andy.desktop.service.agents

import app.andy.model.AgentTask
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

internal object OpenClawSessionIds {
    fun resolveForTask(task: AgentTask): String? = task.vendorSessionId?.takeIf { it.isNotBlank() }
    internal fun parseSessionListOutput(output: String): List<String> = runCatching {
        val root = Json.parseToJsonElement(output)
        val array = if (root is kotlinx.serialization.json.JsonArray) root else root.jsonObject["sessions"]!!.jsonArray
        array.mapNotNull { item ->
            val obj = item.jsonObject
            (obj["key"] ?: obj["sessionKey"] ?: obj["id"])?.jsonPrimitive?.content
        }
    }.getOrElse {
        output.lineSequence().map { it.trim() }.filter { it.isNotBlank() && (it.startsWith("agent:") || !it.contains(' ')) }.toList().distinct()
    }
    fun findNewestSession(binary: String?, cwd: String?): String? = runCatching {
        if (binary.isNullOrBlank() || !File(binary).canExecute()) return null
        val process = ProcessBuilder(binary, "sessions", "list", "--json").directory(cwd?.let(::File)).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(8, TimeUnit.SECONDS) || process.exitValue() != 0) null else parseSessionListOutput(output).lastOrNull()
    }.getOrNull()
}
