package app.andy.terminal.rust

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

    @Test
    fun ctrlCEncodesSigintWhenAwtDeliversEtxCodePoint() {
        // Real Compose Desktop events: AWT keyChar for Ctrl+C is ETX (0x03), not 'c'.
        assertEquals(
            listOf(0x03),
            encodeTerminalKey(keyEvent(Key.C, ctrl = true, codePoint = 0x03))?.map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun ctrlDEncodesEofWhenAwtDeliversEotCodePoint() {
        assertEquals(
            listOf(0x04),
            encodeTerminalKey(keyEvent(Key.D, ctrl = true, codePoint = 0x04))?.map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun altGrPrintableSurvivesWhenAwtReportsCtrl() {
        // German AltGr+Q → '@'; AWT often exposes AltGr as Ctrl+Alt with a printable code point.
        assertEquals(
            listOf('@'.code),
            encodeTerminalKey(keyEvent(Key.Q, ctrl = true, codePoint = '@'.code))?.map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun ctrlEnterKeepsCarriageReturnWhenAwtDeliversLf() {
        assertEquals(
            listOf('\r'.code),
            encodeTerminalKey(keyEvent(Key.Enter, ctrl = true, codePoint = 0x0A))?.map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun ctrlBackspaceKeepsDeleteWhenAwtDeliversBs() {
        assertEquals(
            listOf(0x7F),
            encodeTerminalKey(keyEvent(Key.Backspace, ctrl = true, codePoint = 0x08))?.map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun modifierOnlyKeysAreNotEncoded() {
        assertNull(encodeTerminalKey(keyEvent(Key.ShiftLeft)))
        assertNull(encodeTerminalKey(keyEvent(Key.ShiftRight)))
        assertNull(encodeTerminalKey(keyEvent(Key.CtrlLeft)))
        assertNull(encodeTerminalKey(keyEvent(Key.AltLeft)))
        assertNull(encodeTerminalKey(keyEvent(Key.MetaLeft, meta = true)))
    }

    @Test
    fun awtUndefinedKeyCharIsNotEncoded() {
        assertNull(
            encodeTerminalKey(
                KeyEvent(
                    key = Key.Unknown,
                    type = KeyEventType.KeyDown,
                    codePoint = 0xFFFF,
                ),
            ),
        )
    }

    @Test
    fun formatTerminalPasteWrapsMultilineWhenBracketed() {
        val bytes = formatTerminalPaste("line1\nline2", bracketedPaste = true)
        assertEquals("\u001B[200~line1\nline2\u001B[201~", bytes.decodeToString())
    }

    @Test
    fun formatTerminalPasteLeavesSingleLineUntouched() {
        val bytes = formatTerminalPaste("hello", bracketedPaste = true)
        assertEquals("hello", bytes.decodeToString())
    }

    private fun keyEvent(
        key: Key,
        ctrl: Boolean = false,
        meta: Boolean = false,
        codePoint: Int = 0,
    ): KeyEvent = KeyEvent(
        key = key,
        type = KeyEventType.KeyDown,
        isCtrlPressed = ctrl,
        isMetaPressed = meta,
        codePoint = codePoint,
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
    fun extractSelectionRespectsDisplayOffset() {
        val frame = RustTerminalFrame().apply {
            columns = 4
            rows = 2
            displayOffset = 5
            codePoints = IntArray(8) { ' '.code }
            // row 0 corresponds to bufferLine = 0 - 5 = -5
            // row 1 corresponds to bufferLine = 1 - 5 = -4
            codePoints[0] = 'h'.code
            codePoints[1] = 'i'.code
            codePoints[4] = 'b'.code
            codePoints[5] = 'y'.code
            codePoints[6] = 'e'.code
        }
        val text = extractSelection(frame, CellRange(0, -5, 2, -4))
        assertEquals("hi\nbye", text)
    }

    @Test
    fun cellRangeContainsWorksAcrossBufferLines() {
        val range = CellRange(startCol = 5, startLine = -10, endCol = 10, endLine = 2)
        // Before start line
        assertFalse(range.contains(col = 5, line = -11))
        // On start line before startCol
        assertFalse(range.contains(col = 4, line = -10))
        // On start line at/after startCol
        assertTrue(range.contains(col = 5, line = -10))
        assertTrue(range.contains(col = 100, line = -10))
        // On middle history line
        assertTrue(range.contains(col = 0, line = -5))
        assertTrue(range.contains(col = 50, line = 0))
        // On end line before/at endCol
        assertTrue(range.contains(col = 0, line = 2))
        assertTrue(range.contains(col = 10, line = 2))
        // On end line after endCol
        assertFalse(range.contains(col = 11, line = 2))
        // After end line
        assertFalse(range.contains(col = 0, line = 3))
    }

    @Test
    fun cellRangeNormalizedHandlesReversedDrag() {
        val forward = CellRange(startCol = 2, startLine = -5, endCol = 8, endLine = 3)
        val backward = CellRange(startCol = 8, startLine = 3, endCol = 2, endLine = -5)
        assertEquals(forward, backward.normalized())
        assertTrue(backward.contains(col = 5, line = 0))
    }

    @Test
    fun palettePacksNineteenArgbSlots() {
        val palette = app.andy.model.TerminalAppearanceSnapshot().toRustPaletteArgb()
        assertEquals(19, palette.size)
        assertTrue(palette[0] ushr 24 == 0xFF)
        assertTrue(palette[1] ushr 24 == 0xFF)
    }
}
