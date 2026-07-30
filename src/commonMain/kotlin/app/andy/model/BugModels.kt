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
    /** Non-null when capture.mp4 has no playable video (e.g. no frames captured, encode failure). */
    val videoCaptureWarning: String? = null,
    /**
     * 1 (default) = legacy bug report without a timeline sidecar.
     * 2+ = investigation report with [timelineRelativePath].
     */
    val schemaVersion: Int = 1,
    /** Relative path to `timeline.json` when [schemaVersion] >= 2. */
    val timelineRelativePath: String? = null,
    val captureMode: InvestigationCaptureMode? = null,
    val appIdentity: AppIdentity? = null,
    val projectIdentity: ProjectIdentity? = null,
    val hostIdentity: HostIdentity? = null,
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

/** Export container for a trimmed recording. Gif/WebP favor small, shareable clips over fidelity. */
@Serializable
enum class ClipFormat { Mp4, Gif, WebP, PngSequence }

/**
 * A request to export part (or all) of a saved recording (`BugReport` with a `recording-` id).
 * Trim is metadata over [BugReport.videoFrameTimestampsMillis] — no re-encode is needed until
 * export actually runs. [scale] is the target output width in pixels (aspect-preserving); [fps]
 * is the target output frame rate; [loop] only applies to Gif/WebP.
 */
@Serializable
data class RecordingExportRequest(
    val id: String,
    val startMillis: Long,
    val endMillis: Long,
    val format: ClipFormat,
    val scale: Int = 480,
    val fps: Int = 12,
    val loop: Boolean = true,
)

/** Result of a completed export: written to [localPath], ready to reveal/copy/open. */
@Serializable
data class ExportedClip(
    val localPath: String,
    val format: ClipFormat,
    val sizeBytes: Long,
    val frameCount: Int,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
)
