package app.andy.desktop.service.voice

import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CarbonHotKeyMappingTest {
    @Test
    fun mapsCmdShiftA() {
        // Compose packs location in the high int and AWT code in the low int.
        val packed = (0L shl 32) or (KeyEvent.VK_A.toLong() and 0xFFFFFFFFL)
        val mapped = CarbonHotKeyMapping.fromPackedKeyCode(
            packedKeyCode = packed,
            ctrl = false,
            meta = true,
            alt = false,
            shift = true,
        )
        assertNotNull(mapped)
        assertEquals(0x00, mapped.virtualKeyCode) // kVK_ANSI_A
        // cmdKey | shiftKey
        assertEquals((1 shl 8) or (1 shl 9), mapped.carbonModifiers)
    }

    @Test
    fun rejectsBareLetterWithoutModifiers() {
        val packed = KeyEvent.VK_M.toLong()
        assertNull(
            CarbonHotKeyMapping.fromPackedKeyCode(
                packedKeyCode = packed,
                ctrl = false,
                meta = false,
                alt = false,
                shift = false,
            ),
        )
    }

    @Test
    fun rejectsUnmappedKey() {
        assertNull(
            CarbonHotKeyMapping.fromPackedKeyCode(
                packedKeyCode = KeyEvent.VK_ACCEPT.toLong(),
                ctrl = true,
                meta = false,
                alt = false,
                shift = false,
            ),
        )
    }
}
