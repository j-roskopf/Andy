package app.andy.desktop.service

import app.andy.model.BugReport
import app.andy.model.InvestigationTimeline
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val InvestigationBundleJsonFormat = Json {
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = true
}

/** `manifest.json` for an exported investigation bundle (§4): report id, schema, and identity. */
@Serializable
internal data class InvestigationBundleManifestDto(
    val reportId: String,
    val schemaVersion: Int,
    val title: String,
    val capturedAtMillis: Long,
    val eventCount: Int,
    val app: AppIdentityDto? = null,
    val device: DeviceIdentityDto? = null,
    val project: ProjectIdentityDto? = null,
    val host: HostIdentityDto? = null,
)

internal fun buildInvestigationBundleManifest(report: BugReport, timeline: InvestigationTimeline): InvestigationBundleManifestDto =
    InvestigationBundleManifestDto(
        reportId = report.id,
        schemaVersion = report.schemaVersion,
        title = report.title,
        capturedAtMillis = report.capturedAtMillis,
        eventCount = timeline.events.size,
        app = report.appIdentity?.let(AppIdentityDto::fromModel),
        device = DeviceIdentityDto(
            serial = report.deviceSerial,
            model = report.deviceModel,
            apiLevel = report.apiLevel,
            abi = report.abi,
            resolution = report.resolution,
        ),
        project = report.projectIdentity?.let(ProjectIdentityDto::fromModel),
        host = report.hostIdentity?.let(HostIdentityDto::fromModel),
    )

internal fun writeInvestigationBundleManifest(manifest: InvestigationBundleManifestDto): String =
    InvestigationBundleJsonFormat.encodeToString(manifest) + "\n"

internal fun readInvestigationBundleManifest(json: String): InvestigationBundleManifestDto =
    InvestigationBundleJsonFormat.decodeFromString(InvestigationBundleManifestDto.serializer(), json)

/** Human-readable `summary.md` companion to `manifest.json` — title, notes, counts, identity. */
internal fun buildInvestigationBundleSummaryMarkdown(report: BugReport, timeline: InvestigationTimeline): String {
    val countsByKind = timeline.events.groupingBy { it.kind.name }.eachCount().toSortedMap()
    return buildString {
        appendLine("# ${report.title}")
        appendLine()
        if (report.notes.isNotBlank()) {
            appendLine(report.notes.trim())
            appendLine()
        }
        appendLine("## Identity")
        val appIdentity = report.appIdentity
        appendLine(
            "- App: ${appIdentity?.packageName ?: "unknown"}" +
                (appIdentity?.versionName?.let { " ($it)" } ?: ""),
        )
        appendLine("- Device: ${report.deviceModel ?: report.deviceSerial} (API ${report.apiLevel ?: "-"}, ${report.abi ?: "-"})")
        val project = report.projectIdentity
        appendLine(
            "- Project: ${project?.projectId ?: "unknown"}" +
                (project?.gitBranch?.let { " @ $it" } ?: ""),
        )
        appendLine("- Host: ${report.hostIdentity?.hostOs ?: "unknown"} · Andy ${report.hostIdentity?.andyVersionName ?: "-"}")
        appendLine("- Clock source: Host clock is authoritative; device timestamps are approximate when shown.")
        appendLine()
        appendLine("## Event counts")
        if (countsByKind.isEmpty()) {
            appendLine("- (no events)")
        } else {
            countsByKind.forEach { (kind, count) -> appendLine("- $kind: $count") }
        }
        appendLine()
        appendLine("## Timeline window")
        appendLine("- Origin: ${timeline.originMillis}")
        appendLine("- Ended: ${timeline.endedAtMillis}")
        appendLine("- Total events: ${timeline.events.size}")
    }
}
