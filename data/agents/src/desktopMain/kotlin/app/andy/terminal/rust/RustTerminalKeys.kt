package app.andy.terminal.rust

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint

internal fun formatTerminalPaste(text: String, bracketedPaste: Boolean): ByteArray {
    val payload = if (bracketedPaste && text.contains('\n')) {
        "\u001B[200~$text\u001B[201~"
    } else {
        text
    }
    return payload.toByteArray(Charsets.UTF_8)
}

/**
 * Minimal VT key encoder for the Rust terminal canvas.
 * Covers printable UTF-16, Enter/Tab/Backspace/Esc, arrows, and common Ctrl chords.
 */
internal fun isTerminalCopyChord(event: KeyEvent): Boolean =
    event.type == KeyEventType.KeyDown &&
        (event.isMetaPressed || event.isCtrlPressed) &&
        event.key == Key.C

internal fun isTerminalPasteChord(event: KeyEvent): Boolean =
    event.type == KeyEventType.KeyDown &&
        (event.isMetaPressed || event.isCtrlPressed) &&
        event.key == Key.V

private val modifierOnlyKeys = setOf(
    Key.CtrlLeft, Key.CtrlRight,
    Key.AltLeft, Key.AltRight,
    Key.ShiftLeft, Key.ShiftRight,
    Key.MetaLeft, Key.MetaRight,
)

/** AWT `KeyEvent.KEY_CHAR_UNDEFINED` — sent for modifier-only KEY_PRESSED events. */
private const val KEY_CHAR_UNDEFINED = 0xFFFF

internal fun encodeTerminalKey(event: KeyEvent): ByteArray? {
    if (event.type != KeyEventType.KeyDown) return null
    // Cmd/Meta chords are handled by the canvas (copy/paste) or dropped — never encoded.
    if (event.isMetaPressed) return null
    if (event.key in modifierOnlyKeys) return null

    if (event.isCtrlPressed) {
        encodeCtrl(event)?.let { return it }
    }

    when (event.key) {
        Key.Enter, Key.NumPadEnter -> return byteArrayOf('\r'.code.toByte())
        Key.Tab -> return byteArrayOf('\t'.code.toByte())
        Key.Backspace -> return byteArrayOf(0x7F)
        Key.Escape -> return byteArrayOf(0x1B)
        Key.DirectionUp -> return "\u001B[A".toByteArray()
        Key.DirectionDown -> return "\u001B[B".toByteArray()
        Key.DirectionRight -> return "\u001B[C".toByteArray()
        Key.DirectionLeft -> return "\u001B[D".toByteArray()
        Key.MoveHome -> return "\u001B[H".toByteArray()
        Key.MoveEnd -> return "\u001B[F".toByteArray()
        Key.PageUp -> return "\u001B[5~".toByteArray()
        Key.PageDown -> return "\u001B[6~".toByteArray()
        Key.Delete -> return "\u001B[3~".toByteArray()
        else -> Unit
    }

    val codePoint = event.utf16CodePoint
    if (
        codePoint != 0 &&
            codePoint != KEY_CHAR_UNDEFINED &&
            Character.isValidCodePoint(codePoint) &&
            !Character.isISOControl(codePoint)
    ) {
        return String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
    }
    return null
}

private fun encodeCtrl(event: KeyEvent): ByteArray? {
    // Prefer the physical key. AWT KEY_PRESSED for Ctrl+letter sets keyChar to the C0
    // control (Ctrl+C → ETX 0x03), not the letter — deriving from utf16CodePoint alone
    // would lowercase 0x03 and miss the 'c' → 0x03 mapping, so SIGINT never reached the PTY.
    ctrlLetterFromKey(event.key)?.let { letter ->
        return byteArrayOf((letter.code - 'a'.code + 1).toByte())
    }
    val cp = event.utf16CodePoint
    if (cp in 0x01..0x1F) {
        return byteArrayOf(cp.toByte())
    }
    val ch = when {
        cp != 0 &&
            cp != KEY_CHAR_UNDEFINED &&
            Character.isValidCodePoint(cp) -> cp.toChar().lowercaseChar()
        else -> return null
    }
    val ctrl = when (ch) {
        '[' -> 0x1B
        '\\' -> 0x1C
        ']' -> 0x1D
        else -> return null
    }
    return byteArrayOf(ctrl.toByte())
}

private fun ctrlLetterFromKey(key: Key): Char? = when (key) {
    Key.A -> 'a'
    Key.B -> 'b'
    Key.C -> 'c'
    Key.D -> 'd'
    Key.E -> 'e'
    Key.F -> 'f'
    Key.G -> 'g'
    Key.H -> 'h'
    Key.I -> 'i'
    Key.J -> 'j'
    Key.K -> 'k'
    Key.L -> 'l'
    Key.M -> 'm'
    Key.N -> 'n'
    Key.O -> 'o'
    Key.P -> 'p'
    Key.Q -> 'q'
    Key.R -> 'r'
    Key.S -> 's'
    Key.T -> 't'
    Key.U -> 'u'
    Key.V -> 'v'
    Key.W -> 'w'
    Key.X -> 'x'
    Key.Y -> 'y'
    Key.Z -> 'z'
    else -> null
}
