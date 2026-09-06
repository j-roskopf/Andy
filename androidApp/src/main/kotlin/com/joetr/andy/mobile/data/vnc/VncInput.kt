package com.joetr.andy.mobile.data.vnc

/** X11 keysyms we care about. */
object Keysym {
    const val BackSpace = 0xFF08
    const val Tab = 0xFF09
    const val Return = 0xFF0D
    const val Escape = 0xFF1B
    const val Left = 0xFF51
    const val Up = 0xFF52
    const val Right = 0xFF53
    const val Down = 0xFF54
    const val ShiftL = 0xFFE1
    const val ControlL = 0xFFE3
    const val AltL = 0xFFE9
    const val SuperL = 0xFFEB
}

/** US-layout characters the host expects to see with Shift held. */
private const val SHIFTED_SYMBOLS = "~!@#$%^&*()_+{}|:\"<>?"

internal fun charRequiresShift(ch: Char): Boolean =
    ch.isUpperCase() || SHIFTED_SYMBOLS.indexOf(ch) >= 0

/**
 * Map a character to an X11 keysym. Latin-1 maps 1:1; everything else uses the
 * Unicode keysym range (`0x01000000 + codepoint`) defined by the X protocol.
 */
internal fun charToKeysym(ch: Char): Int = when (ch) {
    '\n', '\r' -> Keysym.Return
    '\t' -> Keysym.Tab
    '\b' -> Keysym.BackSpace
    else -> if (ch.code in 0x20..0xFF) ch.code else 0x01000000 + ch.code
}

/**
 * The edit an IME made to a text field, expressed as keystrokes for the remote host.
 *
 * Soft keyboards do not emit one character per change — autocorrect, gesture typing
 * and composing regions rewrite whole words at once, and can shrink, grow, or keep
 * the length identical. Diffing on length alone (the old behaviour) mis-slices those
 * edits and sends the wrong characters. Comparing common prefix + suffix reconstructs
 * the actual edit for any of those cases.
 */
internal data class TextEdit(val backspaces: Int, val insert: String) {
    val isEmpty: Boolean get() = backspaces == 0 && insert.isEmpty()
}

internal fun diffText(old: String, new: String): TextEdit {
    if (old == new) return TextEdit(0, "")
    var prefix = 0
    val maxPrefix = minOf(old.length, new.length)
    while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++

    var suffix = 0
    val maxSuffix = minOf(old.length, new.length) - prefix
    while (
        suffix < maxSuffix &&
        old[old.length - 1 - suffix] == new[new.length - 1 - suffix]
    ) {
        suffix++
    }

    return TextEdit(
        backspaces = old.length - prefix - suffix,
        insert = new.substring(prefix, new.length - suffix),
    )
}
