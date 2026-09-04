package app.andy.terminal.rust

import app.andy.model.TerminalAppearanceSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end Phase-0 proof: known ANSI bytes cross the JNI boundary into the
 * Rust `alacritty_terminal` engine and the resulting grid state comes back.
 */
class RustTerminalEngineTest {
    @Test
    fun mapsSupportedArchitecturesToPackagedNatives() {
        assertEquals(
            "andy-terminal-engine/macos-arm64/libandy_terminal_engine.dylib",
            RustTerminalNative.resourcePath("Mac OS X", "aarch64"),
        )
        assertEquals(
            "andy-terminal-engine/macos-x86_64/libandy_terminal_engine.dylib",
            RustTerminalNative.resourcePath("Darwin", "x86_64"),
        )
        assertEquals(
            "andy-terminal-engine/linux-x86_64/libandy_terminal_engine.so",
            RustTerminalNative.resourcePath("Linux", "amd64"),
        )
        assertEquals(
            "andy-terminal-engine/linux-arm64/libandy_terminal_engine.so",
            RustTerminalNative.resourcePath("Linux", "aarch64"),
        )
        assertEquals(
            "andy-terminal-engine/windows-x86_64/andy_terminal_engine.dll",
            RustTerminalNative.resourcePath("Windows 11", "amd64"),
        )
        assertEquals(null, RustTerminalNative.resourcePath("Solaris", "amd64"))
    }

    @Test
    fun ansiSequenceProducesExpectedGridState() {
        if (!isMacArm64()) return
        assertTrue(RustTerminalNative.isAvailable())

        RustTerminalEngine(columns = 40, rows = 8).use { engine ->
            // Bold red "OK" at home, then a plain second word.
            engine.advance("\u001B[1;31mOK\u001B[0m hi")

            assertEquals("OK hi", engine.viewportText())
            assertEquals(0, engine.cursorRow())
            assertEquals(5, engine.cursorCol())

            val grid = engine.gridChars()
            assertEquals(40 * 8, grid.length)
            assertEquals('O', grid[0])
            assertEquals('K', grid[1])
            assertEquals(' ', grid[2])
            assertEquals('h', grid[3])
            assertEquals('i', grid[4])

            assertTrue(engine.cellBold(0, 0))
            assertTrue(engine.cellBold(0, 1))
            assertFalse(engine.cellBold(0, 3))
        }
    }

    @Test
    fun dec2026BuffersUntilEndAcrossJni() {
        if (!isMacArm64()) return
        RustTerminalEngine(columns = 40, rows = 5).use { engine ->
            engine.advance("\u001B[?2026hsecret")
            assertTrue(engine.syncBufferedBytes() > 0)
            assertEquals("", engine.viewportText())

            engine.advance("\u001B[?2026l")
            assertEquals(0, engine.syncBufferedBytes())
            assertEquals("secret", engine.viewportText())
        }
    }

    @Test
    fun alternateScreenRoundTrip() {
        if (!isMacArm64()) return
        RustTerminalEngine(columns = 20, rows = 5).use { engine ->
            engine.advance("main")
            assertFalse(engine.isAltScreen())

            engine.advance("\u001B[?1049h\u001B[H\u001B[2Jalt")
            assertTrue(engine.isAltScreen())
            assertEquals("alt", engine.viewportText())

            engine.advance("\u001B[?1049l")
            assertFalse(engine.isAltScreen())
            assertEquals("main", engine.viewportText())
        }
    }

    @Test
    fun fillFrameReturnsPackedColorsAndAttrs() {
        if (!isMacArm64()) return
        RustTerminalEngine(columns = 20, rows = 4).use { engine ->
            engine.advance("\u001B[1;31mHi\u001B[0m")
            val frame = RustTerminalFrame()
            assertTrue(engine.fillFrame(frame))
            assertEquals(20, frame.columns)
            assertEquals(4, frame.rows)
            assertEquals('H'.code, frame.codePoints[0])
            assertEquals('i'.code, frame.codePoints[1])
            assertTrue(frame.attrs[0].toInt() and RustTerminalAttrs.BOLD != 0)
            assertEquals(0xFFE06C75.toInt(), frame.fgArgb[0])
        }
    }

    @Test
    fun paletteAndScrollbackRoundTripAcrossJni() {
        if (!isMacArm64()) return
        RustTerminalEngine(columns = 20, rows = 3).use { engine ->
            val palette = IntArray(19) { 0xFF112233.toInt() }
            palette[0] = 0xFFEEEEEE.toInt()
            palette[1] = 0xFF101010.toInt()
            palette[3] = 0xFFFF0000.toInt() // ansi0 / black slot used as NamedColor::Black
            engine.setPalette(palette)
            engine.advance("\u001B[30mZ\u001B[0m")
            // Fill history then scroll up.
            engine.advance("\n1\n2\n3\n4\n5")
            engine.scrollDisplay(2)
            assertTrue(engine.displayOffset() >= 1)
            val frame = RustTerminalFrame()
            assertTrue(engine.fillFrame(frame))
            assertEquals(engine.displayOffset(), frame.displayOffset)
            engine.scrollToBottom()
            assertEquals(0, engine.displayOffset())
        }
    }

    @Test
    fun extractTextAcrossViewportAndHistory() {
        if (!isMacArm64()) return
        RustTerminalEngine(columns = 20, rows = 4).use { engine ->
            for (i in 0..10) {
                engine.advance("line$i\r\n")
            }
            // The terminal leaves the cursor on the next line after the final CRLF.
            // Visible viewport has line8..line10, history has line0..line7.
            assertEquals("line8", engine.extractText(0, 0, 0, 4))
            assertEquals("line8\nline9", engine.extractText(0, 0, 1, 4))
            assertEquals("line0\nline1\nline2\nline3\nline4\nline5\nline6\nline7\nline8", engine.extractText(-8, 0, 0, 4))
            // Reverse coordinates extraction
            assertEquals("line0\nline1\nline2\nline3\nline4\nline5\nline6\nline7\nline8", engine.extractText(0, 4, -8, 0))
        }
    }

    @Test
    fun mouseFlagsExposeSgrReportingAcrossJni() {
        if (!isMacArm64()) return
        RustTerminalEngine(columns = 20, rows = 3).use { engine ->
            engine.advance("\u001B[?1000h\u001B[?1006h")
            val flags = engine.mouseFlags()
            assertTrue(flags and RustMouseFlags.REPORTING != 0)
            assertTrue(flags and RustMouseFlags.SGR != 0)
        }
    }

    @Test
    fun closedEngineDegradesToNoOpsInsteadOfThrowing() {
        if (!isMacArm64()) return
        // Compose keeps dispatching scroll/paint at a terminal backend for a frame or two
        // after a chat swap closes it — those late calls must not blow up the EDT.
        val engine = RustTerminalEngine(columns = 20, rows = 4)
        engine.advance("hello")
        val frame = RustTerminalFrame()
        assertTrue(engine.fillFrame(frame))

        engine.close()
        engine.close() // idempotent

        assertTrue(engine.isClosed)
        engine.scrollDisplay(3)
        engine.scrollToBottom()
        engine.advance("more")
        engine.resize(40, 10)
        engine.stopSync()
        assertFalse(engine.fillFrame(frame))
        assertEquals(0, engine.displayOffset())
        assertEquals(0, engine.mouseFlags())
        assertEquals("", engine.viewportText())
        assertEquals("", engine.extractText(0, 0, 1, 1))
        assertFalse(engine.bracketedPasteEnabled())
        assertFalse(engine.isAltScreen())
    }

    @Test
    fun closedScrollbackReplayIgnoresLateScrollAndPaint() {
        if (!isMacArm64()) return
        val replay = RustScrollbackReplay.create(
            content = (1..200).joinToString("\n") { "line $it" },
            cols = 40,
            rows = 10,
        )
        val frame = RustTerminalFrame()
        replay.copyPaintFrame(frame)
        assertTrue(frame.rows > 0)

        replay.close()
        // Late mouse-wheel delivery at the old chat's canvas.
        replay.scrollDisplay(5)
        replay.copyPaintFrame(frame)
        assertEquals(0, replay.displayOffset())
        assertEquals("", replay.extractText(0, 0, 1, 1))
        replay.updateAppearance(TerminalAppearanceSnapshot())
    }

    private fun isMacArm64(): Boolean {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return (os.contains("mac") || os.contains("darwin")) &&
            arch in setOf("aarch64", "arm64")
    }
}
