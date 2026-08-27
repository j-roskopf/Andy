package app.andy

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformTextTest {
    @Test
    fun formatDecimalDoesNotRenderNegativeZero() {
        assertEquals("0.0", formatDecimal(-0.01, 1))
        assertEquals("0", formatDecimal(-0.01, 0))
        assertEquals("-0.2", formatDecimal(-0.16, 1))
    }
}
