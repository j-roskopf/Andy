package app.andy.terminal.rust

/**
 * JVM handle around the Rust `alacritty_terminal` engine (Phase-0 spike only).
 *
 * Andy feeds PTY chunks and polls grid state on its own redraw cadence — there is
 * no per-character redraw callback from native code.
 */
class RustTerminalEngine(
    columns: Int,
    rows: Int,
) : AutoCloseable {
    private var handle: Long = nativeCreate(columns, rows)

    init {
        check(handle != 0L) { "nativeCreate returned null handle" }
    }

    fun advance(bytes: ByteArray) {
        checkOpen()
        nativeAdvance(handle, bytes)
    }

    fun advance(text: String) = advance(text.toByteArray(Charsets.UTF_8))

    fun resize(columns: Int, rows: Int) {
        checkOpen()
        nativeResize(handle, columns, rows)
    }

    fun isAltScreen(): Boolean {
        checkOpen()
        return nativeIsAltScreen(handle)
    }

    fun syncBufferedBytes(): Int {
        checkOpen()
        return nativeSyncBufferedBytes(handle)
    }

    fun viewportText(): String {
        checkOpen()
        return nativeViewportText(handle)
    }

    /** Row-major grid characters, length = rows * columns. */
    fun gridChars(): String {
        checkOpen()
        return nativeGridChars(handle)
    }

    fun cursorRow(): Int {
        checkOpen()
        return nativeCursorRow(handle)
    }

    fun cursorCol(): Int {
        checkOpen()
        return nativeCursorCol(handle)
    }

    fun columns(): Int {
        checkOpen()
        return nativeColumns(handle)
    }

    fun rows(): Int {
        checkOpen()
        return nativeRows(handle)
    }

    fun cellBold(row: Int, col: Int): Boolean {
        checkOpen()
        return nativeCellBold(handle, row, col)
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private fun checkOpen() {
        check(handle != 0L) { "RustTerminalEngine is closed" }
    }

    private companion object {
        init {
            RustTerminalNative.ensureLoaded()
        }

        @JvmStatic external fun nativeCreate(columns: Int, rows: Int): Long
        @JvmStatic external fun nativeDestroy(handle: Long)
        @JvmStatic external fun nativeAdvance(handle: Long, bytes: ByteArray)
        @JvmStatic external fun nativeResize(handle: Long, columns: Int, rows: Int)
        @JvmStatic external fun nativeIsAltScreen(handle: Long): Boolean
        @JvmStatic external fun nativeSyncBufferedBytes(handle: Long): Int
        @JvmStatic external fun nativeViewportText(handle: Long): String
        @JvmStatic external fun nativeGridChars(handle: Long): String
        @JvmStatic external fun nativeCursorRow(handle: Long): Int
        @JvmStatic external fun nativeCursorCol(handle: Long): Int
        @JvmStatic external fun nativeColumns(handle: Long): Int
        @JvmStatic external fun nativeRows(handle: Long): Int
        @JvmStatic external fun nativeCellBold(handle: Long, row: Int, col: Int): Boolean
    }
}
