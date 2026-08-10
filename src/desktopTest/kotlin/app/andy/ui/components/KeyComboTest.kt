package app.andy.ui.components

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class)
class KeyComboTest {
    @Test
    fun encodeDecodeRoundTripsMetaE() {
        val original = KeyCombo(Key.E, meta = true)
        val restored = KeyCombo.decode(original.encode())
        assertEquals(original, restored)
        assertEquals("Cmd+E", restored!!.label())
    }

    @Test
    fun matchesRequiresExactModifiers() {
        val combo = KeyCombo(Key.E, meta = true)
        assertTrue(
            combo.matches(
                KeyEvent(key = Key.E, type = KeyEventType.KeyDown, isMetaPressed = true),
            ),
        )
        assertFalse(
            combo.matches(
                KeyEvent(key = Key.E, type = KeyEventType.KeyDown, isCtrlPressed = true),
            ),
        )
        assertFalse(
            combo.matches(
                KeyEvent(key = Key.E, type = KeyEventType.KeyDown, isMetaPressed = true, isShiftPressed = true),
            ),
        )
        assertFalse(
            combo.matches(
                KeyEvent(key = Key.D, type = KeyEventType.KeyDown, isMetaPressed = true),
            ),
        )
    }

    @Test
    fun fromKeyDownIgnoresBareModifiers() {
        assertNull(
            KeyCombo.fromKeyDown(
                KeyEvent(key = Key.MetaLeft, type = KeyEventType.KeyDown, isMetaPressed = true),
            ),
        )
        assertEquals(
            KeyCombo(Key.E, meta = true),
            KeyCombo.fromKeyDown(
                KeyEvent(key = Key.E, type = KeyEventType.KeyDown, isMetaPressed = true),
            ),
        )
    }

    @Test
    fun decodeRejectsGarbage() {
        assertNull(KeyCombo.decode(null))
        assertNull(KeyCombo.decode(""))
        assertNull(KeyCombo.decode("not-a-combo"))
        assertNull(KeyCombo.decode("1|0|1"))
    }
}
