package app.andy.desktop.service

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopMetricsDeviceSmokeTest {
    @Test
    fun metricsStreamEmitsFreshSamplesForConnectedDevice() = runBlocking {
        if (System.getenv("ANDY_DEVICE_SMOKE") != "1") return@runBlocking

        val services = createDesktopServices()
        val requestedSerial = System.getenv("ANDY_DEVICE_SERIAL")?.takeIf { it.isNotBlank() }
        val device = services.devices.listDevices().firstOrNull {
            it.state.name == "Online" && (requestedSerial == null || it.serial == requestedSerial)
        } ?: error("No online Android device connected")

        val samples = withTimeout(20_000) {
            services.metrics.stream(device.serial, null).take(3).toList()
        }

        assertEquals(3, samples.size)
        assertTrue(samples.zipWithNext().all { (left, right) -> right.timestampMillis > left.timestampMillis }, "Expected increasing sample timestamps: ${samples.map { it.timestampMillis }}")
        assertTrue(samples.all { it.processes.isNotEmpty() }, "Expected process metrics in every sample")
        assertTrue(samples.any { it.memoryMb != null && it.memoryMb > 0f }, "Expected nonzero memory telemetry")
        assertTrue(samples.any { it.frameRenderTimes.isNotEmpty() }, "Expected frame render timings from focused package")
        assertTrue(samples.map { it.processes.firstOrNull()?.pid }.distinct().isNotEmpty(), "Expected process rows to refresh")
    }
}
