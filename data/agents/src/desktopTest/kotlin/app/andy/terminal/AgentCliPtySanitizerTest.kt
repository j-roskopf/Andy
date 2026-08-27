package app.andy.terminal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun doesNotBakeReplacementCharsForIncompleteUtf8BesideShowCursor() {
        // Old String round-trip decoded E2 9C (incomplete ✨) as U+FFFD whenever 25h was
        // present, then re-encoded the diamond (EF BF BD) into the live PTY stream.
        val sparkle = "✨".encodeToByteArray()
        val csi = "\u001b[?25h".encodeToByteArray()
        val raw = csi + sparkle.copyOf(2)
        val (sanitized, offset, length) = sanitizeAgentCliPtyChunk(raw, 0, raw.size)
        val out = sanitized.copyOfRange(offset, offset + length)
        assertContentEquals(sparkle.copyOf(2), out)
        assertFalse(
            out.toList() == listOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte()),
            "sanitizer must pass through partial UTF-8 bytes, not emit U+FFFD",
        )
    }

    @Test
    fun preservesCompleteMultibyteGlyphsAroundShowCursor() {
        val raw = ("✨working\u001b[?25h✨").encodeToByteArray()
        val (sanitized, offset, length) = sanitizeAgentCliPtyChunk(raw, 0, raw.size)
        assertEquals("✨working✨", sanitized.decodeToString(offset, offset + length))
    }
}
