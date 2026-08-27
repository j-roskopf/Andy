package app.andy.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class OscSequencesTest {
    @Test
    fun extractsLatestOsc0And2Title() {
        val raw = "\u001B]0;first\u0007noise\u001B]2;final title\u001B\\"
        assertEquals("final title", extractLatestOscTitle(raw))
    }

    @Test
    fun extractsConEmuProgressAsHerdrPayload() {
        val raw = "\u001B]9;4;1\u0007mid\u001B]9;4;0\u0007"
        assertEquals("4;0", extractLatestOscProgress(raw))
    }

    @Test
    fun ignoresUnrelatedOscNinePayloads() {
        assertEquals("", extractLatestOscProgress("\u001B]9;Hello\u0007"))
    }
}
