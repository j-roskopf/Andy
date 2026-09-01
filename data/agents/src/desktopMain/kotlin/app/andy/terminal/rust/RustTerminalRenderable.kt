package app.andy.terminal.rust

import app.andy.model.TerminalAppearanceSnapshot
import kotlinx.coroutines.flow.StateFlow

/** Paint + input surface shared by live PTY sessions and read-only history replay. */
interface RustTerminalRenderable {
    val frameTick: StateFlow<Long>
    fun copyPaintFrame(into: RustTerminalFrame)
    fun resize(cols: Int, rows: Int)
    fun scrollDisplay(delta: Int)
    fun displayOffset(): Int = 0
    fun extractText(startLine: Int, startCol: Int, endLine: Int, endCol: Int): String = ""
    fun updateAppearance(appearance: TerminalAppearanceSnapshot)
    fun mouseFlags(): Int
    fun bracketedPasteEnabled(): Boolean
    fun write(bytes: ByteArray)
    fun close()
}
