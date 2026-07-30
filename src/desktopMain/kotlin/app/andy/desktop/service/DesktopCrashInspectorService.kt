package app.andy.desktop.service

import app.andy.desktop.parser.AndroidParsers
import app.andy.model.CrashKind
import app.andy.model.CrashRecord
import app.andy.service.CommandResult
import app.andy.service.CrashInspectorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val AnrFilePrefix = "anr-file:"
private const val TombstonePrefix = "tombstone:"
private const val DropboxPrefix = "dropbox|"

private val unreadableDeviceMessage =
    "Not readable on this device (needs userdebug/root or READ_LOGS permission)."

private val anrPermissionDeniedMessage = """
    ANR trace files in /data/anr/ are not readable on this device without root or a userdebug build.

    Look for a dropbox ANR entry (data_app_anr) in the list instead — those are readable on most devices.
""".trim()

/**
 * Reads crash/ANR/watchdog records via `dumpsys dropbox`, `/data/anr`, and `/data/tombstones` —
 * all read-only shell reads through [DeviceService.shell] (§B.2). Permission failures (the
 * common case on production/non-userdebug devices) are surfaced as ordinary text, not thrown.
 */
class DesktopCrashInspectorService(
    private val devices: DesktopDeviceService,
) : CrashInspectorService {
    private val dropboxBodies = ConcurrentHashMap<Pair<String, String>, String>()

    override suspend fun listCrashes(serial: String): List<CrashRecord> = withContext(Dispatchers.IO) {
        dropboxBodies.keys.removeAll { it.first == serial }

        val dropboxOutput = devices.shell(serial, listOf("dumpsys", "dropbox", "--print")).stdout
        val dropboxCrashes = runCatching {
            val parsed = AndroidParsers.parseDropboxIndex(dropboxOutput)
            parsed.bodiesById.forEach { (id, body) -> dropboxBodies[serial to id] = body }
            parsed.records
        }.getOrDefault(emptyList())

        val anrListing = devices.shell(serial, listOf("ls", "-1", "/data/anr"))
        val anrCrashes = if (anrListing.isSuccess && !anrListing.stderr.contains("Permission denied", ignoreCase = true)) {
            anrListing.stdout.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("ls:") }
                .map { name ->
                    CrashRecord(
                        id = "$AnrFilePrefix$name",
                        kind = CrashKind.Anr,
                        packageName = null,
                        timestampMillis = 0L,
                        summary = "ANR trace file: $name",
                    )
                }
                .toList()
        } else {
            emptyList()
        }

        val tombstoneListing = devices.shell(serial, listOf("ls", "-1", "/data/tombstones"))
        val tombstones = if (tombstoneListing.isSuccess && !tombstoneListing.stderr.contains("Permission denied", ignoreCase = true)) {
            tombstoneListing.stdout.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("ls:") }
                .map { name ->
                    CrashRecord(
                        id = "$TombstonePrefix$name",
                        kind = CrashKind.NativeCrash,
                        packageName = null,
                        timestampMillis = 0L,
                        summary = "Tombstone: $name",
                    )
                }
                .toList()
        } else {
            emptyList()
        }

        (dropboxCrashes + anrCrashes + tombstones).sortedByDescending { it.timestampMillis }
    }

    override suspend fun loadCrash(serial: String, id: String): String = withContext(Dispatchers.IO) {
        when {
            id.startsWith(AnrFilePrefix) -> {
                val name = id.removePrefix(AnrFilePrefix)
                val result = devices.shell(serial, listOf("cat", "/data/anr/$name"))
                when {
                    result.stdout.isNotBlank() -> result.stdout
                    result.stderr.contains("Permission denied", ignoreCase = true) -> anrPermissionDeniedMessage
                    else -> result.stderr.ifBlank { unreadableDeviceMessage }
                }
            }
            id.startsWith(TombstonePrefix) -> {
                val name = id.removePrefix(TombstonePrefix)
                val result = devices.shell(serial, listOf("cat", "/data/tombstones/$name"))
                when {
                    result.stdout.isNotBlank() -> result.stdout
                    result.stderr.contains("Permission denied", ignoreCase = true) -> unreadableDeviceMessage
                    else -> result.stderr.ifBlank { unreadableDeviceMessage }
                }
            }
            id.startsWith(DropboxPrefix) -> {
                dropboxBodies[serial to id]
                    ?: loadDropboxEntry(serial, id)
                    ?: unreadableDeviceMessage
            }
            else -> {
                // Legacy ids from older Andy versions (`tag:epochMillis`) — try dropbox --print by tag.
                val tag = id.substringBefore(':')
                val result = devices.shell(serial, listOf("dumpsys", "dropbox", "--print", tag))
                val raw = result.stdout.ifBlank { result.stderr }
                if (raw.isBlank() || raw.contains("No entries found", ignoreCase = true)) {
                    unreadableDeviceMessage
                } else {
                    AndroidParsers.parseDropboxEntry(raw)
                }
            }
        }
    }

    override suspend fun exportCrash(serial: String, id: String, localPath: String): CommandResult = withContext(Dispatchers.IO) {
        val text = loadCrash(serial, id)
        if (text.isBlank()) return@withContext CommandResult.failure("Crash entry not found or unreadable: $id")
        runCatching {
            val file = File(localPath)
            file.parentFile?.mkdirs()
            file.writeText(text)
            CommandResult.success(file.absolutePath)
        }.getOrElse { CommandResult.failure(it.message ?: "Export failed") }
    }

    private suspend fun loadDropboxEntry(serial: String, id: String): String? {
        val parts = id.split('|', limit = 3)
        if (parts.size != 3) return null
        val timestampText = parts[1]
        val tag = parts[2]
        val dateParts = timestampText.split(' ', limit = 2)
        if (dateParts.size != 2) return null
        val result = devices.shell(
            serial,
            listOf("dumpsys", "dropbox", "--print", dateParts[0], dateParts[1], tag),
        )
        val raw = result.stdout.ifBlank { result.stderr }
        if (raw.isBlank() || raw.contains("No entries found", ignoreCase = true)) return null
        val body = AndroidParsers.parseDropboxEntry(raw)
        dropboxBodies[serial to id] = body
        return body
    }
}
