package app.andy.desktop.service

import app.andy.service.DeviceService

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
    private val devices: DeviceService,
) : LogcatService {
    override fun stream(serial: String, filter: LogcatFilter): Flow<List<LogcatEntry>> = channelFlow {
        val adb = devices.adbPath() ?: return@channelFlow
        val command = buildList {
            add(adb)
            add("-s")
            add(serial)
            add("logcat")
            add("-v")
            add("threadtime")
            // Skip the existing ring buffer — critical for bug-capture's 30s window.
            if (filter.followOnly) {
                add("-T")
                add("0")
            }
            filter.buffers.forEach { buffer ->
                add("-b")
                add(buffer)
            }
        }
        var resolvedFilter = resolveLogcatFilter(serial, filter)
        var lastPidRefreshNanos = System.nanoTime()
        var process: Process? = null
        val reader = launch(Dispatchers.IO) {
            process = ProcessBuilder(command).redirectErrorStream(true).start()
            val batch = ArrayList<LogcatEntry>(80)
            var lastFlush = System.nanoTime()
            try {
                process?.inputStream?.bufferedReader()?.useLines { lines ->
                    for (line in lines) {
                        if (!isActive) break
                        val now = System.nanoTime()
                        if (resolvedFilter.packageName != null && now - lastPidRefreshNanos > 2_000_000_000L) {
                            resolvedFilter = resolveLogcatFilter(serial, filter)
                            lastPidRefreshNanos = now
                        }
                        val entry = AndroidParsers.parseLogcatLine(line)
                        if (entry != null && matchesLogcatFilter(entry, resolvedFilter)) {
                            batch += entry
                        }
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
            resolvePackagePids(serial, name)
        }.orEmpty()
        return ResolvedLogcatFilter(search = search, levels = filter.levels, packageName = explicitPackage, packagePids = packagePids)
    }

    private suspend fun resolvePackagePids(serial: String, packageName: String): Set<String> {
        val fromPidof = AndroidParsers.parsePidList(
            devices.shell(serial, listOf("pidof", packageName)).stdout,
        )
        if (fromPidof.isNotEmpty()) return fromPidof

        val fromPs = AndroidParsers.packagePidsFromPs(
            devices.shell(serial, listOf("ps", "-A", "-o", "PID,NAME")).stdout,
            packageName,
        )
        if (fromPs.isNotEmpty()) return fromPs

        return AndroidParsers.packagePidsFromPsArgs(
            devices.shell(serial, listOf("ps", "-A", "-o", "PID,ARGS")).stdout,
            packageName,
        )
    }

    private fun matchesLogcatFilter(entry: LogcatEntry, filter: ResolvedLogcatFilter): Boolean {
        if (entry.level !in filter.levels) return false
        if (filter.packageName != null) {
            val pidMatch = entry.pid != null && entry.pid in filter.packagePids
            val textMatch = logEntryMentionsPackage(entry, filter.packageName)
            if (!pidMatch && !textMatch) return false
        }
        return filter.search.isBlank() ||
            entry.message.contains(filter.search, true) ||
            entry.tag.contains(filter.search, true)
    }

    private fun logEntryMentionsPackage(entry: LogcatEntry, packageName: String): Boolean =
        entry.tag.contains(packageName, ignoreCase = true) ||
            entry.message.contains(packageName, ignoreCase = true)

    private data class ResolvedLogcatFilter(
        val search: String,
        val levels: Set<LogLevel>,
        val packageName: String?,
        val packagePids: Set<String>,
    )
}
