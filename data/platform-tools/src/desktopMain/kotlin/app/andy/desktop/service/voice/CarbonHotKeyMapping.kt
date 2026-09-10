package app.andy.desktop.service.voice

import java.awt.event.KeyEvent

/**
 * Maps Compose/AWT keycodes + modifiers onto Carbon virtual keycodes and modifier masks
 * for [RegisterEventHotKey]. Unmappable keys return null so registration fails visibly.
 */
internal data class CarbonHotKey(
    val virtualKeyCode: Int,
    val carbonModifiers: Int,
)

internal object CarbonHotKeyMapping {
    // Carbon Events.h modifier bits.
    private const val CMD_KEY = 1 shl 8
    private const val SHIFT_KEY = 1 shl 9
    private const val OPTION_KEY = 1 shl 11
    private const val CONTROL_KEY = 1 shl 12

    fun fromPackedKeyCode(
        packedKeyCode: Long,
        ctrl: Boolean,
        meta: Boolean,
        alt: Boolean,
        shift: Boolean,
    ): CarbonHotKey? {
        // Compose desktop packs (location << 32) | awtKeyCode into Key.keyCode.
        val awtCode = unpackAwtKeyCode(packedKeyCode)
        val vk = awtToCarbonVirtualKey[awtCode] ?: return null
        var mods = 0
        if (meta) mods = mods or CMD_KEY
        if (shift) mods = mods or SHIFT_KEY
        if (alt) mods = mods or OPTION_KEY
        if (ctrl) mods = mods or CONTROL_KEY
        // Require at least one modifier so bare letter keys never steal typing system-wide.
        if (mods == 0) return null
        return CarbonHotKey(virtualKeyCode = vk, carbonModifiers = mods)
    }

    /** Low 32 bits are the AWT keycode (see Compose [Key.nativeKeyCode]). */
    fun unpackAwtKeyCode(packedKeyCode: Long): Int = packedKeyCode.toInt()

    /**
     * AWT [KeyEvent] VK_* → Carbon `kVK_ANSI_*` / navigation / function keys.
     * Incomplete coverage is intentional: unknown keys fail registration instead of
     * silently binding a wrong key.
     */
    private val awtToCarbonVirtualKey: Map<Int, Int> = buildMap {
        // Letters (AWT VK_A..VK_Z are ASCII).
        put(KeyEvent.VK_A, 0x00) // kVK_ANSI_A
        put(KeyEvent.VK_S, 0x01)
        put(KeyEvent.VK_D, 0x02)
        put(KeyEvent.VK_F, 0x03)
        put(KeyEvent.VK_H, 0x04)
        put(KeyEvent.VK_G, 0x05)
        put(KeyEvent.VK_Z, 0x06)
        put(KeyEvent.VK_X, 0x07)
        put(KeyEvent.VK_C, 0x08)
        put(KeyEvent.VK_V, 0x09)
        put(KeyEvent.VK_B, 0x0B)
        put(KeyEvent.VK_Q, 0x0C)
        put(KeyEvent.VK_W, 0x0D)
        put(KeyEvent.VK_E, 0x0E)
        put(KeyEvent.VK_R, 0x0F)
        put(KeyEvent.VK_Y, 0x10)
        put(KeyEvent.VK_T, 0x11)
        put(KeyEvent.VK_1, 0x12)
        put(KeyEvent.VK_2, 0x13)
        put(KeyEvent.VK_3, 0x14)
        put(KeyEvent.VK_4, 0x15)
        put(KeyEvent.VK_6, 0x16)
        put(KeyEvent.VK_5, 0x17)
        put(KeyEvent.VK_EQUALS, 0x18)
        put(KeyEvent.VK_9, 0x19)
        put(KeyEvent.VK_7, 0x1A)
        put(KeyEvent.VK_MINUS, 0x1B)
        put(KeyEvent.VK_8, 0x1C)
        put(KeyEvent.VK_0, 0x1D)
        put(KeyEvent.VK_CLOSE_BRACKET, 0x1E)
        put(KeyEvent.VK_O, 0x1F)
        put(KeyEvent.VK_U, 0x20)
        put(KeyEvent.VK_OPEN_BRACKET, 0x21)
        put(KeyEvent.VK_I, 0x22)
        put(KeyEvent.VK_P, 0x23)
        put(KeyEvent.VK_L, 0x25)
        put(KeyEvent.VK_J, 0x26)
        put(KeyEvent.VK_QUOTE, 0x27)
        put(KeyEvent.VK_K, 0x28)
        put(KeyEvent.VK_SEMICOLON, 0x29)
        put(KeyEvent.VK_BACK_SLASH, 0x2A)
        put(KeyEvent.VK_COMMA, 0x2B)
        put(KeyEvent.VK_SLASH, 0x2C)
        put(KeyEvent.VK_N, 0x2D)
        put(KeyEvent.VK_M, 0x2E)
        put(KeyEvent.VK_PERIOD, 0x2F)
        put(KeyEvent.VK_SPACE, 0x31)
        put(KeyEvent.VK_BACK_QUOTE, 0x32)
        put(KeyEvent.VK_BACK_SPACE, 0x33) // kVK_Delete
        put(KeyEvent.VK_ESCAPE, 0x35)
        put(KeyEvent.VK_ENTER, 0x24) // kVK_Return
        put(KeyEvent.VK_TAB, 0x30)
        put(KeyEvent.VK_LEFT, 0x7B)
        put(KeyEvent.VK_RIGHT, 0x7C)
        put(KeyEvent.VK_DOWN, 0x7D)
        put(KeyEvent.VK_UP, 0x7E)
        put(KeyEvent.VK_F1, 0x7A)
        put(KeyEvent.VK_F2, 0x78)
        put(KeyEvent.VK_F3, 0x63)
        put(KeyEvent.VK_F4, 0x76)
        put(KeyEvent.VK_F5, 0x60)
        put(KeyEvent.VK_F6, 0x61)
        put(KeyEvent.VK_F7, 0x62)
        put(KeyEvent.VK_F8, 0x64)
        put(KeyEvent.VK_F9, 0x65)
        put(KeyEvent.VK_F10, 0x6D)
        put(KeyEvent.VK_F11, 0x67)
        put(KeyEvent.VK_F12, 0x6F)
        put(KeyEvent.VK_DELETE, 0x75) // forward delete
        put(KeyEvent.VK_HOME, 0x73)
        put(KeyEvent.VK_END, 0x77)
        put(KeyEvent.VK_PAGE_UP, 0x74)
        put(KeyEvent.VK_PAGE_DOWN, 0x79)
    }
}
