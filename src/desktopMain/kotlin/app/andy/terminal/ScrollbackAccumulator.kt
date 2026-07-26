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
        rows += saved
    }

    fun merge(snapshot: List<StyledTerminalRow>) {
        val incoming = snapshot.dropLastWhile { it.isBlank }
        if (incoming.isEmpty()) return
        val overlap = scrollbackSnapshotOverlap(rows, incoming)
        repeat(overlap) { rows.removeAt(rows.lastIndex) }
        rows += incoming
        if (rows.size > maxRows) {
            repeat(rows.size - maxRows) { rows.removeAt(0) }
        }
    }

    fun render(): String = rows.joinToString("\n") { it.ansi }.trimEnd()

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
                previous.plain != current.plain -> score -= MISMATCH_PENALTY
                !current.isBlank -> score += MATCH_REWARD
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

/**
 * True when [content] is a raw PTY tee (cursor motion, alt-screen switches, erases)
 * rather than the newline-oriented, SGR-only transcript Andy now persists.
 */
internal fun looksLikeRawAnsiTee(content: String): Boolean {
    if (!content.contains('\u001B')) return false
    return NonSgrControlSequence.containsMatchIn(content)
}
