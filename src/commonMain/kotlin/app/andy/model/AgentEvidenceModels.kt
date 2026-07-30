package app.andy.model

import kotlinx.serialization.Serializable

/** The contextual entry point that requested evidence-backed agent assistance. */
@Serializable
enum class ContextualActionKind {
    ExplainCrash,
    ExplainRequest,
    ExplainNode,
    ExplainMoment,
    InvestigateSelection,
}

/**
 * Points at a slice of a saved investigation timeline to use as evidence for an agent task.
 * Callers select either explicit [eventIds] or a window around [centerMillis]/[focusedEventId].
 */
@Serializable
data class InvestigationEvidenceRef(
    val investigationId: String,
    val eventIds: List<String> = emptyList(),
    val focusedEventId: String? = null,
    val centerMillis: Long = 0L,
    val windowRadiusMillis: Long = 30_000L,
)

/** One artifact written into (or referenced by) a managed evidence bundle. */
@Serializable
data class EvidenceArtifactManifestEntry(
    val relativePath: String,
    val kind: String,
    val sizeBytes: Long,
    val redacted: Boolean = false,
)

/** What Andy stripped, capped, or left out while building an evidence bundle — kept for transparency. */
@Serializable
data class RedactionReport(
    val redactedHeaderNames: List<String> = emptyList(),
    val redactedNodeCount: Int = 0,
    val truncatedFields: List<String> = emptyList(),
    val excludedArtifacts: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = redactedHeaderNames.isEmpty() && redactedNodeCount == 0 &&
            truncatedFields.isEmpty() && excludedArtifacts.isEmpty() && notes.isEmpty()
}

/**
 * A cheap, read-only preview of what materializing evidence would produce. No files are written
 * for a preview — [InvestigationEvidenceService.materialize] is required to persist a bundle.
 */
data class AgentEvidencePreview(
    val promptDraft: String,
    val selectedEventsSummary: String,
    val windowStartMillis: Long,
    val windowEndMillis: Long,
    val totalBytes: Long,
    val artifacts: List<EvidenceArtifactManifestEntry> = emptyList(),
    val redactionReport: RedactionReport = RedactionReport(),
    val exclusions: List<String> = emptyList(),
)

/**
 * A bundle materialized under Andy's managed evidence root (desktop: `~/.andy/evidence/<id>/`).
 * [rootRelativePath] is relative to the user's home directory so it stays portable across hosts.
 */
@Serializable
data class ManagedEvidenceBundle(
    val id: String,
    val rootRelativePath: String,
    val manifest: List<EvidenceArtifactManifestEntry> = emptyList(),
    val redactionReport: RedactionReport = RedactionReport(),
    val investigationId: String,
    val eventId: String? = null,
    val playbackMillis: Long? = null,
)

/** Where a contextual agent action was triggered from, threaded through as provenance. */
@Serializable
data class AgentContextualProvenance(
    val sourceKind: ContextualActionKind,
    val investigationId: String? = null,
    val eventId: String? = null,
    val playbackMillis: Long? = null,
    val networkExchangeId: String? = null,
    val crashId: String? = null,
    val hierarchyNodeId: String? = null,
    val packageName: String? = null,
)

/** Request to preview evidence for a caller-supplied question, without writing any files. */
data class EvidencePreviewRequest(
    val question: String,
    val evidence: InvestigationEvidenceRef,
    val provenance: AgentContextualProvenance,
)

/** Request to materialize a managed evidence bundle on disk. */
data class EvidenceMaterializeRequest(
    val evidence: InvestigationEvidenceRef,
    val provenance: AgentContextualProvenance,
)
