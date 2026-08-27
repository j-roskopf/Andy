package app.andy.desktop.service.ios

import app.andy.desktop.service.CommandRunner
import app.andy.model.PrefEntry
import app.andy.model.PrefType
import app.andy.service.CommandResult
import app.andy.service.SharedPrefsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * iOS UserDefaults (Phase 2.3): `Library/Preferences/<bundleid>.plist` inside the simulator's
 * app data container. Always round-trips through `plutil -convert json`/`xml1` per the plan's
 * standing risk note — hand-parsing OpenStep/binary plists is not worth it.
 */
class DesktopIosSharedPrefsService(
    private val runner: CommandRunner,
) : SharedPrefsService {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listFiles(serial: String, packageName: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val containerPath = resolveContainer(serial, packageName)
                    ?: error("Could not resolve app container for $packageName")
                val plistName = "$packageName.plist"
                val file = File(File(containerPath, "Library/Preferences"), plistName)
                if (file.isFile) listOf(plistName) else emptyList()
            }
        }

    override suspend fun read(serial: String, packageName: String, fileName: String): Result<List<PrefEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = resolvePreferencesFile(serial, packageName, fileName)
                val map = readPlistJson(file) ?: error("Unable to read ${file.name}")
                parseEntries(map)
            }
        }

    override suspend fun upsert(
        serial: String,
        packageName: String,
        fileName: String,
        entry: PrefEntry,
    ): CommandResult = withContext(Dispatchers.IO) {
        mutate(serial, packageName, fileName) { existing -> existing + (entry.key to entry.toJsonElement()) }
    }

    override suspend fun delete(
        serial: String,
        packageName: String,
        fileName: String,
        key: String,
    ): CommandResult = withContext(Dispatchers.IO) {
        mutate(serial, packageName, fileName) { existing -> existing - key }
    }

    private suspend fun mutate(
        serial: String,
        packageName: String,
        fileName: String,
        transform: (Map<String, JsonElement>) -> Map<String, JsonElement>,
    ): CommandResult {
        val file = runCatching { resolvePreferencesFile(serial, packageName, fileName) }
            .getOrElse { return CommandResult.failure(it.message ?: "Invalid preferences file") }
        val existing = readPlistJson(file) ?: emptyMap()
        val updated = transform(existing)
        val tempJson = File.createTempFile("andy-ios-prefs-", ".json")
        return try {
            tempJson.writeText(json.encodeToString(JsonObject(updated)))
            file.parentFile?.mkdirs()
            val convert = runner.run(
                listOf("plutil", "-convert", "xml1", "-o", file.absolutePath, tempJson.absolutePath),
            )
            if (!convert.isSuccess) {
                CommandResult.failure(convert.stderr.ifBlank { convert.stdout }.ifBlank { "Failed to write ${file.name}" })
            } else {
                CommandResult.success("Updated ${file.name}")
            }
        } finally {
            tempJson.delete()
        }
    }

    private suspend fun resolveContainer(serial: String, packageName: String): String? {
        val result = runner.run(listOf("xcrun", "simctl", "get_app_container", serial, packageName, "data"))
        return result.stdout.trim().takeIf { result.isSuccess && it.isNotBlank() }
    }

    private suspend fun resolvePreferencesFile(serial: String, packageName: String, fileName: String): File {
        val containerPath = resolveContainer(serial, packageName)
            ?: error("Could not resolve app container for $packageName")
        val safeName = requirePlistName(fileName)
        return File(File(containerPath, "Library/Preferences"), safeName)
    }

    private suspend fun readPlistJson(file: File): Map<String, JsonElement>? {
        if (!file.isFile) return emptyMap()
        val result = runner.run(listOf("plutil", "-convert", "json", "-o", "-", file.absolutePath))
        if (!result.isSuccess) return null
        return runCatching { json.parseToJsonElement(result.stdout).jsonObject }.getOrNull()
    }

    private fun requirePlistName(fileName: String): String {
        val name = fileName.substringAfterLast('/').trim()
        require(
            name.endsWith(".plist") &&
                name.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' },
        ) {
            "Invalid preferences file name"
        }
        return name
    }

    companion object {
        internal fun parseEntries(map: Map<String, JsonElement>): List<PrefEntry> =
            map.mapNotNull { (key, element) -> entryFromJson(key, element) }.sortedBy { it.key }

        private fun entryFromJson(key: String, element: JsonElement): PrefEntry? = when (element) {
            is JsonArray -> {
                val values = element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                PrefEntry(key, PrefType.StringSet, values.joinToString("\n"))
            }
            is JsonPrimitive -> when {
                element.isString -> PrefEntry(key, PrefType.String, element.content)
                element.booleanOrNull != null -> PrefEntry(key, PrefType.Boolean, element.boolean.toString())
                element.longOrNull != null -> {
                    val long = element.long
                    if (long in Int.MIN_VALUE..Int.MAX_VALUE) {
                        PrefEntry(key, PrefType.Int, long.toString())
                    } else {
                        PrefEntry(key, PrefType.Long, long.toString())
                    }
                }
                element.doubleOrNull != null -> PrefEntry(key, PrefType.Float, element.double.toString())
                else -> null
            }
            // Nested dictionaries have no flat key/value UI analog; not editable here.
            else -> null
        }

        internal fun PrefEntry.toJsonElement(): JsonElement = when (type) {
            PrefType.String -> JsonPrimitive(value)
            PrefType.Int -> JsonPrimitive(value.toIntOrNull() ?: 0)
            PrefType.Long -> JsonPrimitive(value.toLongOrNull() ?: 0L)
            PrefType.Float -> JsonPrimitive(value.toDoubleOrNull() ?: 0.0)
            PrefType.Boolean -> JsonPrimitive(value.toBooleanStrictOrNull() ?: false)
            PrefType.StringSet -> JsonArray(
                if (value.isEmpty()) emptyList() else value.split("\n").map { JsonPrimitive(it) },
            )
        }
    }
}
