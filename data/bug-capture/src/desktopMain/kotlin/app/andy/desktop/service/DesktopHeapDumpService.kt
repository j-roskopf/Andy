package app.andy.desktop.service

import app.andy.model.HeapDumpInfo
import app.andy.service.CommandResult
import app.andy.service.DeviceService
import app.andy.service.FileService
import app.andy.service.HeapDumpService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Heap dump capture and local library management, shaped like [DesktopTracingService]:
 * capture -> list -> reveal -> delete, no built-in analyzer (§B.3).
 *
 * Pipeline: `am dumpheap` (returns before the write finishes) -> poll on-device size until
 * stable -> `adb pull` -> `hprof-conv` (when platform-tools has it; raw JVM-unfriendly hprof
 * otherwise) -> delete the on-device copy.
 */
class DesktopHeapDumpService(
    private val runner: CommandRunner,
    private val devices: DeviceService,
    private val files: FileService,
    private val dumpsDir: File = File(System.getProperty("user.home"), ".andy/heapdumps"),
    private val clock: () -> Long = System::currentTimeMillis,
) : HeapDumpService {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    init {
        dumpsDir.mkdirs()
    }

    override suspend fun capture(serial: String, packageName: String, localPath: String): Result<HeapDumpInfo> =
        withContext(Dispatchers.IO) {
            val id = "heap-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(clock()))
            val remotePath = "/data/local/tmp/$id.hprof"

            val dump = devices.shell(serial, listOf("am", "dumpheap", packageName, remotePath))
            if (!dump.isSuccess) {
                return@withContext Result.failure(
                    IllegalStateException(dump.stderr.ifBlank { dump.stdout }.ifBlank { "am dumpheap failed" }),
                )
            }

            var lastSize = -1L
            var stableCount = 0
            var attempts = 0
            while (attempts < 40) {
                delay(500)
                val size = remoteFileSize(serial, remotePath)
                stableCount = if (size != null && size > 0 && size == lastSize) stableCount + 1 else 0
                lastSize = size ?: lastSize
                attempts++
                if (stableCount >= 2) break
            }
            if (lastSize <= 0) {
                return@withContext Result.failure(
                    IllegalStateException("Heap dump did not produce a file on device (dumpheap may still be finalizing)"),
                )
            }

            dumpsDir.mkdirs()
            val rawLocal = File(dumpsDir, "$id-raw.hprof")
            val pull = files.pull(serial, remotePath, rawLocal.absolutePath)
            if (!pull.isSuccess || !rawLocal.isFile || rawLocal.length() <= 0L) {
                return@withContext Result.failure(
                    IllegalStateException(pull.stderr.ifBlank { pull.stdout }.ifBlank { "adb pull failed" }),
                )
            }
            devices.shell(serial, listOf("rm", "-f", remotePath))

            val destination = if (localPath.isNotBlank()) File(localPath) else File(dumpsDir, "$id.hprof")
            destination.parentFile?.mkdirs()
            val hprofConv = locateHprofConv()
            val converted = hprofConv != null && runCatching {
                val conv = runner.run(listOf(hprofConv, rawLocal.absolutePath, destination.absolutePath), 30)
                conv.isSuccess && destination.isFile && destination.length() > 0L
            }.getOrDefault(false)
            if (converted) {
                rawLocal.delete()
            } else {
                rawLocal.copyTo(destination, overwrite = true)
                rawLocal.delete()
            }

            val deviceLabel = devices.listDevices().firstOrNull { it.serial == serial }?.displayName
            val info = HeapDumpInfo(
                id = id,
                packageName = packageName,
                serial = serial,
                deviceLabel = deviceLabel,
                capturedAtMillis = clock(),
                sizeBytes = destination.length(),
                localPath = destination.absolutePath,
            )
            File(dumpsDir, "$id.json").writeText(json.encodeToString(info))
            Result.success(info)
        }

    override suspend fun listCaptures(): List<HeapDumpInfo> = withContext(Dispatchers.IO) {
        dumpsDir.mkdirs()
        dumpsDir.listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) }
            .orEmpty()
            .mapNotNull { file -> runCatching { json.decodeFromString<HeapDumpInfo>(file.readText()) }.getOrNull() }
            .sortedByDescending { it.capturedAtMillis }
    }

    override suspend fun deleteCapture(id: String): Boolean = withContext(Dispatchers.IO) {
        val info = listCaptures().firstOrNull { it.id == id }
        val hprof = info?.let { File(it.localPath) } ?: File(dumpsDir, "$id.hprof")
        val sidecar = File(dumpsDir, "$id.json")
        val hprofDeleted = if (hprof.exists()) hprof.delete() else true
        val sidecarDeleted = if (sidecar.exists()) sidecar.delete() else true
        hprofDeleted && sidecarDeleted
    }

    override suspend fun revealCapture(id: String): CommandResult = withContext(Dispatchers.IO) {
        val info = listCaptures().firstOrNull { it.id == id }
            ?: return@withContext CommandResult.failure("Heap dump not found")
        val file = File(info.localPath)
        if (!file.exists()) return@withContext CommandResult.failure("File missing: ${file.absolutePath}")
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                Desktop.getDesktop().browseFileDirectory(file)
            } else {
                Desktop.getDesktop().open(file.parentFile)
            }
            CommandResult.success(file.absolutePath)
        }.getOrElse { CommandResult.failure(it.message ?: "Reveal failed") }
    }

    private suspend fun remoteFileSize(serial: String, remotePath: String): Long? {
        val stat = devices.shell(serial, listOf("stat", "-c", "%s", remotePath))
        stat.stdout.trim().toLongOrNull()?.let { return it }
        val listing = devices.shell(serial, listOf("ls", "-l", remotePath))
        return listing.stdout.trim().split(Regex("\\s+")).getOrNull(4)?.toLongOrNull()
    }

    private suspend fun locateHprofConv(): String? {
        val adb = devices.adbPath() ?: return null
        val platformTools = File(adb).parentFile ?: return null
        val exeName = if (System.getProperty("os.name").lowercase(Locale.US).contains("win")) "hprof-conv.exe" else "hprof-conv"
        val candidate = File(platformTools, exeName)
        return candidate.takeIf { it.isFile }?.absolutePath
    }
}
