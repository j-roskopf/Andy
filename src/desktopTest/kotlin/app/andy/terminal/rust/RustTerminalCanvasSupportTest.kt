package app.andy.terminal.rust

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class)
class RustTerminalCanvasSupportTest {
    @Test
    fun metaChordsAreNotEncodedAsTerminalInput() {
        assertNull(encodeTerminalKey(keyEvent(Key.V, meta = true)))
        assertNull(encodeTerminalKey(keyEvent(Key.C, meta = true)))
    }

    @Test
    fun ctrlVPasteChordIsRecognized() {
        assertTrue(isTerminalPasteChord(keyEvent(Key.V, ctrl = true)))
        assertTrue(isTerminalPasteChord(keyEvent(Key.V, meta = true)))
    }

    @Test
    fun ctrlCEncodesSigintWhenNotMeta() {
        assertEquals(listOf(0x03), encodeTerminalKey(keyEvent(Key.C, ctrl = true))?.map { it.toInt() and 0xFF })
    }

    private fun keyEvent(
        key: Key,
        ctrl: Boolean = false,
        meta: Boolean = false,
    ): KeyEvent = KeyEvent(
        key = key,
        type = KeyEventType.KeyDown,
        isCtrlPressed = ctrl,
        isMetaPressed = meta,
    )

    @Test
    fun sgrMouseReportsUseOneBasedCells() {
        val press = RustTerminalMouse.encodeClick(
            flags = RustMouseFlags.REPORTING or RustMouseFlags.SGR,
            button = RustTerminalMouse.BUTTON_LEFT,
            col = 0,
            row = 2,
            pressed = true,
        )
        assertEquals("\u001B[<0;1;3M", press?.decodeToString())

        val release = RustTerminalMouse.encodeClick(
            flags = RustMouseFlags.REPORTING or RustMouseFlags.SGR,
            button = RustTerminalMouse.BUTTON_LEFT,
            col = 0,
            row = 2,
            pressed = false,
        )
        assertEquals("\u001B[<0;1;3m", release?.decodeToString())
    }

    @Test
    fun mouseReportsRequireReportingFlag() {
        assertNull(
            RustTerminalMouse.encodeClick(
                flags = 0,
                button = RustTerminalMouse.BUTTON_LEFT,
                col = 1,
                row = 1,
                pressed = true,
            ),
        )
    }

    @Test
    fun extractSelectionJoinsRowsAndTrimsTrailingSpaces() {
        val frame = RustTerminalFrame().apply {
            columns = 6
            rows = 2
            codePoints = IntArray(12) { ' '.code }
            codePoints[0] = 'a'.code
            codePoints[1] = 'b'.code
            codePoints[2] = ' '.code
            codePoints[6] = 'c'.code
            codePoints[7] = 'd'.code
        }
        val text = extractSelection(frame, CellRange(0, 0, 1, 1))
        assertEquals("ab\ncd", text)
    }

    @Test
    fun palettePacksNineteenArgbSlots() {
        val palette = app.andy.model.TerminalAppearanceSnapshot().toRustPaletteArgb()
        assertEquals(19, palette.size)
        assertTrue(palette[0] ushr 24 == 0xFF)
        assertTrue(palette[1] ushr 24 == 0xFF)
    }
}
