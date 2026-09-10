package app.andy.model

/**
 * Fuzzy-matches a spoken transcript against known projects.
 *
 * Returns a project id when exactly one candidate clears the threshold; null on
 * no-match or ambiguity so callers fall back to their configured default rather
 * than guessing. Never rewrites the transcript — the caller passes it through
 * verbatim to the agent.
 *
 * @param projects list of (id, displayName) pairs
 */
fun matchProject(transcript: String, projects: List<Pair<String, String>>): String? {
    if (transcript.isBlank() || projects.isEmpty()) return null

    val normalizedTranscript = transcript.lowercase()
    val tokens = tokenize(normalizedTranscript)
    if (tokens.isEmpty()) return null

    val scored = projects.mapNotNull { (id, name) ->
        val candidates = listOf(id, name)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        val best = candidates.maxOfOrNull { label ->
            scoreProjectLabel(normalizedTranscript, tokens, label.lowercase())
        } ?: 0.0
        if (best >= MATCH_THRESHOLD) id to best else null
    }

    if (scored.isEmpty()) return null
    val bestScore = scored.maxOf { it.second }
    val winners = scored.filter { it.second >= bestScore - AMBIGUITY_EPSILON }
    // Ambiguity: two projects within epsilon of the top score → fall back.
    if (winners.size != 1) return null
    return winners.first().first
}

private const val MATCH_THRESHOLD = 0.82
private const val AMBIGUITY_EPSILON = 0.05
/** Short labels only match when the transcript names them as a project explicitly. */
private const val SHORT_LABEL_MAX = 4

private fun scoreProjectLabel(transcript: String, tokens: List<String>, label: String): Double {
    if (label.isBlank()) return 0.0

    // Explicit "… project" phrasing is the strongest signal and allows short names.
    val explicit = explicitProjectMentionScore(transcript, label)
    if (explicit > 0.0) return explicit

    // Bare fuzzy token match — refuse short labels so "sync the branches" cannot
    // claim a project named "sync".
    if (label.length <= SHORT_LABEL_MAX) return 0.0

    val labelTokens = tokenize(label)
    if (labelTokens.isEmpty()) return 0.0

    // Multi-word labels: best window of the same width in the transcript.
    if (labelTokens.size > 1) {
        if (tokens.size < labelTokens.size) return 0.0
        var best = 0.0
        for (i in 0..tokens.size - labelTokens.size) {
            val window = tokens.subList(i, i + labelTokens.size).joinToString(" ")
            best = maxOf(best, similarity(window, label))
        }
        return best
    }

    val single = labelTokens.first()
    return tokens.maxOfOrNull { similarity(it, single) } ?: 0.0
}

/**
 * Scores phrases like "in the phoebe project", "phoebe project", "project phoebe".
 * Exact (case-insensitive) and near-exact (edit distance ≤ 1 for labels ≥ 5) both count.
 */
private fun explicitProjectMentionScore(transcript: String, label: String): Double {
    val patterns = listOf(
        Regex("""(?:in\s+(?:the\s+)?)?([a-z0-9][a-z0-9._\-]*)\s+project\b"""),
        Regex("""\bproject\s+([a-z0-9][a-z0-9._\-]*)\b"""),
    )
    var best = 0.0
    for (pattern in patterns) {
        for (match in pattern.findAll(transcript)) {
            val spoken = match.groupValues[1]
            val score = when {
                spoken == label -> 1.0
                label.length >= 5 && levenshtein(spoken, label) <= 1 -> 0.92
                label.length >= 8 && levenshtein(spoken, label) <= 2 -> 0.88
                else -> similarity(spoken, label)
            }
            best = maxOf(best, score)
        }
    }
    // Also allow the full multi-word label inside "… project".
    if (label.contains(' ') || label.contains('-') || label.contains('_')) {
        val escaped = Regex.escape(label).replace("\\-", "[-\\s_]+").replace("\\_", "[-\\s_]+")
        val multi = Regex("""(?:in\s+(?:the\s+)?)?$escaped\s+project\b""")
        if (multi.containsMatchIn(transcript)) best = maxOf(best, 1.0)
    }
    return best
}

private fun tokenize(text: String): List<String> =
    text.lowercase()
        .split(Regex("""[^a-z0-9]+"""))
        .filter { it.isNotBlank() }

/** Normalized similarity in 0f..1f from Levenshtein distance. */
private fun similarity(a: String, b: String): Double {
    if (a == b) return 1.0
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val distance = levenshtein(a, b)
    val maxLen = maxOf(a.length, b.length)
    return 1.0 - distance.toDouble() / maxLen
}

private fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    val prev = IntArray(b.length + 1) { it }
    val curr = IntArray(b.length + 1)
    for (i in 1..a.length) {
        curr[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(
                curr[j - 1] + 1,
                prev[j] + 1,
                prev[j - 1] + cost,
            )
        }
        for (j in prev.indices) prev[j] = curr[j]
    }
    return prev[b.length]
}
