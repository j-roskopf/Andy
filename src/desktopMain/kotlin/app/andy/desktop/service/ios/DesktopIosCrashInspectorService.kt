package app.andy.desktop.service.ios

import app.andy.desktop.parser.IosIpsReport
import app.andy.desktop.parser.IosParsers
import app.andy.desktop.service.CommandRunner
import app.andy.model.CrashKind
import app.andy.model.CrashRecord
import app.andy.service.CommandResult
import app.andy.service.CrashInspectorService
import app.andy.service.IosTargetRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

private const val IpsPrefix = "ips:"

/**
 * Reads simulator crash reports from the host `~/Library/Logs/DiagnosticReports` (§Phase 3.2).
 * Simulator apps are host processes, so their `.ips` reports land alongside every other macOS
 * crash log — this service filters to reports whose binary image path resolves under
 * `CoreSimulator/Devices/<udid>` for the selected target, plus best-effort `atos` symbolication
 * against `.dSYM` bundles discovered via Spotlight (`mdfind`).
 */
class DesktopIosCrashInspectorService(
    private val runner: CommandRunner,
    private val diagnosticReportsDir: File = File(System.getProperty("user.home"), "Library/Logs/DiagnosticReports"),
) : CrashInspectorService {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listCrashes(serial: String): List<CrashRecord> = withContext(Dispatchers.IO) {
        val target = IosTargetRegistry.target(serial)
        val files = diagnosticReportsDir.listFiles { file -> file.isFile && file.extension.equals("ips", ignoreCase = true) }
            ?: return@withContext emptyList()
        files.sortedByDescending { it.lastModified() }
            .take(200)
            .mapNotNull { file ->
                val report = runCatching { IosParsers.parseIpsReport(file.name, file.readText()) }.getOrNull() ?: return@mapNotNull null
                report to file
            }
            .filter { (report, _) ->
                // Scope to this simulator when the report resolved a device UDID; reports whose
                // path we couldn't attribute (system frameworks, older schema) are still shown
                // rather than silently hidden.
                target == null || report.simulatorUdid == null || report.simulatorUdid == target.udid
            }
            .map { (report, file) ->
                CrashRecord(
                    id = "$IpsPrefix${file.absolutePath}",
                    kind = if (report.exceptionType?.contains("SIGABRT", ignoreCase = true) == true ||
                        report.exceptionType?.contains("EXC_", ignoreCase = true) == true
                    ) {
                        CrashKind.NativeCrash
                    } else {
                        CrashKind.JavaCrash
                    },
                    packageName = report.processName,
                    timestampMillis = report.timestampMillis,
                    summary = report.summary,
                )
            }
    }

    override suspend fun loadCrash(serial: String, id: String): String = withContext(Dispatchers.IO) {
        val path = id.removePrefix(IpsPrefix)
        val file = File(path)
        if (!file.isFile) return@withContext "Crash report not found: $path"
        val text = runCatching { file.readText() }.getOrElse { return@withContext it.message ?: "Failed to read crash report" }
        val report = runCatching { IosParsers.parseIpsReport(file.name, text) }.getOrNull()
        val symbolicated = report?.let { runCatching { symbolicate(it) }.getOrNull() }
        if (symbolicated.isNullOrBlank()) text else "$text\n\n--- atos symbolication ---\n$symbolicated"
    }

    override suspend fun exportCrash(serial: String, id: String, localPath: String): CommandResult = withContext(Dispatchers.IO) {
        val text = loadCrash(serial, id)
        if (text.isBlank()) return@withContext CommandResult.failure("Crash entry not found or unreadable: $id")
        runCatching {
            val out = File(localPath)
            out.parentFile?.mkdirs()
            out.writeText(text)
            CommandResult.success(out.absolutePath)
        }.getOrElse { CommandResult.failure(it.message ?: "Export failed") }
    }

    /**
     * Best-effort symbolication of the crashing thread's frames in the app's own binary image.
     * Discovers a matching `.dSYM` via Spotlight; returns null when unavailable so callers fall
     * back to the unsymbolicated report rather than failing the whole load.
     */
    private suspend fun symbolicate(report: IosIpsReport): String? {
        val body = runCatching {
            val text = report.rawText
            val firstLineEnd = text.indexOf('\n')
            if (firstLineEnd <= 0) return null
            json.parseToJsonElement(text.substring(firstLineEnd + 1).trim()).jsonObject
        }.getOrNull() ?: return null

        val usedImages = body["usedImages"]?.jsonArray ?: return null
        val appImageIndex = usedImages.indexOfFirst { element ->
            val obj = element.jsonObject
            val name = obj.stringOrNull("name")
            val path = obj.stringOrNull("path")
            name != null && (path?.contains("/Containers/Bundle/Application/") == true || name == report.processName)
        }
        if (appImageIndex < 0) return null
        val appImage = usedImages[appImageIndex].jsonObject
        val base = appImage.longOrNull("base") ?: return null
        val arch = appImage.stringOrNull("arch") ?: "arm64"
        val imagePath = appImage.stringOrNull("path") ?: return null

        val threads = body["threads"]?.jsonArray ?: return null
        val crashingThread = threads.firstOrNull { it.jsonObject["triggered"]?.jsonPrimitive?.content == "true" }
            ?: threads.firstOrNull()
        val frames = crashingThread?.jsonObject?.get("frames")?.jsonArray ?: return null
        val addresses = frames.mapNotNull { frame ->
            val obj = frame.jsonObject
            if (obj.intOrNull("imageIndex") != appImageIndex) return@mapNotNull null
            val offset = obj.longOrNull("imageOffset") ?: return@mapNotNull null
            base + offset
        }
        if (addresses.isEmpty()) return null

        val dsym = discoverDsym(File(imagePath).name) ?: return null
        val binary = File(dsym, "Contents/Resources/DWARF/${File(imagePath).name}")
        val executable = if (binary.isFile) binary.absolutePath else imagePath
        val command = buildList {
            add("atos"); add("-o"); add(executable); add("-arch"); add(arch)
            add("-l"); add("0x${base.toString(16)}")
            addAll(addresses.map { "0x${it.toString(16)}" })
        }
        val result = runner.run(command, timeoutSeconds = 30)
        return if (result.isSuccess) result.stdout.trim().ifBlank { null } else null
    }

    /** Locates a `.dSYM` bundle by binary name via Spotlight; null when not indexed/found. */
    private suspend fun discoverDsym(binaryName: String): File? {
        val result = runner.run(listOf("mdfind", "kMDItemFSName == '$binaryName.dSYM'"), timeoutSeconds = 15)
        if (!result.isSuccess) return null
        val path = result.stdout.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return null
        val dir = File(path)
        return if (dir.isDirectory) dir else null
    }

    private fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.content
    private fun JsonObject.longOrNull(key: String): Long? = runCatching { this[key]?.jsonPrimitive?.long }.getOrNull()
        ?: this[key]?.jsonPrimitive?.content?.let { raw -> runCatching { if (raw.startsWith("0x")) raw.removePrefix("0x").toLong(16) else raw.toLong() }.getOrNull() }
    private fun JsonObject.intOrNull(key: String): Int? = longOrNull(key)?.toInt()
}
