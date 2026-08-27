package app.andy.desktop.service.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosHidKeyboardTest {
    @Test
    fun mapsCommonAndroidKeyCodesToHidUsages() {
        assertEquals(0x28, androidKeyCodeToIosHidUsage(66))
        assertEquals(0x2A, androidKeyCodeToIosHidUsage(67))
        assertEquals(0x2B, androidKeyCodeToIosHidUsage(61))
        assertEquals(0x52, androidKeyCodeToIosHidUsage(19))
        assertEquals(0x4F, androidKeyCodeToIosHidUsage(22))
    }

    @Test
    fun ignoresUnsupportedAndroidKeyCodes() {
        assertNull(androidKeyCodeToIosHidUsage(24))
    }
}
