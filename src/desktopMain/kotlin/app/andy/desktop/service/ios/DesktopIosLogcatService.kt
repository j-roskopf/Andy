package app.andy.desktop.service.ios

import app.andy.desktop.parser.IosParsers
import app.andy.desktop.service.CommandRunner
import app.andy.model.LogcatEntry
import app.andy.service.LogcatFilter
import app.andy.service.LogcatService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * iOS unified log (Phase 3.1): `simctl spawn <udid> log stream --style ndjson --level info`
 * emits one JSON object per line. Reuses [app.andy.desktop.service.DesktopLogcatService]'s
 * batching cadence (80 entries / 80ms) so Live's log pane behaves the same on both platforms.
 */
class DesktopIosLogcatService(
    private val runner: CommandRunner,
) : LogcatService {
    override fun stream(serial: String, filter: LogcatFilter): Flow<List<LogcatEntry>> = channelFlow {
        val command = listOf(
            "xcrun", "simctl", "spawn", serial, "log", "stream",
            "--style", "ndjson", "--level", "info",
        )
        var process: Process? = null
        val reader = launch(Dispatchers.IO) {
            process = ProcessBuilder(command).redirectErrorStream(true).start()
            val batch = ArrayList<LogcatEntry>(BatchSize)
            var lastFlush = System.nanoTime()
            try {
                process?.inputStream?.bufferedReader()?.useLines { lines ->
                    for (line in lines) {
                        if (!isActive) break
                        val entry = IosParsers.parseLogStreamLine(line)
                        if (entry != null && matchesIosLogFilter(entry, filter)) {
                            batch += entry
                        }
                        val now = System.nanoTime()
                        if (batch.size >= BatchSize || (batch.isNotEmpty() && now - lastFlush > BatchWindowNanos)) {
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
        val result = runner.run(
            listOf(
                "xcrun", "simctl", "spawn", serial, "log", "show",
                "--style", "ndjson", "--level", "info", "--last", SnapshotWindow,
            ),
            SnapshotTimeoutSeconds,
        )
        if (!result.isSuccess) return emptyList()
        return result.stdout.lineSequence()
            .mapNotNull(IosParsers::parseLogStreamLine)
            .filter { matchesIosLogFilter(it, filter) }
            .toList()
            .takeLast(limit)
    }

    override suspend fun clear(serial: String) {
        // The unified log has no public "erase" equivalent (unlike `adb logcat -c`); nothing to do.
    }

    private fun matchesIosLogFilter(entry: LogcatEntry, filter: LogcatFilter): Boolean {
        if (entry.level !in filter.levels) return false
        return filter.search.isBlank() ||
            entry.message.contains(filter.search, ignoreCase = true) ||
            entry.tag.contains(filter.search, ignoreCase = true)
    }

    companion object {
        private const val BatchSize = 80
        private const val BatchWindowNanos = 80_000_000L
        private const val SnapshotWindow = "5m"
        private const val SnapshotTimeoutSeconds = 30L
    }
}
