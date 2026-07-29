package app.andy.terminal

import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.core.api.TerminalInspector
import io.github.ketraterm.render.api.TerminalRenderAttrs
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.api.TerminalRenderClusterSink
import io.github.ketraterm.render.api.TerminalRenderColorKind
import io.github.ketraterm.render.api.TerminalRenderFrame
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.api.TerminalRenderUnderline
import io.github.ketraterm.session.TerminalSession as KetraSession

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
     * ~24-regex classifier per pair dominated scrollback derivation. The verdict is a pure
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

    /**
     * Tri-state cache for [isVolatileChrome]. Races are harmless — the classifier is pure,
     * so concurrent computers agree — which buys a plain field over a `lazy` allocation on
     * every one of a transcript's rows.
     */
    @Volatile
    private var volatileChrome: Byte = VOLATILE_UNKNOWN

    private companion object {
        const val VOLATILE_UNKNOWN: Byte = 0
        const val VOLATILE_TRUE: Byte = 1
        const val VOLATILE_FALSE: Byte = 2
    }
}

/**
 * Export KetraTerm history + screen as newline-oriented ANSI so replay keeps
 * terminal styling (not raw TUI redraw noise or plain text).
 */
fun TerminalBuffer.exportScrollbackAnsi(): String {
    val reader = this as? TerminalRenderFrameReader
    if (reader == null) {
        return (this as? TerminalInspector)?.getAllAsString().orEmpty()
    }
    return reader.readStyledScrollbackRows().joinToString("\r\n") { it.ansi }
}

/** Session-aware export; must be used for replay buffers owned by a live session. */
fun KetraSession.exportScrollbackAnsi(): String =
    readStyledScrollbackRows().joinToString("\r\n") { it.ansi }

/**
 * Read history + screen as styled rows, newest [maxRows] only when positive.
 *
 * Bounding the window keeps periodic capture cheap; callers stitch successive
 * windows back together with [app.andy.terminal.ScrollbackAccumulator].
 */
fun TerminalRenderFrameReader.readStyledScrollbackRows(maxRows: Int = 0): List<StyledTerminalRow> {
    var historySize = 0
    var screenRows = 0
    readRenderFrame { frame ->
        historySize = frame.historySize
        screenRows = frame.rows
    }
    val total = historySize + screenRows
    if (total <= 0) return emptyList()
    val wanted = if (maxRows in 1 until total) maxRows else total
    val rows = ArrayList<StyledTerminalRow>(wanted)
    readRenderFrame(scrollbackOffset = historySize - (total - wanted), viewportRows = wanted) { frame ->
        for (row in 0 until frame.rows) {
            rows += renderLineAsStyledRow(frame, row)
        }
    }
    return rows
}

/**
 * Styled rows not yet present in [seenKeys] (matched on trimmed [StyledTerminalRow.plain]).
 *
 * Used by [replayCaptureStyledRows] to sample a replay buffer after every chunk fed to
 * it: on the alt screen a row that scrolls off between two samples is gone for good, so
 * capturing new rows as they appear — rather than only reading the final screen — is what
 * keeps a fast replay (or a fast-talking model) from losing whatever scrolled past.
 */
internal fun captureNewStyledRows(
    reader: TerminalRenderFrameReader,
    seenKeys: MutableSet<String>,
): List<StyledTerminalRow> = buildList {
    for (row in reader.readStyledScrollbackRows()) {
        val key = row.plain.trim()
        if (key.isEmpty() || key in seenKeys) continue
        seenKeys += key
        add(row)
    }
}

private fun renderLineAsStyledRow(
    frame: TerminalRenderFrame,
    row: Int,
): StyledTerminalRow {
    val columns = frame.columns
    val codeWords = IntArray(columns)
    val attrWords = LongArray(columns)
    val flags = IntArray(columns)
    val extraAttrWords = LongArray(columns)
    val hyperlinkIds = IntArray(columns)
    val clusters = HashMap<Int, String>(4)

    frame.copyLine(
        row = row,
        codeWords = codeWords,
        attrWords = attrWords,
        flags = flags,
        extraAttrWords = extraAttrWords,
        hyperlinkIds = hyperlinkIds,
        clusterSink = TerminalRenderClusterSink { column, text -> clusters[column] = text },
        clusterDataSink = null,
    )

    var lastContent = columns - 1
    while (lastContent >= 0) {
        when {
            flags[lastContent] and TerminalRenderCellFlags.WIDE_TRAILING != 0 -> lastContent--
            flags[lastContent] and TerminalRenderCellFlags.EMPTY != 0 -> lastContent--
            codeWords[lastContent] == 0 -> lastContent--
            else -> break
        }
    }
    if (lastContent < 0) return StyledTerminalRow("", "")

    val ansi = StringBuilder()
    val plain = StringBuilder()
    var currentAttr = Long.MIN_VALUE
    for (col in 0..lastContent) {
        if (flags[col] and TerminalRenderCellFlags.WIDE_TRAILING != 0) continue
        val attr = attrWords[col]
        if (attr != currentAttr) {
            ansi.append(renderAttrToAnsiSgr(attr))
            currentAttr = attr
        }
        val cluster = clusters[col]
        if (cluster != null) {
            ansi.append(cluster)
            plain.append(cluster)
            continue
        }
        when {
            flags[col] and TerminalRenderCellFlags.EMPTY != 0 -> {
                ansi.append(' ')
                plain.append(' ')
            }
            codeWords[col] == 0 -> {
                ansi.append(' ')
                plain.append(' ')
            }
            else -> {
                ansi.appendCodePoint(codeWords[col])
                plain.appendCodePoint(codeWords[col])
            }
        }
    }
    // Each row resets its own styling so the transcript can be re-ordered and
    // truncated without a stale SGR bleeding into everything below it.
    if (currentAttr != Long.MIN_VALUE) ansi.append("\u001b[0m")
    return StyledTerminalRow(plain = plain.toString(), ansi = ansi.toString())
}

internal fun renderAttrToAnsiSgr(attr: Long): String {
    val codes = mutableListOf("0")
    if (TerminalRenderAttrs.isBold(attr)) codes += "1"
    if (TerminalRenderAttrs.isFaint(attr)) codes += "2"
    if (TerminalRenderAttrs.isItalic(attr)) codes += "3"
    when (TerminalRenderAttrs.underlineStyle(attr)) {
        TerminalRenderUnderline.SINGLE -> codes += "4"
        TerminalRenderUnderline.DOUBLE -> codes += "21"
        TerminalRenderUnderline.CURLY -> codes += "4:3"
        TerminalRenderUnderline.DOTTED -> codes += "4:4"
        TerminalRenderUnderline.DASHED -> codes += "4:5"
    }
    if (TerminalRenderAttrs.isBlink(attr)) codes += "5"
    if (TerminalRenderAttrs.isInverse(attr)) codes += "7"
    if (TerminalRenderAttrs.isInvisible(attr)) codes += "8"
    if (TerminalRenderAttrs.isStrikethrough(attr)) codes += "9"
    appendAnsiColorCodes(
        codes = codes,
        kind = TerminalRenderAttrs.foregroundKind(attr),
        value = TerminalRenderAttrs.foregroundValue(attr),
        foreground = true,
    )
    appendAnsiColorCodes(
        codes = codes,
        kind = TerminalRenderAttrs.backgroundKind(attr),
        value = TerminalRenderAttrs.backgroundValue(attr),
        foreground = false,
    )
    return "\u001B[${codes.joinToString(";")}m"
}

private fun appendAnsiColorCodes(
    codes: MutableList<String>,
    kind: Int,
    value: Int,
    foreground: Boolean,
) {
    when (kind) {
        TerminalRenderColorKind.INDEXED -> {
            val prefix = if (foreground) "38" else "48"
            codes += listOf(prefix, "5", value.toString())
        }
        TerminalRenderColorKind.RGB -> {
            val prefix = if (foreground) "38" else "48"
            val red = (value shr 16) and 0xFF
            val green = (value shr 8) and 0xFF
            val blue = value and 0xFF
            codes += listOf(prefix, "2", red.toString(), green.toString(), blue.toString())
        }
        TerminalRenderColorKind.DEFAULT -> Unit
    }
}
