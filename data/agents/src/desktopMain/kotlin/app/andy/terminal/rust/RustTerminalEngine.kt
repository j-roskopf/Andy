package app.andy.terminal.rust

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * JVM handle around the Rust `alacritty_terminal` engine.
 *
 * Andy feeds PTY chunks and polls grid state on its own redraw cadence — there is
 * no per-character redraw callback from native code.
 *
 * Every native call goes through [withHandle], so a closed engine degrades to no-ops
 * instead of throwing: Compose keeps dispatching pointer and paint work at a backend
 * for a frame or two after a chat swap has closed it, and the PTY read loop can be
 * mid-call on another thread when [close] lands.
 */
class RustTerminalEngine(
    columns: Int,
    rows: Int,
) : AutoCloseable {
    @Volatile
    private var handle: Long = nativeCreate(columns, rows)
    private val lifecycle = ReentrantReadWriteLock()
    private var columns: Int = columns.coerceAtLeast(1)
    private var rows: Int = rows.coerceAtLeast(1)

    /** Unicode scalar values per cell (supports supplementary-plane glyphs). */
    private var codePoints = IntArray(this.columns * this.rows)
    private var fgArgb = IntArray(this.columns * this.rows)
    private var bgArgb = IntArray(this.columns * this.rows)
    private var attrs = ByteArray(this.columns * this.rows)
    private val meta = IntArray(8)

    init {
        check(handle != 0L) { "nativeCreate returned null handle" }
    }

    val isClosed: Boolean
        get() = handle == 0L

    fun advance(bytes: ByteArray) = withHandle(Unit) { h ->
        nativeAdvance(h, bytes)
    }

    fun advance(text: String) = advance(text.toByteArray(Charsets.UTF_8))

    fun resize(columns: Int, rows: Int) = withHandle(Unit) { h ->
        val cols = columns.coerceAtLeast(1)
        val r = rows.coerceAtLeast(1)
        nativeResize(h, cols, r)
        this.columns = cols
        this.rows = r
        ensureBuffers(cols * r)
    }

    fun setPalette(paletteArgb: IntArray) {
        require(paletteArgb.size >= 19) { "palette must be [fg,bg,cursor,ansi0..15]" }
        withHandle(Unit) { h ->
            check(nativeSetPalette(h, paletteArgb) == 0) { "nativeSetPalette failed" }
        }
    }

    fun isAltScreen(): Boolean = withHandle(false) { h -> nativeIsAltScreen(h) }

    fun syncBufferedBytes(): Int = withHandle(0) { h -> nativeSyncBufferedBytes(h) }

    fun stopSync() = withHandle(Unit) { h -> nativeStopSync(h) }

    fun scrollDisplay(delta: Int) = withHandle(Unit) { h -> nativeScrollDisplay(h, delta) }

    fun scrollToBottom() = withHandle(Unit) { h -> nativeScrollToBottom(h) }

    fun mouseFlags(): Int = withHandle(0) { h -> nativeMouseFlags(h) }

    fun bracketedPasteEnabled(): Boolean = withHandle(false) { h -> nativeBracketedPasteEnabled(h) }

    fun displayOffset(): Int = withHandle(0) { h -> nativeDisplayOffset(h) }

    fun viewportText(): String = withHandle("") { h -> nativeViewportText(h) }

    fun gridChars(): String = withHandle("") { h -> nativeGridChars(h) }

    fun cursorRow(): Int = withHandle(0) { h -> nativeCursorRow(h) }

    fun cursorCol(): Int = withHandle(0) { h -> nativeCursorCol(h) }

    fun columns(): Int = columns

    fun rows(): Int = rows

    fun cellBold(row: Int, col: Int): Boolean = withHandle(false) { h -> nativeCellBold(h, row, col) }

    fun extractText(startLine: Int, startCol: Int, endLine: Int, endCol: Int): String =
        withHandle("") { h -> nativeExtractText(h, startLine, startCol, endLine, endCol) }

    fun fillFrame(into: RustTerminalFrame): Boolean = withHandle(false) { h ->
        ensureBuffers(columns * rows)
        val rc = nativeFillSnapshot(h, codePoints, fgArgb, bgArgb, attrs, meta)
        if (rc != 0) return@withHandle false
        into.columns = meta[0]
        into.rows = meta[1]
        into.cursorRow = meta[2]
        into.cursorCol = meta[3]
        into.altScreen = meta[4] != 0
        into.syncBufferedBytes = meta[5]
        into.displayOffset = meta[6]
        into.historySize = meta[7]
        into.codePoints = codePoints
        into.fgArgb = fgArgb
        into.bgArgb = bgArgb
        into.attrs = attrs
        true
    }

    override fun close() {
        lifecycle.write {
            if (handle != 0L) {
                nativeDestroy(handle)
                handle = 0L
            }
        }
    }

    private fun ensureBuffers(cellCount: Int) {
        if (codePoints.size < cellCount) {
            codePoints = IntArray(cellCount)
            fgArgb = IntArray(cellCount)
            bgArgb = IntArray(cellCount)
            attrs = ByteArray(cellCount)
        }
    }

    /**
     * Runs [body] with the native handle pinned open, or returns [fallback] once the engine
     * is closed. The read lock is what keeps [close] from freeing the terminal underneath an
     * in-flight native call on another thread.
     */
    private inline fun <T> withHandle(fallback: T, body: (Long) -> T): T = lifecycle.read {
        val h = handle
        if (h == 0L) fallback else body(h)
    }

    private companion object {
        init {
            RustTerminalNative.ensureLoaded()
        }

        @JvmStatic external fun nativeCreate(columns: Int, rows: Int): Long
        @JvmStatic external fun nativeDestroy(handle: Long)
        @JvmStatic external fun nativeAdvance(handle: Long, bytes: ByteArray)
        @JvmStatic external fun nativeResize(handle: Long, columns: Int, rows: Int)
        @JvmStatic external fun nativeSetPalette(handle: Long, palette: IntArray): Int
        @JvmStatic external fun nativeIsAltScreen(handle: Long): Boolean
        @JvmStatic external fun nativeSyncBufferedBytes(handle: Long): Int
        @JvmStatic external fun nativeStopSync(handle: Long)
        @JvmStatic external fun nativeScrollDisplay(handle: Long, delta: Int)
        @JvmStatic external fun nativeScrollToBottom(handle: Long)
        @JvmStatic external fun nativeBracketedPasteEnabled(handle: Long): Boolean
        @JvmStatic external fun nativeMouseFlags(handle: Long): Int
        @JvmStatic external fun nativeDisplayOffset(handle: Long): Int
        @JvmStatic external fun nativeViewportText(handle: Long): String
        @JvmStatic external fun nativeGridChars(handle: Long): String
        @JvmStatic external fun nativeExtractText(
            handle: Long,
            startLine: Int,
            startCol: Int,
            endLine: Int,
            endCol: Int,
        ): String
        @JvmStatic external fun nativeCursorRow(handle: Long): Int
        @JvmStatic external fun nativeCursorCol(handle: Long): Int
        @JvmStatic external fun nativeColumns(handle: Long): Int
        @JvmStatic external fun nativeRows(handle: Long): Int
        @JvmStatic external fun nativeCellBold(handle: Long, row: Int, col: Int): Boolean
        @JvmStatic external fun nativeFillSnapshot(
            handle: Long,
            codePoints: IntArray,
            fgArgb: IntArray,
            bgArgb: IntArray,
            attrs: ByteArray,
            meta: IntArray,
        ): Int
    }
}

/** Mutable frame buffer shared between the backend poller and Compose painter. */
class RustTerminalFrame {
    var columns: Int = 0
    var rows: Int = 0
    var cursorRow: Int = 0
    var cursorCol: Int = 0
    var altScreen: Boolean = false
    var syncBufferedBytes: Int = 0
    var displayOffset: Int = 0
    var historySize: Int = 0
    var codePoints: IntArray = IntArray(0)
    var fgArgb: IntArray = IntArray(0)
    var bgArgb: IntArray = IntArray(0)
    var attrs: ByteArray = ByteArray(0)

    fun copyFrom(other: RustTerminalFrame) {
        columns = other.columns
        rows = other.rows
        cursorRow = other.cursorRow
        cursorCol = other.cursorCol
        altScreen = other.altScreen
        syncBufferedBytes = other.syncBufferedBytes
        displayOffset = other.displayOffset
        historySize = other.historySize
        val n = columns * rows
        if (codePoints.size < n) {
            codePoints = IntArray(n)
            fgArgb = IntArray(n)
            bgArgb = IntArray(n)
            attrs = ByteArray(n)
        }
        if (n > 0) {
            System.arraycopy(other.codePoints, 0, codePoints, 0, n)
            System.arraycopy(other.fgArgb, 0, fgArgb, 0, n)
            System.arraycopy(other.bgArgb, 0, bgArgb, 0, n)
            System.arraycopy(other.attrs, 0, attrs, 0, n)
        }
    }

    fun cellCodePoint(row: Int, col: Int): Int {
        if (row !in 0 until rows || col !in 0 until columns) return ' '.code
        val idx = row * columns + col
        return if (idx in codePoints.indices) codePoints[idx] else ' '.code
    }

    fun cellChar(row: Int, col: Int): Char {
        val cp = cellCodePoint(row, col)
        return if (cp in Char.MIN_VALUE.code..Char.MAX_VALUE.code) cp.toChar() else '\uFFFD'
    }

    fun cellString(row: Int, col: Int): String {
        val cp = cellCodePoint(row, col).takeIf { it > 0 } ?: ' '.code
        return if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else "\uFFFD"
    }
}

object RustTerminalAttrs {
    const val BOLD: Int = 1
    const val ITALIC: Int = 2
    const val UNDERLINE: Int = 4
    const val INVERSE: Int = 8
    const val DIM: Int = 16
    const val STRIKE: Int = 32
}

object RustMouseFlags {
    const val REPORTING: Int = 1
    const val SGR: Int = 2
    const val MOTION: Int = 4
    const val DRAG: Int = 8
    const val ALT_SCROLL: Int = 16
}
