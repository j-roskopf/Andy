package app.andy.model

data class PerformanceSample(
    val timestampMillis: Long,
    val cpuPercent: Float?,
    val memoryMb: Float?,
    val fps: Float?,
    val batteryPercent: Int?,
    val thermalStatus: String?,
    val networkRxKbps: Float? = null,
    val networkTxKbps: Float? = null,
    val processes: List<ProcessMetric> = emptyList(),
    val frameRenderTimes: List<FrameRenderMetric> = emptyList(),
)

data class ProcessMetric(
    val pid: String,
    val name: String,
    val cpuPercent: Float?,
    val memoryMb: Float?,
)

data class FrameRenderMetric(
    val label: String,
    val millis: Float,
    val vsyncGapMillis: Float? = null,
)
