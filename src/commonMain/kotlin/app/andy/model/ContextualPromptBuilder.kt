package app.andy.model

private const val MaxPromptChars = 2_000

/**
 * Builds a compact prompt for a contextual agent action (§4). Heavy content (network bodies,
 * hierarchy trees, crash text) never goes here — it lives in the materialized evidence bundle's
 * files, referenced by relative path so the prompt stays small regardless of evidence size.
 */
fun buildContextualPrompt(
    question: String,
    provenance: AgentContextualProvenance,
    timelineSummary: String? = null,
    bundle: ManagedEvidenceBundle? = null,
): String {
    val sections = buildList {
        add(question.trim())
        provenanceLine(provenance)?.let { add(it) }
        timelineSummary?.takeIf { it.isNotBlank() }?.let { add("Timeline: $it") }
        bundle?.let { add(evidencePathsSection(it)) }
    }
    val prompt = sections.filter { it.isNotBlank() }.joinToString("\n\n")
    return capText(prompt, MaxPromptChars)
}

private fun provenanceLine(provenance: AgentContextualProvenance): String? {
    val parts = buildList {
        add("kind=${provenance.sourceKind.name}")
        provenance.packageName?.let { add("package=$it") }
        provenance.investigationId?.let { add("investigation=$it") }
        provenance.eventId?.let { add("event=$it") }
        provenance.playbackMillis?.let { add("atMillis=$it") }
        provenance.networkExchangeId?.let { add("exchange=$it") }
        provenance.crashId?.let { add("crash=$it") }
        provenance.hierarchyNodeId?.let { add("node=$it") }
    }
    if (parts.isEmpty()) return null
    return "Context: " + parts.joinToString(" · ")
}

private fun evidencePathsSection(bundle: ManagedEvidenceBundle): String = buildString {
    append("Managed evidence bundle: ").append(bundle.id).append('\n')
    append("Root (relative to your home directory): ").append(bundle.rootRelativePath)
    if (bundle.manifest.isNotEmpty()) {
        append("\nFiles:\n")
        bundle.manifest.joinTo(this, separator = "\n") { entry ->
            "- ${entry.relativePath} (${entry.kind}${if (entry.redacted) ", redacted" else ""})"
        }
    }
    if (!bundle.redactionReport.isEmpty) {
        append("\nNote: some evidence was redacted or truncated for safety; see redaction-report.json in the bundle.")
    }
}

private fun capText(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val cut = text.length - maxChars
    return text.take(maxChars) + "…[truncated $cut chars]"
}
