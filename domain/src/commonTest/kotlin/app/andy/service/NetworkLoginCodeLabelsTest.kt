package app.andy.service

import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkLoginCodeLabelsTest {
    @Test
    fun countdownLabelFormatsRemainingTime() {
        assertEquals("45s", networkLoginCodeCountdownLabel(45_500))
        assertEquals("1:05", networkLoginCodeCountdownLabel(65_000))
        assertEquals("0s", networkLoginCodeCountdownLabel(0))
        assertEquals("0s", networkLoginCodeCountdownLabel(-1_000))
    }
}
