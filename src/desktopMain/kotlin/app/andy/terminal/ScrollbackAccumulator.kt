package app.andy.terminal

/**
 * One captured terminal row. [plain] drives snapshot alignment and text search;
 * [ansi] carries the styling replay needs to look like the live terminal.
 */
data class StyledTerminalRow(val plain: String, val ansi: String) {
    val isBlank: Boolean = plain.isBlank()

    /** [plain] without surrounding whitespace, shared by every alignment comparison. */
    internal val trimmedPlain: String = plain.trim()

    /**
     * Cached [isVolatileTerminalChromeLine] verdict.
     *
     * Alignment scores one row against every candidate overlap, so recomputing the
     * classifier per pair dominated scrollback derivation. The verdict is a pure
     * function of [plain], so compute it at most once per row and reuse it thereafter.
     */
    internal val isVolatileChrome: Boolean
        get() = when (volatileChrome) {
            VOLATILE_TRUE -> true
            VOLATILE_FALSE -> false
            else -> isVolatileTerminalChromeLine(plain).also {
                volatileChrome = if (it) VOLATILE_TRUE else VOLATILE_FALSE
            }
        }

    @Volatile
    private var volatileChrome: Byte = VOLATILE_UNKNOWN

    private companion object {
        const val VOLATILE_UNKNOWN: Byte = 0
        const val VOLATILE_TRUE: Byte = 1
        const val VOLATILE_FALSE: Byte = 2
    }
}

/** CSI sequences other than SGR (`m`) — cursor motion, erases, mode switches. */
private val NonSgrControlSequence = Regex("\u001B\\[[0-9;:?<=>]*[ -/]*[@-ln-~]")
private val AnyEscapeSequence = Regex(
    "\u001B(?:\\[[0-9;:?<=>]*[ -/]*[@-~]|\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|[@-Z\\\\-_])",
)

/** Visible text of an ANSI-styled line. */
internal fun stripAnsi(line: String): String = AnyEscapeSequence.replace(line, "")

/** Split ANSI-styled terminal output (e.g. `tmux capture-pane -e`) into styled rows. */
internal fun styledRowsFromAnsiText(text: String): List<StyledTerminalRow> =
    text.replace("\r\n", "\n").removeSuffix("\n").split('\n').map { line ->
        StyledTerminalRow(plain = stripAnsi(line).trimEnd(), ansi = line)
    }

/**
 * Rebuilds one linear transcript out of repeated terminal screen snapshots.
 *
 * A snapshot is a moving window over the output stream: rows that scrolled past the
 * top are final, while the rest are still being repainted (spinners, status footers,
 * streaming text). Each [merge] aligns the incoming snapshot against the captured
 * tail, keeps whatever scrolled out of reach, and replaces the still-volatile tail
 * with the newest snapshot. Styling and column layout survive intact, so a replay
 * looks like the live terminal instead of a pile of half-drawn frames.
 */
class ScrollbackAccumulator(private val maxRows: Int = MAX_ROWS) {
    private val rows = mutableListOf<StyledTerminalRow>()

    val size: Int get() = rows.size

    /**
     * Adopt previously saved rows. Reattaching to a still-running session re-captures
     * output that is already on disk, so the first [merge] must be able to recognise it
     * instead of writing it out a second time.
     */
    fun seed(saved: List<StyledTerminalRow>) {
        rows.clear()
        rows += compactRepeatedProviderStartupFrames(saved)
    }

    fun merge(snapshot: List<StyledTerminalRow>) {
        val incoming = compactRepeatedProviderStartupFrames(snapshot)
            .dropLastWhile { it.isBlank }
        if (incoming.isEmpty()) return
        mergeTerminalRows(rows, incoming)
        if (rows.size > maxRows) {
            repeat(rows.size - maxRows) { rows.removeAt(0) }
        }
    }

    fun render(): String = rows.joinToString("\n") { it.ansi }.trimEnd()

    /**
     * A stable copy for raw-PTY replay while it is still being reconstructed.
     *
     * Unlike a set of seen line values, this preserves deliberately repeated text such as
     * list bullets, code, and a provider redrawing an identical response line.
     */
    fun snapshot(): List<StyledTerminalRow> = rows.toList()

    private companion object {
        const val MAX_ROWS = 20_000
    }
}

/**
 * How many trailing rows of [captured] the [snapshot] re-states.
 *
 * Scored rather than matched exactly, because a snapshot never lines up perfectly: the
 * app repaints the rows it is streaming into, and an input box pinned to the bottom of
 * the screen stays put while the rows above it scroll underneath. Agreement is therefore
 * worth more than disagreement costs, so a mostly-right alignment still beats treating
 * the snapshot as unrelated content. Ties go to the longest overlap so nothing repeats.
 */
internal fun scrollbackSnapshotOverlap(
    captured: List<StyledTerminalRow>,
    snapshot: List<StyledTerminalRow>,
): Int {
    val longest = minOf(captured.size, snapshot.size)
    if (longest == 0) return 0
    var bestOverlap = 0
    var bestScore = 0
    for (overlap in longest downTo 1) {
        // An alignment of this length can score at most overlap * MATCH_REWARD, and every
        // shorter one scores strictly less, so an incumbent already at that ceiling can no
        // longer be beaten. Ties still favour the longest overlap: the scan runs descending
        // and replacement below stays a strict `>`.
        if (bestScore >= overlap * MATCH_REWARD) break
        val base = captured.size - overlap
        var score = 0
        var remaining = overlap
        for (offset in 0 until overlap) {
            val previous = captured[base + offset]
            val current = snapshot[offset]
            when {
                terminalRowsEquivalent(previous, current) -> score += MATCH_REWARD
                !current.isBlank -> score -= MISMATCH_PENALTY
            }
            remaining--
            // Abandon an alignment whose best remaining outcome cannot pass the incumbent.
            // Safe to leave `score` partial: it is bounded by the same ceiling, so the
            // update below cannot fire.
            if (score + remaining * MATCH_REWARD <= bestScore) break
        }
        if (score > bestScore) {
            bestScore = score
            bestOverlap = overlap
        }
    }
    return bestOverlap
}

private const val MATCH_REWARD = 2
private const val MISMATCH_PENALTY = 1

private fun terminalRowsEquivalent(
    previous: StyledTerminalRow,
    current: StyledTerminalRow,
): Boolean {
    if (previous.plain == current.plain) return !current.isBlank
    if (previous.isVolatileChrome && current.isVolatileChrome) return true
    if (isAntigravityWelcomeLine(previous.plain) && isAntigravityWelcomeLine(current.plain)) {
        return true
    }
    if (isProviderLogoArtLine(previous.plain) && isProviderLogoArtLine(current.plain)) {
        return true
    }
    val left = previous.trimmedPlain
    val right = current.trimmedPlain
    val shorter = minOf(left.length, right.length)
    return shorter >= TRUNCATED_LINE_MATCH_MIN &&
        (left.startsWith(right) || right.startsWith(left))
}

private const val TRUNCATED_LINE_MATCH_MIN = 32

/**
 * Collapse full Codex startup frames that a raw-terminal reconstruction captured more
 * than once before the answer began.
 *
 * A replay snapshot normally behaves like one moving screen, but a provider can repaint
 * its startup box, prompt, plan-mode notice, and MCP warnings several times inside that
 * one reconstructed snapshot. Treat each boxed Codex banner as a successive screen frame
 * and merge the frames with the same overlap logic used for live snapshots. Session rules
 * split independent provider runs so a real resume/start banner remains visible.
 */
internal fun compactRepeatedProviderStartupFrames(
    input: List<StyledTerminalRow>,
): List<StyledTerminalRow> {
    if (input.isEmpty()) return input
    val output = mutableListOf<StyledTerminalRow>()
    var segmentStart = 0
    input.forEachIndexed { index, row ->
        if (row.plain.trim() == SCROLLBACK_SESSION_SEPARATOR.trim()) {
            output += compactProviderStartupSegment(input.subList(segmentStart, index))
            output += row
            segmentStart = index + 1
        }
    }
    output += compactProviderStartupSegment(input.subList(segmentStart, input.size))
    return output
}

/** Repair already-persisted styled history when it is opened read-only. */
internal fun compactRepeatedProviderStartupText(content: String): String {
    if (content.isBlank()) return content.trimEnd()
    return compactRepeatedProviderStartupFrames(styledRowsFromAnsiText(content))
        .joinToString("\n") { it.ansi }
        .trimEnd()
}

private fun compactProviderStartupSegment(
    rows: List<StyledTerminalRow>,
): List<StyledTerminalRow> {
    val frameStarts = providerStartupFrameStarts(rows)
    if (frameStarts.size < 2) return rows

    // A partially painted old-width border can precede the first complete frame. Drop
    // only that terminal chrome; preserve any real text earlier in the session.
    val prefix = rows.subList(0, frameStarts.first()).dropLastWhile { row ->
        row.isBlank || isTerminalBoxTop(row.plain) || isProviderLogoArtLine(row.plain)
    }
    val mergedFrames = mutableListOf<StyledTerminalRow>()
    frameStarts.forEachIndexed { index, start ->
        val end = frameStarts.getOrNull(index + 1) ?: rows.size
        mergeTerminalRows(
            mergedFrames,
            collapseRepeatedStartupChrome(rows.subList(start, end)),
        )
    }
    return collapseRepeatedStartupChrome(prefix + mergedFrames)
}

/** Codex boxed banner or Antigravity welcome/logo frames that were captured more than once. */
private fun providerStartupFrameStarts(rows: List<StyledTerminalRow>): List<Int> {
    val codex = rows.indices.filter { index ->
        index > 0 &&
            isProviderBootBannerLine(rows[index].plain) &&
            isTerminalBoxTop(rows[index - 1].plain)
    }.map { it - 1 }
    if (codex.size >= 2) return codex

    val welcomes = rows.indices.filter { isAntigravityWelcomeLine(rows[it].plain) }
    if (welcomes.size < 2) return emptyList()
    return welcomes.map { welcomeIndex ->
        var start = welcomeIndex
        while (start > 0) {
            val prev = rows[start - 1].plain
            if (prev.isBlank() || isProviderLogoArtLine(prev)) {
                start--
            } else {
                break
            }
        }
        start
    }.distinct()
}

/**
 * Remove only the partial Codex-box repaint proven by the reported capture.
 *
 * This intentionally does not use the broader volatile-line classifier: repeated rules,
 * status text, or ordinary response rows outside a repeated startup remain untouched.
 */
private fun collapseRepeatedStartupChrome(
    rows: List<StyledTerminalRow>,
): List<StyledTerminalRow> {
    val result = ArrayList<StyledTerminalRow>(rows.size)
    var providerBannerSeen = false
    rows.forEach { row ->
        val previous = result.lastOrNull()
        val repeatedProviderBanner = previous?.plain == row.plain &&
            (isProviderBootBannerLine(row.plain) || isAntigravityWelcomeLine(row.plain))
        val repeatedLeadingBoxTop = !providerBannerSeen &&
            previous != null &&
            isTerminalBoxTop(previous.plain) &&
            isTerminalBoxTop(row.plain)
        val repeatedLogoArt = !providerBannerSeen &&
            previous != null &&
            isProviderLogoArtLine(previous.plain) &&
            isProviderLogoArtLine(row.plain) &&
            previous.trimmedPlain == row.trimmedPlain
        if (repeatedProviderBanner || repeatedLeadingBoxTop || repeatedLogoArt) {
            return@forEach
        }
        result += row
        if (isProviderBootBannerLine(row.plain) || isAntigravityWelcomeLine(row.plain)) {
            providerBannerSeen = true
        }
    }
    return result
}

private fun isTerminalBoxTop(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.startsWith('╭') && trimmed.endsWith('╮')
}

internal fun isAntigravityWelcomeLine(line: String): Boolean =
    line.contains("Welcome to the Antigravity CLI", ignoreCase = true)

/** Block-drawing logo rows (Antigravity rainbow mark) without relying on SGR. */
internal fun isProviderLogoArtLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.length < 2) return false
    var blocks = 0
    for (char in trimmed) {
        if (char in PROVIDER_LOGO_BLOCK_CHARS) blocks++
    }
    return blocks * 2 >= trimmed.length
}

private const val PROVIDER_LOGO_BLOCK_CHARS =
    "█▀▄▌▐░▒▓■□▪▫◆◇▲▼△▽◢◣◤◥▆▇▅▃▂▁▔▕▖▗▘▙▚▛▜▝▞▟"

private fun mergeTerminalRows(
    captured: MutableList<StyledTerminalRow>,
    incoming: List<StyledTerminalRow>,
) {
    val overlap = when {
        isLikelyHomeRepaint(captured, incoming) -> minOf(captured.size, incoming.size)
        else -> scrollbackSnapshotOverlap(captured, incoming)
    }
    repeat(overlap) { captured.removeAt(captured.lastIndex) }
    captured += incoming
}

/**
 * Alt-screen TUIs often CSI-home and repaint the whole viewport (boot chrome, growing
 * markdown, tool lists). Line-by-line scoring then underlaps because most body rows
 * changed, and the previous screen is appended again — duplicated headings/logos.
 *
 * When the top stable line of [incoming] matches the top stable line of the previous
 * viewport, replace that viewport wholesale. True scrolling changes the top line, so
 * those snapshots still go through [scrollbackSnapshotOverlap].
 */
private fun isLikelyHomeRepaint(
    captured: List<StyledTerminalRow>,
    incoming: List<StyledTerminalRow>,
): Boolean {
    if (incoming.size < HOME_REPAINT_MIN_ROWS || captured.size < incoming.size) return false
    val previousTop = firstStableRow(captured.takeLast(incoming.size)) ?: return false
    val incomingTop = firstStableRow(incoming) ?: return false
    return terminalRowsEquivalent(previousTop, incomingTop) ||
        previousTop.trimmedPlain == incomingTop.trimmedPlain
}

private fun firstStableRow(rows: List<StyledTerminalRow>): StyledTerminalRow? =
    rows.firstOrNull { row ->
        if (row.isBlank) return@firstOrNull false
        if (isAntigravityWelcomeLine(row.plain) || isProviderLogoArtLine(row.plain)) return@firstOrNull true
        !row.isVolatileChrome && row.trimmedPlain.length >= 8
    }

private const val HOME_REPAINT_MIN_ROWS = 12

/**
 * Fold a durable prefix ([committed]) into a freshly derived current-run transcript.
 *
 * Mid-session `.ansi` writes are often a partial prefix of the same raw run (history
 * bridge / early persist). Blind concatenation duplicated Antigravity boot frames;
 * accumulator merge collapses the overlap while still appending true prior sessions.
 */
internal fun combineCommittedAndDerivedScrollback(
    committed: String,
    derived: String,
): String {
    val prior = committed.trimEnd()
    val current = derived.trimEnd()
    if (prior.isEmpty()) return current
    if (current.isEmpty()) return prior
    val acc = ScrollbackAccumulator()
    acc.seed(styledRowsFromAnsiText(prior))
    acc.merge(styledRowsFromAnsiText(current))
    return acc.render()
}

/**
 * True when [content] is a raw PTY tee (cursor motion, alt-screen switches, erases)
 * rather than the newline-oriented, SGR-only transcript Andy now persists.
 */
internal fun looksLikeRawAnsiTee(content: String): Boolean {
    if (!content.contains('\u001B')) return false
    return NonSgrControlSequence.containsMatchIn(content)
}
