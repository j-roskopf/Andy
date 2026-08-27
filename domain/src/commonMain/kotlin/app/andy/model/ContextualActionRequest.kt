package app.andy.model

/**
 * Inline source text (crash excerpt, request line, node properties) is capped well below the
 * prompt budget so provenance and evidence-bundle paths always survive the final trim.
 */
private const val MaxExcerptChars = 1_200

/**
 * A contextual agent action assembled by an entry point (§5) and handed to the confirmation
 * sheet. [evidence] is null when no saved investigation backs the action; the sheet then runs
 * prompt-only and [contextExcerpt] carries the small amount of inline detail instead.
 */
data class ContextualActionRequest(
    val kind: ContextualActionKind,
    /** Sheet heading, e.g. "Explain crash". */
    val title: String,
    /** Editable one-line ask shown at the top of the prompt. */
    val question: String,
    val provenance: AgentContextualProvenance,
    val evidence: InvestigationEvidenceRef? = null,
    val contextExcerpt: String? = null,
) {
    val hasEvidence: Boolean get() = evidence != null
}

/** The question plus any inline excerpt, capped so provenance and evidence paths always survive. */
fun ContextualActionRequest.questionWithExcerpt(): String {
    val excerpt = contextExcerpt?.trim()?.takeIf { it.isNotEmpty() } ?: return question
    return "$question\n\n${capExcerpt(excerpt)}"
}

/** The prompt Andy pre-fills the sheet with when no evidence preview is available. */
fun ContextualActionRequest.promptDraft(
    timelineSummary: String? = null,
    bundle: ManagedEvidenceBundle? = null,
): String = buildContextualPrompt(questionWithExcerpt(), provenance, timelineSummary, bundle)

/**
 * Actions triggered from a live device surface default to attaching Andy's MCP server so the
 * agent can inspect the running device; saved-investigation actions read the bundle instead.
 */
fun ContextualActionKind.attachesAndyMcpByDefault(): Boolean = when (this) {
    ContextualActionKind.ExplainCrash,
    ContextualActionKind.ExplainRequest,
    ContextualActionKind.ExplainNode,
    -> true
    ContextualActionKind.ExplainMoment,
    ContextualActionKind.InvestigateSelection,
    ContextualActionKind.Kanban,
    -> false
}

/** Short chat title for the task a contextual action launches. */
fun ContextualActionRequest.taskTitle(): String = title

/** Crashes panel (§5.1). [investigationId] is set only when a saved report holds this crash. */
fun explainCrashRequest(
    crashId: String,
    packageName: String?,
    summary: String,
    crashText: String,
    investigationId: String? = null,
    eventId: String? = null,
    atMillis: Long? = null,
): ContextualActionRequest = ContextualActionRequest(
    kind = ContextualActionKind.ExplainCrash,
    title = "Explain crash",
    question = "Explain this crash and give the most likely root cause, then the smallest safe fix.",
    provenance = AgentContextualProvenance(
        sourceKind = ContextualActionKind.ExplainCrash,
        investigationId = investigationId,
        eventId = eventId,
        playbackMillis = atMillis,
        crashId = crashId,
        packageName = packageName,
    ),
    evidence = investigationId?.let { id ->
        InvestigationEvidenceRef(investigationId = id, focusedEventId = eventId, centerMillis = atMillis ?: 0L)
    },
    contextExcerpt = buildExcerpt(
        "Crash summary" to summary,
        "Crash detail" to crashText,
    ),
)

/** Network screen (§5.2). [headerSummary] must already be redacted by the caller. */
fun explainNetworkRequest(
    exchangeId: String,
    method: String,
    url: String,
    statusCode: Int?,
    durationMillis: Long?,
    error: String?,
    headerSummary: String? = null,
    packageName: String? = null,
    investigationId: String? = null,
    eventId: String? = null,
    atMillis: Long? = null,
): ContextualActionRequest = ContextualActionRequest(
    kind = ContextualActionKind.ExplainRequest,
    title = "Explain request",
    question = "Explain this network exchange: what it does, why it returned what it did, and what to check next.",
    provenance = AgentContextualProvenance(
        sourceKind = ContextualActionKind.ExplainRequest,
        investigationId = investigationId,
        eventId = eventId,
        playbackMillis = atMillis,
        networkExchangeId = exchangeId,
        packageName = packageName,
    ),
    evidence = investigationId?.let { id ->
        InvestigationEvidenceRef(investigationId = id, focusedEventId = eventId, centerMillis = atMillis ?: 0L)
    },
    contextExcerpt = buildExcerpt(
        "Exchange" to listOfNotNull(
            method,
            statusCode?.toString() ?: "no status",
            url,
            durationMillis?.let { "${it}ms" },
        ).joinToString(" "),
        "Error" to error,
        "Headers" to headerSummary,
    ),
)

/** Inspector (§5.3). The hierarchy snapshot only comes along when an investigation holds one. */
fun explainNodeRequest(
    nodeId: String,
    className: String?,
    resourceId: String?,
    text: String?,
    contentDescription: String?,
    bounds: String?,
    packageName: String? = null,
    investigationId: String? = null,
    eventId: String? = null,
    atMillis: Long? = null,
): ContextualActionRequest = ContextualActionRequest(
    kind = ContextualActionKind.ExplainNode,
    title = "Explain node",
    question = "Explain this view hierarchy node: what renders it, and why it may be laid out or behaving this way.",
    provenance = AgentContextualProvenance(
        sourceKind = ContextualActionKind.ExplainNode,
        investigationId = investigationId,
        eventId = eventId,
        playbackMillis = atMillis,
        hierarchyNodeId = nodeId,
        packageName = packageName,
    ),
    evidence = investigationId?.let { id ->
        InvestigationEvidenceRef(investigationId = id, focusedEventId = eventId, centerMillis = atMillis ?: 0L)
    },
    contextExcerpt = buildExcerpt(
        "Class" to className,
        "Resource id" to resourceId,
        "Text" to text,
        "Content description" to contentDescription,
        "Bounds" to bounds,
    ),
)

/** Bugs timeline (§5.4): one selected event or the current scrub position. */
fun explainMomentRequest(
    investigationId: String,
    eventId: String?,
    playbackMillis: Long,
    momentSummary: String? = null,
    packageName: String? = null,
    windowRadiusMillis: Long = DefaultMomentRadiusMillis,
): ContextualActionRequest = ContextualActionRequest(
    kind = ContextualActionKind.ExplainMoment,
    title = "Explain this moment",
    question = "Explain what happened at this moment in the investigation and what most likely caused it.",
    provenance = AgentContextualProvenance(
        sourceKind = ContextualActionKind.ExplainMoment,
        investigationId = investigationId,
        eventId = eventId,
        playbackMillis = playbackMillis,
        packageName = packageName,
    ),
    evidence = InvestigationEvidenceRef(
        investigationId = investigationId,
        focusedEventId = eventId,
        centerMillis = playbackMillis,
        windowRadiusMillis = windowRadiusMillis,
    ),
    contextExcerpt = buildExcerpt("Selected event" to momentSummary),
)

/** Bugs timeline (§5.4): the currently filtered slice of events around the scrub position. */
fun investigateSelectionRequest(
    investigationId: String,
    eventIds: List<String>,
    playbackMillis: Long,
    selectionSummary: String? = null,
    packageName: String? = null,
    windowRadiusMillis: Long = DefaultSelectionRadiusMillis,
): ContextualActionRequest = ContextualActionRequest(
    kind = ContextualActionKind.InvestigateSelection,
    title = "Investigate selection",
    question = "Investigate this slice of the investigation timeline and explain the most likely root cause.",
    provenance = AgentContextualProvenance(
        sourceKind = ContextualActionKind.InvestigateSelection,
        investigationId = investigationId,
        eventId = eventIds.firstOrNull(),
        playbackMillis = playbackMillis,
        packageName = packageName,
    ),
    evidence = InvestigationEvidenceRef(
        investigationId = investigationId,
        eventIds = eventIds,
        centerMillis = playbackMillis,
        windowRadiusMillis = windowRadiusMillis,
    ),
    contextExcerpt = buildExcerpt("Selection" to selectionSummary),
)

const val DefaultMomentRadiusMillis = 15_000L
const val DefaultSelectionRadiusMillis = 60_000L

private fun buildExcerpt(vararg fields: Pair<String, String?>): String? = fields
    .mapNotNull { (label, value) ->
        value?.trim()?.takeIf { it.isNotEmpty() }?.let { "$label: $it" }
    }
    .takeIf { it.isNotEmpty() }
    ?.joinToString("\n")

private fun capExcerpt(text: String): String {
    if (text.length <= MaxExcerptChars) return text
    return text.take(MaxExcerptChars) + "…[truncated]"
}
