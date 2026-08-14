package app.andy.terminal.rust

import app.andy.model.TerminalAppearanceSnapshot
import app.andy.terminal.StyledTerminalRow
import app.andy.terminal.scrollbackReplayColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Read-only history viewer: feeds persisted ANSI into the Rust engine once and paints
 * via [RustTerminalCanvas]. Local scroll only; writes are ignored.
 */
class RustScrollbackReplay private constructor(
    private val engine: RustTerminalEngine,
    appearance: TerminalAppearanceSnapshot,
) : RustTerminalRenderable, AutoCloseable {
    private val appearanceRef = java.util.concurrent.atomic.AtomicReference(appearance)
    private val paintFrame = RustTerminalFrame()
    private val stagingFrame = RustTerminalFrame()
    private val publishLock = Any()
    private val version = AtomicLong(0)
    private val _frameTick = MutableStateFlow(0L)
    private val ready = AtomicBoolean(false)

    override val frameTick: StateFlow<Long> = _frameTick.asStateFlow()

    fun isReady(): Boolean = ready.get()

    override fun copyPaintFrame(into: RustTerminalFrame) {
        synchronized(publishLock) {
            into.copyFrom(paintFrame)
        }
    }

    override fun resize(cols: Int, rows: Int) {
        // History is fed at transcript width; ignore live layout churn so boxed TUIs
        // don't re-wrap into a shattered layout after mount.
    }

    override fun scrollDisplay(delta: Int) {
        engine.scrollDisplay(delta)
        publish()
    }

    override fun updateAppearance(appearance: TerminalAppearanceSnapshot) {
        appearanceRef.set(appearance)
        engine.setPalette(appearance.toRustPaletteArgb())
        publish()
    }

    override fun mouseFlags(): Int = 0

    override fun write(bytes: ByteArray) {
        // read-only
    }

    override fun close() {
        runCatching { engine.close() }
    }

    private fun publish() {
        if (engine.fillFrame(stagingFrame)) {
            synchronized(publishLock) {
                paintFrame.copyFrom(stagingFrame)
            }
            _frameTick.value = version.incrementAndGet()
        }
    }

    companion object {
        fun create(
            content: String,
            cols: Int = 0,
            rows: Int = 40,
            appearance: TerminalAppearanceSnapshot = TerminalAppearanceSnapshot(),
        ): RustScrollbackReplay {
            val display = content.trimEnd().ifBlank { "(no readable history for this chat)" }
            val columns = if (cols > 0) cols else scrollbackReplayColumns(display)
            val replayRows = rows.coerceAtLeast(1)
            val engine = RustTerminalEngine(columns, replayRows)
            engine.setPalette(appearance.toRustPaletteArgb())
            val payload = (display.replace("\r\n", "\n").replace("\n", "\r\n") + "\u001b[0m\u001b[?25l")
                .toByteArray(Charsets.UTF_8)
            // Chunk large transcripts so DEC 2026 / parser doesn't hold forever.
            var offset = 0
            while (offset < payload.size) {
                val end = (offset + 64 * 1024).coerceAtMost(payload.size)
                engine.advance(payload.copyOfRange(offset, end))
                offset = end
            }
            return RustScrollbackReplay(engine, appearance).also {
                it.publish()
                it.ready.set(true)
            }
        }
    }
}

/**
 * Headless Rust emulator for raw-tee → [StyledTerminalRow] transcript derivation.
 * Replaces the former BossTerm scrollback replay capture path.
 */
internal class RustScrollbackCapture(
    cols: Int,
    rows: Int,
) : AutoCloseable {
    private var columns = cols.coerceAtLeast(1)
    private var rowCount = rows.coerceAtLeast(1)
    private var engine = RustTerminalEngine(columns, rowCount)

    fun feed(chunk: String) {
        if (chunk.isEmpty()) return
        engine.advance(chunk.toByteArray(Charsets.UTF_8))
    }

    fun resize(cols: Int, rows: Int) {
        columns = cols.coerceAtLeast(1)
        rowCount = rows.coerceAtLeast(1)
        engine.resize(columns, rowCount)
    }

    fun styledRows(maxRows: Int = 0): List<StyledTerminalRow> {
        val frame = RustTerminalFrame()
        if (!engine.fillFrame(frame)) return emptyList()
        val rows = frame.rows
        if (rows <= 0 || frame.columns <= 0) return emptyList()
        val wanted = if (maxRows > 0) minOf(maxRows, rows) else rows
        val start = rows - wanted
        val out = ArrayList<StyledTerminalRow>(wanted)
        for (row in start until rows) {
            out += styledRowFromFrame(frame, row)
        }
        return out
    }

    override fun close() {
        runCatching { engine.close() }
    }
}

internal fun styledRowFromFrame(frame: RustTerminalFrame, row: Int): StyledTerminalRow {
    val cols = frame.columns
    if (row !in 0 until frame.rows || cols <= 0) return StyledTerminalRow("", "")
    val plainBuilder = StringBuilder(cols)
    val ansi = StringBuilder(cols + 32)
    var lastFg = 0
    var lastBg = 0
    var lastAttr = -1
    var emitted = false
    for (col in 0 until cols) {
        val idx = row * cols + col
        val ch = frame.chars.getOrElse(idx) { ' ' }
        plainBuilder.append(ch)
        val fg = frame.fgArgb.getOrElse(idx) { 0 }
        val bg = frame.bgArgb.getOrElse(idx) { 0 }
        val attr = frame.attrs.getOrElse(idx) { 0 }.toInt()
        if (!emitted || fg != lastFg || bg != lastBg || attr != lastAttr) {
            ansi.append(cellStyleToSgr(fg, bg, attr))
            lastFg = fg
            lastBg = bg
            lastAttr = attr
            emitted = true
        }
        ansi.append(ch)
    }
    val plain = plainBuilder.toString().trimEnd()
    if (plain.isEmpty()) return StyledTerminalRow("", "")
    // Trim ANSI to the same visible length.
    val trimmedAnsi = trimAnsiToPlainLength(ansi.toString(), plain.length)
    return StyledTerminalRow(plain = plain, ansi = trimmedAnsi + "\u001b[0m")
}

private fun cellStyleToSgr(fg: Int, bg: Int, attr: Int): String {
    val codes = mutableListOf("0")
    if (attr and RustTerminalAttrs.BOLD != 0) codes += "1"
    if (attr and RustTerminalAttrs.DIM != 0) codes += "2"
    if (attr and RustTerminalAttrs.ITALIC != 0) codes += "3"
    if (attr and RustTerminalAttrs.UNDERLINE != 0) codes += "4"
    if (attr and RustTerminalAttrs.INVERSE != 0) codes += "7"
    if (attr and RustTerminalAttrs.STRIKE != 0) codes += "9"
    appendTrueColor(codes, fg, foreground = true)
    appendTrueColor(codes, bg, foreground = false)
    return "\u001b[${codes.joinToString(";")}m"
}

private fun appendTrueColor(codes: MutableList<String>, argb: Int, foreground: Boolean) {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    codes += if (foreground) "38" else "48"
    codes += "2"
    codes += r.toString()
    codes += g.toString()
    codes += b.toString()
}

private fun trimAnsiToPlainLength(ansi: String, plainLength: Int): String {
    if (plainLength <= 0) return ""
    val out = StringBuilder(ansi.length)
    var visible = 0
    var i = 0
    while (i < ansi.length && visible < plainLength) {
        if (ansi[i] == '\u001b') {
            val end = ansi.indexOf('m', i)
            if (end < 0) break
            out.append(ansi, i, end + 1)
            i = end + 1
            continue
        }
        out.append(ansi[i])
        visible++
        i++
    }
    return out.toString()
}
