package app.andy.terminal

import java.nio.charset.StandardCharsets

/**
 * DECSET 25h — show cursor. Agent TUIs hide the hardware cursor (`\e[?25l`) and draw their
 * own prompt block; they still emit 25h on spinner redraws, which flashes the emulator
 * cursor at the grid tail. Keep hide sequences so KetraTerm stays cursorless.
 */
private val ShowCursorCsi = Regex("""\u001B\[\?25h""")

/**
 * Strip PTY bytes that make embedded agent TUIs flicker when replayed through KetraTerm.
 * Returns the original slice when nothing was removed.
 */
internal fun sanitizeAgentCliPtyChunk(bytes: ByteArray, offset: Int, length: Int): Triple<ByteArray, Int, Int> {
    if (length <= 0) return Triple(bytes, offset, length)
    val chunk = String(bytes, offset, length, StandardCharsets.UTF_8)
    if (!ShowCursorCsi.containsMatchIn(chunk)) return Triple(bytes, offset, length)
    val sanitized = ShowCursorCsi.replace(chunk, "")
    val out = sanitized.toByteArray(StandardCharsets.UTF_8)
    return Triple(out, 0, out.size)
}
