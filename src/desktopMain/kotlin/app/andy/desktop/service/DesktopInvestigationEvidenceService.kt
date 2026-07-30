package app.andy.desktop.service

import app.andy.domain.EvidenceBudgets
import app.andy.domain.applyBudget
import app.andy.domain.capText
import app.andy.domain.redactHeaders
import app.andy.domain.redactHierarchyJson
import app.andy.domain.selectEvidenceWindow
import app.andy.model.AgentEvidencePreview
import app.andy.model.BugReport
import app.andy.model.EvidenceArtifactManifestEntry
import app.andy.model.EvidenceMaterializeRequest
import app.andy.model.EvidencePreviewRequest
import app.andy.model.InvestigationEvent
import app.andy.model.InvestigationEventKind
import app.andy.model.InvestigationEvidenceRef
import app.andy.model.InvestigationTimeline
import app.andy.model.ManagedEvidenceBundle
import app.andy.model.RedactionReport
import app.andy.model.buildContextualPrompt
import app.andy.service.BugService
import app.andy.service.InvestigationEvidenceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Reads a previously saved investigation (via [bugs]) and builds a redacted, size-budgeted
 * evidence bundle for a contextual agent action (§4). Callers only ever supply an investigation
 * id plus an event selection — this service never accepts an arbitrary filesystem path, so a
 * bundle's contents can only ever come from Andy's own saved investigation data.
 *
 * [materialize] is the only method that writes to disk, and only under the managed evidence
 * root `~/.andy/evidence/<bundleId>/`. Nothing here is ever sent anywhere automatically.
 */
class DesktopInvestigationEvidenceService(
    private val bugs: BugService,
    private val homeDir: File = File(System.getProperty("user.home")),
) : InvestigationEvidenceService {

    private val evidenceRootDir: File get() = File(homeDir, ".andy/evidence")

    override suspend fun preview(request: EvidencePreviewRequest): AgentEvidencePreview = withContext(Dispatchers.IO) {
        val selection = loadSelection(request.evidence)
            ?: return@withContext AgentEvidencePreview(
                promptDraft = request.question,
                selectedEventsSummary = "Investigation ${request.evidence.investigationId} was not found.",
                windowStartMillis = request.evidence.centerMillis,
                windowEndMillis = request.evidence.centerMillis,
                totalBytes = 0L,
            )
        val plan = planArtifacts(selection)
        val budgetResult = applyBudget(plan.candidates)
        val redactionReport = plan.redactionReport.copy(
            excludedArtifacts = plan.redactionReport.excludedArtifacts + budgetResult.exclusions,
        )
        AgentEvidencePreview(
            promptDraft = buildContextualPrompt(
                question = request.question,
                provenance = request.provenance,
                timelineSummary = selection.summary,
            ),
            selectedEventsSummary = selection.summary,
            windowStartMillis = selection.windowStart,
            windowEndMillis = selection.windowEnd,
            totalBytes = budgetResult.included.sumOf { it.sizeBytes },
            artifacts = budgetResult.included + plan.referenceOnly,
            redactionReport = redactionReport,
            exclusions = redactionReport.excludedArtifacts,
        )
    }

    override suspend fun materialize(request: EvidenceMaterializeRequest): ManagedEvidenceBundle = withContext(Dispatchers.IO) {
        val selection = requireNotNull(loadSelection(request.evidence)) {
            "Investigation ${request.evidence.investigationId} was not found."
        }
        val plan = planArtifacts(selection)
        val budgetResult = applyBudget(plan.candidates)
        val bundleId = "evidence-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val bundleDir = File(evidenceRootDir, bundleId).apply { mkdirs() }

        val writtenManifest = budgetResult.included.mapNotNull { candidate ->
            val write = plan.writers[candidate.relativePath] ?: return@mapNotNull null
            val target = File(bundleDir, candidate.relativePath)
            target.parentFile?.mkdirs()
            write(target)
            candidate.copy(sizeBytes = target.length())
        }
        val fullManifest = writtenManifest + plan.referenceOnly
        val redactionReport = plan.redactionReport.copy(
            excludedArtifacts = plan.redactionReport.excludedArtifacts + budgetResult.exclusions,
        )
        EvidenceJson.writeManifest(File(bundleDir, ManifestFileName), fullManifest)
        EvidenceJson.writeRedactionReport(File(bundleDir, RedactionReportFileName), redactionReport)

        ManagedEvidenceBundle(
            id = bundleId,
            rootRelativePath = bundleDir.relativeTo(homeDir).path.replace('\\', '/'),
            manifest = fullManifest,
            redactionReport = redactionReport,
            investigationId = request.evidence.investigationId,
            eventId = request.evidence.focusedEventId,
            playbackMillis = request.evidence.centerMillis.takeIf { it != 0L },
        )
    }

    private suspend fun loadSelection(ref: InvestigationEvidenceRef): EvidenceSelection? {
        val report = bugs.loadBug(ref.investigationId) ?: return null
        val timeline = bugs.loadBugTimeline(ref.investigationId) ?: return null
        val reportDirPath = bugs.bugDirectoryPath(ref.investigationId) ?: return null
        val reportDir = File(reportDirPath)
        val events = if (ref.eventIds.isNotEmpty()) {
            timeline.events.filter { it.id in ref.eventIds }
        } else {
            timeline.selectEvidenceWindow(ref.focusedEventId, ref.centerMillis, ref.windowRadiusMillis)
        }
        val center = ref.focusedEventId
            ?.let { id -> timeline.events.firstOrNull { it.id == id }?.atMillis }
            ?: ref.centerMillis
        val windowStart = events.minOfOrNull { it.atMillis } ?: (center - ref.windowRadiusMillis)
        val windowEnd = events.maxOfOrNull { it.atMillis } ?: (center + ref.windowRadiusMillis)
        return EvidenceSelection(
            report = report,
            reportDir = reportDir,
            timeline = timeline,
            events = events,
            windowStart = windowStart,
            windowEnd = windowEnd,
            centerMillis = center,
            summary = summarizeEvents(events),
        )
    }

    private fun planArtifacts(selection: EvidenceSelection): ArtifactPlan {
        val reportDir = selection.reportDir
        val prioritized = selection.events.sortedBy { kotlin.math.abs(it.atMillis - selection.centerMillis) }
        val results = mutableListOf<ArtifactResult>()

        // Always included first (highest budget priority): a compact JSON excerpt of the
        // selected window so the agent has full context even for kinds without a sidecar.
        val timelineWindowJson = InvestigationJson.writeTimeline(
            InvestigationTimeline(
                schemaVersion = selection.timeline.schemaVersion,
                originMillis = selection.windowStart,
                endedAtMillis = selection.windowEnd,
                events = selection.events,
            ),
        )
        results += ArtifactResult(
            manifestEntry = EvidenceArtifactManifestEntry(
                relativePath = "timeline-window.json",
                kind = "timeline",
                sizeBytes = timelineWindowJson.toByteArray(Charsets.UTF_8).size.toLong(),
            ),
            write = { target -> target.writeText(timelineWindowJson) },
        )

        prioritized.forEach { event ->
            val result = when (event.kind) {
                InvestigationEventKind.NetworkExchange -> networkArtifact(event, reportDir)
                InvestigationEventKind.Crash, InvestigationEventKind.Anr -> crashArtifact(event, reportDir)
                InvestigationEventKind.HierarchySnapshot -> hierarchyArtifact(event, reportDir)
                InvestigationEventKind.Screenshot -> screenshotArtifact(event, reportDir)
                else -> null
            }
            if (result != null) results += result
        }

        val videoArtifact = selection.report.artifacts.firstOrNull { it.kind == "video" }
        val referenceOnly = videoArtifact?.let { artifact ->
            val videoFile = File(reportDir, artifact.relativePath)
            listOf(
                EvidenceArtifactManifestEntry(
                    relativePath = videoFile.absolutePath,
                    kind = "video-reference",
                    sizeBytes = artifact.sizeBytes ?: videoFile.length(),
                    redacted = false,
                ),
            )
        }.orEmpty()
        val videoNote = videoArtifact?.let {
            "${it.name} (video reference only; not copied into the evidence bundle by default policy)"
        }

        return ArtifactPlan(
            candidates = results.map { it.manifestEntry },
            writers = results.associate { it.manifestEntry.relativePath to it.write },
            referenceOnly = referenceOnly,
            redactionReport = RedactionReport(
                redactedHeaderNames = results.flatMap { it.redactedHeaderNames }.distinct(),
                redactedNodeCount = results.sumOf { it.redactedNodeCount },
                truncatedFields = results.flatMap { it.truncatedFields },
                excludedArtifacts = listOfNotNull(videoNote),
            ),
        )
    }

    private fun networkArtifact(event: InvestigationEvent, reportDir: File): ArtifactResult? {
        val ref = event.payloadRef ?: return null
        val file = File(reportDir, ref.relativePath)
        if (!file.isFile) return null
        val sidecar = runCatching { InvestigationJson.readNetworkSidecar(file.readText()) }.getOrNull() ?: return null
        val requestRedaction = redactHeaders(sidecar.requestHeaders)
        val responseRedaction = redactHeaders(sidecar.responseHeaders)
        val truncatedFields = mutableListOf<String>()
        val requestBody = sidecar.requestBodyPreview?.let { body ->
            capText(body, EvidenceBudgets.MaxNetworkPreviewChars).also {
                if (it != body) truncatedFields += "${event.id}.requestBodyPreview"
            }
        }
        val responseBody = sidecar.responseBodyPreview?.let { body ->
            capText(body, EvidenceBudgets.MaxNetworkPreviewChars).also {
                if (it != body) truncatedFields += "${event.id}.responseBodyPreview"
            }
        }
        val redacted = sidecar.copy(
            requestHeaders = requestRedaction.headers,
            responseHeaders = responseRedaction.headers,
            requestBodyPreview = requestBody,
            responseBodyPreview = responseBody,
        )
        val redactedNames = (requestRedaction.redactedNames + responseRedaction.redactedNames).distinct()
        return ArtifactResult(
            manifestEntry = EvidenceArtifactManifestEntry(
                relativePath = ref.relativePath,
                kind = "network",
                sizeBytes = file.length(),
                redacted = redactedNames.isNotEmpty() || truncatedFields.isNotEmpty(),
            ),
            write = { target -> target.writeText(InvestigationJson.writeNetworkSidecar(redacted)) },
            redactedHeaderNames = redactedNames,
            truncatedFields = truncatedFields,
        )
    }

    private fun crashArtifact(event: InvestigationEvent, reportDir: File): ArtifactResult? {
        val ref = event.payloadRef ?: return null
        val file = File(reportDir, ref.relativePath)
        if (!file.isFile) return null
        val sidecar = runCatching { InvestigationJson.readCrashSidecar(file.readText()) }.getOrNull() ?: return null
        val cappedStack = capText(sidecar.stackTrace, EvidenceBudgets.MaxCrashChars)
        val truncated = cappedStack != sidecar.stackTrace
        return ArtifactResult(
            manifestEntry = EvidenceArtifactManifestEntry(
                relativePath = ref.relativePath,
                kind = "crash",
                sizeBytes = file.length(),
                redacted = truncated,
            ),
            write = { target -> target.writeText(InvestigationJson.writeCrashSidecar(sidecar.copy(stackTrace = cappedStack))) },
            truncatedFields = if (truncated) listOf("${event.id}.stackTrace") else emptyList(),
        )
    }

    private fun hierarchyArtifact(event: InvestigationEvent, reportDir: File): ArtifactResult? {
        val ref = event.payloadRef ?: return null
        val file = File(reportDir, ref.relativePath)
        if (!file.isFile) return null
        val sidecar = runCatching { InvestigationJson.readHierarchySidecar(file.readText()) }.getOrNull() ?: return null
        val redaction = redactHierarchyJson(sidecar.treeJson)
        return ArtifactResult(
            manifestEntry = EvidenceArtifactManifestEntry(
                relativePath = ref.relativePath,
                kind = "hierarchy",
                sizeBytes = file.length(),
                redacted = redaction.redactedNodeCount > 0,
            ),
            write = { target -> target.writeText(InvestigationJson.writeHierarchySidecar(sidecar.copy(treeJson = redaction.json))) },
            redactedNodeCount = redaction.redactedNodeCount,
        )
    }

    private fun screenshotArtifact(event: InvestigationEvent, reportDir: File): ArtifactResult? {
        val ref = event.payloadRef ?: return null
        val file = File(reportDir, ref.relativePath)
        if (!file.isFile) return null
        return ArtifactResult(
            manifestEntry = EvidenceArtifactManifestEntry(
                relativePath = ref.relativePath,
                kind = "screenshot",
                sizeBytes = file.length(),
            ),
            write = { target -> file.copyTo(target, overwrite = true) },
        )
    }

    private fun summarizeEvents(events: List<InvestigationEvent>): String {
        if (events.isEmpty()) return "No events in the selected window."
        val kindsSummary = events.groupingBy { it.kind }.eachCount()
            .entries.sortedByDescending { it.value }
            .joinToString(", ") { (kind, count) -> "$kind×$count" }
        return "${events.size} event(s): $kindsSummary"
    }

    private data class EvidenceSelection(
        val report: BugReport,
        val reportDir: File,
        val timeline: InvestigationTimeline,
        val events: List<InvestigationEvent>,
        val windowStart: Long,
        val windowEnd: Long,
        val centerMillis: Long,
        val summary: String,
    )

    private data class ArtifactResult(
        val manifestEntry: EvidenceArtifactManifestEntry,
        val write: (File) -> Unit,
        val redactedHeaderNames: List<String> = emptyList(),
        val redactedNodeCount: Int = 0,
        val truncatedFields: List<String> = emptyList(),
    )

    private data class ArtifactPlan(
        val candidates: List<EvidenceArtifactManifestEntry>,
        val writers: Map<String, (File) -> Unit>,
        val referenceOnly: List<EvidenceArtifactManifestEntry>,
        val redactionReport: RedactionReport,
    )

    private companion object {
        const val ManifestFileName = "manifest.json"
        const val RedactionReportFileName = "redaction-report.json"
    }
}
