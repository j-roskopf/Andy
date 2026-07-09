package app.andy.desktop.service

import app.andy.desktop.parser.AndroidParsers
import app.andy.model.PerformanceSample
import app.andy.service.MetricsService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class DesktopMetricsService(
    private val runner: CommandRunner,
    private val devices: DesktopDeviceService,
) : MetricsService {
    override fun stream(serial: String, packageName: String?): Flow<PerformanceSample> = flow {
        var lastNetworkTotals: Pair<Long, Long>? = null
        var lastNetworkAtMillis: Long? = null
        while (true) {
            val cpu = devices.shell(serial, listOf("dumpsys", "cpuinfo")).stdout
            val processRows = devices.shell(serial, listOf("top", "-b", "-n", "1", "-o", "PID,%CPU,RES,ARGS", "-m", "80")).stdout
                .ifBlank { devices.shell(serial, listOf("ps", "-A", "-o", "PID,RSS,NAME")).stdout }
            val processes = AndroidParsers.parseProcessMetrics(processRows)
            val mem = packageName?.let { devices.shell(serial, listOf("dumpsys", "meminfo", it)).stdout }
            val battery = devices.shell(serial, listOf("dumpsys", "battery")).stdout
            val netDev = devices.shell(serial, listOf("cat", "/proc/net/dev")).stdout
            val focusedPackage = packageName
                ?: AndroidParsers.parseFocusedPackage(devices.shell(serial, listOf("dumpsys", "window", "windows")).stdout)
                ?: AndroidParsers.parseFocusedPackage(devices.shell(serial, listOf("dumpsys", "activity", "activities")).stdout)
            val frameTimes = focusedPackage?.let {
                AndroidParsers.parseFrameStats(devices.shell(serial, listOf("dumpsys", "gfxinfo", it, "framestats")).stdout)
            }.orEmpty()
            val now = System.currentTimeMillis()
            val networkTotals = AndroidParsers.parseNetworkTotals(netDev)
            var networkRxKbps: Float? = null
            var networkTxKbps: Float? = null
            val previousTotals = lastNetworkTotals
            val previousAtMillis = lastNetworkAtMillis
            if (networkTotals != null && previousTotals != null && previousAtMillis != null) {
                val elapsedSeconds = (now - previousAtMillis) / 1000f
                if (elapsedSeconds > 0f) {
                    networkRxKbps = ((networkTotals.first - previousTotals.first).coerceAtLeast(0) / 1024f) / elapsedSeconds
                    networkTxKbps = ((networkTotals.second - previousTotals.second).coerceAtLeast(0) / 1024f) / elapsedSeconds
                }
            }
            if (networkTotals != null) {
                lastNetworkTotals = networkTotals
                lastNetworkAtMillis = now
            }
            emit(
                PerformanceSample(
                    timestampMillis = now,
                    cpuPercent = Regex("""(\d+(?:\.\d+)?)%""").find(cpu)?.groupValues?.getOrNull(1)?.toFloatOrNull(),
                    memoryMb = (mem?.let { Regex("""TOTAL\s+(\d+)""").find(it)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.div(1024f) }
                        ?: processes.sumOf { it.memoryMb?.toDouble() ?: 0.0 }.toFloat().takeIf { it > 0f }),
                    fps = frameTimes.takeLast(30).mapNotNull { it.vsyncGapMillis }.takeIf { it.isNotEmpty() }
                        ?.let { gaps -> 1000f / (gaps.sum() / gaps.size) },
                    batteryPercent = AndroidParsers.parseBatteryPercent(battery),
                    thermalStatus = null,
                    networkRxKbps = networkRxKbps,
                    networkTxKbps = networkTxKbps,
                    processes = processes,
                    frameRenderTimes = frameTimes,
                ),
            )
            delay(600)
        }
    }
}
