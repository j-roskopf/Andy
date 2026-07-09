package app.andy.desktop.service

import app.andy.desktop.parser.AndroidParsers
import app.andy.model.LogLevel
import app.andy.model.LogcatEntry
import app.andy.service.LogcatFilter
import app.andy.service.LogcatService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DesktopLogcatService(
    private val runner: CommandRunner,
    private val devices: DesktopDeviceService,
) : LogcatService {
    override fun stream(serial: String, filter: LogcatFilter): Flow<List<LogcatEntry>> = channelFlow {
        val adb = devices.adbPath() ?: return@channelFlow
        val command = listOf(adb, "-s", serial, "logcat", "-v", "threadtime") + filter.buffers.flatMap { listOf("-b", it) }
        val normalizedFilter = resolveLogcatFilter(serial, filter)
        var process: Process? = null
        val reader = launch(Dispatchers.IO) {
            process = ProcessBuilder(command).redirectErrorStream(true).start()
            val batch = ArrayList<LogcatEntry>(80)
            var lastFlush = System.nanoTime()
            try {
                process?.inputStream?.bufferedReader()?.useLines { lines ->
                    for (line in lines) {
                        if (!isActive) break
                        val entry = AndroidParsers.parseLogcatLine(line)
                        if (entry != null && matchesLogcatFilter(entry, normalizedFilter)) {
                            batch += entry
                        }
                        val now = System.nanoTime()
                        if (batch.size >= 80 || (batch.isNotEmpty() && now - lastFlush > 80_000_000L)) {
                            send(batch.toList())
                            batch.clear()
                            lastFlush = now
                        }
                    }
                }
                if (batch.isNotEmpty()) send(batch.toList())
            } finally {
                process?.destroy()
            }
        }
        awaitClose {
            reader.cancel()
            process?.destroy()
            process?.destroyForcibly()
        }
    }

    override suspend fun snapshot(serial: String, filter: LogcatFilter, limit: Int): List<LogcatEntry> {
        val adb = devices.adbPath() ?: return emptyList()
        val normalizedFilter = resolveLogcatFilter(serial, filter)
        val result = runner.run(listOf(adb, "-s", serial, "logcat", "-d", "-v", "threadtime", "-t", limit.toString()), 10)
        return result.stdout.lineSequence()
            .mapNotNull(AndroidParsers::parseLogcatLine)
            .filter { matchesLogcatFilter(it, normalizedFilter) }
            .toList()
    }

    override suspend fun clear(serial: String) {
        val adb = devices.adbPath() ?: return
        runner.run(listOf(adb, "-s", serial, "logcat", "-c"), 10)
    }

    private suspend fun resolveLogcatFilter(serial: String, filter: LogcatFilter): ResolvedLogcatFilter {
        val (packageName, search) = AndroidParsers.extractPackageFilter(filter.search)
        val explicitPackage = filter.packageName ?: packageName
        val packagePids = explicitPackage?.takeIf { it.isNotBlank() }?.let { name ->
            devices.shell(serial, listOf("pidof", name)).stdout
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .toSet()
        }.orEmpty()
        return ResolvedLogcatFilter(search = search, levels = filter.levels, packageName = explicitPackage, packagePids = packagePids)
    }

    private fun matchesLogcatFilter(entry: LogcatEntry, filter: ResolvedLogcatFilter): Boolean {
        if (entry.level !in filter.levels) return false
        if (filter.packageName != null && entry.pid !in filter.packagePids) return false
        return filter.search.isBlank() ||
            entry.message.contains(filter.search, true) ||
            entry.tag.contains(filter.search, true)
    }

    private data class ResolvedLogcatFilter(
        val search: String,
        val levels: Set<LogLevel>,
        val packageName: String?,
        val packagePids: Set<String>,
    )
}
