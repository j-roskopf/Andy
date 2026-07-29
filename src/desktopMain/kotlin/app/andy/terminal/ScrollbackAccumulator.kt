package app.andy.terminal

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
        val base = captured.size - overlap
        var score = 0
        for (offset in 0 until overlap) {
            val previous = captured[base + offset]
            val current = snapshot[offset]
            when {
                terminalRowsEquivalent(previous.plain, current.plain) -> score += MATCH_REWARD
                !current.isBlank -> score -= MISMATCH_PENALTY
            }
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

private fun terminalRowsEquivalent(previous: String, current: String): Boolean {
    if (previous == current) return current.isNotBlank()
    if (isVolatileTerminalChromeLine(previous) && isVolatileTerminalChromeLine(current)) {
        return true
    }
    val left = previous.trim()
    val right = current.trim()
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
    val frameStarts = rows.indices.filter { index ->
        index > 0 &&
            isProviderBootBannerLine(rows[index].plain) &&
            isTerminalBoxTop(rows[index - 1].plain)
    }.map { it - 1 }
    if (frameStarts.size < 2) return rows

    // A partially painted old-width border can precede the first complete frame. Drop
    // only that terminal chrome; preserve any real text earlier in the session.
    val prefix = rows.subList(0, frameStarts.first()).dropLastWhile { row ->
        row.isBlank || isTerminalBoxTop(row.plain)
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
            isProviderBootBannerLine(row.plain)
        val repeatedLeadingBoxTop = !providerBannerSeen &&
            previous != null &&
            isTerminalBoxTop(previous.plain) &&
            isTerminalBoxTop(row.plain)
        if (repeatedProviderBanner || repeatedLeadingBoxTop) {
            return@forEach
        }
        result += row
        if (isProviderBootBannerLine(row.plain)) providerBannerSeen = true
    }
    return result
}

private fun isTerminalBoxTop(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.startsWith('╭') && trimmed.endsWith('╮')
}

private fun mergeTerminalRows(
    captured: MutableList<StyledTerminalRow>,
    incoming: List<StyledTerminalRow>,
) {
    val overlap = scrollbackSnapshotOverlap(captured, incoming)
    repeat(overlap) { captured.removeAt(captured.lastIndex) }
    captured += incoming
}

/**
 * True when [content] is a raw PTY tee (cursor motion, alt-screen switches, erases)
 * rather than the newline-oriented, SGR-only transcript Andy now persists.
 */
internal fun looksLikeRawAnsiTee(content: String): Boolean {
    if (!content.contains('\u001B')) return false
    return NonSgrControlSequence.containsMatchIn(content)
}
