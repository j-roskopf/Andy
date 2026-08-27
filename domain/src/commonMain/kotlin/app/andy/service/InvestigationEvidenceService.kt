package app.andy.service

import app.andy.model.AgentEvidencePreview
import app.andy.model.EvidenceMaterializeRequest
import app.andy.model.EvidencePreviewRequest
import app.andy.model.ManagedEvidenceBundle

/**
 * Builds evidence-backed context for contextual agent actions (§4). Callers only ever supply
 * an investigation id plus event selection — never an arbitrary filesystem path — so a bundle's
 * contents are always drawn from Andy's own saved investigation data.
 *
 * [preview] never writes to disk; only [materialize] persists a bundle, always under Andy's
 * managed evidence root. Nothing here sends data anywhere — callers decide what to do with the
 * returned bundle.
 */
interface InvestigationEvidenceService {
    suspend fun preview(request: EvidencePreviewRequest): AgentEvidencePreview
    suspend fun materialize(request: EvidenceMaterializeRequest): ManagedEvidenceBundle
}

object UnavailableInvestigationEvidenceService : InvestigationEvidenceService {
    override suspend fun preview(request: EvidencePreviewRequest): AgentEvidencePreview = AgentEvidencePreview(
        promptDraft = request.question,
        selectedEventsSummary = "Evidence bundles are unavailable on this platform.",
        windowStartMillis = 0L,
        windowEndMillis = 0L,
        totalBytes = 0L,
    )

    override suspend fun materialize(request: EvidenceMaterializeRequest): ManagedEvidenceBundle =
        error("Evidence bundles are unavailable on this platform.")
}
