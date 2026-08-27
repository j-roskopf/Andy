package app.andy.terminal

/**
 * DECSET 25h — show cursor. Agent TUIs hide the hardware cursor (`\e[?25l`) and draw their
 * own prompt block; they still emit 25h on spinner redraws, which flashes the emulator
 * cursor at the grid tail. Keep hide sequences so the embed stays cursorless.
 *
 * Matched as raw bytes (`ESC [ ? 2 5 h`) so sanitizing never UTF-8-decodes a PTY chunk.
 * A String round-trip would replace incomplete multi-byte glyphs at chunk boundaries with
 * U+FFFD and bake those diamonds into the live terminal stream.
 */
private val ShowCursorCsiBytes = byteArrayOf(
    0x1B, '['.code.toByte(), '?'.code.toByte(),
    '2'.code.toByte(), '5'.code.toByte(), 'h'.code.toByte(),
)

/**
 * Strip PTY bytes that make embedded agent TUIs flicker when replayed through the embed.
 * Returns the original slice when nothing was removed.
 */
internal fun sanitizeAgentCliPtyChunk(bytes: ByteArray, offset: Int, length: Int): Triple<ByteArray, Int, Int> {
    if (length <= 0) return Triple(bytes, offset, length)
    val end = offset + length
    val seqLen = ShowCursorCsiBytes.size
    var hits = 0
    var i = offset
    while (i <= end - seqLen) {
        if (matchesShowCursorCsi(bytes, i)) {
            hits++
            i += seqLen
        } else {
            i++
        }
    }
    if (hits == 0) return Triple(bytes, offset, length)

    val out = ByteArray(length - hits * seqLen)
    var src = offset
    var dst = 0
    while (src < end) {
        if (src <= end - seqLen && matchesShowCursorCsi(bytes, src)) {
            src += seqLen
        } else {
            out[dst++] = bytes[src++]
        }
    }
    return Triple(out, 0, out.size)
}

private fun matchesShowCursorCsi(bytes: ByteArray, index: Int): Boolean {
    for (j in ShowCursorCsiBytes.indices) {
        if (bytes[index + j] != ShowCursorCsiBytes[j]) return false
    }
    return true
}
