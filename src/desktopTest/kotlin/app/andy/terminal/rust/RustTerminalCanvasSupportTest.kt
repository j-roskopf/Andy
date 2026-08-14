package app.andy.terminal.rust

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RustTerminalCanvasSupportTest {
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
            chars = CharArray(12) { ' ' }
            chars[0] = 'a'
            chars[1] = 'b'
            chars[2] = ' '
            chars[6] = 'c'
            chars[7] = 'd'
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
