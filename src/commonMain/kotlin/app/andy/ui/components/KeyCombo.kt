package app.andy.ui.components

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key

/** A recordable keyboard shortcut, persisted via [encode]/[decode] as a plain string. */
internal data class KeyCombo(
    val key: Key,
    val ctrl: Boolean = false,
    val meta: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
) {
    fun matches(event: KeyEvent): Boolean =
        event.key == key &&
            event.isCtrlPressed == ctrl &&
            event.isMetaPressed == meta &&
            event.isAltPressed == alt &&
            event.isShiftPressed == shift

    fun encode(): String = listOf(
        key.keyCode.toString(),
        if (ctrl) "1" else "0",
        if (meta) "1" else "0",
        if (alt) "1" else "0",
        if (shift) "1" else "0",
    ).joinToString("|")

    fun label(): String = buildString {
        if (ctrl) append("Ctrl+")
        if (alt) append("Alt+")
        if (shift) append("Shift+")
        if (meta) append("Cmd+")
        append(keyLabel(key))
    }

    companion object {
        fun decode(raw: String?): KeyCombo? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split("|")
            if (parts.size != 5) return null
            val code = parts[0].toLongOrNull() ?: return null
            return KeyCombo(
                key = Key(code),
                ctrl = parts[1] == "1",
                meta = parts[2] == "1",
                alt = parts[3] == "1",
                shift = parts[4] == "1",
            )
        }

        private val modifierOnlyKeys = setOf(
            Key.CtrlLeft, Key.CtrlRight,
            Key.AltLeft, Key.AltRight,
            Key.ShiftLeft, Key.ShiftRight,
            Key.MetaLeft, Key.MetaRight,
        )

        /** Builds a combo from a key-down event, or null if only modifier keys were pressed. */
        fun fromKeyDown(event: KeyEvent): KeyCombo? {
            if (event.key in modifierOnlyKeys) return null
            return KeyCombo(
                key = event.key,
                ctrl = event.isCtrlPressed,
                meta = event.isMetaPressed,
                alt = event.isAltPressed,
                shift = event.isShiftPressed,
            )
        }
    }
}

private val namedKeyLabels: Map<Key, String> = buildMap {
    put(Key.Spacebar, "Space")
    put(Key.Enter, "Enter")
    put(Key.NumPadEnter, "Enter")
    put(Key.Tab, "Tab")
    put(Key.Escape, "Esc")
    put(Key.Backspace, "Backspace")
    put(Key.Delete, "Delete")
    put(Key.DirectionUp, "Up")
    put(Key.DirectionDown, "Down")
    put(Key.DirectionLeft, "Left")
    put(Key.DirectionRight, "Right")
    val letters = listOf(
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
    )
    letters.forEachIndexed { index, key -> put(key, ('A' + index).toString()) }
    val digits = listOf(
        Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
    )
    digits.forEachIndexed { index, key -> put(key, index.toString()) }
    val functionKeys = listOf(
        Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12,
    )
    functionKeys.forEachIndexed { index, key -> put(key, "F${index + 1}") }
}

private fun keyLabel(key: Key): String = namedKeyLabels[key] ?: "Key ${key.keyCode}"
