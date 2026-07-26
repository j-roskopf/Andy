package app.andy.terminal

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tee parses OSC incrementally as PTY bytes arrive rather than rescanning the whole
 * scrollback on a timer, so these cover what a full-buffer rescan used to get for free:
 * sequences split across reads, and state surviving a buffer trim.
 */
class ScrollbackAnsiTeeOscTest {
    private fun ScrollbackAnsiTee.feed(text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        append(bytes, 0, bytes.size)
    }

    @Test
    fun tracksLatestTitleAndProgressAcrossAppends() {
        val tee = ScrollbackAnsiTee()
        tee.feed("\u001B]0;first\u0007output line\n")
        tee.feed("\u001B]2;second\u001B\\more output\n")
        tee.feed("\u001B]9;4;3\u0007")

        assertEquals("second", tee.latestOscTitle())
        assertEquals("4;3", tee.latestOscProgress())
    }

    @Test
    fun parsesSequenceSplitAcrossChunkBoundary() {
        val tee = ScrollbackAnsiTee()
        // Sequence arrives one character per read, the worst case for a PTY stream.
        for (ch in "\u001B]2;split title\u0007") tee.feed(ch.toString())

        assertEquals("split title", tee.latestOscTitle())
    }

    @Test
    fun parsesProgressSplitAcrossChunkBoundary() {
        val tee = ScrollbackAnsiTee()
        tee.feed("noise\u001B]9;4")
        tee.feed(";7\u0007trailing")

        assertEquals("4;7", tee.latestOscProgress())
    }

    @Test
    fun ignoresUnrelatedOscNinePayload() {
        val tee = ScrollbackAnsiTee()
        tee.feed("\u001B]9;4;2\u0007")
        tee.feed("\u001B]9;Hello\u0007")

        assertEquals("", tee.latestOscProgress())
    }

    @Test
    fun retainsOscStateAfterBufferTrim() {
        val tee = ScrollbackAnsiTee(maxBytes = 4096)
        tee.feed("\u001B]2;early title\u0007")
        // Read once so the sequence is scanned before the buffer drops it.
        assertEquals("early title", tee.latestOscTitle())

        // Push far enough past the cap (plus trim slack) to force a real trim.
        repeat(200) { tee.feed("filler line ".repeat(64) + "\n") }

        assertTrue(tee.snapshot().length < 100_000, "buffer should be trimmed toward its cap")
        assertEquals("early title", tee.latestOscTitle())
    }

    @Test
    fun clearResetsOscState() {
        val tee = ScrollbackAnsiTee()
        tee.feed("\u001B]2;title\u0007\u001B]9;4;1\u0007")
        tee.clear()

        assertEquals("", tee.latestOscTitle())
        assertEquals("", tee.latestOscProgress())
    }

    @Test
    fun keepsAppendedBytesInSnapshot() {
        val tee = ScrollbackAnsiTee()
        tee.feed("hello ")
        tee.feed("world")

        assertEquals("hello world", tee.snapshot())
    }
}
