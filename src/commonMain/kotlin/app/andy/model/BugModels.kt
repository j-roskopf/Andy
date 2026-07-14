package app.andy.model

import kotlinx.serialization.Serializable

@Serializable
data class BugReport(
    val id: String,
    val title: String,
    val notes: String,
    val deviceSerial: String,
    val deviceModel: String?,
    val apiLevel: String?,
    val abi: String?,
    val resolution: String?,
    val capturedAtMillis: Long,
    val windowStartedAtMillis: Long,
    val windowEndedAtMillis: Long,
    val actions: List<BugAction>,
    val artifacts: List<BugArtifact>,
    val videoStartedAtMillis: Long? = null,
    val videoEndedAtMillis: Long? = null,
    val videoFrameRate: Double? = null,
    val videoFrameTimestampsMillis: List<Long> = emptyList(),
)

@Serializable
data class BugAction(
    val id: String,
    val timestampMillis: Long,
    val kind: String,
    val label: String,
    val detail: String? = null,
)

@Serializable
data class BugArtifact(
    val name: String,
    val relativePath: String,
    val kind: String,
    val sizeBytes: Long? = null,
)

@Serializable
data class BugCaptureDraft(
    val title: String,
    val notes: String = "",
)

@Serializable
data class BugCaptureStatus(
    val active: Boolean = false,
    val deviceSerial: String? = null,
    val actionCount: Int = 0,
    val logCount: Int = 0,
    val videoFrameCount: Int = 0,
    val message: String = "Bug capture idle",
)
