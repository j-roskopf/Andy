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
 * Export KetraTerm history + screen as newline-oriented ANSI so replay keeps
 * terminal styling (not raw TUI redraw noise or plain text).
 */
fun TerminalBuffer.exportScrollbackAnsi(): String {
    val reader = this as? TerminalRenderFrameReader
    if (reader == null) {
        return (this as? TerminalInspector)?.getAllAsString().orEmpty()
    }
    return exportScrollbackAnsi(reader)
}

/** Session-aware export; must be used for replay buffers owned by a live session. */
fun KetraSession.exportScrollbackAnsi(): String = exportScrollbackAnsi(this)

private fun exportScrollbackAnsi(reader: TerminalRenderFrameReader): String {
    var historySize = 0
    var screenRows = 0
    reader.readRenderFrame { frame ->
        historySize = frame.historySize
        screenRows = frame.rows
    }
    val viewportRows = historySize + screenRows
    if (viewportRows <= 0) return ""

    val out = StringBuilder()
    reader.readRenderFrame(scrollbackOffset = historySize, viewportRows = viewportRows) { frame ->
        for (row in 0 until frame.rows) {
            if (out.isNotEmpty()) out.append("\r\n")
            appendRenderLineAsAnsi(out, frame, row)
        }
    }
    if (out.isNotEmpty()) out.append("\u001b[0m")
    return out.toString()
}

private fun appendRenderLineAsAnsi(
    out: StringBuilder,
    frame: TerminalRenderFrame,
    row: Int,
) {
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
    if (lastContent < 0) return

    var currentAttr = Long.MIN_VALUE
    for (col in 0..lastContent) {
        if (flags[col] and TerminalRenderCellFlags.WIDE_TRAILING != 0) continue
        val attr = attrWords[col]
        if (attr != currentAttr) {
            out.append(renderAttrToAnsiSgr(attr))
            currentAttr = attr
        }
        clusters[col]?.let {
            out.append(it)
            continue
        }
        when {
            flags[col] and TerminalRenderCellFlags.EMPTY != 0 -> out.append(' ')
            codeWords[col] == 0 -> out.append(' ')
            else -> out.appendCodePoint(codeWords[col])
        }
    }
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
