package app.andy.model

import kotlinx.serialization.Serializable

/** Schema version for investigation timeline sidecars (`timeline.json`). */
const val InvestigationTimelineSchemaVersion = 2

/** Schema version written into v2 [BugReport] metadata. Absent / 1 = legacy bug report. */
const val InvestigationReportSchemaVersion = 2

@Serializable
enum class InvestigationEventKind {
    Action,
    ScreenTransition,
    LogLine,
    NetworkExchange,
    ProxyWarning,
    MetricSample,
    Crash,
    Anr,
    HierarchySnapshot,
    Screenshot,
    VideoMarker,
    UserMarker,
}

@Serializable
enum class InvestigationEventSeverity {
    Info,
    Warning,
    Error,
}

@Serializable
data class InvestigationPayloadRef(
    val relativePath: String,
    val kind: String,
    val sizeBytes: Long? = null,
)

/**
 * Compact, JSON-safe payload kept inline on the timeline index.
 * Heavy content lives behind [InvestigationEvent.payloadRef].
 */
@Serializable
data class InvestigationInlinePayload(
    val text: String? = null,
    val packageName: String? = null,
    val activity: String? = null,
    val level: String? = null,
    val tag: String? = null,
    val deviceTime: String? = null,
    val method: String? = null,
    val url: String? = null,
    val statusCode: Int? = null,
    val durationMillis: Long? = null,
    val error: String? = null,
    val tlsStatus: String? = null,
    val matchedRuleId: String? = null,
    val flowId: String? = null,
    val cpuPercent: Float? = null,
    val memoryMb: Float? = null,
    val fps: Float? = null,
    val batteryPercent: Int? = null,
    val thermalStatus: String? = null,
    val networkRxKbps: Float? = null,
    val networkTxKbps: Float? = null,
    val crashKind: String? = null,
    val hierarchySource: String? = null,
    val displayWidth: Int? = null,
    val displayHeight: Int? = null,
    val nodeCount: Int? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val approximatelyTimed: Boolean = false,
    val proxySessionScoped: Boolean = false,
)

@Serializable
data class InvestigationEvent(
    val id: String,
    /** Host wall-clock millis — the authoritative scrub axis. */
    val atMillis: Long,
    /** Optional source/device timestamp when known and distinct from [atMillis]. */
    val sourceTimestampMillis: Long? = null,
    val kind: InvestigationEventKind,
    val summary: String,
    val severity: InvestigationEventSeverity = InvestigationEventSeverity.Info,
    val detail: String? = null,
    /** Correlation keys such as package, flowId, crash id, or action id. */
    val correlationIds: Map<String, String> = emptyMap(),
    val inline: InvestigationInlinePayload? = null,
    val payloadRef: InvestigationPayloadRef? = null,
)

@Serializable
data class InvestigationTimeline(
    val schemaVersion: Int = InvestigationTimelineSchemaVersion,
    val originMillis: Long,
    val endedAtMillis: Long,
    val events: List<InvestigationEvent> = emptyList(),
)

@Serializable
data class AppIdentity(
    val packageName: String,
    val versionName: String? = null,
    val versionCode: String? = null,
    val minSdk: String? = null,
    val targetSdk: String? = null,
    val debuggable: Boolean? = null,
    val label: String? = null,
)

@Serializable
data class DeviceIdentity(
    val serial: String,
    val model: String? = null,
    val apiLevel: String? = null,
    val abi: String? = null,
    val resolution: String? = null,
)

@Serializable
data class ProjectIdentity(
    val projectId: String? = null,
    val contextDir: String? = null,
    val gitHead: String? = null,
    val gitBranch: String? = null,
    val gitDirty: Boolean? = null,
)

@Serializable
data class HostIdentity(
    val andyVersionName: String,
    val andyVersionCode: Int = 0,
    val hostOs: String,
)

@Serializable
data class InvestigationIdentity(
    val app: AppIdentity? = null,
    val device: DeviceIdentity? = null,
    val project: ProjectIdentity? = null,
    val host: HostIdentity? = null,
)

/** Capture mode recorded in metadata for rolling vs durable recordings. */
@Serializable
enum class InvestigationCaptureMode {
    Rolling,
    Recording,
}
