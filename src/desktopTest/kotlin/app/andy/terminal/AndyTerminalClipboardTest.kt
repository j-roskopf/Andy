package app.andy.terminal

import java.awt.Component
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndyTerminalClipboardTest {
    private val isMac = System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)

    @Test
    fun pasteShortcutMatchesPlatformConvention() {
        if (isMac) {
            assertTrue(isTerminalPasteShortcut(key(KeyEvent.VK_V, meta = true)))
            assertFalse(isTerminalPasteShortcut(key(KeyEvent.VK_V, ctrl = true)))
            assertFalse(isTerminalPasteShortcut(key(KeyEvent.VK_V, meta = true, shift = true)))
        } else {
            assertTrue(isTerminalPasteShortcut(key(KeyEvent.VK_V, ctrl = true)))
            assertTrue(isTerminalPasteShortcut(key(KeyEvent.VK_V, ctrl = true, shift = true)))
            assertFalse(isTerminalPasteShortcut(key(KeyEvent.VK_V, meta = true)))
        }
    }

    @Test
    fun copyShortcutMatchesPlatformConvention() {
        if (isMac) {
            assertTrue(isTerminalCopyShortcut(key(KeyEvent.VK_C, meta = true)))
            assertFalse(isTerminalCopyShortcut(key(KeyEvent.VK_C, ctrl = true)))
        } else {
            assertTrue(isTerminalCopyShortcut(key(KeyEvent.VK_C, ctrl = true, shift = true)))
            assertFalse(isTerminalCopyShortcut(key(KeyEvent.VK_C, ctrl = true)))
        }
    }

    @Test
    fun selectAllShortcutMatchesPlatformConvention() {
        if (isMac) {
            assertTrue(isTerminalSelectAllShortcut(key(KeyEvent.VK_A, meta = true)))
        } else {
            assertTrue(isTerminalSelectAllShortcut(key(KeyEvent.VK_A, ctrl = true, shift = true)))
            assertFalse(isTerminalSelectAllShortcut(key(KeyEvent.VK_A, ctrl = true)))
        }
    }

    @Test
    fun scrollbackHostKeyHandlerConsumesOrdinaryTyping() {
        val handler = andyScrollbackSwingHostServices().hostKeyHandler
        assertTrue(handler.handleKeyPressed(key(KeyEvent.VK_A)))
        assertTrue(handler.handleKeyPressed(key(KeyEvent.VK_ENTER)))
        assertTrue(handler.handleKeyPressed(key(KeyEvent.VK_V, meta = isMac, ctrl = !isMac)))
    }

    private fun key(
        keyCode: Int,
        ctrl: Boolean = false,
        meta: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
    ): KeyEvent {
        var mods = 0
        if (ctrl) mods = mods or KeyEvent.CTRL_DOWN_MASK
        if (meta) mods = mods or KeyEvent.META_DOWN_MASK
        if (shift) mods = mods or KeyEvent.SHIFT_DOWN_MASK
        if (alt) mods = mods or KeyEvent.ALT_DOWN_MASK
        return KeyEvent(
            object : Component() {},
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            mods,
            keyCode,
            KeyEvent.CHAR_UNDEFINED,
        )
    }
}
