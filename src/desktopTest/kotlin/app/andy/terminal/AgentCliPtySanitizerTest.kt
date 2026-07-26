package app.andy.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AgentCliPtySanitizerTest {
    @Test
    fun stripsShowCursorCsiButKeepsHide() {
        val raw = "\u001b[?25l\r\u001b[2A\u001b[0Gworking\u001b[?25h\u001b[?25l".encodeToByteArray()
        val (sanitized, offset, length) = sanitizeAgentCliPtyChunk(raw, 0, raw.size)
        assertEquals(0, offset)
        val text = sanitized.decodeToString(offset, offset + length)
        assertFalse(text.contains("?25h"))
        assertEquals("\u001b[?25l\r\u001b[2A\u001b[0Gworking\u001b[?25l", text)
    }

    @Test
    fun leavesCleanChunksUntouched() {
        val raw = "plain agent output\n".encodeToByteArray()
        val (sanitized, offset, length) = sanitizeAgentCliPtyChunk(raw, 0, raw.size)
        assertEquals(raw, sanitized)
        assertEquals(0, offset)
        assertEquals(raw.size, length)
    }
}
