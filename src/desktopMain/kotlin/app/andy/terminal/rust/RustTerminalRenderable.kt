package app.andy.terminal.rust

import app.andy.model.TerminalAppearanceSnapshot
import kotlinx.coroutines.flow.StateFlow

/** Paint + input surface shared by live PTY sessions and read-only history replay. */
interface RustTerminalRenderable {
    val frameTick: StateFlow<Long>
    fun copyPaintFrame(into: RustTerminalFrame)
    fun resize(cols: Int, rows: Int)
    fun scrollDisplay(delta: Int)
    fun updateAppearance(appearance: TerminalAppearanceSnapshot)
    fun mouseFlags(): Int
    fun write(bytes: ByteArray)
    fun close()
}
