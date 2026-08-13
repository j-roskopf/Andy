package app.andy.terminal.rust

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
    fun mapsSupportedMacArchitecturesToPackagedDylib() {
        assertEquals(
            "andy-terminal-engine/macos-arm64/libandy_terminal_engine.dylib",
            RustTerminalNative.resourcePath("Mac OS X", "aarch64"),
        )
        assertEquals(
            "andy-terminal-engine/macos-x86_64/libandy_terminal_engine.dylib",
            RustTerminalNative.resourcePath("Darwin", "x86_64"),
        )
        assertEquals(null, RustTerminalNative.resourcePath("Windows 11", "amd64"))
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

    private fun isMacArm64(): Boolean {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return (os.contains("mac") || os.contains("darwin")) &&
            arch in setOf("aarch64", "arm64")
    }
}
